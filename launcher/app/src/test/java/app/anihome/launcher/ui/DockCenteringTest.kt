package app.anihome.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DockCenteringTest {
    // Dock 1000 wide (centre 500); free area from 300 to 800 (wider cluster on the left).
    @Test fun fewAppsSitOnTheDockCentre() {
        val start = DockCentering.start(500f, 300f, 500, 100)
        assertEquals(500f, 300f + start + 50f, 0.5f)
    }
    @Test fun growingRowSlidesOnlyAsFarAsNeeded() {
        // 300 wide row centred at 500 would begin at 350 (inside the area at 50) and still fit.
        assertEquals(50, DockCentering.start(500f, 300f, 500, 300))
        // 460 wide row can't be centred without crossing the left cluster: pinned to the area start.
        assertEquals(0, DockCentering.start(500f, 300f, 500, 460))
    }
    @Test fun neverPushesPastTheFarEdge() {
        // Free area mostly left of centre: the row stops at the area's end instead of overlapping.
        val start = DockCentering.start(500f, 0f, 520, 200)
        assertEquals(320, start)
    }
    @Test fun overflowingRowStartsAtTheEdgeAndScrolls() {
        assertEquals(0, DockCentering.start(500f, 300f, 500, 900))
    }
}
