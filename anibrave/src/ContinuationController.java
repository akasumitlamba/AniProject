package app.anibrave;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.concurrent.*;

/** Native, normal-profile-only continuation UI. Each operation retains its source tab. */
final class ContinuationController {
    private static final Handler UI=new Handler(Looper.getMainLooper());
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private static ContinuationStore store;
    private static LinearLayout card;
    private static View decorOwner;
    private static ViewTreeObserver.OnPreDrawListener privacy;
    private static JSONObject identity,state;
    private static String target="",url="",signature="",notice="";
    private static Object tab;
    private static Activity owner;
    private static EngineClient engine;
    private static String resumeTarget="",resumeUrl="";
    private static int resumeScope=-1;
    private static final Runnable timeout=()->action("dismiss");
    private static final Runnable pendingTimeout=()->{JSONObject p=pending();if(p!=null){if(p.optLong("expires")<=System.currentTimeMillis())forgetPresentation(null);else scheduleExpiry(p);}};
    private static void scheduleExpiry(JSONObject p){UI.removeCallbacks(pendingTimeout);if(p!=null)UI.postDelayed(pendingTimeout,Math.max(0,p.optLong("expires")-System.currentTimeMillis()));}
    private static ContinuationStore store(Activity a){if(store==null)store=new ContinuationStore(a.getApplicationContext());return store;}
    private static Object currentTab(Activity a){try{return a.getClass().getMethod("m4").invoke(a);}catch(Exception e){return null;}}
    private static int scope(Object t){try{return ((Number)t.getClass().getMethod("getId").invoke(t)).intValue();}catch(Exception e){return -1;}}
    private static boolean valid(Activity a,Object t,String page){return a!=null&&a==AniRuntime.continuationActivity()&&t!=null&&currentTab(a)==t&&WatchUrl.same(page,AniRuntime.currentUrl());}
    static JSONObject normalized(JSONObject id){try{JSONObject n=new JSONObject(id.toString());if(!n.optString("seriesTitle").isEmpty())n.put("title",n.optString("seriesTitle"));return n;}catch(Exception e){return id;}}
    static void page(EngineClient source,String targetId,String page,JSONObject info){UI.post(()->{
        Activity a=AniRuntime.continuationActivity();Object t=currentTab(a);
        if(!valid(a,t,page)||scope(t)<0)return;
        if(info.optBoolean("visible",true)==false)return;
        // A hidden/other same-URL window cannot take ownership of a live card.
        if(!info.optBoolean("focused",true)&&(owner!=a||tab!=t||!targetId.equals(target)))return;
        if(!WatchUrl.same(page,url))notice="";owner=a;tab=t;target=targetId;url=page;identity=normalized(info);engine=source;
        refresh(a,t,page,targetId,identity);
    });}
    private static void refresh(Activity a,Object t,String page,String targetId,JSONObject id){int key=scope(t);IO.execute(()->{
        try{JSONObject learned="watch".equals(id.optString("kind"))?store(a).learned(page):null;JSONObject effective=learned==null?id:new JSONObject(learned.toString()).put("kind","watch").put("url",page).put("seasonKnown",true);JSONObject next=store(a).state(page,effective,key);UI.post(()->{
            if(!valid(a,t,page)||!targetId.equals(target))return;
            JSONObject before=entry();state=next;if(learned!=null)engine.confirmContinuationIdentity(targetId,page,learned);JSONObject after=entry();if(before!=null&&after!=null&&!before.optString("key").equals(after.optString("key")))notice="";engine.continuationState(targetId,page,next);render();
        });}catch(Exception e){UI.post(()->{if(valid(a,t,page)){notice="History is unavailable. Try again from Watched anime.";hide();}});}
    });}
    static void progress(String page,JSONObject id,JSONObject progress){UI.post(()->{
        Activity a=AniRuntime.continuationActivity();Object t=currentTab(a);if(!valid(a,t,page))return;
        JSONObject n=normalized(id);IO.execute(()->{try{progress.put("qualified",true);store(a).record(n,progress);}catch(Exception ignored){}});
    });}
    private static String titleKey(String s){return java.text.Normalizer.normalize(s,java.text.Normalizer.Form.NFKC).toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+"," ").trim();}
    private static int dp(Activity a,int value){return Math.round(value*a.getResources().getDisplayMetrics().density);}
    private static TextView label(Activity a,String value,int size,int color){TextView v=new TextView(a);v.setText(value);v.setTextSize(size);v.setTextColor(color);return v;}
    private static Button button(Activity a,String text,Runnable run){Button b=new Button(a);b.setText(text);b.setTextSize(12);b.setAllCaps(false);b.setMinHeight(dp(a,44));b.setMinimumHeight(dp(a,44));b.setTextColor(0xfff5f1e9);b.setPadding(dp(a,9),0,dp(a,9),0);GradientDrawable bg=new GradientDrawable();bg.setColor(0xff433c32);bg.setCornerRadius(dp(a,8));b.setBackground(bg);b.setOnClickListener(v->run.run());return b;}
    private static JSONObject entry(){if(state==null)return null;JSONObject e=state.optJSONObject("entry");JSONObject p=state.optJSONObject("pending");return e!=null?e:p==null?null:p.optJSONObject("entry");}
    private static JSONObject pending(){return state==null?null:state.optJSONObject("pending");}
    private static String detail(JSONObject e){int seconds=Math.max(0,e.optInt("position"));return "Season "+e.optInt("season",1)+(e.optInt("part")>0?" · Part "+e.optInt("part"):"")+" · Episode "+e.optInt("episode")+" · "+seconds/60+":"+String.format(java.util.Locale.ROOT,"%02d",seconds%60);}
    private static void render(){
        if(!valid(owner,tab,url)){hide();return;}JSONObject e=entry();if(e==null){hide();return;}JSONObject p=pending();
        String sig=e.optString("key")+"|"+(p!=null)+"|"+(p!=null&&p.optBoolean("applied"))+"|"+notice;
        if(sig.equals(signature)&&card!=null){scheduleExpiry(p);return;}
        hide();signature=sig;Activity a=owner;card=new LinearLayout(a);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(a,12),dp(a,8),dp(a,12),dp(a,12));
        GradientDrawable bg=new GradientDrawable();bg.setColor(0xfa24211f);bg.setCornerRadius(dp(a,12));card.setBackground(bg);card.setElevation(dp(a,6));
        LinearLayout header=new LinearLayout(a);header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=label(a,e.optString("title"),14,0xfff5f1e9);title.setTypeface(null,android.graphics.Typeface.BOLD);title.setMaxLines(2);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        TextView close=label(a,"×",24,0xffc9c0b3);close.setGravity(Gravity.CENTER);close.setContentDescription("Dismiss continuation reminder");close.setOnClickListener(v->action("dismiss"));header.addView(close,new LinearLayout.LayoutParams(dp(a,44),dp(a,44)));card.addView(header);
        card.addView(label(a,detail(e),12,0xffd3cabc));
        if(!notice.isEmpty())card.addView(label(a,notice,12,0xffe3cda8));
        LinearLayout controls=new LinearLayout(a);controls.setPadding(0,dp(a,10),0,0);
        String primary=p==null?"Continue here":p.optBoolean("applied")?"Done":"watch".equals(identity.optString("kind"))?"Confirm episode":"Search here";
        Button here=button(a,primary,()->action(p==null?"here":p.optBoolean("applied")?"dismiss":"watch".equals(identity.optString("kind"))?"confirm":"search"));
        controls.addView(here,new LinearLayout.LayoutParams(0,dp(a,44),1));Button old=button(a,"Open previous site",()->action("previous"));LinearLayout.LayoutParams oldParams=new LinearLayout.LayoutParams(0,dp(a,44),1);oldParams.leftMargin=dp(a,6);controls.addView(old,oldParams);card.addView(controls);
        FrameLayout decor=(FrameLayout)a.getWindow().getDecorView();View content=a.findViewById(android.R.id.content);int reserve=content==null?0:content.getPaddingLeft();
        FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(Math.min(dp(a,360),Math.max(dp(a,240),decor.getWidth()-reserve-dp(a,24))),-2,Gravity.BOTTOM|Gravity.LEFT);params.leftMargin=reserve+dp(a,12);WindowInsets insets=decor.getRootWindowInsets();params.bottomMargin=dp(a,16)+(insets==null?0:insets.getSystemWindowInsetBottom());decor.addView(card,params);decorOwner=decor;privacy=()->{if(!valid(owner,tab,url))hide();return true;};decor.getViewTreeObserver().addOnPreDrawListener(privacy);
        if(p==null){int ms=12000;android.view.accessibility.AccessibilityManager am=(android.view.accessibility.AccessibilityManager)a.getSystemService(Context.ACCESSIBILITY_SERVICE);if(am!=null)ms=am.getRecommendedTimeoutMillis(ms,3);UI.postDelayed(timeout,ms);}else scheduleExpiry(p);
    }
    private static void action(String command){
        Activity a=owner;Object t=tab;String page=url,targetId=target;JSONObject e=entry(),id=identity;if(e==null||!valid(a,t,page))return;
        if(command.equals("confirm")){new AlertDialog.Builder(a).setTitle("Confirm this episode").setMessage(e.optString("title")+"\n"+detail(e)+"\n\nIs the current player showing this exact season and episode?").setPositiveButton("Confirm and resume",(d,w)->perform(a,t,page,targetId,e,id,"confirmed")).setNegativeButton("Cancel",null).show();return;}
        perform(a,t,page,targetId,e,id,command);
    }
    private static void perform(Activity a,Object t,String page,String targetId,JSONObject e,JSONObject id,String command){
        if(!valid(a,t,page))return;int key=scope(t);IO.execute(()->{try{
            JSONObject p=null;
            if(command.equals("dismiss")){store(a).dismiss(page,e.optString("key"),key);}
            else if(command.equals("here")||command.equals("previous")){p=store(a).begin(page,e.optString("key"),key,command.equals("previous"));}
            else if(command.equals("confirmed")){JSONObject confirmed=new JSONObject(id.toString()).put("learnAlias",id.optInt("episode")>0);if(confirmed.optInt("episode")<1)confirmed.put("episode",e.optInt("episode"));if(confirmed.optString("title").isEmpty())confirmed.put("title",e.optString("title"));p=store(a).confirm(page,confirmed,key);}
            final JSONObject result=p;UI.post(()->{
                if(!valid(a,t,page)||!targetId.equals(target))return;
                if(command.equals("dismiss")){hide();refresh(a,t,page,targetId,id);return;}
                notice="";
                if(result!=null)try{state=new JSONObject().put("pending",result);}catch(JSONException ignored){}
                if(command.equals("confirmed")){if(result!=null)resume(a,t,page,targetId,result.optJSONObject("entry"));return;}
                if(result!=null){String destination=result.optString("url");if(!destination.isEmpty()&&!WatchUrl.same(destination,page)){navigate(a,t,page,destination);return;}}
                refresh(a,t,page,targetId,id);
                if(command.equals("search")||(result!=null&&result.optBoolean("search")))engine.continuationSearch(targetId,page,e);
            });
        }catch(Exception failure){UI.post(()->{if(valid(a,t,page)){notice="Search this site manually, then confirm the episode.";render();}});}});
    }
    private static void navigate(Activity a,Object t,String expected,String destination){
        if(!valid(a,t,expected)||!ResumeLocation.valid(destination)||SiteKey.watchHost(destination).isEmpty())return;
        try{Class<?> params=Class.forName("org.chromium.content_public.browser.LoadUrlParams");Object request=params.getConstructor(int.class,String.class).newInstance(0,destination);Class.forName("org.chromium.chrome.browser.tab.Tab").getMethod("k",params).invoke(t,request);hide();}
        catch(Exception failure){notice="Open the site manually; your continuation is saved.";render();}
    }
    static void navigateRequest(String targetId,String page,String destination){UI.post(()->{if(!targetId.equals(target)||!valid(owner,tab,page)||!SiteKey.of(page).equals(SiteKey.of(destination))||pending()==null)return;navigate(owner,tab,page,destination);});}
    static void applyRequest(String targetId,String page,JSONObject detected){UI.post(()->{
        if(!targetId.equals(target)||!valid(owner,tab,page)||pending()==null)return;
        JSONObject e=entry(),id=normalized(detected);if(e==null||!id.optBoolean("seasonKnown")||!"watch".equals(id.optString("kind"))||!titleKey(e.optString("title")).equals(titleKey(id.optString("title")))||e.optInt("season",1)!=id.optInt("season",1)||e.optInt("part")!=id.optInt("part")||e.optInt("episode")!=id.optInt("episode"))return;
        Activity a=owner;Object t=tab;IO.execute(()->{try{JSONObject p=store(a).confirm(page,id,scope(t));UI.post(()->{if(p!=null&&valid(a,t,page))resume(a,t,page,targetId,p.optJSONObject("entry"));});}catch(Exception ignored){}});
    });}
    private static void resume(Activity a,Object t,String page,String targetId,JSONObject e){
        if(e==null||!valid(a,t,page))return;engine.confirmContinuationIdentity(targetId,page,e);resumeTarget=targetId;resumeUrl=page;resumeScope=scope(t);double speed=e.optDouble("speed",1);PlaybackSettings.set(a,SiteKey.of(page),speed);engine.resumeContinuation(targetId,page,e);notice="Restoring saved position. Press Play when ready.";render();
    }
    static void applied(String targetId,String page,boolean success,String reason){UI.post(()->{
        if(!targetId.equals(resumeTarget)||!WatchUrl.same(page,resumeUrl)||!valid(owner,tab,page)||scope(tab)!=resumeScope)return;
        if(!success){notice="This player could not restore the position. Use the saved time manually.";render();return;}
        Activity a=owner;Object t=tab;IO.execute(()->{store(a).markApplied(scope(t));UI.post(()->{if(valid(a,t,page)){notice="Position restored. Press Play when ready.";refresh(a,t,page,targetId,identity);}});});
    });}
    static void hide(){UI.removeCallbacks(timeout);UI.removeCallbacks(pendingTimeout);if(decorOwner!=null&&privacy!=null&&decorOwner.getViewTreeObserver().isAlive())decorOwner.getViewTreeObserver().removeOnPreDrawListener(privacy);decorOwner=null;privacy=null;if(card!=null&&card.getParent() instanceof ViewGroup)((ViewGroup)card.getParent()).removeView(card);card=null;signature="";}
    private static void forgetPresentation(String key){JSONObject e=entry();if(key==null||(e!=null&&key.equals(e.optString("key")))){if(engine!=null&&valid(owner,tab,url))engine.continuationState(target,url,new JSONObject());pause();}}
    static void pause(){hide();owner=null;tab=null;target="";state=null;identity=null;notice="";resumeTarget="";resumeUrl="";}
    static void library(Activity a){
        if(a!=AniRuntime.continuationActivity()){new AlertDialog.Builder(a).setMessage("Watch history is unavailable in private tabs.").setPositiveButton("Close",null).show();return;}
        IO.execute(()->{JSONArray list=store(a).library();String[] labels=new String[list.length()];for(int i=0;i<labels.length;i++){JSONObject e=list.optJSONObject(i);labels[i]=e.optString("title")+" · "+detail(e)+(e.optBoolean("completed")?" · Completed":"");}UI.post(()->{if(a!=AniRuntime.continuationActivity())return;new AlertDialog.Builder(a).setTitle("Watched anime").setItems(labels,(d,i)->edit(a,list.optJSONObject(i))).setNeutralButton("Delete all history",(d,w)->new AlertDialog.Builder(a).setMessage("Delete all local watch history, continuation and learned links?").setPositiveButton("Delete",(x,z)->{if(a!=AniRuntime.continuationActivity())return;IO.execute(()->{store(a).clear();try(AnimeHistory legacy=new AnimeHistory(a)){legacy.clearHistory();}});forgetPresentation(null);}).setNegativeButton("Cancel",null).show()).setNegativeButton("Close",null).show();});});
    }
    private static void edit(Activity a,JSONObject entry){
        new AlertDialog.Builder(a).setTitle(entry.optString("title")).setItems(new String[]{"Continue here","Edit episode details","Delete episode history","Watch log"},(d,index)->{
            if(a!=AniRuntime.continuationActivity())return;
            if(index==3){AniRuntime.continuationLog(a,entry);return;}
            if(index==0){if(valid(owner,tab,url))perform(owner,tab,url,target,entry,identity,"here");else Toast.makeText(a,"Open a supported anime site first",Toast.LENGTH_SHORT).show();}
            else if(index==2)new AlertDialog.Builder(a).setMessage("Delete this episode's saved progress and learned links?").setPositiveButton("Delete",(x,w)->{if(a!=AniRuntime.continuationActivity())return;forgetPresentation(entry.optString("key"));IO.execute(()->{store(a).forget(entry.optString("key"));try(AnimeHistory legacy=new AnimeHistory(a)){legacy.deleteEpisode(entry.optString("title")+(entry.optInt("season",1)>1?" Season "+entry.optInt("season"):"")+(entry.optInt("part")>0?" Part "+entry.optInt("part"):""),entry.optInt("episode"));}});}).setNegativeButton("Cancel",null).show();
            else{LinearLayout fields=new LinearLayout(a);fields.setOrientation(1);EditText title=new EditText(a),season=new EditText(a),episode=new EditText(a),position=new EditText(a);title.setText(entry.optString("title"));title.setHint("Series title");season.setText(Integer.toString(entry.optInt("season",1)));season.setHint("Season");episode.setText(Integer.toString(entry.optInt("episode")));episode.setHint("Episode");position.setText(Integer.toString(entry.optInt("position")));position.setHint("Position in seconds");for(EditText f:new EditText[]{title,season,episode,position})fields.addView(f);
                new AlertDialog.Builder(a).setTitle("Correct episode").setView(fields).setPositiveButton("Save",(x,w)->{if(a!=AniRuntime.continuationActivity())return;try{JSONObject changes=new JSONObject(entry.toString());changes.put("title",title.getText().toString());changes.put("season",Integer.parseInt(season.getText().toString()));changes.put("episode",Integer.parseInt(episode.getText().toString()));changes.put("position",Integer.parseInt(position.getText().toString()));IO.execute(()->{JSONObject corrected=store(a).correct(entry.optString("key"),changes);if(corrected==null)UI.post(()->Toast.makeText(a,"Enter valid season, episode and seconds",Toast.LENGTH_LONG).show());});}catch(Exception failure){Toast.makeText(a,"Enter valid season, episode and seconds",Toast.LENGTH_LONG).show();}}).setNegativeButton("Cancel",null).show();}
        }).setNegativeButton("Close",null).show();
    }
}
