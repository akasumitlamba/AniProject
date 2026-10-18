package app.anihome.launcher.ui
import org.junit.Assert.*
import org.junit.Test
class PanelGeometryPolicyTest {
    @Test fun panelAttachesToPhysicalLeftAndTopRegardlessOfRailAndInsets() {
        run {
            val p = PanelGeometryPolicy.bounds(960, 600, 360)
            assertEquals(0, p.x); assertEquals(0, p.y); assertEquals(600, p.height); assertEquals(360, p.width)
        }
    }
    @Test fun compactWindowsClampWidthWithoutReservingDockSpace() {
        assertEquals(320, PanelGeometryPolicy.bounds(320, 240, 360).width)
    }
    @Test fun emptyWindowDimensionsRemainValid() {
        val p = PanelGeometryPolicy.bounds(0, 0, 360)
        assertEquals(1, p.width); assertEquals(1, p.height)
    }
}
