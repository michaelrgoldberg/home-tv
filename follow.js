/* W2W_FOLLOW — keeps the TV's starred-game list in sync with the encrypted follow list.
   Mike tells Claude "track the Bills" -> Claude adds {lg, team} to the encrypted data file ->
   this resolves the team to today's ESPN event id and stars it on the device.
   Games that prop / parlay legs are in get auto-starred too (one less thing to do by hand).
   A game Mike manually un-stars is remembered (w2w.autoStar) and is not re-added.
   Star levels (w2w.starLevel[id]): 1 = key moments (default), 2 = every score. */
(function(){
  'use strict';
  var ESPN='https://site.web.api.espn.com/apis/site/v2/sports/';
  function ls(k,d){ try{ var v=localStorage.getItem(k); return v==null?d:JSON.parse(v); }catch(e){ return d; } }
  function st(k,v){ try{ localStorage.setItem(k, JSON.stringify(v)); }catch(e){} }
  function b64(s){ return Uint8Array.from(atob(s),function(c){return c.charCodeAt(0);}); }

  function loadData(){
    var key=''; try{ key=localStorage.getItem('w2w.key.priv')||''; }catch(e){}
    if(!key||!window.crypto||!crypto.subtle) return Promise.resolve(null);
    return fetch('props.json?'+Date.now()).then(function(r){ if(!r.ok) throw 0; return r.json(); }).then(function(p){
      return crypto.subtle.importKey('raw',b64(key),'AES-GCM',false,['decrypt']).then(function(k){ return crypto.subtle.decrypt({name:'AES-GCM',iv:b64(p.iv)},k,b64(p.ct)); });
    }).then(function(buf){ var a=JSON.parse(new TextDecoder().decode(buf)); if(Array.isArray(a)) a={props:a,parlays:[],follow:[]}; return a; }).catch(function(){ return null; });
  }
  var sb={};
  function scoreboard(lg){ if(sb[lg]&&Date.now()-sb[lg].t<60000) return Promise.resolve(sb[lg].e);
    return fetch(ESPN+lg+'/scoreboard').then(function(r){return r.json();}).then(function(d){ var e=d.events||[]; sb[lg]={t:Date.now(),e:e}; return e; }).catch(function(){ return []; }); }
  function teamMatch(tm, want){ want=String(want||'').toLowerCase(); if(!want) return false;
    var f=[tm.abbreviation,tm.shortDisplayName,tm.displayName,tm.name,tm.location,tm.nickname].map(function(x){return String(x||'').toLowerCase();});
    return f.some(function(x){ return x && (x===want || x.indexOf(want)>=0); }); }
  // pick today's event for a team: live first, then upcoming, then final
  function resolve(lg, team){ return scoreboard(lg).then(function(evs){
    var best=null, rank={in:0,pre:1,post:2};
    evs.forEach(function(e){ var c=(e.competitions&&e.competitions[0])||{}; var cs=c.competitors||[];
      if(cs.some(function(x){ return teamMatch(x.team||{}, team); })){ var s=((e.status||{}).type||{}).state||'pre'; if(!best||rank[s]<rank[best.s]) best={id:String(e.id), s:s}; } });
    return best; }); }

  function wanted(data){ // -> [{lg, team, level}]
    var out=[], seen={};
    function add(lg,team,level){ if(!lg||!team) return; var k=lg+'|'+String(team).toLowerCase(); if(seen[k]) return; seen[k]=1; out.push({lg:lg,team:team,level:level||1}); }
    (data.follow||[]).forEach(function(f){ add(f.lg,f.team,f.level); });
    (data.props||[]).forEach(function(p){ add(p.lg,p.team,1); });
    (data.parlays||[]).forEach(function(pl){ (pl.legs||[]).forEach(function(l){ add(l.lg,l.team,1); }); });
    return out;
  }

  function sync(){
    return loadData().then(function(data){
      if(!data) return {added:0};
      var want=wanted(data); if(!want.length) return {added:0};
      var explicit={}; (data.follow||[]).forEach(function(f){ explicit[(f.lg+'|'+String(f.team).toLowerCase())]=1; });
      return Promise.all(want.map(function(w){ return resolve(w.lg,w.team).then(function(r){ return r?{lg:w.lg,id:r.id,level:w.level,team:w.team,exp:!!explicit[(w.lg+'|'+String(w.team).toLowerCase())]}:null; }); })).then(function(res){
        var stars=ls('w2w.starred',[]); if(!Array.isArray(stars)) stars=[];
        var auto=ls('w2w.autoStar',{}); var lvl=ls('w2w.starLevel',{}); var added=0;
        res.forEach(function(r){ if(!r) return;
          var has=stars.some(function(s){ return String(s.id)===r.id; });
          if(!has && !auto[r.id]){ stars.push({lg:r.lg,id:r.id}); added++; }
          auto[r.id]=1; if(r.level>1) lvl[r.id]=r.level;
        });
        var fg=[]; res.forEach(function(r){ if(r&&r.exp&&fg.indexOf(r.id)<0) fg.push(r.id); });
        st('w2w.followGames', fg);
        st('w2w.starred',stars); st('w2w.autoStar',auto); st('w2w.starLevel',lvl);
        return {added:added, data:data};
      });
    });
  }
  window.W2W_FOLLOW={ sync:sync, loadData:loadData, teamMatch:teamMatch };
})();
