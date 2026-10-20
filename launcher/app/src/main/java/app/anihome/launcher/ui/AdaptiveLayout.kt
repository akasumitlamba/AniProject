package app.anihome.launcher.ui

import android.content.Context
import kotlin.math.floor

/** Geometry, rather than a device model, determines how many complete controls fit. */
internal object AdaptiveLayout {
    fun tablet(context: Context) = context.resources.configuration.smallestScreenWidthDp >= 600
    fun columns(widthDp: Float, fontScale: Float, maximum: Int = 8, cellDp: Float = 80f): Int =
        floor(widthDp / (cellDp * fontScale.coerceIn(1f, 1.6f))).toInt().coerceIn(1, maximum)
    fun dockCapacity(lengthDp: Int, vertical: Boolean, recents: Boolean, editing: Boolean, search: Boolean = true): Int {
        val fixed = 80 + (if (search) 44 else 0) + 44 + (if (recents) 44 else 0) + (if (editing) 88 else 0) + 16
        return ((lengthDp - fixed) / (if (vertical) 52 else 44)).coerceIn(0, 5)
    }
    fun expandedHeight(sheetHeightDp: Int, portrait: Boolean): Int =
        (sheetHeightDp * if (portrait) .66f else .58f).toInt().coerceAtMost((sheetHeightDp - 130).coerceAtLeast(80))
}
