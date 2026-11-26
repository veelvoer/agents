const { contextBridge, ipcRenderer } = require('electron');
const on = (ch, f) => { const h = (_e, ...a) => f(...a); ipcRenderer.on(ch, h); return () => ipcRenderer.removeListener(ch, h); };
contextBridge.exposeInMainWorld('shell', {
  win: (a) => ipcRenderer.send('win', a),
  onMax: (f) => on('max', f),
  theme: () => ipcRenderer.invoke('theme'),
  getPrefs: () => ipcRenderer.invoke('prefs-get'),
  setPrefs: (p) => ipcRenderer.invoke('prefs-set', p),
  setConn: (c) => ipcRenderer.send('set-conn', c),
  getFrame: () => ipcRenderer.invoke('frame-get'),
  setFrame: (on) => ipcRenderer.invoke('frame-set', on),
  onTheme: (f) => on('theme', f),
  version: () => ipcRenderer.invoke('version'),
  checkUpdate: () => ipcRenderer.invoke('update-check'),
  installUpdate: () => ipcRenderer.invoke('update-install'),
  onProgress: (f) => on('update-progress', f),
  openExternal: (u) => ipcRenderer.send('open-external', u),
});
