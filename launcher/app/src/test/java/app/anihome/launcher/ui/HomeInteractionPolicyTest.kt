package app.anihome.launcher.ui

import org.junit.Assert.*
import org.junit.Test

class HomeInteractionPolicyTest {
    @Test fun fourDirectionsAreDetectedIndependently() {
        assertEquals(HomeInteractionPolicy.Swipe.UP, HomeInteractionPolicy.swipe(10f, -180f, 0f, -500f, 72f, 200f))
        assertEquals(HomeInteractionPolicy.Swipe.DOWN, HomeInteractionPolicy.swipe(10f, 180f, 0f, 500f, 72f, 200f))
        assertEquals(HomeInteractionPolicy.Swipe.RIGHT, HomeInteractionPolicy.swipe(180f, 5f, 500f, 0f, 72f, 200f))
        assertEquals(HomeInteractionPolicy.Swipe.LEFT, HomeInteractionPolicy.swipe(-180f, 5f, -500f, 0f, 72f, 200f))
    }
    @Test fun defaultsAndPersistedActionsAreIndependent() {
        assertEquals(HomeInteractionPolicy.Action.RECENTS, HomeInteractionPolicy.action(null, HomeInteractionPolicy.Swipe.UP))
        assertEquals(HomeInteractionPolicy.Action.APPS, HomeInteractionPolicy.action(null, HomeInteractionPolicy.Swipe.LEFT))
        assertEquals(HomeInteractionPolicy.Action.NOTIFICATIONS, HomeInteractionPolicy.action(null, HomeInteractionPolicy.Swipe.RIGHT))
        assertEquals(HomeInteractionPolicy.Action.NONE, HomeInteractionPolicy.action(null, HomeInteractionPolicy.Swipe.DOWN))
        assertEquals(HomeInteractionPolicy.Action.SEARCH, HomeInteractionPolicy.action("SEARCH", HomeInteractionPolicy.Swipe.DOWN))
        assertEquals(HomeInteractionPolicy.Action.RECENTS, HomeInteractionPolicy.action("invalid", HomeInteractionPolicy.Swipe.UP))
    }
    @Test fun slowShortDiagonalAndOpposingVelocityDoNotTrigger() {
        assertNull(HomeInteractionPolicy.swipe(200f, 180f, 500f, 500f, 72f, 200f))
        assertNull(HomeInteractionPolicy.swipe(40f, 0f, 500f, 0f, 72f, 200f))
        assertNull(HomeInteractionPolicy.swipe(200f, 0f, 100f, 0f, 72f, 200f))
        assertNull(HomeInteractionPolicy.swipe(200f, 0f, -500f, 0f, 72f, 200f))
    }
    @Test fun invalidAndUnchangedDragPositionsAreRejected() {
        assertFalse(HomeInteractionPolicy.validMove(-1, 2, 4))
        assertFalse(HomeInteractionPolicy.validMove(2, -1, 4))
        assertFalse(HomeInteractionPolicy.validMove(4, 0, 4))
        assertFalse(HomeInteractionPolicy.validMove(0, 0, 0))
        assertFalse(HomeInteractionPolicy.validMove(1, 1, 4))
        assertTrue(HomeInteractionPolicy.validMove(3, 0, 4))
    }
    @Test fun dragEdgesScrollInCorrectDirectionWithBoundedSpeed() {
        assertEquals(-18, HomeInteractionPolicy.scrollStep(-40f, 600, 64, 18))
        assertEquals(18, HomeInteractionPolicy.scrollStep(650f, 600, 64, 18))
        assertTrue(HomeInteractionPolicy.scrollStep(32f, 600, 64, 18) in -17..-1)
        assertTrue(HomeInteractionPolicy.scrollStep(570f, 600, 64, 18) in 1..17)
        assertEquals(0, HomeInteractionPolicy.scrollStep(300f, 600, 64, 18))
    }
    @Test fun shortAndEmptyViewportsNeverOverlapEdgeZonesOrDivideByZero() {
        assertEquals(0, HomeInteractionPolicy.scrollStep(10f, 0, 64, 18))
        assertEquals(0, HomeInteractionPolicy.scrollStep(10f, 100, 0, 18))
        assertEquals(0, HomeInteractionPolicy.scrollStep(40f, 80, 64, 18))
    }
}
