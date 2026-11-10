// Scoreboard crew: watches your favorite teams and sends score pop-ups + a corner scorebug to the TV.
// Runs on a computer on the same Wi-Fi as the TV. Needs Node 18+. No installs.
//   node scorewatch.js            run
//   node scorewatch.js --test     send one sample pop-up and exit
//   node scorewatch.js --replay football/nfl <eventId>   replay a finished game's scoring plays (test)
// Settings come from the same locked config.json the TV page uses (key.txt sits next to this file).
const fs = require('fs'), path = require('path'), crypto = require('crypto');
const DIR = __dirname;
const LOCAL = JSON.parse(fs.readFileSync(path.join(DIR, 'scorewatch.local.json'), 'utf8')); // {tv:"http://ip:5001", site:"https://.../"}
const KEY = Buffer.from(fs.readFileSync(path.join(DIR, 'key.txt'), 'utf8').trim(), 'base64');
const ESPN = 'https://site.api.espn.com/apis/site/v2/sports/';
const TVHOST = (LOCAL.tv.match(/\/\/([^:\/]+)/) || [])[1] || '192.168.1.56';
const APP = 'http://' + TVHOST + ':8777';
const sleep = ms => new Promise(r => setTimeout(r, ms));
const log = (...a) => console.log(new Date().toLocaleTimeString(), ...a);

async function getJSON(url, ms = 12000) {
  const c = new AbortController(); const t = setTimeout(() => c.abort(), ms);
  try { const r = await fetch(url, { signal: c.signal }); if (!r.ok) throw new Error('HTTP ' + r.status); return await r.json(); }
  finally { clearTimeout(t); }
}
async function app(pathname, body) { // POST to the Home TV app overlay; never throws
  const c = new AbortController(); const t = setTimeout(() => c.abort(), 4000);
  try { const r = await fetch(APP + pathname, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}), signal: c.signal }); return r.ok; }
  catch (e) { return false; } finally { clearTimeout(t); }
}
async function appMuted() { const c = new AbortController(); const t = setTimeout(() => c.abort(), 3500);
  try { const r = await fetch(APP + '/muted', { signal: c.signal }); if (!r.ok) return null; const j = await r.json(); return !!j.muted; } catch (e) { return null; } finally { clearTimeout(t); }
}
async function tv(pathname, body) { // never throws: TV off = stay quiet
  const c = new AbortController(); const t = setTimeout(() => c.abort(), 4000);
  try { const r = await fetch(LOCAL.tv + pathname, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body), signal: c.signal }); return r.ok; }
  catch (e) { return false; } finally { clearTimeout(t); }
}
async function alertsOn() { // null = TV unreachable/off
  try { const base = LOCAL.tv.replace(/\/$/, ''); const c = new AbortController(); const t = setTimeout(() => c.abort(), 4000);
    const r = await fetch(base + '/', { signal: c.signal }); clearTimeout(t); if (!r.ok) return null; const j = await r.json();
    const n = (j.result || {}).notifications || {}; return n.displayNotifications !== false; } catch (e) { return null; }
}
async function setAlerts(on) { return tv('/set/notifications', { displayNotifications: on, displayFixedNotifications: on }); }
async function loadConfig() {
  const p = await getJSON(LOCAL.site + 'config.json?' + Date.now());
  const iv = Buffer.from(p.iv, 'base64'), ct = Buffer.from(p.ct, 'base64');
  const d = crypto.createDecipheriv('aes-256-gcm', KEY, iv); d.setAuthTag(ct.subarray(ct.length - 16));
  return JSON.parse(Buffer.concat([d.update(ct.subarray(0, ct.length - 16)), d.final()]).toString('utf8'));
}
const ord = n => n + (['th', 'st', 'nd', 'rd'][(n % 100 > 10 && n % 100 < 14) ? 0 : Math.min(n % 10, 4) % 4] || 'th');
function periodName(lg, n) {
  if (/football/.test(lg)) return n > 4 ? 'OT' : ord(n) + ' quarter';
  if (/basketball/.test(lg)) return n > 4 ? 'OT' : ord(n) + ' quarter';
  if (/hockey/.test(lg)) return n > 3 ? 'OT' : ord(n) + ' period';
  if (/baseball/.test(lg)) return ord(n) + ' inning';
  if (/soccer/.test(lg)) return n === 1 ? '1st half' : n === 2 ? '2nd half' : 'extra time';
  return 'period ' + n;
}
const SCORE_WORD = lg => /football/.test(lg) ? 'SCORE' : /hockey|soccer/.test(lg) ? 'GOAL' : /baseball/.test(lg) ? 'RUN' : 'SCORE';

