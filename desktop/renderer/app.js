'use strict';
const $ = (s, r = document) => r.querySelector(s);
const ICONS = {
  bot: '<path d="M12 8V4H8"/><rect width="16" height="12" x="4" y="8" rx="2"/><path d="M2 14h2"/><path d="M20 14h2"/><path d="M15 13v2"/><path d="M9 13v2"/>',
  chart: '<path d="M3 3v16a2 2 0 0 0 2 2h16"/><path d="M18 17V9"/><path d="M13 17V5"/><path d="M8 17v-3"/>',
  set: '<line x1="21" x2="14" y1="4" y2="4"/><line x1="10" x2="3" y1="4" y2="4"/><line x1="21" x2="12" y1="12" y2="12"/><line x1="8" x2="3" y1="12" y2="12"/><line x1="21" x2="16" y1="20" y2="20"/><line x1="12" x2="3" y1="20" y2="20"/><line x1="14" x2="14" y1="2" y2="6"/><line x1="8" x2="8" y1="10" y2="14"/><line x1="16" x2="16" y1="18" y2="22"/>',
  plus: '<path d="M5 12h14"/><path d="M12 5v14"/>', send: '<path d="m22 2-7 20-4-9-9-4Z"/><path d="M22 2 11 13"/>',
  edit: '<path d="M21.2 5.6a2.1 2.1 0 0 0-3-3L3 17.8V21h3.2Z"/>', min: '<path d="M5 12h14"/>', max: '<rect width="14" height="14" x="5" y="5" rx="2"/>', x: '<path d="M18 6 6 18"/><path d="m6 6 12 12"/>',
  trash: '<path d="M3 6h18"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/>',
};
const icon = (n) => `<svg class="i" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${ICONS[n]}</svg>`;
const MARK = '<svg class="mark" viewBox="40 40 176 176" aria-hidden="true"><g stroke="#F0643C" stroke-width="12" stroke-linecap="round"><path d="M128 128 70 80M128 128 186 80M128 128 128 190"/></g><circle cx="128" cy="128" r="30" fill="#F0643C"/><circle cx="128" cy="128" r="12" fill="#FFD3A8"/><circle cx="70" cy="80" r="16" fill="#F0643C"/><circle cx="186" cy="80" r="16" fill="#F0643C"/><circle cx="128" cy="190" r="16" fill="#F0643C"/></svg>';
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const fmtN = (n) => n >= 1e6 ? (n / 1e6).toFixed(1) + 'M' : n >= 1e3 ? (n / 1e3).toFixed(1) + 'k' : String(n);
const tokens = (u) => u.input_tokens + u.output_tokens;
const colorOf = (m) => /opus|fable/.test(m) ? 'var(--c-opus)' : /sonnet/.test(m) ? 'var(--c-sonnet)' : /haiku/.test(m) ? 'var(--c-haiku)' : 'var(--c-other)';
function toast(t) { const e = $('#toast'); e.textContent = t; e.classList.add('show'); clearTimeout(toast.t); toast.t = setTimeout(() => e.classList.remove('show'), 2600); }
function md(t) {
  let s = esc(t);
  s = s.replace(/```(?:\w+)?\n?([\s\S]*?)```/g, (_m, c) => `<pre><code>${c.replace(/\n$/, '')}</code></pre>`);
  s = s.replace(/`([^`\n]+)`/g, '<code>$1</code>').replace(/\*\*([^*\n]+)\*\*/g, '<b>$1</b>');
  return s;
}

// ---- connection ----
let conn = JSON.parse(localStorage.getItem('conn') || 'null');
function parsePair(text) {   // accepts "https://host#key", "https://host key" or just an address
  text = text.trim(); let url = text, key = '';
  const m = text.match(/^(\S+?)(?:#|\s+)(\S+)$/); if (m) { url = m[1]; key = m[2]; }
  if (url && !/^https?:\/\//.test(url)) url = (/^[\d.]+(:\d+)?$|^localhost/.test(url) ? 'http://' : 'https://') + url;
  return { url: url.replace(/\/+$/, ''), key };
}
async function api(method, path, body) {
  let r;
  try {
    r = await fetch(conn.url + path, { method, headers: { Authorization: 'Bearer ' + conn.key, ...(body ? { 'content-type': 'application/json' } : {}) }, body: body ? JSON.stringify(body) : undefined });
  } catch { throw new Error(`can't reach ${conn.url}. Check the address and your internet.`); }
  if (r.status === 401) throw new Error('the key was refused. Paste the key again from the server.');
  const t = await r.text();
  if (!r.ok) throw new Error(t || `the server answered ${r.status}`);
  try { return JSON.parse(t); } catch { return t; }
}

