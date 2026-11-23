use axum::{
    extract::{Path, Query, Request, State},
    http::{header, HeaderName, Method, StatusCode},
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, HashMap},
    io::{Read, Seek, SeekFrom, Write},
    path::PathBuf,
    sync::Arc,
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tokio::{
    process::Command,
    sync::{watch, Mutex, Notify},
};
use tower_http::cors::{Any, CorsLayer};

const VERSION: &str = env!("CARGO_PKG_VERSION");
const REPO: &str = "veelvoer/agents";
const KEEP_DAYS: usize = 60;
const SHOW_DAYS: usize = 14;

const MODELS: &[(&str, &str)] = &[
    ("claude-fable-5-1", "Fable 5.1"),
    ("claude-opus-5-5", "Opus 5.5"),
    ("claude-sonnet-5-5", "Sonnet 5.5"),
    ("claude-haiku-5-5", "Haiku 5.5"),
    ("claude-haiku-4-5-20251001", "Haiku 4.5"),
    ("claude-opus-4-1", "Opus 4.1"),
    ("claude-sonnet-4-5", "Sonnet 4.5"),
    ("claude-3-7-sonnet-latest", "Sonnet 3.7"),
    ("claude-3-5-haiku-latest", "Haiku 3.5"),
];

#[derive(Clone, Serialize, Deserialize)]
struct Spec {
    name: String,
    model: String,
    #[serde(default)]
    prompt: String,
    #[serde(default)]
    subagents: bool,
    #[serde(default)]
    mcp_config: Option<String>,
    #[serde(default)]
    memory: String,
}

#[derive(Clone, Default, Serialize, Deserialize)]
struct Usage { input_tokens: u64, output_tokens: u64, cache_read_tokens: u64, cost_usd: f64 }

#[derive(Clone, Default, Serialize, Deserialize)]
struct Day { cost: f64, tokens: u64 }

#[derive(Clone, Serialize, Deserialize)]
struct Msg {
    role: String,
    text: String,
    #[serde(default)]
    at: u64,
}

#[derive(Clone, Serialize, Deserialize)]
struct Agent {
    id: u64,
    spec: Spec,
    #[serde(default)]
    usage: Usage,
    #[serde(default)]
    session: Option<String>,
    #[serde(default)]
    pinned: bool,
    #[serde(default)]
    order: u64,
    #[serde(default)]
    last_active: u64,
    #[serde(default)]
    daily: BTreeMap<String, Day>,
    /// Only read from old files (0.2.x); chats now live in chats/<id>.jsonl.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    messages: Vec<Msg>,
    #[serde(skip)]
    running: bool,
}

#[derive(Serialize)]
struct Info {
    id: u64,
    spec: Spec,
    status: &'static str,
    usage: Usage,
    pinned: bool,
    order: u64,
    last_active: u64,
    messages: usize,
    daily: Vec<(String, Day)>,
}

#[derive(Default, Serialize, Deserialize)]
struct Db { agents: Vec<Agent> }

#[derive(Default)]
struct Rt {
    /// Byte offset of every line of every chat file, so a page of messages is one seek, however long the chat gets.
    offsets: HashMap<u64, Vec<u64>>,
    cancel: HashMap<u64, Arc<Notify>>,
}

#[derive(Clone)]
struct S {
    db: Arc<Mutex<Db>>,
    rt: Arc<Mutex<Rt>>,
    rev: Arc<watch::Sender<u64>>,
    token: Arc<String>,
    file: Arc<PathBuf>,
}

fn now() -> u64 { SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_secs()) }

fn day_of(secs: u64) -> String {
    let z = (secs / 86400) as i64 + 719468;
    let era = z.div_euclid(146097);
    let doe = z.rem_euclid(146097);
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    format!("{:04}-{:02}-{:02}", if m <= 2 { y + 1 } else { y }, m, d)
}

fn save(db: &Db, file: &PathBuf) {
    if let Ok(j) = serde_json::to_vec(db) {
        let tmp = file.with_extension("tmp");
        if std::fs::write(&tmp, j).is_ok() { let _ = std::fs::rename(tmp, file); }
    }
}

