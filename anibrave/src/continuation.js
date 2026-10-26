(() => {
  'use strict';
  if(globalThis.__aniContinue)return;
  const norm=v=>String(v||'').normalize('NFKC').toLowerCase().replace(/[^\p{L}\p{N}]+/gu,' ').trim();
  const send=value=>{try{globalThis.aniBraveNative(JSON.stringify(value));}catch(_){}};
  const identity=()=>globalThis.__aniWatch?.page?.();
  const safe=value=>{try{const u=new URL(value,location.href);return /^https?:$/.test(u.protocol)&&u.origin===location.origin&&!u.username&&!u.password?u:null;}catch(_){return null;}};
  const same=(entry,id)=>!!id&&id.kind==='watch'&&id.seasonKnown===true&&norm(entry.seriesTitle||entry.title)===norm(id.seriesTitle||id.title)&&Number(entry.season||1)===Number(id.season||1)&&Number(entry.part||0)===Number(id.part||0)&&Number(entry.episode)===Number(id.episode);
  let state=null,lastRoute='',lastApply='',resumeTimer=null,activeResume=null;
  const sought=new WeakMap(),ownSeek=new WeakMap();
  let automatic=globalThis.performance?.getEntriesByType?.('navigation')?.[0]?.type!=='back_forward',configured=false;
  function cancel(reason){clearInterval(resumeTimer);resumeTimer=null;if(activeResume){send({type:'continuationBlocked',url:activeResume.url,reason});activeResume=null;}}
  function pending(){return state?.pending&&state.pending.expires>Date.now()&&String(state.pending.targetHost||state.pending.host||'').replace(/^www\./,'')===location.hostname.replace(/^www\./,'')&&!state.privateMode?state.pending:null;}
  function route(url){if(lastRoute===location.href+'|'+url)return;lastRoute=location.href+'|'+url;send({type:'continuationNavigate',url});}
  function fillSearch(entry){
    const input=document.querySelector('form[role="search"] input, input[type="search"], input[name="keyword"], input[name="q"]');
    const form=input?.form;if(!form||String(form.method||'get').toLowerCase()!=='get'||!safe(form.action||location.href)||form.querySelector?.('input[type="password"]'))return false;
    // Request a validated GET destination rather than submitting arbitrary site code/forms.
    const url=safe(form.action||location.href),name=input.name;if(!name||!['q','keyword','query','search'].includes(name))return false;
    url.searchParams.set(name,entry.seriesTitle||entry.title);route(url.href);return true;
  }
  function update(){
    const p=pending();if(!automatic||!p||document.visibilityState==='hidden')return;const entry=p.entry||state.entry;if(!entry)return;
    const id=identity();if(same(entry,id)){
      const key=location.href+'|'+JSON.stringify([entry.title,entry.season,entry.part,entry.episode]);
      if(lastApply!==key){lastApply=key;send({type:'continuationApply',url:location.href,identity:id});}return;
    }
    const matches=[];
    for(const a of document.querySelectorAll('a[href]')){
      const u=safe(a.href);if(!u||u.href===location.href)continue;
      const label=(a.textContent||a.getAttribute('aria-label')||'').trim();
      if(id?.kind==='search'&&norm(label)===norm(entry.seriesTitle||entry.title)&&/\/(series|anime)\//.test(u.pathname))matches.push(u.href);
      if(id&&norm(id.seriesTitle||id.title)===norm(entry.seriesTitle||entry.title)&&/\/watch\//.test(u.pathname)){
        const episode=Number(a.getAttribute('data-number'))||Number((label.match(/\bS\d+\s*E(\d+)\b/i)||label.match(/\b(?:episode\s*|ep\.?\s*|e)(\d+)\b/i))?.[1]||0);
        const season=Number(a.getAttribute('data-season'))||Number(label.match(/\b(?:season\s*|s)(\d+)\s*(?:e|episode)/i)?.[1]||0);
        const part=Number(a.getAttribute('data-part'))||Number(label.match(/\bpart\s*(\d+)/i)?.[1]||id.part||0);
        if(episode===Number(entry.episode)&&part===Number(entry.part||0)&&(season===Number(entry.season||1)||(!season&&id.seasonKnown===true&&Number(id.season)===Number(entry.season||1))))matches.push(u.href);
      }
    }
    const unique=[...new Set(matches)];if(unique.length===1)route(unique[0]);
  }
  function seek(video,entry){
    const watch=globalThis.__aniWatch;if(!watch?.main(video)||watch.ad(video)||!Number.isFinite(video.duration)||video.duration<60||video.readyState<2)return false;
    const key=JSON.stringify([entry.title,entry.season,entry.part,entry.episode,entry.position]);if(sought.get(video)===key)return true;
    const position=Math.max(0,Number(entry.position)||0),duration=Number(entry.duration)||0;
    if(position>=video.duration-3||(duration>0&&Math.abs(video.duration-duration)>Math.max(30,duration*.1)))return false;
    if(position>0){let seekable=false;for(let i=0;i<video.seekable.length;i++)if(position>=video.seekable.start(i)&&position<=video.seekable.end(i))seekable=true;if(!seekable)return false;}
    try{ownSeek.set(video,position);video.currentTime=position;if(Math.abs(video.currentTime-position)>1)return false;sought.set(video,key);send({type:'continuationApplied',url:location.href,position});return true;}catch(_){return false;}
  }
  function resume(entry){
    cancel('Replaced by a newer resume request.');if(!entry||state?.privateMode)return;
    // A new native request must seek even when SPA history retained the same player.
    for(const video of globalThis.__aniWatch?.media||[])sought.delete(video);
    const url=location.href,until=Date.now()+60000;activeResume={url};
    const attempt=()=>{
      if(!activeResume)return true;
      if(document.visibilityState==='hidden'){cancel('Resume paused while this page is hidden.');return true;}
      if(location.href!==url||Date.now()>until){cancel(location.href!==url?'navigation':'Player did not permit resume; use the saved position manually.');return true;}
      let done=false;for(const video of globalThis.__aniWatch?.media||[])if(video.isConnected!==false&&seek(video,entry))done=true;
      if(done){clearInterval(resumeTimer);resumeTimer=null;activeResume=null;}return done;
    };
    if(!attempt())resumeTimer=setInterval(attempt,500);
  }
  const on=(name,handler)=>document.addEventListener?.(name,handler,true);
  for(const name of ['pointerdown','keydown'])on(name,event=>{if(event.isTrusted)cancel('Resume cancelled after user interaction.');});
  on('seeking',event=>{const v=event.target;if(event.isTrusted&&!(ownSeek.has(v)&&Math.abs(v.currentTime-ownSeek.get(v))<=1))cancel('Resume cancelled after the position changed.');});
  on('seeked',event=>ownSeek.delete(event.target));
  on('visibilitychange',()=>{if(document.visibilityState==='hidden')cancel('Resume paused while this page is hidden.');});
  globalThis.__aniContinue={configure(next){if(configured&&next?.pending&&next.pending.expires!==state?.pending?.expires)automatic=true;configured=true;if(JSON.stringify(state?.pending?.entry)!==JSON.stringify(next?.pending?.entry))lastApply='';state=next;update();},update,inspect:identity,resume,search:fillSearch,act(action){if(action==='search'){const p=pending();return !!p&&fillSearch(p.entry||state.entry);}return false;}};
  window.addEventListener('pagehide',()=>cancel('navigation'));
  // History traversal is user navigation, not permission to route forward again.
  const pauseForHistory=()=>{automatic=false;lastRoute='';lastApply='';cancel('navigation');};
  window.addEventListener('popstate',pauseForHistory);
  window.addEventListener('pageshow',event=>{if(event.persisted)pauseForHistory();});
})();
