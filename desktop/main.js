const { app, BrowserWindow, ipcMain, Menu, nativeTheme, shell } = require('electron');
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');

const REPO = 'veelvoer/agents';
app.setName('Agents');
const stateFile = () => path.join(app.getPath('userData'), 'window.json');
const loadState = () => { try { return JSON.parse(fs.readFileSync(stateFile(), 'utf8')); } catch { return {}; } };
let win = null;
const send = (ch, ...a) => { if (win && !win.isDestroyed()) win.webContents.send(ch, ...a); };

const { theme, watchTheme } = require('./theme');

// ---- updates from GitHub releases ----
const semver = (v) => String(v).replace(/^v/, '').split('.').map((n) => parseInt(n, 10) || 0);
const newer = (a, b) => { const x = semver(a), y = semver(b); for (let i = 0; i < 3; i++) { if ((x[i] || 0) !== (y[i] || 0)) return (x[i] || 0) > (y[i] || 0); } return false; };
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
    return { ok: true, current: app.getVersion(), latest: latest.version, available: newer(latest.version, app.getVersion()), notes: latest.notes, page: latest.page, canInstall: !!(process.env.APPIMAGE && latest.url) };
  } catch (e) { return { ok: false, current: app.getVersion(), error: e.name === 'TimeoutError' ? 'GitHub did not answer in time' : e.message }; }
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
    setTimeout(() => { app.relaunch({ execPath: target, args: [] }); app.exit(0); }, 400);
    return { ok: true };
  } catch (e) { try { fs.unlinkSync(tmp); } catch {} installing = false; return { ok: false, error: e.message }; }
}

function create() {
  const st = loadState();
  win = new BrowserWindow({
    width: st.width || 1100, height: st.height || 740, minWidth: 480, minHeight: 520,
    title: 'Agents', frame: false, backgroundColor: '#1B1A19', show: false,
    icon: path.join(__dirname, 'assets', 'icon.png'),
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, sandbox: true },
  });
  Menu.setApplicationMenu(null);
  win.setMenuBarVisibility(false);
  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  win.once('ready-to-show', () => { if (st.max) win.maximize(); win.show(); });
  win.on('close', () => { const b = win.getNormalBounds(); fs.writeFileSync(stateFile(), JSON.stringify({ width: b.width, height: b.height, max: win.isMaximized() })); });
  win.webContents.setWindowOpenHandler(({ url }) => { if (/^https?:/.test(url)) shell.openExternal(url); return { action: 'deny' }; });
  win.on('maximize', () => send('max', true));
  win.on('unmaximize', () => send('max', false));
}

ipcMain.on('win', (e, act) => {
  const w = BrowserWindow.fromWebContents(e.sender); if (!w) return;
  if (act === 'min') w.minimize(); else if (act === 'max') w.isMaximized() ? w.unmaximize() : w.maximize(); else if (act === 'close') w.close();
});
ipcMain.handle('theme', () => theme());
ipcMain.handle('update-check', () => checkUpdate());
ipcMain.handle('update-install', () => installUpdate());
ipcMain.handle('version', () => app.getVersion());
ipcMain.on('open-external', (_e, url) => { if (/^https:\/\/github\.com\//.test(url)) shell.openExternal(url); });

if (!app.requestSingleInstanceLock()) app.quit();
else {
  app.on('second-instance', () => { if (win) { if (win.isMinimized()) win.restore(); win.focus(); } });
  app.whenReady().then(() => { create(); watchTheme(send); });
  app.on('window-all-closed', () => app.quit());
}
