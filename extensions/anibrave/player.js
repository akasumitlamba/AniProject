(() => {
  'use strict';
  // Site player methods live in MAIN world, not the extension's isolated world.
  if (window.__anibravePlayer) return;
  window.__anibravePlayer = true;
  const rate = n => typeof n === 'number' && Number.isFinite(n) && n >= .25 && n <= 3 ? Math.round(n * 100) / 100 : null;
  const near = (a, b) => typeof a === 'number' && typeof b === 'number' && Math.abs(a - b) < .01;
  function media(root, id, depth = 0) {
    if (!root?.querySelectorAll || depth > 8) return null;
    for (const v of root.querySelectorAll('video,audio')) if (v.getAttribute('data-anibrave-media') === id) return v;
    for (const node of root.querySelectorAll('*')) {
      if (node.shadowRoot) { const found = media(node.shadowRoot, id, depth + 1); if (found) return found; }
    }
    return null;
  }
  function apply(v, requested) {
    // Only update this media's own native player. Ads/other players are not targets.
    for (let player = v; player; player = player.parentElement || player.getRootNode()?.host) {
      if (player.classList?.contains('ad-showing') || player.classList?.contains('ad-interrupting')) return requested;
      if (typeof player.setPlaybackRate !== 'function') continue;
      try {
        const current = typeof player.getPlaybackRate === 'function' ? player.getPlaybackRate() : null;
        // A native control can offer rates omitted from an older API's advertised list.
        if (near(current, requested)) return requested;
        player.setPlaybackRate(requested);
        const actual = typeof player.getPlaybackRate === 'function' ? rate(player.getPlaybackRate()) : null;
        if (near(actual, requested)) return requested;
        const offered = typeof player.getAvailablePlaybackRates === 'function' ? player.getAvailablePlaybackRates() : null;
        const allowed = Array.isArray(offered) ? offered.map(rate).filter(n => n != null) : [];
        if (allowed.length && !allowed.some(n => near(n, requested))) {
          const accepted = allowed.reduce((best, n) => Math.abs(n - requested) < Math.abs(best - requested) ? n : best);
          if (!near(actual, accepted)) player.setPlaybackRate(accepted);
          return accepted;
        }
        return requested;
      } catch { return requested; }
    }
    return requested;
  }
  window.addEventListener('message', event => {
    const m = event.data;
    if (event.source !== window || m?.source !== 'anibrave-ext' || m.type !== 'set-rate' || !Number.isSafeInteger(m.id) || m.id <= 0 || !/^\d+$/.test(m.media) || rate(m.rate) == null) return;
    let settled = rate(m.rate);
    try { const v = media(document, m.media); if (v) settled = apply(v, settled); } catch { /* use HTML media fallback */ }
    window.postMessage({source: 'anibrave-page', type: 'player-rate', id: m.id, rate: settled}, '*');
  });
})();
