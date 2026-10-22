import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

async function fixture(url, selectors, title = '', options = {}) {
 let clock = 100000, timerId = 0; const sent = [], events = {}, documentEvents = {}, timers = new Map(), intervals = [], created = [];
 const parent = {classList:{contains:()=>false},getRootNode:()=>({})};
 function makeMedia(tag='VIDEO', width=640, height=360) {
  const attrs = new Map(), handlers = {};
  return {nodeType:1,localName:tag.toLowerCase(),tagName:tag,isConnected:true,clientWidth:width,clientHeight:height,parentElement:parent,classList:{contains:()=>false},matches:s=>s==='video,audio',querySelectorAll:()=>[],getRootNode:()=>({}),addEventListener:(name,fn)=>{handlers[name]=fn;},fire:name=>handlers[name]?.(),setAttribute:(k,v)=>attrs.set(k,v),getAttribute:k=>attrs.get(k),playbackRate:1,defaultPlaybackRate:1,duration:100,paused:false,seeking:false,currentTime:0,currentSrc:'https://example.com/video.webm'};
 }
 const video = makeMedia(), media = [video], hosts = [];
 const queryAll = s=>s==='video, audio'||s==='video,audio'?media:s==='*'?hosts:[];
 const root={nodeType:1,localName:'html',classList:{contains:()=>false},querySelectorAll:queryAll};
 function element(tag) { const el={tag,children:[],style:{},setAttribute(){},append(...children){this.children.push(...children);},remove(){this.removed=true;},attachShadow(){this.shadow=element('shadow');return this.shadow;}};created.push(el);return el; }
 const document={nodeType:9,documentElement:root,body:element('body'),title,addEventListener:(name,fn)=>{documentEvents[name]=fn;},createElement:element,querySelector:s=>selectors[s]||null,querySelectorAll:queryAll,getElementById:id=>created.find(el=>el.id===id&&!el.removed)||null};
 const windowEvents = {};
 const window={addEventListener:(name,fn)=>{windowEvents[name]=fn;},postMessage(data){queueMicrotask(()=>windowEvents.message?.({source:window,data:{source:'anibrave-page',type:'player-rate',id:data.id,rate:data.rate}}));}};
 const chrome={dom:{openOrClosedShadowRoot:el=>el.closedShadow||null},runtime:{async sendMessage(m){sent.push(m);if(m.type==='state'&&options.delayState)await options.delayState;return {ok:true,value:m.type==='state'?{host:new URL(url).hostname,settings:{speed:options.speed||1,speedUpdated:0},history:options.history||[]}:null};},onMessage:{addListener(fn){events.message=fn;}}},storage:{onChanged:{addListener(){}}}};
 const location=new URL(url);
 vm.runInNewContext(fs.readFileSync(new URL('../content.js',import.meta.url),'utf8'),{window,top:window,document,location,chrome,Date:{now:()=>clock},performance:{now:()=>clock},MutationObserver:class{observe(){}},setInterval:fn=>{intervals.push(fn);},setTimeout:(fn,ms)=>{timers.set(++timerId,{fn,at:clock+ms});return timerId;},clearTimeout:id=>timers.delete(id),queueMicrotask,console});
 const flush=async()=>{await new Promise(resolve=>setImmediate(resolve));};
 const advance=async(ms)=>{clock+=ms;for(const [id,t]of [...timers])if(t.at<=clock){timers.delete(id);t.fn();}await flush();};
 await flush();
 return {sent,selectors,video,parent,media,hosts,makeMedia,document,location,flush,advance,fire:(name,event)=>documentEvents[name]?.(event),async poll(){for(const fn of intervals)fn();await flush();},async tick(seconds=1,target=video){clock+=seconds*1000;target.currentTime+=seconds;target.fire('timeupdate');await flush();},async identify(){events.message({type:'playerProgress',position:42});await flush();}};
}

