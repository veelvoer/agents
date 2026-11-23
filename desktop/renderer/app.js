'use strict';
const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const ICONS = {
  bot: '<path d="M12 8V4H8"/><rect width="16" height="12" x="4" y="8" rx="2"/><path d="M2 14h2"/><path d="M20 14h2"/><path d="M15 13v2"/><path d="M9 13v2"/>',
  chart: '<path d="M3 3v16a2 2 0 0 0 2 2h16"/><path d="M18 17V9"/><path d="M13 17V5"/><path d="M8 17v-3"/>',
  set: '<line x1="21" x2="14" y1="4" y2="4"/><line x1="10" x2="3" y1="4" y2="4"/><line x1="21" x2="12" y1="12" y2="12"/><line x1="8" x2="3" y1="12" y2="12"/><line x1="21" x2="16" y1="20" y2="20"/><line x1="12" x2="3" y1="20" y2="20"/><line x1="14" x2="14" y1="2" y2="6"/><line x1="8" x2="8" y1="10" y2="14"/><line x1="16" x2="16" y1="18" y2="22"/>',
  plus: '<path d="M5 12h14"/><path d="M12 5v14"/>', send: '<path d="m22 2-7 20-4-9-9-4Z"/><path d="M22 2 11 13"/>',
  edit: '<path d="M21.2 5.6a2.1 2.1 0 0 0-3-3L3 17.8V21h3.2Z"/>', min: '<path d="M5 12h14"/>', max: '<rect width="14" height="14" x="5" y="5" rx="2"/>', x: '<path d="M18 6 6 18"/><path d="m6 6 12 12"/>',
  trash: '<path d="M3 6h18"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/>',
  pin: '<path d="M12 17v5"/><path d="M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V7a1 1 0 0 1 1-1 2 2 0 0 0 0-4H8a2 2 0 0 0 0 4 1 1 0 0 1 1 1z"/>',
  copy: '<rect width="14" height="14" x="8" y="8" rx="2"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/>',
  clock: '<circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/>', bell: '<path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9"/><path d="M10.3 21a1.94 1.94 0 0 0 3.4 0"/>', play: '<polygon points="6 3 20 12 6 21 6 3"/>',
  zap: '<path d="M4 14a1 1 0 0 1-.78-1.63l9.9-10.2a.5.5 0 0 1 .86.46l-1.92 6.02A1 1 0 0 0 13 10h7a1 1 0 0 1 .78 1.63l-9.9 10.2a.5.5 0 0 1-.86-.46l1.92-6.02A1 1 0 0 0 11 14z"/>', alert: '<path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3"/><path d="M12 9v4"/><path d="M12 17h.01"/>',
  repeat: '<path d="m17 2 4 4-4 4"/><path d="M3 11v-1a4 4 0 0 1 4-4h14"/><path d="m7 22-4-4 4-4"/><path d="M21 13v1a4 4 0 0 1-4 4H3"/>',
  search: '<circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/>', stop: '<rect width="12" height="12" x="6" y="6" rx="2"/>',
};
const icon = (n) => `<svg class="i" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${ICONS[n]}</svg>`;
const MARK = '<svg class="mark" viewBox="40 40 176 176" aria-hidden="true"><g stroke="#F0643C" stroke-width="12" stroke-linecap="round"><path d="M128 128 70 80M128 128 186 80M128 128 128 190"/></g><circle cx="128" cy="128" r="30" fill="#F0643C"/><circle cx="128" cy="128" r="12" fill="#FFD3A8"/><circle cx="70" cy="80" r="16" fill="#F0643C"/><circle cx="186" cy="80" r="16" fill="#F0643C"/><circle cx="128" cy="190" r="16" fill="#F0643C"/></svg>';
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const fmtN = (n) => n >= 1e6 ? (n / 1e6).toFixed(1) + 'M' : n >= 1e3 ? (n / 1e3).toFixed(1) + 'k' : String(n);
const tokens = (u) => u.input_tokens + u.output_tokens;
const colorOf = (m) => /opus|fable/.test(m) ? 'var(--c-opus)' : /sonnet/.test(m) ? 'var(--c-sonnet)' : /haiku/.test(m) ? 'var(--c-haiku)' : 'var(--c-other)';
const ago = (t) => { if (!t) return ''; const s = Math.max(0, Date.now() / 1000 - t); if (s < 60) return 'just now'; if (s < 3600) return Math.floor(s / 60) + ' min ago'; if (s < 86400) return Math.floor(s / 3600) + ' h ago'; return Math.floor(s / 86400) + ' d ago'; };
function toast(t) { const e = $('#toast'); e.textContent = t; e.classList.add('show'); clearTimeout(toast.t); toast.t = setTimeout(() => e.classList.remove('show'), 3200); }
function md(t) {
  let s = esc(t);
  s = s.replace(/```(?:\w+)?\n?([\s\S]*?)```/g, (_m, c) => `<pre><code>${c.replace(/\n$/, '')}</code></pre>`);
  return s.replace(/`([^`\n]+)`/g, '<code>$1</code>').replace(/\*\*([^*\n]+)\*\*/g, '<b>$1</b>');
}

// ---- settings (kept on this computer) ----
const DEF = { mode: 'system', colors: 'clay', textSize: 15, motion: 'full', autoUpdate: true, notify: false };
const cfg = { ...DEF, ...JSON.parse(localStorage.getItem('cfg') || '{}') };
const saveCfg = () => localStorage.setItem('cfg', JSON.stringify(cfg));

// ---- colours ----
const hex = (c) => { c = String(c || '').trim(); let m = c.match(/^#([0-9a-f]{3,8})$/i); if (m) { let h = m[1]; if (h.length < 6) h = h.split('').map((x) => x + x).join(''); return [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16)); } m = c.match(/rgba?\(\s*([\d.]+)[ ,]+([\d.]+)[ ,]+([\d.]+)/); return m ? [+m[1], +m[2], +m[3]] : null; };
const toHex = (a) => '#' + a.map((n) => Math.round(Math.max(0, Math.min(255, n))).toString(16).padStart(2, '0')).join('');
const mix = (a, b, t) => { const x = hex(a), y = hex(b); return toHex(x.map((v, i) => v + (y[i] - v) * t)); };
const lum = (c) => { const [r, g, b] = hex(c).map((v) => { v /= 255; return v <= .03928 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4; }); return .2126 * r + .7152 * g + .0722 * b; };
const contrast = (a, b) => { const x = lum(a), y = lum(b); return (Math.max(x, y) + .05) / (Math.min(x, y) + .05); };
const ensure = (c, bg, min) => { let out = c; const fg = lum(bg) < .4 ? '#ffffff' : '#000000'; for (let t = 0; t <= 1 && contrast(out, bg) < min; t += .05) out = mix(c, fg, t); return out; };
const bw = (bg) => (contrast('#ffffff', bg) >= contrast('#000000', bg) ? '#ffffff' : '#000000');
const VARS = ['--bg', '--card', '--ink', '--muted', '--line', '--line-ctl', '--clay', '--onclay', '--side', '--active', '--ok'];
let sys = null;
function palette() {
  if (!sys || !sys.bg || !hex(sys.bg)) return null;
  const bg = sys.bg, dark = lum(bg) < .35, fg = hex(sys.fg) ? sys.fg : dark ? '#eeeeee' : '#222222';
  const accent = hex(sys.accent) ? sys.accent : (dark ? '#F0643C' : '#B04D2A');
  const clay = ensure(accent, bg, 4.5);
  const card = sys.card && hex(sys.card) && sys.card.toLowerCase() !== bg.toLowerCase() ? sys.card : mix(bg, fg, dark ? .06 : .03);
  const side = sys.side && hex(sys.side) && sys.side.toLowerCase() !== bg.toLowerCase() ? sys.side : mix(bg, '#000000', dark ? .22 : .035);
  const onclay = sys.accentFg && hex(sys.accentFg) && contrast(sys.accentFg, clay) >= 4.5 ? sys.accentFg : bw(clay);
  return { dark, vars: { '--bg': bg, '--card': card, '--ink': fg, '--muted': ensure(mix(bg, fg, .62), bg, 4.5), '--line': mix(bg, fg, .14), '--line-ctl': ensure(mix(bg, fg, .5), bg, 3), '--clay': clay, '--onclay': onclay, '--side': side, '--active': mix(bg, fg, .1), '--ok': dark ? '#4CAF7D' : '#2F7A53' } };
}
function applyTheme() {
  const r = document.documentElement;
  const p = cfg.colors === 'system' ? palette() : null;
  if (p) { for (const k of VARS) r.style.setProperty(k, p.vars[k]); r.dataset.theme = p.dark ? 'dark' : 'light'; r.style.colorScheme = p.dark ? 'dark' : 'light'; }
  else {
    for (const k of VARS) r.style.removeProperty(k);
    const dark = cfg.mode === 'dark' || (cfg.mode === 'system' && (sys ? sys.dark : matchMedia('(prefers-color-scheme: dark)').matches));
    r.dataset.theme = dark ? 'dark' : 'light'; r.style.colorScheme = dark ? 'dark' : 'light';
  }
  r.style.setProperty('--fs', cfg.textSize + 'px');
  r.dataset.motion = cfg.motion;
}
applyTheme();
window.shell.getFrame().then((f) => { S.framed = f; document.documentElement.classList.toggle('framed', f); });
window.shell.theme().then((t) => { sys = t; applyTheme(); if (S.tab === 'settings') settingsView(); });
window.shell.onTheme((t) => { sys = t; applyTheme(); if (S.tab === 'settings') settingsView(); });

// ---- connection ----
let conn = JSON.parse(localStorage.getItem('conn') || 'null');
function parsePair(text) {
  text = text.trim(); let url = text, key = '';
  const m = text.match(/^(\S+?)(?:#|\s+)(\S+)$/); if (m) { url = m[1]; key = m[2]; }
  if (url && !/^https?:\/\//.test(url)) url = (/^[\d.]+(:\d+)?$|^localhost/.test(url) ? 'http://' : 'https://') + url;
  return { url: url.replace(/\/+$/, ''), key };
}
async function api(method, path, body, signal) {
  let r;
  try { r = await fetch(conn.url + path, { method, signal, headers: { Authorization: 'Bearer ' + conn.key, ...(body !== undefined ? { 'content-type': 'application/json' } : {}) }, body: body !== undefined ? JSON.stringify(body) : undefined }); }
  catch (e) { if (e.name === 'AbortError') throw e; throw new Error(`can't reach ${conn.url}. Check the address and your internet.`); }
  if (r.status === 401) throw new Error('the key was refused. Paste the key again from the server.');
  const t = await r.text();
  if (!r.ok) throw new Error(t || `the server answered ${r.status}`);
  try { return JSON.parse(t); } catch { return t; }
}

