package app.anihome.launcher.ui

import android.content.Context
import app.anihome.launcher.R

/** A saved tile density shared by tiles and the finer widget grid. Zero means adaptive. */
internal object HomeLayout {
    fun selection(context: Context) = context.getSharedPreferences("anihome", Context.MODE_PRIVATE)
        .getInt("home_columns", 0).takeIf { it in 2..6 } ?: 0
    fun columns(context: Context) = selection(context).takeIf { it != 0 }
        ?: context.resources.getInteger(R.integer.home_columns)
}