function sides(ev) {
  const c = ev.competitions[0], h = c.competitors.find(x => x.homeAway === 'home'), a = c.competitors.find(x => x.homeAway === 'away');
  return { c, h, a };
}
function line(ev) { const { h, a } = sides(ev); return `${a.team.abbreviation} ${a.score}  ·  ${h.team.abbreviation} ${h.score}`; }
function bugText(lg, ev) {
  const { c } = sides(ev), st = ev.status, sit = c.situation || {};
  let t = line(ev) + '   ' + (st.type.shortDetail || '');
  if (/football/.test(lg) && sit.downDistanceText) t += '   ' + sit.downDistanceText + (sit.possession ? '' : '');
  if (/baseball/.test(lg) && sit.outs != null) t += `   ${sit.balls || 0}-${sit.strikes || 0}, ${sit.outs} out` + ((sit.onFirst || sit.onSecond || sit.onThird) ? '  on: ' + [sit.onFirst && '1st', sit.onSecond && '2nd', sit.onThird && '3rd'].filter(Boolean).join(',') : '');
  return t;
}

const G = {}, IDS = {}; // per-game memory, team ids
function watchUrlFor(lg, ev) { try { const c = ev.competitions[0]; const q = c.competitors.map(x => (x.team||{}).displayName || '').filter(Boolean).join(' vs '); return 'intent://tv.youtube.com/search?q=' + encodeURIComponent(q) + '#Intent;scheme=https;package=com.amazon.firetv.youtube.tv;launchFlags=0x10008000;end'; } catch (e) { return ''; } }
let mutedSince = 0;            // >0 while game alerts are muted
let muteUntil = 0;            // epoch ms when alerts auto-restore
async function checkGame(cfg, lg, ev, myId) {
  const A = Object.assign({ popups: true, scorebug: true, periods: true, corner: 'top_end' }, cfg.alerts || {});
  const { c, h, a } = sides(ev), st = ev.status, state = st.type.state, id = ev.id;
  const me = c.competitors.find(x => x.team.id === myId) || h, logo = me.team.logo || (me.team.logos || [{}])[0].href;
  let g = G[id];
  if (!g) { // first sight: remember where things stand, announce nothing old
    g = G[id] = { state, period: st.period, plays: new Set(), primed: false, total: (+h.score || 0) + (+a.score || 0) };
    if (state !== 'in') return;
  }
  const pop = (title, message, big) => A.popups && app('/popup', { title, message, logo, big, watchUrl: watchUrlFor(lg, ev), stats: [a.team.displayName + '  ' + (a.score||'') + '    ' + h.team.displayName + '  ' + (h.score||'')] });
  if (g.state === 'pre' && state === 'in') pop('Game on: ' + ev.shortName, 'Underway now');
  if (state === 'in') {
    // scoring plays (skip basketball: too many)
    if (!/basketball/.test(lg)) {
      try {
        const s = await getJSON(ESPN + lg + '/summary?event=' + id);
        const plays = s.scoringPlays || [];
        for (const p of plays) {
          if (g.plays.has(p.id)) continue; g.plays.add(p.id);
          if (!g.primed) continue;
          const tm = (p.team || {}).abbreviation || '', mine = (p.team || {}).id === myId;
          const kind = (p.type || {}).text || SCORE_WORD(lg);
          if (A.popups) await app('/popup', { title: (mine ? '🔥 ' : '') + kind.toUpperCase() + ' · ' + tm,
            message: (p.text || '') + `\n${a.team.abbreviation} ${p.awayScore}  ·  ${h.team.abbreviation} ${p.homeScore}` + (p.clock ? '   ' + periodName(lg, (p.period || {}).number || st.period) + ' ' + p.clock.displayValue : ''),
            logo: (p.team || {}).logo || logo, watchUrl: watchUrlFor(lg, ev), big: mine,
            stats: [a.team.displayName + '  ' + a.score + '    ' + h.team.displayName + '  ' + h.score, ev.shortName + ' · ' + (st.type.shortDetail || '')] });
        }
        g.primed = true;
      } catch (e) { /* summary hiccup: try next round */ }
    }
    if (A.periods && g.state === 'in' && st.period > g.period) pop('End of ' + periodName(lg, g.period), line(ev));
    if (A.periods && /basketball/.test(lg) && st.type.name === 'STATUS_HALFTIME' && !g.half) { g.half = true; pop('Halftime', line(ev)); }
    if (A.scorebug) await app('/bug', { text: bugText(lg, ev), logo: logo });
  }
  if (g.state === 'in' && state === 'post') {
    const win = me.winner; pop((win ? '✅ FINAL · ' : 'FINAL · ') + ev.shortName, line(ev) + (win ? `\n${me.team.shortDisplayName || me.team.displayName} win` : ''), true);
    app('/bug/clear', {});
  }
  g.state = state; g.period = st.period;
}

