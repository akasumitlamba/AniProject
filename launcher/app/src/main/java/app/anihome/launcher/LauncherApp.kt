package app.anihome.launcher

import android.app.Application
import android.webkit.WebView
import app.anihome.launcher.data.LauncherDb
import app.anihome.launcher.data.TileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LauncherApp : Application() {
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            val process = getProcessName()
            if (process != packageName) WebView.setDataDirectorySuffix(process)
        }
        scope.launch { app.anihome.launcher.data.InstalledApps.list(this@LauncherApp) }
        scope.launch {
            LauncherDb.get(this@LauncherApp).writableDatabase
            Seed.ensure(this@LauncherApp, TileStore(this@LauncherApp))
        }
    }
}
