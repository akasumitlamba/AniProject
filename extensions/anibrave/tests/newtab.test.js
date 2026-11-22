import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {BANNER_STORE, bannerRequest, catalogFromStorage, catalogIsFresh, collectSites, destination, digestSha256, flattenBookmarks, imageDecision, matchSite, parseCatalog, pauseAfterStatus, pauseAllows, publicUrl, validBannerSize} from '../newtab-core.js';

const entry = (id, name, packages = [], webHosts = [], aliases = []) => ({id, name, file: `images/${id}.webp`, sha256: 'a'.repeat(64), packages, aliases, webHosts});
const hotstar = entry('jiohotstar', 'JioHotstar', ['in.startv.hotstar'], [], ['JioHotstar', 'Jio Hotstar', 'Hotstar', 'Disney+ Hotstar']);
const myjio = entry('myjio', 'MyJio', ['com.jio.myjio'], ['jio.com'], ['MyJio', 'My Jio', 'Jio']);
const saavn = entry('jiosaavn', 'JioSaavn', ['com.jio.media.jiobeats'], ['jiosaavn.com'], ['JioSaavn']);
const youtube = entry('youtube', 'YouTube', ['com.google.android.youtube'], ['youtube.com', 'm.youtube.com'], ['YouTube']);
const music = entry('youtube-music', 'YouTube Music', ['com.google.android.apps.youtube.music'], ['music.youtube.com'], ['YouTube Music', 'YT Music']);
const netflix = entry('netflix', 'Netflix', ['com.netflix.mediaclient'], ['netflix.com'], ['Netflix']);
const prime = entry('prime-video', 'Prime Video', ['com.amazon.avod.thirdpartyclient'], ['primevideo.com'], ['Amazon Prime Video']);
const catalog = [hotstar, myjio, saavn, youtube, music, netflix, prime];

test('banner requests stay on the public store and reject user paths', () => {
  assert.equal(bannerRequest('catalog.json'), `${BANNER_STORE}catalog.json`);
  assert.equal(bannerRequest('images/crunchyroll.webp', 'a'.repeat(16)), `${BANNER_STORE}images/crunchyroll.webp?v=${'a'.repeat(16)}`);
  assert.equal(bannerRequest('images/crunchyroll.webp', 'ab', '17'), `${BANNER_STORE}images/crunchyroll.webp?v=ab&t=17`);
  for (const path of ['../README.md', 'images/../../secret', 'https://example.com/image.webp', 'catalog.json?ref=other', 'images/Crunchyroll.webp', 'images/a.png.exe']) {
    assert.throws(() => bannerRequest(path), /Unsafe/);
  }
  assert.throws(() => bannerRequest('images/crunchyroll.webp', 'https://www.crunchyroll.com'), /Unsafe/);
  assert.throws(() => bannerRequest('images/crunchyroll.webp', 'ab', '17&x=1'), /Unsafe/);
});

test('site matching follows the launcher rules', () => {
  assert.equal(matchSite(catalog, 'https://www.primevideo.com/region/eu', 'Watch').id, 'prime-video');
  assert.equal(matchSite(catalog, 'https://example.org', 'Amazon Prime Video').id, 'prime-video');
  assert.equal(matchSite(catalog, 'https://example.org', 'Example'), null);
  assert.equal(matchSite(catalog, 'https://www.hotstar.com/in/home', 'JioHotstar - Watch TV Shows').id, 'jiohotstar');
  assert.equal(matchSite(catalog, 'https://www.jiohotstar.com/', '').id, 'jiohotstar');
  assert.equal(matchSite(catalog, null, 'JioHotstar - Watch TV Shows, Movies').id, 'jiohotstar');
  assert.equal(matchSite(catalog, 'https://music.youtube.com/watch?v=1', 'Song - YouTube Music').id, 'youtube-music');
  assert.equal(matchSite(catalog, 'https://m.youtube.com/watch?v=1', 'Video - YouTube').id, 'youtube');
  assert.equal(matchSite(catalog, 'https://www.jiosaavn.com/', '').id, 'jiosaavn');
  assert.equal(matchSite(catalog, 'https://www.jio.com/selfcare', '').id, 'myjio');
  assert.equal(matchSite(catalog, 'https://example.org/', 'Example Domain'), null);
  assert.equal(matchSite(catalog, 'https://blog.example.com/', 'Notes - My blog'), null);
  assert.equal(matchSite([saavn, hotstar], 'https://jiotv.example/', 'Jio'), null);
  assert.equal(matchSite(catalog, null, 'Netflix India - Watch TV').id, 'netflix');
});

