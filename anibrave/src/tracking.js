(() => {
  'use strict';
  if (globalThis.__aniWatch) return;
  const send = value => { try { globalThis.aniBraveNative(JSON.stringify(value)); } catch (_) {} };
  const text = selector => { const node = document.querySelector(selector); return node ? (node.textContent || '').trim() : ''; };
  const episodeNumber = value => {
    const match = String(value || '').match(/\bS\d+\s*E(\d{1,4})\b/i) || String(value || '').match(/(?:\bepisode\s*|\bep\.?\s*|\be)(\d{1,4})\b/i) || String(value || '').match(/^\s*(\d{1,4})\s*$/);
    return match ? Number(match[1]) : 0;
  };
  function snapshot() {
    const host = location.hostname.toLowerCase();
    let title = '', episode = 0;
    if (host === 'crunchyroll.com' || host.endsWith('.crunchyroll.com')) {
      if (!location.pathname.startsWith('/watch/')) return null;
      title = text('[data-t="series-title"]') || text('a[href*="/series/"]');
      episode = episodeNumber(text('[data-t="episode-title"]') || text('h1'));
      const fallback = document.title.replace(/^watch\s+/i, '').replace(/\s*[|–-]\s*Crunchyroll.*$/i, '');
      if (!episode) episode = episodeNumber(fallback);
      if (!title) title = fallback.split(/\s+(?:season\s*\d+\s+)?episode\s*\d+|\s+S\d+\s*E\d+/i)[0];
      const season = (text('[data-t="episode-title"]') || text('h1')).match(/\bS(\d{1,2})\s*E\d+|\bSeason\s*(\d{1,2})\s*Episode\b/i);
      if (season && Number(season[1] || season[2]) > 1 && !/season\s*\d+|\bs\d+\b/i.test(title)) title += ' Season ' + (season[1] || season[2]);
    } else if (host === 'hianime.to' || host.endsWith('.hianime.to') || host === 'hianimez.to' || host.endsWith('.hianimez.to')) {
      if (!location.pathname.startsWith('/watch/')) return null;
      title = text('.film-name a') || text('.film-name') || text('.anisc-detail h2');
      const active = document.querySelector('.ep-item.active, .ssl-item.ep-item.active, [data-number].active');
      if (active) episode = episodeNumber(active.getAttribute('data-number')) || episodeNumber(active.getAttribute('title')) || episodeNumber(active.textContent);
      // ?ep= is an internal ID, never an episode number.
    } else if (host === 'anikototv.to' || host.endsWith('.anikototv.to')) {
      const path = location.pathname.match(/^\/watch\/([^/]+)\/ep-?(\d{1,4})(?:\/|$)/i);
      if (!path) return null;
      episode = Number(path[2]);
      title = text('h1.title.d-title') || text('h1.title') || text('h1');
      if (!title) {
        let slug; try { slug = decodeURIComponent(path[1]); } catch (_) { slug = path[1]; }
        const match = slug.match(/^(.+)-([a-z0-9]{4,6})$/i);
        if (match && /\d/.test(match[2])) slug = match[1];
        title = slug.replace(/-/g, ' ');
      }
    } else return null;
    title = title.replace(/^watch\s+/i, '').replace(/\s*[|–-]\s*(?:HiAnime|AniKoto|AniKotoTV|Crunchyroll).*$/i, '').trim();
    return title && episode > 0 ? {type:'watchPage', title:title.slice(0,500), episode, host, url:location.href} : null;
  }
  let lastSnapshot = '', lastProbe = 0;
  let knownPage = null;
  const probe = () => {
    const data = snapshot();
    if (knownPage && knownPage.url !== location.href) knownPage = null;
    if (data) knownPage = data;
    const encoded = JSON.stringify(knownPage);
    if (encoded !== lastSnapshot || Date.now() - lastProbe >= 10000) {
      lastSnapshot = encoded; lastProbe = Date.now();
      send(knownPage || {type:'watchPage', title:'', episode:0, url:location.href});
    }
  };
  const progress = new WeakMap(), media = new Set();
  function ad(video) {
    for (let p=video; p; p=p.parentElement || p.getRootNode?.()?.host)
      if (p.classList?.contains('ad-showing') || p.classList?.contains('ad-interrupting')) return true;
    return false;
  }
  function main(video) {
    if (video.isConnected === false || ad(video)) return false;
    const area = (video.clientWidth || 0)*(video.clientHeight || 0);
    return ![...media].some(v => v!==video && v.isConnected !== false && !ad(v) && (v.clientWidth||0)*(v.clientHeight||0)>area);
  }
  function tick(event) {
    const video = event.target;
    if (!video || video.tagName !== 'VIDEO') return;
    media.add(video);
    for(const candidate of document.querySelectorAll('video'))if(candidate.tagName==='VIDEO')media.add(candidate);
    for(const candidate of media)if(candidate.isConnected===false)media.delete(candidate);
    const eligible=main(video);
    const now = Date.now(), position = Number(video.currentTime), duration = Number(video.duration);
    const previous = progress.get(video);
    const signature = location.href + '|' + (knownPage?.episode || 0);
    let seconds = eligible && previous && previous.signature === signature ? previous.seconds : 0;
    if (eligible && previous && previous.signature === signature && !video.paused && !video.seeking && Number.isFinite(position) && duration >= 60) {
      const wall = (now - previous.time) / 1000, delta = position - previous.position;
      // Count real time playing, excluding forward seeks, rewinds and buffering.
      if (wall > 0 && wall <= 10 && delta > 0 && delta <= wall * Math.max(1, Number(video.playbackRate) || 1) + 1) seconds += Math.min(wall, delta / Math.max(0.25, Number(video.playbackRate) || 1));
    }
    let sent = previous && previous.signature === signature ? previous.sent : 0;
    if (seconds >= 15 && now - sent >= 30000) { probe(); send({type:'watchProgress', position:Math.floor(position), duration:Math.floor(duration), playedSeconds:Math.floor(seconds)}); sent = now; }
    progress.set(video, {time:now,position,seconds,sent,signature});
  }
  document.addEventListener('timeupdate', tick, true);
  const shadowRoots = new WeakSet();
  const scan = root => {
    for (const video of root.querySelectorAll('video')) if(video.tagName === 'VIDEO')media.add(video);
    for(const video of media)if(video.isConnected===false)media.delete(video);
    for (const node of root.querySelectorAll('*')) if (node.shadowRoot) {
    if (!shadowRoots.has(node.shadowRoot)) { shadowRoots.add(node.shadowRoot); node.shadowRoot.addEventListener('timeupdate',tick,true); }
    scan(node.shadowRoot);
  } };
  scan(document);
  globalThis.__aniWatch = {snapshot, tick};
  probe();
  let timer = null;
  const start = () => { if (timer === null) timer = setInterval(() => { probe(); scan(document); }, 2000); };
  start();
  window.addEventListener('pagehide', () => { clearInterval(timer); timer = null; });
  window.addEventListener('pageshow', () => { probe(); scan(document); start(); });
})();
