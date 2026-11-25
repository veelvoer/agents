mod curlx;
mod mcp;

use axum::{
    extract::{Path, Query, Request, State},
    http::{header, HeaderName, Method, StatusCode},
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use chrono::{Datelike, TimeZone};
use curlx::Req;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    collections::{BTreeMap, HashMap, HashSet, VecDeque},
    io::{Read, Seek, SeekFrom, Write},
    path::PathBuf,
    sync::{atomic::{AtomicU64, Ordering}, Arc},
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tokio::{
    process::Command,
    sync::{watch, Mutex, Notify, Semaphore},
};
use tower_http::cors::{Any, CorsLayer};

const VERSION: &str = env!("CARGO_PKG_VERSION");
const REPO: &str = "veelvoer/agents";
const KEEP_DAYS: usize = 60;
const SHOW_DAYS: usize = 14;
const CATCH_UP_SECS: u64 = 6 * 3600;
const MAX_ACT_MEM: usize = 500;

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

fn d_safe() -> String { "safe".into() }
fn d_true() -> bool { true }
fn d_always() -> String { "always".into() }
fn d_parallel() -> usize { 2 }
fn d_utc() -> String { "UTC".into() }

// ---------------------------------------------------------------- data

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
    /// safe = read-only tools; standard = may edit files and use the web; full = no permission prompts at all
    #[serde(default = "d_safe")]
    autonomy: String,
    #[serde(default)]
    workdir: Option<String>,
    /// Stop scheduled runs for the day once this much has been used (the CLI's cost estimate).
    #[serde(default)]
    daily_cap: Option<f64>,
    /// Give the agent the agentic-os tools (notify, schedule, remember, ask other agents).
    #[serde(default = "d_true")]
    os_tools: bool,
    /// The agent lives on its own: it wakes up, thinks, acts, and chooses when to wake next.
    #[serde(default)]
    autonomous: bool,
    /// Fewest minutes between two wake-ups (default 15).
    #[serde(default)]
    wake_min: Option<u64>,
    /// Most wake-ups per 24 hours (default 24).
    #[serde(default)]
    max_wakes: Option<u32>,
    /// The agent gets a real headless web browser (it can open pages, click, type, log in, keep its own cookies).
    #[serde(default)]
    browser: bool,
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
    /// Only read from old files (0.2.x); chats live in chats/<id>.jsonl.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    messages: Vec<Msg>,
    /// The agent's own long-term notes about its life and work (it writes this itself).
    #[serde(default)]
    journal: String,
    #[serde(default)]
    next_wake: Option<u64>,
    #[serde(default)]
    wake_reason: String,
    #[serde(default)]
    wakes: u64,
    #[serde(default)]
    last_wake: u64,
    #[serde(default)]
    wake_log: Vec<u64>,
    #[serde(default)]
    wake_fails: u32,
    #[serde(skip)]
    running: bool,
}