fn chat_path(s: &S, id: u64) -> PathBuf {
    s.file.parent().unwrap_or(std::path::Path::new(".")).join("chats").join(format!("{id}.jsonl"))
}

fn scan_offsets(path: &PathBuf) -> Vec<u64> {
    let mut v = vec![];
    if let Ok(b) = std::fs::read(path) {
        let mut pos = 0u64;
        for line in b.split_inclusive(|c| *c == b'\n') {
            if line.len() > 1 { v.push(pos); }
            pos += line.len() as u64;
        }
    }
    v
}

async fn append_msg(s: &S, id: u64, role: &str, text: &str) {
    let path = chat_path(s, id);
    let _ = std::fs::create_dir_all(path.parent().unwrap());
    let line = serde_json::to_string(&Msg { role: role.into(), text: text.into(), at: now() }).unwrap_or_default() + "\n";
    let mut rt = s.rt.lock().await;
    let off = rt.offsets.entry(id).or_insert_with(|| scan_offsets(&path));
    let pos = std::fs::metadata(&path).map_or(0, |m| m.len());
    if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(&path) {
        if f.write_all(line.as_bytes()).is_ok() { off.push(pos); }
    }
}

fn bump(s: &S) { s.rev.send_modify(|r| *r += 1); }

fn info(a: &Agent, msgs: usize) -> Info {
    Info {
        id: a.id, spec: a.spec.clone(), status: if a.running { "running" } else { "idle" }, usage: a.usage.clone(),
        pinned: a.pinned, order: a.order, last_active: a.last_active, messages: msgs,
        daily: a.daily.iter().rev().take(SHOW_DAYS).rev().map(|(k, v)| (k.clone(), v.clone())).collect(),
    }
}

async fn auth(State(s): State<S>, req: Request, next: Next) -> Result<Response, StatusCode> {
    if req.method() == Method::OPTIONS || req.uri().path() == "/health" { return Ok(next.run(req).await); }
    let ok = req.headers().get(header::AUTHORIZATION).and_then(|h| h.to_str().ok())
        .is_some_and(|h| h.strip_prefix("Bearer ") == Some(s.token.as_str()));
    if ok { Ok(next.run(req).await) } else { Err(StatusCode::UNAUTHORIZED) }
}

async fn health() -> Json<serde_json::Value> {
    Json(serde_json::json!({"ok": true, "name": "agents-server", "version": VERSION}))
}
async fn models() -> Json<Vec<(&'static str, &'static str)>> { Json(MODELS.to_vec()) }

#[derive(Deserialize)]
struct ListQ { since: Option<u64> }

/// Long poll: with ?since=<rev> the answer waits (up to 25 s) until something changed, so apps see updates at once and idle apps cost nothing.
async fn list(State(s): State<S>, Query(q): Query<ListQ>) -> Json<serde_json::Value> {
    let mut rx = s.rev.subscribe();
    if let Some(since) = q.since {
        if *rx.borrow() == since { let _ = tokio::time::timeout(Duration::from_secs(25), rx.changed()).await; }
    }
    let rev = *rx.borrow();
    let counts: HashMap<u64, usize> = s.rt.lock().await.offsets.iter().map(|(k, v)| (*k, v.len())).collect();
    let g = s.db.lock().await;
    let mut v: Vec<&Agent> = g.agents.iter().collect();
    v.sort_by_key(|a| a.order);
    Json(serde_json::json!({"rev": rev, "agents": v.into_iter().map(|a| info(a, counts.get(&a.id).copied().unwrap_or(0))).collect::<Vec<_>>()}))
}