const text = textContent => ({textContent});
test('automatic memory requires 15 seconds of actual advancing playback',async()=>{
 const f=await fixture('https://crunchyroll.com/watch/fixture',{'[data-t="series-title"]':text('Series'),'[data-t="episode-title"]':text('Season 2 Episode 12')});
 assert.equal(f.sent.filter(m=>m.type==='save').length,0);await f.tick();for(let i=0;i<14;i++)await f.tick();assert.equal(f.sent.filter(m=>m.type==='save').length,0);await f.tick();const save=f.sent.find(m=>m.type==='save');assert.equal(save.episode,12);assert.equal(save.season,2);
});
test('pauses, seeks and ad time do not count as watched progress',async()=>{
 const f=await fixture('https://crunchyroll.com/watch/fixture',{'[data-t="series-title"]':text('Series'),'h1':text('Episode 1')});f.video.paused=true;for(let i=0;i<20;i++)await f.tick();f.video.paused=false;f.video.seeking=true;for(let i=0;i<20;i++)await f.tick();f.video.seeking=false;f.parent.classList.contains=name=>name==='ad-showing';for(let i=0;i<20;i++)await f.tick();assert.equal(f.sent.filter(m=>m.type==='save').length,0);
});
test('episode metadata changes reset the watched-time gate',async()=>{
 const selectors={'[data-t="series-title"]':text('Series'),'h1':text('Episode 1')};const f=await fixture('https://crunchyroll.com/watch/fixture',selectors);for(let i=0;i<15;i++)await f.tick();selectors.h1.textContent='Episode 2';await f.tick();assert.equal(f.sent.filter(m=>m.type==='save').length,0);
});
test('HiAnime internal URL IDs are never mistaken for episode numbers',async()=>{
 const selectors={'.film-name a':text('Series')};const f=await fixture('https://hianime.to/watch/series?ep=99999',selectors);await f.identify();assert.equal(f.sent.filter(m=>m.type==='save').length,0);
 selectors['.ep-item.active, [data-number].active']={getAttribute:()=> '12',textContent:'Episode 12'};await f.identify();assert.equal(f.sent.find(m=>m.type==='save').episode,12);
});
test('malformed AniKoto slugs do not crash the content controller',async()=>{
 const f=await fixture('https://anikototv.to/watch/bad-%ZZ-slug/ep-3',{});await f.identify();assert.equal(f.sent.find(m=>m.type==='save').episode,3);
});

test('compact Crunchyroll season/episode labels identify the real episode',async()=>{const f=await fixture('https://crunchyroll.com/watch/fixture',{'[data-t="series-title"]':text('Series'),'h1':text('S2E12 - Episode title')});await f.identify();const saved=f.sent.find(m=>m.type==='save');assert.equal(saved.episode,12);assert.equal(saved.season,2);});

