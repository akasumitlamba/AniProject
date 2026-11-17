package app.anihome.launcher.ui

import android.content.Context
import app.anihome.launcher.R

/** A saved tile density shared by tiles and the finer widget grid. Zero means adaptive. */
internal object HomeLayout {
    private fun prefs(context: Context) = context.getSharedPreferences("anihome", Context.MODE_PRIVATE)
    /** Banners per row; zero means automatic. */
    fun selection(context: Context) = prefs(context).getInt("home_columns", 0).takeIf { it in 2..6 } ?: 0
    fun columns(context: Context) = selection(context).takeIf { it != 0 }
        ?: context.resources.getInteger(R.integer.home_columns)
    /** Icons per row; zero means automatic. */
    fun iconSelection(context: Context) = prefs(context).getInt("home_icon_columns", 0).takeIf { it in 3..12 } ?: 0
    /**
     * Icons per row, independent of banners: on a phone 2 banners or 5 icons share a row.
     * Automatic fits about one icon per 76 dp, and always more icons than banners.
     */
    fun iconColumns(context: Context, availableDp: Float, banners: Int = columns(context)): Int {
        val chosen = iconSelection(context).takeIf { it != 0 } ?: kotlin.math.round(availableDp / 76f).toInt()
        return chosen.coerceIn(banners + 1, banners * 3)
    }
}

/**
 * The Home board's fine grid. Banners span [bannerSpan] columns, icons [iconSpan], and a widget
 * cell is [widgetUnit] columns, so banners, icons and widgets each line up on their own rhythm.
 */
internal data class HomeGrid(val banners: Int, val icons: Int) {
    val columns: Int get() = banners * 2 * icons
    val bannerSpan: Int get() = 2 * icons
    val iconSpan: Int get() = 2 * banners
    val widgetUnit: Int get() = icons
}