async function main() {
  if (process.argv[2] === '--test') { const ok = await app('/popup', { title: 'Scoreboard crew checking in', message: 'Press OK to expand, Down to mute.', stats: ['This is a test pop-up', 'Your real alerts will look like this'], watchUrl: '' }); console.log(ok ? 'sent' : 'Home TV app not reachable'); return; }
  if (process.argv[2] === '--replay') {
    const lg = process.argv[3], id = process.argv[4]; const s = await getJSON(ESPN + lg + '/summary?event=' + id);
    const comp = s.header.competitions[0], h = comp.competitors.find(x => x.homeAway === 'home'), a = comp.competitors.find(x => x.homeAway === 'away');
    for (const p of (s.scoringPlays || []).slice(-2)) {
      await tv('/notify', { id: 'p' + p.id, title: ((p.type || {}).text || 'SCORE').toUpperCase() + ' · ' + p.team.abbreviation, message: p.text + `\n${a.team.abbreviation} ${p.awayScore}  ·  ${h.team.abbreviation} ${p.homeScore}   ${periodName(lg, p.period.number)} ${p.clock.displayValue}`, source: 'Replay test', largeIcon: p.team.logo, corner: 'top_end', duration: 9 });
      await tv('/notify_fixed', { id: 'bugtest', message: `${a.team.abbreviation} ${p.awayScore}  ·  ${h.team.abbreviation} ${p.homeScore}   ${ord(p.period.number)} ${p.clock.displayValue}   2nd & 7 at ${h.team.abbreviation} 34`, icon: p.team.logo, backgroundColor: '#D90B1020', borderColor: '#FFCC00', shape: 'rounded', expiration: 40 });
      await sleep(11000);
    }
    return;
  }
  startControl();
  log('Scoreboard crew on duty. TV:', LOCAL.tv);
  let cfg = {}, cfgAt = 0;
  for (;;) {
    let live = false;
    try {
      if (Date.now() - cfgAt > 5 * 60000) { try { cfg = await loadConfig(); cfgAt = Date.now(); } catch (e) { log('settings not reachable, keeping the last ones'); } }
      // Respect the TV's mute: if someone turned game alerts off on the welcome screen, stay quiet and auto-restore after 1 hour.
      const m = await appMuted();
      if (m === true) { await app('/bug/clear', {}); await sleep(20000); continue; } // app is muted; it manages its own 1-hour timer
      const teams = (cfg.teams || []).filter(t => t[2] !== 'hide' && t[3] !== 'noalerts');
      const byLg = {};
      for (const t of teams) { const k = t[0] + '|' + t[1];
        if (!IDS[k]) { try { IDS[k] = String((await getJSON(ESPN + t[0] + '/teams/' + t[1] + '/schedule')).team.id); } catch (e) { continue; } }
        (byLg[t[0]] = byLg[t[0]] || []).push(IDS[k]); }
      for (const lg of Object.keys(byLg)) {
        let sb; try { sb = await getJSON(ESPN + lg + '/scoreboard'); } catch (e) { continue; }
        for (const ev of sb.events || []) {
          const mine = ev.competitions[0].competitors.find(x => byLg[lg].includes(String(x.team.id)));
          if (!mine) continue;
          if (ev.status.type.state === 'in') live = true;
          await checkGame(cfg, lg, ev, mine.team.id);
        }
      }
    } catch (e) { log('round skipped:', e.message); }
    await sleep(live ? 20000 : 120000);
  }
}
function lanIPs() { const os = require('os'), out = []; const ni = os.networkInterfaces();
  for (const k of Object.keys(ni)) for (const a of ni[k]) if (a.family === 'IPv4' && !a.internal) out.push(a.address); return out; }
