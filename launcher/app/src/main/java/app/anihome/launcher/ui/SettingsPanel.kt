package app.anihome.launcher.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import app.anihome.launcher.R

/** Consistent settings rows: label left, control right; only expanded content scrolls. */
/**
 * Settings UI. Narrow screens (phones, the side panel) get expandable section cards; wide
 * tablet screens get a two-pane layout: section list on the left, the chosen section's
 * options on the right, like Android's own tablet Settings.
 */
internal class SettingsPanel(private val activity: AppCompatActivity, private val twoPane: Boolean = false) {
    companion object {
        /** Wide tablet screens with the full-screen panel use the two-pane layout. */
        fun twoPane(activity: AppCompatActivity) = PanelStyle.wide(activity) && activity.resources.configuration.screenWidthDp >= 840
        /** Last opened section, so a live layout change returns to the same place. */
        private var lastSection: String? = null
    }
    private class Spec(val title: String, val icon: Int, val accent: Int, val summary: () -> String, val content: LinearLayout.() -> Unit)
    private val specs = mutableListOf<Spec>()
    private val rows = mutableListOf<Pair<View, ImageButton>>()
    private val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    /** Section cards with their searchable text, for the filter box at the top. */
    private val searchable = mutableListOf<Pair<View, String>>()
    private val palette = intArrayOf(0xFF3B82F6.toInt(), 0xFF10B981.toInt(), 0xFFF59E0B.toInt(), 0xFF8B5CF6.toInt(), 0xFFEF4444.toInt(),
        0xFF06B6D4.toInt(), 0xFFEC4899.toInt(), 0xFF84CC16.toInt(), 0xFF6366F1.toInt(), 0xFFF97316.toInt())
    private val search: EditText by lazy {
        EditText(activity).apply {
            hint = "Search settings"; textSize = 14f; setTextColor(Color.WHITE); setHintTextColor(0x99FFFFFF.toInt())
            isSingleLine = true; imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0); compoundDrawablePadding = dp(10)
            compoundDrawableTintList = ColorStateList.valueOf(0x99FFFFFF.toInt())
            background = android.graphics.drawable.GradientDrawable().apply { setColor(ControlCenter.SURFACE); cornerRadius = dp(22).toFloat(); setStroke(dp(1), ControlCenter.STROKE) }
            setPadding(dp(16), 0, dp(16), 0)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) {
                    val query = s?.toString()?.trim()?.lowercase().orEmpty()
                    MotionUi.expand(body)
                    searchable.forEach { (card, text) -> card.visibility = if (query.isEmpty() || query in text) View.VISIBLE else View.GONE }
                }
            })
        }.also { body.addView(it, 0, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(6) }) }
    }
    private fun dp(n: Int) = ArtworkUi.dp(activity, n)
    private fun label(value: String, size: Float = 13f) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL
    }
    private fun row(parent: LinearLayout): LinearLayout {
        if (parent.childCount > 0) parent.addView(View(activity).apply { setBackgroundColor(0x14FFFFFF) }, LinearLayout.LayoutParams(-1, dp(1)))
        return LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(54); setPadding(dp(2), dp(6), dp(2), dp(6))
            parent.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
    }
    fun section(title: String, icon: Int, summary: () -> String = { "" }, content: LinearLayout.() -> Unit) {
        if (twoPane) { specs += Spec(title, icon, palette[specs.size % palette.size], summary, content); return }
        search // Created with the first section so it sits at the top.
        val accent = palette[searchable.size % palette.size]
        val group = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply { setColor(ControlCenter.SURFACE); cornerRadius = dp(20).toFloat(); setStroke(dp(1), ControlCenter.STROKE) }
            clipToOutline = true
        }
        val controls = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(8)); visibility = View.GONE
        }
        val toggle = ArtworkUi.plainIcon(activity, R.drawable.ic_expand_more, "Expand $title") { }
        val header = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(10), dp(4), dp(10)); minimumHeight = dp(64)
            background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x1FFFFFFF), null, null)
        }
        // Coloured badge per section makes the list scannable at a glance.
        header.addView(ImageView(activity).apply {
            setImageResource(icon); setColorFilter(Color.WHITE); scaleType = ImageView.ScaleType.CENTER
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(accent) }
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(6), 0) }
        val titleView = label(title, 15f).apply { typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) }
        val summaryView = label("", 12f).apply { setTextColor(0xFFA9ADB6.toInt()); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        texts.addView(titleView); texts.addView(summaryView)
        fun refresh() {
            val detail = summary()
            summaryView.text = detail; summaryView.visibility = if (detail.isBlank()) View.GONE else View.VISIBLE
        }
        refresh(); header.addView(texts, LinearLayout.LayoutParams(0, -2, 1f)); header.addView(toggle)
        var built = false
        val expand = {
            val opening = controls.visibility != View.VISIBLE
            if (opening && !built) { controls.content(); built = true }
            MotionUi.expand(body)
            refresh(); rows.forEach { (view, button) -> if (view !== controls) { view.visibility = View.GONE; button.animate().rotation(0f).setDuration(160).start() } }
            controls.visibility = if (opening) View.VISIBLE else View.GONE
            toggle.animate().rotation(if (opening) 180f else 0f).setDuration(MotionUi.duration(toggle, 200)).setInterpolator(MotionUi.STANDARD).start()
            toggle.contentDescription = "${if (opening) "Collapse" else "Expand"} $title"
        }
        header.setOnClickListener { expand() }; toggle.setOnClickListener { expand() }
        rows += controls to toggle; group.addView(header); group.addView(controls)
        body.addView(group, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        searchable += group to (title + " " + summary()).lowercase()
    }
    fun toggle(parent: LinearLayout, title: String, checked: Boolean, changed: (Boolean) -> Unit) {
        val row = row(parent)
        row.addView(label(title).apply { setPadding(0, 0, dp(12), 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        val control = androidx.appcompat.widget.SwitchCompat(activity).apply {
            contentDescription = title; isChecked = checked; minHeight = dp(44)
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Color.WHITE, 0xFFD0D3DA.toInt()))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(ControlCenter.ACCENT, 0x40FFFFFF))
            setOnCheckedChangeListener { _, value -> changed(value) }
        }
        row.addView(control, LinearLayout.LayoutParams(-2, dp(44)))
        row.setOnClickListener { control.isChecked = !control.isChecked }
    }
    fun choice(parent: LinearLayout, title: String, values: List<String>, initial: Int, changed: (Int) -> Unit) {
        val row = row(parent)
        row.addView(label(title).apply { setPadding(0, 0, dp(10), 0) }, LinearLayout.LayoutParams(0, -2, .43f))
        val value = label(values[initial.coerceIn(values.indices)]).apply {
            gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; textSize = 12.5f
            setPadding(dp(12), dp(6), dp(8), dp(6))
            setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_expand_more, 0); compoundDrawablePadding = dp(2)
            compoundDrawableTintList = ColorStateList.valueOf(0xFFBBBBBB.toInt())
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0x1FFFFFFF); cornerRadius = dp(16).toFloat() }
        }
        row.addView(LinearLayout(activity).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; addView(value) }, LinearLayout.LayoutParams(0, -2, .57f))
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
            progressTintList = ColorStateList.valueOf(ControlCenter.ACCENT); thumbTintList = ColorStateList.valueOf(Color.WHITE)
            setPadding(dp(8), 0, dp(8), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) { value.text = "$progress%"; if (fromUser) changed(progress) }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }, LinearLayout.LayoutParams(-1, dp(48)))
        parent.addView(container, LinearLayout.LayoutParams(-1, -2)); return container
    }
    /** Small group title inside a section. */
    fun heading(parent: LinearLayout, value: String) {
        parent.addView(label(value, 12f).apply {
            setTextColor(ControlCenter.ACCENT); setTypeface(null, android.graphics.Typeface.BOLD); setPadding(0, dp(14), 0, dp(2))
        })
    }
    fun text(parent: LinearLayout, value: String) {
        parent.addView(label(value, 12f).apply { setTextColor(0xffbbbbbb.toInt()); setPadding(0, dp(8), 0, dp(8)) })
    }
    fun attach(parent: LinearLayout) {
        if (twoPane) { attachTwoPane(parent); return }
        (body.parent as? android.view.ViewGroup)?.removeView(body); parent.addView(body)
    }

    private fun badge(icon: Int, accent: Int, size: Int) = ImageView(activity).apply {
        setImageResource(icon); setColorFilter(Color.WHITE); scaleType = ImageView.ScaleType.CENTER
        background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(accent) }
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    private var detailShown: () -> Unit = {}
    private fun attachTwoPane(parent: LinearLayout) {
        val panes = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val detail = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(16))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(ControlCenter.SURFACE); cornerRadius = dp(24).toFloat(); setStroke(dp(1), ControlCenter.STROKE) }
        }
        val rowsByIndex = mutableListOf<View>()
        var selected = 0
        fun show(index: Int) {
            selected = index
            val spec = specs[index]
            lastSection = spec.title
            rowsByIndex.forEachIndexed { i, row -> row.isSelected = i == index; (row.background as? android.graphics.drawable.GradientDrawable)?.setColor(if (i == index) 0x2EFFFFFF else 0) }
            detail.removeAllViews()
            val head = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(12)) }
            head.addView(badge(spec.icon, spec.accent, 44))
            head.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0)
                addView(label(spec.title, 20f).apply { typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) })
                spec.summary().takeIf { it.isNotBlank() }?.let { addView(label(it, 13f).apply { setTextColor(0xFFA9ADB6.toInt()) }) }
            })
            detail.addView(head)
            detail.addView(View(activity).apply { setBackgroundColor(0x14FFFFFF) }, LinearLayout.LayoutParams(-1, dp(1)).apply { bottomMargin = dp(4) })
            val controls = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            controls.apply(spec.content)
            detail.addView(controls)
            detailShown()
            detail.alpha = 0f; detail.translationX = dp(12).toFloat()
            detail.animate().alpha(1f).translationX(0f).setDuration(MotionUi.duration(detail, 220)).setInterpolator(MotionUi.EMPHASIZED_DECELERATE).start()
        }
        // Search filters the section list.
        val searchBox = EditText(activity).apply {
            hint = "Search"; textSize = 14f; setTextColor(Color.WHITE); setHintTextColor(0x99FFFFFF.toInt()); isSingleLine = true
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0); compoundDrawablePadding = dp(10)
            compoundDrawableTintList = ColorStateList.valueOf(0x99FFFFFF.toInt())
            background = android.graphics.drawable.GradientDrawable().apply { setColor(ControlCenter.SURFACE); cornerRadius = dp(22).toFloat(); setStroke(dp(1), ControlCenter.STROKE) }
            setPadding(dp(16), 0, dp(16), 0)
        }
        list.addView(searchBox, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(8) })
        specs.forEachIndexed { index, spec ->
            val row = LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(8)); minimumHeight = dp(56)
                isClickable = true; isFocusable = true
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0) }
                foreground = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x1FFFFFFF), null, android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(-1) })
                setOnClickListener { show(index) }
            }
            row.addView(badge(spec.icon, spec.accent, 32))
            row.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0)
                addView(label(spec.title, 14f).apply { maxLines = 1 })
                spec.summary().takeIf { it.isNotBlank() }?.let { addView(label(it, 11.5f).apply { setTextColor(0xFFA9ADB6.toInt()); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(2) })
            rowsByIndex += row
        }
        searchBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString()?.trim()?.lowercase().orEmpty()
                specs.forEachIndexed { i, spec -> rowsByIndex[i].visibility = if (q.isEmpty() || q in (spec.title + " " + spec.summary()).lowercase()) View.VISIBLE else View.GONE }
            }
        })
        // Classic tablet settings: the list and the options scroll independently.
        fun scroller(child: View) = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(child, FrameLayout.LayoutParams(-1, -2))
        }
        panes.addView(scroller(list), LinearLayout.LayoutParams(dp(300), -1))
        val detailScroll = scroller(detail)
        panes.addView(detailScroll, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(20) })
        parent.addView(panes, LinearLayout.LayoutParams(-1, -1))
        detailShown = { detailScroll.scrollTo(0, 0) }
        if (specs.isNotEmpty()) show(specs.indexOfFirst { it.title == lastSection }.coerceAtLeast(0))
    }
}
