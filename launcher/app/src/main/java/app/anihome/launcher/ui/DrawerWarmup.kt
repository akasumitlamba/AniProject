package app.anihome.launcher.ui

import android.content.Context
import app.anihome.launcher.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Data and artwork are warmed without retaining an Activity, View, or widget host. */
internal object DrawerWarmup {
    data class Snapshot(val apps: List<InstalledApp>, val tiles: List<HomeTile>, val files: List<RecentFiles.File>)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var snapshot: Snapshot? = null
    private var job: Job? = null
    fun current(): Snapshot? = snapshot?.takeIf { it.apps === InstalledApps.cached() }
    @Synchronized fun prepare(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            runCatching {
                val apps = InstalledApps.list(app)
                val initial = Snapshot(apps, TileStore(app).all(), snapshot?.files.orEmpty())
                snapshot = initial
                coroutineScope {
                    launch { runCatching { RecentFiles.read(app) }.onSuccess { files -> if (snapshot === initial) snapshot = initial.copy(files = files) } }
                    val workers = Semaphore(3)
                    apps.take(60).map { entry -> async {
                        workers.withPermit { IconCache.appIcon(app, entry.packageName, entry.activityName) }
                    } }.awaitAll()
                }
            }
        }
    }
}
