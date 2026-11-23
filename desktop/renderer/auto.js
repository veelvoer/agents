'use strict';
// Automations (tasks that run by themselves), the activity feed, and the settings for them.
// Loaded before app.js; everything here runs later, after app.js has defined $, S, api, icon, esc, toast …

const DAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
const BROWSER_TZ = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
const srvTz = () => (S.srv && S.srv.tz) || BROWSER_TZ;
const timeAgo = (t) => { if (!t) return ''; const s = Math.max(0, Date.now() / 1000 - t); if (s < 60) return 'just now'; if (s < 3600) return Math.floor(s / 60) + ' min ago'; if (s < 86400) return new Date(t * 1000).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }); return new Date(t * 1000).toLocaleDateString([], { day: 'numeric', month: 'short' }) + ' ' + new Date(t * 1000).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }); };

// ---- data ----
async function refreshJobs() { try { S.jobs = await api('GET', '/jobs'); } catch { /* shown as offline elsewhere */ } if (S.tab === 'auto') renderAuto(); }
async function refreshSettings() { try { S.srv = await api('GET', '/settings'); } catch {} if (S.tab === 'settings') settingsView(); }
async function fetchActivity() {
  try {
    const d = await api('GET', '/activity?limit=80'); const prev = S.actLast;
    S.acts = d.items; S.actLast = d.last;
    if (localStorage.getItem('seen') === null) { S.seen = d.last; localStorage.setItem('seen', String(d.last)); }
    if (prev && cfg.notify && !document.hasFocus()) for (const e of d.items.filter((x) => x.id > prev && x.kind !== 'system')) { try { new Notification(e.title || 'Agents', { body: e.body.slice(0, 200), tag: e.agent_id ? 'agent-' + e.agent_id : undefined }); } catch {} }
    if (S.tab === 'activity') { renderActivity(); markRead(); } else updateBadge();
  } catch {}
}
const unread = () => (S.acts || []).filter((e) => e.id > S.seen && e.kind !== 'system').length;
function updateBadge() { const b = $('#nav [data-tab=activity]'); if (!b) return; $('.badge', b)?.remove(); const n = unread(); if (n) b.insertAdjacentHTML('beforeend', `<span class="badge" aria-label="${n} new">${n > 9 ? '9+' : n}</span>`); }
function markRead() { if (S.actLast && S.seen !== S.actLast) { S.seen = S.actLast; localStorage.setItem('seen', String(S.seen)); updateBadge(); } }

// ---- schedule builder ----
function fromSchedule(sch) {
  const st = { type: 'daily', time: '08:00', dow: '1', every: 30, unit: 'minutes', at: '', cron: '' };
  if (!sch) return st;
  if (sch.kind === 'every') { const m = sch.minutes; st.type = 'every'; if (m % 1440 === 0) { st.every = m / 1440; st.unit = 'days'; } else if (m % 60 === 0) { st.every = m / 60; st.unit = 'hours'; } else st.every = m; return st; }
  if (sch.kind === 'once') { st.type = 'once'; const d = new Date(sch.at * 1000); d.setMinutes(d.getMinutes() - d.getTimezoneOffset()); st.at = d.toISOString().slice(0, 16); return st; }
  if (sch.kind === 'hook') { st.type = 'hook'; return st; }
  if (sch.kind === 'manual') { st.type = 'manual'; return st; }
  const f = (sch.expr || '').trim().split(/\s+/);
  if (f.length === 5 && f[2] === '*' && f[3] === '*' && /^\d+$/.test(f[0]) && /^\d+$/.test(f[1])) {
    st.time = `${f[1].padStart(2, '0')}:${f[0].padStart(2, '0')}`;
    if (f[4] === '*') { st.type = 'daily'; return st; } if (f[4] === '1-5') { st.type = 'weekdays'; return st; } if (f[4] === '0,6' || f[4] === '6,0') { st.type = 'weekends'; return st; }
    if (/^[0-7]$/.test(f[4])) { st.type = 'weekly'; st.dow = f[4] === '7' ? '0' : f[4]; return st; }
  }
  st.type = 'cron'; st.cron = sch.expr; return st;
}
function toSchedule(st) {
  const tz = srvTz(), [h, m] = (st.time || '08:00').split(':').map(Number), c = (d) => ({ kind: 'cron', expr: `${m} ${h} * * ${d}`, tz });
  switch (st.type) {
    case 'daily': return c('*'); case 'weekdays': return c('1-5'); case 'weekends': return c('0,6'); case 'weekly': return c(st.dow);
    case 'every': { const n = Math.max(1, +st.every || 1); return { kind: 'every', minutes: n * (st.unit === 'days' ? 1440 : st.unit === 'hours' ? 60 : 1) }; }
    case 'once': { const t = Date.parse(st.at); if (!t) throw new Error('Pick a date and time.'); if (t < Date.now()) throw new Error('That time is in the past.'); return { kind: 'once', at: Math.floor(t / 1000) }; }
    case 'hook': return { kind: 'hook' }; case 'manual': return { kind: 'manual' };
    default: if (!st.cron.trim()) throw new Error('Type a cron schedule, like 0 8 * * 1-5.'); return { kind: 'cron', expr: st.cron.trim(), tz };
  }
}

