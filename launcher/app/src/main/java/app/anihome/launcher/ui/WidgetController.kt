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
class WidgetController(private val activity: AppCompatActivity, private val grid: RecyclerView, private val onDragging: (Boolean) -> Unit = {}, private val onChanged: () -> Unit = {}) {
    private data class Entry(val id: Int, var columns: Int, var rows: Int, val kind: String = BuiltInWidgetPolicy.PROVIDER, var note: String = "")
    private val prefs = activity.getSharedPreferences("home_widgets", 0)
    private val manager = AppWidgetManager.getInstance(activity)
    private val host = AppWidgetHost(activity, 1024)
    private var columns = HomeLayout.columns(activity) * 2
    private val entries = mutableListOf<Entry>()
    val hasWidgets: Boolean get() = entries.isNotEmpty()
    private var pendingId = prefs.getInt("pending", -1)
    private var editing = false
    private var resizingId: Int? = null
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
        if (!value) resizingId = null
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
        val dialog = MaterialAlertDialogBuilder(activity).setTitle("Widgets")
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
                    background = GradientDrawable().apply { setColor(0xff202020.toInt()); cornerRadius = dp(12).toFloat() }
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
                        textSize = 14f; setTextColor(ContextCompat.getColor(activity, R.color.text))
                        setTypeface(null, android.graphics.Typeface.BOLD)
                    })
                    holder.card.setOnClickListener(null)
                    return
                }
                if (position < providerStart - 1) {
                    val kind = kinds[position - 1]
                    holder.card.addView(builtIns.create(kind), LinearLayout.LayoutParams(-1, dp(120)))
                    holder.card.addView(TextView(activity).apply { text = builtIns.title(kind); textSize = 15f; setTextColor(ContextCompat.getColor(activity, R.color.text)); setPadding(0, dp(8), 0, dp(4)) })
                    holder.card.addView(TextView(activity).apply { text = "Included with AniHome · 2 × 2 cells"; setTextColor(ContextCompat.getColor(activity, R.color.muted)) })
                    holder.card.contentDescription = "${builtIns.title(kind)}, included with AniHome, preview widget"
                    holder.card.setOnClickListener { dialog.dismiss(); confirmBuiltIn(kind) }
                    return
                }
                val info = providers[position - providerStart]
                val label = info.loadLabel(activity.packageManager)
                holder.card.addView(preview(info), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)))
                holder.card.addView(TextView(activity).apply {
                    text = label
                    textSize = 15f
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
        // Provider info can be temporarily absent during restore or an app update.
        // Keep the allocation and a removable placeholder until explicit removal.
        grid.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        adapter.notifyDataSetChanged()
        onChanged()
    }
    private fun options(entry: Entry, anchor: View = grid) {
        val title = if (BuiltInWidgetPolicy.isBuiltIn(entry.kind)) builtIns.title(entry.kind)
            else runCatching { manager.getAppWidgetInfo(entry.id)?.loadLabel(activity.packageManager)?.toString() }.getOrNull() ?: "Widget"
        val actions = mutableListOf<HomeMenu.Item>()
        val limits = limits(entry)
        if (limits != null && (limits.horizontal || limits.vertical)) actions += HomeMenu.Item(1, "Resize", R.drawable.ic_widget_resize, "Drag the widget edges") {
            resizingId = entry.id; editing = true; adapter.notifyDataSetChanged()
        }
        if (entry.kind == BuiltInWidgetPolicy.NOTE) actions += HomeMenu.Item(2, "Edit note", R.drawable.ic_edit) { editNote(entry) }
        fun move(delta: Int) {
            val from = entries.indexOf(entry)
            val to = (from + delta).coerceIn(0, entries.lastIndex)
            if (from >= 0 && from != to) { entries.removeAt(from); entries.add(to, entry); save(); refresh() }
        }
        if (entries.indexOf(entry) > 0) actions += HomeMenu.Item(3, "Move earlier", R.drawable.ic_widget_move) { move(-1) }
        if (entries.indexOf(entry) < entries.lastIndex) actions += HomeMenu.Item(4, "Move later", R.drawable.ic_widget_move) { move(1) }
        actions += HomeMenu.Item(5, "Remove widget", R.drawable.ic_close) {
            MaterialAlertDialogBuilder(activity).setTitle("Remove widget?").setMessage("You can add it again from Widgets.")
                .setNegativeButton("Cancel", null).setPositiveButton("Remove") { _, _ ->
                    if (BuiltInWidgetPolicy.usesProviderHost(entry.kind, entry.id)) runCatching { host.deleteAppWidgetId(entry.id) }
                    entries.remove(entry); if (resizingId == entry.id) resizingId = null; save(); refresh()
                }.show()
        }
        HomeMenu.show(anchor, title, actions)
    }
    private fun unavailable() = Toast.makeText(activity, "Widget service unavailable. Try again.", Toast.LENGTH_SHORT).show()
    private data class SizeLimits(val horizontal: Boolean, val vertical: Boolean, val minColumns: Int, val maxColumns: Int, val minRows: Int, val maxRows: Int)
    private fun limits(entry: Entry): SizeLimits? {
        val builtin = BuiltInWidgetPolicy.isBuiltIn(entry.kind)
        val info = if (BuiltInWidgetPolicy.usesProviderHost(entry.kind, entry.id)) runCatching { manager.getAppWidgetInfo(entry.id) }.getOrNull() else null
        if (!builtin && info == null) return null
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
        return SizeLimits(horizontal, vertical,
            if (horizontal) minColumns else entry.columns.coerceAtMost(columns), if (horizontal) maxColumns else entry.columns.coerceAtMost(columns),
            if (vertical) minRows else entry.rows, if (vertical) maxRows else entry.rows)
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
            if (!builtin && info == null) {
                holder.container.addView(Button(activity).apply {
                    text = "Widget unavailable · Remove"
                    setOnClickListener { options(entry) }
                })
                return
            }
            val title = if (builtin) builtIns.title(entry.kind) else info!!.loadLabel(activity.packageManager).toString()
            if (editing) {
                val header = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
                header.addView(TextView(activity).apply {
                    text = title; textSize = 12f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(0xffd9e2e9.toInt()); setPadding(dp(8), 0, 0, 0)
                }, LinearLayout.LayoutParams(0, dp(48), 1f))
                header.addView(ArtworkUi.plainIcon(activity, R.drawable.ic_widget_move, "Move $title; hold and drag") {}.apply {
                    setOnLongClickListener { dragHelper.startDrag(holder); true }
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
                header.addView(ArtworkUi.plainIcon(activity, R.drawable.ic_more, "$title options") { options(entry, it) }, LinearLayout.LayoutParams(dp(48), dp(48)))
                holder.container.addView(header)
            }
            val height = (entry.rows * cellHeightDp() * activity.resources.displayMetrics.density).toInt()
            val frame = WidgetResizeFrame(activity).apply {
                this.editing = this@WidgetController.editing
                resizeSelected = editing && resizingId == entry.id
                setPadding(dp(6), dp(6), dp(6), dp(6))
                setOnClickListener {
                    if (this@WidgetController.editing && resizingId != entry.id) { resizingId = entry.id; adapter.notifyDataSetChanged() }
                }
                contentDescription = if (editing) "$title, ${entry.columns} by ${entry.rows} cells. Select, then drag edges to resize." else null
                importantForAccessibility = if (editing) View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            }
            val live = if (builtin) builtIns.create(entry.kind, entry.note) { editNote(entry) }
                else runCatching { host.createView(activity, entry.id, info!!) }.getOrElse {
                    holder.container.addView(TextView(activity).apply { text = "Widget unavailable" }); return
                }
            frame.addView(live, FrameLayout.LayoutParams(-1, -1))
            holder.container.addView(frame, LinearLayout.LayoutParams(-1, height))
            fun updateProvider() {
                if (live is android.appwidget.AppWidgetHostView) frame.post {
                    val density = activity.resources.displayMetrics.density
                    val widthDp = (frame.width - frame.paddingLeft - frame.paddingRight) / density
                    val heightDp = (frame.height - frame.paddingTop - frame.paddingBottom) / density
                    if (live.isAttachedToWindow && widthDp > 0 && heightDp > 0) runCatching {
                        live.updateAppWidgetSize(Bundle(), widthDp.toInt(), heightDp.toInt(), widthDp.toInt(), heightDp.toInt())
                    }
                }
            }
            frame.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
                if (r - l != oldR - oldL || b - t != oldB - oldT) updateProvider()
            }
            val bounds = limits(entry)
            if (bounds != null) {
                frame.horizontal = bounds.horizontal; frame.vertical = bounds.vertical
                var startColumns = entry.columns; var startRows = entry.rows
                var cellWidth = 1f; var cellHeight = 1f
                fun applySize(c: Int, r: Int) {
                    entry.columns = c.coerceIn(bounds.minColumns, bounds.maxColumns)
                    entry.rows = r.coerceIn(bounds.minRows, bounds.maxRows)
                    frame.layoutParams = frame.layoutParams.apply { this.height = (entry.rows * cellHeightDp() * activity.resources.displayMetrics.density).toInt() }
                    (grid.layoutManager as GridLayoutManager).spanSizeLookup.invalidateSpanIndexCache()
                    grid.requestLayout()
                    frame.contentDescription = "$title, ${entry.columns} by ${entry.rows} cells. Drag edges to resize."
                    androidx.core.view.ViewCompat.setStateDescription(frame, "${entry.columns} by ${entry.rows} cells")
                    updateProvider()
                }
                frame.onStart = {
                    startColumns = entry.columns; startRows = entry.rows
                    cellWidth = cellWidthDp() * activity.resources.displayMetrics.density
                    cellHeight = cellHeightDp() * activity.resources.displayMetrics.density
                    onDragging(true)
                }
                frame.onResize = { dx, dy ->
                    val c = WidgetSizing.dragCells(startColumns, dx, cellWidth, bounds.minColumns, bounds.maxColumns)
                    val r = WidgetSizing.dragCells(startRows, dy, cellHeight, bounds.minRows, bounds.maxRows)
                    if (c != entry.columns || r != entry.rows) applySize(c, r)
                }
                frame.onEnd = { cancelled ->
                    if (cancelled) applySize(startColumns, startRows) else save()
                    onDragging(false)
                }
                if (editing) {
                    fun action(label: String, dc: Int, dr: Int) {
                        androidx.core.view.ViewCompat.addAccessibilityAction(frame, label) { _, _ -> applySize(entry.columns + dc, entry.rows + dr); save(); true }
                    }
                    if (bounds.horizontal) { action("Wider", 1, 0); action("Narrower", -1, 0) }
                    if (bounds.vertical) { action("Taller", 0, 1); action("Shorter", 0, -1) }
                }
            } else frame.resizeSelected = false
            updateProvider()
        }
    }
    private companion object { const val CONFIGURE_REQUEST = 5020 }

    private class WidgetHolder(val container: LinearLayout) : RecyclerView.ViewHolder(container)
}
