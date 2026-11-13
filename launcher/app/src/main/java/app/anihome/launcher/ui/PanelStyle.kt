package app.anihome.launcher.ui

import android.content.Context

/** Notification panel presentation choices, stored with Home's other preferences. */
internal object PanelStyle {
    /** Open panels listen so customisations show live while the user adjusts them. */
    val listeners = mutableSetOf<(String) -> Unit>()
    private fun changed(key: String) = listeners.toList().forEach { it(key) }
    private fun prefs(context: Context) = context.getSharedPreferences("anihome", Context.MODE_PRIVATE)
    /** Full-screen panel: controls on one side, notifications on the other. */
    fun wide(context: Context) = prefs(context).getString("panel_layout", "side") == "wide"
    fun setWide(context: Context, wide: Boolean) { prefs(context).edit().putString("panel_layout", if (wide) "wide" else "side").apply(); changed("panel_layout") }
    fun brightness(context: Context) = prefs(context).getBoolean("cc_show_brightness", true)
    fun volume(context: Context) = prefs(context).getBoolean("cc_show_volume", true)
    fun media(context: Context) = prefs(context).getBoolean("cc_show_media", true)
    fun set(context: Context, key: String, on: Boolean) { prefs(context).edit().putBoolean(key, on).apply(); changed(key) }
    /** Panel background opacity in percent (the rest shows the blur or what is behind). */
    fun opacity(context: Context) = prefs(context).getInt("panel_opacity", 82).coerceIn(35, 100)
    fun setOpacity(context: Context, value: Int) { prefs(context).edit().putInt("panel_opacity", value).apply(); changed("panel_opacity") }
    /** Background is either Apple-style blurred glass or solid; no fine-grained opacity. */
    fun blur(context: Context) = prefs(context).getBoolean("panel_blur", true)
}