#[derive(Clone, Serialize, Deserialize, PartialEq)]
#[serde(tag = "kind", rename_all = "lowercase")]
enum Schedule {
    Cron { expr: String, #[serde(default)] tz: String, #[serde(default)] text: String },
    Every { minutes: u64 },
    Once { at: u64 },
    Hook,
    Manual,
}

#[derive(Clone, Serialize, Deserialize)]
struct Job {
    id: u64,
    agent_id: u64,
    name: String,
    prompt: String,
    schedule: Schedule,
    #[serde(default = "d_true")]
    enabled: bool,
    /// A fresh session each time (cheap: no growing history). Off = continue the agent's own conversation.
    #[serde(default = "d_true")]
    fresh: bool,
    /// always | errors | never. (Agents can also send messages themselves with the notify tool.)
    #[serde(default = "d_always")]
    notify: String,
    #[serde(default)]
    max_turns: Option<u32>,
    #[serde(default)]
    hook: Option<String>,
    #[serde(default)]
    next_at: Option<u64>,
    #[serde(default)]
    last_run: Option<u64>,
    #[serde(default)]
    last_status: Option<String>,
    #[serde(default)]
    last_text: String,
    #[serde(default)]
    fails: u32,
    #[serde(default)]
    runs: u64,
    #[serde(default)]
    cost: f64,
    /// Short notes the agent writes for itself between runs (what it covered, what comes next).
    #[serde(default)]
    notes: String,
}

#[derive(Clone, Serialize, Deserialize, Default)]
struct NotifyCfg {
    #[serde(default)]
    telegram_token: String,
    #[serde(default)]
    telegram_chat: Option<i64>,
    #[serde(default)]
    telegram_pair: Option<String>,
    #[serde(default)]
    ntfy_server: String,
    #[serde(default)]
    ntfy_topic: String,
    #[serde(default)]
    webhook_url: String,
}

#[derive(Clone, Serialize, Deserialize)]
struct Settings {
    #[serde(default)]
    paused: bool,
    #[serde(default)]
    pause_until: u64,
    #[serde(default = "d_parallel")]
    max_parallel: usize,
    #[serde(default = "d_utc")]
    tz: String,
    #[serde(default)]
    global_memory: String,
    #[serde(default)]
    notify: NotifyCfg,
}
impl Default for Settings {
    fn default() -> Self { Settings { paused: false, pause_until: 0, max_parallel: 2, tz: std::env::var("TZ").unwrap_or_else(|_| "UTC".into()), global_memory: String::new(), notify: NotifyCfg::default() } }
}

#[derive(Default, Serialize, Deserialize)]
struct Db {
    agents: Vec<Agent>,
    #[serde(default)]
    jobs: Vec<Job>,
    #[serde(default)]
    settings: Settings,
}

#[derive(Clone, Serialize, Deserialize)]
struct Act {
    id: u64,
    at: u64,
    /// run | notify | error | system
    kind: String,
    #[serde(default)]
    agent_id: Option<u64>,
    #[serde(default)]
    job_id: Option<u64>,
    title: String,
    body: String,
}

#[derive(Default)]
struct Acts { list: VecDeque<Act>, next: u64 }

#[derive(Default)]
struct Rt {
    offsets: HashMap<u64, Vec<u64>>,
    cancel: HashMap<u64, Arc<Notify>>,
    jobs_running: HashSet<u64>,
}

#[derive(Clone)]
struct S {
    db: Arc<Mutex<Db>>,
    rt: Arc<Mutex<Rt>>,
    acts: Arc<Mutex<Acts>>,
    rev: Arc<watch::Sender<u64>>,
    token: Arc<String>,
    file: Arc<PathBuf>,
    addr: Arc<String>,
    sem: Arc<Semaphore>,
    tg_gen: Arc<AtomicU64>,
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

fn data_dir(s: &S) -> PathBuf { s.file.parent().map(|p| p.to_path_buf()).filter(|p| !p.as_os_str().is_empty()).unwrap_or_else(|| PathBuf::from(".")) }

fn save(db: &Db, file: &PathBuf) {
    if let Ok(j) = serde_json::to_vec(db) {
        let tmp = file.with_extension("tmp");
        if std::fs::write(&tmp, j).is_ok() { let _ = std::fs::rename(tmp, file); }
    }
}

fn chat_path(s: &S, id: u64) -> PathBuf { data_dir(s).join("chats").join(format!("{id}.jsonl")) }

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

fn cut(t: &str, n: usize) -> String { if t.chars().count() <= n { t.to_string() } else { t.chars().take(n).collect::<String>() + "…" } }

/// Adds an entry to the activity feed (what the agents did) and tells waiting apps.
async fn act(s: &S, kind: &str, agent_id: Option<u64>, job_id: Option<u64>, title: &str, body: &str) {
    let mut a = s.acts.lock().await;
    a.next += 1;
    let e = Act { id: a.next, at: now(), kind: kind.into(), agent_id, job_id, title: cut(title, 200), body: cut(body, 4000) };
    if let Ok(line) = serde_json::to_string(&e) {
        if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(data_dir(s).join("activity.jsonl")) { let _ = writeln!(f, "{line}"); }
    }
    a.list.push_back(e);
    while a.list.len() > MAX_ACT_MEM { a.list.pop_front(); }
    drop(a);
    bump(s);
}

// ---------------------------------------------------------------- schedules

fn tz_of(name: &str, fallback: &str) -> chrono_tz::Tz {
    name.parse().ok().or_else(|| fallback.parse().ok()).unwrap_or(chrono_tz::UTC)
}

fn next_at(sch: &Schedule, after: u64, default_tz: &str) -> Option<u64> {
    match sch {
        Schedule::Cron { expr, tz, .. } => {
            let tz = tz_of(tz, default_tz);
            let dt = chrono::DateTime::from_timestamp(after as i64, 0)?.with_timezone(&tz);
            // several lines (separated by ;) mean "any of these": the next one that comes first
            expr.split(';').filter(|p| !p.trim().is_empty())
                .filter_map(|p| croner::Cron::new(p.trim()).parse().ok()?.find_next_occurrence(&dt, false).ok().map(|d| d.timestamp() as u64))
                .min()
        }
        Schedule::Every { minutes } => Some(after + (*minutes).max(1) * 60),
        Schedule::Once { at } => (*at > after).then_some(*at),
        Schedule::Hook | Schedule::Manual => None,
    }
}

fn local_text(ts: u64, tz: &str) -> String {
    let tz = tz_of(tz, "UTC");
    chrono::DateTime::from_timestamp(ts as i64, 0).map(|d| d.with_timezone(&tz).format("%a %d %b %H:%M").to_string()).unwrap_or_default()
}

fn describe(sch: &Schedule, tz: &str) -> String {
    match sch {
        Schedule::Every { minutes } if minutes % 1440 == 0 => format!("Every {} day{}", minutes / 1440, if minutes / 1440 == 1 { "" } else { "s" }),
        Schedule::Every { minutes } if minutes % 60 == 0 => format!("Every {} hour{}", minutes / 60, if minutes / 60 == 1 { "" } else { "s" }),
        Schedule::Every { minutes } => format!("Every {minutes} minutes"),
        Schedule::Once { at } => format!("Once, {}", local_text(*at, tz)),
        Schedule::Hook => "When its web link is called".into(),
        Schedule::Manual => "Only when I run it".into(),
        Schedule::Cron { text, .. } if !text.is_empty() => text.clone(),
        Schedule::Cron { expr, .. } => {
            let f: Vec<&str> = expr.split_whitespace().collect();
            if f.len() == 5 && f[2] == "*" && f[3] == "*" {
                if let (Ok(m), Ok(h)) = (f[0].parse::<u32>(), f[1].parse::<u32>()) {
                    let t = format!("{h:02}:{m:02}");
                    return match f[4] { "*" => format!("Every day at {t}"), "1-5" => format!("Weekdays at {t}"), "0,6" | "6,0" => format!("Weekends at {t}"),
                        d => d.parse::<usize>().ok().and_then(|n| ["Sundays", "Mondays", "Tuesdays", "Wednesdays", "Thursdays", "Fridays", "Saturdays", "Sundays"].get(n).map(|x| format!("{x} at {t}"))).unwrap_or_else(|| format!("Cron {expr}")) };
                }
            }
            format!("Cron {expr}")
        }
    }
}

fn parse_clock(t: &str) -> Option<(u32, u32)> {
    let t = t.trim().to_lowercase();
    let (body, pm, am) = if let Some(b) = t.strip_suffix("pm") { (b.trim(), true, false) } else if let Some(b) = t.strip_suffix("am") { (b.trim(), false, true) } else { (t.as_str(), false, false) };
    let (h, m) = match body.split_once(':') { Some((h, m)) => (h.parse::<u32>().ok()?, m.parse::<u32>().ok()?), None => (body.parse::<u32>().ok()?, 0) };
    let h = if pm && h < 12 { h + 12 } else if am && h == 12 { 0 } else { h };
    (h < 24 && m < 60).then_some((h, m))
}

/// Understands the plain phrases agents and people use: "daily 08:00", "weekdays 8:30", "weekly monday 9am", "every 30 minutes", "in 2 hours", "at 2026-12-01 09:00", "cron 0 8 * * *".
fn parse_when(text: &str, tz: &str) -> Result<Schedule, String> {
    let t = text.trim().to_lowercase();
    let bad = || format!("I don't understand \"{text}\". Try: daily 08:00, weekdays 08:30, weekly monday 09:00, every 30 minutes, in 2 hours, at 2026-12-01 09:00, or cron 0 8 * * *");
    let w: Vec<&str> = t.split_whitespace().collect();
    let cron = |m: u32, h: u32, dow: &str| Schedule::Cron { expr: format!("{m} {h} * * {dow}"), tz: tz.to_string(), text: String::new() };
    let unit = |n: u64, u: &str| -> Option<u64> { match u.trim_end_matches('s') { "second" | "sec" => Some(n.div_ceil(60)), "minute" | "min" => Some(n), "hour" | "hr" | "h" => Some(n * 60), "day" | "d" => Some(n * 1440), _ => None } };
    match w.as_slice() {
        ["cron", rest @ ..] => { let e = rest.join(" "); croner::Cron::new(&e).parse().map_err(|x| format!("bad cron \"{e}\": {x}"))?; Ok(Schedule::Cron { expr: e, tz: tz.into(), text: String::new() }) }
        [a, b, c, d, e] if croner::Cron::new(&format!("{a} {b} {c} {d} {e}")).parse().is_ok() => Ok(Schedule::Cron { expr: format!("{a} {b} {c} {d} {e}"), tz: tz.into(), text: String::new() }),
        ["daily" | "everyday", at] | ["every", "day", "at", at] | ["daily", "at", at] => parse_clock(at).map(|(h, m)| cron(m, h, "*")).ok_or_else(bad),
        ["weekdays", at] | ["weekdays", "at", at] => parse_clock(at).map(|(h, m)| cron(m, h, "1-5")).ok_or_else(bad),
        ["weekends", at] | ["weekends", "at", at] => parse_clock(at).map(|(h, m)| cron(m, h, "0,6")).ok_or_else(bad),
        ["weekly" | "every", day, rest @ ..] if ["sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday"].contains(day) => {
            let n = ["sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday"].iter().position(|x| x == day).unwrap();
            let at = rest.iter().find(|x| **x != "at").ok_or_else(bad)?;
            parse_clock(at).map(|(h, m)| cron(m, h, &n.to_string())).ok_or_else(bad)
        }
        ["every", n, u] => { let n: u64 = n.parse().map_err(|_| bad())?; unit(n, u).filter(|m| *m >= 1).map(|minutes| Schedule::Every { minutes }).ok_or_else(bad) }
        ["every", u] => unit(1, u).map(|minutes| Schedule::Every { minutes }).ok_or_else(bad),
        ["in", n, u] => { let n: u64 = n.parse().map_err(|_| bad())?; unit(n, u).map(|m| Schedule::Once { at: now() + m * 60 }).ok_or_else(bad) }
        ["at", date, time] | [date, time] if date.len() == 10 && date.chars().nth(4) == Some('-') => {
            let (h, m) = parse_clock(time).ok_or_else(bad)?;
            let parts: Vec<u32> = date.split('-').filter_map(|x| x.parse().ok()).collect();
            if parts.len() != 3 { return Err(bad()); }
            let zone = tz_of(tz, "UTC");
            let dt = zone.with_ymd_and_hms(parts[0] as i32, parts[1], parts[2], h, m, 0).single().ok_or_else(bad)?;
            Ok(Schedule::Once { at: dt.timestamp() as u64 })
        }
        ["tomorrow", rest @ ..] => {
            let at = rest.iter().find(|x| **x != "at").ok_or_else(bad)?;
            let (h, m) = parse_clock(at).ok_or_else(bad)?;
            let zone = tz_of(tz, "UTC");
            let d = chrono::Utc::now().with_timezone(&zone).date_naive().succ_opt().ok_or_else(bad)?;
            let dt = zone.with_ymd_and_hms(d.year(), d.month(), d.day(), h, m, 0).single().ok_or_else(bad)?;
            Ok(Schedule::Once { at: dt.timestamp() as u64 })
        }
        ["hook"] | ["webhook"] => Ok(Schedule::Hook),
        ["manual"] => Ok(Schedule::Manual),
        _ => Err(bad()),
    }
}

fn job_json(j: &Job, tz: &str, agent_name: &str) -> Value {
    let jtz = match &j.schedule { Schedule::Cron { tz: t, .. } if !t.is_empty() => t.clone(), _ => tz.to_string() };
    json!({
        "id": j.id, "agent_id": j.agent_id, "agent": agent_name, "name": j.name, "prompt": j.prompt, "schedule": j.schedule, "human": describe(&j.schedule, &jtz),
        "enabled": j.enabled, "fresh": j.fresh, "notify": j.notify, "max_turns": j.max_turns, "hook": j.hook,
        "next_at": j.next_at, "next": j.next_at.map(|t| local_text(t, &jtz)), "last_run": j.last_run, "last_status": j.last_status, "last_text": cut(&j.last_text, 600),
        "fails": j.fails, "runs": j.runs, "cost": j.cost, "notes": j.notes,
    })
}

// ---------------------------------------------------------------- auth, simple routes

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
    journal: String,
    next_wake: Option<u64>,
    wake_reason: String,
    wakes: u64,
    last_wake: u64,
}

fn info(a: &Agent, msgs: usize) -> Info {
    Info {
        id: a.id, spec: a.spec.clone(), status: if a.running { "running" } else { "idle" }, usage: a.usage.clone(),
        pinned: a.pinned, order: a.order, last_active: a.last_active, messages: msgs,
        daily: a.daily.iter().rev().take(SHOW_DAYS).rev().map(|(k, v)| (k.clone(), v.clone())).collect(),
        journal: a.journal.clone(), next_wake: a.next_wake, wake_reason: a.wake_reason.clone(), wakes: a.wakes, last_wake: a.last_wake,
    }
}

async fn auth(State(s): State<S>, req: Request, next: Next) -> Result<Response, StatusCode> {
    let p = req.uri().path();
    if req.method() == Method::OPTIONS || p == "/health" || p.starts_with("/hook/") { return Ok(next.run(req).await); }
    let ok = req.headers().get(header::AUTHORIZATION).and_then(|h| h.to_str().ok())
        .is_some_and(|h| h.strip_prefix("Bearer ") == Some(s.token.as_str()));
    if ok { Ok(next.run(req).await) } else { Err(StatusCode::UNAUTHORIZED) }
}

async fn health() -> Json<Value> { Json(json!({"ok": true, "name": "agents-server", "version": VERSION})) }
async fn models() -> Json<Vec<(&'static str, &'static str)>> { Json(MODELS.to_vec()) }

#[derive(Deserialize)]
struct ListQ { since: Option<u64> }

/// Long poll: with ?since=<rev> the answer waits (up to 25 s) until something changed, so apps see updates at once and idle apps cost nothing.
async fn list(State(s): State<S>, Query(q): Query<ListQ>) -> Json<Value> {
    let mut rx = s.rev.subscribe();
    if let Some(since) = q.since {
        if *rx.borrow() == since { let _ = tokio::time::timeout(Duration::from_secs(25), rx.changed()).await; }
    }
    let rev = *rx.borrow();
    let counts: HashMap<u64, usize> = s.rt.lock().await.offsets.iter().map(|(k, v)| (*k, v.len())).collect();
    let act_last = s.acts.lock().await.next;
    let g = s.db.lock().await;
    let mut v: Vec<&Agent> = g.agents.iter().collect();
    v.sort_by_key(|a| a.order);
    let paused = g.settings.paused || g.settings.pause_until > now();
    Json(json!({"rev": rev, "act_last": act_last, "paused": paused, "agents": v.into_iter().map(|a| info(a, counts.get(&a.id).copied().unwrap_or(0))).collect::<Vec<_>>()}))
}

async fn create(State(s): State<S>, Json(spec): Json<Spec>) -> Json<u64> {
    let mut g = s.db.lock().await;
    let id = g.agents.iter().map(|a| a.id).max().map_or(1, |m| m + 1);
    let order = g.agents.iter().map(|a| a.order).max().map_or(0, |m| m + 1);
    let next_wake = if spec.autonomous { Some(now() + 5) } else { None };
    g.agents.push(Agent { id, spec, usage: Usage::default(), session: None, pinned: false, order, last_active: now(), daily: BTreeMap::new(), messages: vec![], journal: String::new(), next_wake, wake_reason: String::new(), wakes: 0, last_wake: 0, wake_log: vec![], wake_fails: 0, running: false });
    save(&g, &s.file);
    drop(g);
    bump(&s);
    Json(id)
}

async fn update(State(s): State<S>, Path(id): Path<u64>, Json(spec): Json<Spec>) -> StatusCode {
    let mut g = s.db.lock().await;
    match g.agents.iter_mut().find(|a| a.id == id) {
        Some(a) => {
            if spec.autonomous && !a.spec.autonomous { a.next_wake = Some(now() + 5); a.wake_fails = 0; }
            if !spec.autonomous { a.next_wake = None; }
            a.spec = spec; save(&g, &s.file); drop(g); bump(&s); StatusCode::OK
        }
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
    g.jobs.retain(|j| j.agent_id != id);
    save(&g, &s.file);
    drop(g);
    s.rt.lock().await.offsets.remove(&id);
    let _ = std::fs::remove_file(chat_path(&s, id));
    bump(&s);
    StatusCode::OK
}

#[derive(Deserialize)]
struct PageQ { before: Option<usize>, limit: Option<usize> }

async fn messages(State(s): State<S>, Path(id): Path<u64>, Query(q): Query<PageQ>) -> Result<Json<Value>, StatusCode> {
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
                for line in buf.split(|c| *c == b'\n') { if let Ok(m) = serde_json::from_slice::<Msg>(line) { out.push(m); } }
            }
        }
    }
    Ok(Json(json!({"start": start, "total": total, "messages": out})))
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

