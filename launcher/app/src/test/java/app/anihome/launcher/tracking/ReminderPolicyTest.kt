package app.anihome.launcher.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderPolicyTest {
    @Test
    fun showsWhenNotDismissed() {
        assertTrue(ReminderPolicy.shouldShow(9, dismissed = false))
        assertFalse(ReminderPolicy.shouldShow(9, dismissed = true))
        assertFalse(ReminderPolicy.shouldShow(0, dismissed = false))
    }

    @Test
    fun sameEpisodeKeepsDismiss() {
        assertTrue(ReminderPolicy.stillDismissed(wasDismissed = true, previousEpisode = 9, watchedEpisode = 9))
    }

    @Test
    fun anyOtherEpisodeClearsDismiss() {
        assertFalse(ReminderPolicy.stillDismissed(wasDismissed = true, previousEpisode = 9, watchedEpisode = 10))
        assertFalse(ReminderPolicy.stillDismissed(wasDismissed = true, previousEpisode = 9, watchedEpisode = 3))
        assertFalse(ReminderPolicy.stillDismissed(wasDismissed = true, previousEpisode = 9, watchedEpisode = 1))
    }

    @Test
    fun notDismissedStaysVisible() {
        assertFalse(ReminderPolicy.stillDismissed(wasDismissed = false, previousEpisode = 9, watchedEpisode = 9))
        assertFalse(ReminderPolicy.stillDismissed(wasDismissed = false, previousEpisode = 9, watchedEpisode = 10))
    }
}

class WatchLogPolicyTest {
    private val now = 1_000_000_000_000L

    @Test
    fun firstWatchIsANewEntry() {
        assertTrue(WatchLogPolicy.shouldAppend(null, 5, "hianime.to", now))
    }

    @Test
    fun sameSittingSameEpisodeUpdates() {
        val last = WatchLogPolicy.LastEntry(5, "hianime.to", now)
        assertFalse(WatchLogPolicy.shouldAppend(last, 5, "hianime.to", now + 60_000))
    }

    @Test
    fun differentEpisodeAlwaysAppends() {
        val last = WatchLogPolicy.LastEntry(5, "hianime.to", now)
        assertTrue(WatchLogPolicy.shouldAppend(last, 6, "hianime.to", now + 1_000))
        assertTrue(WatchLogPolicy.shouldAppend(last, 3, "hianime.to", now + 1_000))
    }

    @Test
    fun differentSiteAlwaysAppends() {
        val last = WatchLogPolicy.LastEntry(5, "hianime.to", now)
        assertTrue(WatchLogPolicy.shouldAppend(last, 5, "crunchyroll.com", now + 1_000))
        assertTrue(WatchLogPolicy.shouldAppend(last, 5, "anikototv.to", now + 1_000))
    }

    @Test
    fun laterSittingSameEpisodeAppends() {
        val last = WatchLogPolicy.LastEntry(5, "hianime.to", now)
        assertTrue(WatchLogPolicy.shouldAppend(last, 5, "hianime.to", now + WatchLogPolicy.SAME_SITTING_MS + 1))
    }
}

class AniKotoSlugTest {
    @Test
    fun stripsSiteId() {
        assertEquals("one-piece", SiteAdapters.stripAniKotoId("one-piece-odmau"))
        assertEquals("solo-leveling", SiteAdapters.stripAniKotoId("solo-leveling-ilh08"))
        assertEquals("sakamoto-days", SiteAdapters.stripAniKotoId("sakamoto-days-sfdxz"))
    }

    @Test
    fun keepsRealWords() {
        assertEquals("some-movie", SiteAdapters.stripAniKotoId("some-movie"))
    }
}
