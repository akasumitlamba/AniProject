package app.anibrave;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.InsetDrawable;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.os.*;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Activity-owned native tab rail. Never installed in immersive shortcut activities. */
final class VerticalTabs {
    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ViewGroup content;
    private final FrameLayout decor;
    private final int left;
    private final int[] blockedIds;
    private LinearLayout rail;
    private View scrim;
    private ListView list;
    private ImageButton toggle, overviewButton, featuresButton, compactMode;
    private TextView heading, count, regularMode, privateMode, add;
    private LinearLayout mode, segments, footer;
    private final int surface, ink, muted, accent, selectedColor, divider, well;
    private boolean lastExpanded;
    private boolean overlayVisible, nativeOverlay;
    private int addedTopInset;
    private final Map<View,Integer> toolbarVisibility=new IdentityHashMap<>(),toolbarWidths=new IdentityHashMap<>();
    private ViewTreeObserver.OnPreDrawListener privacyListener;
    private TabAdapter adapter;
    private NativeTabs nativeTabs;
    private Object stripController,stripCoordinator,stripLayer;
    private boolean originalLayerHidden;
    private boolean originalStripHidden;
    private boolean expanded, closed, updating;
    private String fingerprint = "";
    private boolean privateModel;
    private int startupAttempts;
    private ViewTreeObserver.OnGlobalLayoutListener layoutListener;
    private final Runnable refresh = new Runnable() { public void run() {
        if (closed) return;
        try { update(); startupAttempts=0; } catch (Exception e) { if(++startupAttempts>=20) { android.util.Log.w("AniBrave","Native tab layout unavailable",e);fail(); } }
        if (!closed) handler.postDelayed(this, 750);
    }};