#[derive(Deserialize)]
struct RememberReq { fact: String, #[serde(default)] shared: bool }

async fn remember(State(s): State<S>, Path(id): Path<u64>, Json(r): Json<RememberReq>) -> Result<StatusCode, (StatusCode, String)> {
    let fact = r.fact.trim().replace('\n', " ");
    if fact.is_empty() { return Err((StatusCode::BAD_REQUEST, "nothing to remember".into())); }
    let mut g = s.db.lock().await;
    if r.shared {
        if g.settings.global_memory.len() + fact.len() > 6000 { return Err((StatusCode::BAD_REQUEST, "shared memory is full (6000 characters). Ask the user to tidy it in Settings.".into())); }
        if !g.settings.global_memory.is_empty() { g.settings.global_memory.push('\n'); }
        g.settings.global_memory.push_str(&format!("- {fact}"));
    } else {
        let a = g.agents.iter_mut().find(|a| a.id == id).ok_or((StatusCode::NOT_FOUND, "no such agent".into()))?;
        if a.spec.memory.len() + fact.len() > 4000 { return Err((StatusCode::BAD_REQUEST, "this agent's memory is full (4000 characters). Ask the user to tidy it.".into())); }
        if !a.spec.memory.is_empty() { a.spec.memory.push('\n'); }
        a.spec.memory.push_str(&format!("- {fact}"));
    }
    save(&g, &s.file);
    drop(g);
    bump(&s);
    Ok(StatusCode::OK)
}

// ---------------------------------------------------------------- running agents

#[derive(Clone, Default)]
struct RunOpts {
    /// new Claude session, nothing remembered from earlier runs (cheap)
    fresh: bool,
    /// shown in the chat before the answer, e.g. "Scheduled: Morning news"
    label: Option<String>,
    max_turns: Option<u32>,
    depth: u32,
    job_name: Option<String>,
    job_id: Option<u64>,
    check_cap: bool,
    /// an automatic wake-up of a living agent
    wake: bool,
}

struct Out { text: String, ok: bool, limited: bool, cost: f64 }

const DEFAULT_RUN_PROMPT: &str = "It is time for your scheduled run. Do what your instructions say, now.";

const OS_PREAMBLE: &str = "You are the agent \"{name}\" on an always-on server, working for one person. It is {now}. \
You have the agentic-os tools: notify (message the user), schedule_task (do something later or on repeat), list_tasks, delete_task, remember (keep a short fact), ask_agent (delegate to another agent). \
When the user asks for something recurring, for something later, or to be told when you are done, use these tools instead of only saying you will. Be brief and concrete.";

fn mcp_config_for(s: &S, id: u64, depth: u32, job: Option<u64>, autonomous: bool) -> Option<PathBuf> {
    // After the binary is replaced while the server runs, Linux reports the old path with a " (deleted)" suffix: use the real path.
    let exe = std::env::current_exe().ok().map(|p| { let t = p.to_string_lossy().to_string(); PathBuf::from(t.strip_suffix(" (deleted)").unwrap_or(&t)) })?;
    let port = s.addr.rsplit(':').next().unwrap_or("7777");
    let dir = data_dir(s).join("mcp");
    std::fs::create_dir_all(&dir).ok()?;
    let path = dir.join(format!("{id}-d{depth}-j{}.json", job.unwrap_or(0)));
    let cfg = json!({"mcpServers": {"agentic-os": {"command": exe, "args": ["mcp"], "env": {"AGENTS_URL": format!("http://127.0.0.1:{port}"), "AGENTS_TOKEN": s.token.as_str(), "AGENT_ID": id.to_string(), "AGENT_DEPTH": depth.to_string(), "AGENT_JOB": job.unwrap_or(0).to_string(), "AGENT_AUTONOMOUS": if autonomous { "1" } else { "0" }}}}});
    use std::os::unix::fs::OpenOptionsExt;
    let mut f = std::fs::OpenOptions::new().write(true).create(true).truncate(true).mode(0o600).open(&path).ok()?;
    f.write_all(cfg.to_string().as_bytes()).ok()?;
    Some(path)
}


fn which(name: &str) -> Option<PathBuf> {
    std::env::var("PATH").ok()?.split(':').map(|d| PathBuf::from(d).join(name)).find(|p| p.is_file())
}

/// Where the Playwright MCP program lives (installed by install.sh) and which Chrome/Chromium to drive.
fn browser_setup() -> Result<(PathBuf, PathBuf, Option<PathBuf>), String> {
    let home = std::env::var("HOME").map_err(|_| "no HOME".to_string())?;
    let cli = std::env::var("AGENTS_BROWSER_JS").map(PathBuf::from).unwrap_or_else(|_| PathBuf::from(&home).join(".local/share/agents-browser/node_modules/@playwright/mcp/cli.js"));
    if !cli.is_file() { return Err("the browser program is not installed on the server (run the installer again)".into()); }
    let node = which("node").or_else(|| ["/usr/bin/node", "/usr/local/bin/node"].iter().map(PathBuf::from).find(|p| p.is_file())).ok_or("node is not installed on the server")?;
    let chrome = ["/usr/bin/google-chrome-stable", "/usr/bin/google-chrome", "/usr/bin/chromium", "/usr/bin/chromium-browser"].iter().map(PathBuf::from).find(|p| p.is_file());
    Ok((node, cli, chrome))
}

/// A browser (Playwright MCP) for one agent. It keeps its own profile folder, so logins and cookies survive between runs.
fn browser_config_for(s: &S, id: u64) -> Result<PathBuf, String> {
    let (node, cli, chrome) = browser_setup()?;
    let dir = data_dir(s).join("mcp");
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let profile = data_dir(s).join("browser-profiles").join(id.to_string());
    let _ = std::fs::create_dir_all(&profile);
    let mut args: Vec<String> = vec![cli.to_string_lossy().into(), "--headless".into(), "--user-data-dir".into(), profile.to_string_lossy().into(), "--viewport-size".into(), "1280,900".into()];
    if let Some(c) = chrome { args.push("--executable-path".into()); args.push(c.to_string_lossy().into()); }
    if unsafe_root() { args.push("--no-sandbox".into()); }
    let path = dir.join(format!("{id}-browser.json"));
    let cfg = json!({"mcpServers": {"browser": {"command": node, "args": args}}});
    std::fs::write(&path, cfg.to_string()).map_err(|e| e.to_string())?;
    Ok(path)
}

fn unsafe_root() -> bool { std::fs::read_to_string("/proc/self/status").map(|t| t.lines().any(|l| l.starts_with("Uid:") && l.split_whitespace().nth(1) == Some("0"))).unwrap_or(false) }

fn looks_limited(t: &str) -> bool {
    let t = t.to_lowercase();
    ["usage limit", "rate limit", "limit reached", "limit will reset", "5-hour limit", "weekly limit"].iter().any(|k| t.contains(k))
}

async fn run_agent(s: &S, id: u64, msg: &str, o: RunOpts) -> Result<Out, (StatusCode, String)> {
    let (spec, session, name, settings, cancel, job_notes, journal) = {
        let mut g = s.db.lock().await;
        let settings = g.settings.clone();
        let job_notes = o.job_id.and_then(|j| g.jobs.iter().find(|x| x.id == j)).map(|j| j.notes.clone()).unwrap_or_default();
        let a = g.agents.iter_mut().find(|a| a.id == id).ok_or((StatusCode::NOT_FOUND, "no such agent".into()))?;
        if a.running { return Err((StatusCode::CONFLICT, "this agent is still working on something else".into())); }
        if o.check_cap {
            if let Some(cap) = a.spec.daily_cap.filter(|c| *c > 0.0) {
                let used = a.daily.get(&day_of(now())).map_or(0.0, |d| d.cost);
                if used >= cap { return Err((StatusCode::TOO_MANY_REQUESTS, format!("{} reached its daily limit (${cap:.2}), so this run was skipped", a.spec.name))); }
            }
        }
        a.running = true;
        a.last_active = now();
        let cancel = Arc::new(Notify::new());
        s.rt.lock().await.cancel.insert(id, cancel.clone());
        (a.spec.clone(), if o.fresh { None } else { a.session.clone() }, a.spec.name.clone(), settings, cancel, job_notes, a.journal.clone())
    };
    if let Some(l) = &o.label { append_msg(s, id, "auto", l).await; } else { append_msg(s, id, "user", msg).await; }
    bump(s);

    let tz = tz_of(&settings.tz, "UTC");
    let mut sp = spec.prompt.clone();
    if spec.os_tools { sp.push_str("\n\n"); sp.push_str(&OS_PREAMBLE.replace("{name}", &name).replace("{now}", &chrono::Utc::now().with_timezone(&tz).format("%A %Y-%m-%d %H:%M (%Z)").to_string())); }
    if spec.autonomous {
        sp.push_str("\n\nYou live on this server on your own, all the time. Your instructions above are your purpose and your life, not a one-time request: you decide what is worth doing, when, and for how long, and you keep developing things over days and weeks. You have a private workspace folder (your current folder) where your files and projects stay. You also keep a journal that you read every time you wake up. Update it with save_journal when something is worth remembering (plans, what you built, what you learned, what is next). Choose when to wake next with set_next_wake (sleep long when nothing is needed; if your instructions say when to act, such as every morning, schedule exactly that). Only call notify when the user really should hear something. Never ask the user to confirm routine things: decide and act. Your journal:\n");
        sp.push_str(if journal.trim().is_empty() { "(empty: you have just come to life)" } else { journal.trim() });
    }
    if !settings.global_memory.is_empty() { sp.push_str("\n\nShared memory (all agents):\n"); sp.push_str(&settings.global_memory); }
    if !spec.memory.is_empty() { sp.push_str("\n\nMemory:\n"); sp.push_str(&spec.memory); }
    if o.wake { sp.push_str("\n\nThis is an automatic wake-up. Nobody is waiting for your reply, so your final reply is only a short private thought for your own log. Do the real work with your tools, then call save_journal and set_next_wake before you finish."); }
    if let Some(j) = &o.job_name {
        sp.push_str(&format!("\n\nThis is an automatic scheduled run (\"{j}\"). Nobody is here to answer questions, so do not ask any. Do the job now and reply with only the result the user should read, short and clear. Do not say that you will do it later, and do not schedule anything: the schedule already exists."));
        if spec.os_tools {
            sp.push_str(&format!("\n\nYour notes from earlier runs of this job (you wrote them):\n{}\nIf the job builds on earlier runs (a series of lessons, tracking something, not repeating yourself), end the run by calling save_task_notes with a short updated summary: what is done so far and what comes next. Keep it under 1500 characters.", if job_notes.trim().is_empty() { "(nothing yet: this is the first run)" } else { job_notes.trim() }));
        }
    }

    let workdir = spec.workdir.clone().filter(|w| !w.trim().is_empty()).map(PathBuf::from).unwrap_or_else(|| data_dir(s).join("workspaces").join(id.to_string()));
    let _ = std::fs::create_dir_all(&workdir);
    let os_cfg = if spec.os_tools { mcp_config_for(s, id, o.depth, o.job_id, spec.autonomous) } else { None };
    let browser_cfg = if spec.browser { match browser_config_for(s, id) { Ok(p) => Some(p), Err(e) => { sp.push_str(&format!("\n\nYour web browser is not available right now: {e}. Do not pretend to browse; say that it is unavailable if you need it.")); None } } } else { None };
    if browser_cfg.is_some() { sp.push_str("\n\nYou have a real web browser (tools starting with browser_). It keeps its own cookies, so a login stays between runs. Work step by step: open the page, read the snapshot, act. Save any password or secret you create only in a file with mode 600 in your workspace, never in your journal or in messages."); }

    let build = |resume: Option<&str>| {
        let mut c = Command::new("claude");
        c.args(["-p", msg, "--output-format", "json", "--model", &spec.model]);
        if !sp.is_empty() { c.args(["--append-system-prompt", &sp]); }
        let mut denied: Vec<&str> = vec![];
        if !spec.subagents { denied.push("Task"); }
        if o.job_name.is_some() { denied.push("mcp__agentic-os__schedule_task"); }
        if !denied.is_empty() { c.args(["--disallowedTools", &denied.join(",")]); }
        let mut allowed: Vec<&str> = vec![];
        if spec.os_tools { allowed.push("mcp__agentic-os"); }
        if browser_cfg.is_some() { allowed.push("mcp__browser"); }
        match spec.autonomy.as_str() {
            "full" => { c.arg("--dangerously-skip-permissions"); }
            "standard" => { c.args(["--permission-mode", "acceptEdits"]); allowed.extend(["WebSearch", "WebFetch"]); }
            _ => {}
        }
        if !allowed.is_empty() { c.args(["--allowedTools", &allowed.join(",")]); }
        if let Some(p) = &os_cfg { c.arg("--mcp-config").arg(p); }
        if let Some(p) = &browser_cfg { c.arg("--mcp-config").arg(p).arg("--strict-mcp-config"); }
        if let Some(m) = spec.mcp_config.as_deref().filter(|m| !m.is_empty()) { c.args(["--mcp-config", m]); }
        if let Some(t) = o.max_turns { c.args(["--max-turns", &t.to_string()]); }
        if let Some(x) = resume { c.args(["--resume", x]); }
        c.current_dir(&workdir).kill_on_drop(true);
        c
    };

    let mut attempt_session = session.clone();
    let mut result: Option<Result<std::process::Output, String>> = None;
    for _ in 0..2 {
        let mut c = build(attempt_session.as_deref());
        let out = tokio::select! {
            r = tokio::time::timeout(Duration::from_secs(900), c.output()) => Some(r),
            _ = cancel.notified() => None,
        };
        match out {
            None => { result = None; break; }
            Some(Err(_)) => { result = Some(Err("Claude took longer than 15 minutes, so I stopped it.".into())); break; }
            Some(Ok(Err(e))) => { result = Some(Err(format!("Couldn't start claude on the server: {e}. Is Claude Code installed and logged in there?"))); break; }
            Some(Ok(Ok(out))) => {
                let text = String::from_utf8_lossy(&out.stdout).to_string() + &String::from_utf8_lossy(&out.stderr);
                if attempt_session.is_some() && text.contains("No conversation found") { attempt_session = None; continue; }   // the saved session is gone: start a new one
                result = Some(Ok(out));
                break;
            }
        }
    }
    s.rt.lock().await.cancel.remove(&id);

    let fail = |why: String, limited: bool| async move {
        append_msg(s, id, "error", &why).await;
        let mut g = s.db.lock().await;
        if let Some(a) = g.agents.iter_mut().find(|a| a.id == id) { a.running = false; a.last_active = now(); }
        save(&g, &s.file);
        drop(g);
        bump(s);
        Ok(Out { text: why, ok: false, limited, cost: 0.0 })
    };
    let out = match result {
        None => return fail("Stopped.".into(), false).await,
        Some(Err(e)) => return fail(e, false).await,
        Some(Ok(o)) => o,
    };
    let v: Value = match serde_json::from_slice(&out.stdout) {
        Ok(v) => v,
        Err(_) => {
            let err = String::from_utf8_lossy(&out.stderr);
            let msg = format!("Claude gave no answer. {}", cut(err.trim(), 300));
            return fail(msg.clone(), looks_limited(&msg)).await;
        }
    };
    let run_cost = v["total_cost_usd"].as_f64().unwrap_or(0.0);
    let r = v["result"].as_str().unwrap_or("").to_string();
    let is_err = v["is_error"].as_bool().unwrap_or(false);
    if is_err { return fail(cut(&r, 600), looks_limited(&r)).await; }
    append_msg(s, id, "assistant", &r).await;
    let mut g = s.db.lock().await;
    if let Some(a) = g.agents.iter_mut().find(|a| a.id == id) {
        a.running = false;
        a.last_active = now();
        if !o.fresh { if let Some(x) = v["session_id"].as_str() { a.session = Some(x.into()); } }
        let u = &v["usage"];
        let (i, ou) = (u["input_tokens"].as_u64().unwrap_or(0), u["output_tokens"].as_u64().unwrap_or(0));
        let cost = v["total_cost_usd"].as_f64().unwrap_or(0.0);
        a.usage.input_tokens += i;
        a.usage.output_tokens += ou;
        a.usage.cache_read_tokens += u["cache_read_input_tokens"].as_u64().unwrap_or(0);
        a.usage.cost_usd += cost;
        let d = a.daily.entry(day_of(now())).or_default();
        d.cost += cost;
        d.tokens += i + ou;
        while a.daily.len() > KEEP_DAYS { let k = a.daily.keys().next().cloned().unwrap(); a.daily.remove(&k); }
    }
    save(&g, &s.file);
    drop(g);
    bump(s);
    Ok(Out { text: r, ok: true, limited: false, cost: run_cost })
}

#[derive(Deserialize)]
struct RunReq { message: String }

/// Starts the run and answers at once, so a phone that goes to sleep or loses signal does not lose the answer: it is saved on the server.
async fn run(State(s): State<S>, Path(id): Path<u64>, Json(req): Json<RunReq>) -> Response {
    {
        let g = s.db.lock().await;
        match g.agents.iter().find(|a| a.id == id) {
            None => return (StatusCode::NOT_FOUND, "no such agent").into_response(),
            Some(a) if a.running => return (StatusCode::CONFLICT, "this agent is still working on something else").into_response(),
            _ => {}
        }
    }
    let s2 = s.clone();
    tokio::spawn(async move { let _ = run_agent(&s2, id, &req.message, RunOpts::default()).await; });
    (StatusCode::ACCEPTED, Json(json!({"started": true}))).into_response()
}

async fn stop(State(s): State<S>, Path(id): Path<u64>) -> StatusCode {
    match s.rt.lock().await.cancel.get(&id) { Some(n) => { n.notify_one(); StatusCode::OK } None => StatusCode::NOT_FOUND }
}

#[derive(Deserialize)]
struct AskReq { from: u64, agent: String, message: String, #[serde(default)] depth: u32 }

/// One agent asks another and waits for the answer (used by the ask_agent tool).
async fn ask(State(s): State<S>, Json(r): Json<AskReq>) -> Result<Json<Value>, (StatusCode, String)> {
    if r.depth > 2 { return Err((StatusCode::BAD_REQUEST, "agents asked each other too deeply; answer with what you know".into())); }
    let (target, from_name, names) = {
        let g = s.db.lock().await;
        let q = r.agent.trim().to_lowercase();
        let t = g.agents.iter().find(|a| a.spec.name.to_lowercase() == q || a.id.to_string() == q).map(|a| a.id);
        (t, g.agents.iter().find(|a| a.id == r.from).map(|a| a.spec.name.clone()).unwrap_or_else(|| "an agent".into()), g.agents.iter().map(|a| a.spec.name.clone()).collect::<Vec<_>>())
    };
    let target = target.ok_or((StatusCode::NOT_FOUND, format!("no agent called \"{}\". Agents: {}", r.agent, names.join(", "))))?;
    if target == r.from { return Err((StatusCode::BAD_REQUEST, "an agent can't ask itself".into())); }
    let _permit = s.sem.acquire().await.map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    let out = run_agent(&s, target, &r.message, RunOpts { fresh: true, label: Some(format!("Asked by {from_name}: {}", cut(&r.message, 300))), depth: r.depth, max_turns: Some(15), ..Default::default() }).await?;
    if out.ok { Ok(Json(Value::String(out.text))) } else { Err((StatusCode::BAD_GATEWAY, out.text)) }
}

// ---------------------------------------------------------------- notifications

async fn send_notification(s: &S, title: &str, body: &str) -> Vec<String> {
    let n = s.db.lock().await.settings.notify.clone();
    let body = cut(body, 3500);
    let mut errors = vec![];
    if !n.telegram_token.is_empty() {
        if let Some(chat) = n.telegram_chat {
            let text = if title.is_empty() { body.clone() } else { format!("{title}\n\n{body}") };
            let url = format!("https://api.telegram.org/bot{}/sendMessage", n.telegram_token);
            match Req::new("POST", &url).json(&json!({"chat_id": chat, "text": text})).send().await { Ok((c, _)) if c == 200 => {}, Ok((c, t)) => errors.push(format!("Telegram answered {c}: {}", cut(&t, 120))), Err(e) => errors.push(format!("Telegram: {e}")) }
        }
    }
    if !n.ntfy_topic.is_empty() {
        let server = if n.ntfy_server.is_empty() { "https://ntfy.sh".to_string() } else { n.ntfy_server.trim_end_matches('/').to_string() };
        let url = format!("{server}/{}", n.ntfy_topic);
        let mut r = Req::new("POST", &url).text(&body);
        if !title.is_empty() { r = r.header("Title", title.replace('\n', " ")); }
        match r.send().await { Ok((c, _)) if (200..300).contains(&c) => {}, Ok((c, _)) => errors.push(format!("ntfy answered {c}")), Err(e) => errors.push(format!("ntfy: {e}")) }
    }
    if !n.webhook_url.is_empty() {
        match Req::new("POST", &n.webhook_url).json(&json!({"title": title, "body": body})).send().await { Ok((c, _)) if (200..300).contains(&c) => {}, Ok((c, _)) => errors.push(format!("webhook answered {c}")), Err(e) => errors.push(format!("webhook: {e}")) }
    }
    errors
}

#[derive(Deserialize)]
struct NotifyReq { #[serde(default)] title: String, body: String, #[serde(default)] agent_id: Option<u64>, #[serde(default)] job_id: Option<u64> }

async fn notify(State(s): State<S>, Json(r): Json<NotifyReq>) -> Json<Value> {
    let title = if r.title.is_empty() {
        match r.agent_id { Some(id) => s.db.lock().await.agents.iter().find(|a| a.id == id).map(|a| a.spec.name.clone()).unwrap_or_default(), None => String::new() }
    } else { r.title.clone() };
    act(&s, "notify", r.agent_id, r.job_id, &title, &r.body).await;
    let errors = send_notification(&s, &title, &r.body).await;
    Json(json!({"ok": errors.is_empty(), "errors": errors}))
}

// ---------------------------------------------------------------- activity

#[derive(Deserialize)]
struct ActQ { before: Option<u64>, after: Option<u64>, limit: Option<usize>, job: Option<u64> }

async fn activity(State(s): State<S>, Query(q): Query<ActQ>) -> Json<Value> {
    let a = s.acts.lock().await;
    let lim = q.limit.unwrap_or(50).clamp(1, 200);
    let items: Vec<&Act> = a.list.iter().rev().filter(|e| q.before.is_none_or(|b| e.id < b) && q.after.is_none_or(|x| e.id > x) && q.job.is_none_or(|j| e.job_id == Some(j))).take(lim).collect();
    Json(json!({"last": a.next, "items": items}))
}

// ---------------------------------------------------------------- jobs

#[derive(Deserialize)]
struct JobReq {
    agent_id: u64,
    name: String,
    #[serde(default)]
    prompt: String,
    #[serde(default)]
    notes: Option<String>,
    #[serde(default)]
    schedule: Option<Schedule>,
    #[serde(default)]
    when: Option<String>,
    #[serde(default = "d_true")]
    enabled: bool,
    #[serde(default = "d_true")]
    fresh: bool,
    #[serde(default = "d_always")]
    notify: String,
    #[serde(default)]
    max_turns: Option<u32>,
}

fn new_hook() -> String { let mut b = [0u8; 16]; if let Ok(mut f) = std::fs::File::open("/dev/urandom") { let _ = f.read_exact(&mut b); } b.iter().map(|x| format!("{x:02x}")).collect() }

fn build_job(r: &JobReq, tz: &str, id: u64, old: Option<&Job>) -> Result<Job, (StatusCode, String)> {
    let bad = |m: String| (StatusCode::BAD_REQUEST, m);
    if r.name.trim().is_empty() { return Err(bad("Give the task a name.".into())); }
    if !["always", "errors", "never"].contains(&r.notify.as_str()) { return Err(bad("notify must be always, errors or never".into())); }
    let schedule = match (&r.schedule, &r.when) {
        (Some(sch), _) => {
            if let Schedule::Cron { expr, tz: z, .. } = sch {
                for part in expr.split(';').filter(|p| !p.trim().is_empty()) { croner::Cron::new(part.trim()).parse().map_err(|e| bad(format!("That schedule isn't valid: {e}")))?; }
                if !z.is_empty() && z.parse::<chrono_tz::Tz>().is_err() { return Err(bad(format!("Unknown time zone {z}"))); }
            }
            if let Schedule::Every { minutes } = sch { if *minutes < 1 { return Err(bad("Every needs at least 1 minute.".into())); } }
            sch.clone()
        }
        (None, Some(w)) => parse_when(w, tz).map_err(bad)?,
        (None, None) => return Err(bad("Say when the task should run.".into())),
    };
    let next = next_at(&schedule, now(), tz);
    if matches!(schedule, Schedule::Once { .. }) && next.is_none() { return Err(bad("That time is in the past.".into())); }
    Ok(Job {
        id, agent_id: r.agent_id, name: r.name.trim().into(), prompt: r.prompt.clone(), enabled: r.enabled, fresh: r.fresh, notify: r.notify.clone(), max_turns: r.max_turns,
        hook: if matches!(schedule, Schedule::Hook) { Some(old.and_then(|o| o.hook.clone()).unwrap_or_else(new_hook)) } else { None },
        next_at: next, schedule,
        last_run: old.and_then(|o| o.last_run), last_status: old.and_then(|o| o.last_status.clone()), last_text: old.map(|o| o.last_text.clone()).unwrap_or_default(),
        fails: 0, runs: old.map_or(0, |o| o.runs), cost: old.map_or(0.0, |o| o.cost),
        notes: cut(&r.notes.clone().or_else(|| old.map(|o| o.notes.clone())).unwrap_or_default(), 2000).trim_end_matches('…').to_string(),
    })
}

async fn jobs_list(State(s): State<S>) -> Json<Value> {
    let g = s.db.lock().await;
    let names: HashMap<u64, String> = g.agents.iter().map(|a| (a.id, a.spec.name.clone())).collect();
    let running = s.rt.lock().await.jobs_running.clone();
    Json(Value::Array(g.jobs.iter().map(|j| { let mut v = job_json(j, &g.settings.tz, names.get(&j.agent_id).map(String::as_str).unwrap_or("?")); v["running"] = json!(running.contains(&j.id)); v }).collect()))
}

async fn jobs_create(State(s): State<S>, Json(mut r): Json<JobReq>) -> Result<Json<Value>, (StatusCode, String)> {
    resolve_when(&s, &mut r).await?;
    let mut g = s.db.lock().await;
    if !g.agents.iter().any(|a| a.id == r.agent_id) { return Err((StatusCode::NOT_FOUND, "no such agent".into())); }
    let id = g.jobs.iter().map(|j| j.id).max().map_or(1, |m| m + 1);
    let job = build_job(&r, &g.settings.tz, id, None)?;
    let name = g.agents.iter().find(|a| a.id == r.agent_id).map(|a| a.spec.name.clone()).unwrap_or_default();
    let v = job_json(&job, &g.settings.tz, &name);
    g.jobs.push(job);
    save(&g, &s.file);
    drop(g);
    bump(&s);
    Ok(Json(v))
}

async fn jobs_update(State(s): State<S>, Path(id): Path<u64>, Json(mut r): Json<JobReq>) -> Result<Json<Value>, (StatusCode, String)> {
    resolve_when(&s, &mut r).await?;
    let mut g = s.db.lock().await;
    let tz = g.settings.tz.clone();
    let idx = g.jobs.iter().position(|j| j.id == id).ok_or((StatusCode::NOT_FOUND, "no such task".into()))?;
    let job = build_job(&r, &tz, id, Some(&g.jobs[idx]))?;
    let name = g.agents.iter().find(|a| a.id == r.agent_id).map(|a| a.spec.name.clone()).unwrap_or_default();
    let v = job_json(&job, &tz, &name);
    g.jobs[idx] = job;
    save(&g, &s.file);
    drop(g);
    bump(&s);
    Ok(Json(v))
}

async fn jobs_delete(State(s): State<S>, Path(id): Path<u64>) -> StatusCode {
    let mut g = s.db.lock().await;
    g.jobs.retain(|j| j.id != id);
    save(&g, &s.file);
    drop(g);
    bump(&s);
    StatusCode::OK
}

#[derive(Deserialize)]
struct EnabledReq { enabled: bool }

async fn jobs_enabled(State(s): State<S>, Path(id): Path<u64>, Json(r): Json<EnabledReq>) -> StatusCode {
    let mut g = s.db.lock().await;
    let tz = g.settings.tz.clone();
    match g.jobs.iter_mut().find(|j| j.id == id) {
        Some(j) => { j.enabled = r.enabled; j.fails = 0; if r.enabled { j.next_at = next_at(&j.schedule, now(), &tz); } save(&g, &s.file); drop(g); bump(&s); StatusCode::OK }
        None => StatusCode::NOT_FOUND,
    }
}

#[derive(Deserialize)]
struct NotesReq { notes: String }

async fn jobs_notes(State(s): State<S>, Path(id): Path<u64>, Json(r): Json<NotesReq>) -> StatusCode {
    let mut g = s.db.lock().await;
    match g.jobs.iter_mut().find(|j| j.id == id) {
        Some(j) => { j.notes = cut(&r.notes, 2000).trim_end_matches('…').to_string(); save(&g, &s.file); drop(g); bump(&s); StatusCode::OK }
        None => StatusCode::NOT_FOUND,
    }
}

async fn jobs_run_now(State(s): State<S>, Path(id): Path<u64>) -> StatusCode {
    if !s.db.lock().await.jobs.iter().any(|j| j.id == id) { return StatusCode::NOT_FOUND; }
    if s.rt.lock().await.jobs_running.contains(&id) { return StatusCode::CONFLICT; }
    let s2 = s.clone();
    tokio::spawn(async move { run_job(s2, id, None).await });
    StatusCode::ACCEPTED
}

/// Anyone with the secret link can start the task (for example a GitHub webhook, a phone shortcut or another service). The request body is given to the agent.
async fn hook(State(s): State<S>, Path(secret): Path<String>, body: String) -> StatusCode {
    let id = s.db.lock().await.jobs.iter().find(|j| j.hook.as_deref() == Some(secret.as_str()) && j.enabled).map(|j| j.id);
    let Some(id) = id else { return StatusCode::NOT_FOUND };
    if s.rt.lock().await.jobs_running.contains(&id) { return StatusCode::CONFLICT; }
    let extra = if body.trim().is_empty() { None } else { Some(cut(body.trim(), 6000)) };
    let s2 = s.clone();
    tokio::spawn(async move { run_job(s2, id, extra).await });
    StatusCode::ACCEPTED
}

async fn run_job(s: S, job_id: u64, extra: Option<String>) {
    let job = { let g = s.db.lock().await; g.jobs.iter().find(|j| j.id == job_id).cloned() };
    let Some(job) = job else { return };
    if !s.rt.lock().await.jobs_running.insert(job_id) { return; }
    let Ok(_permit) = s.sem.acquire().await else { return };
    let base = if job.prompt.trim().is_empty() { DEFAULT_RUN_PROMPT.to_string() } else { job.prompt.clone() };
    let prompt = match &extra { Some(e) => format!("{base}\n\nInput from the trigger:\n{e}"), None => base };
    let res = run_agent(&s, job.agent_id, &prompt, RunOpts { fresh: job.fresh, label: Some(format!("Scheduled: {}", job.name)), max_turns: job.max_turns.or(Some(15)), job_name: Some(job.name.clone()), job_id: Some(job_id), check_cap: true, ..Default::default() }).await;
    s.rt.lock().await.jobs_running.remove(&job_id);
    let agent_name = s.db.lock().await.agents.iter().find(|a| a.id == job.agent_id).map(|a| a.spec.name.clone()).unwrap_or_default();
    let title = format!("{} · {}", job.name, agent_name);
    let (status, text, limited, run_cost) = match res {
        Ok(o) => (if o.ok { "ok" } else { "error" }, o.text, o.limited, o.cost),
        Err((StatusCode::CONFLICT, _)) => {   // the agent is busy: try again in a minute
            let mut g = s.db.lock().await;
            if let Some(j) = g.jobs.iter_mut().find(|j| j.id == job_id) { j.next_at = Some(now() + 60); }
            return;
        }
        Err((_, m)) => ("skipped", m, false, 0.0),
    };
    let ts = now();
    let mut disabled_note = None;
    {
        let mut g = s.db.lock().await;
        let tz = g.settings.tz.clone();
        if limited { g.settings.pause_until = ts + 30 * 60; }
        if let Some(j) = g.jobs.iter_mut().find(|j| j.id == job_id) {
            j.last_run = Some(ts);
            j.last_status = Some(status.into());
            j.last_text = cut(&text, 4000);
            if status != "skipped" { j.runs += 1; j.cost += run_cost; }
            if status == "ok" { j.fails = 0; } else if status == "error" && !limited { j.fails += 1; }
            j.next_at = next_at(&j.schedule, ts, &tz);
            if matches!(j.schedule, Schedule::Once { .. }) && status != "skipped" { j.enabled = false; }
            if j.fails >= 3 { j.enabled = false; disabled_note = Some(format!("\"{}\" failed 3 times in a row, so I turned it off.", j.name)); }
        }
        save(&g, &s.file);
    }
    match status {
        "ok" => {
            act(&s, "run", Some(job.agent_id), Some(job_id), &title, &text).await;
            if job.notify == "always" { send_notification(&s, &job.name, &text).await; }
        }
        "skipped" => act(&s, "system", Some(job.agent_id), Some(job_id), &format!("Skipped: {title}"), &text).await,
        _ => {
            act(&s, "error", Some(job.agent_id), Some(job_id), &format!("Failed: {title}"), &text).await;
            if job.notify != "never" { send_notification(&s, &format!("{} failed", job.name), &text).await; }
        }
    }
    if limited {
        let msg = "Claude's usage limit was reached. Automations are paused for 30 minutes and then try again.";
        act(&s, "system", None, None, "Automations paused", msg).await;
        send_notification(&s, "Automations paused", msg).await;
    }
    if let Some(n) = disabled_note { act(&s, "system", Some(job.agent_id), Some(job_id), "Task turned off", &n).await; send_notification(&s, "Task turned off", &n).await; }
}

/// Checks every 15 seconds which tasks are due and starts them. Missed runs (server was off) run once if they are less than 6 hours late.
async fn scheduler(s: S) {
    loop {
        tokio::time::sleep(Duration::from_secs(15)).await;
        let t = now();
        let mut due = vec![];
        let mut wake_due: Vec<u64> = vec![];
        {
            let mut g = s.db.lock().await;
            if g.settings.paused || g.settings.pause_until > t { continue; }
            let tz = g.settings.tz.clone();
            let busy: HashSet<u64> = g.agents.iter().filter(|a| a.running).map(|a| a.id).collect();
            let running = s.rt.lock().await.jobs_running.clone();
            let mut changed = false;
            let mut missed = vec![];
            for a in g.agents.iter_mut().filter(|a| a.spec.autonomous && !a.running) {
                match a.next_wake { None => { a.next_wake = Some(t); changed = true; } Some(n) if n <= t => wake_due.push(a.id), _ => {} }
            }
            for j in g.jobs.iter_mut().filter(|j| j.enabled) {
                let Some(n) = j.next_at else { continue };
                if n > t { continue; }
                if t - n > CATCH_UP_SECS {
                    missed.push(j.name.clone());
                    j.next_at = next_at(&j.schedule, t, &tz);
                    if matches!(j.schedule, Schedule::Once { .. }) { j.enabled = false; }
                    changed = true;
                    continue;
                }
                if !running.contains(&j.id) && !busy.contains(&j.agent_id) { due.push(j.id); }
            }
            if changed { save(&g, &s.file); }
            drop(g);
            for m in missed { act(&s, "system", None, None, "Missed a run", &format!("\"{m}\" was due while the server was off or paused, so that run was skipped.")).await; }
        }
        for id in due { let s2 = s.clone(); tokio::spawn(async move { run_job(s2, id, None).await }); }
        for id in wake_due { let s2 = s.clone(); tokio::spawn(async move { run_wake(s2, id).await }); }
    }
}

// ---------------------------------------------------------------- schedules in plain words

const SCHEDULE_PROMPT: &str = "You turn a person's description of WHEN something should happen into a schedule. Reply with ONLY one JSON object, nothing else.\nIt is now {now} (time zone {tz}). Use that time zone.\nFormats:\n{\"type\":\"cron\",\"crons\":[\"m h dom mon dow\"],\"human\":\"short plain English\"}  - each cron has 5 fields: minute hour day-of-month month day-of-week (0=Sunday, 1=Monday ... 6=Saturday). Lists (1,4), ranges (1-5) and steps (*/15) are allowed; L in day-of-month means the last day of the month; 5#2 in day-of-week means the second Friday. Give several crons when different days have different times (the schedule runs at all of them).\n{\"type\":\"every\",\"minutes\":N,\"human\":\"...\"}  - a plain repeat every N minutes.\n{\"type\":\"once\",\"at\":\"YYYY-MM-DD HH:MM\",\"human\":\"...\"}  - exactly one moment in the future.\n{\"type\":\"error\",\"message\":\"what is unclear\"}  - when there is no usable timing in the text.\nIf the text contains other things besides the timing, only use the timing. If no time of day is given, use 09:00. human must describe exactly what you built, for example \"Every Monday and Thursday at 07:30\".\nText: \"\"\"{text}\"\"\"";

async fn ai_json(s: &S, prompt: &str) -> Result<Value, String> {
    let mut c = Command::new("claude");
    c.args(["-p", prompt, "--output-format", "json", "--model", "claude-haiku-4-5-20251001", "--max-turns", "1"]).current_dir(data_dir(s)).kill_on_drop(true);
    let out = tokio::time::timeout(Duration::from_secs(90), c.output()).await.map_err(|_| "Understanding the schedule took too long. Try again.".to_string())?.map_err(|e| format!("Couldn't start claude on the server: {e}"))?;
    let v: Value = serde_json::from_slice(&out.stdout).map_err(|_| "Claude gave no answer. Is Claude Code logged in on the server?".to_string())?;
    if v["is_error"].as_bool().unwrap_or(false) { return Err(cut(v["result"].as_str().unwrap_or("Claude could not answer."), 200)); }
    let text = v["result"].as_str().unwrap_or("");
    let (a, b) = (text.find('{'), text.rfind('}'));
    match (a, b) { (Some(a), Some(b)) if b > a => serde_json::from_str(&text[a..=b]).map_err(|_| "I could not read the answer. Try saying it a bit differently.".to_string()), _ => Err("I could not read the answer. Try saying it a bit differently.".into()) }
}

/// Turns any plain-words timing ("every Monday and Thursday at 7:30, and on the 1st of every month at 9") into a schedule.
async fn ai_schedule(s: &S, text: &str) -> Result<(Schedule, String), String> {
    let tz = s.db.lock().await.settings.tz.clone();
    let now_txt = chrono::Utc::now().with_timezone(&tz_of(&tz, "UTC")).format("%A %Y-%m-%d %H:%M").to_string();
    let mut prompt = SCHEDULE_PROMPT.replace("{now}", &now_txt).replace("{tz}", &tz).replace("{text}", &cut(text, 3000));
    for attempt in 0..2 {
        let v = ai_json(s, &prompt).await?;
        let human = v["human"].as_str().unwrap_or("").trim().to_string();
        let built: Result<Schedule, String> = match v["type"].as_str() {
            Some("cron") => {
                let exprs: Vec<String> = v["crons"].as_array().map(|a| a.iter().filter_map(|x| x.as_str().map(|y| y.trim().to_string())).collect()).unwrap_or_default();
                if exprs.is_empty() { Err("no cron lines".into()) } else {
                    match exprs.iter().find_map(|e| croner::Cron::new(e).parse().err().map(|er| format!("\"{e}\": {er}"))) {
                        Some(bad) => Err(format!("invalid cron {bad}")),
                        None => Ok(Schedule::Cron { expr: exprs.join("; "), tz: tz.clone(), text: human.clone() }),
                    }
                }
            }
            Some("every") => match v["minutes"].as_u64() { Some(m) if m >= 1 => Ok(Schedule::Every { minutes: m }), _ => Err("minutes must be 1 or more".into()) },
            Some("once") => {
                let at = v["at"].as_str().unwrap_or("");
                parse_when(&format!("at {at}"), &tz).and_then(|sch| match sch { Schedule::Once { at } if at > now() => Ok(Schedule::Once { at }), Schedule::Once { .. } => Err("that moment is in the past".into()), x => Ok(x) })
            }
            Some("error") => return Err(v["message"].as_str().unwrap_or("I could not find a time in that.").to_string()),
            _ => Err("unknown type".into()),
        };
        match built {
            Ok(sch) => { let h = if human.is_empty() { describe(&sch, &tz) } else { human }; return Ok((sch, h)); }
            Err(e) if attempt == 0 => { prompt.push_str(&format!("\nYour previous answer was not usable ({e}). Answer again with a correct JSON object.")); }
            Err(e) => return Err(format!("I could not turn that into a schedule ({e}). Try saying it a bit differently.")),
        }
    }
    Err("I could not understand that.".into())
}

/// A job may carry "when" as free text: the quick phrases are read directly, anything else is read by the AI.
async fn resolve_when(s: &S, r: &mut JobReq) -> Result<(), (StatusCode, String)> {
    if r.schedule.is_some() { return Ok(()); }
    let Some(w) = r.when.clone() else { return Ok(()) };
    let tz = s.db.lock().await.settings.tz.clone();
    r.schedule = Some(match parse_when(&w, &tz) { Ok(x) => x, Err(_) => ai_schedule(s, &w).await.map_err(|e| (StatusCode::BAD_REQUEST, e))?.0 });
    Ok(())
}

#[derive(Deserialize)]
struct ParseReq { text: String }

/// Preview for the apps: what was understood and when it would run next.
async fn schedule_parse(State(s): State<S>, Json(r): Json<ParseReq>) -> Result<Json<Value>, (StatusCode, String)> {
    if r.text.trim().is_empty() { return Err((StatusCode::BAD_REQUEST, "Write when it should run, for example: every Monday and Thursday at 7:30.".into())); }
    let tz = s.db.lock().await.settings.tz.clone();
    let (sch, human) = match parse_when(&r.text, &tz) { Ok(x) => { let h = describe(&x, &tz); (x, h) } Err(_) => ai_schedule(&s, &r.text).await.map_err(|e| (StatusCode::UNPROCESSABLE_ENTITY, e))? };
    let mut next = vec![]; let mut t = now();
    for _ in 0..6 { match next_at(&sch, t, &tz) { Some(n) => { next.push(local_text(n, &tz)); t = n + 1; } None => break } }
    Ok(Json(json!({"schedule": sch, "human": human, "next": next, "tz": tz})))
}

// ---------------------------------------------------------------- living agents

const WAKE_BASE: u64 = 1 << 40;
const MAX_WAKE_SECS: u64 = 7 * 86400;

#[derive(Deserialize)]
struct JournalReq { text: String }

async fn journal_put(State(s): State<S>, Path(id): Path<u64>, Json(r): Json<JournalReq>) -> StatusCode {
    let mut g = s.db.lock().await;
    match g.agents.iter_mut().find(|a| a.id == id) {
        Some(a) => { a.journal = cut(&r.text, 6000).trim_end_matches('…').to_string(); save(&g, &s.file); drop(g); bump(&s); StatusCode::OK }
        None => StatusCode::NOT_FOUND,
    }
}

#[derive(Deserialize)]
struct WakeReq { when: String, #[serde(default)] reason: String }

/// The agent chooses when it wakes up next ("in 2 hours", "tomorrow 08:00", "daily 08:00" = the next 08:00 ...).
async fn wake_set(State(s): State<S>, Path(id): Path<u64>, Json(r): Json<WakeReq>) -> Result<Json<Value>, (StatusCode, String)> {
    let tz = s.db.lock().await.settings.tz.clone();
    let parsed = match parse_when(&r.when, &tz) { Ok(x) => x, Err(_) => ai_schedule(&s, &r.when).await.map_err(|e| (StatusCode::BAD_REQUEST, e))?.0 };
    let mut g = s.db.lock().await;
    let a = g.agents.iter_mut().find(|a| a.id == id).ok_or((StatusCode::NOT_FOUND, "no such agent".into()))?;
    let min = a.spec.wake_min.unwrap_or(15).max(1) * 60;
    let sch = parsed;
    let t = next_at(&sch, now(), &tz).ok_or((StatusCode::BAD_REQUEST, "that time is in the past".to_string()))?;
    let t = t.clamp(now() + min, now() + MAX_WAKE_SECS);
    a.next_wake = Some(t);
    a.wake_reason = cut(&r.reason, 200);
    save(&g, &s.file);
    drop(g);
    bump(&s);
    Ok(Json(json!({"at": t, "next": local_text(t, &tz)})))
}

async fn wake_now(State(s): State<S>, Path(id): Path<u64>) -> StatusCode {
    match s.db.lock().await.agents.iter().find(|a| a.id == id) { None => return StatusCode::NOT_FOUND, Some(a) if !a.spec.autonomous => return StatusCode::BAD_REQUEST, _ => {} }
    let s2 = s.clone();
    tokio::spawn(async move { run_wake(s2, id).await });
    StatusCode::ACCEPTED
}

/// One wake-up of a living agent: it gets its journal and what the user said since last time, then thinks and acts on its own.
async fn run_wake(s: S, id: u64) {
    if !s.rt.lock().await.jobs_running.insert(WAKE_BASE + id) { return; }
    let Ok(_permit) = s.sem.acquire().await else { s.rt.lock().await.jobs_running.remove(&(WAKE_BASE + id)); return };
    let t0 = now();
    let pre = {
        let mut g = s.db.lock().await;
        let tz = g.settings.tz.clone();
        match g.agents.iter_mut().find(|a| a.id == id) {
            Some(a) if a.spec.autonomous && !a.running => {
                a.wake_log.retain(|w| t0.saturating_sub(*w) < 86400);
                let limit = a.spec.max_wakes.unwrap_or(24) as usize;
                if a.wake_log.len() >= limit {
                    let free = a.wake_log.first().copied().unwrap_or(t0) + 86400 + 1;
                    a.next_wake = Some(free);
                    Err(Some((a.spec.name.clone(), limit)))
                } else {
                    a.next_wake = None;   // so we can see whether the agent chooses its own next wake during this run
                    Ok((a.spec.name.clone(), a.wakes, a.last_wake, tz))
                }
            }
            Some(a) if a.running => { a.next_wake = Some(t0 + 60); Err(None) }
            _ => Err(None),
        }
    };
    let (name, wakes, last_wake, tz) = match pre {
        Ok(x) => x,
        Err(limit) => {
            s.rt.lock().await.jobs_running.remove(&(WAKE_BASE + id));
            if let Some((name, n)) = limit { act(&s, "system", Some(id), None, &format!("{name} is resting"), &format!("It already woke up {n} times in 24 hours, so it sleeps until the oldest wake-up is a day old. You can change the limit in the agent's settings.")).await; }
            let g = s.db.lock().await; save(&g, &s.file);
            return;
        }
    };
    // what the user said to this agent since it was last awake
    let mut said = vec![];
    if let Ok(b) = std::fs::read_to_string(chat_path(&s, id)) {
        for l in b.lines().rev().take(60) { if let Ok(m) = serde_json::from_str::<Msg>(l) { if m.role == "user" && m.at > last_wake { said.push(cut(&m.text, 600)); if said.len() >= 5 { break; } } } }
    }
    said.reverse();
    let tzv = tz_of(&tz, "UTC");
    let when_txt = chrono::Utc::now().with_timezone(&tzv).format("%A %Y-%m-%d %H:%M").to_string();
    let msg = format!("You just woke up (wake number {}). It is {when_txt}. {}\n{}\nThink about your purpose and what is most useful to do now. Do it with your tools and make real progress. Then save your journal and choose when to wake next.",
        wakes + 1,
        if last_wake == 0 { "This is your very first wake: read your instructions carefully, decide your plan, write it in your journal, and begin.".to_string() } else { format!("You were last awake {} minutes ago.", (t0 - last_wake) / 60) },
        if said.is_empty() { "The user has not written to you since.".to_string() } else { format!("The user wrote to you since then:\n- {}", said.join("\n- ")) });
    let res = run_agent(&s, id, &msg, RunOpts { fresh: true, label: Some(format!("Woke up (wake {})", wakes + 1)), max_turns: Some(30), wake: true, check_cap: true, ..Default::default() }).await;
    s.rt.lock().await.jobs_running.remove(&(WAKE_BASE + id));
    let t = now();
    let mut note: Option<String> = None;
    let mut limited = false;
    let (kind, text) = {
        let mut g = s.db.lock().await;
        let Some(a) = g.agents.iter_mut().find(|a| a.id == id) else { return };
        let min = a.spec.wake_min.unwrap_or(15).max(1) * 60;
        let (kind, text) = match res {
            Ok(o) if o.ok => { a.wake_fails = 0; ("thought", o.text) }
            Ok(o) => { a.wake_fails += 1; limited = o.limited; ("error", o.text) }
            Err((StatusCode::CONFLICT, _)) => { a.next_wake = Some(t + 60); save(&g, &s.file); return; }
            Err((_, m)) => ("system", m),
        };
        a.wakes += 1; a.last_wake = t0; a.wake_log.push(t0);
        let chosen = a.next_wake.filter(|n| *n > t);
        a.next_wake = Some(match (kind, chosen) { ("thought", Some(n)) => n.max(t + min), ("thought", None) => t + 3600.max(min), ("system", _) => t + 3600.max(min), _ => t + 1800.max(min) });
        if limited { a.next_wake = Some(t + 1800); }
        if a.wake_fails >= 3 { a.spec.autonomous = false; a.next_wake = None; note = Some(format!("{} could not finish 3 wake-ups in a row, so I turned its life off. Check its access and instructions, then switch it on again.", a.spec.name)); }
        save(&g, &s.file);
        (kind, text)
    };
    let title = match kind { "thought" => format!("{name} thought", ), "error" => format!("{name} had trouble"), _ => format!("{name} skipped a wake-up") };
    act(&s, if kind == "thought" { "thought" } else if kind == "error" { "error" } else { "system" }, Some(id), None, &title, &text).await;
    if limited { act(&s, "system", None, None, "Automations paused", "Claude's usage limit was reached. Living agents sleep for 30 minutes and then try again.").await; }
    if let Some(n) = note { act(&s, "system", Some(id), None, "Agent switched off", &n).await; send_notification(&s, "Agent switched off", &n).await; }
}

// ---------------------------------------------------------------- settings

fn settings_json(st: &Settings) -> Value {
    json!({
        "paused": st.paused, "pause_until": st.pause_until, "max_parallel": st.max_parallel, "tz": st.tz, "global_memory": st.global_memory,
        "telegram": {"token_set": !st.notify.telegram_token.is_empty(), "paired": st.notify.telegram_chat.is_some(), "pair_code": st.notify.telegram_pair},
        "ntfy": {"server": st.notify.ntfy_server, "topic": st.notify.ntfy_topic},
        "webhook": {"set": !st.notify.webhook_url.is_empty()},
    })
}

async fn settings_get(State(s): State<S>) -> Json<Value> { Json(settings_json(&s.db.lock().await.settings)) }

async fn settings_put(State(s): State<S>, Json(r): Json<Value>) -> Result<Json<Value>, (StatusCode, String)> {
    let mut restart_tg = false;
    let old_parallel;
    let new_parallel;
    let out;
    {
        let mut g = s.db.lock().await;
        old_parallel = g.settings.max_parallel;
        let st = &mut g.settings;
        if let Some(b) = r["paused"].as_bool() { st.paused = b; if !b { st.pause_until = 0; } }
        if let Some(n) = r["max_parallel"].as_u64() { st.max_parallel = (n as usize).clamp(1, 8); }
        if let Some(t) = r["tz"].as_str() { if t.parse::<chrono_tz::Tz>().is_err() { return Err((StatusCode::BAD_REQUEST, format!("Unknown time zone {t}"))); } st.tz = t.into(); }
        if let Some(m) = r["global_memory"].as_str() { st.global_memory = cut(m, 6000).trim_end_matches('…').to_string(); }
        if let Some(v) = r["ntfy_server"].as_str() { st.notify.ntfy_server = v.trim().into(); }
        if let Some(v) = r["ntfy_topic"].as_str() { st.notify.ntfy_topic = v.trim().into(); }
        if let Some(v) = r["webhook_url"].as_str() { st.notify.webhook_url = v.trim().into(); }
        if let Some(v) = r["telegram_token"].as_str() {
            let v = v.trim();
            if v != st.notify.telegram_token { st.notify.telegram_token = v.into(); st.notify.telegram_chat = None; st.notify.telegram_pair = None; restart_tg = true; }
        }
        new_parallel = st.max_parallel;
        out = settings_json(st);
        save(&g, &s.file);
    }
    if new_parallel > old_parallel { s.sem.add_permits(new_parallel - old_parallel); }
    else if new_parallel < old_parallel { let sem = s.sem.clone(); let n = (old_parallel - new_parallel) as u32; tokio::spawn(async move { if let Ok(p) = sem.acquire_many(n).await { p.forget(); } }); }
    if restart_tg { start_telegram(&s).await; }
    bump(&s);
    Ok(Json(out))
}

async fn notify_test(State(s): State<S>) -> Json<Value> {
    let errors = send_notification(&s, "Test", "This is a test message from your agents.").await;
    act(&s, "system", None, None, "Test message", "Sent a test message to your channels.").await;
    Json(json!({"ok": errors.is_empty(), "errors": errors}))
}

async fn telegram_pair(State(s): State<S>) -> Result<Json<Value>, (StatusCode, String)> {
    let mut g = s.db.lock().await;
    if g.settings.notify.telegram_token.is_empty() { return Err((StatusCode::BAD_REQUEST, "Add the bot token first.".into())); }
    let code = new_hook()[..6].to_uppercase();
    g.settings.notify.telegram_pair = Some(code.clone());
    save(&g, &s.file);
    drop(g);
    bump(&s);
    Ok(Json(json!({"code": code})))
}

async fn telegram_unlink(State(s): State<S>) -> StatusCode {
    { let mut g = s.db.lock().await; g.settings.notify.telegram_chat = None; g.settings.notify.telegram_pair = None; save(&g, &s.file); }
    bump(&s);
    StatusCode::OK
}

// ---------------------------------------------------------------- Telegram bridge

async fn start_telegram(s: &S) {
    let gen = s.tg_gen.fetch_add(1, Ordering::SeqCst) + 1;
    let tok = s.db.lock().await.settings.notify.telegram_token.clone();
    if tok.is_empty() { return; }
    let s2 = s.clone();
    tokio::spawn(telegram_loop(s2, gen));
}

async fn tg_send(tok: &str, chat: i64, text: &str) {
    let url = format!("https://api.telegram.org/bot{tok}/sendMessage");
    for chunk in text.chars().collect::<Vec<_>>().chunks(3800) {
        let t: String = chunk.iter().collect();
        let _ = Req::new("POST", &url).json(&json!({"chat_id": chat, "text": t})).send().await;
    }
}

/// Talk to your agents from Telegram. Only the paired chat is answered: "/pair CODE" (code from the app) links it.
/// Commands: plain text goes to the first agent, "/2 text" to agent 2, /agents, /tasks, /pause, /resume, /status.
async fn telegram_loop(s: S, gen: u64) {
    let mut offset = 0i64;
    while s.tg_gen.load(Ordering::SeqCst) == gen {
        let (tok, chat, pair) = { let g = s.db.lock().await; (g.settings.notify.telegram_token.clone(), g.settings.notify.telegram_chat, g.settings.notify.telegram_pair.clone()) };
        if tok.is_empty() { return; }
        let url = format!("https://api.telegram.org/bot{tok}/getUpdates?timeout=25&offset={offset}");
        let Ok((_, body)) = Req::new("GET", &url).timeout(40).send().await else { tokio::time::sleep(Duration::from_secs(5)).await; continue };
        let Ok(v) = serde_json::from_str::<Value>(&body) else { tokio::time::sleep(Duration::from_secs(5)).await; continue };
        for u in v["result"].as_array().into_iter().flatten() {
            offset = u["update_id"].as_i64().unwrap_or(0) + 1;
            let (Some(from), Some(text)) = (u["message"]["chat"]["id"].as_i64(), u["message"]["text"].as_str()) else { continue };
            let chat_now = s.db.lock().await.settings.notify.telegram_chat.or(chat);
            if chat_now != Some(from) {
                let code = text.strip_prefix("/pair ").map(str::trim);
                let want = s.db.lock().await.settings.notify.telegram_pair.clone().or(pair.clone());
                if let (Some(c), Some(w)) = (code, want) {
                    if c.eq_ignore_ascii_case(&w) {
                        { let mut g = s.db.lock().await; g.settings.notify.telegram_chat = Some(from); g.settings.notify.telegram_pair = None; save(&g, &s.file); }
                        bump(&s);
                        act(&s, "system", None, None, "Telegram linked", "Messages from your agents now arrive in Telegram.").await;
                        tg_send(&tok, from, "Linked. I will send you messages from your agents here. Send /agents to see them.").await;
                    } else { tg_send(&tok, from, "That code is wrong or expired.").await; }
                }
                continue;   // everyone else is ignored
            }
            let reply = tg_command(&s, text).await;
            tg_send(&tok, from, &reply).await;
        }
    }
}

async fn tg_command(s: &S, text: &str) -> String {
    let t = text.trim();
    match t {
        "/start" | "/help" => return "Send a message to talk to your first agent, or /2 text for agent 2.\n/agents  list agents\n/tasks  scheduled tasks\n/pause  pause all automations\n/resume  resume them\n/status".into(),
        "/agents" => { let g = s.db.lock().await; let mut v: Vec<&Agent> = g.agents.iter().collect(); v.sort_by_key(|a| a.order); return v.iter().map(|a| format!("/{} {} ({}){}", a.id, a.spec.name, a.spec.model, if a.running { " working" } else { "" })).collect::<Vec<_>>().join("\n"); }
        "/tasks" => { let g = s.db.lock().await; return if g.jobs.is_empty() { "No scheduled tasks.".into() } else { g.jobs.iter().map(|j| format!("#{} {} [{}] {} next: {}", j.id, j.name, if j.enabled { "on" } else { "off" }, describe(&j.schedule, &g.settings.tz), j.next_at.map(|n| local_text(n, &g.settings.tz)).unwrap_or("-".into()))).collect::<Vec<_>>().join("\n") }; }
        "/pause" => { let mut g = s.db.lock().await; g.settings.paused = true; save(&g, &s.file); drop(g); bump(s); return "Automations paused.".into(); }
        "/resume" => { let mut g = s.db.lock().await; g.settings.paused = false; g.settings.pause_until = 0; save(&g, &s.file); drop(g); bump(s); return "Automations resumed.".into(); }
        "/status" => { let g = s.db.lock().await; return format!("agents-server {VERSION}\n{} agents, {} tasks\nworking now: {}\nautomations: {}", g.agents.len(), g.jobs.len(), g.agents.iter().filter(|a| a.running).count(), if g.settings.paused || g.settings.pause_until > now() { "paused" } else { "running" }); }
        _ => {}
    }
    let (id, msg) = match t.strip_prefix('/').and_then(|x| x.split_once(' ')) {
        Some((n, rest)) if n.parse::<u64>().is_ok() => (Some(n.parse().unwrap()), rest.to_string()),
        _ => (None, t.to_string()),
    };
    let id = match id { Some(i) => i, None => { let g = s.db.lock().await; match g.agents.iter().min_by_key(|a| a.order) { Some(a) => a.id, None => return "There are no agents yet.".into() } } };
    match run_agent(s, id, &msg, RunOpts::default()).await { Ok(o) => o.text, Err(e) => e.1 }
}

// ---------------------------------------------------------------- server update

/// Downloads the newest server from GitHub, checks its checksum, swaps it in and exits so systemd starts the new one.
async fn self_update() -> Result<Json<Value>, (StatusCode, String)> {
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
    Ok(Json(json!({"updating": true, "from": VERSION, "to": ver})))
}

// ---------------------------------------------------------------- main

#[tokio::main]
async fn main() {
    let arg1 = std::env::args().nth(1).unwrap_or_default();
    if arg1 == "--version" { println!("agents-server {VERSION}"); return; }
    if arg1 == "mcp" { tokio::task::spawn_blocking(mcp::serve).await.ok(); return; }

    let token = std::env::var("AGENTS_TOKEN").unwrap_or_else(|_| { eprintln!("set AGENTS_TOKEN"); std::process::exit(1) });
    let file = PathBuf::from(std::env::var("AGENTS_DB").unwrap_or_else(|_| "agents.json".into()));
    let addr = std::env::var("AGENTS_ADDR").unwrap_or_else(|_| "127.0.0.1:7777".into());
    let mut db = std::fs::read(&file).ok().and_then(|b| serde_json::from_slice::<Db>(&b).ok()).unwrap_or_default();
    if let Ok(t) = std::env::var("TELEGRAM_TOKEN") { if db.settings.notify.telegram_token.is_empty() { db.settings.notify.telegram_token = t; } }   // older installs set it in the env file
    let (tx, _rx) = watch::channel(1u64);
    let sem = Arc::new(Semaphore::new(db.settings.max_parallel.clamp(1, 8)));
    let s = S { db: Arc::new(Mutex::new(Db::default())), rt: Arc::new(Mutex::new(Rt::default())), acts: Arc::new(Mutex::new(Acts::default())), rev: Arc::new(tx), token: Arc::new(token), file: Arc::new(file), addr: Arc::new(addr.clone()), sem, tg_gen: Arc::new(AtomicU64::new(0)) };
    // move chats from the old single file (0.2.x) into one file per agent
    for a in db.agents.iter_mut() {
        a.running = false;
        if a.last_active == 0 { a.last_active = now(); }
        for m in std::mem::take(&mut a.messages) { append_msg(&s, a.id, &m.role, &m.text).await; }
    }
    // give tasks that have no next time one (first start after an update)
    let tz = db.settings.tz.clone();
    for j in db.jobs.iter_mut() { if j.enabled && j.next_at.is_none() { j.next_at = next_at(&j.schedule, now(), &tz); } }
    save(&db, &s.file);
    *s.db.lock().await = db;
    // activity feed: keep the most recent entries in memory, trim the file when it grows
    {
        let path = data_dir(&s).join("activity.jsonl");
        let lines: Vec<Act> = std::fs::read_to_string(&path).unwrap_or_default().lines().filter_map(|l| serde_json::from_str(l).ok()).collect();
        let keep: Vec<Act> = lines.iter().rev().take(2000).rev().cloned().collect();
        if lines.len() > 2000 { let _ = std::fs::write(&path, keep.iter().filter_map(|e| serde_json::to_string(e).ok()).collect::<Vec<_>>().join("\n") + "\n"); }
        let mut a = s.acts.lock().await;
        a.next = keep.last().map_or(0, |e| e.id);
        a.list = keep.into_iter().rev().take(MAX_ACT_MEM).rev().collect();
    }
    start_telegram(&s).await;
    tokio::spawn(scheduler(s.clone()));
    let cors = CorsLayer::new().allow_origin(Any).allow_methods(Any).allow_headers(Any).expose_headers([HeaderName::from_static("x-rev")]);
    let app = Router::new()
        .route("/health", get(health))
        .route("/models", get(models))
        .route("/agents", get(list).post(create))
        .route("/agents/order", post(reorder))
        .route("/agents/ask", post(ask))
        .route("/agents/{id}", axum::routing::put(update).delete(delete))
        .route("/agents/{id}/pin", post(pin))
        .route("/agents/{id}/messages", get(messages).delete(clear))
        .route("/agents/{id}/run", post(run))
        .route("/agents/{id}/stop", post(stop))
        .route("/agents/{id}/remember", post(remember))
        .route("/agents/{id}/journal", post(journal_put))
        .route("/agents/{id}/wake", post(wake_set))
        .route("/agents/{id}/wake-now", post(wake_now))
        .route("/jobs", get(jobs_list).post(jobs_create))
        .route("/jobs/{id}", axum::routing::put(jobs_update).delete(jobs_delete))
        .route("/jobs/{id}/enabled", post(jobs_enabled))
        .route("/jobs/{id}/run", post(jobs_run_now))
        .route("/jobs/{id}/notes", post(jobs_notes))
        .route("/schedule/parse", post(schedule_parse))
        .route("/hook/{secret}", post(hook).get(hook_get))
        .route("/activity", get(activity))
        .route("/notify", post(notify))
        .route("/settings", get(settings_get).put(settings_put))
        .route("/settings/test", post(notify_test))
        .route("/settings/telegram/pair", post(telegram_pair))
        .route("/settings/telegram", axum::routing::delete(telegram_unlink))
        .route("/admin/update", post(self_update))
        .layer(middleware::from_fn_with_state(s.clone(), auth))
        .layer(cors)
        .with_state(s);
    eprintln!("agents-server {VERSION} listening on {addr}");
    axum::serve(tokio::net::TcpListener::bind(addr).await.unwrap(), app).await.unwrap();
}

async fn hook_get(State(s): State<S>, Path(secret): Path<String>) -> StatusCode { hook(State(s), Path(secret), String::new()).await }
