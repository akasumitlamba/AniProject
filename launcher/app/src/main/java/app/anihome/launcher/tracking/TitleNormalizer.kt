package app.anihome.launcher.tracking

import java.text.Normalizer
import java.util.Locale

/**
 * Turns the many ways websites spell a series name into one comparable key.
 *
 * "Watch Frieren: Beyond Journey's End Season 2 Episode 9 English Sub - HiAnime"
 * and "Frieren: Beyond Journey's End 2nd Season | Crunchyroll" both become
 * `frieren beyond journeys end` with season 2.
 */
object TitleNormalizer {
    data class Result(val clean: String, val key: String, val season: Int?)

    private val noise = setOf(
        "watch", "online", "free", "english", "eng", "sub", "dub", "subbed", "dubbed", "hd", "stream",
        "streaming", "anime", "episodes", "full", "latest", "now", "tv", "the", "and", "in", "on", "at",
    )
    private val siteWords = setOf(
        "crunchyroll", "hianime", "aniwatch", "zoro", "gogoanime", "anitaku", "animepahe", "aniwave", "9anime",
        "kickassanime", "animesuge", "animixplay", "allanime", "animekai", "anikoto", "anikototv", "animeflix",
        "animeheaven", "animedao", "netflix", "hulu", "funimation", "youtube", "bilibili", "miruro", "yugenanime",
    )
    private val ordinals = mapOf(
        "1st" to 1, "2nd" to 2, "3rd" to 3, "4th" to 4, "5th" to 5, "6th" to 6, "7th" to 7, "8th" to 8, "9th" to 9,
        "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6,
        "final" to null,
    )
    private val romans = mapOf("ii" to 2, "iii" to 3, "iv" to 4, "v" to 5, "vi" to 6)

    fun normalize(raw: String, host: String? = null): Result? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        text = Normalizer.normalize(text, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")
        text = text.replace('’', '\'').replace('–', '-').replace('—', '-')

        var season: Int? = null
        fun capture(regex: Regex, pick: (MatchResult) -> Int?) {
            val match = regex.find(text) ?: return
            if (season == null) season = pick(match)
            text = text.removeRange(match.range)
        }
        capture(Regex("""\bseason\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE)) { it.groupValues[1].toIntOrNull() }
        capture(Regex("""\b(\d{1,2})(?:st|nd|rd|th)\s+season\b""", RegexOption.IGNORE_CASE)) { it.groupValues[1].toIntOrNull() }
        capture(Regex("""\b(first|second|third|fourth|fifth|sixth|final)\s+season\b""", RegexOption.IGNORE_CASE)) { ordinals[it.groupValues[1].lowercase(Locale.ROOT)] }
        capture(Regex("""\bs(\d{1,2})\b""", RegexOption.IGNORE_CASE)) { it.groupValues[1].toIntOrNull() }
        capture(Regex("""\bpart\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE)) { null }
        capture(Regex("""\bcour\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE)) { null }

        text = text.replace(Regex("""\((?:dub|sub|dubbed|subbed|uncensored|tv|ona|ova)\)""", RegexOption.IGNORE_CASE), " ")
        text = text.replace(Regex("""\[[^\]]*]"""), " ")
        text = text.replace(Regex("""['`]"""), "")
        text = text.replace(Regex("""[^\p{L}\p{N}]+"""), " ").lowercase(Locale.ROOT).trim()

        val words = text.split(' ').filter { it.isNotBlank() }.toMutableList()
        if (words.isNotEmpty() && season == null) {
            romans[words.last()]?.let { season = it; words.removeAt(words.lastIndex) }
        }
        val hostWord = host?.substringBefore('.')?.lowercase(Locale.ROOT)
        val kept = words.filterIndexed { index, word ->
            when {
                word in siteWords || word == hostWord -> false
                word in noise && !(index == 0 && words.size == 1) -> false
                else -> true
            }
        }
        if (kept.isEmpty()) return null
        val key = kept.joinToString(" ").take(80)
        if (key.length < 2 || kept.all { it.all(Char::isDigit) }) return null
        val clean = pretty(kept)
        return Result(clean, if (season != null && season!! > 1) "$key|s$season" else key, season)
    }

    private fun pretty(words: List<String>) = words.joinToString(" ") { w ->
        if (w.length <= 2 && w.all(Char::isDigit)) w else w.replaceFirstChar { it.titlecase(Locale.ROOT) }
    }
}