    static boolean enabled(Context context) {
        return context.getSharedPreferences("anibrave_ui", 0).getBoolean("vertical_tabs", false);
    }
    static void settings(Activity activity, Runnable changed) {
        // Omnibox focus holds Chromium's toolbar transition open. End editing
        // before changing geometry so its compositor snapshot can be released.
        View focus=activity.getCurrentFocus();
        if(focus instanceof EditText){
            focus.clearFocus();
            android.view.inputmethod.InputMethodManager keyboard=(android.view.inputmethod.InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if(keyboard!=null)keyboard.hideSoftInputFromWindow(focus.getWindowToken(),0);
        }
        new AlertDialog.Builder(activity).setTitle("Tab layout")
            .setSingleChoiceItems(new String[]{"Horizontal", "Vertical"}, enabled(activity) ? 1 : 0,
                (dialog, which) -> {
                    boolean previous=enabled(activity);
                    activity.getSharedPreferences("anibrave_ui", 0).edit().putBoolean("vertical_tabs", which == 1).apply();
                    dialog.dismiss(); if((which==1)!=previous)changed.run();
                }).setNegativeButton("Cancel", null).show();
    }
    VerticalTabs(Activity activity) {
        this.activity = activity;
        boolean dark=(activity.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        surface=dark?0xff1b1e26:0xfff4f6fa;ink=dark?0xffe6eaf3:0xff202939;
        muted=dark?0xff9aa5ba:0xff66738a;accent=dark?0xffa9c2ff:0xff3c5fb5;
        selectedColor=dark?0xff2c3b55:0xffdfe8fb;divider=dark?0xff303642:0xffdce2ed;
        well=dark?0xff252b36:0xffe8edf5;
        String[] blockers={"incognito_reauth_unlock_incognito_button","incognito_reauth_unlock_incognito_icon","incognito_reauth_see_other_tabs_label","tab_list_recycler_view","tab_grid_dialog_recycler_view_container","modal_dialog_view"};
        blockedIds=new int[blockers.length];
        for(int i=0;i<blockers.length;i++)blockedIds[i]=activity.getResources().getIdentifier(blockers[i],"id",activity.getPackageName());
        content = activity.findViewById(android.R.id.content);
        decor = (FrameLayout) activity.getWindow().getDecorView();
        left=content.getPaddingLeft();
        expanded=activity.getSharedPreferences("anibrave_ui",0).getBoolean("tabs_expanded", true);
        layoutListener=()->{ if(!closed&&!updating) geometry(); };
        decor.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
        privacyListener=()->{
            toolbarControls();
            try{hideStripLayer();}catch(Exception e){android.util.Log.w("AniBrave","Native strip layer unavailable",e);fail();return false;}
            boolean blocked=blocked();
            if(blocked!=nativeOverlay){nativeOverlay=blocked;fingerprint="";
                if(blocked)clearRows();
                else try{update();}catch(Exception e){clearRows();}
                geometry();return false;
            }return true;
        };
        decor.getViewTreeObserver().addOnPreDrawListener(privacyListener);
        handler.post(refresh);
    }
    private int dp(int value) { return Math.round(value*activity.getResources().getDisplayMetrics().density); }
    private View find(String name) {
        int id=activity.getResources().getIdentifier(name,"id",activity.getPackageName());
        return id==0?null:decor.findViewById(id);
    }
    private static Object field(Object owner, String name) throws Exception {
        return owner.getClass().getField(name).get(owner);
    }
    private void strip() throws Exception {
        View control=find("control_container");
        if(control==null) return;
        Object compositor=field(control,"z");
        if(compositor==null) return;
        Object transition=field(compositor,"E");
        // Phones do not have a horizontal strip transition coordinator.
        if(transition==null) return;
        Object controller=field(transition,"J");
        if(controller==null) return;
        if(stripController!=controller) {
            restoreStrip(); stripController=controller;stripCoordinator=transition;
            originalStripHidden=(Boolean)field(controller,"p");
        }
        // Native scroll/resize policy must also know the strip is hidden; a
        // zero height alone leaves its compositor layer alive while scrolling.
        if(!(Boolean)field(controller,"p"))transition.getClass().getMethod("e",boolean.class).invoke(transition,true);
        hideStripLayer();
        if(((Number)field(controller,"m")).intValue()!=0)NativeStrip.request(activity,control,controller,0,handler);
        else NativeStrip.cancel(controller);
    }
    private void hideStripLayer() throws Exception {
        if(stripController==null)return;
        Object layer=field(field(stripController,"a"),"C");
        if(layer==null)return; // Native compositor may attach after toolbar initialization.
        if(layer!=stripLayer){
            restoreStripLayer();stripLayer=layer;
            originalLayerHidden=(((Number)field(field(layer,"w0"),"w")).intValue()&1)!=0;
        }
        int flags=((Number)field(field(layer,"w0"),"w")).intValue();
        // H3() gates native scene-overlay rendering and hit testing. Height zero
        // alone does not suppress this independent layer when controls scroll.
        if((flags&1)==0)layer.getClass().getMethod("t",int.class,boolean.class).invoke(layer,1,false);
    }
    private void restoreStripLayer(){
        if(stripLayer!=null)try{stripLayer.getClass().getMethod("t",int.class,boolean.class).invoke(stripLayer,1,!originalLayerHidden);}catch(Exception ignored){}
        stripLayer=null;
    }
    private void restoreStrip() {
        restoreStripLayer();
        if(stripController!=null) try {
            if(stripCoordinator!=null)stripCoordinator.getClass().getMethod("e",boolean.class).invoke(stripCoordinator,originalStripHidden);
            NativeStrip.immediate(stripController,NativeStrip.desired(stripController));
        } catch(Exception ignored) {}
        if(stripController!=null)NativeStrip.cancel(stripController);
        stripController=null;stripCoordinator=null;
    }
    private GradientDrawable rounded(int color,int radius){
        GradientDrawable shape=new GradientDrawable();shape.setColor(color);shape.setCornerRadius(dp(radius));return shape;
    }
    private Drawable touch(int color,int radius){
        return new RippleDrawable(ColorStateList.valueOf((accent&0x00ffffff)|0x26000000),rounded(color,radius),rounded(Color.WHITE,radius));
    }
    private TabIcon icon(int kind,int color){TabIcon icon=new TabIcon(kind,color,dp(20));icon.setBounds(0,0,dp(20),dp(20));return icon;}
    private ImageButton iconButton(int kind,String description,Runnable click){
        ImageButton button=new ImageButton(activity);button.setImageDrawable(icon(kind,muted));button.setScaleType(ImageView.ScaleType.CENTER);
        button.setBackground(touch(Color.TRANSPARENT,8));button.setContentDescription(description);button.setTooltipText(description);
        button.setFocusable(true);button.setOnClickListener(v->click.run());return button;
    }
    private TextView textButton(String title,int kind,Runnable click){
        TextView button=new TextView(activity);button.setText(title);button.setTextSize(13);button.setTextColor(ink);
        button.setGravity(Gravity.CENTER);button.setSingleLine(true);button.setEllipsize(TextUtils.TruncateAt.END);
        button.setCompoundDrawablesRelative(icon(kind,muted),null,null,null);button.setCompoundDrawablePadding(dp(7));
        button.setPadding(dp(12),0,dp(12),0);button.setBackground(touch(Color.TRANSPARENT,8));
        button.setFocusable(true);button.setContentDescription(title);button.setTooltipText(title);button.setOnClickListener(v->click.run());return button;
    }
    private void create(){
        scrim=new View(activity);scrim.setBackgroundColor(0x44000000);scrim.setContentDescription("Collapse tabs");
        scrim.setOnClickListener(v->expand(false));decor.addView(scrim,new FrameLayout.LayoutParams(-1,-1));
        rail=new LinearLayout(activity){
            private final android.graphics.Paint line=new android.graphics.Paint();
            @Override public boolean dispatchKeyEvent(KeyEvent event){
                if(overlayVisible&&event.getKeyCode()==KeyEvent.KEYCODE_BACK){if(event.getAction()==KeyEvent.ACTION_UP)expand(false);return true;}
                return super.dispatchKeyEvent(event);
            }
            @Override protected void dispatchDraw(android.graphics.Canvas canvas){
                super.dispatchDraw(canvas);line.setColor(divider);
                canvas.drawRect(getWidth()-dp(1),0,getWidth(),getHeight(),line);
            }
        };
        rail.setFocusableInTouchMode(true);rail.setOrientation(LinearLayout.VERTICAL);rail.setBackgroundColor(surface);
        LinearLayout header=new LinearLayout(activity);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(8),0,dp(4),0);
        heading=new TextView(activity);heading.setText("Tabs");heading.setTextColor(ink);heading.setTextSize(15);heading.setTypeface(null,Typeface.BOLD);
        heading.setGravity(Gravity.CENTER_VERTICAL);heading.setPadding(dp(6),0,dp(6),0);header.addView(heading,new LinearLayout.LayoutParams(0,dp(48),1));
        count=new TextView(activity);count.setTextSize(11);count.setTextColor(muted);count.setGravity(Gravity.CENTER);count.setMinWidth(dp(24));
        header.addView(count,new LinearLayout.LayoutParams(-2,dp(28)));
        toggle=iconButton(TabIcon.COLLAPSE,"Collapse tabs",()->expand(!expanded));header.addView(toggle,new LinearLayout.LayoutParams(dp(44),dp(48)));
        rail.addView(header,new LinearLayout.LayoutParams(-1,dp(48)));
        mode=new LinearLayout(activity);mode.setGravity(Gravity.CENTER);mode.setPadding(dp(8),dp(2),dp(8),dp(6));
        segments=new LinearLayout(activity);segments.setPadding(dp(2),dp(2),dp(2),dp(2));segments.setBackground(rounded(well,9));
        regularMode=textButton("Regular",TabIcon.REGULAR,()->perform(()->nativeTabs.switchModel(false)));
        privateMode=textButton("Private",TabIcon.PRIVATE,()->perform(()->nativeTabs.switchModel(true)));
        segments.addView(regularMode,new LinearLayout.LayoutParams(0,-1,1));segments.addView(privateMode,new LinearLayout.LayoutParams(0,-1,1));
        mode.addView(segments,new LinearLayout.LayoutParams(-1,-1));
        compactMode=iconButton(TabIcon.PRIVATE,"Switch regular or private tabs",()->modes());mode.addView(compactMode,new LinearLayout.LayoutParams(dp(40),-1));
        rail.addView(mode,new LinearLayout.LayoutParams(-1,dp(44)));
        list=new ListView(activity);list.setDivider(null);list.setCacheColorHint(Color.TRANSPARENT);list.setClipToPadding(false);list.setPadding(dp(6),dp(4),dp(6),dp(4));
        adapter=new TabAdapter();list.setAdapter(adapter);rail.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        footer=new LinearLayout(activity);footer.setGravity(Gravity.CENTER);footer.setPadding(dp(4),dp(2),dp(4),dp(2));
        add=textButton("New tab",TabIcon.PLUS,()->perform(()->nativeTabs.newTab(privateModel)));add.setContentDescription("New tab");
        overviewButton=iconButton(TabIcon.OVERVIEW,"Tab overview and groups",()->{View tabs=find("tab_switcher_button");if(tabs!=null)tabs.performClick();});
        featuresButton=iconButton(TabIcon.SETTINGS,"AniBrave features",()->AniRuntime.showTools(activity,featuresButton));
        footer.addView(add);footer.addView(overviewButton);footer.addView(featuresButton);
        rail.addView(footer,new LinearLayout.LayoutParams(-1,dp(52)));
        decor.addView(rail,new FrameLayout.LayoutParams(dp(56),-1));
        lastExpanded=!expanded;styleChrome();
    }
    private void styleChrome(){
        if(rail==null)return;
        if(lastExpanded!=expanded){
            heading.setVisibility(expanded?View.VISIBLE:View.GONE);count.setVisibility(expanded?View.VISIBLE:View.GONE);
            segments.setVisibility(expanded?View.VISIBLE:View.GONE);compactMode.setVisibility(expanded?View.GONE:View.VISIBLE);
            toggle.setImageDrawable(icon(expanded?TabIcon.COLLAPSE:TabIcon.EXPAND,muted));
            toggle.setContentDescription(expanded?"Collapse tabs":"Expand tabs");toggle.setTooltipText(expanded?"Collapse tabs":"Expand tabs");
            footer.setOrientation(expanded?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);
            add.setGravity(Gravity.CENTER_VERTICAL|Gravity.LEFT);add.setText(expanded?"New tab":"");add.setCompoundDrawablePadding(expanded?dp(7):0);
            add.setPadding(expanded?dp(12):dp(14),0,expanded?dp(12):dp(14),0);
            add.setLayoutParams(new LinearLayout.LayoutParams(expanded?0:-1,dp(48),expanded?1:0));
            overviewButton.setLayoutParams(new LinearLayout.LayoutParams(expanded?dp(44):-1,dp(48)));
            featuresButton.setLayoutParams(new LinearLayout.LayoutParams(expanded?dp(44):-1,dp(48)));
            lastExpanded=expanded;
        }
        regularMode.setTextColor(privateModel?muted:accent);privateMode.setTextColor(privateModel?accent:muted);
        regularMode.setBackground(touch(privateModel?Color.TRANSPARENT:surface,7));privateMode.setBackground(touch(privateModel?surface:Color.TRANSPARENT,7));
        regularMode.setSelected(!privateModel);privateMode.setSelected(privateModel);
        compactMode.setImageDrawable(icon(privateModel?TabIcon.PRIVATE:TabIcon.REGULAR,privateModel?accent:muted));
        compactMode.setContentDescription(privateModel?"Private tabs. Switch tab type":"Regular tabs. Switch tab type");
        count.setText(Integer.toString(adapter.tabs.size()));heading.setText(privateModel?"Private tabs":"Tabs");
    }
    private void expand(boolean value) {
        expanded=value;activity.getSharedPreferences("anibrave_ui",0).edit().putBoolean("tabs_expanded",value).apply();
        fingerprint="";adapter.notifyDataSetChanged();styleChrome();geometry();
    }
    private void modes() {
        new AlertDialog.Builder(activity).setTitle("Tabs").setItems(new String[]{"Regular tabs","Private tabs"},(d,index)->
            perform(()->nativeTabs.switchModel(index==1))).setNegativeButton("Cancel",null).show();
    }
    private interface Operation { void run() throws Exception; }
    private void perform(Operation operation) {
        try { operation.run(); NativeStrip.showControls(activity);fingerprint="";update(); }
        catch(Exception e) { Toast.makeText(activity,"Tab action unavailable. Use the browser tab overview.",Toast.LENGTH_LONG).show(); }
    }
    private void clearRows() {
        fingerprint="";
        if(adapter!=null&&!adapter.tabs.isEmpty()){adapter.tabs=Collections.emptyList();adapter.selected=-1;adapter.notifyDataSetChanged();}
    }
    private boolean blocked() {
        for(int id:blockedIds) {
            View view=id==0?null:decor.findViewById(id);if(view!=null&&view.isShown()&&view.getWidth()>0&&view.getHeight()>0)return true;
        }return false;
    }
    private void update() throws Exception {
        if(blocked()){nativeOverlay=true;clearRows();geometry();return;}
        if(nativeTabs==null) nativeTabs=new NativeTabs(activity);
        NativeTabs.Snapshot snapshot=nativeTabs.snapshot();
        toolbarControls();strip();
        if(rail==null) create();
        privateModel=snapshot.incognito;
        int selected=-1;StringBuilder state=new StringBuilder(privateModel?"private":"regular");
        for(NativeTabs.Entry entry:snapshot.tabs){if(entry.selected)selected=entry.id;state.append('|').append(entry.id).append(':').append(entry.selected).append(':').append(entry.title.length()).append(':').append(entry.title).append(':').append(entry.favicon==null?0:entry.favicon.getGenerationId());}
        String key=state.toString();
        if(!key.equals(fingerprint)) {
            boolean selectionChanged=adapter.selected!=selected;
            adapter.tabs=snapshot.tabs;adapter.selected=selected;adapter.notifyDataSetChanged();
            styleChrome();
            if(selectionChanged) for(int i=0;i<adapter.tabs.size();i++) if(adapter.tabs.get(i).id==selected){list.smoothScrollToPosition(i);break;}
            fingerprint=key;
        }
        geometry();
    }
    private void toolbarControls(){
        for(String name:new String[]{"anibrave_features","tab_switcher_button"}){
            View button=find(name);if(button==null)continue;
            if(!toolbarVisibility.containsKey(button)){
                toolbarVisibility.put(button,button.getVisibility());toolbarWidths.put(button,button.getLayoutParams().width);
            }
            // Native toolbar measures its child widths explicitly. An invisible
            // zero-width slot reclaims that space; GONE retains its old measured width.
            ViewGroup.LayoutParams params=button.getLayoutParams();
            if(params.width!=0){params.width=0;button.setLayoutParams(params);}
            if(button.getVisibility()!=View.INVISIBLE)button.setVisibility(View.INVISIBLE);
        }
    }
    private void geometry() {
        if(rail==null||content.getWidth()==0||updating) return;
        updating=true;
        try {
            int nativeTop=Math.max(0,content.getPaddingTop()-addedTopInset);
            int right=content.getPaddingRight(),bottom=content.getPaddingBottom();
            boolean fullscreen=AniRuntime.videoFullscreen()||nativeOverlay;
            int width=Math.round(content.getWidth()/activity.getResources().getDisplayMetrics().density);
            int reserve=fullscreen?0:dp(TabLayoutPolicy.reserved(width,expanded));

            rail.setVisibility(fullscreen?View.GONE:View.VISIBLE);
            int[] position=new int[2];content.getLocationInWindow(position);
            FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)rail.getLayoutParams();
            int panel=dp(TabLayoutPolicy.panel(width,expanded));
            WindowInsets insets=decor.getRootWindowInsets();
            int safeTop=insets==null?0:insets.getSystemWindowInsetTop();
            int safeBottom=insets==null?0:insets.getSystemWindowInsetBottom();
            int safeLeft=insets==null?0:insets.getSystemWindowInsetLeft();
            // Chromium's strip transition also removes the edge-to-edge status
            // reservation. Keep that system inset outside the zero-height strip.
            int originTop=position[1]+nativeTop;
            View controls=find("control_container");
            if(controls!=null){
                int[] controlsPosition=new int[2];controls.getLocationInWindow(controlsPosition);
                originTop=controlsPosition[1]-Math.round(controls.getTranslationY())-addedTopInset;
            }
            int top=fullscreen?nativeTop:nativeTop+Math.max(0,safeTop-originTop);
            addedTopInset=top-nativeTop;
            if(content.getPaddingLeft()!=left+reserve||content.getPaddingTop()!=top)
                content.setPadding(left+reserve,top,right,bottom);
            int railTop=Math.max(position[1]+top,safeTop);
            int railLeft=Math.max(position[0]+left,safeLeft);
            int targetHeight=Math.max(0,Math.min(position[1]+content.getHeight()-bottom,decor.getHeight()-safeBottom)-railTop);
            mode.setVisibility(targetHeight>=dp(240)?View.VISIBLE:View.GONE);
            add.setVisibility(expanded||targetHeight>=dp(280)?View.VISIBLE:View.GONE);
            int footerHeight=dp(expanded?52:(targetHeight>=dp(280)?148:100));
            if(footer.getLayoutParams().height!=footerHeight){footer.getLayoutParams().height=footerHeight;footer.requestLayout();}
            if(p.width!=panel||p.height!=targetHeight||p.leftMargin!=railLeft||p.topMargin!=railTop){
                p.width=panel;p.height=targetHeight;p.leftMargin=railLeft;p.topMargin=railTop;rail.setLayoutParams(p);
            }
            boolean overlay=!fullscreen&&expanded&&TabLayoutPolicy.reserved(width,true)==TabLayoutPolicy.COMPACT;
            // An expanded rail dismisses from the page on wide layouts too.
            // Keep the native address bar outside this touch surface, and consume
            // the dismissing tap so it cannot accidentally activate a page link.
            scrim.setBackgroundColor(overlay?0x44000000:Color.TRANSPARENT);
            scrim.setVisibility(!fullscreen&&expanded?View.VISIBLE:View.GONE);
            if(overlay&&!overlayVisible)rail.requestFocus();
            overlayVisible=overlay;
            FrameLayout.LayoutParams s=(FrameLayout.LayoutParams)scrim.getLayoutParams();
            int pageTop=p.topMargin;
            if(controls!=null&&controls.isShown()){
                int[] controlsPosition=new int[2];controls.getLocationInWindow(controlsPosition);
                pageTop=Math.max(pageTop,controlsPosition[1]+controls.getHeight());
            }
            int pageLeft=p.leftMargin+p.width;
            int pageHeight=Math.max(0,p.topMargin+p.height-pageTop);
            int pageWidth=Math.max(0,decor.getWidth()-pageLeft);
            if(s.width!=pageWidth||s.height!=pageHeight||s.leftMargin!=pageLeft||s.topMargin!=pageTop){
                s.width=pageWidth;s.height=pageHeight;s.leftMargin=pageLeft;s.topMargin=pageTop;scrim.setLayoutParams(s);
            }

        } finally { updating=false; }
    }
    private void fail() {
        close();Toast.makeText(activity,"Vertical tabs are temporarily unavailable. Reopen the browser to retry.",Toast.LENGTH_LONG).show();
    }
    void close() {
        if(closed)return;closed=true;handler.removeCallbacks(refresh);
        if(decor.getViewTreeObserver().isAlive()){decor.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);decor.getViewTreeObserver().removeOnPreDrawListener(privacyListener);}
        if(rail!=null)decor.removeView(rail);if(scrim!=null)decor.removeView(scrim);
        content.setPadding(left,Math.max(0,content.getPaddingTop()-addedTopInset),content.getPaddingRight(),content.getPaddingBottom());
        addedTopInset=0;
        for(Map.Entry<View,Integer> button:toolbarVisibility.entrySet()){
            View view=button.getKey();ViewGroup.LayoutParams params=view.getLayoutParams();params.width=toolbarWidths.get(view);view.setLayoutParams(params);view.setVisibility(button.getValue());
        }
        toolbarVisibility.clear();toolbarWidths.clear();restoreStrip();
    }
    private final class TabAdapter extends BaseAdapter {
        List<NativeTabs.Entry> tabs=Collections.emptyList();int selected=-1;
        public int getCount(){return tabs.size();}public Object getItem(int p){return tabs.get(p);}public long getItemId(int p){return tabs.get(p).id;}public boolean hasStableIds(){return true;}
        public View getView(int position,View recycled,ViewGroup parent){
            TabRow row=recycled instanceof TabRow?(TabRow)recycled:new TabRow();row.bind(tabs.get(position),position);return row;
        }
    }
    private final class TabRow extends LinearLayout {
        NativeTabs.Entry tab;
        final TextView label;
        final ImageView mark;
        final ImageButton close;
        TabRow(){
            super(activity);setGravity(Gravity.CENTER_VERTICAL);setPadding(dp(6),0,0,0);
            mark=new ImageView(activity);mark.setScaleType(ImageView.ScaleType.FIT_CENTER);mark.setPadding(dp(3),dp(3),dp(3),dp(3));
            addView(mark,new LinearLayout.LayoutParams(dp(28),dp(28)));
            label=new TextView(activity);label.setTextSize(13);label.setSingleLine(true);label.setEllipsize(TextUtils.TruncateAt.END);label.setGravity(Gravity.CENTER_VERTICAL);label.setPadding(dp(8),0,0,0);
            addView(label,new LinearLayout.LayoutParams(0,-1,1));
            close=iconButton(TabIcon.CLOSE,"Close tab",()->perform(()->nativeTabs.closeById(tab.id,tab.incognito)));addView(close,new LinearLayout.LayoutParams(dp(48),dp(48)));
            setOnClickListener(v->{perform(()->nativeTabs.selectById(tab.id,tab.incognito));if(TabLayoutPolicy.reserved(Math.round(content.getWidth()/activity.getResources().getDisplayMetrics().density),true)==TabLayoutPolicy.COMPACT)expand(false);});
            setOnLongClickListener(v->{NativeTabs.Entry target=tab;PopupMenu menu=new PopupMenu(activity,this);menu.getMenu().add("Close tab").setOnMenuItemClickListener(i->{perform(()->nativeTabs.closeById(target.id,target.incognito));return true;});menu.show();return true;});
            setFocusable(true);setLayoutParams(new AbsListView.LayoutParams(-1,dp(48)));
        }
        void bind(NativeTabs.Entry entry,int index){
            tab=entry;boolean active=entry.id==adapter.selected;String title=entry.title.isEmpty()?"New tab":entry.title;
            setSelected(active);setContentDescription("Open tab: "+title);setTooltipText(title);label.setText(title);label.setTextColor(active?accent:ink);label.setTypeface(null,active?Typeface.BOLD:Typeface.NORMAL);
            if(entry.favicon!=null&&!entry.favicon.isRecycled())mark.setImageBitmap(entry.favicon);
            else mark.setImageDrawable(icon(privateModel?TabIcon.PRIVATE:TabIcon.GLOBE,active?accent:muted));
            mark.setBackground(null);
            setBackground(new InsetDrawable(touch(active?selectedColor:Color.TRANSPARENT,8),0,dp(2),0,dp(2)));
            setPadding(dp(6),0,0,0);
            label.setVisibility(expanded?View.VISIBLE:View.GONE);close.setVisibility(expanded?View.VISIBLE:View.GONE);
            close.setContentDescription("Close tab: "+title);close.setTooltipText("Close tab");
        }
    }
}
