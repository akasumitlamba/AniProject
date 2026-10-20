package app.anihome.launcher.ui

import android.content.ComponentName
import android.content.Context
import org.json.JSONArray

/** Ordered, explicit pins. Uses launcher components, never usage-access or background tracking. */
internal object DockApps {
    private fun prefs(context: Context) = context.getSharedPreferences("anihome", Context.MODE_PRIVATE)
    fun pins(context: Context): List<ComponentName> = runCatching {
        val array = JSONArray(prefs(context).getString("dock_apps", "[]"))
        (0 until array.length()).mapNotNull { ComponentName.unflattenFromString(array.getString(it)) }.distinct().take(5)
    }.getOrDefault(emptyList())
    fun set(context: Context, apps: List<ComponentName>) {
        prefs(context).edit().putString("dock_apps", JSONArray(apps.distinct().take(5).map { it.flattenToString() }).toString()).apply()
    }
    fun toggle(context: Context, app: ComponentName) {
        val pins = pins(context).toMutableList()
        if (!pins.remove(app) && pins.size < 5) pins.add(app)
        set(context, pins)
    }
}
