package app.anibrave;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.net.*;
import java.io.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import java.util.WeakHashMap;

/** Lifecycle-bound native UI; Chromium and the website retain ownership of playback. */
public final class AniRuntime {
    private static final Handler UI=new Handler(Looper.getMainLooper());
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private static WeakReference<Activity> active=new WeakReference<>(null);
    private static Context context;
    private static AnimeHistory history;
    private static LinearLayout watchReminder;
    private static String reminderKey="",reminderUrl="";
    private static int reminderEpisode;
    private static final ReminderPresentation reminderPresentation=new ReminderPresentation();
    private static final Runnable AUTO_HIDE_REMINDER=()->removeWatchReminder();
    private static final Runnable CHECK_REMINDER=new Runnable(){public void run(){captureLocation(active.get());String url=currentUrl();reminderPresentation.enter(url);if(watchReminder!=null&&!url.isEmpty()&&!WatchUrl.same(reminderUrl,url))removeWatchReminder();if(alive(active.get()))UI.postDelayed(this,2000);}};
    private static EngineClient engine;
    private static Object server;
    private static long serverPointer;
    private static boolean registered, immersive;
    private static final WeakHashMap<Activity,String> lastLocations=new WeakHashMap<>();
    private static final WeakHashMap<Activity,Boolean> fullscreenActivities=new WeakHashMap<>();
    private static final WeakHashMap<Activity,Integer> normalLayouts=new WeakHashMap<>();
    private static final WeakHashMap<Activity,Boolean> normalDecorFits=new WeakHashMap<>();
    private static ViewTreeObserver.OnGlobalLayoutListener windowListener;
    private static ViewTreeObserver.OnWindowFocusChangeListener focusListener;
    private static View windowDecor;
    private static LinearLayout shortcutTools;
    private static LinearLayout shortcutActions;
    private static boolean expanded;
    private static int savedNavigationColor, savedStatusColor;
    private static boolean savedNavigationContrast, savedStatusContrast, windowAdjusted;
    private static PopupWindow tools;
    private static View featureButton;
    private static ViewTreeObserver.OnGlobalLayoutListener toolbarListener;
    private static View toolbarDecor;
    private static VerticalTabs verticalTabs;
    private static String status="Connecting to the browser…";
    private static final double[] RATES={0.5,0.75,1,1.25,1.5,1.75,2,2.5,3,4};
    private static final Runnable HIDE=()->{if(tools!=null)tools.dismiss();collapseShortcutTools();if(shortcutTools!=null)shortcutTools.setVisibility(View.GONE);};

