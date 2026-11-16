package app.anihome.launcher.banners

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Runs the site rules over the whole real catalog: every site finds itself, never another banner. */
class SiteMatcherCatalogTest {
    private val catalog: List<BannerEntry> by lazy {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "banners/catalog.json") }.first { it.exists() }
        @Suppress("UNCHECKED_CAST")
        val rows = (Json(file.readText()).value() as Map<String, Any?>)["entries"] as List<Map<String, Any?>>
        rows.map { row ->
            @Suppress("UNCHECKED_CAST") fun list(key: String) = (row[key] as? List<String>).orEmpty()
            BannerEntry(row["id"] as String, row["name"] as String, row["file"] as String, list("packages"), list("aliases"), list("webHosts"), row["sha256"] as String)
        }
    }

    /** Minimal JSON reader (Android's org.json is only a stub in JVM unit tests). */
    private class Json(private val s: String) {
        private var i = 0
        private fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? { skip(); return when (s[i]) {
            '{' -> { i++; val map = LinkedHashMap<String, Any?>(); skip(); if (s[i] == '}') { i++; return map }
                while (true) { skip(); val key = string(); skip(); i++; map[key] = value(); skip(); if (s[i++] == '}') return map } ; @Suppress("UNREACHABLE_CODE") map }
            '[' -> { i++; val list = ArrayList<Any?>(); skip(); if (s[i] == ']') { i++; return list }
                while (true) { list.add(value()); skip(); if (s[i++] == ']') return list } ; @Suppress("UNREACHABLE_CODE") list }
            '"' -> string()
            't' -> { i += 4; true }; 'f' -> { i += 5; false }; 'n' -> { i += 4; null }
            else -> { val start = i; while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++; s.substring(start, i).toDouble() }
        } }
        private fun string(): String { i++; val out = StringBuilder()
            while (s[i] != '"') { if (s[i] == '\\') { i++; when (s[i]) { 'n' -> out.append('\n'); 't' -> out.append('\t'); 'u' -> { out.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }; else -> out.append(s[i]) } } else out.append(s[i]); i++ }
            i++; return out.toString() }
    }

    @Test fun everyListedWebsiteFindsItsOwnBanner() {
        val wrong = catalog.flatMap { entry -> entry.webHosts.mapNotNull { host ->
            val found = SiteMatcher.match(catalog, "https://$host/", "")
            if (found != entry) "$host → ${found?.id}" else null
        } }
        assertTrue("Wrong website matches: $wrong", wrong.isEmpty())
    }

    @Test fun aNameNeverPicksAnotherBanner() {
        val wrong = catalog.flatMap { entry -> (listOf(entry.name) + entry.aliases).mapNotNull { name ->
            val found = SiteMatcher.match(catalog, null, "$name - Official site")
            if (found != null && found != entry) "$name → ${found.id} (expected ${entry.id})" else null
        } }
        assertTrue("Names matched the wrong banner: $wrong", wrong.isEmpty())
    }
}
