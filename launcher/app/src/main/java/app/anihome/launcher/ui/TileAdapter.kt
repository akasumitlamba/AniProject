package app.anihome.launcher.ui

import android.graphics.Bitmap
import android.widget.ImageView
import android.view.LayoutInflater
import android.view.View
import android.view.MotionEvent
import android.animation.ValueAnimator
import android.view.ViewGroup
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.content.ContextCompat
import app.anihome.launcher.R
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import app.anihome.launcher.data.HomeTile
import app.anihome.launcher.data.IconCache
import app.anihome.launcher.data.TileColors
import app.anihome.launcher.databinding.ItemTileBinding
import java.util.Collections
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.findViewTreeLifecycleOwner
import kotlinx.coroutines.*

/** TV-style tile grid: landscape cards, logo centred, label under the card. */
class TileAdapter(
    private val onOpen: (HomeTile) -> Unit,
    private val onOptions: (HomeTile) -> Unit,
    private val onRemove: (HomeTile) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
) : RecyclerView.Adapter<TileAdapter.Holder>() {

    var currentList: List<HomeTile> = emptyList()
        private set
    override fun getItemCount() = currentList.size
    private fun getItem(position: Int) = currentList[position]
    fun submitList(items: List<HomeTile>) {
        val old = currentList
        val next = items.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = next.size
            override fun areItemsTheSame(a: Int, b: Int) = Diff.areItemsTheSame(old[a], next[b])
            override fun areContentsTheSame(a: Int, b: Int) = Diff.areContentsTheSame(old[a], next[b])
        })
        currentList = next
        diff.dispatchUpdatesTo(this)
    }

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
        if (payloads.isNotEmpty() && payloads.all { it == "badge" || it == PAYLOAD_EDIT }) {
            if (payloads.contains(PAYLOAD_EDIT)) holder.applyEditMode(true) else holder.applyBadge()
        } else super.onBindViewHolder(holder, position, payloads)
    }

    override fun onViewRecycled(holder: Holder) { holder.recycle() }

    /** Moves the item locally during a drag; the caller persists the order when the drag ends. */
    fun move(from: Int, to: Int): List<HomeTile> {
        if (!HomeInteractionPolicy.validMove(from, to, itemCount)) return currentList
        val items = currentList.toMutableList()
        if (from < to) for (i in from until to) Collections.swap(items, i, i + 1)
        else for (i in from downTo to + 1) Collections.swap(items, i, i - 1)
        currentList = items
        notifyItemMoved(from, to)
        return items
    }

    inner class Holder(private val binding: ItemTileBinding) : RecyclerView.ViewHolder(binding.root) {
        private var tile: HomeTile? = null
        private var artworkJob: Job? = null
        fun recycle() {
            artworkJob?.cancel()
            artworkJob = null
            tile = null
            binding.icon.setImageDrawable(null)
            binding.root.animate().cancel()
            binding.remove.animate().cancel()
        }

        init {
            // Composite the opaque corner tag and artwork before clipping their common outline.
            // Independent antialiased curves let the banner colour show through the tag edge.
            binding.cardSurface.clipToOutline = true
            binding.cardSurface.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            binding.card.clipToOutline = false
            binding.root.setOnClickListener { tile?.let { if (editMode) onOptions(it) else onOpen(it) } }
            binding.root.setOnLongClickListener {
                tile?.let { onStartDrag(this) }
                true
            }
            binding.root.setOnTouchListener { _, event ->
                if (!editMode && ValueAnimator.areAnimatorsEnabled()) {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> binding.root.animate().scaleX(.97f).scaleY(.97f).setDuration(80).start()
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> binding.root.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    }
                }
                false
            }
            binding.remove.setOnClickListener { tile?.let(onRemove) }
        }

        fun bind(tile: HomeTile) {
            this.tile = tile
            binding.label.text = tile.label
            binding.label.visibility = if (showLabels) View.VISIBLE else View.GONE
            binding.root.contentDescription = tile.label
            val context = binding.root.context
            binding.root.setPadding(0, 0, 0, 0)
            binding.root.gravity = if (tile.iconOnly) android.view.Gravity.CENTER else android.view.Gravity.CENTER_HORIZONTAL
            binding.cardFrame.iconMode = tile.iconOnly
            binding.cardFrame.iconLabelSpace = if (tile.iconOnly && showLabels) ArtworkUi.dp(context, 16) else 0
            binding.label.includeFontPadding = !tile.iconOnly
            binding.cardFrame.layoutParams = binding.cardFrame.layoutParams.apply { width = if (tile.iconOnly) ArtworkUi.dp(context, 64) else ViewGroup.LayoutParams.MATCH_PARENT }
            binding.label.layoutParams = (binding.label.layoutParams as android.widget.LinearLayout.LayoutParams).apply { topMargin = ArtworkUi.dp(context, if (tile.iconOnly) 2 else 8) }
            binding.label.textSize = if (tile.iconOnly) 12f else 14f
            binding.cardSurface.background = if (tile.iconOnly) android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT) else ContextCompat.getDrawable(context, R.drawable.bg_card_fill)
            binding.cardSurface.elevation = if (tile.iconOnly) 0f else ArtworkUi.dp(context, 6).toFloat()
            binding.cardSurface.clipToOutline = !tile.iconOnly
            binding.card.background = if (tile.iconOnly) android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT) else ContextCompat.getDrawable(context, R.drawable.bg_card_fill)
            binding.notificationBadge.setBackgroundResource(if (tile.iconOnly) R.drawable.bg_notification_badge else R.drawable.bg_banner_notification_tag)
            binding.notificationBadge.layoutParams = (binding.notificationBadge.layoutParams as android.widget.FrameLayout.LayoutParams).apply { height = ArtworkUi.dp(context, if (tile.iconOnly) 18 else 28) }
            binding.notificationBadge.minWidth = ArtworkUi.dp(context, if (tile.iconOnly) 18 else 44)
            binding.notificationBadge.setPadding(ArtworkUi.dp(context, if (tile.iconOnly) 4 else 12), 0, ArtworkUi.dp(context, 4), ArtworkUi.dp(context, if (tile.iconOnly) 0 else 7))
            binding.notificationBadge.textSize = if (tile.iconOnly) 9f else 11f
            artworkJob?.cancel()
            // Clear recycled artwork immediately; disk decode and package artwork stay off the UI thread.
            val cachedArt = if (tile.iconOnly) null else tile.artworkPath?.let(IconCache::cachedFileIcon)
            val cachedIcon = if (tile.iconOnly) IconCache.cachedDockIcon(tile.packageName!!, tile.activityName)
                else cachedArt ?: tile.iconPath?.let(IconCache::cachedFileIcon)
                    ?: tile.packageName?.let { IconCache.cachedAppIcon(it, tile.activityName) }
            renderArtwork(tile, cachedArt, cachedIcon)
            val requestedImageCached = when {
                tile.iconOnly -> cachedIcon != null
                tile.artworkPath != null -> cachedArt != null
                tile.iconPath != null -> IconCache.cachedFileIcon(tile.iconPath) != null
                else -> cachedIcon != null
            }
            if (requestedImageCached) {
                applyEditMode()
                return
            }
            val owner = binding.root.findViewTreeLifecycleOwner() ?: context as? LifecycleOwner
            artworkJob = owner?.lifecycleScope?.launch {
                val images = withContext(Dispatchers.IO) {
                    val artwork = if (tile.iconOnly) null else tile.artworkPath?.let(IconCache::fileIcon)
                    val bitmap: Bitmap? = if (tile.iconOnly) IconCache.dockIcon(context, tile.packageName!!, tile.activityName) else artwork ?: when (tile.kind) {
                        HomeTile.Kind.APP -> tile.iconPath?.let(IconCache::fileIcon) ?: IconCache.appIcon(context, tile.packageName!!, tile.activityName)
                        HomeTile.Kind.WEB -> tile.iconPath?.let(IconCache::fileIcon)
                    }
                    artwork to bitmap
                }
                if (this@Holder.tile == tile) renderArtwork(tile, images.first, images.second)
            }
            applyEditMode()
        }

        private fun renderArtwork(tile: HomeTile, artwork: Bitmap?, bitmap: Bitmap?) {
            val context = binding.root.context
            binding.card.foreground = if (tile.iconOnly) null else ContextCompat.getDrawable(context,
                if (artwork != null) R.drawable.bg_tile_card else R.drawable.bg_tile_glass)
            val color = tile.color ?: TileColors.fromIcon(bitmap)
            val badgeColors = BannerBadgeColors.choose(artwork, color)
            DrawableCompat.setTint(binding.notificationBadge.background.mutate(), badgeColors.background)
            binding.notificationBadge.setTextColor(badgeColors.text)
            if (!tile.iconOnly) {
                val fill = DrawableCompat.wrap(binding.card.background.mutate())
                DrawableCompat.setTint(fill, color); binding.card.background = fill
            }
            if (bitmap != null) {
                binding.icon.setImageBitmap(bitmap)
                binding.icon.visibility = View.VISIBLE
                binding.monogram.visibility = View.GONE
                binding.icon.scaleType = if (artwork != null) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
                val inset = if (tile.iconOnly || artwork != null) 0f else if (tile.isApp) 0.22f else 0.08f
                binding.icon.post {
                    if (this.tile != tile) return@post
                    val w = binding.card.width
                    val h = binding.card.height
                    binding.icon.setPadding((w * inset).toInt(), (h * inset).toInt(), (w * inset).toInt(), (h * inset).toInt())
                }
            } else {
                binding.icon.setImageDrawable(null)
                binding.icon.visibility = View.GONE
                binding.monogram.text = if (showLabels) tile.label.take(2).uppercase() else tile.label
                binding.monogram.textSize = if (showLabels) 32f else 18f
                binding.monogram.maxLines = 2
                binding.monogram.ellipsize = android.text.TextUtils.TruncateAt.END
                binding.monogram.setTextColor(TileColors.onColor(color))
                binding.monogram.visibility = View.VISIBLE
            }
        }

        fun applyBadge() {
            val count = tile?.takeIf { it.isApp }?.packageName?.let { notificationCounts[it] } ?: 0
            BadgeUi.bind(binding.notificationBadge, if (editMode) 0 else count)
            binding.root.contentDescription = tile?.label.orEmpty() + if (count > 0) ", $count unread notifications" else ""
        }
        fun applyEditMode(animate: Boolean = false) {
            applyBadge()
            binding.root.animate().cancel()
            binding.remove.animate().cancel()
            val scale = if (editMode) .96f else 1f
            if (animate && ValueAnimator.areAnimatorsEnabled()) {
                binding.root.animate().scaleX(scale).scaleY(scale).setDuration(160).start()
                if (editMode) { binding.remove.alpha = 0f; binding.remove.visibility = View.VISIBLE }
                binding.remove.animate().alpha(if (editMode) 1f else 0f).setDuration(160).withEndAction {
                    binding.remove.visibility = if (editMode) View.VISIBLE else View.GONE
                }.start()
            } else {
                binding.root.scaleX = scale; binding.root.scaleY = scale
                binding.remove.visibility = if (editMode) View.VISIBLE else View.GONE
                binding.remove.alpha = if (editMode) 1f else 0f
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<HomeTile>() {
        override fun areItemsTheSame(oldItem: HomeTile, newItem: HomeTile) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: HomeTile, newItem: HomeTile) = oldItem == newItem
    }

    private companion object { const val PAYLOAD_EDIT = "edit" }
}
