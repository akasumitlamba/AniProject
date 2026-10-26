(() => {
  const norm=t=>String(t||'').normalize('NFKC').toLowerCase().replace(/[^\p{L}\p{N}]+/gu,' ').trim();
  const eid=e=>`${norm(e.title)}|s${e.season||1}|e${e.episode}`;
  const supported=()=>/(^|\.)(crunchyroll\.com|hianime\.to|hianimez\.to|anikototv\.to)$/.test(location.hostname);
  const safe=value=>{try{const u=new URL(value,location.href);return /^https?:$/.test(u.protocol)?u:null;}catch{return null;}};
  const same=(a,b)=>b&&b.seasonKnown!==false&&norm(a.title)===norm(b.title)&&Number(a.season||1)===Number(b.season||1)&&Number(a.episode)===Number(b.episode);
  const clock=seconds=>{const n=Math.max(0,Math.floor(Number(seconds)||0));return `${Math.floor(n/60)}:${String(n%60).padStart(2,'0')}`;};
  globalThis.AniContinue = function({send,identity,media,isMain,adBlocked}) {
    let state=null,signature='',hostElement=null,applying=false,notice='',lastRoute='',searchFilled='',resumeTimer=0,hideTimer=0;
    const sought=new WeakMap();
    function remove(){clearTimeout(hideTimer);hostElement?.remove();hostElement=null;signature='';}
    function selected(){
      if(!state||state.privateMode)return null;
      const pending=state.continuation;
      if(pending?.host===state.host&&pending.expires>Date.now()&&state.history.some(e=>e.key===pending.entry.key))return pending.entry;
      const id=identity();
      if(id)return state.history.find(e=>norm(e.title)===norm(id.title)&&Number(e.season||1)===Number(id.season||1));
      // Only a site's landing page gets a cross-site suggestion. Search/results need an explicit handoff.
      if(!/^\/(?:[a-z]{2}(?:-[a-z]{2})?\/)?(?:home\/?)?$/i.test(location.pathname))return null;
      return (supported()?state.history:state.history.filter(e=>e.host===state.host))[0];
    }
    async function update(){const next=await send({type:'state'});if(next){state=next;render();}}
    async function begin(entry){notice='';const result=await send({type:'continue',key:entry.key});if(result){state.continuation=result.pending;render(true);if(!result.destination)fillSearch(entry);}else{notice='Could not start continuation. Reopen Episode memory and try again.';render(true);}}
    function fillSearch(entry){
      if(searchFilled===location.href)return;
      const input=document.querySelector('form[role="search"] input, input[type="search"], input[name="keyword"], input[name="q"]');
      const form=input?.form;
      if(form && String(form.method||'get').toLowerCase()==='get' && safe(form.action||location.href)?.origin===location.origin){
        searchFilled=location.href;
        const setter=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')?.set;
        if(setter)setter.call(input,entry.title);else input.value=entry.title;
        input.dispatchEvent(new Event('input',{bubbles:true}));input.dispatchEvent(new Event('change',{bubbles:true}));
        form.requestSubmit();
      }else{notice='Search this site for the title below. This card will stay with you.';render(true);}
    }
    function locate(entry){
      if(!state?.continuation || !supported() || lastRoute===location.href)return;
      const id=identity();let matches=[];
      for(const a of document.querySelectorAll('a[href]')){
        const url=safe(a.href);if(!url||url.origin!==location.origin||url.href===location.href)continue;
        const text=(a.textContent||a.getAttribute('aria-label')||'').trim();
        if(!id && /\/search/.test(location.pathname) && norm(text)===norm(entry.title) && /\/(series|watch)\//.test(url.pathname))matches.push(url.href);
        if(id && norm(id.title)===norm(entry.title) && !same(entry,id) && /\/watch\//.test(url.pathname)){
          const episode=Number(a.getAttribute('data-number'))||Number((text.match(/\b(?:episode\s*|ep\.?\s*|e)(\d+)\b/i)||[])[1]||0);
          const season=Number(a.getAttribute('data-season'))||Number((text.match(/\bS(?:eason\s*)?(\d+)\s*(?:E|Episode)/i)||[])[1]||0);
          // A label with no season is safe only inside the same explicitly identified season.
          if(episode===Number(entry.episode) && (season===Number(entry.season||1)||(!season&&id.seasonKnown!==false&&Number(id.season||1)===Number(entry.season||1))))matches.push(url.href);
        }
      }
      matches=[...new Set(matches)];
      if(matches.length===1){lastRoute=location.href;location.assign(matches[0]);}
    }
    function trySeek(v,entry){
      if(!isMain(v)||adBlocked(v)||!Number.isFinite(v.duration)||v.duration<60||sought.get(v)===eid(entry))return false;
      const position=Math.max(0,Number(entry.position)||0);
      // Do not seek to the end of a shorter edition or to an ad/preview.
      if((entry.duration>0&&Math.abs(v.duration-entry.duration)>Math.max(30,entry.duration*.1))||position>=v.duration-3){notice='This version has a different duration. Choose the position manually.';return false;}
      try{if(v.readyState<2 || (position>0&&!v.seekable.length))return false;v.currentTime=position;if(Math.abs(v.currentTime-position)>1)return false;sought.set(v,eid(entry));return true;}catch{return false;}
    }
    function receive(entry){
      clearInterval(resumeTimer);
      let done=false;for(const v of media)if(v.tagName==='VIDEO'&&trySeek(v,entry))done=true;
      if(done)return;
      const until=Date.now()+60000,url=location.href;
      resumeTimer=setInterval(()=>{if(Date.now()>until||location.href!==url){clearInterval(resumeTimer);return;}for(const v of media)if(v.tagName==='VIDEO'&&trySeek(v,entry)){clearInterval(resumeTimer);break;}},500);
    }
    async function apply(entry,confirmed=false){
      if(applying || state?.continuation?.applied)return;
      if(!confirmed&&!same(entry,identity()))return;
      applying=true;
      await send({type:'applyContinuation',identity:identity()});
      applying=false;
      // send returns undefined on a successful command. Refresh reads its persisted acknowledgement.
      await update();
    }
    function render(force=false){
      if(window!==top || !document.body)return;
      const entry=selected();if(!entry){remove();return;}
      const pending=state.continuation?.host===state.host&&state.continuation.expires>Date.now();
      if(!pending&&state.dismissals?.[state.host+'|'+eid(entry)]){remove();return;}
      const sig=eid(entry)+'|'+(pending?'pending':'offer')+'|'+!!state.continuation?.applied+'|'+notice;
      if(sig===signature&&!force&&hostElement?.isConnected){if(pending)locate(entry);if(pending&&same(entry,identity())&&!state.continuation.applied)apply(entry);return;}
      remove();signature=sig;
      const host=document.createElement('div');host.id='anibrave-memory-card';host.style.cssText='position:fixed;right:14px;bottom:14px;z-index:2147483647;max-width:calc(100vw - 28px)';
      const root=host.attachShadow({mode:'closed'});const style=document.createElement('style');style.textContent=':host{all:initial}aside{box-sizing:border-box;width:290px;max-width:calc(100vw - 28px);padding:14px;border:1px solid #51473a;border-radius:10px;background:#211f1c;color:#f5f2ec;font:12px/1.5 system-ui;box-shadow:0 6px 28px #0006}header{display:flex;justify-content:space-between;align-items:center;color:#cdbb9f;font-size:11px}strong{display:block;margin-top:8px;font-size:13px}p{margin:4px 0;color:#c7bfb3}nav{display:flex;gap:7px;flex-wrap:wrap;margin-top:10px}button,a{font:12px system-ui;padding:7px 9px;border:1px solid #61513c;border-radius:6px;color:#f5f2ec;background:#302a22;cursor:pointer;text-decoration:none}header button{border:0;background:transparent;padding:0 3px;font-size:19px}small{display:block;color:#c7bfb3;margin-top:8px;font-size:11px}button:focus-visible,a:focus-visible{outline:2px solid #e2b779;outline-offset:2px}';
      const card=document.createElement('aside');card.setAttribute('aria-label','Continue watching');const header=document.createElement('header');header.textContent=pending?'ANIBRAVE · CONTINUE HERE':'ANIBRAVE · LAST WATCHED';
      const close=document.createElement('button');close.textContent='×';close.setAttribute('aria-label','Dismiss reminder');close.onclick=async()=>{clearInterval(resumeTimer);state.dismissals={...state.dismissals,[state.host+'|'+eid(entry)]:Date.now()};state.continuation=null;remove();await send({type:'dismissReminder',id:eid(entry)});};header.append(close);
      const title=document.createElement('strong');title.textContent=entry.title;
      const detail=document.createElement('p');detail.textContent=`Season ${entry.season||1} · Episode ${entry.episode} · ${clock(entry.position)}`;
      const nav=document.createElement('nav');const button=document.createElement('button');
      button.textContent=pending?(state.continuation.applied?'Done':same(entry,identity())?'Resume here':location.pathname.includes('/watch/')?'Confirm episode':'Search here'):'Continue here';
      button.onclick=()=>{if(!pending)begin(entry);else if(state.continuation.applied){send({type:'dismissReminder',id:eid(entry)});state.dismissals={...state.dismissals,[state.host+'|'+eid(entry)]:Date.now()};state.continuation=null;remove();}else if(same(entry,identity()))apply(entry);else if(location.pathname.includes('/watch/')){
        // Explicit confirmation covers aliases/season labels the site does not expose.
        notice='Confirm this is the saved season and episode before resuming.';const confirm=document.createElement('button');confirm.textContent='This is the right episode — resume';confirm.onclick=()=>apply(entry,true);nav.append(confirm);renderNotice();
      }else begin(entry);};
      nav.append(button);const old=safe(entry.url);if(old){const a=document.createElement('a');a.textContent='Open previous site';a.href=old.href;a.onclick=async event=>{event.preventDefault();const result=await send({type:'returnPrevious',key:entry.key});if(!result) {notice='Could not open the saved episode.';render(true);}};nav.append(a);}
      const note=document.createElement('small');function renderNotice(){note.textContent=notice || (pending?(state.continuation.applied?'Resume requested. Press Play when ready; if this player blocks seeking, use the saved time above.':'Finding the saved episode. You can search manually; this card stays for 30 minutes.'):`Last watched on ${entry.host}`);}renderNotice();
      card.append(header,title,detail,nav,note);root.append(style,card);document.body.append(host);hostElement=host;
      if(!pending)hideTimer=setTimeout(()=>close.onclick(),12000);
      if(pending){locate(entry);if(same(entry,identity())&&!state.continuation.applied)apply(entry);}
    }
    return {setState(next){state=next;render();},render,receive,update};
  };
})();
