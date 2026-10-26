package app.anibrave;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import org.json.*;
import java.net.*;
import java.text.Normalizer;
import java.util.*;

/** Device-only episode state. Does not modify the legacy watch database. */
final class ContinuationStore extends SQLiteOpenHelper {
    private final Context context;
    private boolean migrating;
    private static final long TTL=1800000L;
    ContinuationStore(Context c){super(c,"anibrave-continuation.db",null,1);context=c.getApplicationContext();}
    public void onCreate(SQLiteDatabase d){
        d.execSQL("CREATE TABLE episodes(k TEXT PRIMARY KEY,series TEXT,title TEXT,season INTEGER,part INTEGER,episode INTEGER,position REAL,duration REAL,speed REAL,completed INTEGER,ranges TEXT,url TEXT,host TEXT,updated INTEGER,generation INTEGER)");
        d.execSQL("CREATE TABLE locations(k TEXT,host TEXT,url TEXT,PRIMARY KEY(k,host))");
        d.execSQL("CREATE TABLE dismissed(host TEXT,k TEXT,generation INTEGER,PRIMARY KEY(host,k))");
        d.execSQL("CREATE TABLE pending(task INTEGER PRIMARY KEY,data TEXT,expires INTEGER)");
        d.execSQL("CREATE TABLE aliases(host TEXT,alias TEXT,season INTEGER,part INTEGER,canonical TEXT,target_season INTEGER,target_part INTEGER,offset INTEGER,PRIMARY KEY(host,alias,season,part))");
        d.execSQL("CREATE TABLE metadata(k TEXT,host TEXT,series_id TEXT,episode_id TEXT,PRIMARY KEY(k,host))");
        d.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY,v TEXT)");
    }
    public void onUpgrade(SQLiteDatabase d,int a,int b){throw new IllegalStateException("Unknown continuation schema");}
    static String host(String url){try{URI u=new URI(url);String h=u.getHost();if(h==null||u.getUserInfo()!=null||url.length()>4096||!("https".equalsIgnoreCase(u.getScheme())||"http".equalsIgnoreCase(u.getScheme())))return "";h=h.toLowerCase(Locale.ROOT).replaceFirst("^www\\.","");for(String allowed:new String[]{"crunchyroll.com","hianime.to","hianimez.to","anikototv.to"})if(h.equals(allowed)||h.endsWith("."+allowed))return allowed;return "";}catch(Exception e){return "";}}
    static String titleKey(String title){return Normalizer.normalize(title,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+"," ").trim();}
    private static double number(JSONObject o,String k,double fallback,double max){double v=o.optDouble(k,fallback);return Double.isNaN(v)||Double.isInfinite(v)||v<0||v>max?fallback:v;}
    private JSONObject identity(JSONObject i,String url){return identity(i,url,true);}
    private JSONObject identity(JSONObject i,String url,boolean aliases){
        if(i==null||host(url).isEmpty())return null;String title=i.optString("seriesTitle",i.optString("title","")).trim();int s=i.optInt("season",1),p=i.optInt("part",0),e=i.optInt("episode",0);
        if(title.length()<2||title.length()>500||s<1||s>100||p<0||p>100||e<1||e>9999)return null;
        try{String series=titleKey(title);if(series.isEmpty())return null;if(aliases)try(Cursor c=getReadableDatabase().rawQuery("SELECT canonical,offset,target_season,target_part FROM aliases WHERE host=? AND alias=? AND season=? AND part=?",new String[]{host(url),series,""+s,""+p})){if(c.moveToFirst()){series=c.getString(0);e+=c.getInt(1);s=c.getInt(2);p=c.getInt(3);}}if(e<1||e>9999)return null;return new JSONObject().put("title",title).put("series",series).put("season",s).put("part",p).put("episode",e).put("key",series+"|s"+s+"|p"+p+"|e"+e);}catch(Exception ex){return null;}
    }
    static JSONArray merge(JSONArray a,JSONArray b,double duration)throws JSONException{
        List<double[]> list=new ArrayList<>();for(JSONArray group:new JSONArray[]{a,b})if(group!=null)for(int n=0;n<Math.min(group.length(),1000);n++){JSONArray r=group.optJSONArray(n);if(r==null||r.length()!=2)continue;double x=r.optDouble(0,-1),y=r.optDouble(1,-1);if(Double.isNaN(x)||Double.isNaN(y)||Double.isInfinite(x)||Double.isInfinite(y)||x<0||y<=x||y>86400)continue;if(duration>0)y=Math.min(y,duration);if(y>x)list.add(new double[]{x,y});}
        list.sort((x,y)->Double.compare(x[0],y[0]));List<double[]> out=new ArrayList<>();for(double[] r:list){if(!out.isEmpty()&&r[0]<=out.get(out.size()-1)[1]+.5)out.get(out.size()-1)[1]=Math.max(out.get(out.size()-1)[1],r[1]);else out.add(r.clone());}JSONArray result=new JSONArray();for(int n=0;n<Math.min(out.size(),1000);n++)result.put(new JSONArray().put(out.get(n)[0]).put(out.get(n)[1]));return result;
    }
    private JSONObject row(Cursor c)throws JSONException{return new JSONObject().put("key",c.getString(0)).put("series",c.getString(1)).put("title",c.getString(2)).put("season",c.getInt(3)).put("part",c.getInt(4)).put("episode",c.getInt(5)).put("position",c.getDouble(6)).put("duration",c.getDouble(7)).put("speed",c.getDouble(8)).put("completed",c.getInt(9)!=0).put("ranges",new JSONArray(c.getString(10))).put("url",c.getString(11)).put("host",c.getString(12)).put("updated",c.getLong(13)).put("generation",c.getLong(14));}
    private JSONObject enrich(JSONObject e){if(e==null)return null;try(Cursor c=getReadableDatabase().rawQuery("SELECT host,series_id,episode_id FROM metadata WHERE k=?",new String[]{e.optString("key")})){JSONObject sites=new JSONObject();while(c.moveToNext()){JSONObject item=new JSONObject().put("seriesId",c.getString(1)).put("episodeId",c.getString(2));sites.put(c.getString(0),item);if(c.getString(0).equals(e.optString("host"))){e.put("seriesId",c.getString(1));e.put("episodeId",c.getString(2));}}e.put("siteMetadata",sites);}catch(Exception ignored){}return e;}
    private JSONObject get(String key){try(Cursor c=getReadableDatabase().rawQuery("SELECT * FROM episodes WHERE k=?",new String[]{key})){return c.moveToFirst()?enrich(row(c)):null;}catch(Exception e){return null;}}
    synchronized JSONObject record(JSONObject i,JSONObject progress){return recordInternal(i,progress,false);}
    private JSONObject recordInternal(JSONObject i,JSONObject progress,boolean canonical){
        migrate();String url=i==null?"":i.optString("url","");if(url.isEmpty()&&progress!=null)url=progress.optString("url","");JSONObject id=identity(i,url,!canonical);if(id==null||progress==null||!progress.optBoolean("qualified",false))return null;
        SQLiteDatabase d=getWritableDatabase();d.beginTransaction();try{String key=id.getString("key");JSONObject old=get(key);double duration=number(progress,"duration",0,86400),position=number(progress,"position",0,86400),speed=number(progress,"speed",1,16);if(duration==0&&old!=null)duration=old.optDouble("duration",0);if(duration>0)position=Math.min(position,duration);if(speed<.1)speed=1;JSONArray ranges=merge(old==null?null:old.optJSONArray("ranges"),progress.optJSONArray("ranges"),duration);double covered=0;for(int n=0;n<ranges.length();n++){JSONArray r=ranges.getJSONArray(n);covered+=r.getDouble(1)-r.getDouble(0);}boolean done=old!=null&&old.optBoolean("completed")||duration>=60&&covered>=duration*.85;long now=System.currentTimeMillis();ContentValues v=new ContentValues();v.put("k",key);v.put("series",id.getString("series"));v.put("title",id.getString("title"));v.put("season",id.getInt("season"));v.put("part",id.getInt("part"));v.put("episode",id.getInt("episode"));v.put("position",position);v.put("duration",duration);v.put("speed",speed);v.put("completed",done?1:0);v.put("ranges",ranges.toString());v.put("url",url);v.put("host",host(url));v.put("updated",now);v.put("generation",old==null?now:old.optLong("generation"));d.insertWithOnConflict("episodes",null,v,SQLiteDatabase.CONFLICT_REPLACE);learn(key,url);metadata(key,url,i);d.setTransactionSuccessful();return get(key);}catch(Exception e){return null;}finally{d.endTransaction();}
    }
    private void metadata(String key,String url,JSONObject id){
        String series=id.optString("seriesId", "").trim(),episode=id.optString("episodeId", "").trim();
        if(series.length()>500||episode.length()>500||(series.isEmpty()&&episode.isEmpty()))return;
        // Page metadata can arrive in stages. Missing fields are not deletions.
        try(Cursor c=getReadableDatabase().rawQuery("SELECT series_id,episode_id FROM metadata WHERE k=? AND host=?",new String[]{key,host(url)})){
            if(c.moveToFirst()){
                if(series.isEmpty())series=c.getString(0);
                if(episode.isEmpty())episode=c.getString(1);
            }
        }
        ContentValues v=new ContentValues();v.put("k",key);v.put("host",host(url));
        v.put("series_id",series);v.put("episode_id",episode);
        getWritableDatabase().insertWithOnConflict("metadata",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }
    private void learn(String key,String url){if(host(url).isEmpty())return;ContentValues v=new ContentValues();v.put("k",key);v.put("host",host(url));v.put("url",url);getWritableDatabase().insertWithOnConflict("locations",null,v,SQLiteDatabase.CONFLICT_REPLACE);}
    synchronized JSONArray library(){migrate();JSONArray a=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT * FROM episodes ORDER BY updated DESC LIMIT 10000",null)){while(c.moveToNext())a.put(enrich(row(c)));}catch(Exception e){}return a;}
    // The scope is a persistent Chromium tab ID, not the enclosing Android task ID.
    private JSONObject pending(int task){getWritableDatabase().delete("pending","expires<=?",new String[]{""+System.currentTimeMillis()});try(Cursor c=getReadableDatabase().rawQuery("SELECT data FROM pending WHERE task=? AND expires>?",new String[]{""+task,""+System.currentTimeMillis()})){return c.moveToFirst()?new JSONObject(c.getString(0)):null;}catch(Exception e){return null;}}
    synchronized JSONObject learned(String url){migrate();JSONObject found=null;try(Cursor c=getReadableDatabase().rawQuery("SELECT k,url FROM locations WHERE host=? LIMIT 10000",new String[]{host(url)})){while(c.moveToNext())if(WatchUrl.same(url,c.getString(1))){JSONObject e=get(c.getString(0));if(e!=null){if(found!=null&&!found.optString("key").equals(e.optString("key")))return null;found=e;}}}return found;}
    synchronized JSONObject state(String url,JSONObject id,int task){
        migrate();JSONObject out=new JSONObject();if(host(url).isEmpty())return out;try{JSONObject p=pending(task);if(p!=null){if(host(url).equals(p.optString("targetHost")))out.put("pending",p);return out;}String kind=id==null?"":id.optString("kind","");if(!kind.isEmpty()&&!kind.equals("home")&&!kind.equals("watch")&&!kind.equals("series"))return out;String series=id==null||kind.equals("home")?"":titleKey(id.optString("seriesTitle",id.optString("title","")));String path=new URI(url).getPath();boolean home=path==null||path.isEmpty()||path.equals("/")||path.equals("/home")||path.matches("/[a-z]{2}/?");if(kind.equals("home"))home=true;if(series.isEmpty()&&!home)return out;JSONObject canonical=kind.equals("home")?null:identity(id,url);if(canonical!=null)series=canonical.optString("series",series);
            try(Cursor c=getReadableDatabase().rawQuery("SELECT * FROM episodes WHERE "+(series.isEmpty()?"host<>? ":"series=? ")+"ORDER BY updated DESC LIMIT 100",series.isEmpty()?new String[]{host(url)}:new String[]{series})){while(c.moveToNext()){JSONObject entry=enrich(row(c));if(!series.isEmpty()&&id!=null&&(entry.optInt("season")!=(canonical==null?id.optInt("season",1):canonical.optInt("season",1))||entry.optInt("part")!=(canonical==null?id.optInt("part",0):canonical.optInt("part",0))))continue;try(Cursor x=getReadableDatabase().rawQuery("SELECT 1 FROM dismissed WHERE host=? AND k=? AND generation=?",new String[]{host(url),entry.getString("key"),""+entry.getLong("generation")})){if(x.moveToFirst())return out;}out.put("entry",entry);break;}}
        }catch(Exception e){}return out;
    }
    synchronized void dismiss(String url,String key,int task){JSONObject e=get(key);if(e==null||host(url).isEmpty())return;ContentValues v=new ContentValues();v.put("host",host(url));v.put("k",key);v.put("generation",e.optLong("generation"));getWritableDatabase().insertWithOnConflict("dismissed",null,v,SQLiteDatabase.CONFLICT_REPLACE);applied(task);}
    synchronized JSONObject begin(String url,String key,int task,boolean previous){
        JSONObject entry=get(key);if(entry==null||host(url).isEmpty())return null;try{String target=null;if(previous)target=entry.optString("url");else try(Cursor c=getReadableDatabase().rawQuery("SELECT url FROM locations WHERE k=? AND host=?",new String[]{key,host(url)})){if(c.moveToFirst())target=c.getString(0);}boolean exact=target!=null&&!host(target).isEmpty();if(!exact){URI u=new URI(url);String base=u.getScheme()+"://"+u.getRawAuthority(),q=URLEncoder.encode(entry.getString("title"),"UTF-8");if(host(url).endsWith("crunchyroll.com"))target=base+"/search?q="+q;else if(host(url).contains("hianime"))target=base+"/search?keyword="+q;else target="";}JSONObject p=new JSONObject().put("entry",entry).put("url",target).put("search",!exact).put("confirmed",exact).put("targetHost",previous?host(target):host(url)).put("host",previous?host(target):host(url)).put("applied",false).put("expires",System.currentTimeMillis()+TTL);save(task,p);return p;}catch(Exception e){return null;}
    }
    private void save(int task,JSONObject p){ContentValues v=new ContentValues();v.put("task",task);v.put("data",p.toString());v.put("expires",p.optLong("expires"));getWritableDatabase().insertWithOnConflict("pending",null,v,SQLiteDatabase.CONFLICT_REPLACE);}
    /** Called only after explicit confirmation of the target episode. */
    synchronized JSONObject confirm(String url,JSONObject i,int task){JSONObject p=pending(task),id=identity(i,url);if(p==null||id==null||!host(url).equals(p.optString("targetHost")))return null;try{JSONObject e=p.getJSONObject("entry");ContentValues v=new ContentValues();v.put("host",host(url));v.put("alias",titleKey(i.optString("seriesTitle",i.optString("title"))));v.put("season",i.optInt("season",1));v.put("part",i.optInt("part",0));v.put("canonical",e.getString("series"));v.put("target_season",e.getInt("season"));v.put("target_part",e.getInt("part"));v.put("offset",e.getInt("episode")-i.optInt("episode"));if(i.optBoolean("learnAlias",true))getWritableDatabase().insertWithOnConflict("aliases",null,v,SQLiteDatabase.CONFLICT_REPLACE);learn(e.getString("key"),url);metadata(e.getString("key"),url,i);p.put("url",url).put("confirmed",true).put("search",false);save(task,p);return p;}catch(Exception ex){return null;}}
    synchronized void markApplied(int task){JSONObject p=pending(task);if(p==null)return;try{p.put("applied",true);save(task,p);}catch(JSONException ignored){}}
    synchronized void applied(int task){getWritableDatabase().delete("pending","task=?",new String[]{""+task});}
    synchronized void forget(String key){JSONObject old=get(key);SQLiteDatabase d=getWritableDatabase();d.beginTransaction();try{d.delete("episodes","k=?",new String[]{key});d.delete("locations","k=?",new String[]{key});d.delete("metadata","k=?",new String[]{key});d.delete("dismissed","k=?",new String[]{key});if(old!=null)try(Cursor left=d.rawQuery("SELECT 1 FROM episodes WHERE series=? LIMIT 1",new String[]{old.optString("series")})){if(!left.moveToFirst())d.delete("aliases","canonical=?",new String[]{old.optString("series")});}try(Cursor c=d.rawQuery("SELECT task,data FROM pending",null)){while(c.moveToNext())if(new JSONObject(c.getString(1)).getJSONObject("entry").optString("key").equals(key))applied(c.getInt(0));}catch(Exception e){}d.setTransactionSuccessful();}finally{d.endTransaction();}}
    synchronized void clear(){SQLiteDatabase d=getWritableDatabase();d.beginTransaction();try{for(String table:new String[]{"episodes","locations","metadata","dismissed","pending","aliases"})d.delete(table,null,null);d.execSQL("INSERT OR REPLACE INTO meta VALUES('legacy-v1','1')");d.setTransactionSuccessful();}finally{d.endTransaction();}}
    synchronized JSONObject correct(String key,JSONObject correction){
        JSONObject old=get(key);if(old==null)return null;
        SQLiteDatabase d=getWritableDatabase();d.beginTransaction();
        try{
            JSONObject i=new JSONObject(old.toString()).put("title",old.getString("series"));
            for(String field:new String[]{"title","season","part","episode"})
                if(correction.has(field))i.put(field,correction.get(field));
            JSONObject progress=new JSONObject(old.toString()).put("qualified",true);
            if(correction.has("position")){
                double position=correction.optDouble("position",Double.NaN);
                if(!Double.isFinite(position)||position<0||position>86400)return null;
                progress.put("position",position);
            }
            JSONObject result=recordInternal(i,progress,true);if(result==null)return null;
            String next=result.getString("key");
            ContentValues preserved=new ContentValues();
            if(!correction.has("title"))preserved.put("title",old.optString("title"));
            if(old.optBoolean("completed"))preserved.put("completed",1);
            if(preserved.size()>0)d.update("episodes",preserved,"k=?",new String[]{next});
            if(!next.equals(key)){
                // Identity edits retain every explicitly learned site, not just the last URL.
                // Existing destination links take precedence when merging two records.
                d.execSQL("INSERT OR IGNORE INTO locations(k,host,url) SELECT ?,host,url FROM locations WHERE k=?",new Object[]{next,key});
                d.execSQL("INSERT OR IGNORE INTO metadata(k,host,series_id,episode_id) SELECT ?,host,series_id,episode_id FROM metadata WHERE k=?",new Object[]{next,key});
                forget(key);
            }
            result=get(next);if(result!=null)d.setTransactionSuccessful();return result;
        }catch(Exception e){return null;}finally{d.endTransaction();}
    }
    private JSONObject legacyIdentity(String title,int episode,String url)throws JSONException{
        int season=1,part=0;java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?i)\\bseason\\s*(\\d{1,2})\\b").matcher(title);if(m.find()){season=Integer.parseInt(m.group(1));title=m.replaceAll(" ");}
        m=java.util.regex.Pattern.compile("(?i)\\b(?:part|cour)\\s*(\\d{1,2})\\b").matcher(title);if(m.find()){part=Integer.parseInt(m.group(1));title=m.replaceAll(" ");}
        return new JSONObject().put("title",title.trim()).put("season",season).put("part",part).put("episode",episode).put("url",url);
    }
    private void importLegacy(String title,int episode,String url,double position,double duration,long updated,boolean dismissed)throws JSONException{
        JSONObject result=record(legacyIdentity(title,episode,url),new JSONObject().put("qualified",true).put("position",position).put("duration",duration).put("speed",1));if(result==null)return;
        ContentValues v=new ContentValues();v.put("updated",updated);getWritableDatabase().update("episodes",v,"k=?",new String[]{result.getString("key")});
        if(dismissed)for(String h:new String[]{"crunchyroll.com","hianime.to","hianimez.to","anikototv.to"})dismiss("https://"+h+"/",result.getString("key"),Integer.MIN_VALUE);
    }
    private void migrate(){
        if(migrating)return;SQLiteDatabase d=getWritableDatabase();try(Cursor c=d.rawQuery("SELECT 1 FROM meta WHERE k='legacy-v1'",null)){if(c.moveToFirst())return;}java.io.File file=context.getDatabasePath("anibrave-watch.db");if(!file.exists())return;migrating=true;d.beginTransaction();
        try(SQLiteDatabase legacy=SQLiteDatabase.openDatabase(file.getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY)){
            try(Cursor c=legacy.rawQuery("SELECT a.title,l.episode,l.url,l.watched_at,a.reminder_dismissed,a.last_episode FROM episode_log l JOIN anime a ON a.key=l.anime_key ORDER BY l.watched_at",null)){while(c.moveToNext())importLegacy(c.getString(0),c.getInt(1),c.getString(2),0,0,c.getLong(3),c.getInt(4)!=0&&c.getInt(1)==c.getInt(5));}
            try(Cursor c=legacy.rawQuery("SELECT a.title,s.last_episode,s.last_url,s.position_sec,s.duration_sec,s.updated_at,a.reminder_dismissed,a.last_episode FROM site_progress s JOIN anime a ON a.key=s.anime_key ORDER BY s.updated_at",null)){while(c.moveToNext())importLegacy(c.getString(0),c.getInt(1),c.getString(2),c.getInt(3),c.getInt(4),c.getLong(5),c.getInt(6)!=0&&c.getInt(1)==c.getInt(7));}
            d.execSQL("INSERT OR REPLACE INTO meta VALUES('legacy-v1','1')");d.setTransactionSuccessful();
        }catch(Exception e){/* Retriable; legacy data is never modified. */}finally{d.endTransaction();migrating=false;}
    }
}
