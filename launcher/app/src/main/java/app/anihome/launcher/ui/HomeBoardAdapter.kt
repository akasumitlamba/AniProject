package app.anihome.launcher.ui

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject

/**
 * One adapter and one coordinate space: widgets no longer occupy a separate section.
 * Each grid width keeps its own saved layout, so rotating a tablet between a 6- and an
 * 8-cell board never strands tiles in the old width or leaves an empty column.
 */
internal class HomeBoardAdapter(context: Context, private val tiles: TileAdapter, private val widgets: WidgetController) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val prefs = context.getSharedPreferences("home_board", 0)
    private val homePrefs = context.getSharedPreferences("anihome", 0)
    /** Automatic arrangement closes every gap; free arrangement keeps items where they were put. */
    private val autoArrange get() = homePrefs.getBoolean("home_auto_arrange", true)
    private val desired = linkedMapOf<String, HomeBoardPacking.Cell>()
    var priority: String? = null
    /** Fine-grid geometry, set by the layout manager before each pack. */
    var grid = HomeGrid(2, 5)
    var editing = false
    var loaded = false
    var placements = emptyList<HomeBoardPacking.Placement>()
        private set
    private val widgetAdapter = widgets.homeAdapter
    /** Grid width (in cells) that `desired` and `placements` currently describe. */
    private var activeColumns = 0
    /** Set when a width is shown for the first time: reflow the previous reading order. */
    private var reflowFrom: List<HomeBoardPacking.Placement>? = null
    private var adoptedLegacy = false
    private fun decode(json: String?): Map<String, HomeBoardPacking.Cell>? = json?.let {
        runCatching {
            val saved = JSONObject(it)
            saved.keys().asSequence().associateWith { key -> saved.getJSONObject(key).let { c -> HomeBoardPacking.Cell(c.getInt("x"), c.getInt("y")) } }
        }.getOrNull()
    }
    private fun switchColumns(columns: Int) {
        val previous = placements
        desired.clear(); reflowFrom = null
        // The unversioned "cells" layout predates per-width layouts; it belongs to the first width shown.
        val saved = decode(prefs.getString("layout_$columns", null))
        val legacy = if (saved == null && activeColumns == 0 && prefs.all.keys.none { it.startsWith("layout_") }) decode(prefs.getString("cells", null)) else null
        adoptedLegacy = legacy != null
        if (saved != null) desired.putAll(saved) else if (legacy != null) desired.putAll(legacy)
        else if (previous.isNotEmpty()) reflowFrom = previous
        else olderLayout()?.let { reflowFrom = it } // A new grid: keep the reading order of the last saved one.
        activeColumns = columns
        placements = emptyList()
    }
    /** The most recently written layout for another grid, as placements, to reflow in its reading order. */
    private fun olderLayout(): List<HomeBoardPacking.Placement>? {
        val key = prefs.getString("last_layout_key", null)?.takeIf { prefs.contains(it) }
            ?: prefs.all.keys.filter { it.startsWith("layout_") }.maxByOrNull { it.removePrefix("layout_").toIntOrNull() ?: 0 } ?: return null
        val cells = decode(prefs.getString(key, null)) ?: return null
        return cells.map { (k, c) -> HomeBoardPacking.Placement(k, c.x, c.y, 1, 1) }.takeIf { it.isNotEmpty() }
    }
    /** Close every gap in reading order (after a tile changes between banner and icon). */
    fun reflow() { reflowFrom = placements.takeIf { it.isNotEmpty() } }

    init {
        fun observer(widget: Boolean) = object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() { notifyDataSetChanged() }
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) { notifyItemRangeChanged(positionStart + if (widget) tiles.itemCount else 0, itemCount, payload) }
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) { notifyItemRangeChanged(positionStart + if (widget) tiles.itemCount else 0, itemCount) }
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { notifyItemRangeInserted(positionStart + if (widget) tiles.itemCount else 0, itemCount) }
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { notifyItemRangeRemoved(positionStart + if (widget) tiles.itemCount else 0, itemCount) }
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) { if (itemCount == 1) notifyItemMoved(fromPosition + if (widget) tiles.itemCount else 0, toPosition + if (widget) tiles.itemCount else 0) else notifyDataSetChanged() }
        }
        tiles.registerAdapterDataObserver(observer(false)); widgetAdapter.registerAdapterDataObserver(observer(true))
    }
    fun keyAt(position: Int): String? = if (position < 0 || position >= itemCount) null else if (position < tiles.itemCount) "tile:${tiles.currentList[position].id}" else "widget:${widgets.idAt(position - tiles.itemCount)}"
    fun positionOfTile(index: Int) = index
    fun positionOfWidget(id: Int): Int = widgets.indexOf(id).takeIf { it >= 0 }?.plus(tiles.itemCount) ?: RecyclerView.NO_POSITION
    fun isWidget(position: Int) = position >= tiles.itemCount && position < itemCount
    fun pack(columns: Int): List<HomeBoardPacking.Placement> {
        if (dragOrigin != null && columns == activeColumns) return placements
        if (columns != activeColumns) { dragOrigin = null; previewCell = null; switchColumns(columns) }
        val g = grid
        // Icons snap to icon columns, banners to banner slots (two rows tall), widgets to widget cells.
        val items = tiles.currentList.map {
            if (it.iconOnly) HomeBoardPacking.Item("tile:${it.id}", g.iconSpan, 1, g.iconSpan, 1)
            else HomeBoardPacking.Item("tile:${it.id}", g.bannerSpan, 2, g.bannerSpan, 2)
        } + (0 until widgetAdapter.itemCount).map { HomeBoardPacking.Item("widget:${widgets.idAt(it)}", widgets.columnsAt(it) * g.widgetUnit, widgets.rowsAt(it), g.widgetUnit, 1) }
        // Reserve every existing item before placing newly added apps or widgets.
        placements.forEach { desired.putIfAbsent(it.key, HomeBoardPacking.Cell(it.x, it.y)) }
        val active = items.map { it.key }.toSet()
        val removed = placements.any { it.key !in active }
        val reflow = reflowFrom
        placements = when {
            reflow != null -> HomeBoardPacking.compact(items, columns, reflow)
            removed -> HomeBoardPacking.compact(items, columns, placements)
            else -> HomeBoardPacking.pack(items, columns, desired, priority)
        }
        // Automatic arrangement: always pack in reading order, so no gap survives a move, removal or resize.
        if (autoArrange && dragOrigin == null && placements.isNotEmpty()) placements = HomeBoardPacking.compact(items, columns, placements)
        // A legacy layout saved on a narrower board leaves a whole tile column empty; reflow it once.
        if (adoptedLegacy && placements.isNotEmpty() && placements.maxOf { it.x + it.columns } <= columns - grid.bannerSpan) {
            placements = HomeBoardPacking.compact(items, columns, placements)
            desired.clear()
        }
        // Tiles load asynchronously; keep the one-time check pending until there is something to place.
        if (placements.isNotEmpty()) adoptedLegacy = false
        if (removed || reflow != null) { priority = null; desired.clear(); reflowFrom = null }
        if (loaded) persist()
        return placements
    }
    private var dragOrigin: List<HomeBoardPacking.Placement>? = null
    private var previewCell: HomeBoardPacking.Cell? = null
    val previewing: Boolean get() = dragOrigin != null
    fun beginDrag() {
        dragOrigin = placements.toList()
        previewCell = null
    }
    fun previewMove(key: String, x: Int, y: Int, columns: Int): Boolean {
        val origin = dragOrigin ?: return false
        val item = origin.firstOrNull { it.key == key } ?: return false
        val g = grid
        fun HomeBoardPacking.Placement.packingItem() = when {
            !this.key.startsWith("tile:") -> HomeBoardPacking.Item(this.key, this.columns, this.rows, g.widgetUnit, 1)
            this.columns == g.iconSpan && this.rows == 1 -> HomeBoardPacking.Item(this.key, this.columns, 1, g.iconSpan, 1)
            else -> HomeBoardPacking.Item(this.key, this.columns, this.rows, g.bannerSpan, 2)
        }
        val cell = HomeBoardPacking.snap(item.packingItem(), columns, x, y)
        if (cell == previewCell) return false
        previewCell = cell
        val anchors = origin.associate { it.key to HomeBoardPacking.Cell(it.x, it.y) }.toMutableMap()
        anchors[key] = cell
        placements = HomeBoardPacking.pack(origin.map { it.packingItem() }, columns, anchors, key)
        return true
    }
    fun finishDrag(cancelled: Boolean) {
        val origin = dragOrigin ?: return
        if (cancelled) placements = origin
        dragOrigin = null; previewCell = null; priority = null
        desired.clear()
        persist()
        notifyDataSetChanged()
    }
    private var geometryOrigin: HomeBoardPacking.Cell? = null
    fun beginGeometryChange(key: String) {
        placements.forEach { desired[it.key] = HomeBoardPacking.Cell(it.x, it.y) }
        priority = key
        geometryOrigin = desired[key]
    }
    fun resizeAnchor(key: String, dx: Int, dy: Int) {
        geometryOrigin?.let { desired[key] = HomeBoardPacking.Cell((it.x + dx).coerceAtLeast(0), (it.y + dy).coerceAtLeast(0)) }
    }
    fun moveToCell(key: String, x: Int, y: Int, columns: Int) {
        placements.forEach { desired[it.key] = HomeBoardPacking.Cell(it.x, it.y) }
        val span = placements.firstOrNull { it.key == key }?.columns ?: 1
        desired[key] = HomeBoardPacking.Cell(x.coerceIn(0, columns - span.coerceAtMost(columns)), y.coerceIn(0, 10000))
        priority = key
        pack(columns)
        persist()
        notifyDataSetChanged()
    }
    fun persist() {
        placements.forEach { desired[it.key] = HomeBoardPacking.Cell(it.x, it.y) }
        val active = (0 until itemCount).mapNotNull(::keyAt).toSet()
        desired.keys.retainAll(active)
        val saved = JSONObject()
        desired.forEach { (key, cell) -> saved.put(key, JSONObject().put("x", cell.x).put("y", cell.y)) }
        val encoded = saved.toString()
        if (activeColumns <= 0) return
        if (prefs.getString("layout_$activeColumns", null) != encoded) prefs.edit().putString("layout_$activeColumns", encoded).putString("last_layout_key", "layout_$activeColumns")
            // Interim per-width keys from a pre-release build may hold an unreflowed layout.
            .apply { prefs.all.keys.filter { it.startsWith("cells_") }.forEach(::remove) }.apply()
    }

    override fun getItemCount() = tiles.itemCount + widgetAdapter.itemCount
    override fun getItemViewType(position: Int) = if (position < tiles.itemCount) 0 else 1
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == 0) tiles.onCreateViewHolder(parent, 0) else widgetAdapter.onCreateViewHolder(parent, 0)
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (position < tiles.itemCount) tiles.bindViewHolder(holder as TileAdapter.Holder, position)
        else widgetAdapter.bindViewHolder(holder, position - tiles.itemCount)
    }
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (position < tiles.itemCount && payloads.isNotEmpty()) tiles.onBindViewHolder(holder as TileAdapter.Holder, position, payloads)
        else onBindViewHolder(holder, position)
    }
    override fun findRelativeAdapterPositionIn(adapter: RecyclerView.Adapter<out RecyclerView.ViewHolder>, viewHolder: RecyclerView.ViewHolder, localPosition: Int): Int = when (adapter) {
        this -> localPosition
        tiles -> if (localPosition < tiles.itemCount) localPosition else RecyclerView.NO_POSITION
        widgetAdapter -> if (localPosition >= tiles.itemCount) localPosition - tiles.itemCount else RecyclerView.NO_POSITION
        else -> RecyclerView.NO_POSITION
    }
    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is TileAdapter.Holder) tiles.onViewRecycled(holder) else widgetAdapter.onViewRecycled(holder)
    }
}
