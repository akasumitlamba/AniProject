const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');

function player(initial = 1, acceptsExtra = false) {
 let actual = initial; const calls = []; const allowed = [.25,.5,.75,1,1.25,1.5,1.75,2];
 const native = {classList:{contains:()=>false},getPlaybackRate:()=>actual,getAvailablePlaybackRates:()=>allowed,setPlaybackRate(n){calls.push(n);actual=acceptsExtra?n:allowed.reduce((best,r)=>Math.abs(r-n)<Math.abs(best-n)?r:best);},getRootNode:()=>({})};
 return {native,calls};
}
function bridge(first) {
 const unrelated = player(), responses = [];let listener;
 class Media { constructor(parent,id){this.parentElement=parent;this.id=id;this.value=1;this.isConnected=true;this.duration=120;this.classList={contains:()=>false};}getAttribute(){return this.id;} }
 Object.defineProperty(Media.prototype,'playbackRate',{get(){return this.value;},set(n){this.value=n;},configurable:true});
 const media = [new Media(first.native,'1'),new Media(unrelated.native,'2')];
 const inputs={};let observe;
 class Observer{constructor(fn){observe=fn;}observe(){}disconnect(){}}
 const window = {addEventListener(type,fn){listener=fn;},postMessage(data){responses.push(data);}};
 const document = {addEventListener(type,fn){inputs[type]=fn;},querySelectorAll(selector){return selector==='video,audio'?media:[];}};
 vm.runInNewContext(fs.readFileSync(require('node:path').join(__dirname,'../src/player.js'),'utf8'),{window,document,console,HTMLMediaElement:Media,MutationObserver:Observer});
 function send(rate,id=1){listener({source:window,data:{source:'anibrave-app',type:'set-rate',id,media:'1',rate}});return responses.at(-1);}
 return {send,buffer:seconds=>listener({source:window,data:{source:'anibrave-app',type:'set-buffer',id:1,media:'1',seconds}}),responses,unrelated,media,inputs,mutate:()=>observe()};
}
test('native rate omitted by an old advertised list remains selected',()=>{const p=player(3);const b=bridge(p);assert.equal(b.send(3).rate,3);assert.equal(p.calls.length,0);});
test('requested rates accepted by the real API override a stale capability list',()=>{const p=player(1,true);const b=bridge(p);assert.equal(b.send(3).rate,3);assert.equal(p.native.getPlaybackRate(),3);});
test('browser speed overrides a native menu limit without repeated media resets',()=>{const p=player();const b=bridge(p);assert.equal(b.send(4).rate,4);assert.equal(p.native.getPlaybackRate(),2);assert.equal(b.media[0].playbackRate,4);b.media[0].playbackRate=1;assert.equal(b.media[0].playbackRate,4);assert.equal(p.calls.length,1);});
test('a trusted site control can replace a forced browser speed',()=>{const b=bridge(player());b.send(4);b.inputs.pointerdown({isTrusted:false});b.media[0].playbackRate=1;assert.equal(b.media[0].playbackRate,4);b.inputs.pointerdown({isTrusted:true});b.media[0].playbackRate=1.5;assert.equal(b.media[0].playbackRate,1.5);});
test('ads and detached media release the override',()=>{const p=player(),b=bridge(p);b.send(4);p.native.classList.contains=c=>c==='ad-showing';b.mutate();b.media[0].playbackRate=1;assert.equal(b.media[0].playbackRate,1);p.native.classList.contains=()=>false;b.send(4);b.media[0].isConnected=false;b.mutate();b.media[0].playbackRate=1;assert.equal(b.media[0].playbackRate,1);});
test('only the corresponding player is changed',()=>{const p=player();const b=bridge(p);assert.equal(b.send(1.5).rate,1.5);assert.equal(b.unrelated.calls.length,0);});
test('invalid bridge messages cannot call native player methods',()=>{const p=player();const b=bridge(p);b.send(Infinity);b.send(6);b.send('2');b.send(2,-1);assert.equal(p.calls.length,0);assert.equal(b.responses.length,0);});

test('HLS buffer target is scoped to the media and preserves byte caps and site defaults',()=>{
 const b=bridge(player()),v=b.media[0];v.hls={media:v,config:{maxBufferLength:30,maxMaxBufferLength:600,maxBufferSize:60000000}};
 b.buffer(180);assert.equal(v.hls.config.maxBufferLength,180);assert.equal(v.hls.config.maxBufferSize,60000000);b.buffer(0);assert.equal(v.hls.config.maxBufferLength,30);
});
test('buffer bridge rejects invalid values and avoids live or unrelated HLS instances',()=>{
 const b=bridge(player()),v=b.media[0];v.hls={media:b.media[1],config:{maxBufferLength:30}};b.buffer(180);assert.equal(v.hls.config.maxBufferLength,30);
 v.hls.media=v;v.hls.levels=[{details:{live:true}}];b.buffer(180);assert.equal(v.hls.config.maxBufferLength,30);
 v.hls.levels=[];for(const n of [-1,Infinity,601,'180'])b.buffer(n);assert.equal(v.hls.config.maxBufferLength,30);
});
test('Shaka buffering uses only its matching player and restores the original goal',()=>{
 const b=bridge(player()),v=b.media[0];let goal=30;
 v.shakaPlayer={getMediaElement:()=>v,getConfiguration:()=>({streaming:{bufferingGoal:goal}}),configure:c=>goal=c.streaming.bufferingGoal};
 b.buffer(180);assert.equal(goal,180);b.buffer(0);assert.equal(goal,30);
});

test('an infinite live duration restores any previous VOD buffer override',()=>{
 const b=bridge(player()),v=b.media[0];v.hls={media:v,config:{maxBufferLength:30,maxMaxBufferLength:600}};b.buffer(180);v.duration=Infinity;b.buffer(180);assert.equal(v.hls.config.maxBufferLength,30);
});

test('a player reused with a replacement media element retains its original buffer baseline',()=>{
 const b=bridge(player()),old=b.media[0],hls={media:old,config:{maxBufferLength:30,maxMaxBufferLength:600}};old.hls=hls;b.buffer(180);
 const next=new old.constructor(old.parentElement,'1');next.hls=hls;hls.media=next;b.media[0]=next;b.buffer(300);b.buffer(0);assert.equal(hls.config.maxBufferLength,30);
});