// ---- state ----
const S = { jobs: null, acts: [], actLast: 0, seen: +(localStorage.getItem('seen') || 0), actFilter: 'all', srv: null, paused: false, tab: 'agents', agents: null, rev: 0, err: '', models: [], sel: null, chat: {}, busy: new Set(), drafts: {}, q: '', health: null, upd: null, updBusy: false, prog: 0, prev: new Map() };
const root = $('#root');
let ctl = null, running = false;

const controls = () => `<div class="wc"><button class="ic" data-w="min" aria-label="Minimise">${icon('min')}</button><button class="ic" data-w="max" aria-label="Maximise">${icon('max')}</button><button class="ic x" data-w="close" aria-label="Close">${icon('x')}</button></div>`;
const bindControls = () => $$('[data-w]', root).forEach((b) => (b.onclick = () => window.shell.win(b.dataset.w)));

function connectView() {
  root.innerHTML = `<div class="center"><div class="card stack fade"><div class="row">${MARK}<h2>Agents</h2></div>
  <p class="muted small">Connect to your agent server. Your agents run there, not on this computer.</p>
  <div class="field"><label class="label" for="a">Server address</label><input id="a" placeholder="https://1-2-3-4.sslip.io" value="${esc(conn?.url || '')}" autocomplete="off"></div>
  <div class="field"><label class="label" for="k">Key</label><input id="k" type="password" value="${esc(conn?.key || '')}" autocomplete="off"></div>
  <p class="muted small">Tip: paste the whole pairing line from the installer (address#key) into the first box.</p>
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
  $('#go').onclick = go; $$('input', root).forEach((i) => i.addEventListener('keydown', (e) => e.key === 'Enter' && go()));
}

function mainShell() {
  const nav = (id, label, ic) => `<button class="${S.tab === id ? 'on' : ''}" data-tab="${id}" title="${label}" ${S.tab === id ? 'aria-current="page"' : ''}>${icon(ic)}<span class="lb">${label}</span></button>`;
  root.innerHTML = `<div id="app"><nav id="nav"><div class="brand">${MARK}<span class="lb">Agents</span></div>${nav('agents', 'Agents', 'bot')}${nav('activity', 'Activity', 'bell')}${nav('usage', 'Usage', 'chart')}<div class="sp"></div>${nav('settings', 'Settings', 'set')}</nav>
  <main id="main"><div id="banner"></div><div id="view" style="flex:1;min-height:0;display:flex;flex-direction:column"></div></main></div>${controls()}`;
  bindControls();
  $$('[data-tab]', root).forEach((b) => (b.onclick = () => go(b.dataset.tab)));
  renderBanner(); updateBadge(); ({ agents: agentsView, activity: activityView, usage: usageView, settings: settingsView })[S.tab]();
}
function go(tab) { S.tab = tab; mainShell(); }
const view = () => $('#view');
const statusPill = () => S.err ? `<span class="pill warn"><i></i>Offline</span>` : S.agents ? '<span class="pill ok"><i></i>Connected</span>' : '<span class="pill"><i></i>Connecting…</span>';
const modelName = (id) => (S.models.find((m) => m[0] === id) || [0, id])[1];
const byId = (id) => (S.agents || []).find((a) => a.id === id);

// ---- update banner ----
function renderBanner() {
  const b = $('#banner'); if (!b) return; const u = S.upd;
  if (!u || !u.available) { b.innerHTML = ''; return; }
  b.innerHTML = `<div class="banner" role="status"><span class="grow">${S.updBusy ? 'Downloading version ' + esc(u.latest) + '…' : `Version <b>${esc(u.latest)}</b> is ready.`}</span>
  ${S.updBusy ? `<div class="bar"><i style="width:${Math.round(S.prog * 100)}%"></i></div>` : `<button class="primary sm" id="upd-go">${u.canInstall ? 'Update now' : 'See what is new'}</button><button class="sm ghost" id="upd-later">Later</button>`}</div>`;
  $('#upd-go')?.addEventListener('click', () => (u.canInstall ? doUpdate() : window.shell.openExternal(u.page)));
  $('#upd-later')?.addEventListener('click', () => { S.upd = { ...u, available: false }; renderBanner(); });
}
async function checkUpdates(manual) {
  const r = await window.shell.checkUpdate(); S.upd = r;
  if (manual) toast(!r.ok ? `Couldn't check: ${r.error}` : r.available ? `Version ${r.latest} is available.` : 'You have the newest version.');
  if (S.health && r.ok) S.health.latestNewer = newerV(r.latest, S.health.version);
  renderBanner(); if (S.tab === 'settings') settingsView();
}
async function doUpdate() {
  S.updBusy = true; S.prog = 0; renderBanner();
  const off = window.shell.onProgress((p) => { S.prog = p; renderBanner(); });
  const r = await window.shell.installUpdate(); off();
  if (!r.ok) { S.updBusy = false; renderBanner(); toast(`Update failed: ${r.error}`); }
}

