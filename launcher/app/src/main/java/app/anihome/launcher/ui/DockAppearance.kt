package app.anihome.launcher.ui

import android.app.WallpaperManager
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.core.graphics.ColorUtils

internal object DockOpacityPolicy {
    fun automatic(luminance: Double): Int = (26 + kotlin.math.sqrt(luminance.coerceIn(0.0, 1.0)) * 54).toInt()
    fun alpha(percent: Int) = (percent.coerceIn(0, 100) * 255 / 100)
}

/** Changes the surface only: icons and text never become translucent. */
internal class DockAppearance(private val context: Context, private val dock: View) {
    private val manager = WallpaperManager.getInstance(context)
    private val prefs = context.getSharedPreferences("anihome", 0)
    private var listener: Any? = null
    private var luminance = .5
    fun apply() {
        val percent = if (prefs.getBoolean("dock_dynamic_opacity", true)) DockOpacityPolicy.automatic(luminance) else prefs.getInt("dock_opacity", 72)
        val alpha = DockOpacityPolicy.alpha(percent)
        dock.background = GradientDrawable().apply {
            cornerRadius = context.resources.getDimension(app.anihome.launcher.R.dimen.rail_radius)
            setColor((alpha shl 24) or 0x101216)
            setStroke(ArtworkUi.dp(context, 1), (0x66 shl 24) or 0xffffff)
        }
    }
    fun start() {
        if (Build.VERSION.SDK_INT >= 27) {
            runCatching { Api27.colors(manager) }.getOrNull()?.let { luminance = it }
            if (listener == null) listener = runCatching { Api27.listen(manager) { level -> luminance = level; apply() } }.getOrNull()
        }
        apply()
    }
    fun stop() {
        if (Build.VERSION.SDK_INT >= 27) listener?.let { Api27.remove(manager, it) }
        listener = null
    }
    @androidx.annotation.RequiresApi(27)
    private object Api27 {
        private fun level(colors: android.app.WallpaperColors?) = colors?.let {
            listOfNotNull(it.primaryColor, it.secondaryColor, it.tertiaryColor).maxOfOrNull { color -> ColorUtils.calculateLuminance(color.toArgb()) }
        }
        fun colors(manager: WallpaperManager): Double? = level(manager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM))
        fun listen(manager: WallpaperManager, changed: (Double) -> Unit): Any {
            val listener = WallpaperManager.OnColorsChangedListener { colors, _ -> level(colors)?.let(changed) }
            manager.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper()))
            return listener
        }
        fun remove(manager: WallpaperManager, listener: Any) = manager.removeOnColorsChangedListener(listener as WallpaperManager.OnColorsChangedListener)
    }
}
