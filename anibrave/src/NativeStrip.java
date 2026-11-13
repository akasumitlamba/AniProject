package app.anibrave;

import android.app.Activity;
import android.os.Handler;
import android.view.View;
import java.lang.reflect.*;

/** Pinned Chromium capture-before-height-change protocol; UI thread only. */
final class NativeStrip {
    // Engine member names; tests substitute names that match their fakes.
    static String OWNER=EngineSymbols.CONTROLLER_OWNER;
    static String LAYER=EngineSymbols.OWNER_LAYER;
    static String FLAGS=EngineSymbols.LAYER_FLAGS;
    static String SET_VISIBLE=EngineSymbols.LAYER_SET_VISIBLE;
    static String HIDDEN=EngineSymbols.CONTROLLER_HIDDEN;
    static String RESPONSIVE=EngineSymbols.CONTROLLER_RESPONSIVE;
    static String WIDTH=EngineSymbols.CONTROLLER_WIDTH;
    static String THRESHOLD=EngineSymbols.CONTROLLER_THRESHOLD;
    static String HEIGHT=EngineSymbols.CONTROLLER_HEIGHT;
    static String EXTRA=EngineSymbols.CONTROLLER_EXTRA_HEIGHT;
    static String UPDATE=EngineSymbols.CONTROLLER_UPDATE;

    private static Object field(Object object,String name) throws Exception {return object.getClass().getField(name).get(object);}
    static Object layer(Object controller) throws Exception {
        if(controller==null)return null;
        Object owner=field(controller,OWNER);
        return owner==null?null:field(owner,LAYER);
    }
    static boolean layerHidden(Object layer) throws Exception {
        Object flags=((java.util.function.Supplier<?>)field(layer,FLAGS)).get();
        return (((Number)flags).intValue()&1)!=0;
    }
    static void layerVisible(Object layer,boolean visible) throws Exception {
        layer.getClass().getMethod(SET_VISIBLE,int.class,boolean.class).invoke(layer,1,visible);
    }
    static int desired(Object controller) throws Exception {
        boolean hidden=(Boolean)field(controller,HIDDEN),responsive=(Boolean)field(controller,RESPONSIVE);
        int width=((Number)field(controller,WIDTH)).intValue(),threshold=((Number)field(controller,THRESHOLD)).intValue();
        return !hidden&&(!responsive||width>=threshold)
            ?((Number)field(controller,HEIGHT)).intValue()+((Number)field(controller,EXTRA)).intValue():0;
    }
    static void showControls(Activity activity) {
        if(activity==null||activity.isFinishing()||activity.isDestroyed()||AniRuntime.videoFullscreen())return;
        try {
            int id=activity.getResources().getIdentifier("control_container","id",activity.getPackageName());
            View control=activity.findViewById(id);
            if(control==null)return;
            Object manager=field(field(control,EngineSymbols.CONTROL_COMPOSITOR),EngineSymbols.COMPOSITOR_CONTROLS_MANAGER);
            if(manager!=null)manager.getClass().getMethod(EngineSymbols.CONTROLS_MANAGER_SHOW).invoke(manager);
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
        controller.getClass().getMethod(UPDATE,boolean.class).invoke(controller,false);
    }
    private NativeStrip(){}
}