// ---- data ----
async function loadList() {
  while (running) {
    ctl = new AbortController();
    try {
      const d = await api('GET', `/agents?since=${S.rev}`, undefined, ctl.signal);
      S.err = ''; S.paused = d.paused; if (d.rev !== S.rev || !S.agents) { S.rev = d.rev; S.agents = d.agents; if (d.act_last !== S.actLast) fetchActivity(); onAgents(); } else renderStatus();
    } catch (e) {
      if (e.name === 'AbortError') { if (!running) return; if (document.hidden) await new Promise((r) => document.addEventListener('visibilitychange', () => !document.hidden && r(), { once: true })); continue; }
      S.err = e.message; renderStatus(); S.rev = 0;
      await new Promise((r) => setTimeout(r, 4000));
    }
  }
}
function renderStatus() { if (S.tab === 'agents') { const s = $('#side .pill-slot'); if (s) s.innerHTML = S.err ? statusPill() : ''; } else if (['usage', 'settings', 'activity'].includes(S.tab)) { $$('.status-slot').forEach((e) => (e.innerHTML = statusPill())); } }
function onAgents() {
  // finished work: refresh the open chat, and tell the user if the window is in the background
  for (const a of S.agents) {
    const p = S.prev.get(a.id);
    if (p && (p.status !== a.status || p.messages !== a.messages)) { if (a.id === S.sel) syncMsgs(a.id); if (p.status === 'running' && a.status === 'idle' && cfg.notify && !document.hasFocus()) { try { new Notification(a.spec.name, { body: 'Finished working.', tag: 'agent-' + a.id }); } catch {} } }
    S.prev.set(a.id, { status: a.status, messages: a.messages });
  }
  if (S.tab === 'agents') { renderSide(); updateHead(); if (S.sel && !byId(S.sel)) { S.sel = null; renderPane(); } } else if (S.tab === 'usage') usageView(); else if (S.tab === 'settings') refreshSettings();
  if (S.tab !== 'agents') return; refreshJobsSoon();
}
async function syncMsgs(id, older) {
  try {
    const c = (S.chat[id] ||= { start: 0, total: 0, list: [] });
    if (older) {
      const d = await api('GET', `/agents/${id}/messages?before=${c.start}&limit=100`);
      c.list = d.messages.concat(c.list); c.start = d.start;
    } else {
      const d = await api('GET', `/agents/${id}/messages?limit=100`);
      if (!c.list.length || d.start <= c.start) { c.list = d.messages; c.start = d.start; } else c.list = c.list.slice(0, d.start - c.start).concat(d.messages);
      c.total = d.total;
    }
    if (S.sel === id) renderMsgs(older);
  } catch (e) { toast(e.message); }
}

