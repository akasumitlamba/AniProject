package app.anibrave;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Map;

/** Separate from watch logs; no positions, accounts, private tabs or cloud storage. */
final class ResumeStore {
    private static final int LIMIT=100;
    private static SharedPreferences preferences(Context c){return c.getSharedPreferences("anibrave_resume",0);}
    static String resolve(Context c,String seed){return ResumeLocation.resolve(seed,preferences(c).getString("url:"+SiteKey.of(seed),""));}
    static synchronized void capture(Context c,int task,String url){
        if(!ResumeLocation.valid(url))return;
        SharedPreferences p=preferences(c);String key="task:"+task;
        if(url.equals(p.getString(key,"")))return;
        // Persistent task identity survives Activity/process recreation. An invalid
        // transient URL never replaces the task's last observed valid location.
        SharedPreferences.Editor edit=p.edit().putString(key,url).putLong("task-time:"+task,System.currentTimeMillis());
        int tasks=0;String oldest=null;long oldestTime=Long.MAX_VALUE;
        for(String name:p.getAll().keySet())if(name.startsWith("task:")){
            tasks++;long t=p.getLong("task-time:"+name.substring(5),0);
            if(!name.equals(key)&&t<oldestTime){oldest=name;oldestTime=t;}
        }
        if(tasks>=200&&!p.contains(key)&&oldest!=null)edit.remove(oldest).remove("task-time:"+oldest.substring(5));
        edit.apply();
        save(c,url);
    }
    static synchronized void save(Context c,String url){
        if(!ResumeLocation.valid(url))return;
        SharedPreferences p=preferences(c);String site=SiteKey.of(url);
        if(url.equals(p.getString("url:"+site,"")))return;
        SharedPreferences.Editor edit=p.edit().putString("url:"+site,url).putLong("time:"+site,System.currentTimeMillis());
        Map<String,?> all=p.getAll();int count=0;String oldest=null;long time=Long.MAX_VALUE;
        for(Map.Entry<String,?> entry:all.entrySet())if(entry.getKey().startsWith("url:")){
            count++;String key=entry.getKey().substring(4);long t=p.getLong("time:"+key,0);
            if(!key.equals(site)&&t<time){oldest=key;time=t;}
        }
        if(count>=LIMIT&&!all.containsKey("url:"+site)&&oldest!=null)edit.remove("url:"+oldest).remove("time:"+oldest);
        edit.apply();
    }
}
