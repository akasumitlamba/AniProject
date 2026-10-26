const test=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const source=fs.readFileSync(require('node:path').join(__dirname,'../src/playback.js'),'utf8');
function fixture(){
  const listeners=new Map(), messages=[], bridge=[], timers=new Map(), intervals=[];let clock=10000, next=0;
  class Clock extends Date{static now(){return clock;}}
  const video={tagName:'VIDEO',isConnected:true,playbackRate:1,defaultPlaybackRate:1};
  const document={fullscreenElement:null,scrollingElement:{scrollTop:0},title:'Episode',
    querySelectorAll(selector){return selector==='video,audio'?[video]:[];},
    addEventListener(name,fn){const list=listeners.get(name)||[];list.push(fn);listeners.set(name,list);}};
  const window={postMessage:data=>bridge.push(data),addEventListener:(name,fn)=>document.addEventListener(name,fn)};
  const context=vm.createContext({document,window,location:{href:'https://example.com/episode'},Date:Clock,setInterval:fn=>{intervals.push(fn);return intervals.length;},clearInterval:()=>{},setTimeout:(fn,ms)=>{timers.set(++next,{fn,at:clock+ms});return next;},clearTimeout:id=>timers.delete(id),aniBraveNative:payload=>messages.push(JSON.parse(payload))});
  vm.runInContext(source,context);
  return {context,video,messages,bridge,document,tick(ms){clock+=ms;for(const [id,t] of [...timers])if(t.at<=clock){timers.delete(id);t.fn();}},recover(){intervals.forEach(fn=>fn());},fire:(name,event={target:video})=>(listeners.get(name)||[]).forEach(fn=>fn(event))};
}
test('saved speed applies immediately and survives player metadata/loading',()=>{
  const f=fixture();f.context.__aniBrave.configure(1.75);assert.equal(f.video.playbackRate,1.75);
  f.video.playbackRate=1;f.fire('loadedmetadata');assert.equal(f.video.playbackRate,1.75);
});
test('a replacement video receives the saved speed on its playing event',()=>{
  const f=fixture();f.context.__aniBrave.configure(2);
  const replacement={tagName:'VIDEO',isConnected:true,playbackRate:1};f.fire('playing',{target:replacement});assert.equal(replacement.playbackRate,2);
});
test('rate rejection retries are bounded and an explicit new speed resets the bound',()=>{
  const f=fixture();f.context.__aniBrave.configure(2);
  for(let i=0;i<30;i++){f.video.playbackRate=1;f.fire('ratechange');}
  assert.equal(f.messages.filter(m=>m.type==='rateRejected').length,1);
  f.context.__aniBrave.configure(1.5);assert.equal(f.video.playbackRate,1.5);
});
test('controllers in separate frame contexts accept the same top-site setting',()=>{
  const top=fixture(), embedded=fixture();top.context.__aniBrave.configure(1.25);embedded.context.__aniBrave.configure(1.25);
  assert.equal(top.video.playbackRate,1.25);assert.equal(embedded.video.playbackRate,1.25);
});
test('fullscreen reports state without changing website controls or moving DOM',()=>{
  const f=fixture();f.video.controls=false;f.document.fullscreenElement={};f.fire('fullscreenchange');
  assert.equal(f.video.controls,false);assert.deepEqual(f.messages.at(-1),{type:'fullscreen',enabled:true});
});
test('scrolling and vertical gestures reveal tools without consuming touch events',()=>{
  const f=fixture();f.document.scrollingElement.scrollTop=70;f.fire('scroll',{target:f.document});
  f.fire('touchstart',{touches:[{clientY:100}]});f.fire('touchmove',{touches:[{clientY:50}]});
  assert.equal(f.messages.filter(m=>m.type==='reveal').length,2);
});
test('multiple installations do not register duplicate listeners',()=>{
  const f=fixture();vm.runInContext(source,f.context);f.document.fullscreenElement={};f.fire('fullscreenchange');assert.equal(f.messages.length,1);
});
test('invalid rates leave the active speed untouched',()=>{
  const f=fixture();f.context.__aniBrave.configure(1.5);f.context.__aniBrave.configure(10);assert.equal(f.video.playbackRate,1.5);
});