// ---- agents view ----
function agentsView() {
  view().innerHTML = `<div class="split"><div class="side" id="side"></div><div class="pane" id="pane"></div></div>`;
  renderSide(); renderPane();
}
function itemHtml(a) {
  const run = a.status === 'running';
  return `<div class="item ${S.sel === a.id ? 'on' : ''}" role="button" tabindex="0" draggable="${!S.q}" data-id="${a.id}"><div class="t"><span class="dot ${a.status}"></span><span class="ellip grow">${esc(a.spec.name)}</span>${a.pinned ? `<span class="pin" aria-label="Pinned">${icon('pin')}</span>` : ''}${run ? '<span class="small muted">working</span>' : ''}</div>
  <div class="small muted">${esc(modelName(a.spec.model))} · ${fmtN(tokens(a.usage))} tokens · $${a.usage.cost_usd.toFixed(3)}${a.last_active ? ' · ' + ago(a.last_active) : ''}</div>${(() => { const r = repeatInfo(a); return r ? `<div class="small ${r.on ? '' : 'muted'} repeatline">${icon('repeat')}<span>${esc(r.text)}</span></div>` : ''; })()}
  <div class="act"><button class="ic" data-pin="${a.id}" aria-label="${a.pinned ? 'Unpin' : 'Pin'} ${esc(a.spec.name)}" title="${a.pinned ? 'Unpin' : 'Pin to top'}">${icon('pin')}</button></div></div>`;
}
function renderSide() {
  const side = $('#side'); if (!side) return;
  const keep = $('#q') ? { v: $('#q').value, f: document.activeElement === $('#q') } : null;
  const all = S.agents || [], q = S.q.trim().toLowerCase();
  const list = q ? all.filter((a) => (a.spec.name + ' ' + modelName(a.spec.model)).toLowerCase().includes(q)) : all;
  const pinned = list.filter((a) => a.pinned), rest = list.filter((a) => !a.pinned);
  side.innerHTML = `<div class="row" style="padding:0 4px 8px"><h2 class="grow">Agents</h2><button class="primary" id="new" title="New agent (Ctrl+N)">${icon('plus')}New</button></div>
  <div class="search">${icon('search')}<input id="q" placeholder="Search agents" aria-label="Search agents" value="${esc(S.q)}"></div><div class="pill-slot">${S.err ? statusPill() : ''}</div>${S.paused ? '<div class="card row warnbox" style="padding:10px 12px"><span class="grow small">Repeating is paused.</span><button class="primary sm" id="resume">Resume</button></div>' : ''}
  ${!all.length && !S.err ? '<p class="muted small" style="padding:8px 4px">No agents yet. Press New to create one.</p>' : ''}${all.length && !list.length ? '<p class="muted small" style="padding:8px 4px">No agent matches that.</p>' : ''}
  ${pinned.length ? `<div class="sec">Pinned</div>${pinned.map(itemHtml).join('')}` : ''}${pinned.length && rest.length ? '<div class="sec">Agents</div>' : ''}${rest.map(itemHtml).join('')}`;
  $('#new').onclick = () => editor(null);
  $('#resume')?.addEventListener('click', async () => { try { S.srv = await api('PUT', '/settings', { paused: false }); S.paused = false; renderSide(); toast('Repeating resumed'); } catch (e) { toast(e.message); } });
  const q2 = $('#q'); q2.oninput = () => { S.q = q2.value; renderSide(); $('#q').focus(); $('#q').setSelectionRange(S.q.length, S.q.length); };
  if (keep?.f) { q2.focus(); q2.setSelectionRange(q2.value.length, q2.value.length); }
  $$('.item', side).forEach((i) => {
    const id = +i.dataset.id, open = () => { S.sel = id; if (!S.chat[id]) syncMsgs(id); renderSide(); renderPane(); };
    i.onclick = (e) => { if (e.target.closest('[data-pin]')) return; open(); }; i.onkeydown = (e) => { if (e.key === 'Enter' && e.target === i) open(); };
    i.ondragstart = (e) => { e.dataTransfer.setData('text/plain', String(id)); i.classList.add('dragging'); }; i.ondragend = () => i.classList.remove('dragging');
    i.ondragover = (e) => { if (!S.q) { e.preventDefault(); i.classList.add('drop'); } }; i.ondragleave = () => i.classList.remove('drop');
    i.ondrop = async (e) => { e.preventDefault(); i.classList.remove('drop'); const from = +e.dataTransfer.getData('text/plain'); if (!from || from === id) return;
      const ids = (S.agents || []).map((a) => a.id).filter((x) => x !== from); ids.splice(ids.indexOf(id), 0, from); S.agents.sort((a, b) => ids.indexOf(a.id) - ids.indexOf(b.id)); renderSide();
      try { await api('POST', '/agents/order', ids); } catch (err) { toast(err.message); } };
  });
  $$('[data-pin]', side).forEach((b) => (b.onclick = (e) => { e.stopPropagation(); const a = byId(+b.dataset.pin); togglePin(a); }));
}
async function togglePin(a) { try { await api('POST', `/agents/${a.id}/pin`, { pinned: !a.pinned }); toast(a.pinned ? 'Unpinned' : 'Pinned to the top'); } catch (e) { toast(e.message); } }

