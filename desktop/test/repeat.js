// Opens the Repeat dialog, writes a schedule in words, presses Understand and saves a screenshot.
const { app, BrowserWindow, ipcMain } = require('electron');
const path = require('path'), fs = require('fs');
const { theme } = require('../theme');
ipcMain.handle('theme', () => theme()); ipcMain.handle('version', () => '0.7.0'); ipcMain.handle('frame-get', () => false); ipcMain.handle('update-check', () => ({ ok: true, current: '0.7.0', latest: '0.7.0', available: false }));
const [url, key, out] = process.argv.slice(-3);
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
app.whenReady().then(async () => {
  const w = new BrowserWindow({ width: 1100, height: 760, show: false, frame: false, webPreferences: { offscreen: true, preload: path.join(__dirname, '..', 'preload.js') } });
  const js = (c) => w.webContents.executeJavaScript(c);
  const shot = async (n) => { await wait(400); fs.writeFileSync(path.join(out, n + '.png'), (await w.webContents.capturePage()).toPNG()); };
  await w.loadFile(path.join(__dirname, '..', 'renderer', 'index.html'));
  await js(`localStorage.setItem('conn', JSON.stringify({url:${JSON.stringify(url)},key:${JSON.stringify(key)}}))`);
  await w.loadFile(path.join(__dirname, '..', 'renderer', 'index.html')); await wait(1500);
  await js(`document.querySelector('.item').click()`); await wait(800);
  await js(`document.querySelector('#hrep').click()`); await wait(500);
  await js(`document.querySelector('#jw-ins').click()`); await wait(25000);
  await shot('r1-from-instructions');
  await js(`document.querySelector('#jw').value = 'weekdays at 8:15 and on saturdays at 10 in the morning'; document.querySelector('#jw-go').click()`); await wait(25000);
  await shot('r2-own-words');
  console.log('saving:', await js(`(async()=>{document.querySelector('#jsave').click(); await new Promise(r=>setTimeout(r,2500)); return JSON.stringify(await (await fetch(${JSON.stringify(url)}+'/jobs',{headers:{Authorization:'Bearer ${key}'}})).json().then(j=>j.map(x=>[x.human,x.next])))})()`));
  app.quit();
});
