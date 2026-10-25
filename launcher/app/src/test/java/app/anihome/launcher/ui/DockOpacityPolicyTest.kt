package app.anihome.launcher.ui

import org.junit.Assert.*
import org.junit.Test

class DockOpacityPolicyTest {
    @Test fun brighterWallpaperNeedsMoreScrim() {
        assertTrue(DockOpacityPolicy.automatic(1.0) > DockOpacityPolicy.automatic(.5))
        assertTrue(DockOpacityPolicy.automatic(.5) > DockOpacityPolicy.automatic(0.0))
        assertTrue((0..100).all { DockOpacityPolicy.automatic(it / 100.0) in 0..100 })
    }
    @Test fun manualEndpointsAreTrulyTransparentAndOpaque() {
        assertEquals(0, DockOpacityPolicy.alpha(0)); assertEquals(255, DockOpacityPolicy.alpha(100))
        assertEquals(0, DockOpacityPolicy.alpha(-20)); assertEquals(255, DockOpacityPolicy.alpha(150))
    }
}
