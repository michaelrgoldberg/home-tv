// Shared "how much live score do I see" settings, used by the Sports tab, Gamecast and the ticker overlay.
//  w2w.ticker  = 'off' | 'all' | 'mine' | 'props'   bottom-of-screen ticker while watching other apps
//  w2w.tkSpeed = 'slow' | 'normal' | 'fast'
//  w2w.gcSize  = 's' | 'm' | 'l'                   Gamecast panel size
//  w2w.gcMulti = 1 | 2 | 4                         games shown at once in Gamecast
(function(){
  function g(k,d){ try{ var v=localStorage.getItem(k); return v==null?d:JSON.parse(v); }catch(e){ return d; } }
  function s(k,v){ try{ localStorage.setItem(k, JSON.stringify(v)); localStorage.setItem('w2w.settingsRev', String(Date.now())); }catch(e){} }
  var T=[['off','Off'],['all','All games'],['mine','My games tonight'],['props','My games + props']];
  var SP=[['slow','Slow'],['normal','Normal'],['fast','Fast']];
  var SZ=[['s','Small'],['m','Medium'],['l','Large']];
  var MU=[[1,'1 game'],[2,'2 games'],[4,'4 games']];
  function idx(list,v){ for(var i=0;i<list.length;i++) if(list[i][0]===v) return i; return 0; }
  function nat(fn,arg){ var ok=false; ['OV','TV'].forEach(function(o){ try{ if(window[o]&&window[o][fn]){ window[o][fn](arg); ok=true; } }catch(e){} }); return ok; }
  var V={
    ticker:function(){ var v=g('w2w.ticker','off'); return idx(T,v)>=0&&T[idx(T,v)][0]===v?v:'off'; },
    tickerLabel:function(){ return T[idx(T,V.ticker())][1]; },
    cycleTicker:function(){ var v=T[(idx(T,V.ticker())+1)%T.length][0]; s('w2w.ticker',v); nat('setTicker',v); return v; },
    speed:function(){ return g('w2w.tkSpeed','normal'); },
    speedLabel:function(){ return SP[idx(SP,V.speed())][1]; },
    cycleSpeed:function(){ var v=SP[(idx(SP,V.speed())+1)%SP.length][0]; s('w2w.tkSpeed',v); return v; },
    size:function(){ return g('w2w.gcSize','m'); },
    sizeLabel:function(){ return SZ[idx(SZ,V.size())][1]; },
    cycleSize:function(){ var v=SZ[(idx(SZ,V.size())+1)%SZ.length][0]; s('w2w.gcSize',v); nat('setGamecastSize',v); return v; },
    multi:function(){ var v=+g('w2w.gcMulti',1); return (v===2||v===4)?v:1; },
    multiLabel:function(){ return MU[idx(MU,V.multi())][1]; },
    cycleMulti:function(){ var v=MU[(idx(MU,V.multi())+1)%MU.length][0]; s('w2w.gcMulti',v); return v; },
    // does the installed TV app know how to draw the ticker / resize Gamecast? (app 2.7+)
    nativeOk:function(){ try{ if(window.TV&&TV.version) return TV.version()>=8; if(window.OV) return !!OV.setTicker; }catch(e){} return false; },
    // push the saved choices to the TV app (on page load, in case they were changed elsewhere)
    sync:function(){ nat('setTicker',V.ticker()); nat('setGamecastSize',V.size()); }
  };
  window.W2W_VIEW=V;
})();
