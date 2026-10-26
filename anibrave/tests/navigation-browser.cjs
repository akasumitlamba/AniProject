// Real browser history and retained media element regressions for both controllers.
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const fs=require('fs'),os=require('os'),path=require('path'),assert=require('assert/strict');
const {execFileSync}=require('child_process');
(async()=>{
 const temporary=fs.mkdtempSync(path.join(os.tmpdir(),'ani-navigation-'));
 const movie=path.join(temporary,'video.webm');
 execFileSync('ffmpeg',['-v','error','-f','lavfi','-i','color=c=black:s=320x180:r=1','-t','600','-c:v','libvpx','-an',movie]);
 const browser=await chromium.launch({executablePath:process.env.BRAVE_BINARY||'/usr/bin/brave-browser',headless:true,args:['--no-sandbox']});
 const failures=[];let checks=0;
 async function check(name,fn){checks++;try{await fn();console.log('PASS:',name);}catch(e){failures.push(name+': '+e.message);console.log('FAIL:',name,e.message);}}
 try{
  for(const controller of ['native','extension']){
   const page=await browser.newPage();const bytes=fs.readFileSync(movie);
   await page.route('https://navigation.test/**',async route=>{
    if(new URL(route.request().url()).pathname==='/movie.webm'){
     const range=route.request().headers().range?.match(/bytes=(\d+)-(\d*)/),start=Number(range?.[1]||0),end=range?.[2]?Number(range[2]):bytes.length-1;
     await route.fulfill({status:range?206:200,contentType:'video/webm',headers:{'Accept-Ranges':'bytes',...(range?{'Content-Range':`bytes ${start}-${end}/${bytes.length}`}:{})},body:bytes.subarray(start,end+1)});return;
    }
    await route.fulfill({contentType:'text/html',body:'<!doctype html><h1>Episode 5</h1><a id="next" href="/other">Next page</a><video id="v" src="/movie.webm" preload="auto" style="width:640px;height:360px"></video>'});
   });
   await page.goto('https://navigation.test/watch/5');await page.waitForFunction(()=>document.querySelector('video').readyState>=2);
   await page.evaluate(()=>{
    window.messages=[];window.aniBraveNative=s=>messages.push(JSON.parse(s));
    const video=document.querySelector('video');
    window.__aniWatch={main:v=>v===video,ad:()=>false,media:new Set([video]),page:()=>null};
   });
   await page.addScriptTag({path:controller==='native'?path.resolve(__dirname,'../src/continuation.js'):path.resolve(__dirname,'../../extensions/anibrave/continuation.js')});
   await page.evaluate(kind=>{
    const v=document.querySelector('video');
    window.controller=kind==='native'?__aniContinue:AniContinue({send:async()=>null,identity:()=>null,media:new Set([v]),isMain:x=>x===v,adBlocked:()=>false});
    window.resume=entry=>kind==='native'?controller.resume(entry):controller.receive(entry);
    window.entry={title:'Test',season:1,episode:5,position:100,duration:600};
   },controller);
   await check(controller+' first explicit resume seeks',async()=>{
    await page.evaluate(()=>resume(entry));await page.waitForFunction(()=>Math.abs(document.querySelector('video').currentTime-100)<1);
   });
   await check(controller+' Back returns to retained player and honors a new resume',async()=>{
    await page.evaluate(()=>{history.pushState({},'', '/watch/6');document.querySelector('video').currentTime=0;});
    await page.goBack();assert.equal(new URL(page.url()).pathname,'/watch/5');
    await page.evaluate(()=>resume(entry));
    assert.equal(await page.evaluate(()=>document.querySelector('video').currentTime),100);
   });
   await check(controller+' newer saved timestamp for same episode is honored',async()=>{
    await page.evaluate(()=>resume({...entry,position:210}));
    await page.waitForFunction(()=>Math.abs(document.querySelector('video').currentTime-210)<1,{},{timeout:3000});
   });
   await check(controller+' rapid Back/Forward cycles never reuse stale seek state',async()=>{
    for(let i=0;i<6;i++){
     await page.goForward();assert.equal(new URL(page.url()).pathname,'/watch/6');
     await page.evaluate(()=>{document.querySelector('video').currentTime=0;});
     await page.goBack();await page.evaluate(()=>resume(entry));
     assert.equal(await page.evaluate(()=>document.querySelector('video').currentTime),100);
    }
   });
   await check(controller+' normal document Back/Forward keeps correct URL and history',async()=>{
    await page.locator('#next').click();await page.waitForURL('https://navigation.test/other');
    await page.goBack();assert.equal(new URL(page.url()).pathname,'/watch/5');
    await page.goForward();assert.equal(new URL(page.url()).pathname,'/other');
   });
   if(controller==='native')await check('native history-restored document does not auto-route forward',async()=>{
    await page.goBack();
    await page.evaluate(()=>{
     window.messages=[];window.aniBraveNative=s=>messages.push(JSON.parse(s));
     const a=document.querySelector('a');a.href='/watch/6';a.textContent='Episode 5';a.setAttribute('data-season','1');
     window.__aniWatch={page:()=>({kind:'series',title:'Test',season:1,seasonKnown:true,episode:0}),media:new Set(),main:()=>true,ad:()=>false};
    });
    await page.addScriptTag({path:path.resolve(__dirname,'../src/continuation.js')});
    await page.evaluate(()=>__aniContinue.configure({pending:{entry:{title:'Test',season:1,episode:5,position:100},host:'navigation.test',expires:Date.now()+60000}}));
    assert.equal(await page.evaluate(()=>messages.filter(m=>m.type==='continuationNavigate').length),0);
   });
   await page.close();
  }
 }finally{await browser.close();fs.rmSync(temporary,{recursive:true,force:true});}
 assert.deepEqual(failures,[],`${failures.length} / ${checks} navigation checks failed`);
 console.log(`PASS: ${checks} real-browser navigation checks.`);
})().catch(e=>{console.error(e);process.exitCode=1;});
