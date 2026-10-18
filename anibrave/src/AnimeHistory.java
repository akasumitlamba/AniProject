package app.anibrave;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import org.json.*;
import java.util.*;

/** Device-only SQLite history. No network, analytics, account or cloud dependency. */
final class AnimeHistory extends SQLiteOpenHelper {
    static final class Summary {
        final String key,title,host; final int episode; final boolean dismissed;
        Summary(Cursor c){key=c.getString(0);title=c.getString(1);episode=c.getInt(2);host=c.getString(3);dismissed=c.getInt(4)!=0;}
    }
    AnimeHistory(Context c){super(c,"anibrave-watch.db",null,1);}
    public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE anime(key TEXT PRIMARY KEY,title TEXT NOT NULL,muted INTEGER NOT NULL DEFAULT 0,reminder_dismissed INTEGER NOT NULL DEFAULT 0,last_episode INTEGER NOT NULL DEFAULT 0,last_host TEXT,last_url TEXT,updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE site_progress(anime_key TEXT NOT NULL,host TEXT NOT NULL,max_episode INTEGER NOT NULL,last_episode INTEGER NOT NULL,last_url TEXT,position_sec INTEGER NOT NULL DEFAULT 0,duration_sec INTEGER NOT NULL DEFAULT 0,updated_at INTEGER NOT NULL,PRIMARY KEY(anime_key,host))");
        db.execSQL("CREATE TABLE episode_log(id INTEGER PRIMARY KEY AUTOINCREMENT,anime_key TEXT NOT NULL,episode INTEGER NOT NULL,host TEXT NOT NULL,url TEXT,watched_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX episode_log_anime ON episode_log(anime_key,watched_at)");
        db.execSQL("CREATE INDEX episode_log_import ON episode_log(anime_key,episode,host,watched_at)");
    }
    public void onUpgrade(SQLiteDatabase db,int old,int next){throw new IllegalStateException("Unknown history schema");}
    synchronized Summary summary(String key){try(Cursor c=getReadableDatabase().rawQuery("SELECT key,title,last_episode,last_host,reminder_dismissed FROM anime WHERE key=?",new String[]{key})){return c.moveToFirst()?new Summary(c):null;}}
    synchronized List<Summary> library(){List<Summary> result=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT key,title,last_episode,last_host,reminder_dismissed FROM anime ORDER BY updated_at DESC",null)){while(c.moveToNext())result.add(new Summary(c));}return result;}
    synchronized void dismiss(String key){ContentValues v=new ContentValues();v.put("reminder_dismissed",1);getWritableDatabase().update("anime",v,"key=?",new String[]{key});}
    synchronized String log(String key){StringBuilder text=new StringBuilder();java.text.DateFormat date=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM,java.text.DateFormat.SHORT);try(Cursor c=getReadableDatabase().rawQuery("SELECT episode,host,watched_at FROM episode_log WHERE anime_key=? ORDER BY watched_at DESC",new String[]{key})){while(c.moveToNext()){if(text.length()>0)text.append("\n\n");text.append(date.format(new Date(c.getLong(2)))).append("\n").append(c.getString(1)).append(" · Episode ").append(c.getInt(0));}}return text.length()==0?"No watched episodes yet":text.toString();}
    synchronized Summary record(String title,int episode,String host,String url,int position,int duration){
        String key=AnimeIdentity.key(title);if(key.isEmpty()||episode<1||episode>9999)return null;
        long now=System.currentTimeMillis();SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            Summary old=summary(key);ContentValues v=new ContentValues();v.put("title",old!=null&&old.title.length()>title.length()?old.title:title);v.put("last_episode",episode);v.put("last_host",host);v.put("last_url",url);v.put("updated_at",now);v.put("reminder_dismissed",AnimeIdentity.staysDismissed(old!=null&&old.dismissed,old==null?0:old.episode,episode)?1:0);
            if(old==null){v.put("key",key);db.insertOrThrow("anime",null,v);}else db.update("anime",v,"key=?",new String[]{key});
            int max=episode;try(Cursor c=db.rawQuery("SELECT max_episode FROM site_progress WHERE anime_key=? AND host=?",new String[]{key,host})){if(c.moveToFirst())max=Math.max(max,c.getInt(0));}
            v=new ContentValues();v.put("anime_key",key);v.put("host",host);v.put("max_episode",max);v.put("last_episode",episode);v.put("last_url",url);v.put("position_sec",position);v.put("duration_sec",duration);v.put("updated_at",now);db.insertWithOnConflict("site_progress",null,v,SQLiteDatabase.CONFLICT_REPLACE);
            long id=0,time=0;int previous=0;String oldHost="";try(Cursor c=db.rawQuery("SELECT id,episode,host,watched_at FROM episode_log WHERE anime_key=? ORDER BY watched_at DESC LIMIT 1",new String[]{key})){if(c.moveToFirst()){id=c.getLong(0);previous=c.getInt(1);oldHost=c.getString(2);time=c.getLong(3);}}
            v=new ContentValues();v.put("anime_key",key);v.put("episode",episode);v.put("host",host);v.put("url",url);v.put("watched_at",now);
            if(id==0||AnimeIdentity.newSitting(previous,oldHost,time,episode,host,now))db.insertOrThrow("episode_log",null,v);else db.update("episode_log",v,"id=?",new String[]{Long.toString(id)});
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        return summary(key);
    }
    private static String bounded(JSONObject row,String field,int max,boolean nullable)throws JSONException{if(row.isNull(field)&&nullable)return null;String value=row.getString(field);if(value.length()>max||(!nullable&&value.isEmpty()))throw new JSONException("Invalid "+field);return value;}
    private static long timestamp(JSONObject row,String field)throws JSONException{long t=row.getLong(field);if(t<0||t>System.currentTimeMillis()+86400000L)throw new JSONException("Invalid date");return t;}
    private static int episode(JSONObject row,String field)throws JSONException{int n=row.getInt(field);if(n<0||n>9999)throw new JSONException("Invalid episode");return n;}
    private static String url(JSONObject row,String field)throws JSONException{String u=bounded(row,field,4096,true);if(u!=null&&!u.startsWith("https://")&&!u.startsWith("http://"))throw new JSONException("Invalid watch URL");return u;}
    synchronized int importHistory(JSONObject data)throws JSONException{
        if(data.getInt("version")!=1||!"app.anihome.launcher".equals(data.getString("source")))throw new JSONException("Unknown source");
        JSONArray anime=data.getJSONArray("anime"),sites=data.getJSONArray("site_progress"),logs=data.getJSONArray("episode_log");
        if(anime.length()>10000||sites.length()>30000||logs.length()>100000)throw new JSONException("History too large");
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();int added=0;
        try{
            for(int i=0;i<anime.length();i++){
                JSONObject row=anime.getJSONObject(i);String key=bounded(row,"key",100,false);long time=timestamp(row,"updated_at");ContentValues v=new ContentValues();v.put("title",bounded(row,"title",500,false));v.put("last_episode",episode(row,"last_episode"));v.put("last_host",bounded(row,"last_host",253,true));v.put("last_url",url(row,"last_url"));v.put("updated_at",time);v.put("muted",row.optInt("muted",0)==1?1:0);v.put("reminder_dismissed",row.optInt("reminder_dismissed",0)==1?1:0);
                long existing=-1;try(Cursor c=db.rawQuery("SELECT updated_at FROM anime WHERE key=?",new String[]{key})){if(c.moveToFirst())existing=c.getLong(0);}
                if(existing<0){v.put("key",key);db.insertOrThrow("anime",null,v);}else if(time>existing)db.update("anime",v,"key=?",new String[]{key});
            }
            for(int i=0;i<sites.length();i++){
                JSONObject row=sites.getJSONObject(i);String key=bounded(row,"anime_key",100,false),host=bounded(row,"host",253,false);if(summary(key)==null)throw new JSONException("Orphan site");long time=timestamp(row,"updated_at");ContentValues v=new ContentValues();v.put("anime_key",key);v.put("host",host);v.put("last_episode",episode(row,"last_episode"));v.put("max_episode",episode(row,"max_episode"));v.put("last_url",url(row,"last_url"));v.put("position_sec",Math.max(0,row.optInt("position_sec")));v.put("duration_sec",Math.max(0,row.optInt("duration_sec")));v.put("updated_at",time);
                long existing=-1;int maximum=0;try(Cursor c=db.rawQuery("SELECT updated_at,max_episode FROM site_progress WHERE anime_key=? AND host=?",new String[]{key,host})){if(c.moveToFirst()){existing=c.getLong(0);maximum=c.getInt(1);}}
                v.put("max_episode",Math.max(maximum,v.getAsInteger("max_episode")));if(time>existing)db.insertWithOnConflict("site_progress",null,v,SQLiteDatabase.CONFLICT_REPLACE);
            }
            for(int i=0;i<logs.length();i++){
                JSONObject row=logs.getJSONObject(i);String key=bounded(row,"anime_key",100,false),host=bounded(row,"host",253,false);if(summary(key)==null)throw new JSONException("Orphan episode");int ep=episode(row,"episode");long time=timestamp(row,"watched_at");String watchUrl=url(row,"url");
                try(Cursor c=db.rawQuery("SELECT id FROM episode_log WHERE anime_key=? AND episode=? AND host=? AND watched_at=?",new String[]{key,Integer.toString(ep),host,Long.toString(time)})){if(c.moveToFirst())continue;}
                ContentValues v=new ContentValues();v.put("anime_key",key);v.put("episode",ep);v.put("host",host);v.put("url",watchUrl);v.put("watched_at",time);db.insertOrThrow("episode_log",null,v);added++;
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}return added;
    }
}
