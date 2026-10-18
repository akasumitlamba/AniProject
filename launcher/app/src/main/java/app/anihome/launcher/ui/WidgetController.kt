package app.anihome.launcher.ui

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.widget.ScrollView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import androidx.core.content.ContextCompat
import app.anihome.launcher.R
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject

/** Local widget host. One TV tile is two columns by two rows of conventional widget cells. */
class WidgetController(private val activity: AppCompatActivity, private val grid: RecyclerView, private val onDragging: (Boolean) -> Unit = {}) {
    private data class Entry(val id: Int, var columns: Int, var rows: Int, val kind: String = BuiltInWidgetPolicy.PROVIDER, var note: String = "")
    private val prefs = activity.getSharedPreferences("home_widgets", 0)
    private val manager = AppWidgetManager.getInstance(activity)
    private val host = AppWidgetHost(activity, 1024)
    private var columns = HomeLayout.columns(activity) * 2
    private val entries = mutableListOf<Entry>()
    private var pendingId = prefs.getInt("pending", -1)
    private var editing = false
    private val adapter = WidgetAdapter()
    private val dragHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
    ) {
        override fun isLongPressDragEnabled() = false
        override fun onMove(view: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
            val start = from.bindingAdapterPosition
            val end = to.bindingAdapterPosition
            if (!editing || !HomeInteractionPolicy.validMove(start, end, entries.size)) return false
            entries.add(end, entries.removeAt(start))
            adapter.notifyItemMoved(start, end)
            return true
        }
        override fun onSelectedChanged(holder: RecyclerView.ViewHolder?, state: Int) {
            super.onSelectedChanged(holder, state)
            onDragging(state == ItemTouchHelper.ACTION_STATE_DRAG)
        }
        override fun clearView(view: RecyclerView, holder: RecyclerView.ViewHolder) {
            super.clearView(view, holder)
            onDragging(false)
            save()
        }
        override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) = Unit
    })
    private val builtIns = BuiltInWidgets(activity)
    private val bind = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) configureOrAdd() else cancelAdding()
    }

    init {
        runCatching {
            val saved = JSONArray(prefs.getString("items", "[]"))
            for (i in 0 until saved.length()) {
                val item = saved.getJSONObject(i)
                entries += Entry(item.getInt("id"), item.getInt("columns").coerceIn(1, 12), item.getInt("rows").coerceIn(1, 100), item.optString("kind", BuiltInWidgetPolicy.PROVIDER), item.optString("note", "").take(2000))
            }
        }
        // Reclaim allocations left behind by a killed process before pending state was saved.
        runCatching {
            host.appWidgetIds.filter { id -> id != pendingId && entries.none { BuiltInWidgetPolicy.usesProviderHost(it.kind, it.id) && it.id == id } }
                .forEach { host.deleteAppWidgetId(it) }
        }
        val layout = GridLayoutManager(activity, columns)
        layout.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = entries[position].columns.coerceAtMost(columns)
        }
        grid.layoutManager = layout
        grid.adapter = adapter
        dragHelper.attachToRecyclerView(grid)
        grid.isNestedScrollingEnabled = false
        grid.itemAnimator = null
        grid.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) grid.post { adapter.notifyDataSetChanged() }
        }
        refresh()
    }

    fun onActivityResult(requestCode: Int, resultCode: Int): Boolean {
        if (requestCode != CONFIGURE_REQUEST) return false
        if (resultCode == Activity.RESULT_OK) finishAdding() else cancelAdding()
        return true
    }

    fun start() { runCatching { host.startListening() }; builtIns.start(); refresh() }
    fun stop() { builtIns.stop(); runCatching { host.stopListening() } }
    fun updateLayout() {
        val next = HomeLayout.columns(activity) * 2
        if (next == columns) return
        columns = next
        (grid.layoutManager as GridLayoutManager).spanCount = columns
        refresh()
    }
    fun setEditMode(value: Boolean) {
        editing = value
        if (!value) { dragHelper.attachToRecyclerView(null); dragHelper.attachToRecyclerView(grid) }
        adapter.notifyDataSetChanged()
    }

    fun pick() {
        if (pendingId != -1) {
            MaterialAlertDialogBuilder(activity).setMessage("A widget setup is unfinished. Cancel it and choose another?")
                .setNegativeButton("Keep", null).setPositiveButton("Choose another") { _, _ -> cancelAdding(); pick() }.show()
            return
        }
        val providers = runCatching { manager.installedProviders.sortedBy { it.loadLabel(activity.packageManager).lowercase() } }
            .getOrElse { emptyList() }
        val kinds = BuiltInWidgetPolicy.kinds
        val providerStart = kinds.size + 2
        val pickerColumns = if (activity.resources.displayMetrics.widthPixels / activity.resources.displayMetrics.density >= 600) 2 else 1
        fun isHeading(position: Int) = position == 0 || position == providerStart - 1
        val picker = RecyclerView(activity).apply {
            layoutManager = GridLayoutManager(activity, pickerColumns).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int) = if (isHeading(position)) pickerColumns else 1
                }
            }
            setPadding(dp(8), dp(8), dp(8), dp(8))
            clipToPadding = false
        }
        val dialog = MaterialAlertDialogBuilder(activity).setTitle("Choose a widget")
            .setView(picker).setNegativeButton("Close", null).create()
        picker.adapter = object : RecyclerView.Adapter<PreviewHolder>() {
            override fun getItemCount() = providers.size + providerStart
            override fun getItemViewType(position: Int) = if (isHeading(position)) 1 else 0
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PreviewHolder {
                val card = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(dp(6), dp(6), dp(6), dp(6))
                    }
                    background = GradientDrawable().apply { setColor(ContextCompat.getColor(activity, R.color.ink)); cornerRadius = dp(12).toFloat() }
                    isFocusable = true
                    isClickable = true
                }
                if (viewType == 1) { card.background = null; card.isClickable = false; card.isFocusable = false }
                return PreviewHolder(card)
            }
            override fun onBindViewHolder(holder: PreviewHolder, position: Int) {
                holder.card.removeAllViews()
                if (isHeading(position)) {
                    holder.card.addView(TextView(activity).apply {
                        text = if (position == 0) "AniHome widgets" else if (providers.isEmpty()) "No widgets from other apps installed" else "From your apps"
                        textSize = 18f; setTextColor(ContextCompat.getColor(activity, R.color.text))
                        setTypeface(null, android.graphics.Typeface.BOLD)
                    })
                    holder.card.setOnClickListener(null)
                    return
                }
                if (position < providerStart - 1) {
                    val kind = kinds[position - 1]
                    holder.card.addView(builtIns.create(kind), LinearLayout.LayoutParams(-1, dp(160)))
                    holder.card.addView(TextView(activity).apply { text = builtIns.title(kind); textSize = 17f; setTextColor(ContextCompat.getColor(activity, R.color.text)); setPadding(0, dp(8), 0, dp(4)) })
                    holder.card.addView(TextView(activity).apply { text = "Included with AniHome · 2 × 2 cells"; setTextColor(ContextCompat.getColor(activity, R.color.muted)) })
                    holder.card.contentDescription = "${builtIns.title(kind)}, included with AniHome, preview widget"
                    holder.card.setOnClickListener { dialog.dismiss(); confirmBuiltIn(kind) }
                    return
                }
                val info = providers[position - providerStart]
                val label = info.loadLabel(activity.packageManager)
                holder.card.addView(preview(info), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(160)))
                holder.card.addView(TextView(activity).apply {
                    text = label
                    textSize = 17f
                    setTextColor(ContextCompat.getColor(activity, R.color.text))
                    setPadding(0, dp(8), 0, dp(4))
                })
                holder.card.addView(TextView(activity).apply {
                    text = "${sizeLabel(info)} · Tap to preview and add"
                    setTextColor(ContextCompat.getColor(activity, R.color.muted))
                })
                holder.card.contentDescription = "$label, ${sizeLabel(info)}, preview widget"
                holder.card.setOnClickListener { dialog.dismiss(); confirmWidget(info) }
            }
        }
        dialog.setOnShowListener {
            picker.layoutParams.height = (activity.resources.displayMetrics.heightPixels * 0.65f).toInt()
            picker.requestLayout()
        }
        dialog.show()
    }

    private fun confirmBuiltIn(kind: String) {
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        content.addView(builtIns.create(kind), LinearLayout.LayoutParams(-1, dp(180)))
        content.addView(TextView(activity).apply {
            text = when (kind) {
                BuiltInWidgetPolicy.CLOCK -> "Uses your device's time format and local date. No setup needed."
                BuiltInWidgetPolicy.BATTERY -> "Live battery level and charging status. No setup needed."
                else -> "Tap the widget to write a note. It stays in AniHome on this device."
            } + "\nStarts at 2 × 2 cells, the space of one app tile. Resize or move it in Edit mode."
            setTextColor(ContextCompat.getColor(activity, R.color.text)); setPadding(0, dp(12), 0, 0)
        })
        MaterialAlertDialogBuilder(activity).setTitle(builtIns.title(kind)).setView(content)
            .setNegativeButton("Back") { _, _ -> pick() }.setPositiveButton("Add widget") { _, _ ->
                entries += Entry(BuiltInWidgetPolicy.nextId(entries.map { it.id }), 2.coerceAtMost(columns), 2, kind)
                save(); refresh()
            }.show()
    }
    private fun editNote(entry: Entry) {
        if (entry.kind != BuiltInWidgetPolicy.NOTE) return
        val input = android.widget.EditText(activity).apply {
            setText(entry.note); setSelection(text.length)
            hint = "Your note"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3; maxLines = 8
            filters = arrayOf(android.text.InputFilter.LengthFilter(2000))
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        MaterialAlertDialogBuilder(activity).setTitle("Quick note").setView(input)
            .setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ -> entry.note = input.text.toString(); save(); refresh() }.show()
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun sizeLabel(info: AppWidgetProviderInfo): String {
        val density = activity.resources.displayMetrics.density
        val width = WidgetSizing.cellsFromPixels(info.minWidth, density, cellWidthDp(), columns)
        val height = WidgetSizing.cellsFromPixels(info.minHeight, density, cellHeightDp(), 100)
        return "$width × $height cells"
    }

    /** Provider artwork first; otherwise display its real layout resource without allocating a widget ID. */
    private fun preview(info: AppWidgetProviderInfo): View {
        val frame = FrameLayout(activity).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply { setColor(ContextCompat.getColor(activity, R.color.ink_soft)); cornerRadius = dp(12).toFloat() }
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val drawable = runCatching { info.loadPreviewImage(activity, activity.resources.displayMetrics.densityDpi) }.getOrNull()
        if (drawable != null) {
            frame.addView(ImageView(activity).apply { setImageDrawable(drawable); scaleType = ImageView.ScaleType.FIT_CENTER },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            return frame
        }
        val layout = if (Build.VERSION.SDK_INT >= 31 && info.previewLayout != 0) info.previewLayout else info.initialLayout
        val rendered = if (layout != 0) runCatching {
            RemoteViews(info.provider.packageName, layout).apply(activity, frame)
        }.getOrNull() else null
        if (rendered != null) {
            // Avoid launching provider actions from an unconfigured sample.
            fun disableActions(view: View) {
                view.isClickable = false; view.isLongClickable = false; view.isFocusable = false
                if (view is ViewGroup) for (i in 0 until view.childCount) disableActions(view.getChildAt(i))
            }
            disableActions(rendered)
            frame.addView(rendered, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } else {
            val fallback = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
            fallback.addView(ImageView(activity).apply { setImageDrawable(runCatching { info.loadIcon(activity, activity.resources.displayMetrics.densityDpi) }.getOrNull()) },
                LinearLayout.LayoutParams(dp(64), dp(64)))
            fallback.addView(TextView(activity).apply {
                text = "Preview unavailable"; gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(activity, R.color.text))
            })
            frame.addView(fallback, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        // The enclosing provider card handles selection, even when the sample contains controls.
        frame.addView(View(activity), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return frame
    }

    private fun confirmWidget(info: AppWidgetProviderInfo) {
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        content.addView(preview(info), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)))
        content.addView(TextView(activity).apply {
            text = "${sizeLabel(info)} on your home screen. One app tile occupies 2 × 2 cells.\nThis is the provider's sample; live content appears after setup."
            setTextColor(ContextCompat.getColor(activity, R.color.text)); setPadding(0, dp(12), 0, 0)
        })
        MaterialAlertDialogBuilder(activity).setTitle(info.loadLabel(activity.packageManager)).setView(ScrollView(activity).apply { addView(content) })
            .setNegativeButton("Back") { _, _ -> pick() }
            .setPositiveButton("Add widget") { _, _ -> addProvider(info) }.show()
    }

    private fun addProvider(info: AppWidgetProviderInfo) {
        runCatching {
            pendingId = host.allocateAppWidgetId()
            prefs.edit().putInt("pending", pendingId).apply()
            if (manager.bindAppWidgetIdIfAllowed(pendingId, info.profile, info.provider, Bundle())) configureOrAdd()
            else bind.launch(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingId)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile))
        }.onFailure { cancelAdding(); unavailable() }
    }

    private class PreviewHolder(val card: LinearLayout) : RecyclerView.ViewHolder(card)

    private fun configureOrAdd() {
        val info = runCatching { manager.getAppWidgetInfo(pendingId) }.getOrNull() ?: return cancelAdding()
        if (info.configure == null) finishAdding()
        else runCatching {
            host.startAppWidgetConfigureActivityForResult(activity, pendingId, 0, CONFIGURE_REQUEST, null)
        }.onFailure { cancelAdding(); Toast.makeText(activity, "Widget setup unavailable", Toast.LENGTH_SHORT).show() }
    }

    private fun finishAdding() {
        val info = runCatching { manager.getAppWidgetInfo(pendingId) }.getOrNull() ?: return cancelAdding()
        val cellWidth = cellWidthDp()
        val cellHeight = cellHeightDp()
        val initialColumns = WidgetSizing.cellsFromPixels(info.minWidth, activity.resources.displayMetrics.density, cellWidth, columns)
        val initialRows = WidgetSizing.cellsFromPixels(info.minHeight, activity.resources.displayMetrics.density, cellHeight, 100)
        if (entries.none { it.id == pendingId }) entries += Entry(pendingId, initialColumns, initialRows)
        pendingId = -1
        prefs.edit().remove("pending").apply()
        save(); refresh()
    }

    private fun cancelAdding() {
        if (pendingId > 0) runCatching { host.deleteAppWidgetId(pendingId) }
        pendingId = -1
        prefs.edit().remove("pending").apply()
    }

    private fun cellWidthDp(): Float {
        val density = activity.resources.displayMetrics.density
        val width = if (grid.width > 0) grid.width / density else activity.resources.displayMetrics.widthPixels / density - 28
        return width / columns
    }
    private fun cellHeightDp() = cellWidthDp() / 1.62f + 20f
    private fun save() {
        val array = JSONArray()
        entries.forEach { array.put(JSONObject().put("id", it.id).put("columns", it.columns).put("rows", it.rows).put("kind", it.kind).put("note", it.note)) }
        prefs.edit().putString("items", array.toString()).apply()
    }
    private fun refresh() {
        val removed = runCatching { entries.filter { BuiltInWidgetPolicy.usesProviderHost(it.kind, it.id) && manager.getAppWidgetInfo(it.id) == null } }.getOrElse { return }
        removed.forEach { runCatching { host.deleteAppWidgetId(it.id) } }
        if (entries.removeAll(removed.toSet())) save()
        grid.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        adapter.notifyDataSetChanged()
    }
    private fun options(entry: Entry) {
        MaterialAlertDialogBuilder(activity).setTitle("Widget").setItems(arrayOf("Resize", "Move earlier", "Move later", "Remove")) { _, index ->
            when (index) {
                0 -> resize(entry)
                1, 2 -> {
                    val from = entries.indexOf(entry)
                    val to = (from + if (index == 1) -1 else 1).coerceIn(0, entries.lastIndex)
                    entries.removeAt(from); entries.add(to, entry); save(); refresh()
                }
                3 -> MaterialAlertDialogBuilder(activity).setMessage("Remove this widget?")
                    .setNegativeButton("Cancel", null).setPositiveButton("Remove") { _, _ ->
                        if (BuiltInWidgetPolicy.usesProviderHost(entry.kind, entry.id)) runCatching { host.deleteAppWidgetId(entry.id) }; entries.remove(entry); save(); refresh()
                    }.show()
            }
        }.show()
    }
    private fun unavailable() = Toast.makeText(activity, "Widget service unavailable. Try again.", Toast.LENGTH_SHORT).show()

    private fun resize(entry: Entry) {
        val builtin = BuiltInWidgetPolicy.isBuiltIn(entry.kind)
        val info = if (BuiltInWidgetPolicy.usesProviderHost(entry.kind, entry.id)) runCatching { manager.getAppWidgetInfo(entry.id) }.getOrNull() else null
        if (!builtin && info == null) return
        val density = activity.resources.displayMetrics.density
        val horizontal = builtin || info!!.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0
        val vertical = builtin || info!!.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0
        val minColumns = if (builtin) BuiltInWidgetPolicy.minimumColumns(entry.kind).coerceAtMost(columns) else WidgetSizing.cellsFromPixels(if (horizontal) info!!.minResizeWidth else info!!.minWidth, density, cellWidthDp(), columns)
        val minRows = if (builtin) BuiltInWidgetPolicy.minimumRows(entry.kind) else WidgetSizing.cellsFromPixels(if (vertical) info!!.minResizeHeight else info!!.minHeight, density, cellHeightDp(), 100)
        var maxColumns = columns
        var maxRows = maxOf(12, minRows, entry.rows)
        if (Build.VERSION.SDK_INT >= 31 && info != null) {
            if (info.maxResizeWidth > 0) maxColumns = WidgetSizing.maximumCells(info.maxResizeWidth, density, cellWidthDp(), minColumns, columns)
            if (info.maxResizeHeight > 0) maxRows = WidgetSizing.maximumCells(info.maxResizeHeight, density, cellHeightDp(), minRows, maxRows)
        }
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 16) }
        val width = android.widget.NumberPicker(activity).apply {
            minValue = if (horizontal) minColumns else entry.columns
            maxValue = if (horizontal) maxColumns else entry.columns
            value = entry.columns.coerceIn(minValue, maxValue)
            isEnabled = horizontal
            contentDescription = "Widget width in cells"
        }
        val height = android.widget.NumberPicker(activity).apply {
            minValue = if (vertical) minRows else entry.rows
            maxValue = if (vertical) maxRows else entry.rows
            value = entry.rows.coerceIn(minValue, maxValue)
            isEnabled = vertical
            contentDescription = "Widget height in cells"
        }
        content.addView(TextView(activity).apply { text = "Width in cells (one app tile = 2 × 2 cells)" }); content.addView(width)
        content.addView(TextView(activity).apply { text = "Height in cells" }); content.addView(height)
        if (!horizontal || !vertical) content.addView(TextView(activity).apply { text = "This widget's provider fixes some dimensions." })
        MaterialAlertDialogBuilder(activity).setTitle("Resize widget").setView(ScrollView(activity).apply { addView(content) }).setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                entry.columns = width.value
                entry.rows = height.value
                save(); refresh()
            }.show()
    }

    private inner class WidgetAdapter : RecyclerView.Adapter<WidgetHolder>() {
        override fun getItemCount() = entries.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WidgetHolder {
            val container = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            return WidgetHolder(container)
        }
        override fun onBindViewHolder(holder: WidgetHolder, position: Int) {
            val entry = entries[position]
            holder.container.removeAllViews()
            val builtin = BuiltInWidgetPolicy.isBuiltIn(entry.kind)
            val info = if (BuiltInWidgetPolicy.usesProviderHost(entry.kind, entry.id)) runCatching { manager.getAppWidgetInfo(entry.id) }.getOrNull() else null
            if (!builtin && info == null) { holder.container.addView(TextView(activity).apply { text = "Widget unavailable" }); return }
            if (editing) holder.container.addView(Button(activity).apply {
                text = "${if (builtin) builtIns.title(entry.kind) else info!!.loadLabel(activity.packageManager)} · Edit"
                contentDescription = "Edit widget; hold and drag to rearrange"
                setOnClickListener { options(entry) }
                setOnLongClickListener { dragHelper.startDrag(holder); true }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val height = (entry.rows * cellHeightDp() * activity.resources.displayMetrics.density).toInt()
            if (builtin) {
                holder.container.addView(builtIns.create(entry.kind, entry.note) { editNote(entry) }, LinearLayout.LayoutParams(-1, height))
                return
            }
            val view = runCatching { host.createView(activity, entry.id, info!!) }.getOrElse {
                holder.container.addView(TextView(activity).apply { text = "Widget unavailable" })
                return
            }
            val frame = FrameLayout(activity)
            frame.addView(view, FrameLayout.LayoutParams(-1, -1))
            holder.container.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
            holder.container.post {
                val density = activity.resources.displayMetrics.density
                val widthDp = (holder.container.width - holder.container.paddingLeft - holder.container.paddingRight) / density
                val heightDp = height / density
                if (widthDp > 0) runCatching { view.updateAppWidgetSize(Bundle(), widthDp.toInt(), heightDp.toInt(), widthDp.toInt(), heightDp.toInt()) }
            }
        }
    }
    private companion object { const val CONFIGURE_REQUEST = 5020 }

    private class WidgetHolder(val container: LinearLayout) : RecyclerView.ViewHolder(container)
}
