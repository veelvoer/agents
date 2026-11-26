const { app, BrowserWindow, ipcMain, nativeTheme, shell, Tray, Menu, Notification, nativeImage } = require('electron');
const os = require('os');
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');

const REPO = 'veelvoer/agents';
app.setName('Agents');
const stateFile = () => path.join(app.getPath('userData'), 'window.json');
const loadState = () => { try { return JSON.parse(fs.readFileSync(stateFile(), 'utf8')); } catch { return {}; } };
const saveState = (patch) => { try { fs.writeFileSync(stateFile(), JSON.stringify({ ...loadState(), ...patch })); } catch {} };
let win = null;
const send = (ch, ...a) => { if (win && !win.isDestroyed()) win.webContents.send(ch, ...a); };

const { theme, watchTheme } = require('./theme');

// ---- updates from GitHub releases ----
const semver = (v) => String(v).replace(/^v/, '').split('.').map((n) => parseInt(n, 10) || 0);
const newer = (a, b) => { const x = semver(a), y = semver(b); for (let i = 0; i < 3; i++) { if ((x[i] || 0) !== (y[i] || 0)) return (x[i] || 0) > (y[i] || 0); } return false; };
const curVersion = () => process.env.AGENTS_FAKE_VERSION || app.getVersion();
let latest = null, installing = false;
async function checkUpdate() {
  try {
    const r = await fetch(`https://api.github.com/repos/${REPO}/releases/latest`, { headers: { 'User-Agent': 'agents-app', Accept: 'application/vnd.github+json' }, signal: AbortSignal.timeout(15000) });
    if (!r.ok) throw new Error('GitHub answered ' + r.status);
    const j = await r.json();
    const arch = process.arch === 'arm64' ? 'aarch64' : 'x86_64';
    const asset = (j.assets || []).find((a) => a.name.endsWith(`linux-${arch}.AppImage`));
    const sums = (j.assets || []).find((a) => a.name === 'SHA256SUMS');
    latest = { version: String(j.tag_name).replace(/^v/, ''), notes: j.body || '', page: j.html_url, url: asset?.browser_download_url, sums: sums?.browser_download_url, name: asset?.name, size: asset?.size || 0 };
    return { ok: true, current: curVersion(), latest: latest.version, available: newer(latest.version, curVersion()), notes: latest.notes, page: latest.page, canInstall: !!(process.env.APPIMAGE && latest.url) };
  } catch (e) { return { ok: false, current: curVersion(), error: e.name === 'TimeoutError' ? 'GitHub did not answer in time' : e.message }; }
}
async function installUpdate() {
  if (installing) return { ok: false, error: 'Already updating.' };
  if (!latest?.url || !process.env.APPIMAGE) return { ok: false, error: 'This copy can not update itself. Download the new version from the release page.' };
  installing = true;
  const target = process.env.APPIMAGE, tmp = target + '.new';
  try {
    const r = await fetch(latest.url, { headers: { 'User-Agent': 'agents-app' } });
    if (!r.ok || !r.body) throw new Error('download failed (' + r.status + ')');
    const total = latest.size || +r.headers.get('content-length') || 0; let got = 0, last = 0;
    const hash = crypto.createHash('sha256'); const out = fs.createWriteStream(tmp);
    for await (const chunk of r.body) { hash.update(chunk); if (!out.write(chunk)) await new Promise((res) => out.once('drain', res)); got += chunk.length; if (Date.now() - last > 150) { last = Date.now(); send('update-progress', total ? got / total : 0); } }
    await new Promise((res, rej) => out.end((e) => (e ? rej(e) : res())));
    if (latest.sums) {
      const text = await (await fetch(latest.sums, { headers: { 'User-Agent': 'agents-app' } })).text();
      const want = text.split('\n').map((l) => l.trim().split(/\s+/)).find((p) => p[1] && p[1].replace(/^\*/, '') === latest.name)?.[0];
      if (!want || want !== hash.digest('hex')) throw new Error('the download did not match its checksum, so I kept your current version');
    }
    fs.chmodSync(tmp, 0o755); fs.renameSync(tmp, target);
    if (process.env.AGENTS_SELFTEST) return { ok: true, selftest: true };
    setTimeout(() => { app.relaunch({ execPath: target, args: [] }); app.exit(0); }, 400);
    return { ok: true };
  } catch (e) { try { fs.unlinkSync(tmp); } catch {} installing = false; return { ok: false, error: e.message }; }
}


// ---- popups, background and start at login ----
const prefsFile = () => path.join(app.getPath('userData'), 'prefs.json');
const loadPrefs = () => { try { return { notify: true, background: true, ...JSON.parse(fs.readFileSync(prefsFile(), 'utf8')) }; } catch { return { notify: true, background: true }; } };
const savePrefs = (patch) => { try { fs.writeFileSync(prefsFile(), JSON.stringify({ ...loadPrefs(), ...patch })); } catch {} };
const connFile = () => path.join(app.getPath('userData'), 'conn.json');
let conn = null, listenGen = 0, tray = null, quitting = false;
try { conn = JSON.parse(fs.readFileSync(connFile(), 'utf8')); } catch {}

function showWindow() { if (!win || win.isDestroyed()) create(); else { if (win.isMinimized()) win.restore(); win.show(); win.focus(); } }