test('polling, Play clicks and repeated startup resets cannot replace saved speed',async()=>{
 const f=await fixture('https://example.com/watch',{},'',{speed:2});
 for(let i=0;i<3;i++){f.video.playbackRate=1;f.video.fire('ratechange');await f.flush();assert.equal(f.video.playbackRate,2);}
 f.fire('pointerdown',{isTrusted:true,target:{closest(){return {textContent:'Play',getAttribute:()=>null};}}});
 f.video.playbackRate=1;f.video.fire('ratechange');await f.flush();assert.equal(f.video.playbackRate,2);
 await f.advance(2000);f.video.playbackRate=1;await f.poll();assert.equal(f.video.playbackRate,2);
 await f.advance(100);assert.equal(f.sent.filter(m=>m.type==='settings').length,0);
});
test('a deliberate speed control during startup wins and is remembered',async()=>{
 const f=await fixture('https://example.com/watch',{},'',{speed:2});
 f.fire('pointerdown',{isTrusted:true,target:{closest(){return {textContent:'1.5×',getAttribute:()=>null};}}});
 f.video.playbackRate=1.5;f.video.fire('ratechange');await f.flush();await f.advance(100);
 assert.equal(f.sent.find(m=>m.type==='settings').speed,1.5);assert.equal(f.video.playbackRate,1.5);
});
test('an early generic Play input cannot replace asynchronously loaded settings',async()=>{
 let release;const delayState=new Promise(resolve=>release=resolve);
 const f=await fixture('https://example.com/watch',{},'',{speed:2,delayState});
 f.fire('pointerdown',{isTrusted:true,target:{closest(){return {textContent:'Play',getAttribute:()=>null};}}});
 f.video.playbackRate=1.5;f.video.fire('ratechange');release();await f.flush();await f.advance(100);
 assert.equal(f.video.playbackRate,2);assert.equal(f.sent.filter(m=>m.type==='settings').length,0);
});
test('audio follows the saved speed but cannot author it over a visible video',async()=>{
 const f=await fixture('https://example.com/watch',{},'',{speed:2});const audio=f.makeMedia('AUDIO',0,0);audio.duration=Infinity;f.media.push(audio);await f.poll();assert.equal(audio.playbackRate,2);
 f.fire('pointerdown',{isTrusted:true,target:{closest(){return {textContent:'1.5×',getAttribute:()=>null};}}});audio.playbackRate=1;audio.fire('ratechange');await f.advance(100);
 assert.equal(f.video.playbackRate,2);assert.equal(f.sent.filter(m=>m.type==='settings').length,0);
});
test('generic closed shadow players on large pages attach during maintenance',async()=>{
 const f=await fixture('https://crunchyroll.com/watch/fixture',{'[data-t="series-title"]':text('Series'),'h1':text('Episode 1')},'',{speed:2});
 f.media.length=0;const video=f.makeMedia();const child={nodeType:1,localName:'div',classList:{contains:()=>false},querySelectorAll:s=>s==='video, audio'?[video]:[]};
 const shadow={nodeType:11,children:[child]};f.hosts.push(...Array.from({length:501},()=>({nodeType:1})),{nodeType:1,closedShadow:shadow});await f.poll();assert.equal(video.playbackRate,2);
 await f.tick(1,video);for(let i=0;i<15;i++){video.currentTime++;await f.tick(1,video);}assert.equal(f.sent.filter(m=>m.type==='save').length,1);
});
test('CDN and title refinements and a temporary identity gap preserve watch seconds',async()=>{
 const selectors={'[data-t="series-title"]':text('Series'),'h1':text('Episode 1')};const f=await fixture('https://crunchyroll.com/watch/fixture',selectors);
 for(let i=0;i<14;i++)await f.tick();f.video.currentSrc='https://cdn2/video';selectors['[data-t="series-title"]']=text('Series full title');await f.tick();
 delete selectors['[data-t="series-title"]'];delete selectors.h1;await f.tick(6);
 assert.equal(f.sent.filter(m=>m.type==='save').length,1);assert.equal(f.sent.find(m=>m.type==='save').title,'Series full title');
});
test('HiAnime homepage uses global memory and cannot save a featured episode',async()=>{
 const f=await fixture('https://hianime.to/home',{'.film-name a':text('Featured title'),'.ep-item.active, [data-number].active':{getAttribute:()=> '12',textContent:'Episode 12'}},'',{history:[{key:'remembered series',title:'Remembered series',episode:9,season:1,host:'crunchyroll.com'}]});
 const card=f.document.getElementById('anibrave-memory-card');assert.ok(card);assert.ok(card.shadow.children[1].children.some(el=>el.textContent?.startsWith('Remembered series')));
 await f.identify();for(let i=0;i<20;i++)await f.tick();assert.equal(f.sent.filter(m=>m.type==='save').length,0);
});
test('HiAnime legitimate series pages retain one-series memory without watch writes',async()=>{
 const f=await fixture('https://hianime.to/series-title-123',{'.film-name a':text('Series title'),'.ep-item.active, [data-number].active':{getAttribute:()=> '12',textContent:'Episode 12'}},'',{history:[{key:'series title',title:'Series title',episode:9,season:1,host:'crunchyroll.com'},{key:'other',title:'Other',episode:1,season:1,host:'crunchyroll.com'}]});
 const card=f.document.getElementById('anibrave-memory-card');assert.ok(card);assert.equal(card.shadow.children[1].children.filter(el=>el.tag==='p').length,1);
 await f.identify();for(let i=0;i<20;i++)await f.tick();assert.equal(f.sent.filter(m=>m.type==='save').length,0);
});

test('a missing episode heading preserves the current watch identity',async()=>{
 const selectors={'[data-t="series-title"]':text('Series'),'h1':text('S2E12')};const f=await fixture('https://crunchyroll.com/watch/fixture',selectors);
 for(let i=0;i<14;i++)await f.tick();delete selectors.h1;await f.tick(2);
 assert.equal(f.sent.find(m=>m.type==='save').episode,12);assert.equal(f.sent.find(m=>m.type==='save').season,2);
});
test('an early deliberate speed choice survives asynchronously loaded settings',async()=>{
 let release;const delayState=new Promise(resolve=>release=resolve);
 const f=await fixture('https://example.com/watch',{},'',{speed:2,delayState});
 f.fire('pointerdown',{isTrusted:true,target:{closest(){return {textContent:'1.5×',getAttribute:()=>null};}}});
 f.video.playbackRate=1.5;f.video.fire('ratechange');release();await f.flush();await f.advance(100);
 assert.equal(f.video.playbackRate,1.5);assert.equal(f.sent.find(m=>m.type==='settings').speed,1.5);
});
