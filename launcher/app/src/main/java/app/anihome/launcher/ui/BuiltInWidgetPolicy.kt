package app.anihome.launcher.ui

/** Legacy entries omit kind and retain their existing AppWidgetHost identity. */
internal object BuiltInWidgetPolicy {
    const val PROVIDER = "provider"
    const val CLOCK = "clock"
    const val BATTERY = "battery"
    const val NOTE = "note"
    val kinds = listOf(CLOCK, BATTERY, NOTE)
    fun isBuiltIn(kind: String) = kind in kinds
    fun isProvider(kind: String) = kind == PROVIDER
    fun usesProviderHost(kind: String, id: Int) = isProvider(kind) && id > 0
    fun nextId(existing: Collection<Int>): Int {
        val minimum = existing.filter { it < -1 }.minOrNull() ?: -1
        require(minimum > Int.MIN_VALUE) { "Widget identity space exhausted" }
        return minimum - 1
    }
    fun minimumColumns(kind: String) = if (isBuiltIn(kind)) 2 else 1
    fun minimumRows(kind: String) = if (kind == NOTE) 2 else if (isBuiltIn(kind)) 2 else 1
}
