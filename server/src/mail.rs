//! The agents' mailbox: read and send mail through IMAP/SMTP with an app password, using curl (no TLS library needed).
//! The password never leaves the server; agents only see the results.
use std::process::Stdio;

fn esc(s: &str) -> String {
    s.replace('\\', "\\\\").replace('"', "\\\"").replace('\n', "\\n").replace('\r', "\\r").replace('\t', "\\t")
}

pub struct Mailbox { pub addr: String, pub pass: String }

impl Mailbox {
    pub fn new(addr: &str, pass: &str) -> Self { Mailbox { addr: addr.trim().to_string(), pass: pass.chars().filter(|c| !c.is_whitespace()).collect() } }
    fn domain(&self) -> String { self.addr.rsplit('@').next().unwrap_or("").to_lowercase() }
    fn hosts(&self) -> (String, String) {
        let d = self.domain();
        if d == "gmail.com" || d == "googlemail.com" { ("imap.gmail.com".into(), "smtp.gmail.com".into()) } else { (format!("imap.{d}"), format!("smtp.{d}")) }
    }
    fn auth(&self) -> String { format!("user = \"{}\"\n", esc(&format!("{}:{}", self.addr, self.pass))) }

    async fn curl(&self, cfg: String) -> Result<String, String> {
        use tokio::io::AsyncWriteExt;
        let mut child = tokio::process::Command::new("curl").args(["-K", "-"]).stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::piped()).kill_on_drop(true).spawn().map_err(|e| format!("curl is missing: {e}"))?;
        child.stdin.take().unwrap().write_all(cfg.as_bytes()).await.map_err(|e| e.to_string())?;
        let out = tokio::time::timeout(std::time::Duration::from_secs(60), child.wait_with_output()).await.map_err(|_| "the mail server did not answer in time".to_string())?.map_err(|e| e.to_string())?;
        if out.status.success() { Ok(String::from_utf8_lossy(&out.stdout).to_string()) } else {
            let e = String::from_utf8_lossy(&out.stderr).to_string();
            Err(if e.contains("Login denied") || e.contains("authentication") || e.contains("535") || e.contains("AUTHENTICATIONFAILED") { "The mail server refused the login. Check the address and the app password (Gmail needs 2-step verification and an app password; IMAP must be on).".into() } else { e.trim().to_string() })
        }
    }
    fn imap_cfg(&self, path: &str, request: Option<&str>) -> String {
        let mut c = format!("url = \"imaps://{}:993/{}\"\nsilent\nshow-error\nmax-time = 50\n{}", self.hosts().0, path, self.auth());
        if let Some(r) = request { c.push_str(&format!("request = \"{}\"\n", esc(r))); }
        c
    }

    /// Check the login. Returns the number of messages in the inbox.
    pub async fn test(&self) -> Result<String, String> {
        let r = self.curl(self.imap_cfg("INBOX", Some("STATUS INBOX (MESSAGES)"))).await?;
        Ok(r.trim().to_string())
    }

    /// Newest messages first. `query` is a Gmail search ("from:github subject:verify"), `unread` limits to unread mail.
    pub async fn list(&self, n: usize, query: &str, unread: bool) -> Result<Vec<(u64, String, String, String)>, String> {
        let crit = if !query.trim().is_empty() { format!("X-GM-RAW \"{}\"", query.replace('"', "")) } else if unread { "UNSEEN".into() } else { "ALL".into() };
        let r = self.curl(self.imap_cfg("INBOX", Some(&format!("UID SEARCH {crit}")))).await?;
        let mut ids: Vec<u64> = r.lines().find(|l| l.starts_with("* SEARCH")).map(|l| l[8..].split_whitespace().filter_map(|x| x.parse().ok()).collect()).unwrap_or_default();
        ids.sort_unstable_by(|a, b| b.cmp(a));
        ids.truncate(n.clamp(1, 30));
        if ids.is_empty() { return Ok(vec![]); }
        let set = ids.iter().map(|i| i.to_string()).collect::<Vec<_>>().join(",");
        let r = self.curl(self.imap_cfg("INBOX", Some(&format!("UID FETCH {set} (UID BODY.PEEK[HEADER.FIELDS (FROM SUBJECT DATE)])")))).await?;
        let mut out = vec![];
        for chunk in r.split("\n* ").filter(|c| c.contains("FETCH")) {
            let uid = chunk.split("UID ").nth(1).and_then(|t| t.split(|c: char| !c.is_ascii_digit()).next()).and_then(|t| t.parse().ok()).unwrap_or(0);
            let h = unfold(chunk);
            out.push((uid, header(&h, "From"), decode_words(&header(&h, "Subject")), header(&h, "Date")));
        }
        out.sort_by(|a, b| b.0.cmp(&a.0));
        Ok(out)
    }

    /// The readable text of one message (links kept), at most `max` characters.
    pub async fn read(&self, uid: u64, max: usize) -> Result<String, String> {
        let raw = self.curl(self.imap_cfg(&format!("INBOX;UID={uid}"), None)).await?;
        let h = unfold(raw.split("\r\n\r\n").next().unwrap_or(""));
        let body = body_text(&raw);
        let t = format!("From: {}\nTo: {}\nSubject: {}\nDate: {}\n\n{}", header(&h, "From"), header(&h, "To"), decode_words(&header(&h, "Subject")), header(&h, "Date"), body.trim());
        Ok(if t.chars().count() > max { format!("{}…", t.chars().take(max).collect::<String>()) } else { t })
    }

    pub async fn send(&self, to: &str, subject: &str, body: &str, tmp_dir: &std::path::Path) -> Result<(), String> {
        let to = to.trim();
        if !to.contains('@') || to.contains(['\r', '\n', '<', '>', ' ']) { return Err("That is not a plain email address.".into()); }
        let subject = subject.replace(['\r', '\n'], " ");
        let subj = if subject.is_ascii() { subject } else { format!("=?UTF-8?B?{}?=", b64(subject.as_bytes())) };
        let msg = format!("From: {}\r\nTo: {}\r\nSubject: {}\r\nDate: {}\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Transfer-Encoding: base64\r\n\r\n{}\r\n", self.addr, to, subj, chrono::Utc::now().to_rfc2822(), b64(body.as_bytes()).as_bytes().chunks(76).map(|c| String::from_utf8_lossy(c).to_string()).collect::<Vec<_>>().join("\r\n"));
        std::fs::create_dir_all(tmp_dir).map_err(|e| e.to_string())?;
        let path = tmp_dir.join(format!("out-{}.eml", std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_nanos()).unwrap_or(0)));
        std::fs::write(&path, msg).map_err(|e| e.to_string())?;
        let cfg = format!("url = \"smtps://{}:465\"\nsilent\nshow-error\nmax-time = 50\nmail-from = \"{}\"\nmail-rcpt = \"{}\"\nupload-file = \"{}\"\n{}", self.hosts().1, esc(&self.addr), esc(to), esc(&path.to_string_lossy()), self.auth());
        let r = self.curl(cfg).await;
        let _ = std::fs::remove_file(&path);
        r.map(|_| ())
    }
}

