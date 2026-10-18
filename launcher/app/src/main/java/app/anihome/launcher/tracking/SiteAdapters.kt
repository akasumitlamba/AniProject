package app.anihome.launcher.tracking

import java.net.URI

/**
 * Site-specific rules for pages whose generic title/URL shape is misleading.
 * Keep these small: the generic detector handles most streaming sites already.
 */
object SiteAdapters {
    fun detect(host: String, snapshot: PageSnapshot, uri: URI): AnimeDetection? = when {
        host.endsWith("crunchyroll.com") -> crunchyroll(host, snapshot)
        host.contains("anikoto") -> aniKoto(host, snapshot, uri)
        host.contains("hianime") || host.contains("aniwatch") || host.contains("zoro") || host.contains("animekai") ->
            hiAnimeFamily(host, snapshot, uri)
        host.contains("animepahe") -> animePahe(host, snapshot)
        host.contains("gogoanime") || host.contains("anitaku") -> gogo(host, snapshot, uri)
        host.contains("aniwave") || host.contains("9anime") -> aniwave(host, snapshot, uri)
        host.endsWith("youtube.com") || host == "youtu.be" -> youtube(host, snapshot)
        host.endsWith("netflix.com") -> netflix(host, snapshot)
        else -> null
    }

    /** "Watch Frieren: Beyond Journey's End Season 2 Episode 9 - Title | Crunchyroll" or "E9 - Title". */
    private fun crunchyroll(host: String, snapshot: PageSnapshot): AnimeDetection? {
        val heading = snapshot.heading
        val title = snapshot.title
        // Crunchyroll's player heading is usually "E9 - Episode Title"; the series name is the document title prefix.
        val episodeFromHeading = heading?.let { Regex("""^\s*(?:S(\d{1,2})\s*)?E(\d{1,4})\b""", RegexOption.IGNORE_CASE).find(it) }
        val textParse = title?.let { AnimeDetector.parseText(AnimeDetector.stripSiteSuffix(it)) }
        val episode = snapshot.domEpisode ?: episodeFromHeading?.groupValues?.get(2)?.toIntOrNull() ?: textParse?.episode ?: return null
        val seriesSource = snapshot.domTitle ?: textParse?.title?.clean ?: title?.let { AnimeDetector.stripSiteSuffix(it) }?.substringBefore(" Episode ")?.removePrefix("Watch ") ?: return null
        val name = TitleNormalizer.normalize(seriesSource, host) ?: return null
        val season = episodeFromHeading?.groupValues?.get(1)?.toIntOrNull() ?: textParse?.season ?: name.season
        return AnimeDetector.build(name, season, episode, host, snapshot.url)
    }

    /**
     * HiAnime / Zoro style: `/watch/one-piece-100?ep=2142` where `ep` is an internal id, not an
     * episode number. The real number only exists in the page (active episode button), so the
     * DOM probe is required; without it we refuse to guess.
     */
    private fun hiAnimeFamily(host: String, snapshot: PageSnapshot, uri: URI): AnimeDetection? {
        val episode = snapshot.domEpisode
            ?: snapshot.heading?.let { AnimeDetector.parseText(it)?.episode }
            ?: return null
        val slug = uri.path.orEmpty().trim('/').split('/').lastOrNull { it.isNotBlank() && it != "watch" } ?: return null
        val name = snapshot.domTitle?.let { TitleNormalizer.normalize(it, host) }
            ?: snapshot.heading?.let { TitleNormalizer.normalize(AnimeDetector.stripSiteSuffix(it), host) }
            ?: TitleNormalizer.normalize(AnimeDetector.slugToWords(slug), host)
            ?: return null
        return AnimeDetector.build(name, name.season, episode, host, snapshot.url)
    }

    /**
     * AniKoto (`anikototv.to`): `/watch/one-piece-odmau/ep-202`. The last path segment is the
     * real episode number (`ep-N`), unlike HiAnime's internal `?ep=` id. The series name is the
     * `h1.title.d-title` heading; the slug's trailing 4–6 character token is a site id.
     */
    private fun aniKoto(host: String, snapshot: PageSnapshot, uri: URI): AnimeDetection? {
        val segments = uri.path.orEmpty().trim('/').split('/').filter { it.isNotBlank() }
        val epSegment = segments.lastOrNull { Regex("""^ep-?\d{1,4}$""", RegexOption.IGNORE_CASE).matches(it) }
        val episode = snapshot.domEpisode
            ?: epSegment?.filter(Char::isDigit)?.toIntOrNull()
            ?: snapshot.heading?.let { AnimeDetector.parseText(it)?.episode }
            ?: snapshot.title?.let { AnimeDetector.parseText(AnimeDetector.stripSiteSuffix(it))?.episode }
            ?: return null
        val rawSlug = when {
            epSegment != null -> segments.getOrNull(segments.indexOf(epSegment) - 1)
            else -> segments.lastOrNull { it != "watch" && it != "anime" }
        }
        val name = snapshot.domTitle?.let { TitleNormalizer.normalize(it, host) }
            ?: snapshot.heading?.let { TitleNormalizer.normalize(AnimeDetector.stripSiteSuffix(it), host) }
            ?: snapshot.title?.let { AnimeDetector.parseText(AnimeDetector.stripSiteSuffix(it))?.title }
            ?: rawSlug?.let { TitleNormalizer.normalize(AnimeDetector.slugToWords(stripAniKotoId(it)), host) }
            ?: return null
        return AnimeDetector.build(name, name.season, episode, host, snapshot.url)
    }

