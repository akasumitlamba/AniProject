package app.anihome.launcher.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TitleNormalizerTest {
    @Test
    fun stripsNoiseAndPunctuation() {
        val r = TitleNormalizer.normalize("Watch Frieren: Beyond Journey's End English Sub Online Free", "hianime.to")!!
        assertEquals("frieren beyond journeys end", r.key)
        assertEquals("Frieren Beyond Journeys End", r.clean)
        assertNull(r.season)
    }

    @Test
    fun seasonForms() {
        // "Final Season" carries no number, so it only strips the words.
        assertEquals("attack titan", TitleNormalizer.normalize("Attack on Titan Final Season Part 2")!!.key)
        assertEquals(2, TitleNormalizer.normalize("Frieren 2nd Season")!!.season)
        assertEquals(2, TitleNormalizer.normalize("Frieren Season 2")!!.season)
        assertEquals(2, TitleNormalizer.normalize("Frieren S2")!!.season)
        assertEquals(3, TitleNormalizer.normalize("Mob Psycho 100 III")!!.season)
        assertEquals("frieren|s2", TitleNormalizer.normalize("Frieren Season 2")!!.key)
        assertEquals("frieren", TitleNormalizer.normalize("Frieren Season 1")!!.key)
    }

    @Test
    fun dropsHostBrandWords() {
        assertEquals("one piece", TitleNormalizer.normalize("One Piece HiAnime", "hianime.to")!!.key)
        assertEquals("one piece", TitleNormalizer.normalize("One Piece | Crunchyroll", "www.crunchyroll.com")!!.key)
    }

    @Test
    fun rejectsEmptyOrNumericOnly() {
        assertNull(TitleNormalizer.normalize("Watch Online Free"))
        assertNull(TitleNormalizer.normalize("123"))
        assertNull(TitleNormalizer.normalize(""))
    }
}