// ---- state ----
const S = { tab: 'agents', agents: null, err: '', models: [], sel: null, msgs: {}, busy: new Set(), drafts: {} };
const root = $('#root');
let poll;

function connectView() {
  root.innerHTML = `<div class="center"><div class="card stack fade"><div class="row">${MARK}<h2>Agents</h2></div>
  <p class="muted small">Connect to your agent server. Your agents run there, not on this computer.</p>
  <div class="field"><label class="label" for="a">Server address</label><input id="a" placeholder="https://1-2-3-4.sslip.io" value="${esc(conn?.url || '')}"></div>
  <div class="field"><label class="label" for="k">Key</label><input id="k" type="password" value="${esc(conn?.key || '')}"></div>
  <p class="muted small">Tip: you can paste the whole pairing link from the installer (address#key) into the first box.</p>
  <p id="e" class="err small" role="alert"></p><button class="primary" id="go">Connect</button></div></div>${controls()}`;
  bindControls();
  const go = async () => {
    const p = parsePair($('#a').value); if (!p.key) p.key = $('#k').value;
    if (!p.url || !p.key) { $('#e').textContent = 'Fill in both the address and the key.'; return; }
    $('#go').disabled = true; $('#go').textContent = 'Connecting…'; $('#e').textContent = '';
    conn = p;
    try { await api('GET', '/agents'); localStorage.setItem('conn', JSON.stringify(conn)); start(); }
    catch (e) { $('#e').textContent = `Couldn't connect: ${e.message}`; $('#go').disabled = false; $('#go').textContent = 'Connect'; }
  };
  $('#go').onclick = go; root.querySelectorAll('input').forEach((i) => i.addEventListener('keydown', (e) => e.key === 'Enter' && go()));
}
const controls = () => `<div class="wc"><button class="ic" data-w="min" aria-label="Minimise">${icon('min')}</button><button class="ic" data-w="max" aria-label="Maximise">${icon('max')}</button><button class="ic x" data-w="close" aria-label="Close">${icon('x')}</button></div>`;
function bindControls() { root.querySelectorAll('[data-w]').forEach((b) => (b.onclick = () => window.shell.win(b.dataset.w))); }

function mainShell() {
  const nav = (id, label, ic) => `<button class="${S.tab === id ? 'on' : ''}" data-tab="${id}" ${S.tab === id ? 'aria-current="page"' : ''}>${icon(ic)}${label}</button>`;
  root.innerHTML = `<div id="app"><nav id="nav"><div class="brand">${MARK}Agents</div>${nav('agents', 'Agents', 'bot')}${nav('usage', 'Usage', 'chart')}<div class="sp"></div>${nav('settings', 'Settings', 'set')}</nav><main id="main"></main></div>${controls()}`;
  bindControls();
  root.querySelectorAll('[data-tab]').forEach((b) => (b.onclick = () => { S.tab = b.dataset.tab; mainShell(); }));
  ({ agents: agentsView, usage: usageView, settings: settingsView })[S.tab]();
}
const statusPill = () => S.err ? `<span class="pill warn"><i></i>Offline: ${esc(S.err)}</span>` : S.agents ? '<span class="pill ok"><i></i>Connected</span>' : '<span class="pill"><i></i>Connecting…</span>';
const modelName = (id) => (S.models.find((m) => m[0] === id) || [0, id])[1];

