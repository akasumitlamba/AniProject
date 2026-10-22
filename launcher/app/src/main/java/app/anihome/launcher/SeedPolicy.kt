package app.anihome.launcher

internal object SeedPolicy {
    fun installDefaults(version: Int, legacyCompleted: Boolean): Boolean = version == 0 && !legacyCompleted
}
