package app.anihome.launcher.tracking

import java.net.URI
import java.util.Locale

/**
 * Pure (no Android, no I/O) recogniser for "which anime, which episode" from a page snapshot.
 *
 * Order of trust: a site-specific adapter, then an episode number read from the page DOM,
 * then the page title/heading, then the URL. Series names from the title/heading beat
 * names reconstructed from URL slugs.
 */
object AnimeDetector {
    private val skipHosts = listOf(
        "google.", "bing.com", "duckduckgo.com", "yahoo.", "wikipedia.org", "reddit.com", "github.com",
        "myanimelist.net", "anilist.co", "x.com", "twitter.com", "facebook.com", "instagram.com",
    )

    fun detect(snapshot: PageSnapshot): AnimeDetection? {
        val uri = runCatching { URI(snapshot.url) }.getOrNull() ?: return null
        if (uri.scheme != "http" && uri.scheme != "https") return null
        val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.")?.removePrefix("m.") ?: return null
        if (skipHosts.any { host == it.trimEnd('.') || host.contains(it) }) return null

        SiteAdapters.detect(host, snapshot, uri)?.let { return it }
        return generic(host, snapshot, uri)
    }

    internal fun generic(host: String, snapshot: PageSnapshot, uri: URI): AnimeDetection? {
        // Try each text with site branding stripped first, then raw ("Episode 12 - Vinland Saga" has no branding).
        val textSources = listOfNotNull(snapshot.heading, snapshot.title).flatMap { listOf(stripSiteSuffix(it), it) }.distinct()
        val fromText = textSources.firstNotNullOfOrNull { parseText(it) }
        val fromUrl = parseUrl(uri)
        val fromDomTitle = snapshot.domTitle?.let { TitleNormalizer.normalize(it, host) }

        val episode = snapshot.domEpisode ?: fromText?.episode ?: fromUrl?.episode ?: return null
        val name = fromDomTitle ?: fromText?.title ?: fromUrl?.title ?: textSources.firstNotNullOfOrNull { TitleNormalizer.normalize(stripEpisodeWords(it), host) } ?: return null
        val season = fromText?.season ?: name.season
        return build(name, season, episode, host, snapshot.url)
    }

    internal fun build(name: TitleNormalizer.Result, season: Int?, episode: Int, host: String, url: String): AnimeDetection? {
        if (episode <= 0 || episode > 5000) return null
        val key = if (season != null && season > 1 && !name.key.contains("|s")) "${name.key}|s$season" else name.key
        return AnimeDetection(key = key, title = name.clean, episode = episode, season = season ?: name.season, host = host, url = url)
    }

    internal data class TextParse(val title: TitleNormalizer.Result, val episode: Int, val season: Int?)

    private val episodeInText = Regex(
        """^(?<pre>.*?)(?:[-–|:·]\s*)?(?:season\s*(?<s1>\d{1,2})\s*[,\-–]?\s*)?\b(?:episode|ep\.?|e)\s*(?<ep>\d{1,4})(?!\d)(?<post>.*)$""",
        RegexOption.IGNORE_CASE,
    )
    private val sxe = Regex("""^(?<pre>.*?)\bs(?<s>\d{1,2})\s*[-\s]?e(?<ep>\d{1,4})\b(?<post>.*)$""", RegexOption.IGNORE_CASE)
    private val nXn = Regex("""^(?<pre>.*?)\b(?<s>\d{1,2})x(?<ep>\d{1,3})\b(?<post>.*)$""", RegexOption.IGNORE_CASE)
    private val dashNumber = Regex("""^(?<pre>.+?)\s[-–]\s(?<ep>\d{1,4})(?:\s|$)(?<post>.*)$""")

