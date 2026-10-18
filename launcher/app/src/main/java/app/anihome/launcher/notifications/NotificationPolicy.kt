package app.anihome.launcher.notifications

/** Ongoing notifications stay; a group summary is redundant only when children are present. */
internal object NotificationPolicy {
    data class Entry(val key: String, val group: String?, val summary: Boolean, val clearable: Boolean)
    fun visible(items: List<Entry>): Set<String> {
        val childGroups = items.filter { !it.summary }.mapNotNull { it.group }.toSet()
        return items.filter { !it.summary || it.group !in childGroups }.map { it.key }.toSet()
    }
    /** Cancel a hidden summary along with its final dismissed child, avoiding a ghost group card. */
    fun dismissalTargets(items: List<Entry>, requested: Set<String>): List<String> {
        val targets = items.filter { it.clearable && it.key in requested }.map { it.key }.toMutableSet()
        items.filter { it.summary && it.clearable && it.group != null }.forEach { summary ->
            val children = items.filter { !it.summary && it.group == summary.group }
            if (children.isNotEmpty()) {
                if (children.all { it.key in targets }) targets.add(summary.key) else targets.remove(summary.key)
            }
        }
        return targets.toList()
    }
    fun dismissible(items: List<Entry>): List<String> = items.filter { it.clearable }.map { it.key }
}
