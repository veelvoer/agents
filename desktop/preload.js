const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('shell', {
  win: (a) => ipcRenderer.send('win', a),
  onMax: (f) => ipcRenderer.on('max', (_e, v) => f(v)),
});
