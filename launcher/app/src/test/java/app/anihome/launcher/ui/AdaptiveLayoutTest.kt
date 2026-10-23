package app.anihome.launcher.ui

import org.junit.Assert.*
import org.junit.Test

class AdaptiveLayoutTest {
    @Test fun phoneGridUsesWholeCellsAndShrinksForLargerText() {
        assertEquals(4, AdaptiveLayout.columns(332f, 1f))
        assertEquals(2, AdaptiveLayout.columns(332f, 1.5f))
        assertEquals(1, AdaptiveLayout.columns(52f, 1f))
        assertEquals(4, AdaptiveLayout.columns(454f, 1f, 4, 96f))
    }
    @Test fun dockReservesClockSearchAndNavigationBeforePins() {
        assertEquals(2, AdaptiveLayout.dockCapacity(332, false, false, false))
        assertEquals(1, AdaptiveLayout.dockCapacity(332, false, true, false))
        assertEquals(0, AdaptiveLayout.dockCapacity(240, false, true, true))
        assertEquals(5, AdaptiveLayout.dockCapacity(900, true, true, false))
    }
    @Test fun expandedControlsHavePortraitSpaceAndLeaveNotificationsUsable() {
        assertTrue(AdaptiveLayout.expandedHeight(800, true) > 450)
        assertTrue(AdaptiveLayout.expandedHeight(800, true) < 670)
        assertTrue(AdaptiveLayout.expandedHeight(360, false) <= 230)
        assertTrue(AdaptiveLayout.expandedHeight(360, false) >= 160)
    }
    @Test fun newPhonesUseNativeNavigationFriendlyDefaultsAndExplicitChoicesSurvive() {
        assertEquals(HomeInteractionPolicy.Action.APPS, HomeInteractionPolicy.action(null, HomeInteractionPolicy.Swipe.UP, false))
        assertEquals(HomeInteractionPolicy.Action.NOTIFICATIONS, HomeInteractionPolicy.action(null, HomeInteractionPolicy.Swipe.DOWN, false))
        assertEquals(HomeInteractionPolicy.Action.RECENTS, HomeInteractionPolicy.action("RECENTS", HomeInteractionPolicy.Swipe.UP, false))
        assertEquals(HomeInteractionPolicy.Action.RECENTS, HomeInteractionPolicy.action(null, HomeInteractionPolicy.Swipe.UP, true))
    }
}
