const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
const source=fs.readFileSync(require('node:path').join(__dirname,'../src/tracking.js'),'utf8');
function fixture(url,selectors={},title=''){
  let now=100000;const messages=[],listeners=new Map(),intervals=new Map();let timerId=0;
  const document={title,querySelector:s=>selectors[s]||null,querySelectorAll:()=>[],addEventListener:(name,fn)=>listeners.set(name,fn)};
  const location=new URL(url),window={addEventListener:(name,fn)=>listeners.set(name,fn)};
  const context=vm.createContext({document,location,window,Date:{now:()=>now},setInterval:fn=>{intervals.set(++timerId,fn);return timerId;},clearInterval:id=>intervals.delete(id),aniBraveNative:value=>messages.push(JSON.parse(value))});
  vm.runInContext(source,context);
  return{context,messages,document,fire:(name,event)=>listeners.get(name)?.(event),activeTimers:()=>intervals.size,probe:()=>intervals.forEach(fn=>fn()),time:ms=>now+=ms,tick:video=>listeners.get('timeupdate')({target:video})};
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

test('source and title refinements keep actual playback time for the same episode',()=>{
  const selectors={'h1':text('Example')};
  const f=fixture('https://anikototv.to/watch/example/ep-9',selectors);
  const video={tagName:'VIDEO',currentTime:0,duration:1200,paused:false,seeking:false,playbackRate:1,currentSrc:'cdn-one'};
  f.tick(video);
  for(let i=1;i<=15;i++){
    f.time(1000);video.currentTime=i;
    if(i===10){video.currentSrc='cdn-two';selectors.h1=text('Example Series');f.probe();}
    f.tick(video);
  }
  assert.equal(f.messages.filter(m=>m.type==='watchProgress').length,1);
});
test('temporary metadata gap preserves the episode, but navigation clears it',()=>{
  const selectors={'.film-name':text('Example'),'.ep-item.active, .ssl-item.ep-item.active, [data-number].active':{textContent:'9',getAttribute:()=>null}};
  const f=fixture('https://hianime.to/watch/example?ep=123',selectors);
  delete selectors['.film-name'];f.time(11000);f.probe();
  assert.equal(f.messages.at(-1).episode,9);
  f.context.location.href='https://hianime.to/home';f.probe();
  assert.equal(f.messages.at(-1).episode,0);
});
test('a new episode does not inherit the previous episode playback time',()=>{
  const f=fixture('https://anikototv.to/watch/example/ep-9',{'h1':text('Example')});
  const video={tagName:'VIDEO',currentTime:0,duration:1200,paused:false,seeking:false,playbackRate:1};f.tick(video);
  for(let i=1;i<=14;i++){f.time(1000);video.currentTime=i;f.tick(video);}
  f.context.location.href='https://anikototv.to/watch/example/ep-10';f.probe();
  f.time(1000);video.currentTime=15;f.tick(video);
  assert.equal(f.messages.filter(m=>m.type==='watchProgress').length,0);
});
test('alphabetic slug words are preserved and malformed percent encoding is safe',()=>{
  for(const slug of ['demon-slayer','attack-on-titan','example-movie']){
    const f=fixture('https://anikototv.to/watch/'+slug+'/ep-9');
    assert.equal(f.context.__aniWatch.snapshot().title,slug.replaceAll('-',' '));
  }
  assert.equal(fixture('https://anikototv.to/watch/example-%zz/ep-9').context.__aniWatch.snapshot().episode,9);
});
test('late nested shadow players receive tracking listeners only once',()=>{
  const f=fixture('https://player.example/embed');
  const outer={querySelectorAll:()=>[],addEventListener(){}};
  f.document.querySelectorAll=()=>[{shadowRoot:outer}];f.probe();
  const events=[];const inner={querySelectorAll:()=>[],addEventListener:(...args)=>events.push(args)};
  outer.querySelectorAll=()=>[{shadowRoot:inner}];f.probe();f.probe();
  assert.equal(events.filter(e=>e[0]==='timeupdate').length,1);
});

test('back-forward cache restore restarts one metadata and shadow discovery timer',()=>{
  const selectors={};const f=fixture('https://hianime.to/watch/example?ep=123',selectors);
  assert.equal(f.activeTimers(),1);
  f.fire('pagehide',{persisted:true});assert.equal(f.activeTimers(),0);
  selectors['.film-name']=text('Example');
  selectors['.ep-item.active, .ssl-item.ep-item.active, [data-number].active']={textContent:'9',getAttribute:()=>null};
  f.fire('pageshow',{persisted:true});f.fire('pageshow',{persisted:true});
  assert.equal(f.activeTimers(),1);assert.equal(f.messages.at(-1).episode,9);
  f.fire('pagehide',{persisted:true});assert.equal(f.activeTimers(),0);
});

test('Crunchyroll compact and spelled-out season labels preserve season identity',()=>{
  for(const heading of ['S2E12 - Episode title','Season 2 Episode 12']){
    const f=fixture('https://www.crunchyroll.com/watch/id',{'a[href*="/series/"]':text('Frieren'),'h1':text(heading)});
    const data=f.context.__aniWatch.snapshot();assert.equal(data.episode,12);assert.equal(data.title,'Frieren Season 2');
  }
  const f=fixture('https://www.crunchyroll.com/watch/id',{'a[href*="/series/"]':text('Frieren'),'h1':text('S1E12 - Title')});
  assert.equal(f.context.__aniWatch.snapshot().title,'Frieren');
});
test('a long trailer beside a paused main video cannot record an episode',()=>{
  const f=fixture('https://anikototv.to/watch/example/ep-9',{'h1':text('Example')});
  const main={tagName:'VIDEO',isConnected:true,clientWidth:1280,clientHeight:720,paused:true};
  const trailer={tagName:'VIDEO',isConnected:true,clientWidth:320,clientHeight:180,currentTime:0,duration:90,paused:false,seeking:false,playbackRate:1};
  f.document.querySelectorAll=s=>s==='video'?[main,trailer]:[];
  f.tick(trailer);for(let i=1;i<=20;i++){f.time(1000);trailer.currentTime=i;f.tick(trailer);}
  assert.equal(f.messages.some(m=>m.type==='watchProgress'),false);
});
test('a long ad video cannot record watch progress',()=>{
  const f=fixture('https://player.example/embed');
  const video={tagName:'VIDEO',isConnected:true,classList:{contains:n=>n==='ad-showing'},currentTime:0,duration:90,paused:false,seeking:false,playbackRate:1};
  f.tick(video);for(let i=1;i<=20;i++){f.time(1000);video.currentTime=i;f.tick(video);}
  assert.equal(f.messages.some(m=>m.type==='watchProgress'),false);
});
