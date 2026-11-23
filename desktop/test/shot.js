// Opens the app against a test server and saves screenshots: electron test/shot.js <url> <key> <outdir>
const { app, BrowserWindow } = require('electron');
const path = require('path'), fs = require('fs');
const [url, key, out] = process.argv.slice(-3);
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
app.whenReady().then(async () => {
  const w = new BrowserWindow({ width: 1100, height: 740, show: true, frame: false, webPreferences: { preload: path.join(__dirname, '..', 'preload.js') } });
  await w.loadFile(path.join(__dirname, '..', 'renderer', 'index.html'));
  const shot = async (n) => fs.writeFileSync(path.join(out, n + '.png'), (await w.webContents.capturePage()).toPNG());
  const js = (c) => w.webContents.executeJavaScript(c);
  await shot('1-connect');
  await js(`localStorage.setItem('conn', JSON.stringify({url:${JSON.stringify(url)},key:${JSON.stringify(key)}}))`);
  await w.loadFile(path.join(__dirname, '..', 'renderer', 'index.html')); await wait(1500);
  await shot('2-agents');
  await js(`document.querySelector('.item').click()`); await wait(800); await shot('3-chat');
  await js(`document.querySelector('#edit').click()`); await wait(500); await shot('4-editor');
  await js(`document.querySelector('#cancel').click(); document.querySelector('[data-tab=usage]').click()`); await wait(800); await shot('5-usage');
  await js(`document.querySelector('[data-tab=settings]').click()`); await wait(500); await shot('6-settings');
  app.quit();
});
