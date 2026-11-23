use axum::{
    extract::{Path, Request, State},
    http::{header, Method, StatusCode},
    middleware::{self, Next},
    response::Response,
    routing::{get, post},
    Json, Router,
};
use serde::{Deserialize, Serialize};
use std::{sync::Arc, time::Duration};
use tokio::{process::Command, sync::Mutex};
use tower_http::cors::{Any, CorsLayer};

const MODELS: &[(&str, &str)] = &[
    ("claude-fable-5-1", "Fable 5.1"),
    ("claude-opus-5-5", "Opus 5.5"),
    ("claude-sonnet-5-5", "Sonnet 5.5"),
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

#[derive(Clone, Serialize, Deserialize)]
struct Msg { role: String, text: String }

#[derive(Clone, Serialize, Deserialize)]
struct Agent {
    id: u64,
    spec: Spec,
    #[serde(default)]
    usage: Usage,
    #[serde(default)]
    session: Option<String>,
    #[serde(default)]
    messages: Vec<Msg>,
    #[serde(skip)]
    running: bool,
}

#[derive(Serialize)]
struct Info { id: u64, spec: Spec, status: &'static str, usage: Usage }

#[derive(Default, Serialize, Deserialize)]
struct Db { agents: Vec<Agent> }

#[derive(Clone)]
struct S { db: Arc<Mutex<Db>>, token: Arc<String>, file: Arc<String> }

fn save(db: &Db, file: &str) {
    if let Ok(j) = serde_json::to_vec(db) {
        let tmp = format!("{file}.tmp");
        if std::fs::write(&tmp, j).is_ok() { let _ = std::fs::rename(tmp, file); }
    }
}

fn info(a: &Agent) -> Info {
    Info { id: a.id, spec: a.spec.clone(), status: if a.running { "running" } else { "idle" }, usage: a.usage.clone() }
}

async fn auth(State(s): State<S>, req: Request, next: Next) -> Result<Response, StatusCode> {
    if req.method() == Method::OPTIONS || req.uri().path() == "/health" { return Ok(next.run(req).await); }
    let ok = req.headers().get(header::AUTHORIZATION).and_then(|h| h.to_str().ok())
        .is_some_and(|h| h.strip_prefix("Bearer ") == Some(s.token.as_str()));
    if ok { Ok(next.run(req).await) } else { Err(StatusCode::UNAUTHORIZED) }
}

async fn health() -> Json<serde_json::Value> { Json(serde_json::json!({"ok": true, "name": "agents-server", "version": env!("CARGO_PKG_VERSION")})) }
async fn models() -> Json<Vec<(&'static str, &'static str)>> { Json(MODELS.to_vec()) }

async fn list(State(s): State<S>) -> Json<Vec<Info>> { Json(s.db.lock().await.agents.iter().map(info).collect()) }

async fn create(State(s): State<S>, Json(spec): Json<Spec>) -> Json<u64> {
    let mut g = s.db.lock().await;
    let id = g.agents.iter().map(|a| a.id).max().map_or(1, |m| m + 1);
    g.agents.push(Agent { id, spec, usage: Usage::default(), session: None, messages: vec![], running: false });
    save(&g, &s.file);
    Json(id)
}

async fn update(State(s): State<S>, Path(id): Path<u64>, Json(spec): Json<Spec>) -> StatusCode {
    let mut g = s.db.lock().await;
    match g.agents.iter_mut().find(|a| a.id == id) {
        Some(a) => { a.spec = spec; save(&g, &s.file); StatusCode::OK }
        None => StatusCode::NOT_FOUND,
    }
}

async fn delete(State(s): State<S>, Path(id): Path<u64>) -> StatusCode {
    let mut g = s.db.lock().await;
    g.agents.retain(|a| a.id != id);
    save(&g, &s.file);
    StatusCode::OK
}

async fn messages(State(s): State<S>, Path(id): Path<u64>) -> Result<Json<Vec<Msg>>, StatusCode> {
    let g = s.db.lock().await;
    g.agents.iter().find(|a| a.id == id).map(|a| Json(a.messages.clone())).ok_or(StatusCode::NOT_FOUND)
}

async fn clear(State(s): State<S>, Path(id): Path<u64>) -> StatusCode {
    let mut g = s.db.lock().await;
    match g.agents.iter_mut().find(|a| a.id == id) {
        Some(a) => { a.messages.clear(); a.session = None; save(&g, &s.file); StatusCode::OK }
        None => StatusCode::NOT_FOUND,
    }
}

async fn run_agent(s: &S, id: u64, msg: &str) -> Result<String, (StatusCode, String)> {
    let (spec, session) = {
        let mut g = s.db.lock().await;
        let a = g.agents.iter_mut().find(|a| a.id == id).ok_or((StatusCode::NOT_FOUND, "no such agent".into()))?;
        if a.running { return Err((StatusCode::CONFLICT, "this agent is still working on the last message".into())); }
        a.running = true;
        a.messages.push(Msg { role: "user".into(), text: msg.into() });
        (a.spec.clone(), a.session.clone())
    };
    let mut prompt = spec.prompt.clone();
    if !spec.memory.is_empty() { prompt.push_str("\n\nMemory:\n"); prompt.push_str(&spec.memory); }
    let mut c = Command::new("claude");
    c.args(["-p", msg, "--output-format", "json", "--model", &spec.model]);
    if !prompt.is_empty() { c.args(["--append-system-prompt", &prompt]); }
    if !spec.subagents { c.args(["--disallowedTools", "Task"]); }
    if let Some(m) = spec.mcp_config.as_deref().filter(|m| !m.is_empty()) { c.args(["--mcp-config", m]); }
    if let Some(x) = &session { c.args(["--resume", x]); }
    c.kill_on_drop(true);
    let out = tokio::time::timeout(Duration::from_secs(900), c.output()).await;
    let mut g = s.db.lock().await;
    let a = g.agents.iter_mut().find(|a| a.id == id).ok_or((StatusCode::NOT_FOUND, "agent was deleted".into()))?;
    a.running = false;
    let fail = |a: &mut Agent, why: String| { a.messages.push(Msg { role: "error".into(), text: why.clone() }); (StatusCode::BAD_GATEWAY, why) };
    let out = match out {
        Err(_) => { let e = fail(a, "Claude took longer than 15 minutes, so I stopped it.".into()); save(&g, &s.file); return Err(e); }
        Ok(Err(e)) => { let e = fail(a, format!("Couldn't start claude on the server: {e}. Is Claude Code installed and logged in there?")); save(&g, &s.file); return Err(e); }
        Ok(Ok(o)) => o,
    };
    let v: serde_json::Value = match serde_json::from_slice(&out.stdout) {
        Ok(v) => v,
        Err(_) => {
            let err = String::from_utf8_lossy(&out.stderr);
            let e = fail(a, format!("Claude gave no answer. {}", err.trim().chars().take(300).collect::<String>()));
            save(&g, &s.file);
            return Err(e);
        }
    };
    if let Some(x) = v["session_id"].as_str() { a.session = Some(x.into()); }
    let u = &v["usage"];
    a.usage.input_tokens += u["input_tokens"].as_u64().unwrap_or(0);
    a.usage.output_tokens += u["output_tokens"].as_u64().unwrap_or(0);
    a.usage.cache_read_tokens += u["cache_read_input_tokens"].as_u64().unwrap_or(0);
    a.usage.cost_usd += v["total_cost_usd"].as_f64().unwrap_or(0.0);
    let r = v["result"].as_str().unwrap_or("").to_string();
    a.messages.push(Msg { role: "assistant".into(), text: r.clone() });
    save(&g, &s.file);
    Ok(r)
}

#[derive(Deserialize)]
struct RunReq { message: String }

async fn run(State(s): State<S>, Path(id): Path<u64>, Json(req): Json<RunReq>) -> Result<String, (StatusCode, String)> {
    run_agent(&s, id, &req.message).await
}

/// Telegram bridge via curl (keeps the server free of TLS libraries): "/<id> text" talks to agent <id>; plain text goes to agent 1.
async fn curl_json(args: &[&str], body: Option<String>) -> Option<serde_json::Value> {
    let mut c = Command::new("curl");
    c.args(["-sS", "-m", "45"]).args(args);
    if let Some(b) = body { c.args(["-H", "content-type: application/json", "-d", &b]); }
    let out = c.output().await.ok()?;
    serde_json::from_slice(&out.stdout).ok()
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
    let token = std::env::var("AGENTS_TOKEN").unwrap_or_else(|_| { eprintln!("set AGENTS_TOKEN"); std::process::exit(1) });
    let file = std::env::var("AGENTS_DB").unwrap_or_else(|_| "agents.json".into());
    let db = std::fs::read(&file).ok().and_then(|b| serde_json::from_slice::<Db>(&b).ok()).unwrap_or_default();
    let s = S { db: Arc::new(Mutex::new(db)), token: Arc::new(token), file: Arc::new(file) };
    if let Ok(t) = std::env::var("TELEGRAM_TOKEN") { tokio::spawn(telegram(s.clone(), t)); }
    let cors = CorsLayer::new().allow_origin(Any).allow_methods(Any).allow_headers(Any);
    let app = Router::new()
        .route("/health", get(health))
        .route("/models", get(models))
        .route("/agents", get(list).post(create))
        .route("/agents/{id}", axum::routing::put(update).delete(delete))
        .route("/agents/{id}/messages", get(messages).delete(clear))
        .route("/agents/{id}/run", post(run))
        .layer(middleware::from_fn_with_state(s.clone(), auth))
        .layer(cors)
        .with_state(s);
    let addr = std::env::var("AGENTS_ADDR").unwrap_or_else(|_| "127.0.0.1:7777".into());
    eprintln!("agents-server listening on {addr}");
    axum::serve(tokio::net::TcpListener::bind(addr).await.unwrap(), app).await.unwrap();
}
