'use strict';
// Automations (tasks that run by themselves), the activity feed, and the settings for them.
// Loaded before app.js; everything here runs later, after app.js has defined $, S, api, icon, esc, toast …

const DAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
const BROWSER_TZ = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
const srvTz = () => (S.srv && S.srv.tz) || BROWSER_TZ;
const timeAgo = (t) => { if (!t) return ''; const s = Math.max(0, Date.now() / 1000 - t); if (s < 60) return 'just now'; if (s < 3600) return Math.floor(s / 60) + ' min ago'; if (s < 86400) return new Date(t * 1000).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }); return new Date(t * 1000).toLocaleDateString([], { day: 'numeric', month: 'short' }) + ' ' + new Date(t * 1000).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }); };

// ---- data ----
async function refreshJobs() { try { S.jobs = await api('GET', '/jobs'); } catch { /* shown as offline elsewhere */ } if (S.tab === 'agents') { renderSide(); updateHead(); } }
async function refreshSettings() { try { S.srv = await api('GET', '/settings'); } catch {} if (S.tab === 'settings') settingsView(); }
async function fetchActivity() {
  try {
    const d = await api('GET', '/activity?limit=80'); const prev = S.actLast;
    S.acts = d.items; S.actLast = d.last;
    if (localStorage.getItem('seen') === null) { S.seen = d.last; localStorage.setItem('seen', String(d.last)); }
    if (prev && cfg.notify && !document.hasFocus()) for (const e of d.items.filter((x) => x.id > prev && x.kind !== 'system' && x.kind !== 'thought')) { try { new Notification(e.title || 'Agents', { body: e.body.slice(0, 200), tag: e.agent_id ? 'agent-' + e.agent_id : undefined }); } catch {} }
    if (S.tab === 'activity') { renderActivity(); markRead(); } else updateBadge();
  } catch {}
}
const unread = () => (S.acts || []).filter((e) => e.id > S.seen && e.kind !== 'system' && e.kind !== 'thought').length;
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

// ---- repeat: an agent that runs by itself ----
const jobsOf = (id) => (S.jobs || []).filter((j) => j.agent_id === id);
const repeatInfo = (agent) => { const l = jobsOf(agent.id), j = l.find((x) => x.enabled) || l[0]; if (!j) return null; return { job: j, text: j.enabled ? `${j.human}${j.next ? ' · next ' + j.next : ''}` : `${j.human} (off)`, on: j.enabled }; };

