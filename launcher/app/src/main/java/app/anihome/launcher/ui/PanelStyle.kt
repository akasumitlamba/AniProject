package app.anihome.launcher.ui

import android.content.Context

/** Notification panel presentation choices, stored with Home's other preferences. */
internal object PanelStyle {
    /** Open panels listen so customisations show live while the user adjusts them. */
    val listeners = mutableSetOf<(String) -> Unit>()
    private fun changed(key: String) = listeners.toList().forEach { it(key) }
    private fun prefs(context: Context) = context.getSharedPreferences("anihome", Context.MODE_PRIVATE)
    /** Full-screen panel: controls on one side, notifications on the other. */
    fun wide(context: Context) = !phone(context) && prefs(context).getString("panel_layout", "side") == "wide"
    /** Phones already fill the screen with the single-column panel; the two-column tablet layout doesn't fit. */
    fun phone(context: Context) = context.resources.configuration.smallestScreenWidthDp < 600
    fun setWide(context: Context, wide: Boolean) { prefs(context).edit().putString("panel_layout", if (wide) "wide" else "side").apply(); changed("panel_layout") }
    /** Off: Android's own notification shade and status bar everywhere, including on Home. */
    fun custom(context: Context) = prefs(context).getBoolean("panel_custom", true)
    fun setCustom(context: Context, on: Boolean) { prefs(context).edit().putBoolean("panel_custom", on).apply(); changed("panel_custom") }
    fun brightness(context: Context) = prefs(context).getBoolean("cc_show_brightness", true)
    fun volume(context: Context) = prefs(context).getBoolean("cc_show_volume", true)
    fun media(context: Context) = prefs(context).getBoolean("cc_show_media", true)
    fun set(context: Context, key: String, on: Boolean) { prefs(context).edit().putBoolean(key, on).apply(); changed(key) }
    /** Glass opacity in percent over a real blur; unblurred panels stay nearly solid. */
    fun opacity(context: Context) = prefs(context).getInt("panel_opacity", 88).coerceIn(60, 100)
    fun setOpacity(context: Context, value: Int) { prefs(context).edit().putInt("panel_opacity", value).apply(); changed("panel_opacity") }
    /** Background is either Apple-style blurred glass or solid; no fine-grained opacity. */
    fun blur(context: Context) = prefs(context).getBoolean("panel_blur", true)
}
