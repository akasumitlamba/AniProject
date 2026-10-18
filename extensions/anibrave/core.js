export const hostKey = url => { try { const u = new URL(url); return /^https?:$/.test(u.protocol) ? u.hostname.toLowerCase().replace(/^www\./, '') : ''; } catch { return ''; } };
export const titleKey = title => String(title).normalize('NFKC').toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
export const validSpeed = speed => typeof speed === 'number' && Number.isFinite(speed) && speed >= .25 && speed <= 5;
const MODES = ['desktop', 'mobile', 'identity'];

export function normalizeSpeed(speed) {
  if (typeof speed !== 'number' || !Number.isFinite(speed)) return null;
  const rounded = Math.round((speed + Number.EPSILON) * 100) / 100;
  return validSpeed(rounded) ? rounded : null;
}

export function speedsMatch(a, b) {
  return typeof a === 'number' && typeof b === 'number' && Math.abs(a - b) < .01;
}

export function formatSpeed(speed) {
  const n = normalizeSpeed(speed) ?? 1;
  return `${Number.isInteger(n) ? n.toFixed(0) : n}×`;
}

export function mergeSettings(current, patch, now = Date.now()) {
  const settings = {
    speed: normalizeSpeed(current?.speed) ?? 1,
    mode: MODES.includes(current?.mode) ? current.mode : 'desktop',
    speedUpdated: Number(current?.speedUpdated) || 0
  };
  if (patch?.speed !== undefined) {
    const speed = normalizeSpeed(patch.speed);
    if (speed == null) throw Error('Speed must be between 0.25× and 5×.');
    const updated = Number(patch.speedUpdated) || now;
    if (updated >= settings.speedUpdated) {
      settings.speed = speed;
      settings.speedUpdated = updated;
    }
  }
  if (patch?.mode !== undefined) {
    if (!MODES.includes(patch.mode)) throw Error('Unknown view.');
    settings.mode = patch.mode;
  }
  return settings;
}

// How to react when a media element reports a new playbackRate.
// ignore: echo of our own write, an ad, a preview, or a rate outside 0.25×–5×.
// enforce: startup window — put the saved speed back once.
// adopt: the site chose this speed; remember it instead of fighting.
export function rateDecision({mediaRate, savedRate, suppressRate, withinSuppress, withinHold, adActive, main}) {
  const rate = normalizeSpeed(mediaRate);
  const saved = normalizeSpeed(savedRate);
  if (rate == null || saved == null) return 'ignore';
  if (speedsMatch(rate, saved)) return 'ignore';
  if (withinSuppress && speedsMatch(rate, normalizeSpeed(suppressRate))) return 'ignore';
  if (adActive || !main) return 'ignore';
  return withinHold ? 'enforce' : 'adopt';
}

export function recordEntry(data, host, url) {
  const title = String(data.title || '').trim().slice(0, 200), episode = Number(data.episode), season = Number(data.season ?? 1);
  if (!title || !titleKey(title) || !Number.isInteger(episode) || episode < 1 || episode > 10000 || !Number.isInteger(season) || season < 1 || season > 100) throw Error('Enter a title, season, and valid episode number.');
  return {key:titleKey(title),title,episode,season,host,url,updated:Date.now(),position:Number.isFinite(Number(data.position)) ? Math.min(Number.MAX_SAFE_INTEGER, Math.max(0, Math.floor(Number(data.position)))) : 0};
}