test('the real banner catalog matches its own websites and names', () => {
  const file = join(dirname(fileURLToPath(import.meta.url)), '../../../banners/catalog.json');
  const entries = parseCatalog(JSON.parse(readFileSync(file, 'utf8')));
  const wrongHosts = [];
  for (const item of entries) {
    for (const host of item.webHosts) {
      const found = matchSite(entries, `https://${host}/`, '');
      if (found !== item) wrongHosts.push(`${host} -> ${found?.id}`);
    }
  }
  const wrongNames = [];
  for (const item of entries) {
    for (const name of [item.name, ...item.aliases]) {
      const found = matchSite(entries, null, `${name} - Official site`);
      if (found && found !== item) wrongNames.push(`${name} -> ${found.id}`);
    }
  }
  assert.deepEqual(wrongHosts, []);
  assert.deepEqual(wrongNames, []);
});

test('catalog parsing drops a bad row and rejects a bad document', () => {
  const good = {id: 'example', name: 'Example', file: 'images/example.webp', sha256: 'b'.repeat(64), packages: ['com.example.app'], aliases: ['Example'], webHosts: ['example.com']};
  const parsed = parseCatalog({schema: 1, entries: [good, {id: '../x', name: 'Bad', file: 'images/bad.webp', sha256: 'c'.repeat(64)}]});
  assert.equal(parsed.length, 1);
  assert.equal(parsed[0].id, 'example');
  assert.throws(() => parseCatalog({schema: 2, entries: [good]}));
  assert.equal(catalogFromStorage({at: 10, entries: parsed}).entries[0].id, 'example');
  assert.equal(catalogFromStorage({at: 'today', entries: parsed}), null);
  assert.equal(catalogIsFresh({at: 1_000}, 1_000 + 12 * 60 * 60 * 1000), false);
  assert.equal(catalogIsFresh({at: 1_000}, 1_500), true);
});

test('store failures pause, and other failures do not', () => {
  const pause = pauseAfterStatus(404, 1_000);
  assert.equal(pauseAllows(pause, 1_000), false);
  assert.equal(pauseAllows(pause, 1_000 + 15 * 60 * 1000), true);
  assert.equal(pauseAfterStatus(500, 1_000).until, 0);
});

test('bookmarks lead, top sites fill gaps, and private addresses are dropped', () => {
  const tree = [{children: [
    {title: 'Bookmarks bar', children: [
      {title: 'Crunchyroll', url: 'https://www.crunchyroll.com/'},
      {title: 'Nested', children: [{title: 'HiAnime', url: 'https://hianime.to/home'}]},
      {title: 'Settings', url: 'brave://settings'},
      {title: 'Secret', url: 'https://user:pass@example.com/'}
    ]},
    {title: 'Other bookmarks', children: [{title: 'Example', url: 'https://example.com/'}]}
  ]}];
  const flat = flattenBookmarks(tree);
  assert.deepEqual(flat.map(item => item.title), ['Crunchyroll', 'HiAnime', 'Settings', 'Secret', 'Example']);
  const sites = collectSites(flat, [
    {title: 'Crunchyroll again', url: 'https://crunchyroll.com/watch'},
    {title: 'YouTube', url: 'https://youtube.com/'}
  ]);
  assert.deepEqual(sites.bookmarks.map(item => item.host), ['crunchyroll.com', 'hianime.to', 'example.com']);
  assert.deepEqual(sites.topSites.map(item => item.host), ['youtube.com']);
});

test('search opens addresses locally and leaves words for the browser search engine', () => {
  assert.equal(destination('  '), null);
  assert.deepEqual(destination('high school dxd'), {type: 'search', text: 'high school dxd'});
  assert.equal(destination('https://crunchyroll.com/watch').url, 'https://crunchyroll.com/watch');
  assert.equal(destination('crunchyroll.com/watch').url, 'https://crunchyroll.com/watch');
  assert.equal(destination('javascript:alert(1)'), null);
  assert.equal(destination('https://user:pass@example.com/'), null);
  assert.equal(publicUrl('chrome://newtab'), '');
});

test('banner bytes must match the catalog hash and the banner shape', async () => {
  const bytes = new Uint8Array([1, 2, 3, 4]);
  const hash = await digestSha256(bytes);
  assert.equal((await imageDecision(bytes, hash, {width: 1620, height: 1000})).ok, true);
  assert.equal((await imageDecision(bytes, 'b'.repeat(64), {width: 1620, height: 1000})).reason, 'hash');
  assert.equal((await imageDecision(bytes, hash, {width: 100, height: 62})).reason, 'dimensions');
  assert.equal(validBannerSize(1620, 1000), true);
  assert.equal(validBannerSize(1619, 1000), true);
});
