package app.anihome.launcher.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.anihome.launcher.R
import com.google.android.material.button.MaterialButton

internal object ArtworkUi {
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun surface(context: Context, color: Int = 0xff101c2e.toInt()) = GradientDrawable().apply {
        setColor(color); cornerRadius = context.resources.getDimension(R.dimen.control_radius)
        setStroke(dp(context, 1).coerceAtLeast(1), ContextCompat.getColor(context, R.color.glass_line))
    }
    fun icon(context: Context, icon: Int, label: String, action: (View) -> Unit) = ImageButton(context).apply {
        setImageResource(icon); imageTintList = ColorStateList.valueOf(-1)
        contentDescription = label; tooltipText = label
        background = ContextCompat.getDrawable(context, R.drawable.bg_icon_button)
        setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
        layoutParams = LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)).apply { marginStart = dp(context, 6) }
        setOnClickListener(action)
    }
    fun plainIcon(context: Context, image: Int, label: String, action: (View) -> Unit) = icon(context, image, label, action).apply {
        val value = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true)
        background = ContextCompat.getDrawable(context, value.resourceId)
        setPadding(dp(context, 13), dp(context, 13), dp(context, 13), dp(context, 13))
    }
    fun chip(context: Context, label: String, icon: Int, action: () -> Unit) = MaterialButton(context).apply {
        text = label; isAllCaps = false; textSize = 13f
        setIconResource(icon); iconSize = dp(context, 18)
        iconTint = ColorStateList.valueOf(-1); setTextColor(-1)
        backgroundTintList = ColorStateList.valueOf(0xff1b3047.toInt())
        cornerRadius = dp(context, 12); minWidth = 0
        insetTop = 0; insetBottom = 0
        setPadding(dp(context, 8), 0, dp(context, 8), 0)
        setOnClickListener { action() }
    }
    fun textChip(context: Context, label: String, action: () -> Unit) = chip(context, label, 0, action).apply {
        icon = null
        setPadding(dp(context, 12), 0, dp(context, 12), 0)
    }
    fun title(context: Context, value: String) = TextView(context).apply {
        text = value; textSize = 21f; setTextColor(-1); gravity = Gravity.CENTER_VERTICAL
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
    }
}