// ---- agents ----
function agentsView() {
  $('#main').innerHTML = `<div class="split"><div class="side" id="side"></div><div class="pane" id="pane"></div></div>`;
  renderSide(); renderPane();
}
function renderSide() {
  const side = $('#side'); if (!side) return;
  const list = S.agents || [];
  side.innerHTML = `<div class="row" style="padding:0 4px 8px"><h2 class="grow">Agents</h2><button class="primary" id="new">${icon('plus')}New</button></div>
  ${S.err ? `<div>${statusPill()}</div>` : ''}${!list.length && !S.err ? '<p class="muted small" style="padding:8px 4px">No agents yet. Press New to create one.</p>' : ''}
  ${list.map((a) => `<div class="item ${S.sel === a.id ? 'on' : ''}" role="button" tabindex="0" data-id="${a.id}"><div class="t"><span class="dot ${a.status}"></span><span class="ellip grow">${esc(a.spec.name)}</span>${a.status === 'running' ? '<span class="small muted">working</span>' : ''}</div><div class="small muted ellip">${esc(modelName(a.spec.model))} · ${fmtN(tokens(a.usage))} tokens · $${a.usage.cost_usd.toFixed(3)}</div></div>`).join('')}`;
  $('#new').onclick = () => editor(null);
  side.querySelectorAll('.item').forEach((i) => { const open = () => { S.sel = +i.dataset.id; loadMsgs(S.sel); renderSide(); renderPane(); }; i.onclick = open; i.onkeydown = (e) => e.key === 'Enter' && open(); });
}
async function loadMsgs(id) { try { S.msgs[id] = await api('GET', `/agents/${id}/messages`); if (S.sel === id) renderMsgs(); } catch (e) { toast(e.message); } }
function renderPane() {
  const pane = $('#pane'), a = (S.agents || []).find((x) => x.id === S.sel);
  if (!a) { pane.innerHTML = `<div class="empty">${MARK}<h3>Pick an agent</h3><p class="small">Choose one on the left, or create a new one.</p></div>`; return; }
  pane.innerHTML = `<div class="chat-head"><div class="grow"><h3 class="ellip">${esc(a.spec.name)}</h3><div class="small muted ellip" id="meta"></div></div>
  <button class="ic ghost" id="edit" aria-label="Edit agent">${icon('edit')}</button><button class="ic ghost" id="clear" aria-label="Clear chat" title="Clear chat">${icon('trash')}</button></div>
  <div class="msgs" id="msgs"><div class="col" id="col"></div></div>
  <div class="composer"><div class="cbox"><textarea id="in" rows="1" placeholder="Message ${esc(a.spec.name)}"></textarea><button class="primary" id="send" aria-label="Send">${icon('send')}</button></div></div>`;
  updateMeta(); renderMsgs();
  const ta = $('#in'); ta.value = S.drafts[a.id] || '';
  ta.oninput = () => { S.drafts[a.id] = ta.value; ta.style.height = 'auto'; ta.style.height = Math.min(ta.scrollHeight, 200) + 'px'; };
  ta.onkeydown = (e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(a.id); } };
  $('#send').onclick = () => send(a.id); $('#edit').onclick = () => editor(a);
  $('#clear').onclick = () => confirmBox(`Clear the chat with ${a.spec.name}?`, 'The messages and the conversation memory of this chat are removed. The agent itself and its usage stay.', 'Clear', async () => { await api('DELETE', `/agents/${a.id}/messages`); S.msgs[a.id] = []; renderMsgs(); });
  ta.focus();
}
function updateMeta() { const a = (S.agents || []).find((x) => x.id === S.sel), m = $('#meta'); if (a && m) m.textContent = `${modelName(a.spec.model)} · ${fmtN(tokens(a.usage))} tokens · $${a.usage.cost_usd.toFixed(3)}`; }
function renderMsgs() {
  const col = $('#col'); if (!col) return; const id = S.sel, list = S.msgs[id] || [];
  col.innerHTML = (!list.length && !S.busy.has(id) ? '<div class="empty"><p>Say something to start.</p></div>' : '') +
    list.map((m) => `<div class="msg selectable ${m.role}">${m.role === 'assistant' ? md(m.text) : esc(m.text)}</div>`).join('') +
    (S.busy.has(id) ? '<div class="msg"><span class="typing"><i></i><i></i><i></i></span> <span class="small muted">working</span></div>' : '');
  const box = $('#msgs'); box.scrollTo({ top: box.scrollHeight, behavior: 'smooth' });
}
async function send(id) {
  const ta = $('#in'); const text = ta.value.trim(); if (!text || S.busy.has(id)) return;
  ta.value = ''; S.drafts[id] = ''; ta.style.height = 'auto';
  (S.msgs[id] ||= []).push({ role: 'user', text }); S.busy.add(id); renderMsgs();
  try { await api('POST', `/agents/${id}/run`, { message: text }); } catch (e) { toast(e.message); }
  S.busy.delete(id); await loadMsgs(id); refresh();
}

