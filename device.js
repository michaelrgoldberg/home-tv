// Which screen is this? Same pages run on the master bedroom Hisense TV and the projector Fire Stick.
// Each screen gets its own name on the welcome screen and its own privacy defaults the first time it runs;
// after that the 🔒 Privacy switches on that screen decide (they're saved per device).
(function(){
  var PROFILES = {
    bedroom:   { name:'Goldberg Master Bedroom TV', short:'Master Bedroom TV', privacy:{} },
    projector: { name:'Goldberg Projector',         short:'Projector',         privacy:{ props:true, calendar:true, earnings:true, money:true } },
    desktop:   { name:'Goldberg Desktop',           short:'Desktop',           privacy:{} }  // a computer browser: keys unlock it, the hourly re-lock closes it
  };
  // Hisense 43" QD6QF reports model AFTOLCBC82; any other Fire TV device is the projector stick.
  function detect(){
    var m=(location.hash||'').match(/[#&]dev=(bedroom|projector)/); // manual override: #dev=projector
    try{ if(m) localStorage.setItem('w2w.device', m[1]); var saved=localStorage.getItem('w2w.device'); if(saved && PROFILES[saved]) return saved; }catch(e){ if(m) return m[1]; }
    var ua=navigator.userAgent||'';
    if(/AFTOLCBC82/i.test(ua)) return 'bedroom';
    if(/\bAFT[A-Z0-9]+\b/.test(ua)) return 'projector';
    return 'desktop'; // a laptop/phone browser
  }
  try{ var mk=(location.hash||'').match(/priv=([^&]+)/); if(mk) localStorage.setItem('w2w.key.priv', decodeURIComponent(mk[1])); }catch(e){}
  // One place every page asks for the data key. Order: this tab's unlocked key (PIN flow on
  // computers), a key handed to a child window (#sk=), then the device's own stored key (TVs).
  window.W2W_KEYV=function(){
    try{ var sk=sessionStorage.getItem('w2w.key.sess'); if(sk) return sk; }catch(e){}
    try{ var m=(location.hash||'').match(/[#&]sk=([^&]+)/); if(m) return decodeURIComponent(m[1]); }catch(e){}
    try{ return localStorage.getItem('w2w.key.priv')||''; }catch(e){}
    return '';
  };
  var id=detect(), P=PROFILES[id];
  // privacy: this screen's default for any switch it hasn't set yet (so new switches get the right default too)
  try{ var pv=JSON.parse(localStorage.getItem('w2w.privacy')||'null')||{}, ch=localStorage.getItem('w2w.privacy')==null;
    Object.keys(P.privacy).forEach(function(k){ if(!(k in pv)){ pv[k]=P.privacy[k]; ch=true; } });
    if(ch) localStorage.setItem('w2w.privacy', JSON.stringify(pv)); }catch(e){}
  // one-time settings pushed to one screen from here (applied once per rev; the screen's own switches win afterwards)
  // Tonight (Nov 12): everything sports-quiet on the bedroom TV until midnight, then back on by itself.
  var mideve = new Date('2026-11-13T00:00:00-05:00');
  var PUSH = {
    // bedroom TV (Nov 15 evening): game night — props visible for the ticker and pop-ups;
    // earnings and wager amounts stay behind the privacy switch
    bedroom: { rev:'2026-11-19-tnf', set:{ 'w2w.ticker':'mine', 'w2w.snooze':{}, 'w2w.privacy':{ props:false, calendar:false, earnings:true, money:true }, 'w2w.popPos':'bottom', 'w2w.notify':{ quiet:false, games:false, props:true }, 'w2w.banners':false } },
    projector: null
  };
  if(!PUSH[id]) delete PUSH[id];
  try{ var pu=PUSH[id]; if(pu && localStorage.getItem('w2w.pushRev')!==pu.rev){
    Object.keys(pu.set).forEach(function(k){ localStorage.setItem(k, JSON.stringify(pu.set[k])); });
    localStorage.setItem('w2w.pushRev', pu.rev); localStorage.setItem('w2w.settingsRev', String(Date.now()));
    var n=pu.set['w2w.notify'], b=pu.set['w2w.banners'], tk=pu.set['w2w.ticker'];
    if(n) setTimeout(function(){ ['TV','OV'].forEach(function(o){ try{ if(window[o]&&window[o].setAlertPrefs) window[o].setAlertPrefs(n.games!==false, b!==false); }catch(e){} }); }, 1500);
    if(tk!==undefined) setTimeout(function(){ ['TV','OV'].forEach(function(o){ try{ if(window[o]&&window[o].setTicker) window[o].setTicker(tk); }catch(e){} }); }, 1600); // a push is explicit, so it may turn the ticker on or off
  } }catch(e){}
  // Auto re-lock: once no page on this screen has been open for ~an hour (done watching, TV asleep),
  // the next wake flips this screen's private switches back on. Unlocking stays manual in 🔒 Privacy.
  var RELOCK = { bedroom:{ props:true, money:true }, projector:{ props:true, calendar:true, earnings:true, money:true },
                 desktop:{ props:true, calendar:true, earnings:true, money:true } };
  try{
    var now=Date.now(), last=+localStorage.getItem('w2w.lastSeen')||0;
    if(last && now-last>60*60000){ var rl=RELOCK[id]||{}, pv2=JSON.parse(localStorage.getItem('w2w.privacy')||'{}')||{}, ch2=false;
      try{ sessionStorage.removeItem('w2w.key.sess'); }catch(e){}  // a PIN-opened key closes with the relock
      Object.keys(rl).forEach(function(k){ if(!pv2[k]){ pv2[k]=true; ch2=true; } });
      if(ch2){ localStorage.setItem('w2w.privacy', JSON.stringify(pv2)); localStorage.setItem('w2w.settingsRev', String(Date.now())); } }
    localStorage.setItem('w2w.lastSeen', String(now));
    setInterval(function(){ try{ localStorage.setItem('w2w.lastSeen', String(Date.now())); }catch(e){} }, 60000);
  }catch(e){}
  window.W2W_DEVICE={ id:id, name:P.name, short:P.short, defaults:P.privacy };
})();
