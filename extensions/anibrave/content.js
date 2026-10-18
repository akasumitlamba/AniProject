(() => {
  'use strict';
  // Speed policy mirrors core.js (content scripts cannot import modules).
  const normalizeSpeed = speed => {
    if (typeof speed !== 'number' || !Number.isFinite(speed)) return null;
    const rounded = Math.round((speed + Number.EPSILON) * 100) / 100;
    return rounded >= .25 && rounded <= 5 ? rounded : null;
  };
  const speedsMatch = (a, b) => typeof a === 'number' && typeof b === 'number' && Math.abs(a - b) < .01;
  const rateDecision = ({mediaRate, savedRate, suppressRate, withinSuppress, withinHold, adActive, main}) => {
    const rate = normalizeSpeed(mediaRate), saved = normalizeSpeed(savedRate);
    if (rate == null || saved == null) return 'ignore';
    if (speedsMatch(rate, saved)) return 'ignore';
    if (withinSuppress && speedsMatch(rate, normalizeSpeed(suppressRate))) return 'ignore';
    if (adActive || !main) return 'ignore';
    return withinHold ? 'enforce' : 'adopt';
  };

  let speed = 1, speedStamp = 0, site = '', history = [], currentURL = location.href, currentIdentity = null, ready = false;
  let saveTimer = 0, refreshToken = 0, pageMsg = 0;
  const media = new Set(), roots = new WeakSet(), counters = new WeakMap(), reminded = new Set();
  const hold = new WeakMap(), pushes = new WeakMap(), watchedAds = new WeakSet();
  const HOLD_MS = 600;
  const send = async data => { try { const r = await chrome.runtime.sendMessage(data); return r?.ok ? r.value : null; } catch { return null; } };

  function shadowOf(el) {
    if (!el || el.nodeType !== 1) return null;
    if (el.shadowRoot) return el.shadowRoot;
    try { return chrome.dom?.openOrClosedShadowRoot?.(el) || null; } catch { return null; }
  }
  function adBlocked(v) {
    for (let player = v; player; player = player.parentElement || player.getRootNode()?.host) {
      if (player.classList?.contains('ad-showing') || player.classList?.contains('ad-interrupting')) return true;
    }
    return false;
  }
  function longAudio(v) {
    const d = v.duration;
    return d === Infinity || (Number.isFinite(d) && d >= 20);
  }
  function isControlled(v) {
    if (!v?.isConnected || adBlocked(v)) return false;
    if (v.tagName === 'AUDIO') return longAudio(v);
    if (v.classList.contains('html5-main-video') || document.pictureInPictureElement === v) return true;
    if (document.fullscreenElement?.contains(v)) return true;
    const w = v.clientWidth, h = v.clientHeight;
    if (w >= 200 && h >= 112) return true;
    return w === 0 && h === 0;
  }
  function isMain(v) {
    if (!isControlled(v)) return false;
    if (v.tagName === 'AUDIO') return true;
    const area = v.clientWidth * v.clientHeight;
    let best = area;
    for (const other of media) {
      if (other === v || other.tagName !== 'VIDEO' || !other.isConnected || !isControlled(other)) continue;
      best = Math.max(best, other.clientWidth * other.clientHeight);
    }
    return area >= best;
  }
  function watchAd(player) {
    if (!player || player.nodeType !== 1 || watchedAds.has(player)) return;
    watchedAds.add(player);
    new MutationObserver(() => {
      if (!player.classList.contains('ad-showing') && !player.classList.contains('ad-interrupting')) for (const v of media) if (needsApply(v)) apply(v);
    }).observe(player, {attributes: true, attributeFilter: ['class']});
  }
  const bridgeRequests = new Map(), inFlight = new WeakMap(), mediaIds = new WeakMap();
  let mediaId = 0, inputUntil = 0, earlyChoice = null;
  // A real site click/key wins even during the short player startup window.
  for (const type of ['pointerdown', 'keydown']) document.addEventListener(type, event => {
    if (event.isTrusted) inputUntil = performance.now() + 1000;
  }, true);
  window.addEventListener('message', event => {
    if (event.source !== window || event.data?.source !== 'anibrave-page' || event.data.type !== 'player-rate') return;
    const pending = bridgeRequests.get(event.data.id);
    if (pending) pending.finish(normalizeSpeed(event.data.rate) ?? pending.rate);
  });
  function syncPlayers(v, rate) {
    document.querySelectorAll('#movie_player, .html5-video-player').forEach(watchAd);
    if (!mediaIds.has(v)) mediaIds.set(v, String(++mediaId));
    v.setAttribute('data-anibrave-media', mediaIds.get(v));
    const id = ++pageMsg;
    return new Promise(resolve => {
      const finish = settled => {
        const pending = bridgeRequests.get(id);
        if (!pending) return;
        clearTimeout(pending.timer); bridgeRequests.delete(id); resolve(settled);
      };
      const timer = setTimeout(() => finish(rate), 500);
      bridgeRequests.set(id, {finish, timer, rate});
      window.postMessage({source: 'anibrave-ext', type: 'set-rate', id, rate, media: v.getAttribute('data-anibrave-media')}, '*');
    });
  }
  function needsApply(v) {
    if (!isControlled(v)) return false;
    return normalizeSpeed(v.playbackRate) != null && !speedsMatch(v.playbackRate, speed);
  }
  async function apply(v) {
    if (!ready || !v?.isConnected || !isControlled(v)) return;
    const requested = speed, stamp = speedStamp;
    if (inFlight.get(v)?.rate === requested && inFlight.get(v)?.stamp === stamp) return;
    const task = {rate: requested, stamp}; inFlight.set(v, task);
    try {
      const settled = await syncPlayers(v, requested);
      // An acknowledgement belongs to the request, not whichever choice is current now.
      if (inFlight.get(v) !== task || stamp !== speedStamp || !speedsMatch(requested, speed) || !isControlled(v)) return;
      if (!speedsMatch(settled, requested)) { adopt(settled); return; }
      if (v.defaultPlaybackRate !== speed) v.defaultPlaybackRate = speed;
      if (!speedsMatch(v.playbackRate, speed)) v.playbackRate = speed;
    } catch { /* element detached or player rejected a rate */ }
    finally { if (inFlight.get(v) === task) inFlight.delete(v); }
  }
  function scheduleSave(rate, stamp) {
    clearTimeout(saveTimer);
    saveTimer = setTimeout(() => {
      if (stamp < speedStamp || !speedsMatch(rate, speed)) return;
      send({type: 'settings', speed: rate, speedUpdated: stamp});
    }, 40);
  }
  function adopt(rate) {
    const next = normalizeSpeed(rate);
    if (next == null || speedsMatch(next, speed)) return;
    const stamp = Math.max(Date.now(), speedStamp);
    speed = next; speedStamp = stamp;
    for (const v of media) if (isControlled(v)) apply(v);
    scheduleSave(next, stamp);
  }
  function onRate(v) {
    if (!ready) { if (performance.now() < inputUntil && isMain(v)) earlyChoice = normalizeSpeed(v.playbackRate); return; }
    const pending = inFlight.get(v);
    if (pending && performance.now() >= inputUntil) return;
    const decision = rateDecision({
      mediaRate: v.playbackRate, savedRate: speed,
      suppressRate: null, withinSuppress: false,
      withinHold: performance.now() < (hold.get(v) || 0) && performance.now() >= inputUntil,
      adActive: adBlocked(v), main: isMain(v)
    });
    if (decision === 'ignore') return;
    if (decision === 'adopt') { pushes.set(v, 0); adopt(v.playbackRate); return; }
    const n = (pushes.get(v) || 0) + 1;
    pushes.set(v, n);
    if (n > 2) { const next = normalizeSpeed(v.playbackRate); if (next != null) adopt(next); return; }
    apply(v);
  }
  function track(node) {
    if (!node?.matches?.('video,audio') || media.has(node)) return;
    media.add(node);
    hold.set(node, performance.now() + HOLD_MS);
    node.addEventListener('ratechange', () => onRate(node));
    node.addEventListener('loadedmetadata', () => { if (isControlled(node)) apply(node); });
    node.addEventListener('loadstart', () => { hold.set(node, performance.now() + HOLD_MS); pushes.set(node, 0); if (isControlled(node)) apply(node); });
    node.addEventListener('timeupdate', () => progress(node));
    if (isControlled(node)) apply(node);
  }
  function inspect(node) {
    if (!node || node.nodeType !== 1) return;
    if (node.localName === 'video' || node.localName === 'audio') track(node);
    if (node.id === 'movie_player' || node.classList.contains('html5-video-player')) watchAd(node);
    const shadow = shadowOf(node);
    if (shadow) scan(shadow);
    if (!node.querySelectorAll) return;
    node.querySelectorAll('video, audio').forEach(track);
    node.querySelectorAll('#movie_player, .html5-video-player, ytd-player').forEach(el => {
      watchAd(el);
      const nested = shadowOf(el);
      if (nested) scan(nested);
    });
    const hosts = node.querySelectorAll('*');
    if (hosts.length > 500) return;
    hosts.forEach(el => { const nested = shadowOf(el); if (nested) scan(nested); });
  }
  function scan(root) {
    if (!root || roots.has(root)) return;
    roots.add(root);
    if (root.nodeType === 9) { if (root.documentElement) inspect(root.documentElement); }
    else if (root.nodeType === 11) { for (const child of root.children || []) inspect(child); }
    else inspect(root);
    try {
      new MutationObserver(records => {
        for (const record of records) for (const node of record.addedNodes) inspect(node);
      }).observe(root, {childList: true, subtree: true});
    } catch { /* not an observable root */ }
  }

  const text = s => document.querySelector(s)?.textContent?.trim() || '';
  function identity() {
    const host = location.hostname, heading = text('[data-t="episode-title"]') || text('h1'); let title = '', episode = 0, season = 1;
    const parse = s => Number((s.match(/\b(?:episode\s*|ep\.?\s*|e)(\d{1,4})\b/i) || s.match(/\bS\d+\s*E(\d{1,4})\b/i) || [])[1] || 0);
    if (/(^|\.)crunchyroll\.com$/.test(host)) {
      if (!/\/(watch|series)\//.test(location.pathname)) return null;
      title = text('[data-t="series-title"]') || text('a[href*="/series/"]');
      const fallback = document.title.replace(/^watch\s+/i, '').replace(/\s*[|–-]\s*Crunchyroll.*$/i, '');
      if (!title && /\/series\//.test(location.pathname)) title = heading;
      if (!title) title = fallback.split(/\s+(?:season\s*\d+\s+)?episode\s*\d+|\s+S\d+\s*E\d+/i)[0];
      episode = /\/watch\//.test(location.pathname) ? parse(heading) || parse(fallback) : 0;
      season = Number(((heading + ' ' + fallback).match(/\bS(?:eason\s*)?(\d+)\s*(?:E|Episode)/i) || [])[1] || 1);
    } else if (/(^|\.)(hianime|hianimez)\.to$/.test(host)) {
      title = text('.film-name a') || text('.film-name') || text('.anisc-detail h2');
      const active = document.querySelector('.ep-item.active, [data-number].active');
      episode = active ? Number(active.getAttribute('data-number')) || parse(active.textContent) : 0;
    } else if (/(^|\.)anikototv\.to$/.test(host)) {
      const path = location.pathname.match(/^\/watch\/([^/]+)\/ep-?(\d{1,4})(?:\/|$)/i);
      if (path) { let slug = path[1]; try { slug = decodeURIComponent(slug); } catch { /* malformed URL */ } title = heading || slug.replace(/-/g, ' '); episode = Number(path[2]); }
    }
    return title ? {title: title.replace(/^watch\s+/i, '').trim(), episode, season} : null;
  }
  const key = t => t.normalize('NFKC').toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
  function remind() {
    if (window !== top || !ready || !document.body) return;
    const id = identity(); currentIdentity = id;
    const supported = /(^|\.)(crunchyroll\.com|hianime\.to|hianimez\.to|anikototv\.to)$/.test(location.hostname);
    const entries = id ? history.filter(e => e.key === key(id.title)) : (supported ? history : history.filter(e => e.host === site)).slice(0, 3);
    if (!entries.length) { document.getElementById('anibrave-memory-card')?.remove(); return; }
    const signature = location.href + '|' + entries.map(e => e.key + ':' + e.season + ':' + e.episode).join('|'); if (reminded.has(signature)) return; reminded.add(signature);
    document.getElementById('anibrave-memory-card')?.remove();
    const host = document.createElement('div'); host.id = 'anibrave-memory-card'; host.style.cssText = 'position:fixed;right:20px;bottom:20px;z-index:2147483647;max-width:calc(100vw - 40px);'; const shadow = host.attachShadow({mode: 'closed'});
    const style = document.createElement('style'); style.textContent = ':host{all:initial}aside{font:14px system-ui;color:#f5f2ec;background:#211f1c;border:1px solid #746652;border-radius:16px;padding:18px;width:320px;max-width:calc(100vw - 40px);box-sizing:border-box;box-shadow:0 12px 48px #0008}header{display:flex;justify-content:space-between;align-items:center;color:#e2b779;font-size:12px;font-weight:700}button{background:transparent;color:#ddd;border:0;font:20px system-ui;cursor:pointer;padding:0 4px}p{line-height:1.6;margin:12px 0 0}small{color:#b8afa2;font-size:11px}';
    const card = document.createElement('aside'); card.setAttribute('role', 'status'); const header = document.createElement('header'); header.textContent = 'ANIBRAVE · YOUR LAST EPISODE'; const close = document.createElement('button'); close.textContent = '×'; close.setAttribute('aria-label', 'Dismiss episode reminder'); close.onclick = () => host.remove(); header.append(close); card.append(header);
    for (const e of entries) { const p = document.createElement('p'); p.textContent = `${e.title} — Season ${e.season}, episode ${e.episode}`; const detail = document.createElement('small'); detail.textContent = `Last watched on ${e.host}`; card.append(p, detail); } shadow.append(style, card); document.body.append(host);
  }
  function progress(v) {
    if (!ready || v.tagName !== 'VIDEO' || !isMain(v) || adBlocked(v)) return; const now = Date.now(), old = counters.get(v), position = v.currentTime;
    const identified = window === top ? identity() : null;
    const signature = location.href + '|' + v.currentSrc + '|' + (identified ? JSON.stringify(identified) : ''); let seconds = old?.signature === signature ? old.seconds : 0;
    if (old?.signature === signature && !v.paused && !v.seeking && v.duration >= 60) { const wall = (now - old.time) / 1000, delta = position - old.position; if (wall > 0 && wall < 5 && delta > 0 && delta <= wall * Math.max(1, v.playbackRate) + 1) seconds += Math.min(wall, delta / Math.max(.25, v.playbackRate)); }
    const sent = old?.signature === signature ? old.sent : 0; counters.set(v, {time: now, position, signature, seconds, sent});
    if (seconds >= 15 && now - sent > 30000) { counters.get(v).sent = now; if (window === top) { const id = identity(); if (id?.episode) send({type: 'save', ...id, position}); } else send({type: 'progress', position}); }
  }
  async function refresh(applyStoredSpeed) {
    const token = ++refreshToken;
    const state = await send({type: 'state'});
    if (!state || token !== refreshToken) return;
    site = state.host; history = state.history || []; ready = true;
    if (applyStoredSpeed) {
      const incoming = normalizeSpeed(state.settings?.speed) ?? 1;
      const stamp = Number(state.settings?.speedUpdated) || 0;
      if (stamp >= speedStamp) {
        const changed = !speedsMatch(incoming, speed);
        speed = incoming;
        speedStamp = Math.max(speedStamp, stamp);
        if (changed) for (const v of media) if (isControlled(v)) apply(v);
      }
    }
    if (earlyChoice != null) { const choice = earlyChoice; earlyChoice = null; adopt(choice); }
    for (const v of media) if (needsApply(v)) apply(v);
    remind();
  }
  chrome.runtime.onMessage.addListener(m => { if (m.type === 'playerProgress' && window === top) { const id = identity(); if (id?.episode) send({type: 'save', ...id, position: m.position}); } });
  chrome.storage.onChanged.addListener((changes, area) => {
    if (area !== 'local') return;
    const pageHost = location.hostname.toLowerCase().replace(/^www\./, '');
    const siteChange = changes['site:' + pageHost] || (site && site !== pageHost ? changes['site:' + site] : undefined);
    if (siteChange?.newValue) {
      const incoming = normalizeSpeed(siteChange.newValue.speed);
      const stamp = Number(siteChange.newValue.speedUpdated) || Date.now();
      if (incoming != null && stamp >= speedStamp) {
        const changed = !speedsMatch(speed, incoming);
        speed = incoming;
        speedStamp = stamp;
        if (changed) for (const v of media) if (isControlled(v)) apply(v);
      }
    }
    if (site && (siteChange || changes.history)) refresh(false);
  });
  scan(document);
  refresh(true);
  setInterval(() => {
    if (!ready) refresh(true);
    document.querySelectorAll('video, audio').forEach(track);
    document.querySelectorAll('#movie_player, .html5-video-player, ytd-player').forEach(el => {
      watchAd(el);
      const shadow = shadowOf(el);
      if (shadow) scan(shadow);
    });
    for (const v of [...media]) {
      if (!v.isConnected) media.delete(v);
      else if (needsApply(v)) onRate(v);
    }
    if (currentURL !== location.href) { currentURL = location.href; currentIdentity = null; reminded.clear(); document.getElementById('anibrave-memory-card')?.remove(); refresh(false); }
    else remind();
  }, 2000);
})();