function confirmBox(title, text, ok, fn) {
  const d = document.createElement('div'); d.className = 'scrim';
  d.innerHTML = `<div class="dialog" role="dialog" aria-modal="true"><h3>${esc(title)}</h3><p class="muted small">${esc(text)}</p><div class="row" style="justify-content:flex-end"><button class="ghost" id="no">Keep</button><button class="primary" id="yes">${esc(ok)}</button></div></div>`;
  document.body.appendChild(d); const close = () => d.remove();
  d.onclick = (e) => e.target === d && close(); $('#no', d).onclick = close;
  $('#yes', d).onclick = async () => { try { await fn(); } catch (e) { toast(e.message); } close(); refresh(); };
}

function editor(a) {
  const sp = a ? { ...a.spec } : { name: '', model: (S.models.find((m) => /sonnet-5/.test(m[0])) || S.models[0] || ['claude-sonnet-5-5'])[0], prompt: '', memory: '', mcp_config: '', subagents: false };
  const d = document.createElement('div'); d.className = 'scrim';
  const draw = () => {
    d.innerHTML = `<div class="dialog" role="dialog" aria-modal="true"><h2>${a ? 'Edit agent' : 'New agent'}</h2>
    <div class="field"><label class="label" for="n">Name</label><input id="n" placeholder="Research helper" value="${esc(sp.name)}"></div>
    <div class="field"><span class="label">Model</span><div class="row wrap">${S.models.map((m) => `<button class="chip ${sp.model === m[0] ? 'on' : ''}" data-m="${m[0]}" aria-pressed="${sp.model === m[0]}">${esc(m[1])}</button>`).join('')}</div></div>
    <div class="field"><label class="label" for="p">Instructions</label><textarea id="p" placeholder="What this agent is for and how it should answer">${esc(sp.prompt)}</textarea></div>
    <div class="field"><label class="label" for="mem">Memory</label><textarea id="mem" placeholder="Facts it should always remember">${esc(sp.memory)}</textarea></div>
    <div class="field"><label class="label" for="mcp">Tools (MCP file on the server)</label><input id="mcp" placeholder="/home/me/browser-mcp.json" value="${esc(sp.mcp_config || '')}"></div>
    <div class="row" style="min-height:40px"><span class="grow">Can start subagents</span><button class="toggle ${sp.subagents ? 'on' : ''}" id="sub" role="switch" aria-checked="${sp.subagents}" aria-label="Can start subagents"></button></div>
    <p id="e" class="err small" role="alert"></p><div class="row" style="justify-content:flex-end"><button class="ghost" id="cancel">Cancel</button><button class="primary" id="save">Save</button></div></div>`;
    const keep = () => { sp.name = $('#n', d).value; sp.prompt = $('#p', d).value; sp.memory = $('#mem', d).value; sp.mcp_config = $('#mcp', d).value; };
    d.querySelectorAll('[data-m]').forEach((b) => (b.onclick = () => { keep(); sp.model = b.dataset.m; draw(); }));
    $('#sub', d).onclick = () => { keep(); sp.subagents = !sp.subagents; draw(); };
    $('#cancel', d).onclick = () => d.remove();
    $('#save', d).onclick = async () => {
      keep(); if (!sp.name.trim()) { $('#e', d).textContent = 'Give the agent a name.'; return; }
      $('#save', d).disabled = true;
      try { const body = { ...sp, name: sp.name.trim(), mcp_config: sp.mcp_config.trim() || null }; if (a) await api('PUT', `/agents/${a.id}`, body); else S.sel = await api('POST', '/agents', body); d.remove(); await refresh(); renderPane(); }
      catch (e) { $('#e', d).textContent = `Couldn't save: ${e.message}. Try again.`; $('#save', d).disabled = false; }
    };
    $('#n', d).focus();
  };
  d.onclick = (e) => e.target === d && d.remove(); d.onkeydown = (e) => e.key === 'Escape' && d.remove();
  draw(); document.body.appendChild(d);
}