    /** `one-piece-odmau` → `one-piece`; keep real words like `movie`. */
    internal fun stripAniKotoId(slug: String): String {
        val match = Regex("""^(.+)-([a-z0-9]{4,6})$""", RegexOption.IGNORE_CASE).find(slug) ?: return slug
        val name = match.groupValues[1]
        val token = match.groupValues[2].lowercase()
        if (token in setOf("movie", "final", "first", "part", "ova", "ona")) return slug
        return if (token.any(Char::isDigit) || name.length >= 8) name else slug
    }

    /** AnimePahe titles look like "One Piece - 1097 :: animepahe" or "Watch One Piece - 1097 Online". */
    private fun animePahe(host: String, snapshot: PageSnapshot): AnimeDetection? {
        val source = snapshot.heading ?: snapshot.title ?: return null
        val match = Regex("""^(?:watch\s+)?(.+?)\s+-\s+(\d{1,4})\b""", RegexOption.IGNORE_CASE).find(source)
        val episode = snapshot.domEpisode ?: match?.groupValues?.get(2)?.toIntOrNull() ?: return null
        val name = TitleNormalizer.normalize(match?.groupValues?.get(1) ?: source, host) ?: return null
        return AnimeDetector.build(name, name.season, episode, host, snapshot.url)
    }

    /** Gogoanime: `/one-piece-episode-9`, title "One Piece Episode 9 English Subbed at Gogoanime". */
    private fun gogo(host: String, snapshot: PageSnapshot, uri: URI): AnimeDetection? =
        AnimeDetector.generic(host, snapshot, uri)

    /** Aniwave/9anime: `/watch/one-piece.ov8/ep-9`. The dotted id suffix must not become part of the name. */
    private fun aniwave(host: String, snapshot: PageSnapshot, uri: URI): AnimeDetection? {
        val segments = uri.path.orEmpty().trim('/').split('/')
        val epSegment = segments.lastOrNull { Regex("""^ep-?\d{1,4}$""", RegexOption.IGNORE_CASE).matches(it) }
        val episode = snapshot.domEpisode ?: epSegment?.filter(Char::isDigit)?.toIntOrNull()
            ?: return AnimeDetector.generic(host, snapshot, uri)
        val slug = segments.getOrNull(segments.indexOf(epSegment) - 1)?.substringBefore('.') ?: segments.getOrNull(1)?.substringBefore('.')
        val name = snapshot.domTitle?.let { TitleNormalizer.normalize(it, host) }
            ?: snapshot.title?.let { AnimeDetector.parseText(AnimeDetector.stripSiteSuffix(it))?.title }
            ?: slug?.let { TitleNormalizer.normalize(AnimeDetector.slugToWords(it), host) }
            ?: return null
        return AnimeDetector.build(name, name.season, episode, host, snapshot.url)
    }

    /** YouTube only counts when the video title itself says "Episode N"; channel pages never do. */
    private fun youtube(host: String, snapshot: PageSnapshot): AnimeDetection? {
        if (!snapshot.url.contains("/watch") && !host.contains("youtu.be")) return null
        val title = snapshot.title?.removeSuffix(" - YouTube") ?: return null
        val parse = AnimeDetector.parseText(title) ?: return null
        return AnimeDetector.build(parse.title, parse.season, parse.episode, host, snapshot.url)
    }

    /** Netflix's player exposes "Title: Season 1: Episode Title (Episode 3)" style headings only via the DOM. */
    private fun netflix(host: String, snapshot: PageSnapshot): AnimeDetection? {
        val episode = snapshot.domEpisode ?: return null
        val name = snapshot.domTitle?.let { TitleNormalizer.normalize(it, host) } ?: return null
        return AnimeDetector.build(name, name.season, episode, host, snapshot.url)
    }
}
