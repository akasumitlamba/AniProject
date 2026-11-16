package app.anihome.launcher.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.widget.LinearLayout
import app.anihome.launcher.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/**
 * Material You pieces in AniHome's dark palette, shared by the banner and wallpaper screens.
 * Created from a Material 3 context so they look right whatever screen hosts them.
 */
internal object MaterialUi {
    const val SURFACE = 0xFF111318.toInt()
    const val SURFACE_CONTAINER = 0xFF1D2027.toInt()
    const val SURFACE_HIGH = 0xFF282A31.toInt()
    const val PRIMARY = 0xFFA8C7FA.toInt()
    const val ON_PRIMARY = 0xFF062E6F.toInt()
    const val SECONDARY_CONTAINER = 0xFF2C3A51.toInt()
    const val ON_SECONDARY_CONTAINER = 0xFFD6E3FF.toInt()
    const val ON_SURFACE = 0xFFE3E2E9.toInt()
    const val ON_SURFACE_VARIANT = 0xFFC4C6D0.toInt()

    fun themed(context: Context): Context = ContextThemeWrapper(context, R.style.Theme_AniHome)
    private fun dp(context: Context, v: Int) = ArtworkUi.dp(context, v)

    /** Filled tonal button (Material 3 "tonal"), the default for secondary actions. */
    fun tonal(context: Context, label: String, icon: Int = 0, click: () -> Unit) = MaterialButton(themed(context)).apply {
        text = label; isAllCaps = false; textSize = 14f; cornerRadius = dp(context, 20)
        backgroundTintList = ColorStateList.valueOf(SECONDARY_CONTAINER); setTextColor(ON_SECONDARY_CONTAINER)
        if (icon != 0) { setIconResource(icon); iconTint = ColorStateList.valueOf(ON_SECONDARY_CONTAINER); iconPadding = dp(context, 8) }
        insetTop = 0; insetBottom = 0; minHeight = dp(context, 48)
        setOnClickListener { click() }
    }

    /** Filled primary button for the screen's main action. */
    fun filled(context: Context, label: String, icon: Int = 0, click: () -> Unit) = tonal(context, label, icon, click).apply {
        backgroundTintList = ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(0x1FE3E2E9, PRIMARY))
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(0x61E3E2E9, ON_PRIMARY)))
        if (icon != 0) iconTint = ColorStateList.valueOf(ON_PRIMARY)
        textSize = 15f; minHeight = dp(context, 52); cornerRadius = dp(context, 26)
    }

    /** Single-choice filter chips in a horizontally laid-out group. */
    fun chipGroup(context: Context) = ChipGroup(themed(context)).apply {
        isSingleSelection = true; isSelectionRequired = true; isSingleLine = true
        chipSpacingHorizontal = dp(context, 8)
    }
    fun filterChip(context: Context, label: String) = Chip(themed(context)).apply {
        text = label; isCheckable = true; isCheckedIconVisible = true; textSize = 13f
        chipBackgroundColor = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(SECONDARY_CONTAINER, SURFACE_CONTAINER))
        setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(ON_SECONDARY_CONTAINER, ON_SURFACE_VARIANT)))
        chipStrokeWidth = dp(context, 1).toFloat()
        chipStrokeColor = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(SECONDARY_CONTAINER, 0x33C4C6D0))
        checkedIconTint = ColorStateList.valueOf(ON_SECONDARY_CONTAINER)
    }

    /** Material 3 segmented buttons: one choice among a few, equal widths. */
    fun segmented(context: Context, labels: List<String>, selected: Int, changed: (Int) -> Unit): MaterialButtonToggleGroup {
        val group = MaterialButtonToggleGroup(themed(context)).apply { isSingleSelection = true; isSelectionRequired = true }
        labels.forEachIndexed { index, label ->
            val button = MaterialButton(themed(context), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = android.view.View.generateViewId(); text = label; isAllCaps = false; textSize = 13f; tag = index
                strokeColor = ColorStateList.valueOf(0x66C4C6D0); insetTop = 0; insetBottom = 0; minHeight = dp(context, 44)
                backgroundTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(SECONDARY_CONTAINER, 0x00000000))
                setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(ON_SECONDARY_CONTAINER, ON_SURFACE)))
            }
            group.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        group.check(group.getChildAt(selected).id)
        group.addOnButtonCheckedListener { g, id, checked -> if (checked) changed(g.findViewById<android.view.View>(id).tag as Int) }
        return group
    }

    /** Large screen title, Material 3 "headline small". */
    fun title(context: Context, text: String) = android.widget.TextView(context).apply {
        this.text = text; textSize = 24f; setTextColor(ON_SURFACE)
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
    }
}
