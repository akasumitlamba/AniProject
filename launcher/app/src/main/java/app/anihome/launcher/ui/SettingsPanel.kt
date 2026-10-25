package app.anihome.launcher.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import app.anihome.launcher.R

/** Consistent settings rows: label left, control right; only expanded content scrolls. */
internal class SettingsPanel(private val activity: AppCompatActivity) {
    private val rows = mutableListOf<Pair<View, ImageButton>>()
    private val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private fun dp(n: Int) = ArtworkUi.dp(activity, n)
    private fun label(value: String, size: Float = 13f) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL
    }
    private fun row(parent: LinearLayout): LinearLayout {
        if (parent.childCount > 0) parent.addView(View(activity).apply { setBackgroundColor(0xff323232.toInt()) }, LinearLayout.LayoutParams(-1, dp(1)))
        return LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(56); setPadding(dp(2), dp(6), dp(2), dp(6))
            parent.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
    }
    fun section(title: String, icon: Int, summary: () -> String = { "" }, content: LinearLayout.() -> Unit) {
        val group = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; background = ArtworkUi.surface(activity, 0xff1c1c1c.toInt()).apply { setStroke(0, 0) }
        }
        val controls = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), dp(4)); visibility = View.GONE
        }
        val toggle = ArtworkUi.plainIcon(activity, R.drawable.ic_expand_more, "Expand $title") { }
        val header = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, 0, 0) }
        header.addView(ImageView(activity).apply { setImageResource(icon); setColorFilter(Color.WHITE) }, LinearLayout.LayoutParams(dp(20), dp(20)))
        val titleView = label(title, 14f).apply { setPadding(dp(10), dp(8), 0, dp(8)); minHeight = dp(52) }
        fun refresh() {
            val detail = summary()
            titleView.text = if (detail.isBlank()) title else android.text.SpannableString("$title\n$detail").apply {
                setSpan(android.text.style.RelativeSizeSpan(.82f), title.length + 1, length, 0)
                setSpan(android.text.style.ForegroundColorSpan(0xffbbbbbb.toInt()), title.length + 1, length, 0)
            }
        }
        refresh(); header.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f)); header.addView(toggle)
        var built = false
        val expand = {
            val opening = controls.visibility != View.VISIBLE
            if (opening && !built) { controls.content(); built = true }
            refresh(); rows.forEach { (view, button) -> view.visibility = View.GONE; button.rotation = 0f }
            controls.visibility = if (opening) View.VISIBLE else View.GONE
            toggle.rotation = if (opening) 180f else 0f
            toggle.contentDescription = "${if (opening) "Collapse" else "Expand"} $title"
        }
        header.setOnClickListener { expand() }; toggle.setOnClickListener { expand() }
        rows += controls to toggle; group.addView(header); group.addView(controls)
        body.addView(group, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    }
    fun toggle(parent: LinearLayout, title: String, checked: Boolean, changed: (Boolean) -> Unit) {
        val row = row(parent)
        row.addView(label(title).apply { setPadding(0, 0, dp(12), 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        val control = androidx.appcompat.widget.SwitchCompat(activity).apply {
            contentDescription = title; isChecked = checked; minHeight = dp(44)
            setOnCheckedChangeListener { _, value -> changed(value) }
        }
        row.addView(control, LinearLayout.LayoutParams(-2, dp(44)))
        row.setOnClickListener { control.isChecked = !control.isChecked }
    }
    fun choice(parent: LinearLayout, title: String, values: List<String>, initial: Int, changed: (Int) -> Unit) {
        val row = row(parent)
        row.addView(label(title).apply { setPadding(0, 0, dp(10), 0) }, LinearLayout.LayoutParams(0, -2, .43f))
        val value = label(values[initial.coerceIn(values.indices)]).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; maxLines = 2 }
        row.addView(value, LinearLayout.LayoutParams(0, -2, .57f))
        row.addView(ImageView(activity).apply { setImageResource(R.drawable.ic_expand_more); setColorFilter(0xffbbbbbb.toInt()) }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(6) })
        var selected = initial
        row.isFocusable = true; row.contentDescription = "$title, ${value.text}"
        row.setOnClickListener { HomeMenu.show(row, title, values.mapIndexed { index, name ->
            HomeMenu.Item(index, name, 0, checked = index == selected) {
                selected = index; value.text = name; row.contentDescription = "$title, $name"; changed(index)
            }
        }) }
    }
    fun radios(parent: LinearLayout, values: List<String>, initial: Int, changed: (Int) -> Unit) {
        val group = RadioGroup(activity).apply { orientation = RadioGroup.VERTICAL }
        values.forEachIndexed { index, title ->
            group.addView(androidx.appcompat.widget.AppCompatRadioButton(activity).apply {
                id = View.generateViewId(); text = title; textSize = 13f; setTextColor(Color.WHITE)
                minHeight = dp(52); gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(4), dp(8), dp(4))
                buttonTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Color.WHITE, 0xff85858b.toInt()))
                tag = index
            }, RadioGroup.LayoutParams(-1, -2))
        }
        (group.getChildAt(initial.coerceIn(values.indices)) as RadioButton).isChecked = true
        group.setOnCheckedChangeListener { _, id -> changed(group.findViewById<RadioButton>(id).tag as Int) }
        parent.addView(group, LinearLayout.LayoutParams(-1, -2))
    }

    fun action(parent: LinearLayout, title: String, click: () -> Unit) {
        val row = row(parent)
        row.addView(label(title), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(ImageView(activity).apply { setImageResource(R.drawable.ic_expand_more); rotation = 270f; setColorFilter(0xffbbbbbb.toInt()) }, LinearLayout.LayoutParams(dp(20), dp(20)))
        row.isFocusable = true; row.setOnClickListener { click() }
    }
    fun slider(parent: LinearLayout, title: String, initial: Int, changed: (Int) -> Unit): View {
        val container = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
        val heading = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(label(title), LinearLayout.LayoutParams(0, dp(32), 1f))
        val value = label("$initial%")
        heading.addView(value); container.addView(heading)
        container.addView(androidx.appcompat.widget.AppCompatSeekBar(activity).apply {
            max = 100; progress = initial; contentDescription = title
            progressTintList = ColorStateList.valueOf(0xffdddddd.toInt()); thumbTintList = ColorStateList.valueOf(Color.WHITE)
            setPadding(dp(8), 0, dp(8), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) { value.text = "$progress%"; if (fromUser) changed(progress) }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }, LinearLayout.LayoutParams(-1, dp(48)))
        parent.addView(container, LinearLayout.LayoutParams(-1, -2)); return container
    }
    fun text(parent: LinearLayout, value: String) {
        parent.addView(label(value, 12f).apply { setTextColor(0xffbbbbbb.toInt()); setPadding(0, dp(8), 0, dp(8)) })
    }
    fun attach(parent: LinearLayout) { (body.parent as? android.view.ViewGroup)?.removeView(body); parent.addView(body) }
}
