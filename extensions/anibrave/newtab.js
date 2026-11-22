import {BANNER_STORE, bannerRequest, catalogFromStorage, catalogIsFresh, collectSites, destination, flattenBookmarks, imageDecision, matchSite, parseCatalog, pauseAfterStatus, pauseAllows, publicUrl} from './newtab-core.js';

const $ = selector => document.querySelector(selector);
const status = text => { $('#status').textContent = text; };
const mark = title => (String(title || '').trim().charAt(0) || '•').toUpperCase();

function fill(section, sites) {
  const list = section.querySelector('.grid');
  list.replaceChildren();
  section.hidden = sites.length === 0;
  for (const site of sites) {
    const item = document.createElement('li');
    const link = document.createElement('a');
    link.href = site.url;
    const art = document.createElement('span');
    art.className = 'art';
    const fallback = document.createElement('span');
    fallback.className = 'fallback';
    fallback.setAttribute('aria-hidden', 'true');
    fallback.textContent = mark(site.title);
    const name = document.createElement('span');
    name.className = 'name';
    name.textContent = site.title;
    art.append(fallback);
    link.append(art, name);
    item.append(link);
    list.append(item);
    site.art = art;
  }
}

async function mapPool(items, limit, task) {
  const queue = items.slice();
  const workers = Array.from({length: Math.min(limit, queue.length)}, async () => {
    while (queue.length) await task(queue.shift());
  });
  await Promise.all(workers);
}

async function storeGet(keys) {
  return chrome.storage.local.get(keys);
}

async function fetchBytes(url, accept) {
  const response = await fetch(url, {
    redirect: 'error',
    credentials: 'omit',
    referrerPolicy: 'no-referrer',
    cache: 'no-store',
    headers: {Accept: accept},
    signal: AbortSignal.timeout(15000)
  });
  if (!response.ok || !response.url.startsWith(BANNER_STORE)) {
    const error = Error('Banner store unavailable');
    error.status = response.status;
    throw error;
  }
  const limit = accept === 'application/json' ? 1024 * 1024 : 4 * 1024 * 1024;
  const reader = response.body.getReader();
  const chunks = [];
  let length = 0;
  try {
    while (true) {
      const {done, value} = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > limit) {
        await reader.cancel();
        throw Error('Banner store response too large');
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return bytes;
}

async function fetchCatalog() {
  const bytes = await fetchBytes(bannerRequest('catalog.json'), 'application/json');
  if (bytes.byteLength > 1024 * 1024) throw Error('Banner catalog too large');
  return parseCatalog(JSON.parse(new TextDecoder().decode(bytes)));
}

async function loadCatalog(now = Date.now()) {
  const storedRaw = await storeGet(['bannerStoreCatalog', 'bannerStorePause']);
  const stored = catalogFromStorage(storedRaw.bannerStoreCatalog);
  if (stored && catalogIsFresh(stored, now)) return stored.entries;
  if (!pauseAllows(storedRaw.bannerStorePause, now)) return stored?.entries || [];
  try {
    const entries = await fetchCatalog();
    await chrome.storage.local.set({bannerStoreCatalog: {at: now, entries}, bannerStorePause: {until: 0, status: 0}});
    return entries;
  } catch (error) {
    if (error.status) await chrome.storage.local.set({bannerStorePause: pauseAfterStatus(error.status, now)});
    return stored?.entries || [];
  }
}

async function bitmapSize(bytes, type) {
  const bitmap = await createImageBitmap(new Blob([bytes], {type}));
  try { return {width: bitmap.width, height: bitmap.height}; }
  finally { bitmap.close(); }
}

async function loadVerifiedImage(entry) {
  const key = bannerRequest(entry.file, entry.sha256.slice(0, 16));
  let cache = null;
  try { if ('caches' in globalThis) cache = await caches.open('anibrave-banners'); }
  catch { cache = null; }
  const cached = cache ? await cache.match(key).catch(() => null) : null;
  let bytes = cached ? new Uint8Array(await cached.arrayBuffer()) : null;
  let size = null;
  if (bytes) {
    try { size = await bitmapSize(bytes, typeFor(entry.file)); }
    catch { bytes = null; }
  }
  if (!bytes || !(await imageDecision(bytes, entry.sha256, size)).ok) {
    if (cache) await cache.delete(key);
    bytes = await fetchBytes(key, 'image/webp, image/png');
    try { size = await bitmapSize(bytes, typeFor(entry.file)); }
    catch { size = null; }
    if (!(await imageDecision(bytes, entry.sha256, size)).ok) {
      bytes = await fetchBytes(bannerRequest(entry.file, entry.sha256.slice(0, 16), String(Date.now())), 'image/webp, image/png');
      try { size = await bitmapSize(bytes, typeFor(entry.file)); }
      catch { size = null; }
      const decision = await imageDecision(bytes, entry.sha256, size);
      if (!decision.ok) throw Error(decision.reason);
    }
    if (cache) await cache.put(key, new Response(bytes.slice(), {headers: {'Content-Type': typeFor(entry.file)}}));
  }
  return new Blob([bytes], {type: typeFor(entry.file)});
}

function typeFor(file) {
  return file.endsWith('.png') ? 'image/png' : 'image/webp';
}

async function paint(sites, catalog) {
  const jobs = [];
  for (const site of sites) {
    const entry = matchSite(catalog, site.url, site.title);
    if (entry) jobs.push({site, entry});
  }
  await mapPool(jobs, 4, async ({site, entry}) => {
    try {
      const blob = await loadVerifiedImage(entry);
      if (!site.art.isConnected) return;
      const image = document.createElement('img');
      image.alt = '';
      image.src = URL.createObjectURL(blob);
      image.addEventListener('load', () => image.classList.add('ready'), {once: true});
      site.art.prepend(image);
    } catch {
      /* The name tile stays. A failed banner is not replaced with an unverified image. */
    }
  });
}

$('#search').addEventListener('submit', async event => {
  event.preventDefault();
  const action = destination($('#query').value);
  if (!action) return;
  if (action.type === 'url') {
    if (publicUrl(action.url)) location.assign(action.url);
    return;
  }
  try {
    await chrome.search.query({text: action.text, disposition: 'CURRENT_TAB'});
  } catch {
    status('Search needs this browser’s default search engine.');
  }
});

async function start() {
  if (!globalThis.chrome?.bookmarks || !chrome.storage?.local) return;
  $('#query').focus();
  let bookmarks = [];
  let topSites = [];
  try { bookmarks = flattenBookmarks(await chrome.bookmarks.getTree()); }
  catch { bookmarks = []; }
  const incognito = chrome.extension?.inIncognitoContext === true;
  if (!incognito) {
    try { topSites = await chrome.topSites.get(); }
    catch { topSites = []; }
  }
  const sites = collectSites(bookmarks, topSites);
  fill($('#bookmarks'), sites.bookmarks);
  fill($('#top-sites'), sites.topSites);
  const shown = [...sites.bookmarks, ...sites.topSites];
  $('#empty').hidden = shown.length > 0;
  if (!shown.length) return;
  try {
    const catalog = await loadCatalog();
    if (!catalog.length) {
      status('Banner artwork is unavailable. Site names stay on this device.');
      return;
    }
    await paint(shown, catalog);
  } catch {
    status('Banner artwork is unavailable. Site names stay on this device.');
  }
}

start();