// ---- automations view ----
function autoView() {
  view().innerHTML = '<div class="page"><div class="col fade" style="max-width:900px" id="autocol"></div></div>';
  if (!S.jobs) refreshJobs(); renderAuto();
}
function renderAuto() {
  const col = $('#autocol'); if (!col) return; const jobs = S.jobs || [], paused = S.paused;
  const openIds = new Set($$('.job.open', col).map((e) => +e.dataset.id));
  col.innerHTML = `<div class="head"><h1 class="grow">Automations</h1><button class="primary" id="newjob">${icon('plus')}New task</button></div>
  <div class="card row ${paused ? 'warnbox' : ''}"><span class="grow">${paused ? '<b>Paused.</b> Nothing runs by itself until you turn it back on.' : 'Automations are running. Tasks start on time, even when this app is closed.'}</span>
  <button class="toggle ${paused ? '' : 'on'}" id="pausebtn" role="switch" aria-checked="${!paused}" aria-label="Run automations"></button></div>
  ${!S.jobs ? '<div class="empty"><p>Loading…</p></div>' : !jobs.length ? `<div class="empty">${icon('clock')}<h3>Nothing scheduled yet</h3><p class="small">A task is something an agent does by itself: every morning, every hour, or when a web link is called. It can message you when it is done.</p>
  <p class="small">Or just tell an agent in chat: <span class="kbd">every weekday at 8:00 tell me the weather</span></p><button class="primary" id="newjob2">${icon('plus')}New task</button></div>` : jobs.map(jobCard).join('')}`;
  const nj = () => jobEditor(null); $('#newjob').onclick = nj; $('#newjob2')?.addEventListener('click', nj);
  $('#pausebtn').onclick = async () => { try { S.srv = await api('PUT', '/settings', { paused: !S.paused }); S.paused = S.srv.paused; renderAuto(); toast(S.paused ? 'Automations paused' : 'Automations running'); } catch (e) { toast(e.message); } };
  $$('.job', col).forEach((el) => {
    const id = +el.dataset.id, j = jobs.find((x) => x.id === id); if (!j) return;
    if (openIds.has(id)) { el.classList.add('open'); fillHistory(el, j); }
    $('.jtoggle', el).onclick = async (e) => { e.stopPropagation(); try { await api('POST', `/jobs/${id}/enabled`, { enabled: !j.enabled }); refreshJobs(); } catch (er) { toast(er.message); } };
    $('.jrun', el).onclick = async (e) => { e.stopPropagation(); try { await api('POST', `/jobs/${id}/run`); toast('Started'); setTimeout(refreshJobs, 1500); } catch (er) { toast(er.message); } };
    $('.jedit', el).onclick = (e) => { e.stopPropagation(); jobEditor(j); };
    $('.jhead', el).onclick = () => { el.classList.toggle('open'); if (el.classList.contains('open')) fillHistory(el, j); };
    $('.jhead', el).onkeydown = (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); e.currentTarget.click(); } };
    $('.jcopy', el)?.addEventListener('click', async (e) => { e.stopPropagation(); await navigator.clipboard.writeText(`${conn.url}/hook/${j.hook}`); toast('Link copied'); });
  });
}
function jobCard(j) {
  const st = j.running ? 'running' : !j.enabled ? 'off' : j.last_status === 'error' ? 'err' : j.last_status === 'ok' ? 'ok' : '';
  const stateWord = j.running ? 'working' : !j.enabled ? 'off' : j.last_status === 'error' ? 'last run failed' : '';
  return `<div class="card job" data-id="${j.id}"><div class="jhead" role="button" tabindex="0" aria-expanded="false"><span class="dot ${st}"></span><div class="grow"><div class="row"><h3 class="ellip" style="flex:0 1 auto">${esc(j.name)}</h3><span class="chip sm">${esc(j.agent)}</span>${stateWord ? `<span class="small muted">${stateWord}</span>` : ''}</div>
  <div class="small muted">${esc(j.human)}${j.enabled && j.next ? ` · next ${esc(j.next)}` : ''}${j.last_run ? ` · last ${timeAgo(j.last_run)}` : ''}</div></div>
  <button class="toggle jtoggle ${j.enabled ? 'on' : ''}" role="switch" aria-checked="${j.enabled}" aria-label="${esc(j.name)} on or off"></button></div>
  <div class="jbody"><p class="small muted" style="white-space:pre-wrap">${esc(j.prompt)}</p>${j.last_text ? `<div class="label" style="margin-top:6px">Last result</div><p class="small selectable" style="white-space:pre-wrap">${md(j.last_text)}</p>` : ''}
  ${j.hook ? `<div class="small muted">Web link: <span class="kbd selectable">/hook/${esc(j.hook.slice(0, 8))}…</span> <button class="link jcopy">Copy link</button></div>` : ''}
  <div class="row wrap" style="margin-top:6px"><button class="ghost sm jrun">${icon('play')}Run now</button><button class="ghost sm jedit">${icon('edit')}Edit</button><span class="grow"></span><span class="small muted">${j.runs} run${j.runs === 1 ? '' : 's'} · $${j.cost.toFixed(3)}</span></div>
  <div class="hist"></div></div></div>`;
}
async function fillHistory(el, j) {
  try { const d = await api('GET', `/activity?job=${j.id}&limit=8`); const h = $('.hist', el); if (!h) return;
    h.innerHTML = d.items.length ? `<div class="label" style="margin:8px 0 4px">History</div>` + d.items.map((e) => `<div class="small hrow"><span class="dot ${e.kind === 'error' ? 'err' : e.kind === 'run' ? 'ok' : ''}"></span><span class="muted">${timeAgo(e.at)}</span><span class="clamp selectable">${esc(e.body)}</span></div>`).join('') : ''; } catch {}
}

