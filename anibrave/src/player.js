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
  window.addEventListener('message', event => {
    const m = event.data;
    if (event.source !== window || m?.source !== 'anibrave-app' || m.type !== 'set-rate' || !Number.isSafeInteger(m.id) || m.id <= 0 || !/^\d+$/.test(m.media) || rate(m.rate) == null) return;
    let settled = rate(m.rate);
    try { const v = media(document, m.media); if (v) settled = apply(v, settled); } catch { /* use HTML media fallback */ }
    window.postMessage({source: 'anibrave-app-player', type: 'player-rate', id: m.id, rate: settled}, '*');
  });
})();
