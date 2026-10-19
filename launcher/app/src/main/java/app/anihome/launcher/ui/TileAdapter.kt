package app.anihome.launcher.ui

import android.graphics.Bitmap
import android.widget.ImageView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.content.ContextCompat
import app.anihome.launcher.R
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.anihome.launcher.data.HomeTile
import app.anihome.launcher.data.IconCache
import app.anihome.launcher.data.TileColors
import app.anihome.launcher.databinding.ItemTileBinding
import java.util.Collections

/** TV-style tile grid: landscape cards, logo centred, label under the card. */
class TileAdapter(
    private val onOpen: (HomeTile) -> Unit,
    private val onOptions: (HomeTile) -> Unit,
    private val onRemove: (HomeTile) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
) : ListAdapter<HomeTile, TileAdapter.Holder>(Diff) {

    var editMode = false
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount, PAYLOAD_EDIT)
        }

    var notificationCounts: Map<String, Int> = emptyMap()
        set(value) { if (field == value) return; field = value; notifyItemRangeChanged(0, itemCount, "badge") }
    var showLabels = false
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    init { setHasStableIds(true) }

    override fun getItemId(position: Int) = getItem(position).id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemTileBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains("badge")) holder.applyBadge() else if (payloads.contains(PAYLOAD_EDIT)) holder.applyEditMode() else super.onBindViewHolder(holder, position, payloads)
    }

    /** Moves the item locally during a drag; the caller persists the order when the drag ends. */
    fun move(from: Int, to: Int): List<HomeTile> {
        if (!HomeInteractionPolicy.validMove(from, to, itemCount)) return currentList
        val items = currentList.toMutableList()
        if (from < to) for (i in from until to) Collections.swap(items, i, i + 1)
        else for (i in from downTo to + 1) Collections.swap(items, i, i - 1)
        submitList(items)
        return items
    }

    inner class Holder(private val binding: ItemTileBinding) : RecyclerView.ViewHolder(binding.root) {
        private var tile: HomeTile? = null

        init {
            binding.card.clipToOutline = true
            binding.root.setOnClickListener { tile?.let { if (editMode) onOptions(it) else onOpen(it) } }
            binding.root.setOnLongClickListener {
                tile?.let { t -> if (editMode) onStartDrag(this) else onOptions(t) }
                true
            }
            binding.remove.setOnClickListener { tile?.let(onRemove) }
        }

        fun bind(tile: HomeTile) {
            this.tile = tile
            binding.label.text = tile.label
            binding.label.visibility = if (showLabels) View.VISIBLE else View.GONE
            binding.root.contentDescription = tile.label
            val context = binding.root.context
            val artwork = tile.artworkPath?.let(IconCache::fileIcon)
            val bitmap: Bitmap? = artwork ?: when (tile.kind) {
                HomeTile.Kind.APP -> tile.iconPath?.let(IconCache::fileIcon) ?: IconCache.appIcon(context, tile.packageName!!, tile.activityName)
                HomeTile.Kind.WEB -> tile.iconPath?.let(IconCache::fileIcon)
            }
            binding.card.foreground = ContextCompat.getDrawable(context,
                if (artwork != null) R.drawable.bg_tile_card else R.drawable.bg_tile_glass)
            val color = tile.color ?: TileColors.fromIcon(bitmap)
            val badgeColors = BannerBadgeColors.choose(artwork, color)
            DrawableCompat.setTint(binding.notificationBadge.background.mutate(), badgeColors.background)
            binding.notificationBadge.setTextColor(badgeColors.text)
            val fill = DrawableCompat.wrap(binding.card.background.mutate())
            DrawableCompat.setTint(fill, color)
            binding.card.background = fill
            if (bitmap != null) {
                binding.icon.setImageBitmap(bitmap)
                binding.icon.visibility = View.VISIBLE
                binding.monogram.visibility = View.GONE
                binding.icon.scaleType = if (artwork != null) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
                val inset = if (artwork != null) 0f else if (tile.isApp) 0.22f else 0.08f
                binding.icon.post {
                    val w = binding.card.width
                    val h = binding.card.height
                    binding.icon.setPadding((w * inset).toInt(), (h * inset).toInt(), (w * inset).toInt(), (h * inset).toInt())
                }
            } else {
                binding.icon.setImageDrawable(null)
                binding.icon.visibility = View.GONE
                binding.monogram.text = tile.label.take(2).uppercase()
                binding.monogram.setTextColor(TileColors.onColor(color))
                binding.monogram.visibility = View.VISIBLE
            }
            applyEditMode()
        }

        fun applyBadge() {
            val count = tile?.takeIf { it.isApp }?.packageName?.let { notificationCounts[it] } ?: 0
            BadgeUi.bind(binding.notificationBadge, if (editMode) 0 else count)
            binding.root.contentDescription = tile?.label.orEmpty() + if (count > 0) ", $count unread notifications" else ""
        }
        fun applyEditMode() {
            applyBadge()
            binding.remove.visibility = if (editMode) View.VISIBLE else View.GONE
            binding.root.animate().cancel()
            binding.root.scaleX = if (editMode) 0.96f else 1f
            binding.root.scaleY = if (editMode) 0.96f else 1f
        }
    }

    private object Diff : DiffUtil.ItemCallback<HomeTile>() {
        override fun areItemsTheSame(oldItem: HomeTile, newItem: HomeTile) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: HomeTile, newItem: HomeTile) = oldItem == newItem
    }

    private companion object { const val PAYLOAD_EDIT = "edit" }
}
