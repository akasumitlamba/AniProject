package app.anihome.launcher.ui

import org.json.JSONArray
import org.json.JSONObject

/**
 * The websites on Home as a small JSON file, to move them to another device (or back them up).
 * Import also accepts a plain list of addresses, one per line.
 */
internal object SiteTransfer {
    const val FORMAT = "anihome-sites"

    fun export(sites: List<Pair<String, String>>): String = JSONObject()
        .put("format", FORMAT).put("version", 1)
        .put("sites", JSONArray().apply { sites.forEach { (name, url) -> put(JSONObject().put("name", name).put("url", url)) } })
        .toString(2)

    /** Name (may be null) and address of each site in the file; anything unreadable is skipped. */
    fun parse(text: String, normalize: (String) -> String?): List<BatchSites.Site> {
        val trimmed = text.trim()
        if (trimmed.startsWith("{")) {
            val array = runCatching { JSONObject(trimmed).optJSONArray("sites") }.getOrNull() ?: return emptyList()
            return (0 until minOf(array.length(), 500)).mapNotNull { i ->
                val row = array.optJSONObject(i) ?: return@mapNotNull null
                val url = normalize(row.optString("url")) ?: return@mapNotNull null
                BatchSites.Site(url, row.optString("name").trim().take(200).ifEmpty { null })
            }.distinctBy { it.url }
        }
        return BatchSites.parse(trimmed.take(100_000), normalize).sites
    }
}
