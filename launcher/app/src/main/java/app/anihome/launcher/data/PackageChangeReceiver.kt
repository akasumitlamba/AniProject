package app.anihome.launcher.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Drops tiles whose app was uninstalled and tells Home to refresh its artwork. */
class PackageChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.data?.schemeSpecificPart ?: return
        val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
        val pending = goAsync()
        scope.launch {
            try {
                if (intent.action == Intent.ACTION_PACKAGE_REMOVED && !replacing) {
                    TileStore(context).removePackage(packageName)
                }
                InstalledApps.invalidate()
                IconCache.invalidate(packageName)
                TileEvents.notifyChanged()
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }
}
