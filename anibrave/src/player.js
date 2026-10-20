(() => {
  'use strict';
  // Site player methods live in MAIN world, not the browser's isolated world.
  if (window.__anibraveAppPlayer) return;
  window.__anibraveAppPlayer = true;
  const rate = n => typeof n === 'number' && Number.isFinite(n) && n >= .25 && n <= 4 ? Math.round(n * 100) / 100 : null;
  const near = (a, b) => typeof a === 'number' && typeof b === 'number' && Math.abs(a - b) < .01;
  function media(root, id, depth = 0) {
    if (!root?.querySelectorAll || depth > 8) return null;
    for (const v of root.querySelectorAll('video,audio')) if (v.getAttribute('data-anibrave-app-media') === id) return v;
    for (const node of root.querySelectorAll('*')) {
      if (node.shadowRoot) { const found = media(node.shadowRoot, id, depth + 1); if (found) return found; }
    }
    return null;
  }
  const guarded = new Map();
  const nativeRate = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'playbackRate');
  const guardObserver = new MutationObserver(() => {
    for (const v of guarded.keys()) {
      let ad = false;
      for (let p = v; p; p = p.parentElement || p.getRootNode()?.host) {
        if (p.classList?.contains('ad-showing') || p.classList?.contains('ad-interrupting')) { ad = true; break; }
      }
      if (!v.isConnected || ad) release(v);
    }
  });
  function release(v) {
    if (!guarded.has(v)) return;
    const previous = guarded.get(v).previous;
    if (previous) Object.defineProperty(v, 'playbackRate', previous);
    else delete v.playbackRate;
    guarded.delete(v);
    if (!guarded.size) guardObserver.disconnect();
  }
  // A real site-control selection can replace the browser choice. Scripted clamps cannot.
  for (const type of ['pointerdown', 'keydown']) document.addEventListener(type, event => {
    if (event.isTrusted) for (const v of guarded.keys()) release(v);
  }, true);
  function forceMedia(v, requested) {
    if (!nativeRate?.get || !nativeRate?.set) return;
    if (!guarded.has(v)) {
      const previous = Object.getOwnPropertyDescriptor(v, 'playbackRate');
      // Do not destroy another controller's non-configurable property.
      if (previous?.configurable === false) return;
      guarded.set(v, {previous, requested});
      Object.defineProperty(v, 'playbackRate', {
        configurable: true,
        get() { return nativeRate.get.call(this); },
        set(value) {
          const guard = guarded.get(this);
          if (!guard || near(value, guard.requested)) nativeRate.set.call(this, value);
        }
      });
    }
    guardObserver.observe(document, {subtree: true, childList: true, attributes: true, attributeFilter: ['class']});
    guarded.get(v).requested = requested;
    nativeRate.set.call(v, requested);
  }
  function apply(v, requested) {
    // Only update this media's own native player. Ads/other players are not targets.
    for (let player = v; player; player = player.parentElement || player.getRootNode()?.host) {
      if (player.classList?.contains('ad-showing') || player.classList?.contains('ad-interrupting')) { release(v); return requested; }
      if (typeof player.setPlaybackRate !== 'function') continue;
      try {
        release(v);
        const current = typeof player.getPlaybackRate === 'function' ? player.getPlaybackRate() : null;
        if (near(current, requested)) { forceMedia(v, requested); return requested; }
        player.setPlaybackRate(requested);
        const actual = typeof player.getPlaybackRate === 'function' ? player.getPlaybackRate() : null;
        // A site's advertised menu is not the browser media engine's limit. Keep the
        // explicit choice and use HTML playback when the native API refuses it.
        forceMedia(v, requested);
        return requested;
      } catch { forceMedia(v, requested); return requested; }
    }
    forceMedia(v, requested);
    return requested;
  }
  const bufferPlayers = new WeakMap(), bufferOriginals = new WeakMap();
  function restoreBuffer(player, original) {
    try {
      if(original.kind==='hls'){
        player.config.maxBufferLength=original.length;
        player.config.maxMaxBufferLength=original.max;
      }else player.configure({streaming:{bufferingGoal:original.goal}});
    }catch{}
  }
  function buffering(v, seconds) {
    const previous=bufferPlayers.get(v);
    if(!seconds||!Number.isFinite(v.duration)||v.duration<=0){
      if(previous)for(const [player,original] of previous){restoreBuffer(player,original);bufferOriginals.delete(player);}
      bufferPlayers.delete(v);return;
    }
    const candidates = new Set([v.hls, v._hls, v.hlsInstance, v.shakaPlayer, window.hls, window.hlsPlayer, window.player]);
    for(let p=v.parentElement;p;p=p.parentElement || p.getRootNode?.()?.host){candidates.add(p.hls);candidates.add(p.player);}
    try { for(const p of window.shaka?.Player?.getAllPlayers?.() || [])candidates.add(p); }catch{}
    let records=bufferPlayers.get(v);if(!records){records=new Map();bufferPlayers.set(v,records);}
    for(const player of candidates){
      if(!player)continue;
      try {
        if(player.media===v && player.config && typeof player.config.maxBufferLength==='number'){
          if(player.levels?.some?.(level=>level.details?.live)){if(records.has(player)){restoreBuffer(player,records.get(player));records.delete(player);bufferOriginals.delete(player);}continue;}
          if(!records.has(player)){const original=bufferOriginals.get(player)||{kind:'hls',length:player.config.maxBufferLength,max:player.config.maxMaxBufferLength};records.set(player,original);bufferOriginals.set(player,original);}
          const original=records.get(player);
          player.config.maxBufferLength=Math.max(seconds,original.length);
          player.config.maxMaxBufferLength=seconds ? Math.max(seconds,original.max || seconds) : original.max;
          // Keep the player's byte/memory limit, quality, URLs and loader intact.
        }else if(typeof player.getMediaElement==='function' && player.getMediaElement()===v && typeof player.configure==='function' && typeof player.getConfiguration==='function'){
          if(player.isLive?.()){if(records.has(player)){restoreBuffer(player,records.get(player));records.delete(player);bufferOriginals.delete(player);}continue;}
          const current=player.getConfiguration()?.streaming;if(!current || typeof current.bufferingGoal!=='number')continue;
          if(!records.has(player)){const original=bufferOriginals.get(player)||{kind:'shaka',goal:current.bufferingGoal};records.set(player,original);bufferOriginals.set(player,original);}
          player.configure({streaming:{bufferingGoal:Math.max(seconds,records.get(player).goal)}});
        }
      }catch{} // A non-exposed player continues using its own buffering policy.
    }
    if(!seconds)bufferPlayers.delete(v);
  }
  window.addEventListener('message', event => {
    const m = event.data;
    if(event.source===window && m?.source==='anibrave-app' && m.type==='set-buffer'){
      if(!Number.isSafeInteger(m.id)||m.id<=0||!/^\d+$/.test(m.media)||!Number.isInteger(m.seconds)||m.seconds<0||m.seconds>600)return;
      try {const v=media(document,m.media);if(v)buffering(v,m.seconds);}catch{}
      return;
    }
    if (event.source !== window || m?.source !== 'anibrave-app' || m.type !== 'set-rate' || !Number.isSafeInteger(m.id) || m.id <= 0 || !/^\d+$/.test(m.media) || rate(m.rate) == null) return;
    let settled = rate(m.rate);
    try { const v = media(document, m.media); if (v) settled = apply(v, settled); } catch { /* use HTML media fallback */ }
    window.postMessage({source: 'anibrave-app-player', type: 'player-rate', id: m.id, rate: settled}, '*');
  });
})();
