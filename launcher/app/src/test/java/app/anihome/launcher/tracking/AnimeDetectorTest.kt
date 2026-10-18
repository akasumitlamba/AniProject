package app.anihome.launcher.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AnimeDetectorTest {
    private fun detect(url: String, title: String? = null, heading: String? = null, domEpisode: Int? = null, domTitle: String? = null) =
        AnimeDetector.detect(PageSnapshot(url, title, heading, domEpisode, domTitle))

    @Test
    fun gogoanimeSlugAndTitle() {
        val d = detect("https://anitaku.to/one-piece-episode-9", "One Piece Episode 9 English Subbed at Gogoanime")
        assertNotNull(d)
        assertEquals("one piece", d!!.key)
        assertEquals(9, d.episode)
        assertEquals("anitaku.to", d.host)
    }

    @Test
    fun hiAnimeNeedsDomEpisodeBecauseQueryIsAnId() {
        val url = "https://hianime.to/watch/one-piece-100?ep=2142"
        assertNull(detect(url, "Watch One Piece English Sub/Dub online free on HiAnime.to"))
        val d = detect(url, "Watch One Piece English Sub/Dub online free on HiAnime.to", domEpisode = 9, domTitle = "One Piece")
        assertEquals("one piece", d!!.key)
        assertEquals(9, d.episode)
        assertEquals("hianime.to", d.host)
    }

    @Test
    fun hiAnimeMirrorUsesDomEpisode() {
        val d = detect(
            "https://hianimez.to/watch/frieren-beyond-journeys-end-18542?ep=99",
            heading = "Frieren: Beyond Journey's End",
            domEpisode = 12,
            domTitle = "Frieren: Beyond Journey's End",
        )
        assertEquals("frieren beyond journeys end", d!!.key)
        assertEquals(12, d.episode)
    }

    @Test
    fun aniKotoEpisodeFromUrlPath() {
        val d = detect(
            "https://anikototv.to/watch/one-piece-odmau/ep-202",
            "Anime One Piece Episode 202 Watch Online Free - Anikoto",
            heading = "One Piece",
            domTitle = "One Piece",
        )
        assertEquals("one piece", d!!.key)
        assertEquals(202, d.episode)
        assertEquals("anikototv.to", d.host)
    }

    @Test
    fun aniKotoEpisodeFromUrlWithoutDom() {
        val d = detect("https://anikototv.to/watch/one-piece-odmau/ep-202", "Player")
        assertEquals("one piece", d!!.key)
        assertEquals(202, d.episode)
    }

    @Test
    fun aniKotoDomEpisodeOverridesStaleTitle() {
        val d = detect(
            "https://anikototv.to/watch/solo-leveling-ilh08/ep-1",
            "Anime Solo Leveling Episode 1 Watch Online Free - Anikoto",
            heading = "Solo Leveling",
            domEpisode = 8,
            domTitle = "Solo Leveling",
        )
        assertEquals("solo leveling", d!!.key)
        assertEquals(8, d.episode)
    }

    @Test
    fun crunchyrollUsesHeadingEpisodeWhenTitleOmitsNumber() {
        val d = detect(
            "https://www.crunchyroll.com/watch/GRDQPM1ZY/the-mages-trial",
            "Frieren: Beyond Journey's End - Watch on Crunchyroll",
            heading = "S2 E9 - The Mage's Trial",
            domTitle = "Frieren: Beyond Journey's End",
        )
        assertEquals("frieren beyond journeys end|s2", d!!.key)
        assertEquals(9, d.episode)
        assertEquals(2, d.season)
    }

    @Test
    fun crunchyrollSeasonAndEpisodeFromTitle() {
        val d = detect(
            "https://www.crunchyroll.com/watch/GRDQPM1ZY/frieren",
            "Watch Frieren: Beyond Journey's End Season 2 Episode 9 - Crunchyroll",
            heading = "E9 - The Mage's Trial",
        )
        assertNotNull(d)
        assertEquals("frieren beyond journeys end|s2", d!!.key)
        assertEquals(9, d.episode)
        assertEquals(2, d.season)
    }

    @Test
    fun sameAnimeMatchesAcrossSitesDespiteDifferentWording() {
        val a = detect("https://hianime.to/watch/frieren-beyond-journeys-end-18542?ep=1", "Watch Frieren: Beyond Journey's End English Sub - HiAnime", domEpisode = 9, domTitle = "Frieren: Beyond Journey's End")
        val b = detect("https://www.crunchyroll.com/watch/X/y", "Watch Frieren: Beyond Journey’s End Episode 9 - Crunchyroll")
        assertEquals(a!!.key, b!!.key)
    }

    @Test
    fun aniwaveStyleSlugWithIdSuffix() {
        val d = detect("https://aniwave.to/watch/one-piece.ov8/ep-1097", "Watch One Piece Episode 1097 Online Free - Aniwave")
        assertEquals("one piece", d!!.key)
        assertEquals(1097, d.episode)
    }

    @Test
    fun animePaheDashNumber() {
        val d = detect("https://animepahe.ru/play/abc/def", "One Piece - 1097 :: animepahe")
        assertEquals("one piece", d!!.key)
        assertEquals(1097, d.episode)
    }

    @Test
    fun seasonInRomanNumeralsAndOrdinals() {
        val a = detect("https://example.site/watch/mob-psycho-100-iii-episode-5", "Mob Psycho 100 III Episode 5")
        val b = detect("https://other.site/watch/x", "Mob Psycho 100 3rd Season Episode 5 English Sub")
        assertEquals("mob psycho 100|s3", a!!.key)
        assertEquals(a.key, b!!.key)
    }

    @Test
    fun sxeFormat() {
        val d = detect("https://example.site/play/1", "Spy x Family S2E7 - Title")
        assertEquals("spy x family|s2", d!!.key)
        assertEquals(7, d.episode)
    }

    @Test
    fun urlOnlyFallback() {
        val d = detect("https://example.site/anime/jujutsu-kaisen-season-2/episode-18", "Player")
        assertEquals("jujutsu kaisen|s2", d!!.key)
        assertEquals(18, d.episode)
    }

    @Test
    fun ignoresNonEpisodePages() {
        assertNull(detect("https://www.google.com/search?q=one+piece+episode+9", "one piece episode 9 - Google Search"))
        assertNull(detect("https://example.site/", "Welcome to Example"))
        assertNull(detect("https://shop.site/iphone-15", "iPhone 15 - Shop"))
        assertNull(detect("https://www.youtube.com/", "YouTube"))
    }

    @Test
    fun youtubeOnlyWhenTitleSaysEpisode() {
        val d = detect("https://www.youtube.com/watch?v=abc", "Naruto Episode 3 (Official) - YouTube")
        assertEquals("naruto", d!!.key)
        assertEquals(3, d.episode)
        assertNull(detect("https://www.youtube.com/watch?v=abc", "Top 10 anime of 2024 - YouTube"))
    }

    @Test
    fun episodeNumberBeforeTitle() {
        val d = detect("https://example.site/v/1", "Episode 12 - Vinland Saga")
        assertEquals("vinland saga", d!!.key)
        assertEquals(12, d.episode)
    }
}