// ---- usage / settings ----
function usageView() {
  const l = S.agents || [], cost = l.reduce((s, a) => s + a.usage.cost_usd, 0), tok = l.reduce((s, a) => s + tokens(a.usage), 0), cache = l.reduce((s, a) => s + a.usage.cache_read_tokens, 0), act = l.filter((a) => a.status === 'running').length;
  const max = Math.max(1e-6, ...l.map((a) => a.usage.cost_usd)), sorted = [...l].sort((a, b) => b.usage.cost_usd - a.usage.cost_usd);
  $('#main').innerHTML = `<div class="page"><div class="col fade" style="max-width:1000px"><div class="head"><h1 class="grow">Usage</h1>${statusPill()}</div>
  <div class="card"><div class="label">Estimated cost</div><div class="big" style="margin-top:8px">$${cost.toFixed(2)}</div><p class="muted small" style="margin-top:8px">Counted from your Claude subscription runs. It shows how much you used, not a bill.</p></div>
  <div class="g3"><div class="card kpi"><div class="label">Working now</div><div class="v">${act}</div><span class="small muted">of ${l.length} agents</span></div>
  <div class="card kpi"><div class="label">Tokens</div><div class="v">${fmtN(tok)}</div><span class="small muted">input and output</span></div>
  <div class="card kpi"><div class="label">Read from cache</div><div class="v">${fmtN(cache)}</div><span class="small muted">tokens reused cheaply</span></div></div>
  <div class="card"><div class="label">By agent</div>${!sorted.length ? '<p class="muted small" style="margin-top:10px">Nothing yet. Usage appears after an agent answers.</p>' : ''}
  ${sorted.map((a) => `<div class="hbar"><span class="ellip">${esc(a.spec.name)} <span class="muted small">· ${esc(modelName(a.spec.model))}</span></span><span class="small">$${a.usage.cost_usd.toFixed(3)}</span><div class="t"><i style="width:${Math.max(1, a.usage.cost_usd / max * 100)}%;background:${colorOf(a.spec.model)}"></i></div></div>`).join('')}</div></div></div>`;
}
function settingsView() {
  $('#main').innerHTML = `<div class="page"><div class="col fade"><div class="head"><h1 class="grow">Settings</h1></div><div class="card stack"><div class="label">Server</div>
  <div class="row wrap"><span class="grow ellip selectable">${esc(conn.url)}</span>${statusPill()}</div>
  <p class="muted small">Agents, memory and the Telegram bridge live on the server, so they keep working when this app is closed.</p>
  <div class="row"><button class="ghost" id="out">Disconnect</button></div></div></div></div>`;
  $('#out').onclick = () => { localStorage.removeItem('conn'); conn = null; clearInterval(poll); connectView(); };
}

async function refresh() {
  try { S.agents = await api('GET', '/agents'); S.err = ''; } catch (e) { S.err = e.message; }
  if (S.tab === 'agents') { renderSide(); updateMeta(); if (S.sel && !(S.agents || []).some((a) => a.id === S.sel)) { S.sel = null; renderPane(); } }
  else if (S.tab === 'usage') usageView(); else if (S.tab === 'settings' && !$('#out')?.matches(':focus')) settingsView();
}
async function start() {
  S.agents = null; S.err = '';
  try { S.models = await api('GET', '/models'); } catch { S.models = []; }
  mainShell(); await refresh(); clearInterval(poll); poll = setInterval(refresh, 3000);
}
window.shell.onMax?.(() => {});
if (conn) start(); else connectView();
