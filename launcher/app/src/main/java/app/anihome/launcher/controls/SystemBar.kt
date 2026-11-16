package app.anihome.launcher.controls

import android.content.Context

/**
 * Preferences for AniHome's system-wide status bar and edge panel. Both are drawn by the
 * AniHome controls accessibility service, so they need that service enabled; switching them
 * off (or uninstalling AniHome) leaves Android's own status bar untouched.
 */
internal object SystemBar {
    private fun prefs(context: Context) = context.getSharedPreferences("anihome", Context.MODE_PRIVATE)
    enum class Edge(val label: String) { TOP("Top"), LEFT("Left"), RIGHT("Right") }

    fun edgePanel(context: Context) = prefs(context).getBoolean("system_edge_panel", false)
    /** Retired: Android's own status bar is always kept. */
    @Suppress("UNUSED_PARAMETER") fun statusBar(context: Context) = false
    /** Over other apps the panel opens from the top only: side edges belong to Android's Back gesture. */
    @Suppress("UNUSED_PARAMETER") fun edge(context: Context) = Edge.TOP

    fun setEdgePanel(context: Context, on: Boolean) { prefs(context).edit().putBoolean("system_edge_panel", on).apply(); sync() }
    fun setStatusBar(context: Context, on: Boolean) { prefs(context).edit().putBoolean("system_status_bar", on).apply(); sync() }

    /**
     * AniHome's own full-screen surfaces (Home, its panels) hide Android's bar in ways Android 10
     * does not report to overlays, so they hide AniHome's bar explicitly while they are visible.
     */
    private val suppressors = mutableSetOf<String>()
    val suppressed: Boolean get() = suppressors.isNotEmpty()
    /** An AniHome panel is already on screen (over Home or over an app). */
    val panelOpen: Boolean get() = "panel" in suppressors
    fun suppress(token: String, on: Boolean) {
        val changed = if (on) suppressors.add(token) else suppressors.remove(token)
        if (changed) HomeControlsService.updateOverlayVisibility()
    }

    /** Every AniHome screen (Home, app drawer, artwork, history…) draws its own top area. */
    private var watching = false
    fun watch(application: android.app.Application) {
        if (watching) return
        watching = true
        application.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) = suppress("activity:${System.identityHashCode(activity)}", true)
            override fun onActivityPaused(activity: android.app.Activity) = suppress("activity:${System.identityHashCode(activity)}", false)
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) = Unit
            override fun onActivityStarted(activity: android.app.Activity) = Unit
            override fun onActivityStopped(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = suppress("activity:${System.identityHashCode(activity)}", false)
        })
    }

    /** Ask the running accessibility service (if enabled) to redraw its overlays. */
    fun sync() = HomeControlsService.refreshOverlays()
}
