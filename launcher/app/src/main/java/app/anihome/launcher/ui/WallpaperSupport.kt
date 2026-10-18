package app.anihome.launcher.ui

import android.app.Activity
import android.view.WindowManager

/** Show the device wallpaper behind launcher screens instead of the built-in gradient. */
object WallpaperSupport {
    fun apply(activity: Activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        activity.window.setBackgroundDrawableResource(android.R.color.transparent)
    }
}
