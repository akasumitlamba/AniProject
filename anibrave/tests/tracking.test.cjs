const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
const source=fs.readFileSync(require('node:path').join(__dirname,'../src/tracking.js'),'utf8');
function fixture(url,selectors={},title=''){
  let now=100000;const messages=[],listeners=new Map();
  const document={title,querySelector:s=>selectors[s]||null,querySelectorAll:()=>[],addEventListener:(name,fn)=>listeners.set(name,fn)};
  const location=new URL(url),window={addEventListener(){}};
  const context=vm.createContext({document,location,window,Date:{now:()=>now},setInterval:()=>1,clearInterval(){},aniBraveNative:value=>messages.push(JSON.parse(value))});
  vm.runInContext(source,context);
  return{context,messages,time:ms=>now+=ms,tick:video=>listeners.get('timeupdate')({target:video})};
}
const text=value=>({textContent:value});
test('AniKoto real path ep detects anime without recording a visit',()=>{
  const f=fixture('https://anikototv.to/watch/one-piece-odmau/ep-202',{'h1.title.d-title':text('One Piece')});
  assert.equal(f.context.__aniWatch.snapshot().episode,202);assert.equal(f.context.__aniWatch.snapshot().title,'One Piece');assert.equal(f.messages.filter(m=>m.type==='watchProgress').length,0);
});
test('HiAnime internal query ID is never treated as watched episode',()=>{
  const f=fixture('https://hianime.to/watch/one-piece-100?ep=2142',{'.film-name':text('One Piece')});assert.equal(f.context.__aniWatch.snapshot(),null);
  const active={textContent:'9',getAttribute:key=>key==='data-number'?'9':null};
  const identified=fixture('https://hianime.to/watch/one-piece-100?ep=2142',{'.film-name':text('One Piece'),'.ep-item.active, .ssl-item.ep-item.active, [data-number].active':active});assert.equal(identified.context.__aniWatch.snapshot().episode,9);
});
test('Crunchyroll heading S2 E9 retains series season',()=>{
  const f=fixture('https://www.crunchyroll.com/watch/id/episode',{'a[href*="/series/"]':text('Frieren'),'h1':text('S2 E9 - Title')});const data=f.context.__aniWatch.snapshot();assert.equal(data.episode,9);assert.equal(data.title,'Frieren Season 2');
});
test('unrelated hosts never yield anime metadata',()=>{assert.equal(fixture('https://not-hianime.to/watch/one-piece?ep=9').context.__aniWatch.snapshot(),null);});
test('fifteen seconds of actual moving video records playback; paused/seek do not',()=>{
  const f=fixture('https://player.example/embed');const video={tagName:'VIDEO',currentTime:0,duration:1200,paused:false,seeking:false,playbackRate:1,currentSrc:'stream'};f.tick(video);
  for(let i=1;i<=14;i++){f.time(1000);video.currentTime=i;f.tick(video);}assert.equal(f.messages.filter(m=>m.type==='watchProgress').length,0);
  f.time(1000);video.currentTime=15;f.tick(video);assert.equal(f.messages.filter(m=>m.type==='watchProgress').length,1);
  const paused=fixture('https://player.example/embed');video.paused=true;video.currentTime=0;paused.tick(video);for(let i=1;i<=30;i++){paused.time(1000);video.currentTime=i*30;paused.tick(video);}assert.equal(paused.messages.filter(m=>m.type==='watchProgress').length,0);
});
test('seeking around and short ads never count as an episode',()=>{
  const f=fixture('https://player.example/embed');const video={tagName:'VIDEO',currentTime:0,duration:30,paused:false,seeking:false,playbackRate:1};f.tick(video);for(let i=1;i<=40;i++){f.time(1000);video.currentTime=i;f.tick(video);}assert.equal(f.messages.filter(m=>m.type==='watchProgress').length,0);
  video.duration=1200;for(let i=1;i<=30;i++){f.time(1000);video.currentTime+=60;f.tick(video);}assert.equal(f.messages.filter(m=>m.type==='watchProgress').length,0);
});