function repeatDialog(agent, jobId) {
  const all = jobsOf(agent.id);
  const job = jobId === 0 ? null : (all.find((j) => j.id === jobId) || all.find((j) => j.enabled) || all[0] || null);
  const st = fromSchedule(job?.schedule);
  const f = { whenText: '', parsed: null, parsedFor: '', prompt: job?.prompt || '', notes: job?.notes || '', notify: job?.notify || 'always', fresh: job ? job.fresh : true, enabled: job ? job.enabled : true };
  const d = document.createElement('div'); d.className = 'scrim';
  const types = [['daily', 'Every day'], ['weekdays', 'Weekdays'], ['weekly', 'Weekly'], ['every', 'Every…'], ['once', 'Once'], ['hook', 'Web link'], ['manual', 'Manual'], ['cron', 'Cron']];
  const draw = () => {
    d.innerHTML = `<div class="dialog wide" role="dialog" aria-modal="true" aria-label="Repeat ${esc(agent.spec.name)}"><h2>Repeat ${esc(agent.spec.name)}</h2>
    <p class="tip" style="margin-top:-8px">The agent does this by itself on the server, even when this app is closed, and tells you the result.</p>
    ${all.length > 1 || (all.length && !job) ? `<div class="row wrap">${all.map((j, n) => `<button class="chip ${job && j.id === job.id ? 'on' : ''}" data-pick="${j.id}">${esc(j.name === agent.spec.name ? 'Repeat ' + (n + 1) : j.name)}</button>`).join('')}<button class="chip ${!job ? 'on' : ''}" data-pick="0">+ Another</button></div>` : ''}
    <div class="setrow"><span class="grow"><b>${f.enabled ? 'Repeating' : 'Not repeating'}</b></span><button class="toggle ${f.enabled ? 'on' : ''}" id="r-en" role="switch" aria-checked="${f.enabled}" aria-label="Repeating"></button></div>
    <div class="field"><label class="label" for="jp">Message to send each time (optional)</label><textarea id="jp" placeholder="${agent.spec.prompt.trim() ? 'Leave empty: the agent just follows its own instructions.' : 'For example: Check the news about my topics and give me the three most important things.'}">${esc(f.prompt)}</textarea>
    <p class="tip">${agent.spec.prompt.trim() ? 'This agent already has instructions, so you can leave this empty and only choose when.' : 'This agent has no instructions yet, so say here what it should do each time.'}</p></div>
    <div class="field"><label class="label" for="jw">When: say it in your own words</label>
      <textarea id="jw" style="min-height:64px" placeholder="For example: every Monday and Thursday at 7:30, and on the 1st of each month at 9. Or: weekdays at 8:15, but not in August.">${esc(f.whenText)}</textarea>
      <div class="row wrap"><button class="ghost sm" id="jw-go">Understand</button>${agent.spec.prompt.trim() && TIMING_WORDS.test(agent.spec.prompt) ? '<button class="link" id="jw-ins">Read it from the agent\'s instructions</button>' : ''}${job ? `<span class="small muted">Now: ${esc(job.human)}</span>` : ''}</div>
      ${f.parsed ? `<div class="card" style="padding:10px 14px"><div class="small"><b>Understood:</b> ${esc(f.parsed.human)}</div><div class="small muted">Next: ${f.parsed.next.map(esc).join(' · ')} <span>(${esc(f.parsed.tz)})</span></div></div>` : ''}</div>
    <div class="field"><span class="label">${f.parsed ? 'Or pick' : 'Or pick quickly'}</span><div class="row wrap">${types.map(([v, l]) => `<button class="chip ${st.type === v && !f.parsed ? 'on' : ''}" data-t="${v}" aria-pressed="${st.type === v && !f.parsed}">${l}</button>`).join('')}</div>
    <div class="row wrap">${['daily', 'weekdays', 'weekends', 'weekly'].includes(st.type) ? `${st.type === 'weekly' ? `<select id="jd" style="width:auto">${DAYS.map((x, i) => `<option value="${i}" ${String(i) === st.dow ? 'selected' : ''}>${x}</option>`).join('')}</select>` : ''}<span class="small muted">at</span><input id="jt" type="time" value="${st.time}" style="width:auto">` : ''}
    ${st.type === 'every' ? `<span class="small muted">every</span><input id="je" type="number" min="1" value="${st.every}" style="width:90px"><select id="ju" style="width:auto">${['minutes', 'hours', 'days'].map((u) => `<option ${u === st.unit ? 'selected' : ''}>${u}</option>`).join('')}</select>` : ''}
    ${st.type === 'once' ? `<input id="jo" type="datetime-local" value="${st.at}" style="width:auto">` : ''}
    ${st.type === 'cron' ? `<input id="jc" placeholder="0 8 * * 1-5" value="${esc(st.cron)}">` : ''}
    ${st.type === 'hook' ? '<span class="small muted">You get a private web link. Anything that calls it (a phone shortcut, GitHub, another service) starts the agent and hands its text to it.</span>' : ''}
    ${st.type === 'manual' ? '<span class="small muted">It only runs when you press Run now.</span>' : ''}</div>
    ${['daily', 'weekdays', 'weekends', 'weekly', 'cron'].includes(st.type) ? `<span class="small muted">Times use ${esc(srvTz())}. Change it in Settings.</span>` : ''}</div>
    <div class="setrow"><span class="grow">Message me the result</span>${seg('j-notify', [['always', 'Always'], ['errors', 'If it fails'], ['never', 'Never']], f.notify)}</div>
    <div class="setrow"><span class="grow">Memory between runs</span>${seg('j-fresh', [['1', 'Remember progress'], ['0', 'Keep the whole chat']], f.fresh ? '1' : '0')}</div>
    <p class="tip" style="margin-top:-6px">${f.fresh ? 'Each run starts fresh (cheap), but the agent keeps short notes for itself. A series like daily lessons never repeats itself and keeps building.' : 'It continues this agent\'s own chat, so it remembers everything but uses more tokens over time.'}</p>
    ${f.fresh && job ? `<div class="field"><label class="label" for="jn">What it remembers (it writes this itself, you can edit)</label><textarea id="jn" placeholder="Nothing yet. It fills in after the first run.">${esc(f.notes)}</textarea></div>` : ''}
    ${job && job.hook ? `<div class="small muted">Web link: <span class="kbd selectable">/hook/${esc(job.hook.slice(0, 8))}…</span> <button class="link" id="jcopy">Copy link</button></div>` : ''}
    <p id="e" class="err small" role="alert"></p>
    <div class="row wrap">${job ? '<button class="ghost" id="jdel">Stop repeating</button><button class="ghost" id="jrun">Run now</button>' : ''}<span class="grow"></span><button class="ghost" id="jcancel">Cancel</button><button class="primary" id="jsave">Save</button></div></div>`;
    const keep = () => { f.whenText = $('#jw', d).value; if (f.parsed && f.parsedFor !== f.whenText.trim()) f.parsed = null; f.prompt = $('#jp', d).value; if ($('#jn', d)) f.notes = $('#jn', d).value; if ($('#jt', d)) st.time = $('#jt', d).value; if ($('#jd', d)) st.dow = $('#jd', d).value; if ($('#je', d)) { st.every = +$('#je', d).value; st.unit = $('#ju', d).value; } if ($('#jo', d)) st.at = $('#jo', d).value; if ($('#jc', d)) st.cron = $('#jc', d).value; };
    $$('[data-t]', d).forEach((b) => (b.onclick = () => { keep(); st.type = b.dataset.t; f.parsed = null; f.whenText = ''; draw(); }));
    const understand = async (text) => { const e = $('#e', d); e.textContent = ''; const b = $('#jw-go', d); b.disabled = true; b.textContent = 'Reading…'; try { f.parsed = await api('POST', '/schedule/parse', { text }); f.parsedFor = f.whenText.trim(); } catch (er) { f.parsed = null; draw(); $('#e', d).textContent = er.message; return; } draw(); };
    $('#jw-go', d).onclick = () => { keep(); if (!f.whenText.trim()) { $('#e', d).textContent = 'Write when it should run, for example: every Monday and Thursday at 7:30.'; return; } understand(f.whenText); };
    $('#jw-ins', d)?.addEventListener('click', () => { keep(); f.whenText = agent.spec.prompt; understand(agent.spec.prompt); });
    $$('[data-pick]', d).forEach((b) => (b.onclick = () => { d.remove(); repeatDialog(agent, +b.dataset.pick); }));
    $('#r-en', d).onclick = () => { keep(); f.enabled = !f.enabled; draw(); };
    $$('#j-notify button', d).forEach((b) => (b.onclick = () => { keep(); f.notify = b.dataset.v; draw(); }));
    $$('#j-fresh button', d).forEach((b) => (b.onclick = () => { keep(); f.fresh = b.dataset.v === '1'; draw(); }));
    $('#jcancel', d).onclick = () => d.remove();
    $('#jcopy', d)?.addEventListener('click', async () => { await navigator.clipboard.writeText(`${conn.url}/hook/${job.hook}`); toast('Link copied'); });
    $('#jrun', d)?.addEventListener('click', async () => { try { await api('POST', `/jobs/${job.id}/run`); toast('Started'); d.remove(); } catch (e) { $('#e', d).textContent = e.message; } });
    $('#jsave', d).onclick = async () => {
      keep(); const err = (m) => { $('#e', d).textContent = m; };
      if (!f.prompt.trim() && !agent.spec.prompt.trim()) return err('Say what it should do each time, or give the agent instructions first.');
      let schedule;
      if (f.parsed && f.parsedFor === f.whenText.trim()) schedule = f.parsed.schedule;
      else if (f.whenText.trim()) { $('#jsave', d).disabled = true; $('#jsave', d).textContent = 'Reading…'; try { schedule = (await api('POST', '/schedule/parse', { text: f.whenText })).schedule; } catch (e) { $('#jsave', d).disabled = false; $('#jsave', d).textContent = 'Save'; return err(e.message); } }
      else { try { schedule = toSchedule(st); } catch (e) { return err(e.message); } }
      $('#jsave', d).disabled = true;
      try { const body = { agent_id: agent.id, name: job?.name || agent.spec.name, prompt: f.prompt, schedule, enabled: f.enabled, fresh: f.fresh, notify: f.notify }; if (job) body.notes = f.notes; if (job) await api('PUT', `/jobs/${job.id}`, body); else await api('POST', '/jobs', body); d.remove(); toast(f.enabled ? 'Repeating' : 'Saved'); refreshJobs(); }
      catch (e) { err(`Couldn't save: ${e.message}`); $('#jsave', d).disabled = false; }
    };
    $('#jdel', d)?.addEventListener('click', () => { d.remove(); confirmBox(`Stop repeating ${agent.spec.name}?`, 'It will no longer run by itself. Past results stay in Activity.', 'Stop repeating', async () => { await api('DELETE', `/jobs/${job.id}`); refreshJobs(); }); });
    ($('#jp', d)).focus();
  };
  d.onclick = (e) => { if (e.target === d) d.remove(); }; d.onkeydown = (e) => { if (e.key === 'Escape') d.remove(); };
  draw(); document.body.appendChild(d); $('#jp', d)?.focus();
}