async fn create(State(s): State<S>, Json(spec): Json<Spec>) -> Json<u64> {
    let mut g = s.db.lock().await;
    let id = g.agents.iter().map(|a| a.id).max().map_or(1, |m| m + 1);
    let order = g.agents.iter().map(|a| a.order).max().map_or(0, |m| m + 1);
    g.agents.push(Agent { id, spec, usage: Usage::default(), session: None, pinned: false, order, last_active: now(), daily: BTreeMap::new(), messages: vec![], running: false });
    save(&g, &s.file);
    drop(g);
    bump(&s);
    Json(id)
}

async fn update(State(s): State<S>, Path(id): Path<u64>, Json(spec): Json<Spec>) -> StatusCode {
    let mut g = s.db.lock().await;
    match g.agents.iter_mut().find(|a| a.id == id) {
        Some(a) => { a.spec = spec; save(&g, &s.file); drop(g); bump(&s); StatusCode::OK }
        None => StatusCode::NOT_FOUND,
    }
}

#[derive(Deserialize)]
struct PinReq { pinned: bool }

async fn pin(State(s): State<S>, Path(id): Path<u64>, Json(r): Json<PinReq>) -> StatusCode {
    let mut g = s.db.lock().await;
    match g.agents.iter_mut().find(|a| a.id == id) {
        Some(a) => { a.pinned = r.pinned; save(&g, &s.file); drop(g); bump(&s); StatusCode::OK }
        None => StatusCode::NOT_FOUND,
    }
}

async fn reorder(State(s): State<S>, Json(ids): Json<Vec<u64>>) -> StatusCode {
    let mut g = s.db.lock().await;
    for (i, id) in ids.iter().enumerate() { if let Some(a) = g.agents.iter_mut().find(|a| a.id == *id) { a.order = i as u64; } }
    save(&g, &s.file);
    drop(g);
    bump(&s);
    StatusCode::OK
}

async fn delete(State(s): State<S>, Path(id): Path<u64>) -> StatusCode {
    if let Some(n) = s.rt.lock().await.cancel.get(&id) { n.notify_one(); }
    let mut g = s.db.lock().await;
    g.agents.retain(|a| a.id != id);
    save(&g, &s.file);
    drop(g);
    s.rt.lock().await.offsets.remove(&id);
    let _ = std::fs::remove_file(chat_path(&s, id));
    bump(&s);
    StatusCode::OK
}

#[derive(Deserialize)]
struct PageQ { before: Option<usize>, limit: Option<usize> }

async fn messages(State(s): State<S>, Path(id): Path<u64>, Query(q): Query<PageQ>) -> Result<Json<serde_json::Value>, StatusCode> {
    if !s.db.lock().await.agents.iter().any(|a| a.id == id) { return Err(StatusCode::NOT_FOUND); }
    let path = chat_path(&s, id);
    let mut rt = s.rt.lock().await;
    let off = rt.offsets.entry(id).or_insert_with(|| scan_offsets(&path));
    let total = off.len();
    let end = q.before.unwrap_or(total).min(total);
    let start = end.saturating_sub(q.limit.unwrap_or(100).clamp(1, 500));
    let mut out = vec![];
    if end > start {
        let from = off[start];
        let to = if end < total { off[end] } else { std::fs::metadata(&path).map_or(from, |m| m.len()) };
        if let Ok(mut f) = std::fs::File::open(&path) {
            let mut buf = vec![0u8; (to - from) as usize];
            if f.seek(SeekFrom::Start(from)).is_ok() && f.read_exact(&mut buf).is_ok() {
                for line in buf.split(|c| *c == b'\n') {
                    if let Ok(m) = serde_json::from_slice::<Msg>(line) { out.push(m); }
                }
            }
        }
    }
    Ok(Json(serde_json::json!({"start": start, "total": total, "messages": out})))
}

async fn clear(State(s): State<S>, Path(id): Path<u64>) -> StatusCode {
    let mut g = s.db.lock().await;
    match g.agents.iter_mut().find(|a| a.id == id) {
        Some(a) => {
            a.session = None;
            save(&g, &s.file);
            drop(g);
            s.rt.lock().await.offsets.insert(id, vec![]);
            let _ = std::fs::remove_file(chat_path(&s, id));
            bump(&s);
            StatusCode::OK
        }
        None => StatusCode::NOT_FOUND,
    }
}

