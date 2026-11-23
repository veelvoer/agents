const { nativeTheme } = require('electron');
const path = require('path');
const fs = require('fs');
const os = require('os');
// ---- system colours: read what the desktop uses (GTK colour files, KDE, GNOME) ----
const CFG = path.join(os.homedir(), '.config');
function gtkColors(file, seen = new Set(), out = {}) {
  let text = ''; const real = (() => { try { return fs.realpathSync(file); } catch { return file; } })();
  if (seen.has(real)) return out; seen.add(real);
  try { text = fs.readFileSync(file, 'utf8'); } catch { return out; }
  for (const m of text.matchAll(/@import\s+(?:url\()?['"]?([^'")\s;]+)['"]?\)?\s*;|@define-color\s+([\w-]+)\s+(#[0-9a-fA-F]{3,8}|rgba?\([^)]*\))\s*;/g)) {
    if (m[1]) gtkColors(path.resolve(path.dirname(file), m[1]), seen, out); else out[m[2]] = m[3];   // later definitions win, like in CSS
  }
  return out;
}
function iniSection(text, name) {
  const m = text.match(new RegExp('^\\[' + name.replace(/[[\]]/g, '\\$&') + '\\]\\s*$([\\s\\S]*?)(?=^\\[|$(?![\\s\\S]))', 'm')); const o = {};
  if (m) for (const l of m[1].split('\n')) { const i = l.indexOf('='); if (i > 0) o[l.slice(0, i).trim()] = l.slice(i + 1).trim(); }
  return o;
}
const rgb = (s) => s && /^\d+,\d+,\d+/.test(s) ? '#' + s.split(',').slice(0, 3).map((n) => (+n).toString(16).padStart(2, '0')).join('') : null;
const GNOME = { blue: '#3584e4', teal: '#2190a4', green: '#3a944a', yellow: '#c88800', orange: '#ed5b00', red: '#e62d42', pink: '#d56199', purple: '#9141ac', slate: '#6f8396' };
function systemColors() {
  const dark = nativeTheme.shouldUseDarkColors;
  const g = gtkColors(path.join(CFG, 'gtk-4.0', 'gtk.css'));
  if (g.window_bg_color && (g.accent_bg_color || g.accent_color)) {
    return { source: 'GTK theme', dark, bg: g.window_bg_color, fg: g.window_fg_color, card: g.card_bg_color || g.view_bg_color, side: g.sidebar_bg_color, accent: g.accent_bg_color || g.accent_color, accentFg: g.accent_fg_color, border: g.borders };
  }
  let kde = ''; try { kde = fs.readFileSync(path.join(CFG, 'kdeglobals'), 'utf8'); } catch {}
  if (kde) {
    const w = iniSection(kde, 'Colors:Window'), v = iniSection(kde, 'Colors:View'), sel = iniSection(kde, 'Colors:Selection'), gen = iniSection(kde, 'General');
    if (rgb(w.BackgroundNormal)) return { source: 'KDE colours', dark, bg: rgb(w.BackgroundNormal), fg: rgb(w.ForegroundNormal), card: rgb(v.BackgroundNormal), accent: rgb(gen.AccentColor) || rgb(sel.BackgroundNormal), accentFg: rgb(sel.ForegroundNormal) };
  }
  return { source: 'system', dark };
}
async function gnomeAccent() {
  try { const { execFile } = require('child_process'); const out = await new Promise((r) => execFile('gsettings', ['get', 'org.gnome.desktop.interface', 'accent-color'], { timeout: 1500 }, (_e, o) => r(o || ''))); return GNOME[out.replace(/['\s]/g, '')] || null; } catch { return null; }
}
async function theme() { const t = systemColors(); if (!t.accent) { const a = await gnomeAccent(); if (a) t.accent = a; } return t; }
function watchTheme(send) {
  let timer; const poke = () => { clearTimeout(timer); timer = setTimeout(async () => send('theme', await theme()), 250); };
  nativeTheme.on('updated', poke);
  for (const d of [path.join(CFG, 'gtk-4.0'), path.join(CFG, 'gtk-3.0'), CFG]) { try { fs.watch(d, { persistent: false }, (_e, f) => { if (!f || /gtk|colors|qs-theme|kdeglobals|\.css$/.test(f)) poke(); }); } catch {} }
}

module.exports = { theme, watchTheme };
