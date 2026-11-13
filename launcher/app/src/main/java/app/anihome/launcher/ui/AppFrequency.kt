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
    fun recent(context: Context, apps: List<InstalledApp>): List<InstalledApp> {
        val prefs = preferences(context)
        return apps.filter { prefs.getLong("last:${it.packageName}/${it.activityName}", 0) > 0 }
            .sortedByDescending { prefs.getLong("last:${it.packageName}/${it.activityName}", 0) }.take(8)
    }
    fun rank(context: Context, apps: List<InstalledApp>, pinned: Set<String>): List<InstalledApp> {
        val prefs = preferences(context)
        fun InstalledApp.key() = "$packageName/$activityName"
        return apps.filter { prefs.getLong("count:${it.key()}", 0) > 0 || it.key() in pinned }
            .sortedWith(compareByDescending<InstalledApp> { prefs.getLong("count:${it.key()}", 0) }
                .thenByDescending { prefs.getLong("last:${it.key()}", 0) })
            .take(8)
    }
    /** Most recently opened launcher components, newest first, for the dock's "Recent apps" mode. */
    fun recentComponents(context: Context, limit: Int): List<android.content.ComponentName> {
        val prefs = preferences(context)
        val pm = context.packageManager
        return prefs.all.entries.asSequence()
            .filter { it.key.startsWith("last:") && it.value is Long }
            .sortedByDescending { it.value as Long }
            .mapNotNull { entry ->
                val key = entry.key.removePrefix("last:")
                val pkg = key.substringBefore('/'); val activity = key.substringAfter('/', "")
                if (pkg == context.packageName) return@mapNotNull null
                val launch = if (activity.isNotEmpty()) android.content.ComponentName(pkg, activity) else pm.getLaunchIntentForPackage(pkg)?.component
                launch?.takeIf { runCatching { pm.getActivityInfo(it, 0) }.isSuccess }
            }
            .distinctBy { it.packageName }
            .take(limit).toList()
    }
}
