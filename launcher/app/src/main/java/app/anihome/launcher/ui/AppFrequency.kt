package app.anihome.launcher.ui

import android.content.Context
import app.anihome.launcher.data.InstalledApp

/** Counts only apps explicitly opened through AniHome; no system usage-access permission. */
internal object AppFrequency {
    private fun preferences(context: Context) = context.getSharedPreferences("app_frequency", Context.MODE_PRIVATE)
    fun record(context: Context, packageName: String, activityName: String?) {
        val key = "$packageName/${activityName.orEmpty()}"
        val prefs = preferences(context)
        prefs.edit().putLong("count:$key", prefs.getLong("count:$key", 0) + 1)
            .putLong("last:$key", System.currentTimeMillis()).apply()
    }
    fun rank(context: Context, apps: List<InstalledApp>, pinned: Set<String>): List<InstalledApp> {
        val prefs = preferences(context)
        fun InstalledApp.key() = "$packageName/$activityName"
        return apps.filter { prefs.getLong("count:${it.key()}", 0) > 0 || it.key() in pinned }
            .sortedWith(compareByDescending<InstalledApp> { prefs.getLong("count:${it.key()}", 0) }
                .thenByDescending { prefs.getLong("last:${it.key()}", 0) })
            .take(8)
    }
}