async fn run_agent(s: &S, id: u64, msg: &str) -> Result<String, (StatusCode, String)> {
    let (spec, session, cancel) = {
        let mut g = s.db.lock().await;
        let a = g.agents.iter_mut().find(|a| a.id == id).ok_or((StatusCode::NOT_FOUND, "no such agent".into()))?;
        if a.running { return Err((StatusCode::CONFLICT, "this agent is still working on the last message".into())); }
        a.running = true;
        a.last_active = now();
        let cancel = Arc::new(Notify::new());
        s.rt.lock().await.cancel.insert(id, cancel.clone());
        (a.spec.clone(), a.session.clone(), cancel)
    };
    append_msg(s, id, "user", msg).await;
    bump(s);
    let mut prompt = spec.prompt.clone();
    if !spec.memory.is_empty() { prompt.push_str("\n\nMemory:\n"); prompt.push_str(&spec.memory); }
    let mut c = Command::new("claude");
    c.args(["-p", msg, "--output-format", "json", "--model", &spec.model]);
    if !prompt.is_empty() { c.args(["--append-system-prompt", &prompt]); }
    if !spec.subagents { c.args(["--disallowedTools", "Task"]); }
    if let Some(m) = spec.mcp_config.as_deref().filter(|m| !m.is_empty()) { c.args(["--mcp-config", m]); }
    if let Some(x) = &session { c.args(["--resume", x]); }
    c.kill_on_drop(true);
    let out = tokio::select! {
        r = tokio::time::timeout(Duration::from_secs(900), c.output()) => Some(r),
        _ = cancel.notified() => None,
    };
    s.rt.lock().await.cancel.remove(&id);
    let finish = |why: String, code: StatusCode| async move {
        append_msg(s, id, "error", &why).await;
        let mut g = s.db.lock().await;
        if let Some(a) = g.agents.iter_mut().find(|a| a.id == id) { a.running = false; a.last_active = now(); }
        save(&g, &s.file);
        drop(g);
        bump(s);
        Err::<String, _>((code, why))
    };
    let out = match out {
        None => return finish("Stopped.".into(), StatusCode::OK).await,
        Some(Err(_)) => return finish("Claude took longer than 15 minutes, so I stopped it.".into(), StatusCode::GATEWAY_TIMEOUT).await,
        Some(Ok(Err(e))) => return finish(format!("Couldn't start claude on the server: {e}. Is Claude Code installed and logged in there?"), StatusCode::BAD_GATEWAY).await,
        Some(Ok(Ok(o))) => o,
    };
    let v: serde_json::Value = match serde_json::from_slice(&out.stdout) {
        Ok(v) => v,
        Err(_) => {
            let err = String::from_utf8_lossy(&out.stderr);
            return finish(format!("Claude gave no answer. {}", err.trim().chars().take(300).collect::<String>()), StatusCode::BAD_GATEWAY).await;
        }
    };
    let r = v["result"].as_str().unwrap_or("").to_string();
    append_msg(s, id, "assistant", &r).await;
    let mut g = s.db.lock().await;
    if let Some(a) = g.agents.iter_mut().find(|a| a.id == id) {
        a.running = false;
        a.last_active = now();
        if let Some(x) = v["session_id"].as_str() { a.session = Some(x.into()); }
        let u = &v["usage"];
        let (i, o) = (u["input_tokens"].as_u64().unwrap_or(0), u["output_tokens"].as_u64().unwrap_or(0));
        let cost = v["total_cost_usd"].as_f64().unwrap_or(0.0);
        a.usage.input_tokens += i;
        a.usage.output_tokens += o;
        a.usage.cache_read_tokens += u["cache_read_input_tokens"].as_u64().unwrap_or(0);
        a.usage.cost_usd += cost;
        let d = a.daily.entry(day_of(now())).or_default();
        d.cost += cost;
        d.tokens += i + o;
        while a.daily.len() > KEEP_DAYS { let k = a.daily.keys().next().cloned().unwrap(); a.daily.remove(&k); }
    }
    save(&g, &s.file);
    drop(g);
    bump(s);
    Ok(r)
}

