package app.anihome.launcher.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CrossSitePolicyTest {
    private val detection = AnimeDetection("one piece", "One Piece", episode = 3, season = null, host = "crunchyroll.com", url = "https://crunchyroll.com/x")
    private fun progress(host: String, max: Int) = SiteProgress(host, max, max, null, 0, 0, 0L)

    @Test
    fun promptsWhenAnotherSiteIsAhead() {
        val notice = CrossSitePolicy.decide(detection, listOf(progress("abc.site", 9)), thisHostMax = 0, muted = false)
        assertNotNull(notice)
        assertEquals("abc.site", notice!!.otherHost)
        assertEquals(9, notice.otherMaxEpisode)
        assertEquals(CrossSiteNotice.Relation.AHEAD_ELSEWHERE, notice.relation)
    }

    @Test
    fun silentWhenNoOtherSite() {
        assertNull(CrossSitePolicy.decide(detection, emptyList(), 0, false))
    }

    @Test
    fun silentWhenThisSiteAlreadyKnowsAsMuch() {
        assertNull(CrossSitePolicy.decide(detection, listOf(progress("abc.site", 9)), thisHostMax = 9, muted = false))
        assertNull(CrossSitePolicy.decide(detection, listOf(progress("abc.site", 9)), thisHostMax = 12, muted = false))
    }

    @Test
    fun silentWhenMuted() {
        assertNull(CrossSitePolicy.decide(detection, listOf(progress("abc.site", 9)), 0, muted = true))
    }

    @Test
    fun picksTheFurthestOtherSite() {
        val notice = CrossSitePolicy.decide(detection, listOf(progress("a.site", 4), progress("b.site", 11)), 0, false)!!
        assertEquals("b.site", notice.otherHost)
        assertEquals(11, notice.otherMaxEpisode)
    }

    @Test
    fun relationReflectsCurrentEpisode() {
        val same = CrossSitePolicy.decide(detection.copy(episode = 9), listOf(progress("a.site", 9)), 0, false)!!
        assertEquals(CrossSiteNotice.Relation.SAME_EPISODE, same.relation)
        val behind = CrossSitePolicy.decide(detection.copy(episode = 12), listOf(progress("a.site", 9)), 0, false)!!
        assertEquals(CrossSiteNotice.Relation.BEHIND_ELSEWHERE, behind.relation)
    }
}