function jobEditor(job) {
  const agents = S.agents || []; if (!agents.length) { toast('Create an agent first.'); return; }
  const st = fromSchedule(job?.schedule);
  const f = { name: job?.name || '', agent_id: job?.agent_id || (S.sel && byId(S.sel) ? S.sel : agents[0].id), prompt: job?.prompt || '', notify: job?.notify || 'always', fresh: job ? job.fresh : true, enabled: job ? job.enabled : true };
  const d = document.createElement('div'); d.className = 'scrim';
  const types = [['daily', 'Every day'], ['weekdays', 'Weekdays'], ['weekly', 'Weekly'], ['every', 'Every…'], ['once', 'Once'], ['hook', 'Web link'], ['manual', 'Manual'], ['cron', 'Cron']];
  const draw = () => {
    d.innerHTML = `<div class="dialog wide" role="dialog" aria-modal="true" aria-label="${job ? 'Edit task' : 'New task'}"><h2>${job ? 'Edit task' : 'New task'}</h2>
    <div class="field"><label class="label" for="jn">Name</label><input id="jn" placeholder="Morning news" value="${esc(f.name)}"></div>
    <div class="field"><label class="label" for="ja">Agent</label><select id="ja">${agents.map((a) => `<option value="${a.id}" ${a.id === f.agent_id ? 'selected' : ''}>${esc(a.spec.name)} · ${esc(modelName(a.spec.model))}</option>`).join('')}</select></div>
    <div class="field"><label class="label" for="jp">What should it do?</label><textarea id="jp" placeholder="Check the news about my topics and give me the three most important things.">${esc(f.prompt)}</textarea></div>
    <div class="field"><span class="label">When</span><div class="row wrap">${types.map(([v, l]) => `<button class="chip ${st.type === v ? 'on' : ''}" data-t="${v}" aria-pressed="${st.type === v}">${l}</button>`).join('')}</div>
    <div class="row wrap">${['daily', 'weekdays', 'weekends', 'weekly'].includes(st.type) ? `${st.type === 'weekly' ? `<select id="jd" style="width:auto">${DAYS.map((x, i) => `<option value="${i}" ${String(i) === st.dow ? 'selected' : ''}>${x}</option>`).join('')}</select>` : ''}<span class="small muted">at</span><input id="jt" type="time" value="${st.time}" style="width:auto">` : ''}
    ${st.type === 'every' ? `<span class="small muted">every</span><input id="je" type="number" min="1" value="${st.every}" style="width:90px"><select id="ju" style="width:auto">${['minutes', 'hours', 'days'].map((u) => `<option ${u === st.unit ? 'selected' : ''}>${u}</option>`).join('')}</select>` : ''}
    ${st.type === 'once' ? `<input id="jo" type="datetime-local" value="${st.at}" style="width:auto">` : ''}
    ${st.type === 'cron' ? `<input id="jc" placeholder="0 8 * * 1-5" value="${esc(st.cron)}">` : ''}
    ${st.type === 'hook' ? '<span class="small muted">You get a private web link. Anything that calls it (a phone shortcut, GitHub, another service) starts the task and hands its text to the agent.</span>' : ''}
    ${st.type === 'manual' ? '<span class="small muted">It only runs when you press Run now.</span>' : ''}</div>
    ${['daily', 'weekdays', 'weekends', 'weekly', 'cron'].includes(st.type) ? `<span class="small muted">Times use ${esc(srvTz())}. Change it in Settings.</span>` : ''}</div>
    <div class="setrow"><span class="grow">Message me the result</span>${seg('j-notify', [['always', 'Always'], ['errors', 'If it fails'], ['never', 'Never']], f.notify)}</div>
    <div class="setrow"><span class="grow">Session</span>${seg('j-fresh', [['1', 'Fresh each time'], ['0', 'Keep the chat']], f.fresh ? '1' : '0')}</div>
    <p class="tip" style="margin-top:-6px">${f.fresh ? 'Fresh each time is cheaper: the agent does not carry old conversation along.' : 'The agent continues its own chat, so it remembers earlier runs but uses more tokens over time.'}</p>
    <p id="e" class="err small" role="alert"></p>
    <div class="row">${job ? '<button class="ghost" id="jdel">Delete</button>' : ''}<span class="grow"></span><button class="ghost" id="jcancel">Cancel</button><button class="primary" id="jsave">Save</button></div></div>`;
    const keep = () => { f.name = $('#jn', d).value; f.agent_id = +$('#ja', d).value; f.prompt = $('#jp', d).value; if ($('#jt', d)) st.time = $('#jt', d).value; if ($('#jd', d)) st.dow = $('#jd', d).value; if ($('#je', d)) { st.every = +$('#je', d).value; st.unit = $('#ju', d).value; } if ($('#jo', d)) st.at = $('#jo', d).value; if ($('#jc', d)) st.cron = $('#jc', d).value; };
    $$('[data-t]', d).forEach((b) => (b.onclick = () => { keep(); st.type = b.dataset.t; draw(); }));
    $$('#j-notify button', d).forEach((b) => (b.onclick = () => { keep(); f.notify = b.dataset.v; draw(); }));
    $$('#j-fresh button', d).forEach((b) => (b.onclick = () => { keep(); f.fresh = b.dataset.v === '1'; draw(); }));
    $('#jcancel', d).onclick = () => d.remove();
    $('#jsave', d).onclick = async () => {
      keep(); const err = (m) => { $('#e', d).textContent = m; };
      if (!f.name.trim()) return err('Give the task a name.'); if (!f.prompt.trim()) return err('Say what it should do.');
      let schedule; try { schedule = toSchedule(st); } catch (e) { return err(e.message); }
      $('#jsave', d).disabled = true;
      try { const body = { agent_id: f.agent_id, name: f.name.trim(), prompt: f.prompt, schedule, enabled: f.enabled, fresh: f.fresh, notify: f.notify }; if (job) await api('PUT', `/jobs/${job.id}`, body); else await api('POST', '/jobs', body); d.remove(); toast('Saved'); refreshJobs(); }
      catch (e) { err(`Couldn't save: ${e.message}`); $('#jsave', d).disabled = false; }
    };
    $('#jdel', d)?.addEventListener('click', () => { d.remove(); confirmBox(`Delete ${job.name}?`, 'The task stops and is removed. Its past results stay in Activity.', 'Delete', async () => { await api('DELETE', `/jobs/${job.id}`); refreshJobs(); }); });
    $('#jn', d).focus();
  };
  d.onclick = (e) => e.target === d && d.remove(); d.onkeydown = (e) => e.key === 'Escape' && d.remove();
  draw(); document.body.appendChild(d);
}

