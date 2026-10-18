package app.anihome.launcher.ui

/** Parse the whole draft before any database write. Names are optional; one site per line. */
internal object BatchSites {
    data class Site(val url: String, val name: String?)
    data class Draft(val sites: List<Site>, val invalidLines: List<Int>)
    fun parse(text: String, normalize: (String) -> String?): Draft {
        val sites = linkedMapOf<String, Site>()
        val invalid = mutableListOf<Int>()
        text.lineSequence().forEachIndexed { index, line ->
            if (line.isBlank()) return@forEachIndexed
            val parts = line.split('|', limit = 2).map(String::trim)
            val url = normalize(parts.last())
            if (url == null) invalid += index + 1
            else sites.putIfAbsent(url.lowercase(java.util.Locale.ROOT), Site(url, if (parts.size == 2) parts.first().ifBlank { null } else null))
        }
        return Draft(sites.values.toList(), invalid)
    }
}
