//! `agents-server mcp`: a small MCP server (stdio) that every agent gets as "agentic-os".
//! It lets an agent notify the user, schedule its own tasks, remember things and ask other agents.
use crate::curlx::Req;
use serde_json::{json, Value};
use std::io::{BufRead, Write};

fn call(method: &str, path: &str, body: Option<Value>, timeout: u64) -> Result<Value, String> {
    let url = std::env::var("AGENTS_URL").map_err(|_| "AGENTS_URL is not set")?;
    let key = std::env::var("AGENTS_TOKEN").map_err(|_| "AGENTS_TOKEN is not set")?;
    let full = format!("{url}{path}");
    let mut r = Req::new(method, &full).header("Authorization", format!("Bearer {key}")).timeout(timeout);
    if let Some(b) = &body { r = r.json(b); }
    let (code, text) = r.send_blocking()?;
    if !(200..300).contains(&code) { return Err(if text.is_empty() { format!("the server answered {code}") } else { text }); }
    Ok(serde_json::from_str(&text).unwrap_or(Value::String(text)))
}

fn tools() -> Value {
    let mut v = base_tools();
    if std::env::var("AGENT_AUTONOMOUS").as_deref() == Ok("1") {
        v.as_array_mut().unwrap().extend([
            json!({"name": "save_journal", "description": "Replace your journal: your private long-term memory that you read every time you wake up. Keep it organised and under 4000 characters: your purpose and plan, what you did and built, what you learned, and what comes next.", "inputSchema": {"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]}}),
            json!({"name": "set_next_wake", "description": "Choose when you wake up next. Sleep long when nothing needs you. Use phrases such as 'in 45 minutes', 'in 6 hours', 'tomorrow 08:00', 'daily 08:00' (the next 08:00), or 'at 2026-12-01 09:00'. Times are in the user's time zone.", "inputSchema": {"type": "object", "properties": {"when": {"type": "string"}, "reason": {"type": "string", "description": "What you will do then"}}, "required": ["when"]}})
        ]);
    }
    v
}

fn base_tools() -> Value {
    json!([
        {"name": "notify", "description": "Send the user a message right now (it goes to their phone and desktop app, and to Telegram if set up). Use it when you finish a long task, need their attention, or when they asked to be told something.",
         "inputSchema": {"type": "object", "properties": {"message": {"type": "string", "description": "The message. Keep it short and useful."}, "title": {"type": "string"}}, "required": ["message"]}},
        {"name": "schedule_task", "description": "Schedule a task for yourself. At the time, you run the prompt in a fresh session and the result is sent to the user. Use it when the user asks for something recurring or for later (for example: every morning, in 2 hours, every Monday).",
         "inputSchema": {"type": "object", "properties": {
            "name": {"type": "string", "description": "Short name"},
            "prompt": {"type": "string", "description": "Exactly what to do each time. It runs without this conversation, so include everything needed. Leave empty to follow the agent's own instructions."},
            "notes": {"type": "string", "description": "Optional plan for a series that builds on earlier runs (for example a lesson plan). The job reads and updates these notes on every run."},
            "when": {"type": "string", "description": "One of: 'daily 08:00', 'weekdays 08:30', 'weekly monday 09:00', 'every 30 minutes', 'every 2 hours', 'in 45 minutes', 'at 2026-12-01 09:00', or 'cron 0 8 * * *'. Times are in the user's time zone."},
            "notify": {"type": "string", "enum": ["always", "errors", "never"], "description": "Send the result to the user (default always)"}},
          "required": ["name", "when"]}},
        {"name": "save_task_notes", "description": "Only during a scheduled run: save short notes for the next run of this same job (what you have covered so far and what comes next). Use it for series such as lessons or tracking, so you never repeat yourself. Replaces the old notes. Under 1500 characters.",
         "inputSchema": {"type": "object", "properties": {"notes": {"type": "string"}}, "required": ["notes"]}},
        {"name": "list_tasks", "description": "List the scheduled tasks of all agents (id, name, schedule, next run).", "inputSchema": {"type": "object", "properties": {}}},
        {"name": "delete_task", "description": "Delete a scheduled task by id.", "inputSchema": {"type": "object", "properties": {"id": {"type": "integer"}}, "required": ["id"]}},
        {"name": "remember", "description": "Save a short fact so you (or all agents, with shared=true) remember it in every future session. Keep facts short; do not save secrets.",
         "inputSchema": {"type": "object", "properties": {"fact": {"type": "string"}, "shared": {"type": "boolean", "description": "true = every agent remembers it"}}, "required": ["fact"]}},
        {"name": "ask_agent", "description": "Ask another agent a question and get its answer. Use it to delegate work to an agent with the right model, instructions or tools. Names come from list_agents.",
         "inputSchema": {"type": "object", "properties": {"agent": {"type": "string"}, "message": {"type": "string"}}, "required": ["agent", "message"]}},
        {"name": "list_agents", "description": "List the agents (name, model, what they are for).", "inputSchema": {"type": "object", "properties": {}}}
    ])
}

fn run_tool(name: &str, a: &Value) -> Result<String, String> {
    let me: u64 = std::env::var("AGENT_ID").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let s = |k: &str| a[k].as_str().unwrap_or("").to_string();
    match name {
        "notify" => { call("POST", "/notify", Some(json!({"title": s("title"), "body": s("message"), "agent_id": me})), 60)?; Ok("Sent.".into()) }
        "schedule_task" => {
            let r = call("POST", "/jobs", Some(json!({"agent_id": me, "name": s("name"), "prompt": s("prompt"), "notes": if a["notes"].is_string() { json!(s("notes")) } else { Value::Null }, "when": s("when"), "notify": if a["notify"].is_string() { s("notify") } else { "always".into() }, "fresh": true})), 30)?;
            Ok(format!("Scheduled (task {}). {}", r["id"], r["human"].as_str().unwrap_or("")) + &r["next"].as_str().map(|n| format!(" Next run: {n}.")).unwrap_or_default())
        }
        "save_task_notes" => {
            let job: u64 = std::env::var("AGENT_JOB").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
            if job == 0 { return Err("save_task_notes only works during a scheduled run.".into()); }
            call("POST", &format!("/jobs/{job}/notes"), Some(json!({"notes": s("notes")})), 30)?; Ok("Notes saved for the next run.".into())
        }
        "save_journal" => { call("POST", &format!("/agents/{me}/journal"), Some(json!({"text": s("text")})), 30)?; Ok("Journal saved.".into()) }
        "set_next_wake" => { let r = call("POST", &format!("/agents/{me}/wake"), Some(json!({"when": s("when"), "reason": s("reason")})), 30)?; Ok(format!("You will wake up next on {}.", r["next"].as_str().unwrap_or(""))) }
        "list_tasks" => {
            let r = call("GET", "/jobs", None, 30)?;
            let list = r.as_array().cloned().unwrap_or_default();
            if list.is_empty() { return Ok("No scheduled tasks.".into()); }
            Ok(list.iter().map(|j| format!("#{} {} [{}] {} next: {}", j["id"], j["name"].as_str().unwrap_or(""), if j["enabled"].as_bool().unwrap_or(false) { "on" } else { "off" }, j["human"].as_str().unwrap_or(""), j["next"].as_str().unwrap_or("-"))).collect::<Vec<_>>().join("\n"))
        }
        "delete_task" => { call("DELETE", &format!("/jobs/{}", a["id"].as_u64().unwrap_or(0)), None, 30)?; Ok("Deleted.".into()) }
        "remember" => { call("POST", &format!("/agents/{me}/remember"), Some(json!({"fact": s("fact"), "shared": a["shared"].as_bool().unwrap_or(false)})), 30)?; Ok("Remembered.".into()) }
        "ask_agent" => {
            let depth: u64 = std::env::var("AGENT_DEPTH").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
            let r = call("POST", "/agents/ask", Some(json!({"from": me, "agent": s("agent"), "message": s("message"), "depth": depth + 1})), 900)?;
            Ok(r.as_str().map(String::from).unwrap_or_else(|| r.to_string()))
        }
        "list_agents" => {
            let r = call("GET", "/agents", None, 30)?;
            Ok(r["agents"].as_array().map(|l| l.iter().map(|x| format!("{} ({}) {}", x["spec"]["name"].as_str().unwrap_or(""), x["spec"]["model"].as_str().unwrap_or(""), x["spec"]["prompt"].as_str().unwrap_or("").chars().take(120).collect::<String>())).collect::<Vec<_>>().join("\n")).unwrap_or_default())
        }
        _ => Err(format!("unknown tool {name}")),
    }
}

pub fn serve() {
    let stdin = std::io::stdin();
    let mut out = std::io::stdout();
    for line in stdin.lock().lines() {
        let Ok(line) = line else { break };
        let Ok(m) = serde_json::from_str::<Value>(&line) else { continue };
        let id = m.get("id").cloned();
        let method = m["method"].as_str().unwrap_or("");
        let reply = match method {
            "initialize" => Some(json!({"protocolVersion": m["params"]["protocolVersion"].as_str().unwrap_or("2024-11-05"), "capabilities": {"tools": {}}, "serverInfo": {"name": "agentic-os", "version": env!("CARGO_PKG_VERSION")}})),
            "tools/list" => Some(json!({"tools": tools()})),
            "tools/call" => {
                let r = run_tool(m["params"]["name"].as_str().unwrap_or(""), &m["params"]["arguments"]);
                Some(match r { Ok(t) => json!({"content": [{"type": "text", "text": t}]}), Err(e) => json!({"content": [{"type": "text", "text": e}], "isError": true}) })
            }
            "ping" => Some(json!({})),
            _ => None,
        };
        if let (Some(id), Some(result)) = (id, reply) {
            let _ = writeln!(out, "{}", json!({"jsonrpc": "2.0", "id": id, "result": result}));
            let _ = out.flush();
        }
    }
}
