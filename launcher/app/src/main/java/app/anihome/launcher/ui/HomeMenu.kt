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
        val checked: Boolean? = null, val drawable: android.graphics.drawable.Drawable? = null,
        /** Inline items render as a compact icon row (with [short] as the caption) above the list. */
        val inline: Boolean = false, val short: String? = null, val action: () -> Unit)

    private var active: PopupWindow? = null
    fun dismiss() { active?.dismiss(); active = null }
    fun finishGesture() { active?.let { if (!it.isFocusable) { it.isFocusable = true; it.update() } } }
    /** Show at a touch point (screen coordinates), e.g. a long press on empty Home. */
    fun showAt(anchor: View, screenX: Int, screenY: Int, title: String, items: List<Item>) =
        show(anchor, title, items, keepGesture = false, at = android.graphics.Point(screenX, screenY))
    fun show(anchor: View, title: String, items: List<Item>, keepGesture: Boolean = false, at: android.graphics.Point? = null) {
        if (!anchor.isAttachedToWindow || items.isEmpty()) return
        dismiss()
        val context = anchor.context
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        fun surface(fill: Int, radius: Int = 12) = GradientDrawable().apply {
            cornerRadius = dp(radius).toFloat(); setColor(fill)
        }
        val bounds = Rect().also(anchor::getWindowVisibleDisplayFrame)
        val width = minOf(dp(320), (bounds.width() - dp(24)).coerceAtLeast(dp(180)))
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(6)) }
        list.addView(TextView(context).apply {
            text = title; textSize = 12f; setTextColor(0xffbfc3ca.toInt())
            setPadding(dp(14), dp(8), dp(14), dp(8))
            ViewCompat.setAccessibilityHeading(this, true)
        })
        val scroll = ScrollView(context).apply {
            addView(list); isFillViewport = false
            background = surface(0xF5191B21.toInt(), 22).apply { setStroke(dp(1), 0x1FFFFFFF) }; clipToOutline = true
        }
        val popup = PopupWindow(scroll, width, ViewGroup.LayoutParams.WRAP_CONTENT, !keepGesture).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            elevation = dp(12).toFloat(); isOutsideTouchable = true; isClippingEnabled = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        }
        // Compact icon actions first, so common actions never need scrolling.
        val actions = items.filter { it.inline }
        if (actions.isNotEmpty()) {
            // Fixed grid so actions line up the same way in every menu, however many there are.
            val columns = if (actions.size > 5) (actions.size + 1) / 2 else 4.coerceAtLeast(actions.size)
            val row = android.widget.GridLayout(context).apply { columnCount = columns; setPadding(dp(2), 0, dp(2), dp(6)) }
            actions.forEach { item ->
                val cell = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(2), dp(6), dp(2), dp(6)); isClickable = true; isFocusable = true
                    background = RippleDrawable(ColorStateList.valueOf(0x33ffffff), null, surface(-1, 12))
                    contentDescription = item.title; tooltipText = item.title
                    setOnClickListener { popup.dismiss(); item.action() }
                }
                cell.addView(ImageView(context).apply {
                    setImageResource(item.icon); scaleType = ImageView.ScaleType.CENTER
                    imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.text))
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xff34373d.toInt()) }
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(44), dp(44)))
                cell.addView(TextView(context).apply {
                    text = item.short ?: item.title; textSize = 11f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER; setTextColor(0xffd0d3d8.toInt())
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                row.addView(cell, android.widget.GridLayout.LayoutParams(android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED),
                    android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)).apply { this.width = 0 })
            }
            list.addView(row, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (actions.size < items.size) list.addView(View(context).apply { setBackgroundColor(0x22ffffff) },
                LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(dp(10), 0, dp(10), dp(4)) })
        }
        items.filterNot { it.inline }.forEach { item ->
            val row = LinearLayout(context).apply {
                id = View.generateViewId()
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52); setPadding(dp(6), dp(6), dp(10), dp(6))
                isClickable = true; isFocusable = true; isSelected = item.checked == true
                background = RippleDrawable(ColorStateList.valueOf(0x33ffffff),
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
            // App-provided shortcut artwork keeps its own colours.
            if (item.drawable != null) row.addView(ImageView(context).apply {
                setImageDrawable(item.drawable); scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(6), dp(6), dp(6), dp(6))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            else if (item.icon != 0) row.addView(ImageView(context).apply {
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
        popup.setOnDismissListener { if (active === popup) active = null; if (!keepGesture && anchor.isAttachedToWindow) anchor.requestFocus() }
        active = popup
        val location = at?.let { intArrayOf(it.x, it.y) } ?: IntArray(2).also(anchor::getLocationOnScreen)
        val anchorWidth = if (at != null) 0 else anchor.width
        val anchorHeight = if (at != null) 0 else anchor.height
        val preferredX = if (at != null) location[0] - width / 2 else location[0] + anchorWidth - width
        val x = preferredX.coerceIn(bounds.left + dp(12), (bounds.right - width - dp(12)).coerceAtLeast(bounds.left + dp(12)))
        val below = location[1] + anchorHeight + dp(8)
        val y = (if (below + popup.height <= bounds.bottom) below else location[1] - popup.height - dp(8))
            .coerceIn(bounds.top + dp(8), (bounds.bottom - popup.height - dp(8)).coerceAtLeast(bounds.top + dp(8)))
        popup.showAtLocation(anchor.rootView, Gravity.TOP or Gravity.LEFT, x, y)
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
            scroll.pivotX = (location[0] - x).toFloat().coerceIn(0f, width.toFloat())
            scroll.pivotY = if (y >= location[1]) 0f else popup.height.toFloat()
            scroll.alpha = 0f; scroll.scaleX = .92f; scroll.scaleY = .92f
            scroll.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(MotionUi.EMPHASIZED_DECELERATE).start()
        }
    }
}
