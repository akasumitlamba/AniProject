import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

async function fixture(url, selectors, title = '') {
 let clock = 100000; const sent = [], events = {}; const attrs = new Map();
 const parent = {classList:{contains:()=>false},getRootNode:()=>({})};
 const video = {nodeType:1,localName:'video',tagName:'VIDEO',isConnected:true,clientWidth:640,clientHeight:360,parentElement:parent,classList:{contains:()=>false},matches: s=>s==='video,audio',querySelectorAll:()=>[],getRootNode:()=>({}),addEventListener:(name,fn)=>{events[name]=fn;},setAttribute:(k,v)=>attrs.set(k,v),getAttribute:k=>attrs.get(k),playbackRate:1,defaultPlaybackRate:1,duration:100,paused:false,seeking:false,currentTime:0,currentSrc:'https://example.com/video.webm'};
 const queryAll = s=>s==='video, audio'||s==='video,audio'?[video]:[];
 const root={nodeType:1,localName:'html',classList:{contains:()=>false},querySelectorAll:queryAll};
 const document={nodeType:9,documentElement:root,body:{},title,addEventListener(){},querySelector: s=>selectors[s]||null,querySelectorAll:queryAll,getElementById:()=>null};
 const window={addEventListener(){},postMessage(){}};
 const chrome={runtime:{async sendMessage(m){sent.push(m);return {ok:true,value:m.type==='state'?{host:new URL(url).hostname,settings:{speed:1,speedUpdated:0},history:[]}:null};},onMessage:{addListener(fn){events.message=fn;}}},storage:{onChanged:{addListener(){}}}};
 vm.runInNewContext(fs.readFileSync(new URL('../content.js',import.meta.url),'utf8'),{window,top:window,document,location:new URL(url),chrome,Date:{now:()=>clock},performance:{now:()=>clock},MutationObserver:class{observe(){}},setInterval(){},setTimeout,clearTimeout,console});
 await new Promise(resolve=>setImmediate(resolve));
 return {sent,selectors,video,parent,async tick(seconds=1){clock+=seconds*1000;video.currentTime+=seconds;events.timeupdate();await new Promise(resolve=>setImmediate(resolve));},async identify(){events.message({type:'playerProgress',position:42});await new Promise(resolve=>setImmediate(resolve));}};
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