function page() {
  const left = muteUntil > Date.now() ? Math.ceil((muteUntil - Date.now()) / 60000) : 0;
  return `<!doctype html><html><head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1"><title>Game Alerts</title>
<style>:root{color-scheme:dark}body{margin:0;font-family:-apple-system,system-ui,sans-serif;background:#0b1020;color:#fff;display:flex;flex-direction:column;align-items:center;justify-content:center;min-height:100vh;text-align:center;padding:24px}
h1{font-weight:300;font-size:26px;margin:0 0 6px}#s{opacity:.7;margin-bottom:28px;font-size:16px}
button{font-size:22px;font-weight:700;border:0;border-radius:18px;padding:22px 10px;width:100%;max-width:420px;margin:8px 0;cursor:pointer}
.mute{background:#c62828;color:#fff}.on{background:#ffcc00;color:#111}.small{font-size:16px;padding:14px;background:#1a2340;color:#cdd6f4}</style></head>
<body><h1>🏟️ Game alerts</h1><div id=s>Loading…</div>
<button class=mute onclick="go('/mute?min=60')">🔕 Mute for 1 hour</button>
<button class=small onclick="go('/mute?min=180')">Mute for 3 hours</button>
<button class=on onclick="go('/unmute')">🔔 Turn alerts back on</button>
<script>function r(){fetch('/status').then(x=>x.json()).then(j=>{document.getElementById('s').textContent=j.muted?('Muted — '+j.minutesLeft+' min left'):'Alerts are ON';});}
function go(u){fetch(u,{method:'POST'}).then(r);}r();setInterval(r,5000);</script></body></html>`;
}
function startControl() {
  const http = require('http'), PORT = (LOCAL.controlPort || 8099);
  http.createServer(async (req, res) => {
    const u = req.url.split('?')[0]; const q = require('url').parse(req.url, true).query;
    try {
      if (u === '/mute') { const min = Math.max(1, +q.min || 60); muteUntil = Date.now() + min * 60000; mutedSince = Date.now(); await setAlerts(false); }
      else if (u === '/unmute') { muteUntil = 0; mutedSince = 0; await setAlerts(true); }
      if (u === '/status') { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ muted: muteUntil > Date.now(), minutesLeft: muteUntil > Date.now() ? Math.ceil((muteUntil - Date.now()) / 60000) : 0 })); return; }
      res.writeHead(200, { 'Content-Type': 'text/html' }); res.end(page());
    } catch (e) { res.writeHead(500); res.end('err'); }
  }).listen(PORT, () => {
    const ips = lanIPs(); log('Phone control page: ' + ips.map(ip => 'http://' + ip + ':' + PORT).join('  or  ') + '  (also http://' + require('os').hostname() + ':' + PORT + ')');
  });
}
main();
