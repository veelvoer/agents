const { app, BrowserWindow, ipcMain, Menu, shell } = require('electron');
const path = require('path');
const fs = require('fs');

app.setName('Agents');
const stateFile = () => path.join(app.getPath('userData'), 'window.json');
const loadState = () => { try { return JSON.parse(fs.readFileSync(stateFile(), 'utf8')); } catch { return {}; } };

function create() {
  const st = loadState();
  const win = new BrowserWindow({
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
  win.on('maximize', () => win.webContents.send('max', true));
  win.on('unmaximize', () => win.webContents.send('max', false));
  return win;
}

ipcMain.on('win', (e, act) => {
  const w = BrowserWindow.fromWebContents(e.sender); if (!w) return;
  if (act === 'min') w.minimize();
  else if (act === 'max') w.isMaximized() ? w.unmaximize() : w.maximize();
  else if (act === 'close') w.close();
});

if (!app.requestSingleInstanceLock()) app.quit();
else {
  app.on('second-instance', () => { const w = BrowserWindow.getAllWindows()[0]; if (w) { if (w.isMinimized()) w.restore(); w.focus(); } });
  app.whenReady().then(create);
  app.on('window-all-closed', () => app.quit());
}
