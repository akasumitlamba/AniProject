import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

function player(initial = 1, acceptsExtra = false) {
 let actual = initial; const calls = []; const allowed = [.25,.5,.75,1,1.25,1.5,1.75,2];
 const native = {classList:{contains:()=>false},getPlaybackRate:()=>actual,getAvailablePlaybackRates:()=>allowed,setPlaybackRate(n){calls.push(n);actual=acceptsExtra?n:allowed.reduce((best,r)=>Math.abs(r-n)<Math.abs(best-n)?r:best);},getRootNode:()=>({})};
 return {native,calls};
}
function bridge(first) {
 const unrelated = player(), responses = [];let listener;
 const media = [{getAttribute:()=> '1', parentElement:first.native, classList:{contains:()=>false}},{getAttribute:()=> '2',parentElement:unrelated.native,classList:{contains:()=>false}}];
 const window = {addEventListener(type,fn){listener=fn;},postMessage(data){responses.push(data);}};
 const document = {querySelectorAll(selector){return selector==='video,audio'?media:[];}};
 vm.runInNewContext(fs.readFileSync(new URL('../player.js',import.meta.url),'utf8'),{window,document,console});
 function send(rate,id=1){listener({source:window,data:{source:'anibrave-ext',type:'set-rate',id,media:'1',rate}});return responses.at(-1);}
 return {send,responses,unrelated};
}
test('native rate omitted by an old advertised list remains selected',()=>{const p=player(3);const b=bridge(p);assert.equal(b.send(3).rate,3);assert.equal(p.calls.length,0);});
test('requested rates accepted by the real API override a stale capability list',()=>{const p=player(1,true);const b=bridge(p);assert.equal(b.send(3).rate,3);assert.equal(p.native.getPlaybackRate(),3);});
test('an actual player rejection settles on the nearest supported rate',()=>{const p=player();const b=bridge(p);assert.equal(b.send(2.25).rate,2);assert.equal(p.native.getPlaybackRate(),2);});
test('only the corresponding player is changed',()=>{const p=player();const b=bridge(p);assert.equal(b.send(1.5).rate,1.5);assert.equal(b.unrelated.calls.length,0);});
test('invalid bridge messages cannot call native player methods',()=>{const p=player();const b=bridge(p);b.send(Infinity);b.send('2');b.send(2,-1);assert.equal(p.calls.length,0);assert.equal(b.responses.length,0);});
