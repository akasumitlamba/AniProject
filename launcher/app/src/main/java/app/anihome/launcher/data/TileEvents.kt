package app.anihome.launcher.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** In-process "tiles changed" signal so Home refreshes after background edits. */
object TileEvents {
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val flow = changes.asSharedFlow()
    fun notifyChanged() { changes.tryEmit(Unit) }
}
