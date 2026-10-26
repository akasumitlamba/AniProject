import {formatSpeed, normalizeSpeed} from './core.js';
const $=s=>document.querySelector(s);let tabId,state,editingKey=null;
async function request(data){const r=await chrome.runtime.sendMessage({...data,tabId});if(!r.ok)throw Error(r.error);return r.value;}
const status=text=>$('#status').textContent=text;
function renderSettings(s){
  const stamp=Number(s.speedUpdated)||0, current=Number(state.settings?.speedUpdated)||0;
  if(stamp && current && stamp<current) return;
  const speed=normalizeSpeed(s.speed)??1;
  state.settings={...state.settings,...s,speed,speedUpdated:stamp||current};
  $('#rate').textContent=formatSpeed(speed);$('#mode').value=state.settings.mode;$('#decrease').disabled=speed<=.25;$('#increase').disabled=speed>=5;
}
function history(entries){$('#history').replaceChildren();if(!entries.length){$('#history').textContent='Your next episode starts here.';return;}for(const e of entries.slice(0,30)){const el=document.createElement('div');el.className='entry';const title=document.createElement('strong');title.textContent=e.title;const detail=document.createElement('p');detail.textContent=`Season ${e.season} · Episode ${e.episode} · ${e.host}`;const row=document.createElement('div');row.className='row';const link=document.createElement('a');link.textContent='Return to episode ↗';if(/^https?:\/\//.test(e.url)){link.href=e.url;link.target='_blank';link.rel='noreferrer';}const here=document.createElement('button');here.textContent='Continue here';here.onclick=async()=>{try{await request({type:'continue',key:e.key});window.close();}catch(e){status(e.message);}};const edit=document.createElement('button');edit.textContent='Edit';edit.onclick=()=>{editingKey=e.key;$('#manual').hidden=false;$('#title').value=e.title;$('#episode').value=e.episode;$('#season').value=e.season;};const forget=document.createElement('button');forget.textContent='Forget';forget.onclick=async()=>{try{await request({type:'forget',title:e.title});await load();}catch(e){status(e.message);}};row.append(here,edit,forget);const previous=document.createElement('div');previous.append(link);el.append(title,detail,row,previous);$('#history').append(el);}}
async function load(){const next=await request({type:'state'});if(!state||state.host!==next.host)state=next;else state={...next,settings:state.settings};$('#host').textContent=state.host;$('#memory').hidden=!!state.privateMode;renderSettings(next.settings);history(next.history);}
async function settings(data){try{const s=await request({type:'settings',...data});renderSettings(s);status(data.mode?'View saved. Tab reloaded.':'');}catch(e){status(e.message);}}
async function adjust(delta){
  if(!state?.settings)return;
  const speed=normalizeSpeed(Math.min(5,Math.max(.25,state.settings.speed+delta)));
  if(speed==null)return;
  const speedUpdated=Math.max(Date.now(),(state.settings.speedUpdated||0)+1);
  renderSettings({...state.settings,speed,speedUpdated});
  await settings({speed,speedUpdated});
}
$('#decrease').onclick=()=>adjust(-.25);
$('#increase').onclick=()=>adjust(.25);
$('#mode').onchange=e=>settings({mode:e.target.value});
$('#toggle').onclick=()=>{editingKey=null;$('#manual').reset();$('#manual').hidden=!$('#manual').hidden;};
$('#manual').onsubmit=async e=>{e.preventDefault();try{await request({type:'save',replaceKey:editingKey,title:$('#title').value,episode:Number($('#episode').value),season:Number($('#season').value)});editingKey=null;$('#manual').hidden=true;await load();status('Episode remembered.');}catch(e){status(e.message);}};
chrome.storage.onChanged.addListener((changes,area)=>{
  if(area!=='local'||!state?.host)return;
  const siteChange=changes['site:'+state.host];
  if(siteChange?.newValue)renderSettings(siteChange.newValue);
  if(changes.history&&!state.privateMode)history(Object.values(changes.history.newValue||{}).sort((a,b)=>b.updated-a.updated));
});
try{[ {id:tabId} ]=await chrome.tabs.query({active:true,currentWindow:true});await load();}catch(e){status(e.message);document.querySelectorAll('button,input,select').forEach(x=>x.disabled=true);}
