// Shared, independent continuation policy. No catalog or remote history service.
export const normalizeTitle = value => String(value || '').normalize('NFKC').toLowerCase().replace(/[^\p{L}\p{N}]+/gu,' ').trim();
export const episodeID = entry => `${normalizeTitle(entry.title)}|s${entry.season || 1}|e${entry.episode}`;
export const seriesID = entry => `${normalizeTitle(entry.title)}|s${entry.season || 1}`;
export const supportedSite = host => /(^|\.)(crunchyroll\.com|hianime\.to|hianimez\.to|anikototv\.to)$/.test(host);
export function searchURL(origin, title) {
 const u = new URL(origin); if(!/^https?:$/.test(u.protocol)) return null;
 if(/(^|\.)crunchyroll\.com$/.test(u.hostname))return new URL('/search?q='+encodeURIComponent(title),u).href;
 if(/(^|\.)(hianime|hianimez)\.to$/.test(u.hostname))return new URL('/search?keyword='+encodeURIComponent(title),u).href;
 // Unknown search forms are filled by the content script instead of inventing routes.
 return null;
}
export function safeURL(value, host) { try { const u=new URL(value);return /^https?:$/.test(u.protocol) && (!host || u.hostname.replace(/^www\./,'')===host.replace(/^www\./,''))?u.href:null; } catch {return null;} }
export function canResume(entry, detected) {return !!detected && normalizeTitle(entry.title)===normalizeTitle(detected.title) && Number(entry.season||1)===Number(detected.season||1) && Number(entry.episode)===Number(detected.episode);}
export function updateEpisode(old, entry, now=Date.now()) {
 const duration=Number(entry.duration), watched=Number(entry.watchedSeconds);
 const ranges=mergeRanges(old?.ranges||[],entry.ranges||[]);
 const covered=ranges.reduce((sum,[start,end])=>sum+Math.max(0,Math.min(end,duration)-start),0);
 const completed=!!old?.completed || (Number.isFinite(duration) && duration>=60 && (ranges.length?covered:watched)>=duration*.85);
 return {...old,...entry,ranges,completed,updated:now};
}

export function mergeRanges(...groups) {
 const sorted=groups.flat().filter(r=>Array.isArray(r)&&r.length===2&&Number.isFinite(r[0])&&Number.isFinite(r[1])&&r[0]>=0&&r[1]>r[0]).sort((a,b)=>a[0]-b[0]);
 const out=[];for(const r of sorted){const last=out[out.length-1];if(last&&r[0]<=last[1]+.5)last[1]=Math.max(last[1],r[1]);else out.push([...r]);}return out.slice(0,1000);
}
