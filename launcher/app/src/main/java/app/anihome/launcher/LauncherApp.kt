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

    private fun processName(): String =
        if (android.os.Build.VERSION.SDK_INT >= 28) getProcessName()
        else runCatching { java.io.File("/proc/self/cmdline").readText().trim('\u0000', ' ') }.getOrDefault(packageName)

    override fun onCreate() {
        super.onCreate()
        val process = processName()
        if (process != packageName) {
            // Secondary processes (gesture navigation) only need their own WebView directory.
            if (android.os.Build.VERSION.SDK_INT >= 28) WebView.setDataDirectorySuffix(process)
            return
        }
        // Lets the navigation process receive Shizuku's binder from this (provider) process.
        rikka.shizuku.ShizukuProvider.enableMultiProcessSupport(true)
        scope.launch { app.anihome.launcher.data.InstalledApps.list(this@LauncherApp) }
        app.anihome.launcher.banners.BannerPrefetch.sweep(this)
        scope.launch {
            LauncherDb.get(this@LauncherApp).writableDatabase
            Seed.ensure(this@LauncherApp, TileStore(this@LauncherApp))
        }
    }
}