fn unfold(s: &str) -> String { s.replace("\r\n ", " ").replace("\r\n\t", " ").replace("\r\n", "\n").replace("\n ", " ").replace("\n\t", " ") }

fn header(h: &str, name: &str) -> String {
    let p = format!("{}:", name.to_lowercase());
    h.lines().find(|l| l.to_lowercase().starts_with(&p)).map(|l| l[p.len()..].trim().to_string()).unwrap_or_default()
}

const B64: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
pub fn b64(d: &[u8]) -> String {
    let mut o = String::new();
    for c in d.chunks(3) {
        let n = (c[0] as u32) << 16 | (*c.get(1).unwrap_or(&0) as u32) << 8 | *c.get(2).unwrap_or(&0) as u32;
        o.push(B64[(n >> 18) as usize & 63] as char);
        o.push(B64[(n >> 12) as usize & 63] as char);
        o.push(if c.len() > 1 { B64[(n >> 6) as usize & 63] as char } else { '=' });
        o.push(if c.len() > 2 { B64[n as usize & 63] as char } else { '=' });
    }
    o
}
fn unb64(s: &str) -> Vec<u8> {
    let (mut o, mut acc, mut bits) = (vec![], 0u32, 0);
    for ch in s.bytes() {
        let Some(v) = B64.iter().position(|&b| b == ch) else { continue };
        acc = acc << 6 | v as u32; bits += 6;
        if bits >= 8 { bits -= 8; o.push((acc >> bits) as u8); acc &= (1 << bits) - 1; }
    }
    o
}
fn unqp(s: &str) -> Vec<u8> {
    let b = s.as_bytes();
    let (mut o, mut i) = (vec![], 0);
    while i < b.len() {
        if b[i] == b'=' {
            if i + 1 < b.len() && (b[i + 1] == b'\n' || b[i + 1] == b'\r') { i += if b[i + 1] == b'\r' && b.get(i + 2) == Some(&b'\n') { 3 } else { 2 }; continue; }
            if let Some(v) = s.get(i + 1..i + 3).and_then(|h| u8::from_str_radix(h, 16).ok()) { o.push(v); i += 3; continue; }
        }
        o.push(b[i]); i += 1;
    }
    o
}

