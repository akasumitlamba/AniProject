package app.anihome.launcher.data

import android.content.Context

/** Hiding changes only drawer visibility. It never removes a Home tile or uninstalls an app. */
class DrawerPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("drawer", Context.MODE_PRIVATE)
    fun hidden(): Set<String> = preferences.getStringSet("hidden", emptySet())?.toSet().orEmpty()
    fun hide(key: String, hidden: Boolean) {
        val updated = hidden().toMutableSet()
        if (hidden) updated.add(key) else updated.remove(key)
        preferences.edit().putStringSet("hidden", updated).apply()
    }
}