#[derive(Deserialize)]
struct RunReq { message: String }

/// Starts the run and answers at once, so a phone that goes to sleep or loses signal does not lose the answer: it is saved on the server.
async fn run(State(s): State<S>, Path(id): Path<u64>, Json(req): Json<RunReq>) -> Response {
    {
        let g = s.db.lock().await;
        match g.agents.iter().find(|a| a.id == id) {
            None => return (StatusCode::NOT_FOUND, "no such agent").into_response(),
            Some(a) if a.running => return (StatusCode::CONFLICT, "this agent is still working on the last message").into_response(),
            _ => {}
        }
    }
    let s2 = s.clone();
    tokio::spawn(async move { let _ = run_agent(&s2, id, &req.message).await; });
    (StatusCode::ACCEPTED, Json(serde_json::json!({"started": true}))).into_response()
}

async fn stop(State(s): State<S>, Path(id): Path<u64>) -> StatusCode {
    match s.rt.lock().await.cancel.get(&id) { Some(n) => { n.notify_one(); StatusCode::OK } None => StatusCode::NOT_FOUND }
}

/// Downloads the newest server from GitHub, checks its checksum, swaps it in and exits so systemd starts the new one.
async fn self_update() -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    let err = |m: String| (StatusCode::BAD_GATEWAY, m);
    let arch = match std::env::consts::ARCH { "x86_64" => "x86_64", "aarch64" => "aarch64", a => return Err(err(format!("no build for {a}"))) };
    let name = format!("agents-server-linux-{arch}");
    let base = format!("https://github.com/{REPO}/releases/latest/download");
    let exe = std::env::current_exe().map_err(|e| err(e.to_string()))?;
    let tmp = exe.with_extension("new");
    let dl = |url: String, to: PathBuf| async move {
        Command::new("curl").args(["-fsSL", "-m", "120", "-o"]).arg(&to).arg(&url).status().await.is_ok_and(|s| s.success())
    };
    if !dl(format!("{base}/{name}"), tmp.clone()).await { return Err(err("couldn't download the new server from GitHub".into())); }
    let sums = exe.with_extension("sums");
    if !dl(format!("{base}/SHA256SUMS"), sums.clone()).await { let _ = std::fs::remove_file(&tmp); return Err(err("couldn't download the checksums".into())); }
    let want = std::fs::read_to_string(&sums).unwrap_or_default().lines().find(|l| l.ends_with(&name)).and_then(|l| l.split_whitespace().next()).map(String::from);
    let _ = std::fs::remove_file(&sums);
    let got = Command::new("sha256sum").arg(&tmp).output().await.ok().and_then(|o| String::from_utf8(o.stdout).ok()).and_then(|s| s.split_whitespace().next().map(String::from));
    if want.is_none() || want != got { let _ = std::fs::remove_file(&tmp); return Err(err("the download did not match its checksum, so I kept the current version".into())); }
    use std::os::unix::fs::PermissionsExt;
    let _ = std::fs::set_permissions(&tmp, std::fs::Permissions::from_mode(0o755));
    let ver = Command::new(&tmp).arg("--version").output().await.ok().and_then(|o| String::from_utf8(o.stdout).ok()).unwrap_or_default().trim().to_string();
    if !ver.starts_with("agents-server ") { let _ = std::fs::remove_file(&tmp); return Err(err("the new server did not start, so I kept the current version".into())); }
    std::fs::rename(&tmp, &exe).map_err(|e| err(e.to_string()))?;
    tokio::spawn(async { tokio::time::sleep(Duration::from_millis(800)).await; std::process::exit(1) });
    Ok(Json(serde_json::json!({"updating": true, "from": VERSION, "to": ver})))
}

