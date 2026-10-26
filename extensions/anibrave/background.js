import {hostKey, titleKey, recordEntry, mergeSettings} from './core.js';
import {episodeID,seriesID,searchURL,safeURL,updateEpisode} from './continuation-core.js';
const queues = new Map();
function serial(key, fn) { const next = (queues.get(key) || Promise.resolve()).catch(()=>{}).then(fn); queues.set(key,next); next.finally(()=>{if(queues.get(key)===next) queues.delete(key);}).catch(()=>{}); return next; }
const get = async key => (await chrome.storage.local.get(key))[key];
async function context(tabId, sourceURL) { const tab = await chrome.tabs.get(tabId); const url = sourceURL || tab.url; const host = hostKey(url); if(!host) throw Error('Open a regular website to use AniBrave.'); return {tab,host,url}; }
const mobileUA = 'Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36';
const dropping = new Set(), suppressReapply = new Set();
async function device(tabId, mode, retry = true) {
  const target = {tabId}; const own = await chrome.storage.session.get('attached'); const attached = own.attached || [];
  if(mode === 'desktop') {
    if(attached.includes(tabId)) {
      dropping.add(tabId);
      try { await chrome.debugger.detach(target); } catch { /* already detached */ }
      finally { dropping.delete(tabId); }
      await chrome.storage.session.set({attached:attached.filter(x=>x!==tabId)});
    }
    return;
  }
  if(!attached.includes(tabId)) { await chrome.debugger.attach(target,'1.3'); await chrome.storage.session.set({attached:[...attached,tabId]}); }
  try {
    await chrome.debugger.sendCommand(target,'Emulation.setUserAgentOverride',{userAgent:mobileUA,platform:'Linux armv8l',userAgentMetadata:{brands:[{brand:'Chromium',version:'130'}],fullVersionList:[{brand:'Chromium',version:'130.0.0.0'}],platform:'Android',platformVersion:'13.0.0',architecture:'arm',model:'Pixel 7',mobile:true}});
    if(mode==='mobile') await chrome.debugger.sendCommand(target,'Emulation.setDeviceMetricsOverride',{width:412,height:915,screenWidth:412,screenHeight:915,deviceScaleFactor:1,mobile:true});
    else await chrome.debugger.sendCommand(target,'Emulation.clearDeviceMetricsOverride');
    await chrome.debugger.sendCommand(target,'Emulation.setTouchEmulationEnabled',{enabled:mode==='mobile',maxTouchPoints:1});
  } catch(error) {
    if(retry && mode!=='desktop') {
      dropping.add(tabId);
      try { await chrome.debugger.detach(target).catch(()=>{}); }
      finally { dropping.delete(tabId); }
      const fresh=await chrome.storage.session.get('attached');
      await chrome.storage.session.set({attached:(fresh.attached||[]).filter(x=>x!==tabId)});
      return device(tabId, mode, false);
    }
    suppressReapply.add(tabId);
    try { await device(tabId,'desktop', false); } finally { suppressReapply.delete(tabId); }
    throw error;
  }
}
async function getContinuation(tabId) {
  const key='continue:'+tabId;
  const pending=(await chrome.storage.session.get(key))[key];
  return pending && pending.expires>Date.now()?pending:null;
}
async function handle(m,sender) {
  const extensionUI=sender.url===chrome.runtime.getURL('popup.html');
  const tabId = extensionUI ? m.tabId : sender.tab?.id;
  if(!Number.isInteger(tabId)) throw Error('No active tab.');
  const {tab,host,url} = await context(tabId,!extensionUI && sender.frameId===0 ? sender.url : undefined);
  if(m.type==='state') return {host,privateMode:!!tab.incognito,continuation:tab.incognito?null:await getContinuation(tabId),dismissals:tab.incognito?{}:await get('reminderDismissals')||{},settings:mergeSettings(await get('site:'+host)||{},{}),history:tab.incognito?[]:Object.values(await get('history')||{}).sort((a,b)=>b.updated-a.updated)};
  if(tab.incognito && ['save','forget','progress','continue','dismissReminder','finishContinuation','applyContinuation','returnPrevious'].includes(m.type)) throw Error('Episode memory is disabled in private windows.');
  if(['continue','dismissReminder','finishContinuation','applyContinuation','returnPrevious'].includes(m.type)) {
    if(!extensionUI && (!sender.tab || sender.frameId!==0)) throw Error('Use the main page.');
    if(m.type==='dismissReminder') return serial('dismissals',async()=>{
      const dismissals=await get('reminderDismissals')||{};
      dismissals[host+'|'+String(m.id).slice(0,300)]=Date.now();
      const bounded=Object.fromEntries(Object.entries(dismissals).sort((a,b)=>b[1]-a[1]).slice(0,1000));
      await chrome.storage.local.set({reminderDismissals:bounded});
      await chrome.storage.session.set({['continue:'+tabId]:null});return;
    });
    if(m.type==='finishContinuation') {await chrome.storage.session.set({['continue:'+tabId]:null});return;}
    if(m.type==='returnPrevious') {
      const history=await get('history')||{};const entry=history[m.key];const destination=safeURL(entry?.url);
      if(!entry||!destination)throw Error('Saved website unavailable.');
      await chrome.storage.session.set({['continue:'+tabId]:{entry,host:hostKey(destination),expires:Date.now()+30*60*1000,applied:false}});
      await chrome.tabs.update(tabId,{url:destination});return {destination};
    }
    if(m.type==='continue') {
      const history=await get('history')||{};let entry=history[m.key];if(!entry)throw Error('This saved episode is no longer available.');
      if(!entry.speed)entry={...entry,speed:(await get('site:'+entry.host))?.speed||1};
      const episodes=await get('episodes')||{};
      const known=episodes[episodeID(entry)]?.locations?.[host];
      const destination=safeURL(known,host)|| (entry.host===host?safeURL(entry.url,host):null) || searchURL(url,entry.title);
      const pending={entry,host,expires:Date.now()+30*60*1000,applied:false};
      await chrome.storage.session.set({['continue:'+tabId]:pending});
      if(destination && destination!==url)await chrome.tabs.update(tabId,{url:destination});
      else await chrome.tabs.sendMessage(tabId,{type:'continuationChanged'},{frameId:0}).catch(()=>{});
      return {destination,pending};
    }
    const pending=await getContinuation(tabId);if(!pending || pending.host!==host)throw Error('The continuation expired. Choose Continue here again.');
    if(m.identity?.title && Number(m.identity.episode)>0){
      const aliases=await get('titleAliases')||{};
      aliases[host+'|'+titleKey(m.identity.title)+'|s'+Number(m.identity.season||1)]={title:pending.entry.title,season:pending.entry.season,offset:pending.entry.episode-Number(m.identity.episode)};
      await chrome.storage.local.set({titleAliases:aliases});
    }
    if(/^\/watch\//.test(new URL(url).pathname))await serial('history',async()=>{
      const episodes=await get('episodes')||{},id=episodeID(pending.entry);
      episodes[id]={...pending.entry,...episodes[id],locations:{...episodes[id]?.locations,[host]:url}};await chrome.storage.local.set({episodes});
    });
    const rate=Number(pending.entry.speed);
    if(Number.isFinite(rate) && rate>=.25 && rate<=5)await serial('site:'+host,async()=>{
      const settings=mergeSettings(await get('site:'+host)||{},{speed:rate});await chrome.storage.local.set({['site:'+host]:settings});
    });
    // Persist the handoff before broadcasting; retries on metadata events remain safe.
    pending.applied=true;await chrome.storage.session.set({['continue:'+tabId]:pending});
    await chrome.tabs.sendMessage(tabId,{type:'resumeEpisode',entry:pending.entry}).catch(()=>{});return;
  }
  if(m.type==='settings') return serial('site:'+host,async()=>{
    const settings = mergeSettings(await get('site:'+host)||{}, m);
    await chrome.storage.local.set({['site:'+host]:settings});
    if(m.mode!==undefined) { await serial('device',()=>device(tabId,settings.mode)); await chrome.tabs.reload(tabId); }
    return settings;
  });
  if(m.type==='progress') { if(!sender.tab || sender.frameId===0) throw Error('Invalid player frame.'); await chrome.tabs.sendMessage(tabId,{type:'playerProgress',position:m.position,duration:m.duration,watchedSeconds:m.watchedSeconds,ranges:m.ranges},{frameId:0}).catch(()=>{}); return; }
  if(m.type==='save') return serial('history',async()=>{
    const aliases=await get('titleAliases')||{};
    const alias=!extensionUI&&aliases[host+'|'+titleKey(m.title)+'|s'+Number(m.season||1)];
    const entry=recordEntry(alias?{...m,title:alias.title,season:alias.season,episode:Number(m.episode)+alias.offset}:m,host,url); const history=await get('history')||{};
    const settings=await get('site:'+host);entry.speed=settings?.speed||1;entry.duration=Number.isFinite(Number(m.duration))?Math.max(0,Number(m.duration)):0;
    const episodes=await get('episodes')||{};const eid=episodeID(entry),previous=episodes[eid];
    const episode=updateEpisode(previous,{...entry,duration:Number(m.duration)||0,watchedSeconds:Math.max(previous?.watchedSeconds||0,Number(m.watchedSeconds)||0),ranges:Array.isArray(m.ranges)?m.ranges.slice(0,1000):[],locations:{...previous?.locations,[host]:url}});
    entry.completed=episode.completed;
    episodes[eid]=episode;
    await chrome.storage.local.set({episodes:Object.fromEntries(Object.entries(episodes).sort((a,b)=>b[1].updated-a[1].updated).slice(0,5000))});
    if(extensionUI && typeof m.replaceKey==='string' && m.replaceKey!==entry.key) delete history[m.replaceKey];
    history[entry.key]=entry;
    const seasons=await get('seasons')||{};const sid=seriesID(entry);
    seasons[sid]={...entry,completedEpisodes:Object.values(episodes).filter(e=>seriesID(e)===sid&&e.completed).map(e=>e.episode)};
    await chrome.storage.local.set({seasons:Object.fromEntries(Object.entries(seasons).sort((a,b)=>b[1].updated-a[1].updated).slice(0,2000))});
    const trimmed=Object.fromEntries(Object.entries(history).sort((a,b)=>b[1].updated-a[1].updated).slice(0,1000));
    await chrome.storage.local.set({history:trimmed}); return entry;
  });
  if(m.type==='forget') { if(!extensionUI) throw Error('Use the extension to delete history.'); return serial('history',async()=>{const history=await get('history')||{}; delete history[titleKey(m.title)];const episodes=await get('episodes')||{},seasons=await get('seasons')||{};for(const [id,e]of Object.entries(episodes))if(titleKey(e.title)===titleKey(m.title))delete episodes[id];for(const [id,e]of Object.entries(seasons))if(titleKey(e.title)===titleKey(m.title))delete seasons[id];await chrome.storage.local.set({history,episodes,seasons});}); }
  throw Error('Unknown request.');
}
chrome.runtime.onMessage.addListener((m,sender,reply)=>{handle(m,sender).then(value=>reply({ok:true,value})).catch(e=>reply({ok:false,error:e.message}));return true;});
chrome.webNavigation.onCommitted.addListener(({tabId,frameId,url})=>{
  if(frameId!==0)return;
  serial('device',async()=>{
    const host=hostKey(url); const stored=host&&await get('site:'+host);
    const mode=['desktop','mobile','identity'].includes(stored?.mode)?stored.mode:'desktop';
    await device(tabId,mode);
    await chrome.action.setBadgeText({tabId,text:''}).catch(()=>{});
  }).catch(async()=>{await chrome.action.setBadgeText({tabId,text:'!'}).catch(()=>{});});
});
chrome.debugger.onDetach.addListener(({tabId})=>{
  const intentional=dropping.has(tabId)||suppressReapply.has(tabId);
  serial('device',async()=>{
    const {attached=[]}=await chrome.storage.session.get('attached');
    await chrome.storage.session.set({attached:attached.filter(x=>x!==tabId)});
    // A cancelled debugger notice/DevTools detachment must stay detached.
    // Saved emulation is reapplied on navigation or an explicit View selection.
    if (!intentional) await chrome.action.setBadgeText({tabId,text:'!'}).catch(()=>{});
  }).catch(()=>{});
});
