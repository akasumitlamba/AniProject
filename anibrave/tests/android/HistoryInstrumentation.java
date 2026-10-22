package app.anibrave;

import android.app.Instrumentation;
import android.app.Activity;
import android.os.Bundle;
import android.database.Cursor;
import org.json.*;

/** Real Android SQLite regressions. Runs only in the disposable QA package. */
public final class HistoryInstrumentation extends Instrumentation {
    private int checks;
    private void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
    private JSONObject export(long now,int episode)throws Exception{
        JSONObject anime=new JSONObject().put("key","example").put("title","Example").put("last_episode",episode).put("last_host","hianime.to").put("last_url","https://hianime.to/watch/example").put("updated_at",now);
        JSONObject site=new JSONObject().put("anime_key","example").put("host","hianime.to").put("max_episode",episode).put("last_episode",episode).put("last_url","https://hianime.to/watch/example").put("updated_at",now);
        JSONObject log=new JSONObject().put("anime_key","example").put("host","hianime.to").put("episode",episode).put("url","https://hianime.to/watch/example").put("watched_at",now);
        return new JSONObject().put("version",1).put("source","app.anihome.launcher").put("anime",new JSONArray().put(anime)).put("site_progress",new JSONArray().put(site)).put("episode_log",new JSONArray().put(log));
    }
    public static final class FakeTab { public int getId(){return 7;} }
    public static final class FakeModel {
        boolean privateMode=true; int selected;
        public boolean isIncognito(){return privateMode;}
        public Object getTabById(int id){return new FakeTab();}
        public int getCount(){return 1;}
        public Object getTabAt(int index){return new FakeTab();}
        public void B0(int index,int reason){selected++;}
    }
    public static final class FakeSelector {
        final FakeModel model=new FakeModel();int switched;
        public Object h(){return model;}
        public Object k(boolean privateMode){return model;}
        public void F(boolean privateMode){switched++;}
    }
    public static final class FakeActivity extends Activity {
        final FakeSelector selector=new FakeSelector();
        public Object v4(){return selector;}
        @Override public android.content.Intent getIntent(){return new android.content.Intent();}
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            getTargetContext().deleteDatabase("anibrave-watch.db");
            long now=System.currentTimeMillis();
            try(AnimeHistory history=new AnimeHistory(getTargetContext())){
                check(history.importHistory(export(now,5)).logs==1,"first import adds log");
                check(history.importHistory(export(now-100000,12)).logs==1,"older import adds distinct log");
                try(Cursor c=history.getReadableDatabase().rawQuery("SELECT max_episode,last_episode,updated_at FROM site_progress",null)){
                    check(c.moveToFirst(),"site row exists");check(c.getInt(0)==12,"older import preserves higher maximum");check(c.getInt(1)==5,"newer last episode retained");check(c.getLong(2)==now,"newer timestamp retained");
                }
                AnimeHistory.ImportResult repeated=history.importHistory(export(now-100000,12));
                check(repeated.logs==0,"repeat import deduplicates log");
                check(repeated.message().contains("already present"),"unchanged import clearly reported");
                JSONObject summaryOnly=export(now+100,5);summaryOnly.put("episode_log",new JSONArray());
                AnimeHistory.ImportResult merged=history.importHistory(summaryOnly);
                check(merged.logs==0&&merged.shows==1&&merged.sites==1,"summary-only changes counted independently");
                check(!merged.message().contains("already present"),"summary-only changes reported as success");
                check(history.summary("example").episode==5,"newer show summary retained");
                JSONObject invalid=export(now+1000,20);
                invalid.getJSONArray("episode_log").getJSONObject(0).put("episode",10000);
                boolean rejected=false;try{history.importHistory(invalid);}catch(JSONException expected){rejected=true;}
                check(rejected,"invalid import rejected");
                check(history.summary("example").episode==5,"failed import rolls back show update");
                try(Cursor c=history.getReadableDatabase().rawQuery("SELECT max_episode FROM site_progress",null)){c.moveToFirst();check(c.getInt(0)==12,"failed import rolls back site update");}
                long dismissedAt=System.currentTimeMillis();history.dismiss("example");
                try(Cursor c=history.getReadableDatabase().rawQuery("SELECT updated_at FROM anime WHERE key='example'",null)){c.moveToFirst();check(c.getLong(0)>=dismissedAt,"dismissal timestamps local summary");}
                history.importHistory(export(now+2000,5));
                check(history.summary("example").dismissed,"newer same-episode import retains local dismissal");
                history.importHistory(export(now+3000,6));
                check(!history.summary("example").dismissed,"different imported episode can re-arm reminder");
                history.record("Revisit",5,"hianime.to","https://hianime.to/watch/revisit",100,1200);
                history.record("Revisit",6,"hianime.to","https://hianime.to/watch/revisit",100,1200);
                history.record("Revisit",5,"HIANIME.TO","https://hianime.to/watch/revisit",200,1200);
                try(Cursor c=history.getReadableDatabase().rawQuery("SELECT count(*) FROM episode_log WHERE anime_key='revisit' AND episode=5",null)){c.moveToFirst();check(c.getInt(0)==1,"return to an episode coalesces same sitting despite an intervening episode and host case");}
                try(Cursor c=history.getReadableDatabase().rawQuery("SELECT count(*) FROM episode_log WHERE anime_key='revisit'",null)){c.moveToFirst();check(c.getInt(0)==2,"different episodes keep separate log rows");}
                check(history.summary("revisit").episode==5,"return updates latest summary");
                check(history.record("The 100",9,"hianime.to","https://hianime.to/watch/numeric",90,1200)!=null,"numeric title saved");
                check(history.record("86",3,"hianime.to","https://hianime.to/watch/86",90,1200)!=null,"two-digit title saved");
                history.dismiss("100");check(history.summary("100").dismissed,"explicit dismissal saved");
                history.record("The 100",9,"hianime.to","https://hianime.to/watch/numeric",100,1200);
                check(history.summary("100").dismissed,"same episode retains dismissal");
                history.record("The 100",10,"hianime.to","https://hianime.to/watch/numeric",100,1200);
                check(!history.summary("100").dismissed,"new episode re-arms reminder");
            }
            try(AnimeHistory reopened=new AnimeHistory(getTargetContext())){check(reopened.summary("100").episode==10,"history survives database reopen");}
            String seed="https://hianime.to/",ep5="https://hianime.to/watch/example?ep=5",ep6="https://hianime.to/watch/example?ep=6";
            getTargetContext().getSharedPreferences("anibrave_resume",0).edit().clear().commit();
            ResumeStore.capture(getTargetContext(),1,ep5);ResumeStore.capture(getTargetContext(),2,ep6);
            check(ResumeStore.resolve(getTargetContext(),seed).equals(ep6),"newest task location is saved");
            ResumeStore.capture(getTargetContext(),1,ep5);
            check(ResumeStore.resolve(getTargetContext(),seed).equals(ep6),"unchanged older task cannot overwrite newer task");
            ResumeStore.capture(getTargetContext(),1,"");ResumeStore.capture(getTargetContext(),1,"about:blank");ResumeStore.capture(getTargetContext(),1,ep5);
            check(ResumeStore.resolve(getTargetContext(),seed).equals(ep6),"invalid reads do not erase task memo or resave old location");
            android.content.Context recreated=getTargetContext().createPackageContext(getTargetContext().getPackageName(),0);
            ResumeStore.capture(recreated,1,ep5);
            check(ResumeStore.resolve(recreated,seed).equals(ep6),"recreated context loads persistent task memo");
            String ep7="https://hianime.to/watch/example?ep=7";ResumeStore.capture(recreated,1,ep7);
            check(ResumeStore.resolve(recreated,seed).equals(ep7),"real navigation in an older task still updates resume");
            runOnMainSync(()->{
                try {
                    FakeActivity activity=new FakeActivity();NativeTabs tabs=new NativeTabs(activity);
                    check(!tabs.selectById(7,false),"stale regular row cannot select after private switch");
                    check(!tabs.closeById(7,false),"stale regular row cannot close after private switch");
                    check(activity.selector.switched==0,"stale selection does not force profile change");
                    check(tabs.selectById(7,true),"fresh row still selects its native tab");
                    check(activity.selector.model.selected==1,"native selection invoked once");
                } catch(Exception failure){throw new RuntimeException(failure);}
            });
            result.putString("stream",checks+" Android SQLite/history checks passed\n");finish(Activity.RESULT_OK,result);
        }catch(Throwable failure){result.putString("stream",failure.toString());finish(Activity.RESULT_CANCELED,result);}
    }
}
