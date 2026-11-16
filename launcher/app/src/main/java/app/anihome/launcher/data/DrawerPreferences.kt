package app.anihome.launcher.data

import android.content.Context

/** Hiding changes only drawer visibility. It never removes a Home tile or uninstalls an app. */
class DrawerPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("drawer", Context.MODE_PRIVATE)
    fun opacity(): Int = preferences.getInt("background_opacity", 100).coerceIn(0, 100)
    fun setOpacity(value: Int) { preferences.edit().putInt("background_opacity", value.coerceIn(0, 100)).apply() }
    fun showRecentFiles(): Boolean = preferences.getBoolean("show_recent_files", true)
    fun setShowRecentFiles(show: Boolean) { preferences.edit().putBoolean("show_recent_files", show).apply() }
    fun showRecentApps(): Boolean = preferences.getBoolean("show_recent_apps", true)
    fun setShowRecentApps(show: Boolean) { preferences.edit().putBoolean("show_recent_apps", show).apply() }
    /** "grid" (default) or "list": icon and name on one line. Recent apps stay a row of icons. */
    fun listView(): Boolean = preferences.getString("app_layout", "grid") == "list"
    fun setListView(list: Boolean) { preferences.edit().putString("app_layout", if (list) "list" else "grid").apply() }
    fun hidden(): Set<String> = preferences.getStringSet("hidden", emptySet())?.toSet().orEmpty()
    fun hide(key: String, hidden: Boolean) {
        val updated = hidden().toMutableSet()
        if (hidden) updated.add(key) else updated.remove(key)
        preferences.edit().putStringSet("hidden", updated).apply()
    }
}
