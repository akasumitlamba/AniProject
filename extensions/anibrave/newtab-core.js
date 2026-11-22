/** Local new-tab rules. Banner bytes come from the public AniProject store; site lists never leave the browser. */
export const BANNER_STORE = 'https://raw.githubusercontent.com/akasumitlamba/AniProject/main/banners/';
export const IMAGE_LIMIT = 4 * 1024 * 1024;
export const CATALOG_LIMIT = 1024 * 1024;
export const CATALOG_COOLDOWN_MS = 15 * 60 * 1000;
export const CATALOG_MAX_AGE_MS = 12 * 60 * 60 * 1000;

const SAFE_FILE = /^images\/[a-z0-9]+(?:-[a-z0-9]+)*\.(?:png|webp)$/;
const PREFIXES = new Set(['www', 'm', 'mobile', 'web', 'app', 'play', 'watch', 'en', 'in', 'beta', 'home']);
const SUFFIXES = new Set(['com', 'net', 'org', 'co', 'in', 'io', 'tv', 'app', 'me', 'gg', 'ai', 'us', 'uk', 'au', 'ca', 'de', 'jp', 'fr', 'info', 'biz', 'live', 'xyz', 'site', 'online', 'gov', 'edu', 'ac', 'nic']);
const GENERIC = new Set(['android', 'app', 'apps', 'application', 'client', 'mediaclient', 'mobile', 'main', 'release', 'launcher', 'lite', 'free', 'pro', 'plus', 'com', 'org', 'net', 'in', 'co', 'io', 'google', 'the']);

export function normalize(value) {
  return String(value ?? '').toLowerCase().replace(/[^\p{L}\p{N}]/gu, '');
}

function distinct(values) {
  const seen = new Set();
  const out = [];
  for (const value of values) {
    if (!value || seen.has(value)) continue;
    seen.add(value);
    out.push(value);
  }
  return out;
}

/** "www.music.youtube.com" → ["musicyoutube", "youtube", "music"]. */
export function hostWords(host) {
  const labels = String(host).toLowerCase().split('.').filter(Boolean);
  while (labels.length > 1 && SUFFIXES.has(labels[labels.length - 1])) labels.pop();
  while (labels.length > 1 && PREFIXES.has(labels[0])) labels.shift();
  const words = labels.map(normalize).filter(Boolean);
  return distinct([words.join(''), ...words.slice().reverse()]);
}

export function titleWords(label) {
  return distinct(String(label ?? '').split(/\s+[-–—|:·•]\s+|\s*[|·•]\s*/u).map(normalize));
}

function packageWord(pkg) {
  return String(pkg).toLowerCase().split('.').reverse().map(normalize).find(part => part.length >= 4 && !GENERIC.has(part)) || null;
}

function keys(entry) {
  const values = [normalize(entry.name), ...entry.aliases.map(normalize), normalize(entry.id)];
  for (const host of entry.webHosts) values.push(...hostWords(host).slice(0, 2));
  for (const pkg of entry.packages) {
    const word = packageWord(pkg);
    if (word) values.push(word);
  }
  return new Set(values.filter(value => value.length >= 2));
}

function siteHost(url) {
  try {
    return new URL(url).hostname.toLowerCase().replace(/^www\./, '') || null;
  } catch {
    return null;
  }
}

function unique(found) {
  const distinctEntries = [];
  for (const entry of found) if (!distinctEntries.includes(entry)) distinctEntries.push(entry);
  return distinctEntries.length === 1 ? distinctEntries[0] : null;
}

/**
 * Same identity order as AniHome's SiteMatcher: listed website, address word,
 * title, then an unambiguous containment. An equal tie stays unmatched.
 */
