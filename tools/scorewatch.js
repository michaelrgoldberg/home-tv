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
const sleep = ms => new Promise(r => setTimeout(r, ms));
const log = (...a) => console.log(new Date().toLocaleTimeString(), ...a);

async function getJSON(url, ms = 12000) {
  const c = new AbortController(); const t = setTimeout(() => c.abort(), ms);
  try { const r = await fetch(url, { signal: c.signal }); if (!r.ok) throw new Error('HTTP ' + r.status); return await r.json(); }
  finally { clearTimeout(t); }
}
async function tv(pathname, body) { // never throws: TV off = stay quiet
  const c = new AbortController(); const t = setTimeout(() => c.abort(), 4000);
  try { const r = await fetch(LOCAL.tv + pathname, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body), signal: c.signal }); return r.ok; }
  catch (e) { return false; } finally { clearTimeout(t); }
}
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
async function checkGame(cfg, lg, ev, myId) {
  const A = Object.assign({ popups: true, scorebug: true, periods: true, corner: 'top_end' }, cfg.alerts || {});
  const { c, h, a } = sides(ev), st = ev.status, state = st.type.state, id = ev.id;
  const me = c.competitors.find(x => x.team.id === myId) || h, logo = me.team.logo || (me.team.logos || [{}])[0].href;
  let g = G[id];
  if (!g) { // first sight: remember where things stand, announce nothing old
    g = G[id] = { state, period: st.period, plays: new Set(), primed: false, total: (+h.score || 0) + (+a.score || 0) };
    if (state !== 'in') return;
  }
  const pop = (title, message, big) => A.popups && tv('/notify', { id: 'g' + id + '-' + Date.now(), title, message, source: ev.shortName, largeIcon: logo, corner: A.corner, duration: big ? 14 : 10 });
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
          await tv('/notify', A.popups ? { id: 'p' + p.id, title: (mine ? '🔥 ' : '') + kind.toUpperCase() + ' · ' + tm, message: (p.text || '') + `\n${a.team.abbreviation} ${p.awayScore}  ·  ${h.team.abbreviation} ${p.homeScore}` + (p.clock ? '   ' + periodName(lg, (p.period || {}).number || st.period) + ' ' + p.clock.displayValue : ''), source: ev.shortName, largeIcon: (p.team || {}).logo || logo, corner: A.corner, duration: 14 } : { id: 'noop', duration: 1 });
        }
        g.primed = true;
      } catch (e) { /* summary hiccup: try next round */ }
    }
    if (A.periods && g.state === 'in' && st.period > g.period) pop('End of ' + periodName(lg, g.period), line(ev));
    if (A.periods && /basketball/.test(lg) && st.type.name === 'STATUS_HALFTIME' && !g.half) { g.half = true; pop('Halftime', line(ev)); }
    if (A.scorebug) await tv('/notify_fixed', { id: 'bug' + id, message: bugText(lg, ev), icon: logo, backgroundColor: '#D90B1020', borderColor: '#FFCC00', messageColor: '#FFFFFF', shape: 'rounded', expiration: 75 });
  }
  if (g.state === 'in' && state === 'post') {
    const win = me.winner; pop((win ? '✅ FINAL · ' : 'FINAL · ') + ev.shortName, line(ev) + (win ? `\n${me.team.shortDisplayName || me.team.displayName} win` : ''), true);
    tv('/notify_fixed', { id: 'bug' + id, visible: false });
  }
  g.state = state; g.period = st.period;
}

async function main() {
  if (process.argv[2] === '--test') { const ok = await tv('/notify', { title: 'Scoreboard crew checking in', message: 'Pop-ups are connected to this TV.', duration: 8 }); console.log(ok ? 'sent' : 'TV not reachable'); return; }
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
  log('Scoreboard crew on duty. TV:', LOCAL.tv);
  let cfg = {}, cfgAt = 0;
  for (;;) {
    let live = false;
    try {
      if (Date.now() - cfgAt > 5 * 60000) { try { cfg = await loadConfig(); cfgAt = Date.now(); } catch (e) { log('settings not reachable, keeping the last ones'); } }
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
main();
