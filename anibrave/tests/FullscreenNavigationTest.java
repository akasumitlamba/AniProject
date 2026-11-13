package app.anibrave;

import android.view.View;

public final class FullscreenNavigationTest {
    private static void check(boolean value,String message){
        if(!value)throw new AssertionError(message);
    }
    public static void main(String[] args){
        int shortcut=FullscreenNavigation.visibility(true);
        check((shortcut&View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)==0,"Shortcut must not hide system navigation");
        check((shortcut&View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)==0,"Shortcut must not consume first edge gesture");
        check((shortcut&View.SYSTEM_UI_FLAG_FULLSCREEN)!=0,"Shortcut still hides status bar");
        check((shortcut&View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)!=0,"Content retains edge-to-edge layout");
        check((shortcut&View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)!=0,"No status-bar layout gutter");
        int video=FullscreenNavigation.visibility(false);
        check((video&View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)!=0,"Normal browser video retains immersive navigation hiding");
        check((video&View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)!=0,"Normal browser video retains transient system controls");
        check((video&shortcut)==shortcut,"Both modes retain fullscreen content layout");
        System.out.println("8 fullscreen navigation policy checks passed");
    }
}