// One quiet connection from this app to the server, kept by the main process, so popups arrive even when the window is closed.
async function listen() {
  const gen = ++listenGen; let rev = 0, last = null;
  while (conn && gen === listenGen) {
    const hdr = { Authorization: 'Bearer ' + conn.key };
    try {
      const r = await fetch(`${conn.url}/agents?since=${rev}`, { headers: hdr, signal: AbortSignal.timeout(45000) });
      if (!r.ok) throw new Error(String(r.status));
      const d = await r.json(); rev = d.rev;
      if (last === null) { last = d.act_last; continue; }
      if (d.act_last > last) {
        const ra = await fetch(`${conn.url}/activity?after=${last}&limit=30`, { headers: hdr, signal: AbortSignal.timeout(20000) });
        const items = ((await ra.json()).items || []).sort((a, b) => a.id - b.id);
        last = d.act_last;
        const p = loadPrefs();
        if (p.notify && Notification.isSupported()) for (const e of items) {
          if (!['notify', 'run', 'error'].includes(e.kind)) continue;
          if (win && !win.isDestroyed() && win.isVisible() && win.isFocused()) continue;   // you are looking at it
          const n = new Notification({ title: e.title || 'Agents', body: String(e.body || '').slice(0, 300), icon: path.join(__dirname, 'assets', 'icon.png') });
          n.on('click', showWindow); n.show();
        }
      }
    } catch { rev = 0; await new Promise((r) => setTimeout(r, 5000)); }
  }
}
function setConn(c) { conn = c; try { if (c) fs.writeFileSync(connFile(), JSON.stringify(c), { mode: 0o600 }); else fs.unlinkSync(connFile()); } catch {} if (c) listen(); else listenGen++; }

function buildTray() {
  if (tray) return;
  try {
    tray = new Tray(nativeImage.createFromPath(path.join(__dirname, 'assets', 'icon.png')).resize({ width: 22, height: 22 }));
    tray.setToolTip('Agents');
    tray.setContextMenu(Menu.buildFromTemplate([{ label: 'Open Agents', click: showWindow }, { type: 'separator' }, { label: 'Quit', click: () => { quitting = true; app.quit(); } }]));
    tray.on('click', showWindow);
  } catch { tray = null; }
}

const autostartFile = () => path.join(os.homedir(), '.config', 'autostart', 'agents.desktop');
function setAutostart(on) {
  try {
    if (!on) { fs.rmSync(autostartFile(), { force: true }); return true; }
    fs.mkdirSync(path.dirname(autostartFile()), { recursive: true });
    const exec = process.env.APPIMAGE || process.execPath;
    fs.writeFileSync(autostartFile(), `[Desktop Entry]\nType=Application\nName=Agents\nExec="${exec}" --hidden\nIcon=agents\nTerminal=false\nX-GNOME-Autostart-enabled=true\n`);
    return true;
  } catch { return false; }
}

function create() {
  const st = loadState();
  win = new BrowserWindow({
    width: st.width || 1100, height: st.height || 740, minWidth: 480, minHeight: 520,
    title: 'Agents', frame: !!st.frame, autoHideMenuBar: true, backgroundColor: '#1B1A19',
    icon: path.join(__dirname, 'assets', 'icon.png'),
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true },
  });
  win.setMenuBarVisibility(false);
  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  if (st.max) win.maximize();
  win.on('close', (e) => {
    const b = win.getNormalBounds(); saveState({ width: b.width, height: b.height, max: win.isMaximized() });
    if (!quitting && conn && loadPrefs().background && tray) { e.preventDefault(); win.hide(); }   // keep running in the tray so popups still arrive
  });
  win.on('focus', () => win.webContents.focus());   // make sure the page gets the keyboard
  win.webContents.setWindowOpenHandler(({ url }) => { if (/^https?:/.test(url)) shell.openExternal(url); return { action: 'deny' }; });
  win.on('maximize', () => send('max', true));
  win.on('unmaximize', () => send('max', false));
}

ipcMain.on('win', (e, act) => {
  const w = BrowserWindow.fromWebContents(e.sender); if (!w) return;
  if (act === 'min') w.minimize(); else if (act === 'max') w.isMaximized() ? w.unmaximize() : w.maximize(); else if (act === 'close') w.close();
});
ipcMain.handle('prefs-get', () => ({ ...loadPrefs(), autostart: fs.existsSync(autostartFile()), tray: !!tray }));
ipcMain.handle('prefs-set', (_e, p) => { if ('autostart' in p) { setAutostart(!!p.autostart); delete p.autostart; } savePrefs(p); return true; });
ipcMain.on('set-conn', (_e, c) => setConn(c && c.url && c.key ? { url: c.url, key: c.key } : null));
ipcMain.handle('frame-get', () => !!loadState().frame);
ipcMain.handle('frame-set', (_e, on) => { saveState({ frame: !!on }); setTimeout(() => { app.relaunch(); app.exit(0); }, 200); });
ipcMain.handle('theme', () => theme());
ipcMain.handle('update-check', () => checkUpdate());
ipcMain.handle('update-install', () => installUpdate());
ipcMain.handle('version', () => curVersion());
ipcMain.on('open-external', (_e, url) => { if (/^https:\/\/github\.com\//.test(url)) shell.openExternal(url); });

if (process.env.AGENTS_SELFTEST) {
  app.whenReady().then(async () => { console.log('CHECK', JSON.stringify(await checkUpdate())); console.log('INSTALL', JSON.stringify(await installUpdate())); app.exit(0); });
} else if (!app.requestSingleInstanceLock()) app.quit();
else {
  app.on('second-instance', () => showWindow());
  app.on('before-quit', () => { quitting = true; });
  app.whenReady().then(() => {
    buildTray();
    if (!process.argv.includes('--hidden') || !tray || !conn) create();   // started at login: stay in the tray
    watchTheme(send);
    if (conn) listen();
  });
  app.on('window-all-closed', () => { if (quitting || !tray || !loadPrefs().background || !conn) app.quit(); });
}
