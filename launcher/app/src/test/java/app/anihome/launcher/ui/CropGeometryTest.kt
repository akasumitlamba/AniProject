package app.anihome.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CropGeometryTest {
    @Test fun portraitImageCoversLandscapeViewport() {
        val scale = CropGeometry.minimumScale(324f, 200f, 100, 400)
        assertEquals(3.24f, scale, 0.0001f)
        assertEquals(0f, CropGeometry.boundedOffset(200f, 0f, 324f, 100 * scale), 0.0001f)
        assertEquals(-1096f, CropGeometry.boundedOffset(-2000f, 0f, 200f, 400 * scale), 0.0001f)
    }
    @Test fun wideImageCoversHeightAndCanPanHorizontally() {
        val scale = CropGeometry.minimumScale(324f, 200f, 800, 100)
        assertEquals(2f, scale, 0f)
        assertEquals(-500f, CropGeometry.boundedOffset(-500f, 0f, 324f, 1600f), 0f)
        assertEquals(-1276f, CropGeometry.boundedOffset(-2000f, 0f, 324f, 1600f), 0f)
    }
    @Test fun fittedImageRoundoffDoesNotThrow() {
        assertEquals(10f, CropGeometry.boundedOffset(10f, 10f, 334f, 323.99997f), 0f)
    }
}
