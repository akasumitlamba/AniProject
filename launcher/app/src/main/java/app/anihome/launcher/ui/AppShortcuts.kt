package app.anihome.launcher.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.os.Process
import android.view.View
import android.widget.Toast

/**
 * App shortcuts ("New chat", "Compose"…) published by apps for the default launcher.
 * Android only grants shortcut access to the current default home app; otherwise the
 * menu simply omits them.
 */
internal object AppShortcuts {
    private const val LIMIT = 4

    fun menuItems(context: Context, packageName: String?, activity: ComponentName?): List<HomeMenu.Item> {
        if (packageName.isNullOrEmpty()) return emptyList()
        val apps = context.getSystemService(LauncherApps::class.java) ?: return emptyList()
        val shortcuts = runCatching {
            if (!apps.hasShortcutHostPermission()) return emptyList()
            val query = LauncherApps.ShortcutQuery()
                .setPackage(packageName)
                .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST)
            if (activity != null) query.setActivity(activity)
            apps.getShortcuts(query, Process.myUserHandle()).orEmpty()
        }.getOrDefault(emptyList())
        // Manifest shortcuts first, then dynamic ones, each in the app's own rank order.
        return shortcuts.filter { it.isEnabled }
            .sortedWith(compareBy<ShortcutInfo>({ if (it.isDeclaredInManifest) 0 else 1 }, { it.rank }))
            .take(LIMIT)
            .mapIndexed { index, info ->
                val label = (info.shortLabel ?: info.longLabel ?: "Shortcut").toString()
                val icon = runCatching { apps.getShortcutIconDrawable(info, context.resources.displayMetrics.densityDpi) }.getOrNull()
                HomeMenu.Item(100 + index, label, 0, drawable = icon) { start(context, info, null) }
            }
    }

    fun start(context: Context, info: ShortcutInfo, anchor: View?) {
        val apps = context.getSystemService(LauncherApps::class.java) ?: return
        val bounds = anchor?.let { view -> IntArray(2).also(view::getLocationOnScreen).let { Rect(it[0], it[1], it[0] + view.width, it[1] + view.height) } }
        runCatching { apps.startShortcut(info, bounds, null) }
            .onFailure { Toast.makeText(context, "That shortcut is no longer available", Toast.LENGTH_SHORT).show() }
    }
}