export function matchSite(catalog, url, label = '') {
  const host = siteHost(url || '');
  if (host) {
    let best = null;
    let bestLen = -1;
    for (const entry of catalog) {
      let len = -1;
      for (const raw of entry.webHosts || []) {
        const site = String(raw).toLowerCase().replace(/^www\./, '');
        if (host === site || host.endsWith(`.${site}`)) len = Math.max(len, site.length);
      }
      if (len > bestLen) {
        best = entry;
        bestLen = len;
      }
    }
    if (best) return best;
  }
  const keyed = catalog.map(entry => [entry, keys(entry)]);
  const words = host ? hostWords(host) : [];
  for (const word of words) {
    if (word.length < 3) continue;
    const found = keyed.filter(([, set]) => set.has(word)).map(([entry]) => entry);
    if (found.length) return unique(found);
  }
  for (const word of titleWords(label)) {
    if (word.length < 3) continue;
    const found = keyed.filter(([, set]) => set.has(word)).map(([entry]) => entry);
    if (found.length === 1) return found[0];
  }
  for (const segment of String(label ?? '').split(/\s+[-–—|:·•]\s+|\s*[|·•]\s*/u)) {
    const first = normalize(segment.trim().split(/\s+/)[0] || '');
    if (first.length < 5) continue;
    const found = keyed.filter(([, set]) => set.has(first)).map(([entry]) => entry);
    if (found.length === 1) return found[0];
  }
  const loose = [...words, ...titleWords(label)].filter(word => word.length >= 5);
  let best = null;
  let bestScore = 0;
  let tie = false;
  for (const [entry, set] of keyed) {
    for (const key of set) {
      if (key.length < 5) continue;
      for (const word of loose) {
        if (!key.includes(word) && !word.includes(key)) continue;
        const score = Math.min(key.length, word.length) / Math.max(key.length, word.length);
        if (score < 0.6) continue;
        if (score > bestScore) {
          best = entry;
          bestScore = score;
          tie = false;
        } else if (score === bestScore && entry !== best) tie = true;
      }
    }
  }
  return tie ? null : best;
}

export function bannerRequest(path, hashPrefix, extraStamp) {
  if (path !== 'catalog.json' && !SAFE_FILE.test(path)) throw Error('Unsafe banner path');
  let query = '';
  if (hashPrefix != null) {
    if (!/^[a-f0-9]{1,64}$/.test(hashPrefix)) throw Error('Unsafe banner query');
    query = `?v=${hashPrefix}`;
    if (extraStamp != null) {
      if (!/^[0-9]{1,20}$/.test(String(extraStamp))) throw Error('Unsafe banner query');
      query += `&t=${extraStamp}`;
    }
  }
  return BANNER_STORE + path + query;
}

function strings(row, key) {
  const value = row[key];
  if (value == null) return [];
  if (!Array.isArray(value) || value.length > 100) return null;
  const out = [];
  for (const item of value) {
    if (typeof item !== 'string' || item.length > 256) return null;
    out.push(item);
  }
  return out;
}

/** Accepts the public catalog shape. A bad row is skipped; a bad document is rejected. */
export function parseCatalog(root) {
  if (!root || root.schema !== 1 || !Array.isArray(root.entries) || root.entries.length < 1 || root.entries.length > 5000) {
    throw Error('Banner catalog is not usable');
  }
  const ids = new Set();
  const files = new Set();
  const packages = new Set();
  const hostPattern = /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$/;
  const packagePattern = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$/;
  const entries = [];
  for (const row of root.entries) {
    if (!row || typeof row !== 'object') continue;
    const id = row.id;
    const name = row.name;
    const file = row.file;
    const sha256 = row.sha256;
    const appPackages = strings(row, 'packages');
    const aliases = strings(row, 'aliases');
    const webHosts = strings(row, 'webHosts');
    if (typeof id !== 'string' || !/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(id) || ids.has(id)) continue;
    if (typeof name !== 'string' || !name.trim() || name.length > 120) continue;
    if (typeof file !== 'string' || !SAFE_FILE.test(file) || files.has(file)) continue;
    if (typeof sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(sha256)) continue;
    if (!appPackages || !aliases || !webHosts) continue;
    if (appPackages.some(pkg => !packagePattern.test(pkg) || packages.has(pkg))) continue;
    if (webHosts.some(host => !hostPattern.test(host))) continue;
    ids.add(id);
    files.add(file);
    for (const pkg of appPackages) packages.add(pkg);
    entries.push({ id, name, file, sha256, packages: appPackages, aliases, webHosts });
  }
  if (!entries.length) throw Error('Banner catalog is not usable');
  return entries;
}

