package app.anihome.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetSizingTest {
    @Test fun providerMaximumRoundsDownWithoutBreakingMinimum() {
        assertEquals(2, WidgetSizing.maximumCells(719, 2f, 120f, 1, 8))
        assertEquals(3, WidgetSizing.maximumCells(100, 2f, 120f, 3, 8))
        assertEquals(8, WidgetSizing.maximumCells(0, 2f, 120f, 1, 8))
    }
    @Test fun providerPixelsRespectTabletDensity() {
        assertEquals(2, WidgetSizing.cellsFromPixels(480, 2f, 120f, 8))
        assertEquals(3, WidgetSizing.cellsFromPixels(481, 2f, 120f, 8))
    }
    @Test fun landscapeTileUsesFourSmallCells() {
        assertEquals(2, WidgetSizing.cells(240, 120f, 8))
        assertEquals(2, WidgetSizing.cells(160, 80f, 12))
    }
    @Test fun providerMinimumRoundsUp() {
        assertEquals(3, WidgetSizing.cells(241, 120f, 8))
    }
    @Test fun oversizedProviderFitsAvailableWidth() {
        assertEquals(4, WidgetSizing.cells(1200, 120f, 4))
    }
    @Test fun missingOrZeroMinimumStillOccupiesOneCell() {
        assertEquals(1, WidgetSizing.cells(0, 120f, 4))
    }
}