// After saving an agent whose instructions mention timing: offer to make it repeat, reading the timing from those instructions.
async function offerRepeat(agent) {
  if (agent.spec.autonomous || !TIMING_WORDS.test(agent.spec.prompt) || jobsOf(agent.id).length) return;
  const d = document.createElement('div'); d.className = 'scrim';
  d.innerHTML = `<div class="dialog" role="dialog" aria-modal="true" aria-label="Make it repeat"><h3>Make ${esc(agent.spec.name)} repeat?</h3><p class="muted small">The instructions mention timing. I can read it and set up the repeat for you.</p><p id="o-res" class="small"></p><p id="e" class="err small" role="alert"></p><div class="row" style="justify-content:flex-end"><button class="ghost" id="o-no">No thanks</button><button class="primary" id="o-yes">Read the timing</button></div></div>`;
  d.onclick = (e) => { if (e.target === d) d.remove(); }; d.onkeydown = (e) => { if (e.key === 'Escape') d.remove(); };
  document.body.appendChild(d); $('#o-no', d).onclick = () => d.remove(); $('#o-yes', d).focus();
  let parsed = null;
  $('#o-yes', d).onclick = async () => {
    const b = $('#o-yes', d);
    if (!parsed) { b.disabled = true; b.textContent = 'Reading…'; try { parsed = await api('POST', '/schedule/parse', { text: agent.spec.prompt }); } catch (e) { $('#e', d).textContent = e.message + ' You can set it up with the Repeat button instead.'; b.disabled = false; b.textContent = 'Read the timing'; return; }
      $('#o-res', d).innerHTML = `<b>Understood:</b> ${esc(parsed.human)}<br><span class="muted">Next: ${parsed.next.slice(0, 4).map(esc).join(' · ')}</span>`; b.disabled = false; b.textContent = 'Start repeating'; return; }
    b.disabled = true;
    try { await api('POST', '/jobs', { agent_id: agent.id, name: agent.spec.name, prompt: '', schedule: parsed.schedule, notify: 'always', fresh: true }); d.remove(); toast('Repeating'); refreshJobs(); } catch (e) { $('#e', d).textContent = e.message; b.disabled = false; }
  };
}

