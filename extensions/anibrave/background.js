import {hostKey, titleKey, recordEntry, mergeSettings} from './core.js';
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
async function handle(m,sender) {
  const extensionUI=sender.url===chrome.runtime.getURL('popup.html');
  const tabId = extensionUI ? m.tabId : sender.tab?.id;
  if(!Number.isInteger(tabId)) throw Error('No active tab.');
  const {tab,host,url} = await context(tabId,!extensionUI && sender.frameId===0 ? sender.url : undefined);
  if(m.type==='state') return {host,privateMode:!!tab.incognito,settings:mergeSettings(await get('site:'+host)||{},{}),history:tab.incognito?[]:Object.values(await get('history')||{}).sort((a,b)=>b.updated-a.updated)};
  if(tab.incognito && ['save','forget','progress'].includes(m.type)) throw Error('Episode memory is disabled in private windows.');
  if(m.type==='settings') return serial('site:'+host,async()=>{
    const settings = mergeSettings(await get('site:'+host)||{}, m);
    await chrome.storage.local.set({['site:'+host]:settings});
    if(m.mode!==undefined) { await serial('device',()=>device(tabId,settings.mode)); await chrome.tabs.reload(tabId); }
    return settings;
  });
  if(m.type==='progress') { if(!sender.tab || sender.frameId===0) throw Error('Invalid player frame.'); await chrome.tabs.sendMessage(tabId,{type:'playerProgress',position:m.position},{frameId:0}).catch(()=>{}); return; }
  if(m.type==='save') return serial('history',async()=>{
    const entry=recordEntry(m,host,url); const history=await get('history')||{};
    if(extensionUI && typeof m.replaceKey==='string' && m.replaceKey!==entry.key) delete history[m.replaceKey];
    history[entry.key]=entry;
    const trimmed=Object.fromEntries(Object.entries(history).sort((a,b)=>b[1].updated-a[1].updated).slice(0,1000));
    await chrome.storage.local.set({history:trimmed}); return entry;
  });
  if(m.type==='forget') { if(!extensionUI) throw Error('Use the extension to delete history.'); return serial('history',async()=>{const history=await get('history')||{}; delete history[titleKey(m.title)]; await chrome.storage.local.set({history});}); }
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
