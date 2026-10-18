import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import * as core from '../core.js';

function harness() {
  const local = {}, session = {}, listeners = {}, commands = [], attached = [];
  const event = name => ({addListener(fn) { listeners[name] = fn; }});
  const store = data => ({async get(key) { return {[key]: structuredClone(data[key])}; }, async set(patch) { Object.assign(data, structuredClone(patch)); }});
  const chrome = {
    runtime: {getURL: path => 'chrome-extension://fixture/' + path, onMessage: event('message')},
    storage: {local: store(local), session: store(session)},
    tabs: {async get(id) { return {id, url: 'https://www.example.com/watch'}; }, async reload() {}, async sendMessage() {}},
    action: {async setBadgeText() {}},
    webNavigation: {onCommitted: event('navigation')},
    debugger: {onDetach: event('detach'), async attach(target) { attached.push(target); }, async detach() {}, async sendCommand(target, method, params) { commands.push({target, method, params}); }}
  };
  const code = fs.readFileSync(new URL('../background.js', import.meta.url), 'utf8').replace(/^import[^\n]+\n/, '');
  vm.runInNewContext(code, {chrome, ...core, URL, console});
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
