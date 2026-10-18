const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const path=require('path'),fs=require('fs'),os=require('os'),assert=require('assert/strict'),http=require('http');
(async()=>{
 const profile=fs.mkdtempSync(path.join(os.tmpdir(),'anibrave-qa-'));const extension=path.resolve(__dirname,'..');
 const server=http.createServer((req,res)=>{res.setHeader('Content-Type','text/html');res.end('<html><meta name="viewport" content="width=device-width,initial-scale=1"><title>Player fixture</title><style>video{max-width:100%}</style><body><h1>Player fixture</h1><video id="v" style="width:640px;height:360px"></video></body></html>');}).listen(0,'127.0.0.1');await new Promise(r=>server.listening?r():server.once('listening',r));
 const url=`http://127.0.0.1:${server.address().port}/`;
 const context=await chromium.launchPersistentContext(profile,{executablePath:process.env.BRAVE_BINARY||'/usr/bin/brave-browser',headless:true,args:[`--disable-extensions-except=${extension}`,`--load-extension=${extension}`,'--no-sandbox']});
 try {
 let worker=context.serviceWorkers()[0]||await context.waitForEvent('serviceworker');const id=new URL(worker.url()).host;
 context.setDefaultTimeout(15000);const page=await context.newPage();await page.goto(url);await page.waitForTimeout(800);
 const tabId=await worker.evaluate(async url=>(await chrome.tabs.query({})).find(t=>t.url===url).id,url);
 const popup=await context.newPage();await popup.addInitScript(id=>{chrome.tabs.query=async()=>[{id}];},tabId);await popup.goto(`chrome-extension://${id}/popup.html`);
 const storedSpeed=()=>worker.evaluate(async()=>(await chrome.storage.local.get('site:127.0.0.1'))['site:127.0.0.1']?.speed);
 const setSpeed=speed=>worker.evaluate(async speed=>{const cur=(await chrome.storage.local.get('site:127.0.0.1'))['site:127.0.0.1']||{};await chrome.storage.local.set({'site:127.0.0.1':{...cur,speed,mode:cur.mode||'desktop',speedUpdated:Date.now()}});},speed);
 async function siteRate(target, rate, native = false, shadow = false) {
  await target.evaluate(({rate,native,shadow})=>{const button=document.createElement('button');button.id='test-site-speed';button.textContent='Site speed';button.onclick=()=>{if(native)document.getElementById('movie_player').setPlaybackRate(rate);else (shadow?document.querySelector('#shadow-player').shadowRoot.querySelector('video'):document.querySelector('video')).playbackRate=rate;};document.body.append(button);},{rate,native,shadow});
  await target.locator('#test-site-speed').click();await target.locator('#test-site-speed').evaluate(b=>b.remove());
 }
 async function settled(rate){try{await page.waitForFunction(r=>document.querySelector('video')?.playbackRate===r,rate);}catch(e){console.log('Speed failure',rate,await storedSpeed(),await popup.locator('#rate').textContent(),await page.evaluate(()=>({rate:document.querySelector('video')?.playbackRate,native:document.getElementById('movie_player')?.getPlaybackRate?.()})));throw e;}const samples=[];for(let i=0;i<6;i++){samples.push(await page.evaluate(()=>document.querySelector('video').playbackRate));await page.waitForTimeout(100);}assert.deepEqual(samples,Array(6).fill(rate),`playback drifted (${samples.join(', ')})`);}
 await setSpeed(5);await settled(5);
 await page.evaluate(()=>{window.__rateEvents=0;document.querySelector('#v').addEventListener('ratechange',()=>window.__rateEvents++);for(let i=0;i<30;i++)document.querySelector('#v').playbackRate=1;});
 await settled(5);assert.equal(await storedSpeed(),5);assert.equal(await page.evaluate(()=>window.__rateEvents),0,'scripted clamps must not cause ratechange oscillation');
 await setSpeed(2);
 await settled(2);
 await page.waitForTimeout(700);
 await siteRate(page,1.5);
 await settled(1.5);
 {const start=Date.now();let last;while(Date.now()-start<5000){last=await storedSpeed();if(last===1.5)break;await page.waitForTimeout(50);}assert.equal(last,1.5);}
 await page.evaluate(()=>{const tiny=document.createElement('video');tiny.id='tiny';tiny.style.cssText='width:32px;height:32px';document.body.append(tiny);tiny.playbackRate=.5;});
 await page.waitForTimeout(400);
 assert.equal(await page.evaluate(()=>document.querySelector('#v').playbackRate),1.5);
 assert.equal(await storedSpeed(),1.5);
 await setSpeed(2);await settled(2);
 await siteRate(page,6);
 await page.waitForTimeout(450);
 assert.equal(await page.evaluate(()=>document.querySelector('#v').playbackRate),6,'a rate outside 0.25×–5× should be left alone');
 assert.equal(await storedSpeed(),2);
 await page.evaluate(()=>{document.querySelector('#v').playbackRate=2;});
 await settled(2);
 await page.evaluate(()=>{document.body.innerHTML='<video id="replacement" style="width:640px;height:360px"></video>';});await settled(2);
 // A trusted site interaction during initialization wins immediately.
 await page.evaluate(()=>{document.body.innerHTML='<video id="fresh" style="width:640px;height:360px"></video><button id="site-rate">Site speed</button>';document.querySelector('#site-rate').onclick=()=>document.querySelector('video').playbackRate=1.25;});
 await page.waitForFunction(()=>document.querySelector('video').playbackRate===2);
 await page.locator('#site-rate').click();await settled(1.25);
 await setSpeed(2);await settled(2);

 await page.reload();await settled(2);
 await worker.evaluate(async()=>chrome.storage.local.set({history:{'high school dxd':{key:'high school dxd',title:'High School DxD',episode:10,season:1,host:'127.0.0.1',url:'https://example.com',updated:Date.now()}}}));
 await page.waitForSelector('#anibrave-memory-card');await page.evaluate(()=>document.getElementById('anibrave-memory-card').remove());await page.waitForTimeout(2200);assert.equal(await page.locator('#anibrave-memory-card').count(),0);
 await page.reload();await page.waitForSelector('#anibrave-memory-card');
 // Activate fixture before rendering popup, as the toolbar does.
 await page.bringToFront();await popup.reload();await popup.waitForTimeout(700);
 await popup.waitForFunction(()=>document.querySelector('#host').textContent==='127.0.0.1');
 await popup.waitForFunction(()=>document.querySelector('#rate').textContent==='2×');
 await page.evaluate(()=>{
  const video=document.querySelector('video');
  const player=document.createElement('div');
  player.id='movie_player';player.className='html5-video-player';
  const allowed=[.25,.5,.75,1,1.25,1.5,1.75,2];
  let rate=video.playbackRate;
  player.getPlaybackRate=()=>rate;
  player.getAvailablePlaybackRates=()=>allowed.slice();
  player.setPlaybackRate=requested=>{rate=allowed.reduce((best,n)=>Math.abs(n-requested)<Math.abs(best-requested)?n:best);if(!window.__nativeOnly&&video.isConnected&&video.playbackRate!==rate)video.playbackRate=rate;};
  player.append(video);document.body.append(player);
  window.__sitePlayerTimer=setInterval(()=>{if(!player.isConnected||window.__nativeOnly)return;const target=player.classList.contains('ad-showing')?1:rate;if(video.isConnected&&video.playbackRate!==target)video.playbackRate=target;},80);
 });
 await siteRate(page,1.5,true);
 await settled(1.5);
 {const start=Date.now();let last;while(Date.now()-start<5000){last=await storedSpeed();if(last===1.5)break;await page.waitForTimeout(50);}assert.equal(last,1.5);}
 await popup.waitForFunction(()=>document.querySelector('#rate').textContent==='1.5×');
 await popup.locator('#increase').click();
 await settled(1.75);
 assert.equal(await page.evaluate(()=>document.getElementById('movie_player').getPlaybackRate()),1.75);
 await setSpeed(2);await settled(2);
 await popup.waitForFunction(()=>document.querySelector('#rate').textContent==='2×');
 await page.evaluate(()=>{window.__nativeOnly=true;});
 await popup.locator('#increase').click();
 await page.waitForTimeout(500);
 assert.equal(await page.evaluate(()=>document.getElementById('movie_player').getPlaybackRate()),2);
 assert.equal(await storedSpeed(),2.25);
 await settled(2.25);
 assert.equal(await popup.locator('#rate').textContent(),'2.25×');
 // Rapid toolbar changes are all retained, rather than dropped while storage is pending.
 await popup.evaluate(()=>{for(let i=0;i<3;i++)document.querySelector('#decrease').click();});
 await settled(1.5);assert.equal(await page.evaluate(()=>document.getElementById('movie_player').getPlaybackRate()),1.5);
 await popup.waitForFunction(()=>document.querySelector('#rate').textContent==='1.5×');
 await setSpeed(2);await settled(2);

 await page.evaluate(()=>{window.__nativeOnly=false;});
 await setSpeed(5);await settled(5);assert.equal(await storedSpeed(),5);
 // A genuine site speed selection still takes over, even after a forced override.
 await page.evaluate(()=>{const b=document.createElement('button');b.id='lower-rate';b.textContent='Site rate 1.5';b.onclick=()=>document.getElementById('movie_player').setPlaybackRate(1.5);document.body.append(b);});
 await page.locator('#lower-rate').click();await settled(1.5);assert.equal(await storedSpeed(),1.5);
 await setSpeed(5);await settled(5);
 await page.evaluate(()=>document.getElementById('movie_player').classList.add('ad-showing'));
 await page.waitForFunction(()=>document.querySelector('video').playbackRate===1);
 await page.waitForTimeout(400);
 assert.equal(await storedSpeed(),5);
 assert.equal(await popup.locator('#rate').textContent(),'5×');
 await page.evaluate(()=>document.getElementById('movie_player').classList.remove('ad-showing'));
 await settled(5);
 await page.evaluate(()=>{clearInterval(window.__sitePlayerTimer);const player=document.getElementById('movie_player');const video=document.querySelector('video');if(video)document.body.append(video);player?.remove();});
 await setSpeed(2);await settled(2);
 await popup.waitForFunction(()=>document.querySelector('#rate').textContent==='2×');
 await popup.locator('#increase').click();await settled(2.25);await popup.locator('#increase').click();await settled(2.5);await popup.locator('#increase').click();await settled(2.75);await popup.locator('#increase').click();await settled(3);
 for(let i=0;i<8;i++)await popup.locator('#increase').click();await settled(5);await popup.waitForFunction(()=>document.querySelector('#rate').textContent==='5×');assert.equal(await popup.locator('#increase').isDisabled(),true);await setSpeed(3);await settled(3);
 // Same-site settings propagate to cross-origin embedded players and open shadow roots.
 const [embedded]=await Promise.all([page.waitForEvent('framenavigated',{predicate:f=>f!==page.mainFrame()&&f.url().includes('localhost')}),page.evaluate(url=>{const frame=document.createElement('iframe');frame.src=url.replace('127.0.0.1','localhost');document.body.append(frame);},url)]);
 await embedded.waitForFunction(()=>document.querySelector('video')?.playbackRate===3);await page.waitForTimeout(650);
 await siteRate(embedded,1.5);await settled(1.5);
 await popup.waitForFunction(()=>document.querySelector('#rate').textContent==='1.5×');
 await page.evaluate(()=>{const host=document.createElement('div');host.id='shadow-player';host.attachShadow({mode:'open'}).innerHTML='<video style="width:640px;height:360px"></video>';document.body.append(host);});
 await page.waitForFunction(()=>document.querySelector('#shadow-player').shadowRoot.querySelector('video').playbackRate===1.5);
 await page.waitForTimeout(650);await siteRate(page,1.25,false,true);await settled(1.25);
 await page.evaluate(()=>{document.querySelector('#shadow-player').remove();document.querySelector('iframe').remove();});
 // Editing the title replaces the old entry; forgetting removes the on-page card too.
 await popup.locator('#memory').evaluate(el=>el.open=true);await popup.getByRole('button',{name:'Edit',exact:true}).first().click();await popup.locator('#title').fill('Renamed Series');await popup.getByRole('button',{name:'Save',exact:true}).click();
 await popup.waitForFunction(()=>document.querySelector('#history').textContent.includes('Renamed Series'));
 assert.equal(await worker.evaluate(async()=>Object.keys((await chrome.storage.local.get('history')).history).length),1);
 await popup.getByRole('button',{name:'Forget',exact:true}).first().click();await page.waitForFunction(()=>!document.querySelector('#anibrave-memory-card'));
 await popup.locator('#memory').evaluate(el=>el.open=false);
 await Promise.all([page.waitForEvent('load'),popup.locator('#mode').selectOption('mobile')]);await page.waitForFunction(()=>navigator.userAgent.includes('Mobile'));console.log('Initial mobile metrics',await page.evaluate(()=>({inner:innerWidth,layout:document.documentElement.clientWidth,screen:screen.width,touch:navigator.maxTouchPoints,body:document.body.clientWidth,visual:visualViewport.width,scale:visualViewport.scale,mode:document.compatMode,video:document.querySelector('video')?.clientWidth})));await page.waitForFunction(()=>document.documentElement.clientWidth===412 && navigator.maxTouchPoints===1);
 console.log('Mobile viewport:',await page.evaluate(()=>({inner:innerWidth,layout:document.documentElement.clientWidth,screen:screen.width})));

 await Promise.all([page.waitForEvent('load'),popup.locator('#mode').selectOption('identity')]);await page.waitForFunction(()=>innerWidth!==412 && navigator.userAgent.includes('Mobile'));
 await Promise.all([page.waitForEvent('load'),popup.locator('#mode').selectOption('desktop')]);await page.waitForFunction(()=>!navigator.userAgent.includes('Mobile'));
 fs.mkdirSync(path.resolve(__dirname,'../../../.tools'),{recursive:true});
 await popup.screenshot({path:path.resolve(__dirname,'../../../.tools/anibrave-extension-popup.png'),clip:{x:0,y:0,width:268,height:250}});
 console.log('PASS: real Brave extension loaded; site/extension speed sync; ad speed left intact; speed apply/replacement/reload; reminder revisit/dismissal; mobile, identity-only, desktop restoration.');
 console.log('Worker:',worker.url());
 }finally{await context.close();server.close();fs.rmSync(profile,{recursive:true,force:true});}
})().catch(e=>{console.error(e);process.exitCode=1;});
