package app.anihome.launcher.ui

import kotlin.math.ceil
import kotlin.math.floor

internal object WidgetSizing {
    fun cellsFromPixels(minimumPixels: Int, density: Float, cellDp: Float, available: Int): Int {
        require(density > 0 && cellDp > 0 && available > 0)
        return ceil(minimumPixels / density / cellDp).toInt().coerceIn(1, available)
    }

    /** A provider's maximum must round down, while never contradicting its required minimum. */
    fun maximumCells(maximumPixels: Int, density: Float, cellDp: Float, minimum: Int, available: Int): Int {
        require(density > 0 && cellDp > 0 && minimum in 1..available)
        if (maximumPixels <= 0) return available
        return floor(maximumPixels / density / cellDp).toInt().coerceIn(minimum, available)
    }

    /** Round up so a provider's minimum dimension never gets squeezed into fewer cells. */
    fun cells(minimumDp: Int, cellDp: Float, available: Int): Int {
        require(cellDp > 0 && available > 0)
        return ceil(minimumDp / cellDp).toInt().coerceIn(1, available)
    }
}
