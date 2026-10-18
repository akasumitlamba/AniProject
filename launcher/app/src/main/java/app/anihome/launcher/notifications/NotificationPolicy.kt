package app.anihome.launcher.notifications

/** Ongoing notifications stay; a group summary is redundant only when children are present. */
internal object NotificationPolicy {
    data class Entry(val key: String, val group: String?, val summary: Boolean, val clearable: Boolean)
    fun visible(items: List<Entry>): Set<String> {
        val childGroups = items.filter { !it.summary }.mapNotNull { it.group }.toSet()
        return items.filter { !it.summary || it.group !in childGroups }.map { it.key }.toSet()
    }
    fun dismissible(items: List<Entry>): List<String> = items.filter { it.clearable }.map { it.key }
}
