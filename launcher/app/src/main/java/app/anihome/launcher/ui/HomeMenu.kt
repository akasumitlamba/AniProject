package app.anihome.launcher.ui

import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import app.anihome.launcher.R

/** Compact opaque surface anchored to the control that opened it; keyboard/D-pad focus is retained. */
object HomeMenu {
    data class Item(val id: Int, val title: String, val icon: Int, val detail: String? = null,
        val checked: Boolean? = null, val action: () -> Unit)

    fun show(anchor: View, title: String, items: List<Item>) {
        if (!anchor.isAttachedToWindow || items.isEmpty()) return
        val context = anchor.context
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        fun surface(fill: Int, radius: Int = 12) = GradientDrawable().apply {
            cornerRadius = dp(radius).toFloat(); setColor(fill)
        }
        val bounds = Rect().also(anchor::getWindowVisibleDisplayFrame)
        val width = minOf(dp(320), (bounds.width() - dp(24)).coerceAtLeast(dp(180)))
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(6)) }
        list.addView(TextView(context).apply {
            text = title; textSize = 12f; setTextColor(ContextCompat.getColor(context, R.color.cyan))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            ViewCompat.setAccessibilityHeading(this, true)
        })
        val scroll = ScrollView(context).apply {
            addView(list); isFillViewport = false
            background = ArtworkUi.surface(context); clipToOutline = true
        }
        val popup = PopupWindow(scroll, width, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            elevation = dp(12).toFloat(); isOutsideTouchable = true; isClippingEnabled = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        }
        items.forEach { item ->
            val row = LinearLayout(context).apply {
                id = View.generateViewId()
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52); setPadding(dp(6), dp(6), dp(10), dp(6))
                isClickable = true; isFocusable = true; isSelected = item.checked == true
                background = RippleDrawable(ColorStateList.valueOf(0x4438bdf8),
                    ContextCompat.getDrawable(context, R.drawable.bg_home_menu_row), surface(-1, 12))
                contentDescription = listOfNotNull(item.title, item.detail).joinToString(", ")
                item.checked?.let { ViewCompat.setStateDescription(this, if (it) "On" else "Off") }
                setOnClickListener { popup.dismiss(); item.action() }
            }
            ViewCompat.setAccessibilityDelegate(row, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.Button"
                    info.isCheckable = item.checked != null
                    info.isChecked = item.checked == true
                }
            })
            row.addView(ImageView(context).apply {
                setImageResource(item.icon)
                imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, if (item.checked == true) R.color.cyan else R.color.text))
                setPadding(dp(8), dp(8), dp(8), dp(8))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            row.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(6), 0, dp(6), 0)
                addView(TextView(context).apply {
                    text = item.title; textSize = 15f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    setTextColor(ContextCompat.getColor(context, R.color.text))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                })
                item.detail?.let { detail -> addView(TextView(context).apply {
                    text = detail; textSize = 11f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    setTextColor(ContextCompat.getColor(context, R.color.muted))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (item.checked != null) row.addView(ImageView(context).apply {
                if (item.checked) setImageResource(R.drawable.ic_check)
                imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.cyan))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(20), dp(20)))
            list.addView(row, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        scroll.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((bounds.height() * 0.7f).toInt(), View.MeasureSpec.AT_MOST))
        popup.height = scroll.measuredHeight
        popup.setOnDismissListener { if (anchor.isAttachedToWindow) anchor.requestFocus() }
        popup.showAsDropDown(anchor, 0, dp(8), Gravity.END)
    }
}
