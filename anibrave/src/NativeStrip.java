package app.anibrave;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Handler;
import android.view.View;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.WeakHashMap;

/** Pinned Chromium capture-before-height-change protocol; UI thread only. */
final class NativeStrip {
    private static final WeakHashMap<Object,Pending> pending=new WeakHashMap<>();
    private static Object field(Object object,String name) throws Exception {return object.getClass().getField(name).get(object);}
    static int desired(Object controller) throws Exception {
        boolean hidden=(Boolean)field(controller,"p"),responsive=(Boolean)field(controller,"u");
        int width=((Number)field(controller,"q")).intValue(),threshold=((Number)field(controller,"n")).intValue();
        return !hidden&&(!responsive||width>=threshold)
            ?((Number)field(controller,"e")).intValue()+((Number)field(controller,"r")).intValue():0;
    }
    static void showControls(Activity activity) {
        if(activity==null||activity.isFinishing()||activity.isDestroyed()||AniRuntime.videoFullscreen())return;
        try {
            int id=activity.getResources().getIdentifier("control_container","id",activity.getPackageName());
            View control=activity.findViewById(id);
            if(control==null)return;
            Object manager=field(field(control,"z"),"O");
            if(manager!=null)manager.getClass().getMethod("F").invoke(manager);
        }catch(Exception ignored){}
    }
    static void settle(Activity activity) {
        for(Pending value:new java.util.ArrayList<>(pending.values()))
            if(value.activity.get()==activity)value.finish();
    }
    static void cancel(Object controller) {Pending old=pending.remove(controller);if(old!=null)old.dispose();}
    static void immediate(Object controller,int height) throws Exception {
        cancel(controller);controller.getClass().getMethod("d",int.class,boolean.class).invoke(controller,height,true);
    }
    static void request(Activity activity,View control,Object controller,int height,Handler handler) throws Exception {
        Pending previous=pending.get(controller);
        if(previous!=null&&previous.height==height)return;
        cancel(controller);
        Object resource=field(field(control,"A"),"S");
        if(resource==null){immediate(controller,height);return;}
        Pending next=new Pending(activity,controller,resource,height,handler);
        pending.put(controller,next);next.start();
    }
    private static final class Pending {
        final WeakReference<Activity> activity;
        final WeakReference<Object> controller;
        final Object resource;
        final int height;
        final Handler handler;
        final Class<?> callbackType;
        final Object callback;
        final Runnable begin=this::capture,timeout=this::finish;
        boolean disposed;
        Pending(Activity a,Object c,Object r,int h,Handler ui) throws Exception {
            activity=new WeakReference<>(a);controller=new WeakReference<>(c);resource=r;height=h;handler=ui;
            callbackType=Class.forName("org.chromium.base.Callback");
            callback=Proxy.newProxyInstance(callbackType.getClassLoader(),new Class<?>[]{callbackType},(proxy,method,args)->{
                if(method.getName().equals("onResult")){finish();return null;}
                if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
                if(method.getName().equals("equals"))return args!=null&&args.length==1&&proxy==args[0];
                if(method.getName().equals("toString"))return "AniBrave toolbar capture";
                return null;
            });
        }
        void start() throws Exception {
            resource.getClass().getMethod("a",callbackType).invoke(resource,callback);
            // Let the content width/layout settle before capturing the toolbar.
            handler.postDelayed(begin,32);handler.postDelayed(timeout,500);
        }
        void capture() {
            if(disposed)return;
            try {
                resource.getClass().getMethod("g",Rect.class).invoke(resource,(Object)null);
                resource.getClass().getMethod("i").invoke(resource);
            }catch(Exception e){finish();}
        }
        void finish() {
            if(disposed)return;
            Object c=controller.get();Activity a=activity.get();
            boolean current=c!=null&&pending.get(c)==this;
            if(current)pending.remove(c);
            dispose();
            if(current&&a!=null&&!a.isFinishing()&&!a.isDestroyed())try {
                c.getClass().getMethod("d",int.class,boolean.class).invoke(c,height,true);
                showControls(a);
            }catch(Exception ignored){}
        }
        void dispose() {
            if(disposed)return;disposed=true;handler.removeCallbacks(begin);handler.removeCallbacks(timeout);
            try{resource.getClass().getMethod("c",callbackType).invoke(resource,callback);}catch(Exception ignored){}
        }
    }
    private NativeStrip(){}
}