export function catalogFromStorage(value) {
  if (!value || typeof value.at !== 'number' || !Array.isArray(value.entries)) return null;
  try {
    return { at: value.at, entries: parseCatalog({ schema: 1, entries: value.entries }) };
  } catch {
    return null;
  }
}

export function catalogIsFresh(stored, now) {
  return !!stored && Number.isFinite(stored.at) && now - stored.at >= 0 && now - stored.at < CATALOG_MAX_AGE_MS;
}

export function pauseAfterStatus(status, now) {
  return [401, 403, 404, 410, 429].includes(status) ? { until: now + CATALOG_COOLDOWN_MS, status } : { until: 0, status: 0 };
}

export function pauseAllows(pause, now) {
  return !pause || !pause.until || now >= pause.until;
}

export function publicUrl(value) {
  try {
    const url = new URL(String(value));
    if ((url.protocol !== 'http:' && url.protocol !== 'https:') || !url.hostname || url.username || url.password) return '';
    return url.href;
  } catch {
    return '';
  }
}

export function destination(query) {
  const text = String(query ?? '').trim();
  if (!text || text.length > 2000) return null;
  if (/^[a-z][a-z0-9+.-]*:/i.test(text)) {
    const url = publicUrl(text);
    return url ? { type: 'url', url } : null;
  }
  if (!/\s/.test(text) && /^[a-z0-9.-]+\.[a-z]{2,}(?::[0-9]{1,5})?(?:[/?#][^\s]*)?$/i.test(text)) {
    const url = publicUrl(`https://${text}`);
    if (url) return { type: 'url', url };
  }
  return { type: 'search', text };
}

export function flattenBookmarks(nodes) {
  const roots = Array.isArray(nodes) ? nodes : [];
  const top = roots.length === 1 && Array.isArray(roots[0].children) ? roots[0].children : roots;
  const bar = [];
  const rest = [];
  const walk = (node, into) => {
    if (!node || bar.length + rest.length > 400) return;
    if (node.url) into.push({ title: node.title || '', url: node.url });
    if (Array.isArray(node.children)) for (const child of node.children) walk(child, into);
  };
  top.forEach((folder, index) => walk(folder, index === 0 ? bar : rest));
  return [...bar, ...rest].slice(0, 400);
}

function hostKey(url) {
  try {
    return new URL(url).hostname.toLowerCase().replace(/^www\./, '');
  } catch {
    return '';
  }
}

export function collectSites(bookmarks, topSites, limits = {}) {
  const bookmarkLimit = limits.bookmarkLimit ?? 12;
  const topLimit = limits.topLimit ?? 8;
  const seen = new Set();
  const take = (items, limit) => {
    const out = [];
    for (const item of items || []) {
      if (out.length >= limit) break;
      const url = publicUrl(item?.url);
      const host = hostKey(url);
      if (!url || !host || seen.has(host)) continue;
      seen.add(host);
      const title = String(item.title || host).trim().slice(0, 120) || host;
      out.push({ title, url, host });
    }
    return out;
  };
  return { bookmarks: take(bookmarks, bookmarkLimit), topSites: take(topSites, topLimit) };
}

export function validBannerSize(width, height) {
  if (!Number.isInteger(width) || !Number.isInteger(height)) return false;
  if (width < 320 || width > 4096 || height < 1 || height > 4096) return false;
  return Math.abs(width / height - 1.62) < 0.035;
}

export async function digestSha256(bytes) {
  const digest = await crypto.subtle.digest('SHA-256', bytes);
  return [...new Uint8Array(digest)].map(part => part.toString(16).padStart(2, '0')).join('');
}

export async function imageDecision(bytes, expectedHash, size) {
  const view = ArrayBuffer.isView(bytes) ? new Uint8Array(bytes.buffer, bytes.byteOffset, bytes.byteLength) : null;
  if (!view || view.byteLength < 1 || view.byteLength > IMAGE_LIMIT) return { ok: false, reason: 'size' };
  const hash = await digestSha256(view);
  if (hash !== expectedHash) return { ok: false, reason: 'hash' };
  if (!validBannerSize(size?.width, size?.height)) return { ok: false, reason: 'dimensions' };
  return { ok: true, hash };
}
