package app.anihome.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import java.text.Collator

data class InstalledApp(
    val label: String,
    val packageName: String,
    val activityName: String,
) {
    val component get() = ComponentName(packageName, activityName)
}

/** Enumerates launchable activities exactly the way a stock launcher does. */
object InstalledApps {
    @Volatile private var snapshot: List<InstalledApp>? = null
    private var watching = false
    @Synchronized fun watch(context: Context) {
        if (watching) return
        val launcher = context.applicationContext.getSystemService(android.content.pm.LauncherApps::class.java)
        val callback = object : android.content.pm.LauncherApps.Callback() {
            private fun changed(pkg: String) { invalidate(); IconCache.invalidate(pkg); TileEvents.notifyChanged() }
            override fun onPackageAdded(packageName: String, user: android.os.UserHandle) = changed(packageName)
            override fun onPackageRemoved(packageName: String, user: android.os.UserHandle) = changed(packageName)
            override fun onPackageChanged(packageName: String, user: android.os.UserHandle) = changed(packageName)
            override fun onPackagesAvailable(packageNames: Array<out String>, user: android.os.UserHandle, replacing: Boolean) { packageNames.forEach(::changed) }
            override fun onPackagesUnavailable(packageNames: Array<out String>, user: android.os.UserHandle, replacing: Boolean) { packageNames.forEach(::changed) }
        }
        watching = runCatching { launcher.registerCallback(callback, android.os.Handler(android.os.Looper.getMainLooper())); true }.getOrDefault(false)
    }
    fun cached(): List<InstalledApp>? = snapshot
    @Synchronized fun invalidate() { snapshot = null }
    @Synchronized

    fun list(context: Context, refresh: Boolean = false): List<InstalledApp> {
        if (!refresh) snapshot?.let { return it }
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val collator = Collator.getInstance()
        return pm.queryIntentActivities(intent, 0)
            .asSequence()
            .filter { it.activityInfo.packageName != context.packageName }
            .map {
                InstalledApp(
                    label = it.loadLabel(pm).toString(),
                    packageName = it.activityInfo.packageName,
                    activityName = it.activityInfo.name,
                )
            }
            .distinctBy { it.packageName + "/" + it.activityName }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
            .toList().also { snapshot = it }
    }

    fun icon(context: Context, packageName: String, activityName: String?): Drawable? {
        val pm = context.packageManager
        return runCatching {
            if (activityName != null) pm.getActivityIcon(ComponentName(packageName, activityName))
            else pm.getApplicationIcon(packageName)
        }.getOrElse { runCatching { pm.getApplicationIcon(packageName) }.getOrNull() }
    }

    fun isInstalled(context: Context, packageName: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(packageName, 0).enabled
    }.getOrDefault(false)

    fun launchIntent(tile: HomeTile): Intent? {
        val pkg = tile.packageName ?: return null
        val activity = tile.activityName
        val intent = if (activity != null) {
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(ComponentName(pkg, activity))
        } else {
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
        }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }

    fun fallbackLaunchIntent(context: Context, packageName: String): Intent? =
        context.packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    @Suppress("unused")
    fun exists(pm: PackageManager, component: ComponentName) = runCatching { pm.getActivityInfo(component, 0) }.isSuccess
}