// ---- life: an agent that lives on its own ----
const TIMING_WORDS = /\b(every (day|morning|evening|night|hour|week|monday|tuesday|wednesday|thursday|friday|saturday|sunday)|each (day|morning|evening|week)|daily|weekly|hourly|always|forever|24\/7|all the time|keep (on )?(working|going|developing)|live online)\b/i;
const wakeText = (t) => { if (!t) return ''; const d = new Date(t * 1000), n = new Date(); const sameDay = d.toDateString() === n.toDateString(); return (sameDay ? 'today ' : d.toLocaleDateString([], { weekday: 'short', day: 'numeric', month: 'short' }) + ' ') + d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }); };
const specOf = (agent, patch) => ({ ...agent.spec, mcp_config: agent.spec.mcp_config || null, workdir: agent.spec.workdir || null, ...patch });

function lifeDialog(agent) {
  const sp = agent.spec, alive = !!sp.autonomous;
  const d = document.createElement('div'); d.className = 'scrim';
  d.innerHTML = `<div class="dialog wide" role="dialog" aria-modal="true" aria-label="Life of ${esc(sp.name)}"><h2>${esc(sp.name)} ${alive ? 'is alive' : 'is not alive'}</h2>
  <p class="tip" style="margin-top:-8px">${alive ? 'It wakes up by itself, thinks about its purpose (its instructions), does things with its tools, writes in its journal, and decides when to wake next. It is not waiting for you.' : 'Turn this on and the agent lives on the server by itself: it decides what to do and when, keeps working for days, and tells you only when it matters.'}</p>
  <div class="setrow"><span class="grow"><b>Lives on its own</b></span><button class="toggle ${alive ? 'on' : ''}" id="l-on" role="switch" aria-checked="${alive}" aria-label="Lives on its own"></button></div>
  ${alive ? `<div class="card" style="padding:12px 14px"><div class="small">${agent.status === 'running' ? 'Awake right now.' : agent.next_wake ? `Next wake-up: <b>${esc(wakeText(agent.next_wake))}</b>${agent.wake_reason ? ` · ${esc(agent.wake_reason)}` : ''}` : 'Waking up soon.'}</div><div class="small muted">${agent.wakes} wake-up${agent.wakes === 1 ? '' : 's'} so far${agent.last_wake ? ' · last ' + ago(agent.last_wake) : ''}</div></div>
  <div class="field"><label class="label" for="l-j">Its journal (its own memory, it writes this itself, you can edit)</label><textarea id="l-j" style="min-height:140px" placeholder="Empty: it writes here after its first wake-up.">${esc(agent.journal || '')}</textarea></div>
  <div class="row wrap"><div class="field grow"><label class="label" for="l-min">Wake at most every (minutes)</label><input id="l-min" type="number" min="1" value="${sp.wake_min ?? 15}"></div><div class="field grow"><label class="label" for="l-max">Most wake-ups per day</label><input id="l-max" type="number" min="1" max="200" value="${sp.max_wakes ?? 24}"></div></div>
  <p class="tip">Every wake-up uses your Claude subscription. Fewer wake-ups and a daily cost limit (in the agent's settings) keep usage calm. What it may do on the server is set by the agent's access level (now: ${{ safe: 'look only', standard: 'edit files and browse', full: 'everything' }[sp.autonomy || 'safe']}). To let it run programs, such as tests or scripts, set its access to Everything.</p>` : `<p class="tip">Tip: write its purpose in the Instructions, for example <i>Explain something new about quantum computers every morning, starting from the basics</i>. It works out the schedule by itself.</p>`}
  <p id="e" class="err small" role="alert"></p>
  <div class="row wrap">${alive ? '<button class="ghost" id="l-now">Wake up now</button>' : ''}<span class="grow"></span><button class="ghost" id="l-cancel">Close</button>${alive ? '<button class="primary" id="l-save">Save</button>' : ''}</div></div>`;
  d.onclick = (e) => { if (e.target === d) d.remove(); }; d.onkeydown = (e) => { if (e.key === 'Escape') d.remove(); };
  document.body.appendChild(d);
  $('#l-cancel', d).onclick = () => d.remove();
  $('#l-on', d).onclick = async () => {
    const on = !alive; const patch = { autonomous: on }; if (on && (sp.autonomy || 'safe') === 'safe') patch.autonomy = 'standard';
    try { await api('PUT', `/agents/${agent.id}`, specOf(agent, patch)); d.remove(); toast(on ? `${sp.name} is alive now` : `${sp.name} stopped living on its own`); } catch (e) { $('#e', d).textContent = e.message; }
  };
  $('#l-now', d)?.addEventListener('click', async () => { try { await api('POST', `/agents/${agent.id}/wake-now`); toast('Waking up…'); d.remove(); } catch (e) { $('#e', d).textContent = e.message; } });
  $('#l-save', d)?.addEventListener('click', async () => {
    try {
      await api('POST', `/agents/${agent.id}/journal`, { text: $('#l-j', d).value });
      await api('PUT', `/agents/${agent.id}`, specOf(agent, { wake_min: Math.max(1, +$('#l-min', d).value || 15), max_wakes: Math.min(200, Math.max(1, +$('#l-max', d).value || 24)) }));
      d.remove(); toast('Saved');
    } catch (e) { $('#e', d).textContent = e.message; }
  });
  $('#l-j', d)?.focus();
}

// ---- activity view ----
function activityView() {
  view().innerHTML = '<div class="page"><div class="col fade" style="max-width:820px" id="actcol"></div></div>';
  fetchActivity(); renderActivity(); setTimeout(markRead, 800);
}
function renderActivity() {
  const col = $('#actcol'); if (!col) return; const flt = S.actFilter || 'all';
  const keep = (e) => flt === 'all' || (flt === 'results' && e.kind === 'run') || (flt === 'messages' && e.kind === 'notify') || (flt === 'thoughts' && e.kind === 'thought') || (flt === 'problems' && (e.kind === 'error' || e.kind === 'system'));
  const items = (S.acts || []).filter(keep); let lastDay = '';
  const dayName = (t) => { const d = new Date(t * 1000), n = new Date(); const diff = Math.floor((new Date(n.getFullYear(), n.getMonth(), n.getDate()) - new Date(d.getFullYear(), d.getMonth(), d.getDate())) / 864e5); return diff === 0 ? 'Today' : diff === 1 ? 'Yesterday' : d.toLocaleDateString([], { weekday: 'long', day: 'numeric', month: 'long' }); };
  const ic = { run: 'zap', notify: 'bell', error: 'alert', system: 'set', thought: 'sparkle' };
  col.innerHTML = `<div class="head"><h1 class="grow">Activity</h1><span class="status-slot">${statusPill()}</span></div>
  <div>${seg('a-filter', [['all', 'Everything'], ['results', 'Results'], ['messages', 'Messages'], ['thoughts', 'Thoughts'], ['problems', 'Problems']], flt)}</div>
  ${!items.length ? `<div class="empty">${icon('bell')}<h3>Nothing here yet</h3><p class="small">When a task runs or an agent messages you, it shows up here.</p></div>` : items.map((e) => { const day = dayName(e.at); const head = day !== lastDay ? `<div class="sec">${esc(day)}</div>` : ''; lastDay = day;
    return `${head}<div class="card act ${e.kind} ${e.id > S.seen && e.kind !== 'system' && e.kind !== 'thought' ? 'new' : ''}" tabindex="0" role="button" aria-expanded="false"><div class="row" style="align-items:flex-start"><span class="aic">${icon(ic[e.kind] || 'bot')}</span><div class="grow"><div class="row"><b class="grow ellip">${esc(e.title)}</b><span class="small muted">${new Date(e.at * 1000).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</span></div><div class="small clamp selectable" style="white-space:pre-wrap">${md(e.body)}</div></div></div></div>`; }).join('')}`;
  $$('#a-filter button', col).forEach((b) => (b.onclick = () => { S.actFilter = b.dataset.v; renderActivity(); }));
  $$('.act', col).forEach((el) => { const t = () => { const o = el.classList.toggle('open'); el.setAttribute('aria-expanded', o); }; el.onclick = (e) => { if (!window.getSelection().toString()) t(); }; el.onkeydown = (e) => { if (e.key === 'Enter') t(); }; });
}

// ---- settings: automations + messages ----
function autoSettingsHtml() {
  const v = S.srv; if (!v) return '<div class="card"><p class="muted small">Loading server settings…</p></div>';
  const tg = v.telegram;
  return `<div class="card stack"><div class="label">Repeating agents</div>
  <div class="setrow"><span class="grow">Let agents repeat by themselves</span><button class="toggle ${v.paused ? '' : 'on'}" id="x-run" role="switch" aria-checked="${!v.paused}" aria-label="Let agents repeat by themselves"></button></div>
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
