package app.anihome.launcher.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import app.anihome.launcher.R

internal object PanelGeometryPolicy {
    data class Bounds(val width: Int, val height: Int, val x: Int, val y: Int)
    fun bounds(width: Int, height: Int, desiredWidth: Int): Bounds {
        return Bounds(desiredWidth.coerceAtMost(width.coerceAtLeast(1)), height.coerceAtLeast(1), 0, 0)
    }
}

/** The surface meets the physical left/top edges; content handles safe insets internally. */
internal object SidePanelLayout {
    fun bounds(activity: AppCompatActivity): PanelGeometryPolicy.Bounds {
        val size = if (Build.VERSION.SDK_INT >= 30) activity.windowManager.currentWindowMetrics.bounds else {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.getRealMetrics(metrics)
            android.graphics.Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
        return PanelGeometryPolicy.bounds(size.width(), size.height(), minOf(ArtworkUi.dp(activity, 360), (size.width() - ArtworkUi.dp(activity, 48)).coerceAtLeast(1)))
    }
    fun phone(activity: AppCompatActivity) = activity.resources.configuration.smallestScreenWidthDp < 600
    fun screenWidth(activity: AppCompatActivity) = bounds(activity).let { if (Build.VERSION.SDK_INT >= 30) activity.windowManager.currentWindowMetrics.bounds.width() else {
        val metrics = android.util.DisplayMetrics(); @Suppress("DEPRECATION") activity.windowManager.defaultDisplay.getRealMetrics(metrics); metrics.widthPixels } }
    fun apply(activity: AppCompatActivity, window: Window, right: Boolean = false, full: Boolean = false): PanelGeometryPolicy.Bounds {
        // Phones have no width to spare in any orientation: the panel always fills the screen.
        val bounds = bounds(activity).let { if (full || phone(activity)) it.copy(width = screenWidth(activity)) else it }
        HomeFullscreen.apply(window)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        // Physical left (or right when opened from the right edge), including in RTL layouts.
        @Suppress("RtlHardcoded")
        val edge = if (right) Gravity.RIGHT else Gravity.LEFT
        window.setGravity(edge or Gravity.TOP)
        window.setLayout(bounds.width, bounds.height)
        window.attributes = window.attributes.apply { x = bounds.x; y = bounds.y; screenOrientation = activity.requestedOrientation }
        return bounds
    }
}
