// Which screen is this? Same pages run on the master bedroom Hisense TV and the projector Fire Stick.
// Each screen gets its own name on the welcome screen and its own privacy defaults the first time it runs;
// after that the 🔒 Privacy switches on that screen decide (they're saved per device).
(function(){
  var PROFILES = {
    bedroom:   { name:'Goldberg Master Bedroom TV', short:'Master Bedroom TV', privacy:{} },
    projector: { name:'Goldberg Projector',         short:'Projector',         privacy:{ props:true, calendar:true, earnings:true, money:true } }
  };
  // Hisense 43" QD6QF reports model AFTOLCBC82; any other Fire TV device is the projector stick.
  function detect(){
    var m=(location.hash||'').match(/[#&]dev=(bedroom|projector)/); // manual override: #dev=projector
    try{ if(m) localStorage.setItem('w2w.device', m[1]); var saved=localStorage.getItem('w2w.device'); if(saved && PROFILES[saved]) return saved; }catch(e){ if(m) return m[1]; }
    var ua=navigator.userAgent||'';
    if(/AFTOLCBC82/i.test(ua)) return 'bedroom';
    if(/\bAFT[A-Z0-9]+\b/.test(ua)) return 'projector';
    return 'bedroom'; // a laptop/phone browser previewing the page
  }
  var id=detect(), P=PROFILES[id];
  // privacy: this screen's default for any switch it hasn't set yet (so new switches get the right default too)
  try{ var pv=JSON.parse(localStorage.getItem('w2w.privacy')||'null')||{}, ch=localStorage.getItem('w2w.privacy')==null;
    Object.keys(P.privacy).forEach(function(k){ if(!(k in pv)){ pv[k]=P.privacy[k]; ch=true; } });
    if(ch) localStorage.setItem('w2w.privacy', JSON.stringify(pv)); }catch(e){}
  // one-time settings pushed to one screen from here (applied once per rev; the screen's own switches win afterwards)
  // Tonight (Nov 12): everything sports-quiet on the bedroom TV until midnight, then back on by itself.
  var mideve = new Date('2026-11-13T00:00:00-05:00');
  var PUSH = {
    bedroom: (Date.now() < mideve.getTime())
      ? { rev:'2026-11-12-nightmute', set:{ 'w2w.notify':{ quiet:true, games:false, props:false }, 'w2w.banners':false } }
      : { rev:'2026-11-13-wakeup',    set:{ 'w2w.notify':{ quiet:false, games:true, props:true }, 'w2w.banners':true } },
    // game night on the projector: show props, parlays and pop-ups tonight; the hourly auto re-lock
    // puts it back in full guest mode on its own after the screen goes dark
    projector: (Date.now() < mideve.getTime())
      ? { rev:'2026-11-12-gamenight', set:{ 'w2w.privacy':{ props:false, calendar:true, earnings:true, money:false }, 'w2w.notify':{ quiet:false, games:true, props:true }, 'w2w.banners':true } }
      : null
  };
  if(!PUSH[id]) delete PUSH[id];
  try{ var pu=PUSH[id]; if(pu && localStorage.getItem('w2w.pushRev')!==pu.rev){
    Object.keys(pu.set).forEach(function(k){ localStorage.setItem(k, JSON.stringify(pu.set[k])); });
    localStorage.setItem('w2w.pushRev', pu.rev); localStorage.setItem('w2w.settingsRev', String(Date.now()));
    var n=pu.set['w2w.notify'], b=pu.set['w2w.banners'];
    if(n) setTimeout(function(){ ['TV','OV'].forEach(function(o){ try{ if(window[o]&&window[o].setAlertPrefs) window[o].setAlertPrefs(n.games!==false, b!==false); }catch(e){} }); }, 1500);
  } }catch(e){}
  // Auto re-lock: once no page on this screen has been open for ~an hour (done watching, TV asleep),
  // the next wake flips this screen's private switches back on. Unlocking stays manual in 🔒 Privacy.
  var RELOCK = { bedroom:{ props:true, money:true }, projector:{ props:true, calendar:true, earnings:true, money:true } };
  try{
    var now=Date.now(), last=+localStorage.getItem('w2w.lastSeen')||0;
    if(last && now-last>60*60000){ var rl=RELOCK[id]||{}, pv2=JSON.parse(localStorage.getItem('w2w.privacy')||'{}')||{}, ch2=false;
      Object.keys(rl).forEach(function(k){ if(!pv2[k]){ pv2[k]=true; ch2=true; } });
      if(ch2){ localStorage.setItem('w2w.privacy', JSON.stringify(pv2)); localStorage.setItem('w2w.settingsRev', String(Date.now())); } }
    localStorage.setItem('w2w.lastSeen', String(now));
    setInterval(function(){ try{ localStorage.setItem('w2w.lastSeen', String(Date.now())); }catch(e){} }, 60000);
  }catch(e){}
  window.W2W_DEVICE={ id:id, name:P.name, short:P.short, defaults:P.privacy };
})();
