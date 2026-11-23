const { contextBridge, ipcRenderer } = require('electron');
const on = (ch, f) => { const h = (_e, ...a) => f(...a); ipcRenderer.on(ch, h); return () => ipcRenderer.removeListener(ch, h); };
contextBridge.exposeInMainWorld('shell', {
  win: (a) => ipcRenderer.send('win', a),
  onMax: (f) => on('max', f),
  theme: () => ipcRenderer.invoke('theme'),
  getFrame: () => ipcRenderer.invoke('frame-get'),
  setFrame: (on) => ipcRenderer.invoke('frame-set', on),
  onTheme: (f) => on('theme', f),
  version: () => ipcRenderer.invoke('version'),
  checkUpdate: () => ipcRenderer.invoke('update-check'),
  installUpdate: () => ipcRenderer.invoke('update-install'),
  onProgress: (f) => on('update-progress', f),
  openExternal: (u) => ipcRenderer.send('open-external', u),
});
