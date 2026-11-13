package app.anibrave;

import android.view.View;

/** Shortcut windows keep system navigation available on the first edge swipe. */
final class FullscreenNavigation {
    private FullscreenNavigation() {}

    static int visibility(boolean shortcut) {
        int layout = View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        // Sticky navigation hiding consumes the first Back/Recents gesture to
        // reveal system controls. A website shortcut needs navigation immediately.
        return shortcut ? layout : layout
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
    }
}
