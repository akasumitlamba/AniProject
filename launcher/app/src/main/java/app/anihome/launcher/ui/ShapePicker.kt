package app.anihome.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.anihome.launcher.data.IconCache
import app.anihome.launcher.data.IconShape
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Icon shapes shown as shapes (not names); tapping one applies it everywhere at once. */
internal object ShapePicker {
    private class ShapeDrawable(val shape: IconShape, val color: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@ShapeDrawable.color }
        override fun draw(canvas: Canvas) {
            val size = minOf(bounds.width(), bounds.height())
            canvas.save(); canvas.translate(bounds.left + (bounds.width() - size) / 2f, bounds.top + (bounds.height() - size) / 2f)
            canvas.drawPath(shape.path(size), paint); canvas.restore()
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(filter: android.graphics.ColorFilter?) { paint.colorFilter = filter }
        @Deprecated("Deprecated in Android") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    fun preview(shape: IconShape, color: Int = 0xFFE8EAF0.toInt()): Drawable = ShapeDrawable(shape, color)

    fun show(activity: AppCompatActivity, applied: () -> Unit) {
        fun dp(v: Int) = ArtworkUi.dp(activity, v)
        val current = IconShape.selected(activity)
        lateinit var dialog: androidx.appcompat.app.AlertDialog
        val grid = GridLayout(activity).apply { columnCount = 4; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        IconShape.entries.forEach { shape ->
            val chosen = shape == current
            val cell = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(6), dp(10), dp(6), dp(10))
                isClickable = true; isFocusable = true; contentDescription = shape.label
                background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(if (chosen) 0x2E8AB4FF else 0) }
                setOnClickListener {
                    IconShape.select(activity, shape); IconCache.clearNormalized(); applied(); dialog.dismiss()
                }
            }
            cell.addView(ImageView(activity).apply { setImageDrawable(preview(shape, if (chosen) ControlCenter.ACCENT else 0xFFE8EAF0.toInt())) },
                LinearLayout.LayoutParams(dp(48), dp(48)))
            cell.addView(TextView(activity).apply { text = shape.label; textSize = 11f; setTextColor(0xFFC8CBD2.toInt()); gravity = Gravity.CENTER; maxLines = 1 },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            grid.addView(cell, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0 })
        }
        dialog = MaterialAlertDialogBuilder(activity).setTitle("Icon shape").setView(grid).setNegativeButton("Close", null).show()
    }

    /** Settings row showing the current shape, opening the picker. */
    fun row(activity: AppCompatActivity, parent: LinearLayout, applied: () -> Unit) {
        fun dp(v: Int) = ArtworkUi.dp(activity, v)
        val row = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(54); isClickable = true; isFocusable = true
            setPadding(dp(2), dp(6), dp(2), dp(6))
        }
        row.addView(TextView(activity).apply { text = "Icon shape"; textSize = 13f; setTextColor(-1) }, LinearLayout.LayoutParams(0, -2, 1f))
        val swatch = ImageView(activity).apply { setImageDrawable(preview(IconShape.selected(activity), ControlCenter.ACCENT)) }
        row.addView(swatch, LinearLayout.LayoutParams(dp(28), dp(28)))
        row.setOnClickListener { show(activity) { swatch.setImageDrawable(preview(IconShape.selected(activity), ControlCenter.ACCENT)); applied() } }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
    }
}
