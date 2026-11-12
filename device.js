// Which screen is this? Same pages run on the master bedroom Hisense TV and the projector Fire Stick.
// Each screen gets its own name on the welcome screen and its own privacy defaults the first time it runs;
// after that the 🔒 Privacy switches on that screen decide (they're saved per device).
(function(){
  var PROFILES = {
    bedroom:   { name:'Goldberg Master Bedroom TV', short:'Master Bedroom TV', privacy:{} },
    projector: { name:'Goldberg Projector',         short:'Projector',         privacy:{ calendar:true, earnings:true } }
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
  try{ if(localStorage.getItem('w2w.privacy')==null) localStorage.setItem('w2w.privacy', JSON.stringify(P.privacy)); }catch(e){}
  window.W2W_DEVICE={ id:id, name:P.name, short:P.short, defaults:P.privacy };
})();
