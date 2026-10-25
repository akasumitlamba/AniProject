package app.anihome.launcher.ui

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject

/** One adapter and one coordinate space: widgets no longer occupy a separate section. */
internal class HomeBoardAdapter(context: Context, private val tiles: TileAdapter, private val widgets: WidgetController) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val prefs = context.getSharedPreferences("home_board", 0)
    private val desired = linkedMapOf<String, HomeBoardPacking.Cell>()
    var priority: String? = null
    var editing = false
    var placements = emptyList<HomeBoardPacking.Placement>()
        private set
    private val widgetAdapter = widgets.homeAdapter
    init {
        runCatching {
            val saved = JSONObject(prefs.getString("cells", "{}")!!)
            saved.keys().forEach { key -> val cell = saved.getJSONObject(key); desired[key] = HomeBoardPacking.Cell(cell.getInt("x"), cell.getInt("y")) }
        }
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
        val items = tiles.currentList.map { HomeBoardPacking.Item("tile:${it.id}", if (it.iconOnly) 1 else 2, if (it.iconOnly) 1 else 2) } +
            (0 until widgetAdapter.itemCount).map { HomeBoardPacking.Item("widget:${widgets.idAt(it)}", widgets.columnsAt(it), widgets.rowsAt(it)) }
        placements = HomeBoardPacking.pack(items, columns, desired, priority)
        return placements
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
        prefs.edit().putString("cells", saved.toString()).apply()
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