    internal fun parseText(raw: String): TextParse? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        episodeInText.find(text)?.let { m ->
            val ep = m.groups["ep"]!!.value.toInt()
            val pre = m.groups["pre"]!!.value
            val post = m.groups["post"]!!.value
            val seasonInline = m.groups["s1"]?.value?.toIntOrNull()
            val titleSource = if (pre.isNotBlank()) pre else post.trimStart(' ', '-', '–', ':', '|')
            val name = TitleNormalizer.normalize(firstSegment(titleSource)) ?: return@let
            return TextParse(name, ep, seasonInline ?: name.season)
        }
        sxe.find(text)?.let { m ->
            val name = TitleNormalizer.normalize(firstSegment(m.groups["pre"]!!.value)) ?: return@let
            return TextParse(name, m.groups["ep"]!!.value.toInt(), m.groups["s"]!!.value.toInt())
        }
        nXn.find(text)?.let { m ->
            val name = TitleNormalizer.normalize(firstSegment(m.groups["pre"]!!.value)) ?: return@let
            return TextParse(name, m.groups["ep"]!!.value.toInt(), m.groups["s"]!!.value.toInt())
        }
        dashNumber.find(text)?.let { m ->
            val name = TitleNormalizer.normalize(firstSegment(m.groups["pre"]!!.value)) ?: return@let
            return TextParse(name, m.groups["ep"]!!.value.toInt(), name.season)
        }
        return null
    }

    private val slugEpisode = Regex("""(?:^|[-_/.])(?:episode|episodio|ep|e)[-_]?(\d{1,4})(?=$|[^0-9])""", RegexOption.IGNORE_CASE)

    internal fun parseUrl(uri: URI): TextParse? {
        val path = (uri.path ?: "").trim('/')
        val segments = path.split('/').filter { it.isNotBlank() }
        for ((index, segment) in segments.withIndex().reversed()) {
            val match = slugEpisode.find(segment) ?: continue
            val ep = match.groupValues[1].toInt()
            val before = segment.substring(0, match.range.first).trim('-', '_')
            val slug = if (before.length >= 3) before else segments.getOrNull(index - 1).orEmpty()
            val name = TitleNormalizer.normalize(slugToWords(slug)) ?: continue
            return TextParse(name, ep, name.season)
        }
        val query = parseQuery(uri.rawQuery)
        val ep = listOf("episode", "episode_number", "epnum", "ep_num", "number").firstNotNullOfOrNull { query[it]?.toIntOrNull() }
            ?: query["ep"]?.toIntOrNull()?.takeIf { it in 1..999 }
        if (ep != null) {
            val slug = segments.lastOrNull { it.length >= 3 && !it.all(Char::isDigit) && it != "watch" && it != "anime" } ?: return null
            val name = TitleNormalizer.normalize(slugToWords(slug)) ?: return null
            return TextParse(name, ep, name.season)
        }
        return null
    }

    internal fun parseQuery(raw: String?): Map<String, String> = raw.orEmpty().split('&')
        .mapNotNull { pair -> pair.substringBefore('=').takeIf { it.isNotEmpty() }?.let { it.lowercase(Locale.ROOT) to pair.substringAfter('=', "") } }
        .toMap()

    internal fun slugToWords(slug: String): String = slug
        .replace(Regex("""[-_.]+"""), " ")
        .replace(Regex("""\b\d{3,}\b$"""), "")
        .replace(Regex("""\b[a-z0-9]{3,6}$""")) { if (it.value.any(Char::isDigit) && it.value.any(Char::isLetter)) "" else it.value }
        .trim()

    /** Removes " - HiAnime", " | Crunchyroll", "at Gogoanime" style trailing site branding. */
    internal fun stripSiteSuffix(text: String): String {
        var t = text.trim()
        t = t.replace(Regex("""\s+(?:at|on)\s+[A-Za-z0-9.]+\s*$""", RegexOption.IGNORE_CASE), "")
        val parts = t.split(Regex("""\s+[|•]\s+|\s+-\s+(?=[^-]*$)|\s+::\s+"""))
        return if (parts.size > 1 && parts.last().length <= 28 && !Regex("""\d""").containsMatchIn(parts.last())) parts.dropLast(1).joinToString(" - ") else t
    }

    private fun stripEpisodeWords(text: String) = text.replace(Regex("""\b(?:episode|ep)\.?\s*\d+\b""", RegexOption.IGNORE_CASE), " ")

    private fun firstSegment(text: String): String = text.split(Regex("""\s+\|\s+|\s+::\s+""")).first()
}