/// Telegram bridge via curl (keeps the server free of TLS libraries): "/<id> text" talks to agent <id>; plain text goes to agent 1.
async fn curl_json(args: &[&str], body: Option<String>) -> Option<serde_json::Value> {
    let mut c = Command::new("curl");
    c.args(["-sS", "-m", "45"]).args(args);
    if let Some(b) = body { c.args(["-H", "content-type: application/json", "-d", &b]); }
    serde_json::from_slice(&c.output().await.ok()?.stdout).ok()
}

async fn telegram(s: S, tok: String) {
    let mut offset = 0i64;
    loop {
        let url = format!("https://api.telegram.org/bot{tok}/getUpdates?timeout=30&offset={offset}");
        let Some(v) = curl_json(&[&url], None).await else { tokio::time::sleep(Duration::from_secs(5)).await; continue };
        for u in v["result"].as_array().into_iter().flatten() {
            offset = u["update_id"].as_i64().unwrap_or(0) + 1;
            let (Some(chat), Some(text)) = (u["message"]["chat"]["id"].as_i64(), u["message"]["text"].as_str()) else { continue };
            let (id, msg) = match text.strip_prefix('/').and_then(|t| t.split_once(' ')) {
                Some((n, rest)) if n.parse::<u64>().is_ok() => (n.parse().unwrap(), rest),
                _ => (1, text),
            };
            let reply = run_agent(&s, id, msg).await.unwrap_or_else(|e| e.1);
            let _ = curl_json(&[&format!("https://api.telegram.org/bot{tok}/sendMessage")], Some(serde_json::json!({"chat_id": chat, "text": reply}).to_string())).await;
        }
    }
}

#[tokio::main]
async fn main() {
    if std::env::args().any(|a| a == "--version") { println!("agents-server {VERSION}"); return; }
    let token = std::env::var("AGENTS_TOKEN").unwrap_or_else(|_| { eprintln!("set AGENTS_TOKEN"); std::process::exit(1) });
    let file = PathBuf::from(std::env::var("AGENTS_DB").unwrap_or_else(|_| "agents.json".into()));
    let mut db = std::fs::read(&file).ok().and_then(|b| serde_json::from_slice::<Db>(&b).ok()).unwrap_or_default();
    let (tx, _rx) = watch::channel(1u64);
    let s = S { db: Arc::new(Mutex::new(Db::default())), rt: Arc::new(Mutex::new(Rt::default())), rev: Arc::new(tx), token: Arc::new(token), file: Arc::new(file) };
    // move chats from the old single file (0.2.x) into one file per agent
    for a in db.agents.iter_mut() {
        a.running = false;
        if a.last_active == 0 { a.last_active = now(); }
        for m in std::mem::take(&mut a.messages) { append_msg(&s, a.id, &m.role, &m.text).await; }
    }
    save(&db, &s.file);
    *s.db.lock().await = db;
    if let Ok(t) = std::env::var("TELEGRAM_TOKEN") { tokio::spawn(telegram(s.clone(), t)); }
    let cors = CorsLayer::new().allow_origin(Any).allow_methods(Any).allow_headers(Any).expose_headers([HeaderName::from_static("x-rev")]);
    let app = Router::new()
        .route("/health", get(health))
        .route("/models", get(models))
        .route("/agents", get(list).post(create))
        .route("/agents/order", post(reorder))
        .route("/agents/{id}", axum::routing::put(update).delete(delete))
        .route("/agents/{id}/pin", post(pin))
        .route("/agents/{id}/messages", get(messages).delete(clear))
        .route("/agents/{id}/run", post(run))
        .route("/agents/{id}/stop", post(stop))
        .route("/admin/update", post(self_update))
        .layer(middleware::from_fn_with_state(s.clone(), auth))
        .layer(cors)
        .with_state(s);
    let addr = std::env::var("AGENTS_ADDR").unwrap_or_else(|_| "127.0.0.1:7777".into());
    eprintln!("agents-server {VERSION} listening on {addr}");
    axum::serve(tokio::net::TcpListener::bind(addr).await.unwrap(), app).await.unwrap();
}

