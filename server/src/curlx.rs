//! Tiny HTTP helper that shells out to curl. The server then needs no TLS library (the binary stays about 1 MB),
//! and secrets (bot tokens, the key) go through curl's stdin config instead of the process list.
use std::io::Write;
use std::process::{Command as StdCommand, Stdio};

fn esc(s: &str) -> String {
    s.replace('\\', "\\\\").replace('"', "\\\"").replace('\n', "\\n").replace('\r', "\\r").replace('\t', "\\t")
}

pub struct Req<'a> {
    pub method: &'a str,
    pub url: &'a str,
    pub headers: Vec<(&'a str, String)>,
    pub body: Option<String>,
    pub timeout: u64,
}

impl<'a> Req<'a> {
    pub fn new(method: &'a str, url: &'a str) -> Self { Req { method, url, headers: vec![], body: None, timeout: 30 } }
    pub fn header(mut self, k: &'a str, v: impl Into<String>) -> Self { self.headers.push((k, v.into())); self }
    pub fn json(mut self, v: &serde_json::Value) -> Self { self.headers.push(("content-type", "application/json".into())); self.body = Some(v.to_string()); self }
    pub fn text(mut self, t: &str) -> Self { self.body = Some(t.to_string()); self }
    pub fn timeout(mut self, s: u64) -> Self { self.timeout = s; self }

    fn config(&self) -> String {
        let mut c = format!("url = \"{}\"\nrequest = \"{}\"\nsilent\nshow-error\nmax-time = {}\n", esc(self.url), self.method, self.timeout);
        for (k, v) in &self.headers { c.push_str(&format!("header = \"{}: {}\"\n", esc(k), esc(v))); }
        if let Some(b) = &self.body { c.push_str(&format!("data = \"{}\"\n", esc(b))); }
        c.push_str("write-out = \"\\n%{http_code}\"\n");
        c
    }

    /// Blocking call (used by the MCP helper process). Returns (status, body).
    pub fn send_blocking(&self) -> Result<(u16, String), String> {
        let mut child = StdCommand::new("curl").args(["-K", "-"]).stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::piped()).spawn().map_err(|e| format!("curl is missing: {e}"))?;
        child.stdin.take().unwrap().write_all(self.config().as_bytes()).map_err(|e| e.to_string())?;
        let out = child.wait_with_output().map_err(|e| e.to_string())?;
        parse(out.stdout, out.stderr)
    }

    /// Async call (used by the server).
    pub async fn send(&self) -> Result<(u16, String), String> {
        use tokio::io::AsyncWriteExt;
        let mut child = tokio::process::Command::new("curl").args(["-K", "-"]).stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::piped()).kill_on_drop(true).spawn().map_err(|e| format!("curl is missing: {e}"))?;
        child.stdin.take().unwrap().write_all(self.config().as_bytes()).await.map_err(|e| e.to_string())?;
        let out = child.wait_with_output().await.map_err(|e| e.to_string())?;
        parse(out.stdout, out.stderr)
    }
}

fn parse(stdout: Vec<u8>, stderr: Vec<u8>) -> Result<(u16, String), String> {
    let s = String::from_utf8_lossy(&stdout).to_string();
    match s.rsplit_once('\n') {
        Some((body, code)) => Ok((code.trim().parse().unwrap_or(0), body.to_string())),
        None => Err(String::from_utf8_lossy(&stderr).trim().to_string()),
    }
}