function renderPane() {
  const pane = $('#pane'); if (!pane) return; const a = byId(S.sel);
  if (!a) { pane.innerHTML = `<div class="empty">${MARK}<h3>Pick an agent</h3><p class="small">Choose one on the left, or press <span class="kbd">Ctrl</span> <span class="kbd">N</span> for a new one. Any agent can repeat by itself: open it and press Repeat.</p></div>`; return; }
  pane.innerHTML = `<div class="chat-head"><div class="grow"><h3 id="hname"></h3><div class="small muted" id="meta"></div></div>
  <button class="ghost" id="hrep"></button><button class="ic ghost" id="hpin"></button><button class="ic ghost" id="edit" aria-label="Edit agent" title="Edit agent">${icon('edit')}</button><button class="ic ghost" id="clear" aria-label="Clear chat" title="Clear chat">${icon('trash')}</button></div>
  <div class="msgs" id="msgs"><div class="col" id="col"></div></div>
  <div class="composer"><div class="cbox"><textarea id="in" rows="1" placeholder="Message ${esc(a.spec.name)}" aria-label="Message"></textarea><button class="primary" id="send"></button></div></div>`;
  updateHead(); renderMsgs();
  const ta = $('#in'); ta.value = S.drafts[a.id] || ''; ta.style.height = 'auto'; ta.style.height = Math.min(ta.scrollHeight, 200) + 'px';
  ta.oninput = () => { S.drafts[a.id] = ta.value; ta.style.height = 'auto'; ta.style.height = Math.min(ta.scrollHeight, 200) + 'px'; };
  ta.onkeydown = (e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(a.id); } };
  $('#edit').onclick = () => editor(byId(a.id));
  $('#clear').onclick = () => confirmBox(`Clear the chat with ${a.spec.name}?`, 'The messages and the conversation memory of this chat are removed. The agent itself and its usage stay.', 'Clear', async () => { await api('DELETE', `/agents/${a.id}/messages`); S.chat[a.id] = { start: 0, total: 0, list: [] }; renderMsgs(); });
  ta.focus();
}
function updateHead() {
  const a = byId(S.sel); if (!a || !$('#hname')) return;
  $('#hname').textContent = a.spec.name; $('#meta').textContent = `${modelName(a.spec.model)} · ${fmtN(tokens(a.usage))} tokens · $${a.usage.cost_usd.toFixed(3)}`;
  const r = repeatInfo(a), hb = $('#hrep'); hb.innerHTML = icon('repeat') + `<span>${r && r.on ? 'Repeating' : 'Repeat'}</span>`; hb.title = r ? r.text : 'Make this agent repeat by itself'; hb.setAttribute('aria-label', r ? 'Repeat settings: ' + r.text : 'Make this agent repeat'); hb.style.color = r && r.on ? 'var(--clay)' : ''; hb.onclick = () => repeatDialog(byId(a.id));
  const p = $('#hpin'); p.innerHTML = icon('pin'); p.setAttribute('aria-label', a.pinned ? 'Unpin' : 'Pin'); p.title = a.pinned ? 'Unpin' : 'Pin to top'; p.style.color = a.pinned ? 'var(--clay)' : ''; p.onclick = () => togglePin(byId(a.id));
  const run = a.status === 'running', b = $('#send'); if (b) { b.innerHTML = icon(run ? 'stop' : 'send'); b.setAttribute('aria-label', run ? 'Stop' : 'Send'); b.title = run ? 'Stop' : 'Send'; b.onclick = () => (run ? stopRun(a.id) : send(a.id)); }
  renderTyping();
}
function renderMsgs(keepScroll) {
  const col = $('#col'); if (!col) return; const id = S.sel, c = S.chat[id] || { start: 0, list: [] }, box = $('#msgs'), prevH = box.scrollHeight;
  col.innerHTML = (c.start > 0 ? '<button class="ghost sm more" id="more">Load earlier messages</button>' : '') +
    (!c.list.length ? '<div class="empty"><p>Say something to start.</p></div>' : '') +
    c.list.map((m, i) => m.role === 'auto' ? `<div class="autolabel small muted" data-i="${i}">${icon('clock')}<span>${esc(m.text)}</span><span>${m.at ? new Date(m.at * 1000).toLocaleString([], { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : ''}</span></div>` : `<div class="msg selectable ${m.role}" data-i="${i}" title="${m.at ? new Date(m.at * 1000).toLocaleString() : ''}">${m.role === 'assistant' ? md(m.text) : esc(m.text)}<button class="cp" aria-label="Copy" title="Copy">${icon('copy')}</button></div>`).join('') + '<div id="typing"></div>';
  $$('.cp', col).forEach((b) => (b.onclick = async () => { await navigator.clipboard.writeText(c.list[+b.parentNode.dataset.i].text); toast('Copied'); }));
  $('#more')?.addEventListener('click', () => syncMsgs(id, true));
  renderTyping();
  if (keepScroll) box.scrollTop = box.scrollHeight - prevH; else box.scrollTo({ top: box.scrollHeight, behavior: 'smooth' });
}
function renderTyping() { const t = $('#typing'); const a = byId(S.sel); if (t) t.innerHTML = a && a.status === 'running' ? '<div class="msg"><span class="typing"><i></i><i></i><i></i></span> <span class="small muted">working</span></div>' : ''; }
async function send(id) {
  const ta = $('#in'); const text = ta.value.trim(); const a = byId(id); if (!text || !a || a.status === 'running') return;
  ta.value = ''; S.drafts[id] = ''; ta.style.height = 'auto';
  const c = (S.chat[id] ||= { start: 0, total: 0, list: [] }); c.list.push({ role: 'user', text, at: Date.now() / 1000 }); a.status = 'running'; renderMsgs(); updateHead(); renderSide();
  try { await api('POST', `/agents/${id}/run`, { message: text }); } catch (e) { toast(e.message); a.status = 'idle'; syncMsgs(id); updateHead(); }
}
async function stopRun(id) { try { await api('POST', `/agents/${id}/stop`); } catch (e) { toast(e.message); } }

function confirmBox(title, text, ok, fn) {
  const d = document.createElement('div'); d.className = 'scrim';
  d.innerHTML = `<div class="dialog" role="dialog" aria-modal="true" aria-label="${esc(title)}"><h3>${esc(title)}</h3><p class="muted small">${esc(text)}</p><div class="row" style="justify-content:flex-end"><button class="ghost" id="no">Keep</button><button class="primary" id="yes">${esc(ok)}</button></div></div>`;
  document.body.appendChild(d); const close = () => d.remove();
  d.onclick = (e) => e.target === d && close(); d.onkeydown = (e) => e.key === 'Escape' && close(); $('#no', d).onclick = close;
  $('#yes', d).onclick = async () => { try { await fn(); } catch (e) { toast(e.message); } close(); }; $('#no', d).focus();
}

