package app.anihome.launcher.ui

/** Main-thread operation token: late asynchronous replies must not replace newer events. */
internal class OperationRevision {
    private var revision = 0L
    fun snapshot(): Long = revision
    fun invalidate(): Long = ++revision
    fun isCurrent(token: Long): Boolean = token == revision
}