/// "=?UTF-8?B?...?=" and "=?UTF-8?Q?...?=" in headers.
fn decode_words(s: &str) -> String {
    let mut out = String::new();
    let mut rest = s;
    while let Some(i) = rest.find("=?") {
        out.push_str(&rest[..i]);
        let t = &rest[i + 2..];
        let parts: Vec<&str> = t.splitn(3, '?').collect();
        if parts.len() == 3 && parts[2].contains("?=") {
            let (data, after) = parts[2].split_once("?=").unwrap();
            let bytes = if parts[1].eq_ignore_ascii_case("b") { unb64(data) } else { unqp(&data.replace('_', " ").replace('=', "=")) };
            out.push_str(&String::from_utf8_lossy(&bytes));
            rest = after.trim_start_matches(' ');
        } else { out.push_str("=?"); rest = t; }
    }
    out.push_str(rest);
    out
}

fn param(h: &str, key: &str) -> Option<String> {
    let l = h.to_lowercase();
    let i = l.find(&format!("{key}="))? + key.len() + 1;
    let v = &h[i..];
    Some(if let Some(q) = v.strip_prefix('"') { q.split('"').next()?.to_string() } else { v.split([';', '\n', ' ']).next()?.to_string() })
}

/// Readable text of a raw message: the text part when there is one, else the page with tags removed and links kept.
fn body_text(raw: &str) -> String {
    let raw = raw.replace("\r\n", "\n");
    let (head, body) = raw.split_once("\n\n").unwrap_or((&raw, ""));
    let head = unfold(head);
    let ct = header(&head, "Content-Type").to_lowercase();
    let cte = header(&head, "Content-Transfer-Encoding").to_lowercase();
    if ct.starts_with("multipart/") {
        let Some(bd) = param(&header(&head, "Content-Type"), "boundary") else { return body.to_string() };
        let marker = format!("--{bd}");
        let (mut plain, mut html) = (String::new(), String::new());
        for part in body.split(&marker).skip(1) {
            let part = part.trim_start_matches('\n');
            if part.starts_with("--") { break; }
            let pct = header(&unfold(part.split("\n\n").next().unwrap_or("")), "Content-Type").to_lowercase();
            let t = body_text(part);
            if pct.starts_with("text/plain") || pct.starts_with("multipart/") { if plain.is_empty() { plain = t; } }
            else if pct.starts_with("text/html") && html.is_empty() { html = t; }
        }
        return if plain.trim().len() > 20 { plain } else if !html.is_empty() { html } else { plain };
    }
    let bytes = if cte.contains("base64") { unb64(body) } else if cte.contains("quoted-printable") { unqp(body) } else { body.as_bytes().to_vec() };
    let text = String::from_utf8_lossy(&bytes).to_string();
    if ct.starts_with("text/html") { strip_html(&text) } else if ct.is_empty() || ct.starts_with("text/") { text } else { String::new() }
}

fn strip_html(h: &str) -> String {
    let (mut out, mut i) = (String::new(), 0);
    let b = h.as_bytes();
    let low = h.to_lowercase();
    let mut skip_until: Option<&str> = None;
    while i < b.len() {
        if b[i] == b'<' {
            let end = h[i..].find('>').map(|e| i + e + 1).unwrap_or(b.len());
            let tag = &low[i..end];
            if let Some(s) = skip_until { if tag.starts_with(s) { skip_until = None; } i = end; continue; }
            if tag.starts_with("<style") { skip_until = Some("</style"); }
            else if tag.starts_with("<script") { skip_until = Some("</script"); }
            else if tag.starts_with("<a ") { if let Some(u) = param(&h[i..end].replace('\'', "\""), "href") { out.push_str(&format!(" [{u}] ")); } }
            else if tag.starts_with("<br") || tag.starts_with("</p") || tag.starts_with("</div") || tag.starts_with("</tr") || tag.starts_with("</h") || tag.starts_with("<li") { out.push('\n'); }
            i = end;
        } else {
            if skip_until.is_none() { let ch = h[i..].chars().next().unwrap(); out.push(ch); i += ch.len_utf8(); } else { i += 1; }
        }
    }
    let out = out.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
    out.lines().map(|l| l.trim()).filter(|l| !l.is_empty()).collect::<Vec<_>>().join("\n")
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn reads_html_and_qp() {
        let raw = "From: a@b.c\r\nSubject: =?UTF-8?B?Q29kZQ==?=\r\nContent-Type: multipart/alternative; boundary=\"XX\"\r\n\r\n--XX\r\nContent-Type: text/html; charset=utf-8\r\nContent-Transfer-Encoding: quoted-printable\r\n\r\n<p>Your code is <b>123456</b></p><a href=3D\"https://x.io/v?a=3D1\">Confirm</a>\r\n--XX--\r\n";
        let t = body_text(raw);
        assert!(t.contains("123456") && t.contains("[https://x.io/v?a=1]"), "{t}");
        assert_eq!(decode_words("=?UTF-8?B?Q29kZQ==?="), "Code");
        assert_eq!(unb64(&b64(b"hello w")), b"hello w");
    }
}
