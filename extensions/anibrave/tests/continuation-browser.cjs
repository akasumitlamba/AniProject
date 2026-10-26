const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const path=require('path'),fs=require('fs'),os=require('os'),assert=require('assert/strict');
(async()=>{
 const profile=fs.mkdtempSync(path.join(os.tmpdir(),'ani-continue-'));
 const extension=path.resolve(__dirname,'..');
 const fixture=path.join(profile,'fixture.webm');require('child_process').execFileSync('ffmpeg',['-v','error','-f','lavfi','-i','color=c=black:s=320x180:r=1','-t','600','-c:v','libvpx','-an',fixture]);
 const context=await chromium.launchPersistentContext(profile,{executablePath:process.env.BRAVE_BINARY||'/usr/bin/brave-browser',headless:true,args:[`--disable-extensions-except=${extension}`,`--load-extension=${extension}`,'--no-sandbox']});
 context.setDefaultTimeout(15000);context.on('page',p=>p.on('pageerror',e=>console.log('PAGEERROR',e.message)));
 try{
  await context.route(/https:\/\/(www\.)?(crunchyroll\.com|anikototv\.to)\/.*/,async route=>{
   const u=new URL(route.request().url());if(u.pathname==='/fixture.webm'){const bytes=fs.readFileSync(fixture),range=route.request().headers().range?.match(/bytes=(\d+)-(\d*)/);if(range){const start=Number(range[1]),end=range[2]?Number(range[2]):bytes.length-1;await route.fulfill({status:206,contentType:'video/webm',headers:{'Accept-Ranges':'bytes','Content-Range':`bytes ${start}-${end}/${bytes.length}`},body:bytes.subarray(start,end+1)});}else await route.fulfill({contentType:'video/webm',headers:{'Accept-Ranges':'bytes'},body:bytes});return;}let html='';
   if(u.pathname==='/search')html='<h1>Search</h1><a href="/series/dxd">High School DxD</a>';
   else if(u.pathname==='/series/dxd')html='<h1>High School DxD</h1><a href="/watch/episode5">S2 E5</a><a href="/watch/wrong">S1 E5</a>';
   else if(u.pathname.startsWith('/watch/'))html='<a data-t="series-title" href="/series/dxd">High School DxD</a><h1>S2E5</h1><video style="width:640px;height:360px" id="v" src="/fixture.webm" preload="auto"></video>';
   else html='<h1>Home</h1>';
   await route.fulfill({contentType:'text/html',body:'<!doctype html><meta name="viewport" content="width=device-width">'+html});
  });
  const worker=context.serviceWorkers()[0]||await context.waitForEvent('serviceworker');
  await worker.evaluate(async()=>chrome.storage.local.set({history:{'high school dxd':{key:'high school dxd',title:'High School DxD',season:2,episode:5,position:313,speed:1.5,host:'anikototv.to',url:'https://anikototv.to/watch/dxd/ep-5',updated:Date.now()}}}));
  const page=await context.newPage();const cdp=await context.newCDPSession(page);
  async function clickClosed(text){
   const tree=await cdp.send('DOM.getDocument',{depth:-1,pierce:true});
   function find(node){if((node.nodeName==='BUTTON'||node.nodeName==='A') && (node.children||[]).some(c=>c.nodeValue===text))return node;for(const n of [...node.children||[],...node.shadowRoots||[]]){const hit=find(n);if(hit)return hit;}return null;}
   const node=find(tree.root);assert.ok(node,'Missing action '+text);
   const {object}=await cdp.send('DOM.resolveNode',{nodeId:node.nodeId});await cdp.send('Runtime.callFunctionOn',{objectId:object.objectId,functionDeclaration:'function(){this.click()}'});
  }
  await page.goto('https://www.crunchyroll.com/');await page.waitForSelector('#anibrave-memory-card');
  await clickClosed('Continue here');
  await page.waitForURL('https://www.crunchyroll.com/watch/episode5');
  try{await page.waitForFunction(()=>document.querySelector('video')?.currentTime===313);}catch(e){console.log('Resume failure',await page.evaluate(()=>({url:location.href,time:document.querySelector('video')?.currentTime,duration:document.querySelector('video')?.duration,ready:document.querySelector('video')?.readyState})),await worker.evaluate(async()=>chrome.storage.session.get(null)));throw e;}
  await page.waitForFunction(()=>document.querySelector('video')?.playbackRate===1.5);
  const tabs=await worker.evaluate(async()=>(await chrome.tabs.query({})).filter(t=>t.url?.includes('episode5')));const tabId=tabs[0].id;
  assert.ok(await worker.evaluate(async id=>(await chrome.storage.session.get('continue:'+id))['continue:'+id].applied,tabId));
  // The card survives search + series + episode navigation; Done dismisses persistently.
  await page.waitForSelector('#anibrave-memory-card');await page.screenshot({path:path.resolve(__dirname,'../../../.tools/anibrave-continuation.png')});await clickClosed('Done');await page.waitForFunction(()=>!document.querySelector('#anibrave-memory-card'));
  await page.goto('https://www.crunchyroll.com/');await page.waitForTimeout(2200);assert.equal(await page.locator('#anibrave-memory-card').count(),0);
  // A different episode is a new reminder, not blocked by the old dismissal.
  await worker.evaluate(async()=>{const {history}=await chrome.storage.local.get('history');history['high school dxd'].episode=6;await chrome.storage.local.set({history});});
  await page.waitForSelector('#anibrave-memory-card');
  // Another title's series page must not show this unrelated history.
  await context.route('https://www.crunchyroll.com/series/unrelated',route=>route.fulfill({contentType:'text/html',body:'<h1>Unrelated Anime</h1>'}));
  await page.goto('https://www.crunchyroll.com/series/unrelated');await page.waitForTimeout(2200);assert.equal(await page.locator('#anibrave-memory-card').count(),0);
  await page.goto('https://www.crunchyroll.com/');await page.waitForSelector('#anibrave-memory-card');
  await clickClosed('Open previous site');await page.waitForURL('https://anikototv.to/watch/dxd/ep-5');
  console.log('PASS: cross-site home → search → exact series → correct season/episode → timestamp + speed; persistent dismissal; fresh-episode reminder; unrelated-series silence; previous-site action. All network responses were fixtures.');
 }finally{await context.close();fs.rmSync(profile,{recursive:true,force:true});}
})().catch(e=>{console.error(e);process.exitCode=1;});
