// Opens the new-agent form and types into every field with real key events: electron test/type.js <url> <key>
const { app, BrowserWindow, ipcMain } = require('electron');
const path = require('path');
const { theme } = require('../theme');
ipcMain.handle('theme', () => theme()); ipcMain.handle('version', () => '0.4.0'); ipcMain.handle('update-check', () => ({ ok: true, current: '0.4.0', latest: '0.4.0', available: false }));
const [url, key] = process.argv.slice(-2);
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
app.whenReady().then(async () => {
  const w = new BrowserWindow({ width: 1100, height: 740, show: false, frame: false, webPreferences: { offscreen: true, preload: path.join(__dirname, '..', 'preload.js') } });
  const js = (c) => w.webContents.executeJavaScript(c);
  await w.loadFile(path.join(__dirname, '..', 'renderer', 'index.html'));
  await js(`localStorage.setItem('conn', JSON.stringify({url:${JSON.stringify(url)},key:${JSON.stringify(key)}}))`);
  await w.loadFile(path.join(__dirname, '..', 'renderer', 'index.html')); await wait(1500);
  const type = async (text) => { for (const ch of text) { w.webContents.sendInputEvent({ type: 'char', keyCode: ch }); await wait(15); } };
  await js(`document.querySelector('#new').click()`); await wait(800);
  console.log('focused after open:', await js(`document.activeElement && document.activeElement.id`));
  await js(`document.querySelector('#n').focus()`); await type('Hello');
  console.log('name value:', await js(`document.querySelector('#n').value`));
  await wait(4000);   // wait through a poll cycle
  await type(' World');
  console.log('name value later:', await js(`document.querySelector('#n').value`), '| focused:', await js(`document.activeElement && document.activeElement.id`));
  await js(`document.querySelector('#p').focus()`); await type('Be brief');
  console.log('prompt value:', await js(`document.querySelector('#p').value`));
  app.quit();
});
