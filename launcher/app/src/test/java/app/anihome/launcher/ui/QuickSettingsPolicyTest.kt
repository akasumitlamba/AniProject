package app.anihome.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickSettingsPolicyTest {
    @Test fun fixedRotationPreservesAllFourAnglesOnBothNaturalDisplays() {
        assertEquals(listOf(1, 0, 9, 8), (0..3).map { QuickSettingsPolicy.fixedOrientation(it, 0) })
        assertEquals(listOf(0, 1, 8, 9), (0..3).map { QuickSettingsPolicy.fixedOrientation(it, 1) })
    }
    @Test fun connectedThenRecentThenPairedWithoutTruncation() {
        val entries = (0..30).map { QuickSettingsPolicy.Device("$it", "Device $it", it == 25, if (it == 18) 99 else 0, it == 7) }
        val sorted = QuickSettingsPolicy.ordered(entries)
        assertEquals(listOf("25", "18", "7"), sorted.take(3).map { it.id })
        assertEquals(31, sorted.size)
    }
    @Test fun phoneAndNaturalLandscapeTabletLocksStayCorrectFromAllRotations() {
        for (naturalLandscape in listOf(false, true)) for (rotation in 0..3) {
            val currentLandscape = naturalLandscape == (rotation % 2 == 0)
            assertEquals(if (naturalLandscape) 0 else 1, QuickSettingsPolicy.lockRotation(true, currentLandscape, rotation))
            assertEquals(if (naturalLandscape) 1 else 0, QuickSettingsPolicy.lockRotation(false, currentLandscape, rotation))
        }
    }
}