function editor(a) {
  const sp = a ? { autonomy: 'safe', os_tools: true, daily_cap: null, ...a.spec, mcp_config: a.spec.mcp_config || '', workdir: a.spec.workdir || '' } : { name: '', model: (S.models.find((m) => /sonnet-5/.test(m[0])) || S.models[0] || ['claude-sonnet-5-5'])[0], prompt: '', memory: '', mcp_config: '', subagents: false, autonomy: 'safe', daily_cap: null, os_tools: true, workdir: '' };
  const d = document.createElement('div'); d.className = 'scrim';
  const known = () => S.models.some((m) => m[0] === sp.model);
  const draw = () => {
    d.innerHTML = `<div class="dialog" role="dialog" aria-modal="true" aria-label="${a ? 'Edit agent' : 'New agent'}"><h2>${a ? 'Edit agent' : 'New agent'}</h2>
    <div class="field"><label class="label" for="n">Name</label><input id="n" placeholder="Research helper" value="${esc(sp.name)}"></div>
    <div class="field"><span class="label">Model</span><div class="row wrap">${S.models.map((m) => `<button class="chip ${sp.model === m[0] ? 'on' : ''}" data-m="${m[0]}" aria-pressed="${sp.model === m[0]}">${esc(m[1])}</button>`).join('')}</div>
    <input id="cm" placeholder="Or type any model id, e.g. claude-opus-5-5" value="${known() ? '' : esc(sp.model)}" aria-label="Custom model id"></div>
    <div class="field"><label class="label" for="p">Instructions</label><textarea id="p" placeholder="What this agent is for and how it should answer">${esc(sp.prompt)}</textarea></div>
    <div class="field"><label class="label" for="mem">Memory</label><textarea id="mem" placeholder="Facts it should always remember">${esc(sp.memory)}</textarea></div>
    <div class="field"><label class="label" for="mcp">Tools (MCP file on the server)</label><input id="mcp" placeholder="/home/me/browser-mcp.json" value="${esc(sp.mcp_config)}"></div>
    <div class="field"><span class="label">What it may do on the server</span>${seg('e-auto', [['safe', 'Look only'], ['standard', 'Edit files and browse'], ['full', 'Everything']], sp.autonomy)}<p class="tip">${{ safe: 'Reads files and answers. Nothing is changed.', standard: 'Can edit files in its folder and search the web.', full: 'Can run any command on the server without asking. Only for agents you trust.' }[sp.autonomy]}</p></div>
    <div class="row" style="min-height:40px"><span class="grow">Agentic tools: message me, schedule tasks, remember, ask other agents</span><button class="toggle ${sp.os_tools ? 'on' : ''}" id="e-os" role="switch" aria-checked="${sp.os_tools}" aria-label="Agentic tools"></button></div>
    <div class="row wrap"><div class="field grow"><label class="label" for="e-cap">Daily limit for automatic runs ($, optional)</label><input id="e-cap" type="number" min="0" step="0.5" value="${sp.daily_cap ?? ''}"></div><div class="field grow"><label class="label" for="e-wd">Working folder (optional)</label><input id="e-wd" placeholder="/home/me/projects/site" value="${esc(sp.workdir)}"></div></div>
    <div class="row" style="min-height:40px"><span class="grow">Can start subagents</span><button class="toggle ${sp.subagents ? 'on' : ''}" id="sub" role="switch" aria-checked="${sp.subagents}" aria-label="Can start subagents"></button></div>
    <p id="e" class="err small" role="alert"></p>
    <div class="row">${a ? '<button class="ghost" id="dup">Duplicate</button><button class="ghost" id="del">Delete</button>' : ''}<span class="grow"></span><button class="ghost" id="cancel">Cancel</button><button class="primary" id="save">Save</button></div></div>`;
    const keep = () => { sp.name = $('#n', d).value; sp.prompt = $('#p', d).value; sp.memory = $('#mem', d).value; sp.mcp_config = $('#mcp', d).value; sp.daily_cap = $('#e-cap', d).value === '' ? null : +$('#e-cap', d).value; sp.workdir = $('#e-wd', d).value; const cm = $('#cm', d).value.trim(); if (cm) sp.model = cm; };
    $$('[data-m]', d).forEach((b) => (b.onclick = () => { keep(); sp.model = b.dataset.m; $('#cm', d) && ($('#cm', d).value = ''); draw(); }));
    $$('#e-auto button', d).forEach((b) => (b.onclick = () => { keep(); sp.autonomy = b.dataset.v; draw(); }));
    $('#e-os', d).onclick = () => { keep(); sp.os_tools = !sp.os_tools; draw(); };
    $('#sub', d).onclick = () => { keep(); sp.subagents = !sp.subagents; draw(); };
    $('#cancel', d).onclick = () => d.remove();
    const body = () => ({ ...sp, name: sp.name.trim(), mcp_config: sp.mcp_config.trim() || null, workdir: sp.workdir.trim() || null });
    $('#save', d).onclick = async () => {
      keep(); if (!sp.name.trim()) { $('#e', d).textContent = 'Give the agent a name.'; return; }
      $('#save', d).disabled = true;
      try { if (a) await api('PUT', `/agents/${a.id}`, body()); else S.sel = await api('POST', '/agents', body()); d.remove(); if (S.tab === 'agents') renderPane(); }
      catch (e) { $('#e', d).textContent = `Couldn't save: ${e.message}. Try again.`; $('#save', d).disabled = false; }
    };
    $('#dup', d)?.addEventListener('click', async () => { keep(); try { S.sel = await api('POST', '/agents', { ...body(), name: sp.name.trim() + ' copy' }); d.remove(); renderPane(); toast('Duplicated'); } catch (e) { $('#e', d).textContent = e.message; } });
    $('#del', d)?.addEventListener('click', () => { d.remove(); confirmBox(`Delete ${a.spec.name}?`, "Its chat, memory and usage totals are removed from the server. This can't be undone.", 'Delete', async () => { await api('DELETE', `/agents/${a.id}`); if (S.sel === a.id) { S.sel = null; renderPane(); } }); });
    $('#n', d).focus();
  };
  d.onclick = (e) => e.target === d && d.remove(); d.onkeydown = (e) => e.key === 'Escape' && d.remove();
  draw(); document.body.appendChild(d); $('#n', d)?.focus();
}

