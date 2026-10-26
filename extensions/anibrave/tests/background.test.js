import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import * as core from '../core.js';
import * as continuationCore from '../continuation-core.js';

function harness() {
  const local = {}, session = {}, listeners = {}, commands = [], attached = [];
  const event = name => ({addListener(fn) { listeners[name] = fn; }});
  const store = data => ({async get(key) { return {[key]: structuredClone(data[key])}; }, async set(patch) { Object.assign(data, structuredClone(patch)); }});
  const chrome = {
    runtime: {getURL: path => 'chrome-extension://fixture/' + path, onMessage: event('message')},
    storage: {local: store(local), session: store(session)},
    tabs: {async get(id) { return {id, url: 'https://www.example.com/watch'}; }, async reload() {}, async update(id,patch){return {id,...patch};}, async sendMessage() {}},
    action: {async setBadgeText() {}},
    webNavigation: {onCommitted: event('navigation')},
    debugger: {onDetach: event('detach'), async attach(target) { attached.push(target); }, async detach() {}, async sendCommand(target, method, params) { commands.push({target, method, params}); }}
  };
  const code = fs.readFileSync(new URL('../background.js', import.meta.url), 'utf8').replace(/^import[^\n]+\n/gm, '');
  vm.runInNewContext(code, {chrome, ...core,...continuationCore, URL, console});
  const ui = {url: chrome.runtime.getURL('popup.html')};
  const send = (message, sender = ui) => new Promise(resolve => listeners.message({tabId: 1, ...message}, sender, resolve));
  return {local, session, listeners, commands, attached, send, chrome};
}

test('storage queue keeps the newest speed and rejects bad rates', async () => {
  const h = harness();
  await Promise.all([h.send({type:'settings',speed:2,speedUpdated:200}),h.send({type:'settings',speed:1.5,speedUpdated:100})]);
  assert.equal(h.local['site:example.com'].speed,2);
  assert.equal((await h.send({type:'settings',speed:'2'})).ok,false);
});
test('renaming a history entry replaces its old key without deleting unrelated history', async () => {
  const h = harness();await h.send({type:'save',title:'Series',episode:1});await h.send({type:'save',title:'Other',episode:2});
  const result = await h.send({type:'save',title:'Renamed',replaceKey:'series',episode:3});
  assert.equal(result.ok,true);assert.equal(h.local.history.series,undefined);assert.equal(h.local.history.renamed.episode,3);assert.equal(h.local.history.other.episode,2);
});
test('content scripts cannot delete history or rename another entry', async () => {
  const h = harness();await h.send({type:'save',title:'Series',episode:1});
  const sender = {tab:{id:1},frameId:0};
  assert.equal((await h.send({type:'forget',title:'Series'},sender)).ok,false);
  await h.send({type:'save',title:'Other',replaceKey:'series',episode:2},sender);
  assert.ok(h.local.history.series);
});
test('spoofed message tab ID cannot override a content script sender tab', async () => {
  const h = harness();await h.send({type:'settings',tabId:999,speed:1.5},{tab:{id:1},frameId:0});
  assert.equal(h.local['site:example.com'].speed,1.5);
});
test('mobile emulation commands set the phone metrics; identity mode clears them', async () => {
  const h = harness();assert.equal((await h.send({type:'settings',mode:'mobile'})).ok,true);
  const metrics = h.commands.find(c=>c.method==='Emulation.setDeviceMetricsOverride');assert.equal(metrics.params.width,412);assert.equal(metrics.params.mobile,true);
  await h.send({type:'settings',mode:'identity'});assert.ok(h.commands.some(c=>c.method==='Emulation.clearDeviceMetricsOverride'));
});
test('a cancelled debugger remains detached instead of fighting DevTools/user choice', async () => {
  const h = harness();h.local['site:example.com']={mode:'mobile',speed:1};h.session.attached=[1];
  h.listeners.detach({tabId:1},'canceled_by_user');await new Promise(resolve=>setImmediate(resolve));
  assert.equal(h.attached.length,0);assert.equal(h.session.attached.length,0);
});