    private AniRuntime() {}
    public static String blankNewTab(String url) {return SiteKey.blankNewTab(url);}
    private static boolean alive(Activity a) {return a!=null&&!a.isFinishing()&&!a.isDestroyed();}
    public static boolean controlsStarted(){return serverPointer!=0;}
    private static void startControls(){
        if(controlsStarted())return;
        try{
            // Shortcut-only startup does not register main-browser preference
            // observers. Create the same native server without those observers.
            serverPointer=((Number)Class.forName("J.N").getMethod("JO",int.class,Object.class).invoke(null,4,"anibrave")).longValue();
            toggleServer(true);
        }
        catch(Exception e){status="Page controls could not start";android.util.Log.w("AniBrave","Control startup failed",e);}
    }
    /** P3 runs only after Chromium finishes native startup and loads its profile. */
    public static void shortcutReady(Activity a){
        UI.post(()->{if(alive(a)&&active.get()==a)startControls();});
    }
    public static void server(Object value,long pointer) {
        server=value;serverPointer=pointer;toggleServer(alive(active.get()));
    }
    private static void toggleServer(boolean enabled) {
        if(serverPointer==0)return;
        try { Class.forName("J.N").getMethod("VJZZ",int.class,long.class,boolean.class,boolean.class).invoke(null,0,serverPointer,enabled,true); }
        catch(Exception e){status="Browser controls could not connect";}
    }
    public static void intent(Activity a,Intent intent) {
        a.setIntent(intent);
        immersive=intent.getBooleanExtra("app.anibrave.immersive",false);
        if(alive(a))UI.post(()->{if(alive(a))applyImmersive(a);});
    }
    public static void resume(Activity a) {
        if(!alive(a))return;
        if(context==null){context=a.getApplicationContext();history=new AnimeHistory(context);}
        if(!registered) {
            registered=true;
            ((Application)context).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks(){
                public void onActivityCreated(Activity a,Bundle b){}
                public void onActivityStarted(Activity a){}
                public void onActivityResumed(Activity a){String name=a.getClass().getName();if(name.equals("org.chromium.chrome.browser.ChromeTabbedActivity")||name.equals("org.chromium.chrome.browser.customtabs.FullScreenCustomTabActivity"))resume(a);}
                public void onActivityPaused(Activity a){if(active.get()==a)pause();}
                public void onActivityStopped(Activity a){}
                public void onActivitySaveInstanceState(Activity a,Bundle b){}
                public void onActivityDestroyed(Activity a){if(active.get()==a)pause();}
            });
        }
        if(active.get()!=a){dismiss();active=new WeakReference<>(a);immersive=a.getClass().getName().endsWith("FullScreenCustomTabActivity")||a.getIntent().getBooleanExtra("app.anibrave.immersive",false);}
        toggleServer(true);
        if(engine==null) {
            try{engine=new EngineClient(context);Thread thread=new Thread(engine,"AniBrave-page-controls");thread.setDaemon(true);thread.start();}
            catch(Exception e){status="Page controls could not start";}
        }
        UI.removeCallbacks(CHECK_REMINDER);UI.post(CHECK_REMINDER);
        UI.post(()->{if(alive(a)&&active.get()==a){bindWindow(a);applyImmersive(a);bindToolbar(a);bindTabLayout(a);}});
    }
    private static void pause() {
        captureLocation(active.get());
        if(engine!=null){engine.stop();engine=null;}
        UI.removeCallbacks(CHECK_REMINDER);toggleServer(false);dismiss();active=new WeakReference<>(null);
    }
    private static void dismiss(){
        ContinuationController.pause();
        NativeStrip.settle(active.get());
        if(verticalTabs!=null){verticalTabs.close();verticalTabs=null;}
        UI.removeCallbacks(HIDE);
        removeWatchReminder();
        if(tools!=null){tools.dismiss();tools=null;}
        if(shortcutTools!=null&&shortcutTools.getParent() instanceof ViewGroup)((ViewGroup)shortcutTools.getParent()).removeView(shortcutTools);
        shortcutTools=null;shortcutActions=null;expanded=false;
        if(windowDecor!=null){
            ViewTreeObserver observer=windowDecor.getViewTreeObserver();
            if(observer.isAlive()){if(windowListener!=null)observer.removeOnGlobalLayoutListener(windowListener);if(focusListener!=null)observer.removeOnWindowFocusChangeListener(focusListener);}
            windowDecor.setOnApplyWindowInsetsListener(null);
        }
        windowDecor=null;windowListener=null;focusListener=null;windowAdjusted=false;
        if(toolbarDecor!=null&&toolbarListener!=null&&toolbarDecor.getViewTreeObserver().isAlive())toolbarDecor.getViewTreeObserver().removeOnGlobalLayoutListener(toolbarListener);
        toolbarDecor=null;toolbarListener=null;featureButton=null;
    }
    static boolean videoFullscreen(){return Boolean.TRUE.equals(fullscreenActivities.get(active.get()));}
    private static boolean isFullscreen(Activity a){return immersive||Boolean.TRUE.equals(fullscreenActivities.get(a));}
    private static void bindWindow(Activity a){
        if(windowDecor!=null)return;
        windowDecor=a.getWindow().getDecorView();
        if(!isFullscreen(a))rememberNormalWindow(a);
        // Keep IME insets intact. Remove only the stable system-bar reservation in
        // fullscreen; hiding icons alone leaves Android 10's navigation gutter.
        windowDecor.setOnApplyWindowInsetsListener((v,insets)->{
            if(active.get()==a&&isFullscreen(a)){
                int stableBottom=insets.getStableInsetBottom();
                boolean keyboard=insets.getSystemWindowInsetBottom()>stableBottom;
                insets=new WindowInsets.Builder(insets)
                    .setSystemWindowInsets(Insets.of(0,0,0,keyboard?insets.getSystemWindowInsetBottom():0))
                    .setStableInsets(Insets.NONE).build();
            }
            return v.onApplyWindowInsets(insets);
        });
        windowListener=()->{if(alive(a)&&active.get()==a){if(!isFullscreen(a)&&!windowAdjusted)rememberNormalWindow(a);applyImmersive(a);positionShortcutTools(a);}};
        focusListener=focused->{if(focused&&alive(a)&&active.get()==a)applyImmersive(a);};
        windowDecor.getViewTreeObserver().addOnGlobalLayoutListener(windowListener);
        windowDecor.getViewTreeObserver().addOnWindowFocusChangeListener(focusListener);
    }
    private static void rememberNormalWindow(Activity a){
        normalLayouts.put(a,a.getWindow().getDecorView().getSystemUiVisibility());
        View content=a.findViewById(android.R.id.content);
        if(content!=null&&content.isLaidOut()){
            int[] position=new int[2];content.getLocationInWindow(position);
            normalDecorFits.put(a,position[1]>0);
        }
    }
    private static void applyImmersive(Activity a) {
        if(!isFullscreen(a))return;
        Window window=a.getWindow();View decor=window.getDecorView();
        if(!windowAdjusted){
            savedNavigationColor=window.getNavigationBarColor();savedStatusColor=window.getStatusBarColor();
            savedNavigationContrast=window.isNavigationBarContrastEnforced();savedStatusContrast=window.isStatusBarContrastEnforced();windowAdjusted=true;
        }
        window.setNavigationBarColor(Color.TRANSPARENT);window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarContrastEnforced(false);window.setStatusBarContrastEnforced(false);
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION|WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        if(Build.VERSION.SDK_INT>=30){
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller=window.getInsetsController();
            if(controller!=null){controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);controller.hide(WindowInsets.Type.systemBars());}
        }
        int flags=View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        if(decor.getSystemUiVisibility()!=flags)decor.setSystemUiVisibility(flags);
        if((window.getAttributes().flags&WindowManager.LayoutParams.FLAG_FULLSCREEN)==0)window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }
    /** Called after Chromium's own enter/exit methods, including iframe video. */
    public static void nativeFullscreen(View view,boolean enabled){
        Activity a=active.get();if(!alive(a)||view.getRootView()!=a.getWindow().getDecorView())return;
        fullscreenActivities.put(a,enabled);
        UI.post(()->{
            if(!alive(a)||active.get()!=a)return;
            if(enabled){collapseShortcutTools();applyImmersive(a);}else if(!immersive){
                if(windowAdjusted){a.getWindow().setNavigationBarColor(savedNavigationColor);a.getWindow().setStatusBarColor(savedStatusColor);a.getWindow().setNavigationBarContrastEnforced(savedNavigationContrast);a.getWindow().setStatusBarContrastEnforced(savedStatusContrast);windowAdjusted=false;}
                a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                // A late fullscreen layout pass can overwrite Chromium's exit
                // flags. Restore the pre-fullscreen layout, not only bar icons.
                a.getWindow().getDecorView().setSystemUiVisibility(normalLayouts.getOrDefault(a,View.SYSTEM_UI_FLAG_LAYOUT_STABLE));
                if(Build.VERSION.SDK_INT>=30)a.getWindow().setDecorFitsSystemWindows(normalDecorFits.getOrDefault(a,false));
            }else applyImmersive(a);
            a.getWindow().getDecorView().requestApplyInsets();
            EngineClient e=engine;if(e!=null)IO.execute(()->e.viewportChanged(enabled));
        });
    }
    static android.util.DisplayMetrics viewportMetrics(){
        android.util.DisplayMetrics dm=new android.util.DisplayMetrics();dm.setTo(context.getResources().getDisplayMetrics());
        Activity a=active.get();
        if(alive(a)){
            View page=a.findViewById(android.R.id.content);
            if(page!=null&&page.getWidth()>0&&page.getHeight()>0){
                dm.widthPixels=Math.max(1,page.getWidth()-page.getPaddingLeft()-page.getPaddingRight());
                dm.heightPixels=Math.max(1,page.getHeight()-page.getPaddingTop()-page.getPaddingBottom());
            }
        }
        return dm;
    }
    static void pageBoundsChanged(){
        EngineClient e=engine;if(e!=null)IO.execute(()->e.viewportChanged(videoFullscreen()));
    }
    static String currentUrl() {
        Activity a=active.get();if(!alive(a))return "";
        try {
            Object tab=a.getClass().getMethod("n4").invoke(a);if(tab==null)return "";
            Object url=tab.getClass().getMethod("getUrl").invoke(tab);
            return (String)url.getClass().getField("a").get(url);
        }catch(Exception e){return "";}
    }
    private static boolean normalProfile(Activity a){
        try {Object tab=a.getClass().getMethod("n4").invoke(a);return tab!=null&&!((Boolean)tab.getClass().getMethod("isIncognito").invoke(tab));}
        catch(Exception e){return false;} // Unknown profile must never persist browsing URLs.
    }
    private static void captureLocation(Activity a){
        if(context==null||a==null||a.isDestroyed()||!normalProfile(a))return;
        String url=currentUrlFor(a);
        // Returning to an unchanged older window must not overwrite a newer visit.
        if(!ResumeLocation.valid(url))return;
        lastLocations.put(a,url);
        ResumeStore.capture(context,a.getTaskId(),url);
    }
    private static String currentUrlFor(Activity a){
        try{Object tab=a.getClass().getMethod("n4").invoke(a);Object url=tab.getClass().getMethod("getUrl").invoke(tab);return (String)url.getClass().getField("a").get(url);}catch(Exception e){return "";}
    }
    static String taskUrl(int taskId){
        for(Activity a:lastLocations.keySet())if(a!=null&&!a.isDestroyed()&&a.getTaskId()==taskId)return currentUrlFor(a);
        return "";
    }
    static void navigated(String url){UI.post(()->{
        Activity a=active.get();if(a!=null&&url.equals(currentUrlFor(a)))captureLocation(a);
    });}
    static void nativeRate(String url,double rate,long revision){UI.post(()->{
        Activity a=active.get();if(!alive(a)||!SiteKey.of(url).equals(SiteKey.of(currentUrl())))return;
        String site=SiteKey.of(url);
        PlaybackSettings.adopt(context,site,rate,revision);
        EngineClient e=engine;if(e!=null)IO.execute(()->e.refresh(site,false));
    });}
    private static int dp(Activity a,int value){return Math.round(value*a.getResources().getDisplayMetrics().density);}
    private static Button action(Activity a,String text,Runnable click){
        Button result=new Button(a);result.setText(text);result.setTextColor(Color.WHITE);result.setTextSize(13);result.setAllCaps(false);result.setMinWidth(0);result.setMinimumWidth(0);
        GradientDrawable bg=new GradientDrawable();bg.setColor(0xff171717);bg.setCornerRadius(dp(a,14));result.setBackground(bg);
        result.setPadding(dp(a,12),dp(a,7),dp(a,12),dp(a,7));result.setContentDescription(text);result.setOnClickListener(v->click.run());return result;
    }
    private static void bindToolbar(Activity a){
        if(immersive)return;
        if(toolbarListener!=null)return;
        toolbarDecor=a.getWindow().getDecorView();
        toolbarListener=()->{
            if(!alive(a)||active.get()!=a)return;
            int id=a.getResources().getIdentifier("anibrave_features","id",a.getPackageName());
            View icon=toolbarDecor.findViewById(id);
            if(icon!=null&&icon!=featureButton){featureButton=icon;icon.setOnClickListener(v->showTools(a));}
        };
        toolbarDecor.getViewTreeObserver().addOnGlobalLayoutListener(toolbarListener);
        toolbarListener.onGlobalLayout();
    }
    private static void bindTabLayout(Activity a){
        if(!immersive&&verticalTabs==null&&VerticalTabs.enabled(a))verticalTabs=new VerticalTabs(a);
    }
    static void tabLayoutChanged(Activity a){
        if(active.get()!=a)return;
        if(verticalTabs!=null){verticalTabs.close();verticalTabs=null;}
        if(!VerticalTabs.enabled(a)){
            // Recreate the native window when restoring its horizontal strip.
            // This avoids Chromium retaining an intermediate toolbar snapshot.
            a.recreate();return;
        }
        bindTabLayout(a);
    }
    private static void showTools(Activity a){showTools(a,featureButton);}
    static void showTools(Activity a,View anchor){
        if(!alive(a)||active.get()!=a)return;
        if(!immersive){
            if(anchor==null)return;
            PopupMenu menu=new PopupMenu(a,anchor);
            menu.getMenu().add(0,1,0,"Playback speed");menu.getMenu().add(0,2,1,"Site view");menu.getMenu().add(0,3,2,"Fullscreen shortcut");menu.getMenu().add(0,4,3,"Watched anime");menu.getMenu().add(0,6,4,"Streaming buffer");
            menu.getMenu().add(0,5,4,"Tab layout settings");
            menu.setOnMenuItemClickListener(item->{if(item.getItemId()==5)VerticalTabs.settings(a,()->tabLayoutChanged(a));else if(item.getItemId()==1)speed(a);else if(item.getItemId()==2)view(a);else if(item.getItemId()==4)library(a);else if(item.getItemId()==6)buffering(a);else shortcut(a);return true;});menu.show();return;
        }
        revealShortcutTools(a);
    }
    private static void ensureShortcutTools(Activity a){
        if(shortcutTools!=null)return;
        FrameLayout decor=(FrameLayout)a.getWindow().getDecorView();
        shortcutTools=new LinearLayout(a);shortcutTools.setOrientation(LinearLayout.VERTICAL);shortcutTools.setGravity(Gravity.RIGHT);
        shortcutActions=new LinearLayout(a);shortcutActions.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable panel=new GradientDrawable();panel.setColor(0xf2222228);panel.setCornerRadius(dp(a,16));
        shortcutActions.setBackground(panel);shortcutActions.setElevation(dp(a,8));shortcutActions.setPadding(dp(a,8),dp(a,8),dp(a,8),dp(a,8));
        String[] labels={"Playback speed","Streaming buffer","Site view","Watched anime"};
        Runnable[] clicks={()->speed(a),()->buffering(a),()->view(a),()->library(a)};
        for(int i=0;i<labels.length;i++){
            Button button=action(a,labels[i],clicks[i]);button.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);
            shortcutActions.addView(button,new LinearLayout.LayoutParams(dp(a,200),dp(a,48)));
        }
        shortcutActions.setVisibility(View.GONE);shortcutTools.addView(shortcutActions);
        ImageButton button=new ImageButton(a);int icon=a.getResources().getIdentifier("anibrave_features","drawable",a.getPackageName());button.setImageResource(icon);
        button.setColorFilter(Color.WHITE);button.setContentDescription("Site options");button.setPadding(dp(a,14),dp(a,14),dp(a,14),dp(a,14));
        GradientDrawable circle=new GradientDrawable();circle.setColor(0xff303039);circle.setShape(GradientDrawable.OVAL);button.setBackground(circle);button.setElevation(dp(a,8));
        button.setOnClickListener(v->{expanded=!expanded;shortcutActions.setVisibility(expanded?View.VISIBLE:View.GONE);UI.removeCallbacks(HIDE);if(!expanded)UI.postDelayed(HIDE,6000);});
        LinearLayout.LayoutParams b=new LinearLayout.LayoutParams(dp(a,52),dp(a,52));b.topMargin=dp(a,8);b.gravity=Gravity.RIGHT;shortcutTools.addView(button,b);
        FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(dp(a,216),-2,Gravity.BOTTOM|Gravity.RIGHT);params.rightMargin=dp(a,16);params.bottomMargin=dp(a,16);
        decor.addView(shortcutTools,params);shortcutTools.setVisibility(View.GONE);
    }
    private static void positionShortcutTools(Activity a){
        if(shortcutTools==null)return;
        WindowInsets insets=a.getWindow().getDecorView().getRootWindowInsets();int right=0,bottom=0;
        if(insets!=null){
            right=insets.getSystemWindowInsetRight();bottom=insets.getSystemWindowInsetBottom();
            boolean hidden=(a.getWindow().getDecorView().getSystemUiVisibility()&View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)!=0;
            if(hidden){right=0;if(bottom<=insets.getStableInsetBottom())bottom=0;}
        }
        FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)shortcutTools.getLayoutParams();
        int r=dp(a,16)+right,b=dp(a,16)+bottom;
        if(p.rightMargin!=r||p.bottomMargin!=b){p.rightMargin=r;p.bottomMargin=b;shortcutTools.setLayoutParams(p);}
    }
    private static void collapseShortcutTools(){expanded=false;if(shortcutActions!=null)shortcutActions.setVisibility(View.GONE);}
    private static void revealShortcutTools(Activity a){
        ensureShortcutTools(a);positionShortcutTools(a);shortcutTools.setVisibility(View.VISIBLE);
        UI.removeCallbacks(HIDE);if(!expanded)UI.postDelayed(HIDE,6000);
    }
    private static String requireSite(Activity a){String site=SiteKey.of(currentUrl());if(site.isEmpty())Toast.makeText(a,"Open a website first",Toast.LENGTH_SHORT).show();return site;}
    private static void speed(Activity a){
        String site=requireSite(a);if(site.isEmpty())return;HIDE.run();int selected=2;
        float saved=context.getSharedPreferences("anibrave_sites",0).getFloat("rate:"+site,1);
        java.util.ArrayList<Double> values=new java.util.ArrayList<>();for(double r:RATES)values.add(r);
        if(PlaybackSettings.valid(saved)&&values.stream().noneMatch(r->Math.abs(r-saved)<.001)){values.add((double)saved);java.util.Collections.sort(values);}
        String[] labels=new String[values.size()];
        for(int i=0;i<values.size();i++){labels[i]=String.format(java.util.Locale.ROOT,"%.2f",values.get(i)).replaceAll("0+$", "").replaceAll("\\.$", "")+"×";if(Math.abs(saved-values.get(i))<.001)selected=i;}
        new AlertDialog.Builder(a,android.R.style.Theme_Material_Dialog_Alert).setTitle("Playback speed · "+site).setSingleChoiceItems(labels,selected,(dialog,index)->{
            PlaybackSettings.set(context,site,values.get(index));
            EngineClient e=engine;if(e!=null)IO.execute(()->e.refresh(site,false));dialog.dismiss();
        }).setNegativeButton("Close",null).setNeutralButton("Status",(d,w)->Toast.makeText(a,status,Toast.LENGTH_LONG).show()).show();
    }
    private static void buffering(Activity a){
        HIDE.run();android.content.SharedPreferences prefs=a.getSharedPreferences("anibrave_ui",0);
        int[] values={0,60,180,300,600};String[] labels={"Site default","1 minute ahead","3 minutes ahead","5 minutes ahead","10 minutes ahead"};
        int selected=2;for(int i=0;i<values.length;i++)if(values[i]==prefs.getInt("buffer_seconds",180))selected=i;
        LinearLayout body=new LinearLayout(a);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(a,24),dp(a,12),dp(a,24),0);
        TextView note=new TextView(a);note.setText("Applies to all sites. Requests a larger buffer where the player supports it; site and memory limits still apply.");body.addView(note);
        CheckBox guard=new CheckBox(a);guard.setText("Buffer guard: wait for a cushion after a stall");guard.setChecked(prefs.getBoolean("buffer_guard",true));
        guard.setOnCheckedChangeListener((button,checked)->{prefs.edit().putBoolean("buffer_guard",checked).apply();EngineClient e=engine;if(e!=null)IO.execute(()->e.refresh(null,false));});body.addView(guard);
        new AlertDialog.Builder(a,android.R.style.Theme_Material_Dialog_Alert).setTitle("Streaming buffer · all sites").setView(body)
            .setSingleChoiceItems(labels,selected,(d,index)->{prefs.edit().putInt("buffer_seconds",values[index]).apply();EngineClient e=engine;if(e!=null)IO.execute(()->e.refresh(null,false));})
            .setPositiveButton("Done",null).show();
    }
    private static void view(Activity a){
        String site=requireSite(a);if(site.isEmpty())return;HIDE.run();String[] labels={"Mobile","Desktop identity · mobile layout","Desktop"};
        new AlertDialog.Builder(a,android.R.style.Theme_Material_Dialog_Alert).setTitle("Site view · "+site).setSingleChoiceItems(labels,context.getSharedPreferences("anibrave_sites",0).getInt("mode:"+site,0),(d,index)->{
            context.getSharedPreferences("anibrave_sites",0).edit().putInt("mode:"+site,index).apply();EngineClient e=engine;if(e!=null)IO.execute(()->e.refresh(site,true));d.dismiss();
        }).setNegativeButton("Close",null).show();
    }
    private static void shortcut(Activity a){if(requireSite(a).isEmpty())return;HIDE.run();String url=currentUrl();EngineClient e=engine;if(e!=null)IO.execute(()->e.icon(url));else pin(url,"","");}
    private static void removeWatchReminder(){
        UI.removeCallbacks(AUTO_HIDE_REMINDER);
        if(watchReminder!=null&&watchReminder.getParent() instanceof ViewGroup)((ViewGroup)watchReminder.getParent()).removeView(watchReminder);
        watchReminder=null;reminderKey="";reminderUrl="";reminderEpisode=0;
    }
    static Activity continuationActivity(){Activity a=active.get();if(!alive(a)||!normalProfile(a))return null;
        for(String name:new String[]{"incognito_reauth_unlock_incognito_button","incognito_reauth_unlock_incognito_icon","incognito_reauth_see_other_tabs_label","tab_list_recycler_view","tab_grid_dialog_recycler_view_container"}){int id=a.getResources().getIdentifier(name,"id",a.getPackageName());View view=id==0?null:a.findViewById(id);if(view!=null&&view.isShown()&&view.getWidth()>0&&view.getHeight()>0)return null;}return a;}
    static void watchPage(String url,JSONObject page){} // ContinuationController owns reminders.
    static void watched(String url,JSONObject page,int position,int duration){
        UI.post(()->{
            if(!WatchUrl.same(url,currentUrl())||duration<60||!normalProfile(active.get()))return;
            String base=page.optString("seriesTitle"),title=base.isEmpty()?page.optString("title"):base+(page.optInt("season",1)>1?" Season "+page.optInt("season"):"")+(page.optInt("part")>0?" Part "+page.optInt("part"):"");String host=SiteKey.of(url);int episode=page.optInt("episode");
            host=SiteKey.watchHost(url);if(host.isEmpty())return;
            final String watchHost=host;
            IO.execute(()->{AnimeHistory.Summary summary=history.record(title,episode,watchHost,url,position,duration);});
        });
    }
    private static void displayReminder(String url,AnimeHistory.Summary summary){
        Activity a=active.get();String pageUrl=currentUrl();if(!alive(a)||!WatchUrl.same(url,pageUrl)||!normalProfile(a))return;
        if(summary==null||summary.episode<1||summary.dismissed){removeWatchReminder();return;}
        if(!reminderPresentation.accept(pageUrl,summary.key,summary.episode))return;
        removeWatchReminder();reminderUrl=pageUrl;reminderKey=summary.key;reminderEpisode=summary.episode;
        watchReminder=new LinearLayout(a);watchReminder.setGravity(Gravity.CENTER_VERTICAL);watchReminder.setPadding(dp(a,8),dp(a,6),dp(a,8),dp(a,6));
        GradientDrawable background=new GradientDrawable();background.setColor(0xf0202028);background.setCornerRadius(dp(a,14));watchReminder.setBackground(background);watchReminder.setElevation(dp(a,8));
        TextView label=new TextView(a);label.setText("You were on episode "+summary.episode+" of "+summary.title);label.setTextColor(Color.WHITE);label.setTextSize(14);label.setPadding(dp(a,8),dp(a,8),dp(a,8),dp(a,8));label.setMaxLines(3);label.setOnClickListener(v->{removeWatchReminder();watchLog(a,summary.key,summary.title);});
        watchReminder.addView(label,new LinearLayout.LayoutParams(0,-2,1));Button close=action(a,"×",()->{IO.execute(()->history.dismiss(summary.key));removeWatchReminder();});close.setContentDescription("Dismiss episode reminder");watchReminder.addView(close,new LinearLayout.LayoutParams(dp(a,48),dp(a,48)));
        FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(Math.min(dp(a,560),a.getResources().getDisplayMetrics().widthPixels-dp(a,24)),-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);params.topMargin=dp(a,immersive?24:96);
        ((FrameLayout)a.getWindow().getDecorView()).addView(watchReminder,params);
        int timeout=ReminderPresentation.TIMEOUT_MS;
        android.view.accessibility.AccessibilityManager accessibility=(android.view.accessibility.AccessibilityManager)a.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if(accessibility!=null)timeout=accessibility.getRecommendedTimeoutMillis(timeout,android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT|android.view.accessibility.AccessibilityManager.FLAG_CONTENT_CONTROLS);
        UI.postDelayed(AUTO_HIDE_REMINDER,timeout);
    }
    private static void library(Activity a){HIDE.run();ContinuationController.library(a);}
    static void continuationLog(Activity a,JSONObject e){String title=e.optString("title")+(e.optInt("season",1)>1?" Season "+e.optInt("season"):"")+(e.optInt("part")>0?" Part "+e.optInt("part"):"");watchLog(a,AnimeIdentity.key(title),title);}
    private static void watchLog(Activity a,String key,String title){
        IO.execute(()->{String text=history.log(key);UI.post(()->{if(!alive(a))return;TextView content=new TextView(a);content.setText(text);content.setTextColor(Color.WHITE);content.setTextSize(15);content.setPadding(dp(a,20),dp(a,16),dp(a,20),dp(a,16));ScrollView scroll=new ScrollView(a);scroll.setBackgroundColor(0xff202028);scroll.addView(content);new AlertDialog.Builder(a).setTitle(title).setView(scroll).setPositiveButton("Close",null).show();});});
    }
    static void engineReady(){status="Connected";}
    static void engineWarning(String value){status=value;}
    static void reveal(String url){UI.post(()->{Activity a=active.get();if(alive(a)&&immersive&&SiteKey.of(url).equals(SiteKey.of(currentUrl())))showTools(a);});}
    static void fullscreen(String url,boolean enabled){UI.post(()->{
        Activity a=active.get();if(!alive(a)||!SiteKey.of(url).equals(SiteKey.of(currentUrl())))return;
        HIDE.run();nativeFullscreen(a.getWindow().getDecorView(),enabled);
    });}
    static void pin(String url,String title,String iconUrl){
        Context c=context;if(c==null||SiteKey.of(url).isEmpty())return;
        IO.execute(()->{
            Bitmap bitmap=null;
            try{
                Uri icon=Uri.parse(iconUrl);
                if("https".equals(icon.getScheme())){
                    HttpURLConnection connection=(HttpURLConnection)new URL(iconUrl).openConnection();connection.setConnectTimeout(3000);connection.setReadTimeout(3000);connection.setInstanceFollowRedirects(false);
                    try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                        byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>2*1024*1024)throw new IOException("Icon too large");out.write(buffer,0,n);}
                        byte[] data=out.toByteArray();BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(data,0,data.length,bounds);
                        if(bounds.outWidth>0&&bounds.outHeight>0&&bounds.outWidth<=4096&&bounds.outHeight<=4096){BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=Math.max(1,Math.max(bounds.outWidth,bounds.outHeight)/192);bitmap=BitmapFactory.decodeByteArray(data,0,data.length,options);}
                    }finally{connection.disconnect();}
                }
            }catch(Exception ignored){}
            final Bitmap artwork=bitmap;
            UI.post(()->{
                Activity a=active.get();if(!alive(a))return;
                ShortcutManager manager=c.getSystemService(ShortcutManager.class);if(manager==null||!manager.isRequestPinShortcutSupported()){Toast.makeText(a,"Your launcher does not support pinned shortcuts",Toast.LENGTH_LONG).show();return;}
                Intent launch=new Intent(Intent.ACTION_VIEW,Uri.parse(url));launch.setClass(c,ShortcutLaunchActivity.class);launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
                String label=title.trim().isEmpty()?SiteKey.of(url):title.trim();if(label.length()>45)label=label.substring(0,45);
                Icon image=artwork==null?Icon.createWithResource(c,c.getApplicationInfo().icon):Icon.createWithBitmap(artwork);
                String id="site-"+Integer.toHexString(url.hashCode());
                ShortcutInfo info=new ShortcutInfo.Builder(c,id).setShortLabel(label).setLongLabel(label).setIcon(image).setIntent(launch).build();
                manager.requestPinShortcut(info,null);
            });
        });
    }
}