// ---- usage ----
function usageView() {
  const l = S.agents || [], cost = l.reduce((s, a) => s + a.usage.cost_usd, 0), tok = l.reduce((s, a) => s + tokens(a.usage), 0), cache = l.reduce((s, a) => s + a.usage.cache_read_tokens, 0), act = l.filter((a) => a.status === 'running').length;
  const days = []; for (let i = 13; i >= 0; i--) { const d = new Date(Date.now() - i * 864e5).toISOString().slice(0, 10); days.push(d); }
  const per = Object.fromEntries(days.map((d) => [d, 0])); for (const a of l) for (const [d, v] of a.daily || []) if (d in per) per[d] += v.cost;
  const dmax = Math.max(1e-6, ...Object.values(per)), week = days.slice(-7).reduce((s, d) => s + per[d], 0);
  const max = Math.max(1e-6, ...l.map((a) => a.usage.cost_usd)), sorted = [...l].sort((a, b) => b.usage.cost_usd - a.usage.cost_usd);
  const models = {}; for (const a of l) models[a.spec.model] = (models[a.spec.model] || 0) + a.usage.cost_usd;
  const mm = Math.max(1e-6, ...Object.values(models));
  view().innerHTML = `<div class="page"><div class="col fade" style="max-width:1000px"><div class="head"><h1 class="grow">Usage</h1><span class="status-slot">${statusPill()}</span></div>
  <div class="card"><div class="row wrap"><div class="grow"><div class="label">Estimated cost</div><div class="big" style="margin-top:8px">$${cost.toFixed(2)}</div></div><div><div class="label">Last 7 days</div><div class="kpi"><div class="v">$${week.toFixed(2)}</div></div></div></div>
  <div class="chart" role="img" aria-label="Cost per day, last 14 days">${days.map((d) => `<div title="${d}: $${per[d].toFixed(3)}"><i class="${per[d] ? '' : 'zero'}" style="height:${per[d] ? Math.max(3, per[d] / dmax * 100) : 2}%"></i><span>${d.slice(8)}</span></div>`).join('')}</div>
  <p class="muted small" style="margin-top:10px">Counted from your Claude subscription runs. It shows how much you used, not a bill.</p></div>
  <div class="g3"><div class="card kpi"><div class="label">Working now</div><div class="v">${act}</div><span class="small muted">of ${l.length} agents</span></div>
  <div class="card kpi"><div class="label">Tokens</div><div class="v">${fmtN(tok)}</div><span class="small muted">input and output</span></div>
  <div class="card kpi"><div class="label">Read from cache</div><div class="v">${fmtN(cache)}</div><span class="small muted">tokens reused cheaply</span></div></div>
  <div class="g2"><div class="card"><div class="label">By agent</div>${!sorted.length ? '<p class="muted small" style="margin-top:10px">Nothing yet. Usage appears after an agent answers.</p>' : ''}
  ${sorted.map((a) => `<div class="hbar"><span class="ellip">${esc(a.spec.name)}</span><span class="small">$${a.usage.cost_usd.toFixed(3)}</span><div class="t"><i style="width:${Math.max(1, a.usage.cost_usd / max * 100)}%;background:${colorOf(a.spec.model)}"></i></div></div>`).join('')}</div>
  <div class="card"><div class="label">By model</div>${Object.entries(models).sort((a, b) => b[1] - a[1]).map(([m, c]) => `<div class="hbar"><span class="ellip">${esc(modelName(m))}</span><span class="small">$${c.toFixed(3)}</span><div class="t"><i style="width:${Math.max(1, c / mm * 100)}%;background:${colorOf(m)}"></i></div></div>`).join('') || '<p class="muted small" style="margin-top:10px">Nothing yet.</p>'}</div></div></div></div>`;
}

// ---- settings ----
const seg = (id, opts, val) => `<div class="seg" role="group" id="${id}">${opts.map(([v, l]) => `<button data-v="${v}" class="${val === v ? 'on' : ''}" aria-pressed="${val === v}">${l}</button>`).join('')}</div>`;
const tog = (id, on, label) => `<button class="toggle ${on ? 'on' : ''}" id="${id}" role="switch" aria-checked="${on}" aria-label="${label}"></button>`;
function settingsView() {
  const p = palette(), keepY = $('.page')?.scrollTop || 0;
  const u = S.upd, h = S.health, sv = h?.version, srvNew = u?.ok && sv && u.latest && sv !== u.latest && h.latestNewer;
  view().innerHTML = `<div class="page"><div class="col fade"><div class="head"><h1 class="grow">Settings</h1><span class="status-slot">${statusPill()}</span></div>
  <div class="card stack"><div class="label">Appearance</div>
  <div class="setrow"><span class="grow">Colours</span>${seg('s-colors', [['clay', 'Warm clay'], ['system', 'Match my computer']], cfg.colors)}</div>
  ${cfg.colors === 'system' ? `<p class="tip">${p ? `Following ${esc(sys.source)} (${p.dark ? 'dark' : 'light'}). It changes with your theme.` : 'No theme colours found on this computer, so the warm clay colours are used.'} ${p ? `<span class="swatch"><i style="background:${p.vars['--bg']}"></i><i style="background:${p.vars['--card']}"></i><i style="background:${p.vars['--clay']}"></i></span>` : ''}</p>` : `<div class="setrow"><span class="grow">Light or dark</span>${seg('s-mode', [['system', 'Like my computer'], ['light', 'Light'], ['dark', 'Dark']], cfg.mode)}</div>`}
  <div class="setrow"><span class="grow">Text size</span><input type="range" id="s-size" min="13" max="19" step="1" value="${cfg.textSize}" aria-label="Text size"><span class="small muted" style="width:36px">${cfg.textSize}px</span></div>
  <div class="setrow"><span class="grow">Animations</span>${seg('s-motion', [['full', 'On'], ['reduced', 'Reduced']], cfg.motion)}</div></div>
  ${autoSettingsHtml()}
  <div class="card stack"><div class="label">Pop-ups on this computer</div><div class="setrow"><span class="grow">Show a pop-up for new results and messages while this window is in the background</span>${tog('s-notify', cfg.notify, 'Notify when an agent finishes')}</div></div>
  <div class="card stack"><div class="label">Window</div><div class="setrow"><span class="grow">Use the normal system window frame (turn this on if typing or clicking ever misbehaves)</span>${tog('s-frame', !!S.framed, 'Use the system window frame')}</div></div>
  <div class="card stack"><div class="label">Updates</div>
  <div class="setrow"><span class="grow">This app: <b>${esc(u?.current || '…')}</b>${u?.ok ? (u.available ? ` · version <b>${esc(u.latest)}</b> is ready` : ' · up to date') : u ? ` · ${esc(u.error)}` : ''}</span>
  ${u?.available ? `<button class="primary sm" id="s-upd" ${S.updBusy ? 'disabled' : ''}>${u.canInstall ? 'Update now' : 'Open release page'}</button>` : ''}<button class="ghost sm" id="s-check">Check now</button></div>
  <div class="setrow"><span class="grow">Check for updates automatically</span>${tog('s-auto', cfg.autoUpdate, 'Check for updates automatically')}</div>
  ${u?.available && u.notes ? `<p class="tip selectable" style="white-space:pre-wrap">${esc(u.notes.slice(0, 600))}</p>` : ''}</div>
  <div class="card stack"><div class="label">Server</div>
  <div class="setrow"><span class="grow ellip selectable">${esc(conn.url)}</span>${statusPill()}</div>
  <div class="setrow"><span class="grow">Server version: <b>${esc(sv || '…')}</b>${srvNew ? ` · <b>${esc(u.latest)}</b> is ready` : sv ? ' · up to date' : ''}</span>${srvNew ? '<button class="primary sm" id="s-srv">Update server</button>' : ''}</div>
  <p class="tip">Agents, memory and the Telegram bridge live on the server, so they keep working when this app is closed.</p>
  <div class="row"><button class="ghost" id="s-out">Disconnect</button></div></div>
  <div class="card stack"><div class="label">Shortcuts</div><p class="tip"><span class="kbd">Ctrl</span> <span class="kbd">N</span> new agent · <span class="kbd">Ctrl</span> <span class="kbd">K</span> search · <span class="kbd">Alt</span> <span class="kbd">1</span>/<span class="kbd">2</span>/<span class="kbd">3</span> Agents, Usage, Settings · <span class="kbd">Enter</span> send · <span class="kbd">Shift</span> <span class="kbd">Enter</span> new line · <span class="kbd">Esc</span> close</p></div>
  <p class="tip" style="text-align:center">Agents ${esc(u?.current || '')} · <a href="#" id="s-gh" style="color:var(--clay)">github.com/veelvoer/agents</a></p></div></div>`;
  $('.page').scrollTop = keepY;
  const setSeg = (id, key) => $$(`#${id} button`).forEach((b) => (b.onclick = () => { cfg[key] = b.dataset.v; saveCfg(); applyTheme(); settingsView(); }));
  setSeg('s-colors', 'colors'); setSeg('s-mode', 'mode'); setSeg('s-motion', 'motion');
  $('#s-size').oninput = (e) => { cfg.textSize = +e.target.value; saveCfg(); applyTheme(); }; $('#s-size').onchange = settingsView;
  $('#s-notify').onclick = async () => { cfg.notify = !cfg.notify; if (cfg.notify && Notification.permission === 'default') await Notification.requestPermission(); saveCfg(); settingsView(); };
  $('#s-frame').onclick = () => window.shell.setFrame(!S.framed);
  $('#s-auto').onclick = () => { cfg.autoUpdate = !cfg.autoUpdate; saveCfg(); settingsView(); };
  $('#s-check').onclick = () => checkUpdates(true);
  $('#s-upd')?.addEventListener('click', () => (S.upd.canInstall ? doUpdate() : window.shell.openExternal(S.upd.page)));
  $('#s-srv')?.addEventListener('click', () => confirmBox('Update the server?', 'The server restarts for a few seconds. Agents that are working at that moment stop.', 'Update', async () => { const r = await api('POST', '/admin/update'); toast(`Updating the server to ${r.to.replace('agents-server ', '')}…`); setTimeout(loadHealth, 6000); }));
  $('#s-out').onclick = () => { localStorage.removeItem('conn'); conn = null; running = false; ctl?.abort(); S.agents = null; S.rev = 0; connectView(); };
  bindAutoSettings();
  $('#s-gh').onclick = (e) => { e.preventDefault(); window.shell.openExternal('https://github.com/veelvoer/agents'); };
}
const newerV = (a, b) => { const x = a.split('.').map(Number), y = b.split('.').map(Number); for (let i = 0; i < 3; i++) if ((x[i] || 0) !== (y[i] || 0)) return (x[i] || 0) > (y[i] || 0); return false; };
async function loadHealth() { try { const h = await fetch(conn.url + '/health').then((r) => r.json()); S.health = { ...h, latestNewer: false }; if (S.upd?.ok) S.health.latestNewer = newerV(S.upd.latest, h.version); } catch {} if (S.tab === 'settings') settingsView(); }