test('document-start messages use the sending main document, not a stale tab URL', async () => {
 const h=harness();await h.send({type:'save',title:'Series',episode:1},{tab:{id:1},frameId:0,url:'https://www.crunchyroll.com/watch/new'});
 assert.equal(h.local.history.series.host,'crunchyroll.com');assert.equal(h.local.history.series.url,'https://www.crunchyroll.com/watch/new');
});
test('private tabs do not expose or save episode history', async () => {
 const h=harness();h.local.history={series:{title:'Series',episode:1,updated:1}};h.chrome.tabs.get=async id=>({id,url:'https://example.com/watch',incognito:true});
 const state=await h.send({type:'state'});assert.equal(state.value.privateMode,true);assert.equal(state.value.history.length,0);
 assert.equal((await h.send({type:'save',title:'Private',episode:1})).ok,false);assert.equal(h.local.history.private,undefined);
});

test('continue here carries exact position/speed into site search and survives worker state requests',async()=>{
 const h=harness();h.chrome.tabs.get=async id=>({id,url:'https://www.crunchyroll.com/'});let navigated;
 h.chrome.tabs.update=async(id,patch)=>{navigated=patch.url;};
 h.local.history={series:{key:'series',title:'Series',season:2,episode:5,position:313,speed:1.5,host:'anikototv.to',url:'https://anikototv.to/watch/series/ep-5'}};
 const continued=await h.send({type:'continue',key:'series'});assert.equal(continued.ok,true);assert.equal(navigated,'https://www.crunchyroll.com/search?q=Series');
 const state=await h.send({type:'state'});assert.equal(state.value.continuation.entry.position,313);
 await h.send({type:'applyContinuation',identity:{title:'Series',season:2,episode:5}});assert.equal(h.local['site:crunchyroll.com'].speed,1.5);
 await h.send({type:'dismissReminder',id:'series|s2|e5'});assert.ok(h.local.reminderDismissals['crunchyroll.com|series|s2|e5']);assert.equal((await h.send({type:'state'})).value.continuation,null);
});
test('known target episodes bypass search and prior-site return sets up a resume handoff',async()=>{
 const h=harness();h.chrome.tabs.get=async id=>({id,url:'https://crunchyroll.com/'});let url;h.chrome.tabs.update=async(id,p)=>url=p.url;
 h.local.history={series:{key:'series',title:'Series',season:1,episode:5,position:42,host:'anikototv.to',url:'https://anikototv.to/watch/s/ep-5'}};
 h.local.episodes={'series|s1|e5':{locations:{'crunchyroll.com':'https://crunchyroll.com/watch/exact'}}};
 await h.send({type:'continue',key:'series'});assert.equal(url,'https://crunchyroll.com/watch/exact');
 await h.send({type:'returnPrevious',key:'series'});assert.equal(url,'https://anikototv.to/watch/s/ep-5');assert.equal(h.session['continue:1'].host,'anikototv.to');
});
test('episode archive preserves completed later episodes across rewinds and separate seasons',async()=>{
 const h=harness();await h.send({type:'save',title:'Series',season:1,episode:5,duration:100,ranges:[[0,90]]});await h.send({type:'save',title:'Series',season:2,episode:1});await h.send({type:'save',title:'Series',season:1,episode:2});assert.ok(h.local.episodes['series|s1|e5'].completed);assert.ok(h.local.episodes['series|s2|e1']);assert.ok(h.local.seasons['series|s1'].completedEpisodes.includes(5));
});
test('expired handoffs cannot apply and iframe requests cannot change continuation',async()=>{
 const h=harness();h.session['continue:1']={expires:1,host:'example.com'};assert.equal((await h.send({type:'applyContinuation'})).ok,false);assert.equal((await h.send({type:'continue',key:'x'},{tab:{id:1},frameId:3})).ok,false);
});