test('a deliberate native speed choice updates the browser setting',()=>{
 const f=fixture();f.context.__aniBrave.configure(2,123);f.tick(700);
 f.fire('pointerdown',{isTrusted:true,target:{textContent:'1.5×',closest(){return this;}}});
 f.video.playbackRate=1.5;f.fire('ratechange');f.tick(130);
 assert.deepEqual(f.messages.find(m=>m.type==='nativeRate'),{type:'nativeRate',rate:1.5,revision:123});
});
test('scripted clamps and play clicks cannot replace an above-menu browser speed',()=>{
 const f=fixture();f.context.__aniBrave.configure(3,123);f.tick(700);
 f.fire('pointerdown',{isTrusted:true,target:{textContent:'Play',closest(){return this;}}});
 f.video.playbackRate=1.5;f.fire('ratechange');f.tick(200);
 assert.equal(f.video.playbackRate,3);assert.equal(f.messages.some(m=>m.type==='nativeRate'),false);
});
test('episode changes reset retry budget and missed resets recover',()=>{
 const f=fixture();f.context.__aniBrave.configure(2);
 for(let episode=0;episode<12;episode++){
  f.video.playbackRate=1;f.fire('loadstart');assert.equal(f.video.playbackRate,2);
 }
 f.video.playbackRate=1;f.tick(6000);f.recover();assert.equal(f.video.playbackRate,2);
});
test('newer browser command cancels a delayed native selection',()=>{
 const f=fixture();f.context.__aniBrave.configure(2,12);f.tick(700);
 f.fire('pointerdown',{isTrusted:true,target:{textContent:'1.5×',closest(){return this;}}});
 f.video.playbackRate=1.5;f.fire('ratechange');f.context.__aniBrave.configure(3,13);f.tick(130);
 assert.equal(f.video.playbackRate,3);assert.equal(f.messages.some(m=>m.type==='nativeRate'),false);
 f.context.__aniBrave.configure(1,12);assert.equal(f.video.playbackRate,3);
});
test('ads do not overwrite saved speed',()=>{
 const f=fixture();f.context.__aniBrave.configure(2,12);f.tick(700);
 f.video.classList={contains:n=>n==='ad-showing'};
 f.fire('pointerdown',{isTrusted:true,target:{textContent:'1.5×',closest(){return this;}}});
 f.video.playbackRate=1;f.fire('ratechange');f.tick(200);
 assert.equal(f.messages.some(m=>m.type==='nativeRate'),false);
});

test('periodic recovery cannot race a pending native speed selection',()=>{
 const f=fixture();f.context.__aniBrave.configure(3,12);f.tick(700);
 f.fire('pointerdown',{isTrusted:true,target:{textContent:'1.5×',closest(){return this;}}});
 f.video.playbackRate=1.5;f.fire('ratechange');f.recover();f.tick(130);
 assert.equal(f.video.playbackRate,1.5);assert.equal(f.messages.find(m=>m.type==='nativeRate').rate,1.5);
});

test('blank page clicks do not become speed intent from unrelated page text',()=>{
 const f=fixture();f.context.__aniBrave.configure(3,12);f.tick(700);
 f.fire('pointerdown',{isTrusted:true,target:{textContent:'Choose speed 1.5× elsewhere',closest(){return null;}}});
 f.video.playbackRate=1;f.fire('ratechange');f.tick(200);
 assert.equal(f.video.playbackRate,3);assert.equal(f.messages.some(m=>m.type==='nativeRate'),false);
});

test('numeric-only native speed menu labels work after the menu-open grace period',()=>{
 const f=fixture();f.context.__aniBrave.configure(3,12);f.tick(5000);
 f.fire('pointerdown',{isTrusted:true,target:{textContent:'1.5',closest(){return this;},getAttribute:n=>n==='role'?'menuitemradio':null}});
 f.video.playbackRate=1.5;f.fire('ratechange');f.tick(130);
 assert.equal(f.messages.find(m=>m.type==='nativeRate').rate,1.5);
});

test('late first-level and nested shadow players inherit saved speed without duplicate listeners',()=>{
  const f=fixture();f.context.__aniBrave.configure(1.75,123);
  let registrations=0;
  const outer={querySelectorAll:()=>[],addEventListener(){registrations++;}};
  f.document.querySelectorAll=s=>s==='*'?[{shadowRoot:outer}]:[f.video];
  for(let i=0;i<6;i++)f.recover();
  const replacement={tagName:'VIDEO',isConnected:true,playbackRate:1};
  const inner={querySelectorAll:s=>s==='video,audio'?[replacement]:[],addEventListener(){registrations++;}};
  outer.querySelectorAll=s=>s==='*'?[{shadowRoot:inner}]:[];
  for(let i=0;i<12;i++)f.recover();
  assert.equal(replacement.playbackRate,1.75);
  assert.equal(registrations,18);
});

test('saved speed reaches a first-level shadow player attached after initialization',()=>{
  const f=fixture();f.context.__aniBrave.configure(2,123);
  const video={tagName:'VIDEO',isConnected:true,playbackRate:1};
  const root={querySelectorAll:s=>s==='video,audio'?[video]:[],addEventListener(){}};
  f.document.querySelectorAll=s=>s==='*'?[{shadowRoot:root}]:[f.video];
  for(let i=0;i<6;i++)f.recover();
  assert.equal(video.playbackRate,2);
});

