(() => {
  'use strict';
  if (globalThis.__aniBrave) return;
  const media = new Set(), roots = new WeakSet(), states = new WeakMap();
  let rate = 1, revision = 0, configured = false, serial = 0, inputUntil = 0;
  let lastScroll = 0, lastReveal = 0, bufferSeconds = 0, bufferGuard = false;
  const near = (a,b) => Math.abs(a-b) < .001;
  const valid = n => Number.isFinite(n) && n >= .25 && n <= 4;
  const now = () => Date.now();
  const send = payload => { try { globalThis.aniBraveNative(JSON.stringify(payload)); } catch (_) {} };
  function ad(video) {
    for (let p=video; p; p=p.parentElement || p.getRootNode?.()?.host)
      if (p.classList?.contains('ad-showing') || p.classList?.contains('ad-interrupting')) return true;
    return false;
  }
  function main(video) {
    if (!video?.isConnected || ad(video)) return false;
    const area = (video.clientWidth || 0)*(video.clientHeight || 0);
    return ![...media].some(v => v!==video && v.isConnected && !ad(v) && (v.clientWidth||0)*(v.clientHeight||0)>area);
  }
  function state(video) {
    if (!states.has(video)) {
      const id=String(++serial); video.setAttribute?.('data-anibrave-app-media',id);
      states.set(video,{id, attempts:0, window:now(), applied:0, loading:0, pending:0});
    }
    return states.get(video);
  }
  function ahead(video) {
    try { for(let i=0;i<video.buffered.length;i++)if(video.buffered.start(i)<=video.currentTime+.1 && video.buffered.end(i)>video.currentTime)return video.buffered.end(i)-video.currentTime; } catch(_){}
    return 0;
  }
  function cancelGuard(video) { const s=states.get(video);if(s)s.guard=null; }
  function buffer(video,s,reset) {
    if(!main(video))return;
    if(document.visibilityState==='hidden'&&video.paused){cancelGuard(video);return;}
    const time=now();
    if(video.duration===Infinity){
      cancelGuard(video);
      if(video.preload==='auto'&&s.originalPreload!==undefined)video.preload=s.originalPreload;
      if(reset||s.bufferValue!==0){s.bufferValue=0;window.postMessage?.({source:'anibrave-app',type:'set-buffer',id:++serial,media:s.id,seconds:0},'*');}
      return;
    }
    if(s.originalPreload===undefined)s.originalPreload=video.preload;
    if(bufferSeconds>0){if(video.preload!=='auto')video.preload='auto';}
    else if(video.preload==='auto'&&s.originalPreload!==undefined)video.preload=s.originalPreload;
    if(reset||s.bufferValue!==bufferSeconds||time-(s.bufferAt||0)>5000){
      s.bufferValue=bufferSeconds;s.bufferAt=time;
      window.postMessage?.({source:'anibrave-app',type:'set-buffer',id:++serial,media:s.id,seconds:bufferSeconds},'*');
    }
    const g=s.guard;if(!g)return;
    if(!bufferGuard||document.visibilityState==='hidden'||video.ended||video.seeking||!video.isConnected||video.currentSrc!==g.source||Math.abs(video.currentTime-g.position)>.5){cancelGuard(video);return;}
    const target=Math.min(30,15*Math.max(1,rate),Math.max(0,video.duration-video.currentTime));
    const available=ahead(video);
    if(available>(g.available||0)+.25){g.available=available;g.grewAt=time;}
    const plateau=available>=Math.min(3,target)&&time-g.grewAt>=3500;
    if(available>=target || plateau || time-g.at>=20000){
      cancelGuard(video);s.guardCooldown=time+30000;
      // Resume only the pause owned by this guard; trusted interaction cancels it.
      if(video.paused&&ahead(video)>=Math.min(3,target))try{Promise.resolve(video.play()).catch(()=>{});}catch(_){}
      else if(video.paused)send({type:'bufferGuard',waiting:false});
    }
  }
  function waitForBuffer(video) {
    if(!bufferGuard||!main(video)||video.paused||video.seeking||!Number.isFinite(video.duration)||video.duration<=0||ahead(video)>.25)return;
    const s=state(video);if(s.guard||now()<(s.guardCooldown||0)||typeof video.pause!=='function')return;
    s.guard={source:video.currentSrc,position:video.currentTime,at:now(),grewAt:now(),available:0,ownPause:true};
    video.pause();send({type:'bufferGuard',waiting:true});
  }
  function apply(video, reset=false) {
    if (!video?.isConnected) {media.delete(video);return;}
    if (!configured || ad(video)) return;
    const s=state(video), time=now();
    buffer(video,s,reset);
    if(reset){clearTimeout(s.pending);s.pending=0;}else if(s.pending)return;
    if (reset || time-s.window>5000) {s.attempts=0;s.window=time;}
    if (near(video.playbackRate,rate) && !reset) return;
    if (++s.attempts>6) {
      if (s.attempts===7) send({type:'rateRejected'});
      return;
    }
    s.applied=time;
    // MAIN-world player API updates the site's menu where supported. The media
    // fallback remains authoritative above a site's menu cap.
    window.postMessage?.({source:'anibrave-app',type:'set-rate',id:++serial,media:s.id,rate},'*');
    try {video.defaultPlaybackRate=rate;if(!near(video.playbackRate,rate))video.playbackRate=rate;}catch(_){}
  }
  function changed(video) {
    if (!configured || !main(video) || !valid(video.playbackRate) || near(video.playbackRate,rate)) return;
    const s=state(video), time=now(), chosen=video.playbackRate, expected=revision;
    // Only a real player-control interaction can replace a saved choice. Startup,
    // autoplay, episode/source changes and scripted menu clamps reapply it.
    if (time<inputUntil) {
      clearTimeout(s.pending);
      s.pending=setTimeout(()=>{
        s.pending=0;
        if (expected!==revision || !main(video) || !near(video.playbackRate,chosen)) return;
        rate=chosen;
        send({type:'nativeRate',rate,revision:expected});
        for(const v of media)apply(v,true);
      },120);
    } else apply(video);
  }
  function track(video, lifecycle) {
    if (!video || !/^(VIDEO|AUDIO)$/.test(video.tagName))return;
    media.add(video);const s=state(video);
    if(lifecycle){s.loading=now();clearTimeout(s.pending);s.pending=0;apply(video,true);}else apply(video);
  }
  function discover(root) {
    if (!root) return;
    if (roots.has(root)) {
      for (const element of root.querySelectorAll('*')) if (element.shadowRoot) discover(element.shadowRoot);
      return;
    }
    roots.add(root);
    for(const name of ['loadstart','loadedmetadata','playing','emptied','durationchange','ratechange','waiting','pause','seeking'])root.addEventListener(name,event=>{
      const video=event.composedPath?.()[0] || event.target;
      if(!video || !/^(VIDEO|AUDIO)$/.test(video.tagName))return;
      if(name==='waiting'){waitForBuffer(video);return;}
      if(name==='pause'){const s=states.get(video);if(s?.guard){if(s.guard.ownPause)s.guard.ownPause=false;else cancelGuard(video);}return;}
      if(name==='seeking'||name==='loadstart'||name==='emptied'||name==='playing')cancelGuard(video);
      if(name==='ratechange'){media.add(video);changed(video);}else track(video,name!=='playing');
    },true);
    for(const v of root.querySelectorAll('video,audio'))track(v,false);
    for(const element of root.querySelectorAll('*'))if(element.shadowRoot)discover(element.shadowRoot);
    if(typeof MutationObserver!=='undefined')new MutationObserver(records=>{
      for(const record of records)for(const node of record.addedNodes || []) {
        if(/^(VIDEO|AUDIO)$/.test(node.tagName))track(node,false);
        if(node.shadowRoot)discover(node.shadowRoot);
        if(node.querySelectorAll){for(const v of node.querySelectorAll('video,audio'))track(v,false);for(const e of node.querySelectorAll('*'))if(e.shadowRoot)discover(e.shadowRoot);}
      }
    }).observe(root,{childList:true,subtree:true});
  }
  for(const name of ['pointerdown','keydown'])document.addEventListener(name,event=>{
    if(!event.isTrusted)return;
    for(const video of media)cancelGuard(video);
    // Exclude ordinary page scrolling/navigation from native rate selection.
    const target=event.composedPath?.()[0] || event.target;
    const control=target?.closest?.('button,[role="button"],[role="menuitem"],[role="menuitemradio"],[role="slider"],[class*="speed"],[data-testid*="speed"]');
    const label=(control?.textContent || '')+' '+(control?.getAttribute?.('aria-label') || '');
    const numericChoice=control && ['menuitem','menuitemradio','option'].includes(control.getAttribute?.('role')) && valid(Number((control.textContent || '').trim()));
    if(numericChoice || (control && /speed|\bnormal\b|\b\d+(?:\.\d+)?\s*[x×]/i.test(label)) || (name==='keydown' && ['<','>'].includes(event.key)))inputUntil=now()+1800;
  },true);
  globalThis.__aniBrave = {
    configure(value,stamp=0,seconds=0,guard=false) {
      if(!valid(value)||!Number.isSafeInteger(stamp)||stamp<revision)return;
      if(!Number.isInteger(seconds)||seconds<0||seconds>600||typeof guard!=='boolean')return;
      const fresh=!configured||!near(rate,value)||stamp!==revision||bufferSeconds!==seconds;
      bufferSeconds=seconds;bufferGuard=guard;
      if(!guard)for(const video of media)cancelGuard(video);
      rate=value;revision=stamp;configured=true;
      for(const video of media)apply(video,fresh);
    },
    // Used only for launcher artwork; never move or replace website player DOM.
    icon() {
      const icons = [...document.querySelectorAll('link[rel~="icon"],link[rel="apple-touch-icon"]')];
      return {url: location.href, title: document.title, icon: icons.length ? icons[icons.length - 1].href : ''};
    }
  };
  discover(document);
  document.addEventListener('DOMContentLoaded', () => {
    for(const video of document.querySelectorAll('video,audio'))track(video,false);
    for(const element of document.querySelectorAll('*'))if(element.shadowRoot)discover(element.shadowRoot);
  }, {once:true});
  // Small bounded fallback covers shadow roots attached after insertion and
  // player resets that do not emit usable events in this isolated world.
  let scans=0;
  const recovery=setInterval(()=>{
    for(const video of media)apply(video);
    if(document.visibilityState!=='hidden' && ++scans%6===0)for(const element of document.querySelectorAll('*'))if(element.shadowRoot)discover(element.shadowRoot);
  },1500);
  document.addEventListener('fullscreenchange', () => {
    send({type: 'fullscreen', enabled: !!document.fullscreenElement});
  });
  document.addEventListener('scroll', event => {
    const target = event.target === document ? document.scrollingElement : event.target;
    const scroll = target ? target.scrollTop : 0;
    const now = Date.now();
    if (Math.abs(scroll - lastScroll) >= 24 && now - lastReveal > 1500) {
      lastReveal = now; send({type: 'reveal'});
    }
    lastScroll = scroll;
  }, {capture: true, passive: true});
  let touchY = null;
  document.addEventListener('touchstart', e => { touchY = e.touches.length === 1 ? e.touches[0].clientY : null; }, {passive: true});
  document.addEventListener('touchmove', e => {
    if (touchY === null || e.touches.length !== 1) return;
    if (Math.abs(e.touches[0].clientY - touchY) >= 24) {
      touchY = null; send({type: 'reveal'});
    }
  }, {passive: true});
  window.addEventListener('pagehide', event => {for(const v of media){clearTimeout(state(v).pending);cancelGuard(v);}if(!event.persisted){media.clear();clearInterval(recovery);}});
  window.addEventListener('pageshow', () => {for(const v of document.querySelectorAll('video,audio'))track(v,true);});
  window.addEventListener('resize', () => send({type: 'resize'}), {passive: true});
})();