// ---- activity view ----
function activityView() {
  view().innerHTML = '<div class="page"><div class="col fade" style="max-width:820px" id="actcol"></div></div>';
  fetchActivity(); renderActivity(); setTimeout(markRead, 800);
}
function renderActivity() {
  const col = $('#actcol'); if (!col) return; const flt = S.actFilter || 'all';
  const keep = (e) => flt === 'all' || (flt === 'results' && e.kind === 'run') || (flt === 'messages' && e.kind === 'notify') || (flt === 'problems' && (e.kind === 'error' || e.kind === 'system'));
  const items = (S.acts || []).filter(keep); let lastDay = '';
  const dayName = (t) => { const d = new Date(t * 1000), n = new Date(); const diff = Math.floor((new Date(n.getFullYear(), n.getMonth(), n.getDate()) - new Date(d.getFullYear(), d.getMonth(), d.getDate())) / 864e5); return diff === 0 ? 'Today' : diff === 1 ? 'Yesterday' : d.toLocaleDateString([], { weekday: 'long', day: 'numeric', month: 'long' }); };
  const ic = { run: 'zap', notify: 'bell', error: 'alert', system: 'set' };
  col.innerHTML = `<div class="head"><h1 class="grow">Activity</h1><span class="status-slot">${statusPill()}</span></div>
  <div>${seg('a-filter', [['all', 'Everything'], ['results', 'Results'], ['messages', 'Messages'], ['problems', 'Problems']], flt)}</div>
  ${!items.length ? `<div class="empty">${icon('bell')}<h3>Nothing here yet</h3><p class="small">When a task runs or an agent messages you, it shows up here.</p></div>` : items.map((e) => { const day = dayName(e.at); const head = day !== lastDay ? `<div class="sec">${esc(day)}</div>` : ''; lastDay = day;
    return `${head}<div class="card act ${e.kind} ${e.id > S.seen && e.kind !== 'system' ? 'new' : ''}" tabindex="0" role="button" aria-expanded="false"><div class="row" style="align-items:flex-start"><span class="aic">${icon(ic[e.kind] || 'bot')}</span><div class="grow"><div class="row"><b class="grow ellip">${esc(e.title)}</b><span class="small muted">${new Date(e.at * 1000).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</span></div><div class="small clamp selectable" style="white-space:pre-wrap">${md(e.body)}</div></div></div></div>`; }).join('')}`;
  $$('#a-filter button', col).forEach((b) => (b.onclick = () => { S.actFilter = b.dataset.v; renderActivity(); }));
  $$('.act', col).forEach((el) => { const t = () => { const o = el.classList.toggle('open'); el.setAttribute('aria-expanded', o); }; el.onclick = (e) => { if (!window.getSelection().toString()) t(); }; el.onkeydown = (e) => { if (e.key === 'Enter') t(); }; });
}

// ---- settings: automations + messages ----
function autoSettingsHtml() {
  const v = S.srv; if (!v) return '<div class="card"><p class="muted small">Loading server settings…</p></div>';
  const tg = v.telegram;
  return `<div class="card stack"><div class="label">Automations</div>
  <div class="setrow"><span class="grow">Run automations</span><button class="toggle ${v.paused ? '' : 'on'}" id="x-run" role="switch" aria-checked="${!v.paused}" aria-label="Run automations"></button></div>
  <div class="setrow"><span class="grow">At the same time, at most</span>${seg('x-par', [['1', '1'], ['2', '2'], ['3', '3'], ['4', '4']], String(v.max_parallel))}</div>
  <p class="tip" style="margin-top:-6px">Each run uses your Claude subscription. A lower number keeps usage calm.</p>
  <div class="setrow"><span class="grow">Time zone</span><input id="x-tz" list="x-tzl" value="${esc(v.tz)}" style="width:220px"><datalist id="x-tzl">${(Intl.supportedValuesOf ? Intl.supportedValuesOf('timeZone') : []).map((z) => `<option>${z}</option>`).join('')}</datalist>${v.tz !== BROWSER_TZ ? `<button class="ghost sm" id="x-tzset">Use ${esc(BROWSER_TZ)}</button>` : ''}</div>
  <div class="field"><label class="label" for="x-mem">Shared memory (every agent knows this)</label><textarea id="x-mem" placeholder="Facts for all agents, one per line. Agents can add to it themselves.">${esc(v.global_memory)}</textarea><div class="row"><span class="small muted grow">${v.global_memory.length}/6000</span><button class="ghost sm" id="x-memsave">Save memory</button></div></div></div>
  <div class="card stack"><div class="label">Message me</div><p class="tip">Where your agents send their messages. The app inbox is always on. Add the ones you want.</p>
  <div class="field"><span class="small"><b>Telegram</b></span>${tg.paired ? `<div class="row"><span class="pill ok grow" style="justify-content:flex-start"><i></i>Linked. You can also chat with your agents there.</span><button class="ghost sm" id="x-tgun">Unlink</button></div>`
    : tg.token_set ? `<p class="small">Open your bot in Telegram and send this message:</p><div class="row"><span class="kbd selectable" style="font-size:15px;padding:6px 10px">/pair ${esc(tg.pair_code || '…')}</span><button class="ghost sm" id="x-tgcode">New code</button><button class="ghost sm" id="x-tgrm">Remove bot</button></div><p class="tip">Waiting for you… this page updates by itself.</p>`
    : `<p class="tip">Create a bot with @BotFather in Telegram, paste its token here, then link your chat.</p><div class="row"><input id="x-tgtok" placeholder="123456:ABC-DEF…" type="password" autocomplete="off"><button class="primary sm" id="x-tgsave">Connect</button></div>`}</div>
  <div class="field"><span class="small"><b>Phone notifications with ntfy</b></span><div class="row wrap"><input id="x-ntfysrv" placeholder="https://ntfy.sh" value="${esc(v.ntfy.server)}" style="flex:1;min-width:150px"><input id="x-ntfytopic" placeholder="a-secret-topic-name" value="${esc(v.ntfy.topic)}" style="flex:1;min-width:150px"></div><p class="tip">Install the free ntfy app and subscribe to the same topic. Pick a long random topic name: anyone who knows it can read your messages.</p></div>
  <div class="field"><span class="small"><b>Web hook</b></span><input id="x-hook" type="password" placeholder="${v.webhook.set ? 'Saved. Type to replace, or clear to remove.' : 'https://example.com/my-hook'}" autocomplete="off"><p class="tip">Gets a JSON message {title, body}. Use it for Slack, Discord, Home Assistant and more.</p></div>
  <div class="row"><button class="ghost" id="x-save">Save</button><button class="primary" id="x-test">Send a test message</button></div></div>`;
}
function bindAutoSettings() {
  const put = async (body, msg) => { try { S.srv = await api('PUT', '/settings', body); S.paused = S.srv.paused; if (msg) toast(msg); settingsView(); } catch (e) { toast(e.message); } };
  const q = (s) => $(s);
  q('#x-run') && (q('#x-run').onclick = () => put({ paused: !S.srv.paused }, S.srv.paused ? 'Automations running' : 'Automations paused'));
  $$('#x-par button').forEach((b) => (b.onclick = () => put({ max_parallel: +b.dataset.v }, 'Saved')));
  q('#x-tz') && (q('#x-tz').onchange = (e) => put({ tz: e.target.value }, 'Time zone saved'));
  q('#x-tzset') && (q('#x-tzset').onclick = () => put({ tz: BROWSER_TZ }, 'Time zone saved'));
  q('#x-memsave') && (q('#x-memsave').onclick = () => put({ global_memory: q('#x-mem').value }, 'Memory saved'));
  q('#x-tgsave') && (q('#x-tgsave').onclick = async () => { const t = q('#x-tgtok').value.trim(); if (!t) return; try { await api('PUT', '/settings', { telegram_token: t }); await api('POST', '/settings/telegram/pair'); await refreshSettings(); } catch (e) { toast(e.message); } });
  q('#x-tgcode') && (q('#x-tgcode').onclick = async () => { try { await api('POST', '/settings/telegram/pair'); await refreshSettings(); } catch (e) { toast(e.message); } });
  q('#x-tgrm') && (q('#x-tgrm').onclick = () => put({ telegram_token: '' }, 'Bot removed'));
  q('#x-tgun') && (q('#x-tgun').onclick = async () => { try { await api('DELETE', '/settings/telegram'); await refreshSettings(); } catch (e) { toast(e.message); } });
  q('#x-save') && (q('#x-save').onclick = () => { const b = { ntfy_server: q('#x-ntfysrv').value, ntfy_topic: q('#x-ntfytopic').value }; const h = q('#x-hook').value; if (h || (S.srv.webhook.set && q('#x-hook').dataset.touched)) b.webhook_url = h; put(b, 'Saved'); });
  q('#x-hook') && (q('#x-hook').oninput = (e) => { e.target.dataset.touched = '1'; });
  q('#x-test') && (q('#x-test').onclick = async () => { try { const r = await api('POST', '/settings/test'); toast(r.ok ? 'Test message sent (check your channels)' : 'Problem: ' + r.errors.join('; ')); } catch (e) { toast(e.message); } });
}