function bufferFixture(){
 const f=fixture();let end=10;let pauses=0,plays=0;
 Object.assign(f.video,{duration:120,currentTime:10,currentSrc:'episode.mp4',paused:false,seeking:false,preload:'metadata',buffered:{length:1,start:()=>0,end:()=>end},pause(){this.paused=true;pauses++;f.fire('pause');},play(){this.paused=false;plays++;return Promise.resolve();}});
 return Object.assign(f,{setEnd:n=>end=n,pauses:()=>pauses,plays:()=>plays});
}
test('buffer preference requests preload without reloading media and can restore site default',()=>{
 const f=bufferFixture();f.context.__aniBrave.configure(1,0,180,false);assert.equal(f.video.preload,'auto');
 f.context.__aniBrave.configure(1,0,0,false);assert.equal(f.video.preload,'metadata');assert.equal(f.pauses(),0);
});
test('buffer guard waits for a rate-aware cushion then resumes its own pause once',()=>{
 const f=bufferFixture();f.context.__aniBrave.configure(2,0,180,true);f.fire('waiting');assert.equal(f.pauses(),1);
 f.setEnd(30);f.recover();assert.equal(f.plays(),0);f.setEnd(40);f.recover();assert.equal(f.plays(),1);f.recover();assert.equal(f.plays(),1);
});
test('trusted input cancels automatic buffer resume',()=>{
 const f=bufferFixture();f.context.__aniBrave.configure(1,0,180,true);f.fire('waiting');f.fire('pointerdown',{isTrusted:true,target:{closest:()=>null}});f.setEnd(50);f.recover();assert.equal(f.plays(),0);
});
test('buffer guard does not pause live streams or disabled sessions',()=>{
 const f=bufferFixture();f.context.__aniBrave.configure(1,0,180,false);f.fire('waiting');assert.equal(f.pauses(),0);
 f.video.duration=Infinity;f.context.__aniBrave.configure(1,0,180,true);f.fire('waiting');assert.equal(f.pauses(),0);
});
test('guard stops at a bounded deadline without forcing unbuffered playback',()=>{
 const f=bufferFixture();f.context.__aniBrave.configure(1,0,180,true);f.fire('waiting');f.tick(21000);f.recover();assert.equal(f.plays(),0);assert.ok(f.messages.some(m=>m.type==='bufferGuard'&&!m.waiting));f.setEnd(60);f.recover();assert.equal(f.plays(),0);
});
test('source change, hiding or seeking never causes a stale automatic resume',()=>{
 for(const change of [f=>f.video.currentSrc='next.mp4',f=>f.document.visibilityState='hidden',f=>f.video.seeking=true]){
  const f=bufferFixture();f.context.__aniBrave.configure(1,0,180,true);f.fire('waiting');change(f);f.setEnd(60);f.recover();assert.equal(f.plays(),0);
 }
});

test('a capped player resumes from a useful plateau before the full guard deadline',()=>{
 const f=bufferFixture();f.context.__aniBrave.configure(1,0,180,true);f.fire('waiting');f.setEnd(15);f.recover();assert.equal(f.plays(),0);f.tick(4000);f.recover();assert.equal(f.plays(),1);
});

test('VOD becoming live restores preload and sends player-buffer restoration',()=>{
 const f=bufferFixture();f.context.__aniBrave.configure(1,0,180,true);assert.equal(f.bridge.filter(m=>m.type==='set-buffer').at(-1).seconds,180);
 f.video.duration=Infinity;f.fire('durationchange');assert.equal(f.bridge.filter(m=>m.type==='set-buffer').at(-1).seconds,0);assert.equal(f.video.preload,'metadata');assert.equal(f.pauses(),0);
});

test('genuine speed choice during metadata loading overrides startup suppression',()=>{
 const f=fixture();f.video.readyState=1;f.context.__aniBrave.configure(2,123);f.fire('loadstart');f.tick(100);
 f.fire('pointerdown',{isTrusted:true,target:{textContent:'1.5×',closest(){return this;}}});
 f.video.playbackRate=1.5;f.fire('ratechange');f.tick(130);
 assert.equal(f.video.playbackRate,1.5);assert.equal(f.messages.find(m=>m.type==='nativeRate').rate,1.5);
});

test('hidden paused players skip recovery but background playback keeps saved speed',()=>{
  const f=fixture();f.context.__aniBrave.configure(2);f.document.visibilityState='hidden';
  f.video.paused=true;f.video.playbackRate=1;f.tick(6000);f.recover();
  assert.equal(f.video.playbackRate,1);
  f.video.paused=false;f.recover();assert.equal(f.video.playbackRate,2);
  f.video.paused=true;f.video.playbackRate=1;f.document.visibilityState='visible';
  f.fire('visibilitychange');assert.equal(f.video.playbackRate,2);
});
test('resize bursts send one viewport update and later resize remains supported',()=>{
  const f=fixture();for(let i=0;i<30;i++)f.fire('resize');
  assert.equal(f.messages.filter(m=>m.type==='resize').length,0);
  f.tick(100);assert.equal(f.messages.filter(m=>m.type==='resize').length,1);
  f.fire('resize');f.tick(100);assert.equal(f.messages.filter(m=>m.type==='resize').length,2);
});