// ---- keyboard ----
document.addEventListener('keydown', (e) => {
  if (!conn || !$('#app')) return;
  const mod = e.ctrlKey || e.metaKey;
  if (mod && e.key.toLowerCase() === 'n') { e.preventDefault(); editor(null); }
  else if (mod && e.key.toLowerCase() === 'k') { e.preventDefault(); if (S.tab !== 'agents') go('agents'); $('#q')?.focus(); $('#q')?.select(); }
  else if (mod && e.key === ',') { e.preventDefault(); go('settings'); }
  else if (e.altKey && ['1', '2', '3', '4'].includes(e.key)) { e.preventDefault(); go(['agents', 'activity', 'usage', 'settings'][+e.key - 1]); }
  else if (e.key === 'Escape') { const d = $('.scrim'); if (d) d.remove(); else if (document.activeElement === $('#q') && S.q) { S.q = ''; renderSide(); } }
});
document.addEventListener('visibilitychange', () => { if (document.hidden) ctl?.abort(); });
matchMedia('(prefers-color-scheme: dark)').addEventListener('change', applyTheme);

async function start() {
  S.agents = null; S.err = ''; S.rev = 0;
  try { S.models = await api('GET', '/models'); } catch { S.models = []; }
  mainShell(); running = true; loadList(); loadHealth(); refreshJobs(); fetchActivity();
  api('GET', '/settings').then(async (v) => { S.srv = v; S.paused = v.paused; if (v.tz === 'UTC' && BROWSER_TZ !== 'UTC' && !localStorage.getItem('tzAsked')) { localStorage.setItem('tzAsked', '1'); try { S.srv = await api('PUT', '/settings', { tz: BROWSER_TZ }); toast(`Time zone set to ${BROWSER_TZ}. Change it in Settings.`); } catch {} } }).catch(() => {});
  window.shell.version().then((v) => { S.upd ||= { current: v }; });
  cfg.autoUpdate && checkUpdates(false); clearInterval(start.t); start.t = setInterval(() => cfg.autoUpdate && checkUpdates(false), 6 * 3600e3);
}
if (conn) start(); else connectView();

// While a dialog is open nothing on the page may act as a window drag area: on Linux a drag area under a dialog can swallow clicks and keys.
new MutationObserver(() => document.documentElement.classList.toggle('modal', !!document.querySelector('.scrim'))).observe(document.body, { childList: true });
window.addEventListener('focus', () => { const d = document.querySelector('.scrim input, .scrim textarea'); if (d && !document.activeElement?.matches('input, textarea, select')) d.focus(); });

let jobsTimer; const refreshJobsSoon = () => { clearTimeout(jobsTimer); jobsTimer = setTimeout(refreshJobs, 300); };
