package app.anihome.launcher.ui

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import app.anihome.launcher.R

/** A single accordion keeps each setting and its controls together. */
internal class SettingsPanel(private val activity: AppCompatActivity) {
    private val rows = mutableListOf<Pair<View, ImageButton>>()
    private val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private fun dp(n: Int) = ArtworkUi.dp(activity, n)
    fun section(title: String, icon: Int, summary: () -> String = { "" }, content: LinearLayout.() -> Unit) {
        val group = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ArtworkUi.surface(activity, 0xff1c1c1c.toInt()).apply { setStroke(0, 0) }
        }
        val controls = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), dp(8)); visibility = View.GONE
            content()
        }
        val toggle = ArtworkUi.plainIcon(activity, R.drawable.ic_expand_more, "Expand $title") { }
        val header = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), 0, 0, 0) }
        header.addView(ImageView(activity).apply { setImageResource(icon); setColorFilter(Color.WHITE) }, LinearLayout.LayoutParams(dp(20), dp(20)))
        val label = TextView(activity).apply { setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(6), 0, dp(6)) }
        fun refreshLabel() {
            val detail = summary()
            label.text = if (detail.isEmpty()) title else android.text.SpannableString("$title\n$detail").apply {
                setSpan(android.text.style.RelativeSizeSpan(.85f), title.length + 1, length, 0)
                setSpan(android.text.style.ForegroundColorSpan(0xffbbbbbb.toInt()), title.length + 1, length, 0)
            }
        }
        refreshLabel()
        label.minHeight = dp(48)
        header.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(toggle)
        val expand = {
            refreshLabel()
            val opening = controls.visibility != View.VISIBLE
            rows.forEach { (view, button) -> view.visibility = View.GONE; button.rotation = 0f }
            controls.visibility = if (opening) View.VISIBLE else View.GONE
            toggle.rotation = if (opening) 180f else 0f
            toggle.contentDescription = "${if (opening) "Collapse" else "Expand"} $title"
        }
        header.setOnClickListener { expand() }; toggle.setOnClickListener { expand() }
        rows += controls to toggle
        group.addView(header); group.addView(controls)
        body.addView(group, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    }
    fun toggle(parent: LinearLayout, title: String, checked: Boolean, changed: (Boolean) -> Unit) {
        parent.addView(androidx.appcompat.widget.SwitchCompat(activity).apply {
            text = title; setTextColor(Color.WHITE); textSize = 13f; minHeight = dp(48)
            isChecked = checked; setOnCheckedChangeListener { _, value -> changed(value) }
        }, LinearLayout.LayoutParams(-1, -2))
    }
    fun text(parent: LinearLayout, value: String) {
        parent.addView(TextView(activity).apply { text = value; textSize = 13f; setTextColor(0xffdddddd.toInt()); setPadding(0, dp(6), 0, dp(6)) })
    }
    fun attach(parent: LinearLayout) {
        (body.parent as? android.view.ViewGroup)?.removeView(body)
        parent.addView(body)
    }
}
