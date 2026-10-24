package app.anibrave;

import android.app.Activity;
import android.os.Looper;
import android.graphics.Bitmap;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Version-pinned bridge to Brave 1.98.60's real tab models. UI thread only. */
final class NativeTabs {
    static final class Entry {
        final int id;
        final String title;
        final Bitmap favicon;
        final boolean incognito, selected;
        Entry(int id,String title,boolean incognito,boolean selected,Bitmap favicon) {
            this.id=id;this.title=title;this.favicon=favicon;this.incognito=incognito;this.selected=selected;
        }
    }
    static final class Snapshot {
        final List<Entry> tabs;
        final boolean incognito;
        Snapshot(List<Entry> tabs,boolean incognito) {
            this.tabs=Collections.unmodifiableList(tabs);this.incognito=incognito;
        }
    }
    static final class Failure extends Exception {
        Failure(Throwable cause) {super("Native browser tabs are unavailable",cause);}
        Failure(String message) {super(message);}
    }
    private final Activity activity;
    private final java.util.Map<Integer,Bitmap> icons=new java.util.HashMap<>();
    private boolean iconModel;
    NativeTabs(Activity activity) {this.activity=activity;}
    private void check() throws Failure {
        if(Looper.myLooper()!=Looper.getMainLooper())throw new Failure("Tabs must be accessed on the UI thread");
        if(activity.isFinishing()||activity.isDestroyed())throw new Failure("Browser window has closed");
        String name=activity.getClass().getName();
        if(name.endsWith("FullScreenCustomTabActivity")||activity.getIntent().getBooleanExtra("app.anibrave.immersive",false))
            throw new Failure("Tab controls are unavailable in shortcut windows");
    }
    private static Object call(Object target,String name,Class<?>[] types,Object...args) throws Exception {
        try{return target.getClass().getMethod(name,types).invoke(target,args);}
        catch(InvocationTargetException e){Throwable cause=e.getCause();if(cause instanceof Exception)throw (Exception)cause;throw e;}
    }
    private static Object call(Object target,String name) throws Exception {return call(target,name,new Class<?>[0]);}
    private Object selector() throws Exception {check();Object value=call(activity,"w4");if(value==null)throw new Failure("Tabs are still loading");return value;}
    private Object model(Object selector,boolean incognito) throws Exception {return call(selector,"k",new Class<?>[]{boolean.class},incognito);}
    Snapshot snapshot() throws Failure {
        try {
            Object selector=selector(),model=call(selector,"h");
            if(model==null)throw new Failure("Tabs are still loading");
            boolean incognito=(Boolean)call(model,"isIncognito");
            int count=((Number)call(model,"getCount")).intValue(),index=((Number)call(model,"index")).intValue();
            if(iconModel!=incognito){icons.clear();iconModel=incognito;}
            java.util.Set<Integer> live=new java.util.HashSet<>();
            ArrayList<Entry> entries=new ArrayList<>();
            for(int i=0;i<count;i++) {
                Object tab=call(model,"getTabAt",new Class<?>[]{int.class},i);
                if(tab==null)continue;
                int id=((Number)call(tab,"getId")).intValue();
                String title=(String)call(tab,"getTitle");
                Bitmap favicon=null;
                try {
                    Class<?> faviconType=Class.forName("org.chromium.chrome.browser.tab.TabFavicon");
                    // The horizontal strip normally attaches this native observer.
                    // Attach it for live tabs too when that strip is suppressed.
                    faviconType.getMethod("from",Class.forName("org.chromium.chrome.browser.tab.Tab")).invoke(null,tab);
                    favicon=(Bitmap)faviconType
                        .getMethod("getBitmapWithFallback",Class.forName("org.chromium.chrome.browser.tab.Tab"),boolean.class)
                        .invoke(null,tab,true);
                    if(favicon!=null&&favicon.isRecycled())favicon=null;
                }catch(Exception ignored){} // Missing icons must never disable native tab controls.
                // Native favicon owners may recycle a bitmap between either call.
                // Keep an owned immutable copy; isolate both comparison and copying.
                try {
                    Bitmap previous=icons.get(id);
                    if(favicon!=null)favicon=previous!=null&&!previous.isRecycled()&&favicon.sameAs(previous)
                        ?previous:favicon.copy(Bitmap.Config.ARGB_8888,false);
                }catch(RuntimeException ignored){favicon=null;}
                if(favicon==null)icons.remove(id);else icons.put(id,favicon);
                live.add(id);
                entries.add(new Entry(id,title==null||title.trim().isEmpty()?"New tab":title,incognito,i==index,favicon));
            }
            icons.keySet().retainAll(live);
            return new Snapshot(entries,incognito);
        }catch(Exception e){throw e instanceof Failure?(Failure)e:new Failure(e);}
    }
    private Object resolve(Object model,int id) throws Exception {
        return call(model,"getTabById",new Class<?>[]{int.class},id);
    }
    /** Re-resolve IDs after every click; row indexes may change while a menu is open. */
    boolean selectById(int id,boolean incognito) throws Failure {
        try {
            Object selector=selector();
            if((Boolean)call(call(selector,"h"),"isIncognito")!=incognito)return false;
            Object model=model(selector,incognito),tab=resolve(model,id);
            if(tab==null)return false;
            call(selector,"F",new Class<?>[]{boolean.class},incognito);
            int count=((Number)call(model,"getCount")).intValue();
            for(int i=0;i<count;i++) {
                Object candidate=call(model,"getTabAt",new Class<?>[]{int.class},i);
                if(candidate!=null&&((Number)call(candidate,"getId")).intValue()==id) {
                    // The private model is a lazy wrapper, not a TabModelJniBridge.
                    // Both expose the pinned TabModel selection API.
                    call(model,"A0",new Class<?>[]{int.class,int.class},i,3);return true;
                }
            }
            return false;
        }catch(Exception e){throw e instanceof Failure?(Failure)e:new Failure(e);}
    }
    boolean closeById(int id,boolean incognito) throws Failure {
        try {
            Object selector=selector();
            if((Boolean)call(call(selector,"h"),"isIncognito")!=incognito)return false;
            Object model=model(selector,incognito),tab=resolve(model,id);
            if(tab==null)return false;
            Class<?> tabType=Class.forName("org.chromium.chrome.browser.tab.Tab");
            // Use the same removal pipeline as the tab UI: preserves undo and
            // native shared-group confirmation, unlike JNI closeTab().
            Object builder=Class.forName("fdj").getMethod("b",tabType).invoke(null,tab);
            Object params=call(builder,"a"),remover=call(model,"q1");
            Class.forName("c2k").getMethod("a",Class.forName("fdj")).invoke(remover,params);
            return true;
        }catch(Exception e){throw e instanceof Failure?(Failure)e:new Failure(e);}
    }
    void newTab(boolean incognito) throws Failure {
        try {
            Object selector=selector();
            Class<?> paramsType=Class.forName("org.chromium.content_public.browser.LoadUrlParams");
            Object params=paramsType.getConstructor(int.class,String.class).newInstance(0,"about:blank");
            Class<?> tabType=Class.forName("org.chromium.chrome.browser.tab.Tab");
            Object created=call(selector,"B",new Class<?>[]{paramsType,int.class,tabType,boolean.class},params,2,null,incognito);
            if(created==null)throw new Failure("A new tab could not be opened");
            call(selector,"F",new Class<?>[]{boolean.class},incognito);
            selectById(((Number)call(created,"getId")).intValue(),incognito);
        }catch(Exception e){throw e instanceof Failure?(Failure)e:new Failure(e);}
    }
    void switchModel(boolean incognito) throws Failure {
        try {
            Object selector=selector(),target=model(selector,incognito);
            if(((Number)call(target,"getCount")).intValue()==0){newTab(incognito);return;}
            call(selector,"F",new Class<?>[]{boolean.class},incognito);
        }
        catch(Exception e){throw e instanceof Failure?(Failure)e:new Failure(e);}
    }
}
