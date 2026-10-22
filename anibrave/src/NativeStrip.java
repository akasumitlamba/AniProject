package app.anibrave;

import android.app.Activity;
import android.os.Handler;
import android.view.View;
import java.lang.reflect.*;

/** Pinned Chromium capture-before-height-change protocol; UI thread only. */
final class NativeStrip {
    private static Object field(Object object,String name) throws Exception {return object.getClass().getField(name).get(object);}
    static Object layer(Object controller) throws Exception {
        if(controller==null)return null;
        Object owner=field(controller,"a");
        return owner==null?null:field(owner,"C");
    }
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
    // The pinned controller owns its capture callbacks and their lifetime. Do not
    // register a second callback or call d(height, ...) around its transition.
    static void settle(Activity activity) {}
    static void cancel(Object controller) {}
    static void immediate(Object controller,int height) throws Exception {
        requestNative(controller,height);
    }
    static void request(Activity activity,View control,Object controller,int height,Handler handler) throws Exception {
        requestNative(controller,height);
    }
    private static void requestNative(Object controller,int height) throws Exception {
        if(height!=desired(controller))throw new IllegalArgumentException("Strip height conflicts with native policy");
        // b(false) updates minimum height, captures once, then updates browser
        // controls, margins and compositor through Chromium's own transition.
        controller.getClass().getMethod("b",boolean.class).invoke(controller,false);
    }
    private NativeStrip(){}
}
