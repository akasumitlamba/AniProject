package app.anihome.launcher.ui

import android.app.WallpaperManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.anihome.launcher.R
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Choose a wallpaper from AniHome's collection, the gallery or another app, then frame it for
 * this exact screen in portrait and in landscape (drag to move, pinch to zoom) and apply it.
 */
class WallpaperActivity : AppCompatActivity() {
    private val store by lazy { WallpaperStore(this) }
    private lateinit var grid: RecyclerView
    private lateinit var loading: ProgressBar
    private lateinit var message: TextView
    private lateinit var browse: View
    private lateinit var editor: View
    private lateinit var crop: WallpaperCropView
    private lateinit var portraitChip: MaterialButton
    private lateinit var landscapeChip: MaterialButton
    private lateinit var applyButton: MaterialButton
    private var target = WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
    private var image: Bitmap? = null
    private val items = mutableListOf<WallpaperStore.Item>()
    /** Collection headers and wallpapers, in display order. */
    private val rows = mutableListOf<Any>()
    /** null = all categories. Categories are the collection's folders on GitHub. */
    private var category: String? = null
    private lateinit var categories: LinearLayout
    private val thumbs = Dispatchers.IO.limitedParallelism(3)

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(::edit) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (short, long) = screenSize()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xff0b1423.toInt()); setPadding(dp(16), dp(12), dp(16), dp(12)) }
        val toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        toolbar.addView(ArtworkUi.icon(this, R.drawable.ic_back, "Back") { onBackPressedDispatcher.onBackPressed() })
        toolbar.addView(ArtworkUi.title(this, "Wallpaper"), LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(12) })
        root.addView(toolbar)

        // Browse: AniHome's collection, plus the gallery and other wallpaper apps.
        val sources = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8)) }
        sources.addView(ArtworkUi.chip(this, "Gallery", R.drawable.ic_gallery) { picker.launch("image/*") }, LinearLayout.LayoutParams(0, dp(48), 1f))
        sources.addView(ArtworkUi.chip(this, "Other apps", R.drawable.ic_apps) {
            runCatching { startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Choose wallpaper")) }
                .onFailure { Toast.makeText(this, "No other wallpaper apps are installed", Toast.LENGTH_SHORT).show() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        grid = RecyclerView(this).apply {
            val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
            val spans = if (widthDp >= 840) 5 else if (widthDp >= 600) 4 else 3
            layoutManager = GridLayoutManager(this@WallpaperActivity, spans).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() { override fun getSpanSize(position: Int) = if (rows.getOrNull(position) is String) spans else 1 }
            }
            adapter = Thumbs(); clipToPadding = false
        }
        loading = ProgressBar(this).apply { isIndeterminate = true; indeterminateTintList = android.content.res.ColorStateList.valueOf(ControlCenter.ACCENT) }
        message = TextView(this).apply { textSize = 14f; setTextColor(0xffb9ccdc.toInt()); gravity = Gravity.CENTER; visibility = View.GONE; setPadding(dp(24), 0, dp(24), 0) }
        val listArea = FrameLayout(this).apply {
            addView(grid, FrameLayout.LayoutParams(-1, -1))
            addView(loading, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
            addView(message, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        }
        categories = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        browse = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(sources)
            addView(android.widget.HorizontalScrollView(this@WallpaperActivity).apply {
                isHorizontalScrollBarEnabled = false; addView(categories)
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
            addView(listArea, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        root.addView(browse, LinearLayout.LayoutParams(-1, 0, 1f))

        // Edit: this screen's exact shape in both orientations; drag and pinch to frame each.
        crop = WallpaperCropView(this, short, long)
        val orientations = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) }
        portraitChip = ArtworkUi.chip(this, "Portrait", R.drawable.ic_rotation) { showOrientation(WallpaperCropView.Orientation.PORTRAIT) }.apply { isCheckable = true }
        landscapeChip = ArtworkUi.chip(this, "Landscape", R.drawable.ic_rotation) { showOrientation(WallpaperCropView.Orientation.LANDSCAPE) }.apply { isCheckable = true }
        orientations.addView(portraitChip, LinearLayout.LayoutParams(0, dp(48), 1f))
        orientations.addView(landscapeChip, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        orientations.addView(ArtworkUi.icon(this, R.drawable.ic_refresh, "Reset framing") { crop.reset() }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) })
        val hint = TextView(this).apply {
            text = "Drag to choose the area · Pinch to zoom · Frame portrait and landscape separately"
            textSize = 12f; gravity = Gravity.CENTER; setTextColor(0xffaabbd0.toInt()); setPadding(0, dp(8), 0, dp(4))
        }
        val targets = LinearLayout(this).apply { gravity = Gravity.CENTER }
        val targetChips = listOf(
            "Home & lock" to (WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK),
            "Home" to WallpaperManager.FLAG_SYSTEM, "Lock" to WallpaperManager.FLAG_LOCK)
            .map { (label, flags) -> ArtworkUi.chip(this, label, R.drawable.ic_check) { }.apply { isCheckable = true; tag = flags } }
        // Only the chosen target is bright and ticked.
        fun choose(chosen: MaterialButton) {
            target = chosen.tag as Int
            targetChips.forEach { chip ->
                val on = chip === chosen
                chip.isChecked = on; chip.alpha = if (on) 1f else .55f
                if (on) chip.setIconResource(R.drawable.ic_check) else chip.icon = null
            }
        }
        targetChips.forEachIndexed { i, chip ->
            chip.setOnClickListener { choose(chip) }
            targets.addView(chip, LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (i > 0) marginStart = dp(6) })
        }
        choose(targetChips[0])
        applyButton = ArtworkUi.chip(this, "Set wallpaper", R.drawable.ic_check) { apply() }
        editor = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            addView(crop, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(orientations); addView(hint)
            addView(targets, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            addView(applyButton, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        }
        root.addView(editor, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(left = bars.left + dp(16), top = bars.top + dp(12), right = bars.right + dp(16), bottom = bars.bottom + dp(12)); insets
        }
        showOrientation(if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) WallpaperCropView.Orientation.LANDSCAPE else WallpaperCropView.Orientation.PORTRAIT)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (editor.visibility == View.VISIBLE) showBrowse() else finish() }
        })
        loadCollection()
    }

    private fun screenSize(): Pair<Int, Int> {
        val bounds = if (Build.VERSION.SDK_INT >= 30) windowManager.maximumWindowMetrics.bounds else Rect().also {
            val m = android.util.DisplayMetrics(); @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(m); it.set(0, 0, m.widthPixels, m.heightPixels)
        }
        return minOf(bounds.width(), bounds.height()) to maxOf(bounds.width(), bounds.height())
    }

    private fun showOrientation(o: WallpaperCropView.Orientation) {
        crop.orientation = o
        portraitChip.isChecked = o == WallpaperCropView.Orientation.PORTRAIT
        landscapeChip.isChecked = o == WallpaperCropView.Orientation.LANDSCAPE
        portraitChip.alpha = if (portraitChip.isChecked) 1f else .55f
        landscapeChip.alpha = if (landscapeChip.isChecked) 1f else .55f
    }
    private fun showBrowse() { editor.visibility = View.GONE; browse.visibility = View.VISIBLE }

    private fun loadCollection() {
        loading.visibility = View.VISIBLE; message.visibility = View.GONE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { store.list() } }
            loading.visibility = View.GONE
            items.clear(); items += result.getOrDefault(emptyList())
            buildCategories()
            showCategory(category)
            if (items.isEmpty()) {
                message.visibility = View.VISIBLE
                message.text = (if (result.isFailure) (result.exceptionOrNull() as? NoSuchElementException)?.message ?: "Couldn't reach AniHome's wallpapers. Check your connection." else "No AniHome wallpapers yet.") +
                    "\n\nYou can still use Gallery or Other apps above."
            }
        }
    }

    private fun label(group: String) = if (group.isEmpty()) "More" else WallpaperStore.title(group)

    /** One chip per folder, plus All. */
    private fun buildCategories() {
        categories.removeAllViews()
        val groups = items.map { it.group }.distinct()
        categories.visibility = if (groups.size > 1) View.VISIBLE else View.GONE
        (listOf<String?>(null) + groups).forEach { group ->
            val count = if (group == null) items.size else items.count { it.group == group }
            categories.addView(ArtworkUi.textChip(this, "${group?.let(::label) ?: "All"} · $count") { showCategory(group) }.apply { tag = group ?: "" },
                LinearLayout.LayoutParams(-2, dp(44)).apply { marginEnd = dp(6) })
        }
    }

    private fun showCategory(group: String?) {
        category = group?.takeIf { g -> items.any { it.group == g } }
        for (i in 0 until categories.childCount) {
            val chip = categories.getChildAt(i)
            chip.alpha = if ((chip.tag as String) == (category ?: "")) 1f else .5f
        }
        rows.clear()
        items.filter { category == null || it.group == category }.groupBy { it.group }.forEach { (g, list) ->
            if (category == null) rows.add(label(g)) // Headers only when showing every category.
            rows.addAll(list)
        }
        grid.adapter?.notifyDataSetChanged()
        grid.scrollToPosition(0)
    }

    /** Opens the editor with an image, sized to stay sharp on this screen without using too much memory. */
    private fun edit(uri: Uri) {
        browse.visibility = View.GONE; editor.visibility = View.VISIBLE
        applyButton.isEnabled = false
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { runCatching { decode(uri, maxOf(screenSize().second, 1600)) }.getOrNull() }
            if (bitmap == null) { Toast.makeText(this@WallpaperActivity, "This image can't be opened", Toast.LENGTH_SHORT).show(); showBrowse(); return@launch }
            image = bitmap; crop.setImage(bitmap); applyButton.isEnabled = true
        }
    }

    private fun decode(uri: Uri, maxSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }!!
    }

    private fun apply() {
        val bitmap = image ?: return
        val portrait = crop.crop(WallpaperCropView.Orientation.PORTRAIT) ?: return
        val landscape = crop.crop(WallpaperCropView.Orientation.LANDSCAPE) ?: return
        val (short, long) = screenSize()
        applyButton.isEnabled = false; applyButton.text = "Setting…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { setWallpaper(bitmap, portrait, landscape, short, long) } }
            applyButton.isEnabled = true; applyButton.text = "Set wallpaper"
            result.onSuccess { both ->
                Toast.makeText(this@WallpaperActivity,
                    if (both) "Wallpaper set" else "Wallpaper set. This Android version uses one framing for both orientations, so the ${if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"} one was used.",
                    Toast.LENGTH_LONG).show()
                finish()
            }.onFailure { Toast.makeText(this@WallpaperActivity, "Android didn't accept the wallpaper", Toast.LENGTH_LONG).show() }
        }
    }

    /**
     * Android 15 and newer keep a separate framing per screen shape. Older versions take one
     * framing, so the current orientation's is used. Returns true when both framings were applied.
     */
    private fun setWallpaper(bitmap: Bitmap, portrait: Rect, landscape: Rect, short: Int, long: Int): Boolean {
        val manager = WallpaperManager.getInstance(this)
        val crops = linkedMapOf(Point(short, long) to portrait, Point(long, short) to landscape)
        val perOrientation = runCatching {
            WallpaperManager::class.java.getMethod("setBitmapWithCrops", Bitmap::class.java, Map::class.java, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(manager, bitmap, crops, true, target); true
        }.getOrDefault(false)
        if (perOrientation) return true
        val landscapeNow = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val chosen = if (landscapeNow) landscape else portrait
        val framed = Bitmap.createBitmap(bitmap, chosen.left, chosen.top, chosen.width(), chosen.height())
        manager.setBitmap(framed, null, true, target)
        if (framed !== bitmap) framed.recycle()
        return false
    }

    private inner class Thumbs : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemViewType(position: Int) = if (rows[position] is String) 1 else 0
        inner class Header(val text: TextView) : RecyclerView.ViewHolder(text)
        inner class Holder(val card: FrameLayout, val image: ImageView, val label: TextView, val spinner: ProgressBar) : RecyclerView.ViewHolder(card) { var job: Job? = null }
        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == 1) return Header(TextView(this@WallpaperActivity).apply {
                textSize = 13f; setTextColor(ControlCenter.ACCENT); setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(dp(6), dp(14), 0, dp(6)); layoutParams = RecyclerView.LayoutParams(-1, -2)
            })
            val image = object : androidx.appcompat.widget.AppCompatImageView(this@WallpaperActivity) {
                override fun onMeasure(w: Int, h: Int) { val width = View.MeasureSpec.getSize(w); super.onMeasure(w, View.MeasureSpec.makeMeasureSpec((width * 1.6f).toInt(), View.MeasureSpec.EXACTLY)) }
            }.apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = ArtworkUi.surface(this@WallpaperActivity, 0xff0e1628.toInt()); clipToOutline = true }
            val label = TextView(this@WallpaperActivity).apply { textSize = 11f; setTextColor(-1); setShadowLayer(4f, 0f, 1f, 0xcc000000.toInt()); setPadding(dp(8), 0, dp(8), dp(8)); maxLines = 1 }
            val spinner = ProgressBar(this@WallpaperActivity).apply { isIndeterminate = true; indeterminateTintList = android.content.res.ColorStateList.valueOf(0x99FFFFFF.toInt()) }
            val card = FrameLayout(this@WallpaperActivity).apply {
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
                isFocusable = true; isClickable = true; foreground = getDrawable(R.drawable.bg_drawer_item)
                addView(image, FrameLayout.LayoutParams(-1, -2))
                addView(spinner, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
                addView(label, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            }
            return Holder(card, image, label, spinner)
        }
        override fun onBindViewHolder(viewHolder: RecyclerView.ViewHolder, position: Int) {
            if (viewHolder is Header) { viewHolder.text.text = rows[position] as String; return }
            val holder = viewHolder as Holder
            val item = rows[position] as WallpaperStore.Item
            holder.job?.cancel(); holder.image.setImageDrawable(null); holder.spinner.visibility = View.VISIBLE
            holder.label.text = item.name; holder.card.contentDescription = "${item.name} wallpaper"
            holder.card.setOnClickListener {
                lifecycleScope.launch {
                    val file = withContext(Dispatchers.IO) { runCatching { store.image(item) }.getOrNull() }
                    if (file == null) Toast.makeText(this@WallpaperActivity, "Couldn't download ${item.name}", Toast.LENGTH_SHORT).show()
                    else edit(Uri.fromFile(file))
                }
            }
            holder.job = lifecycleScope.launch {
                val bitmap = withContext(thumbs) { runCatching {
                    val file = store.image(item)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(file.path, bounds)
                    var sample = 1; while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 480) sample *= 2
                    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                }.getOrNull() }
                holder.spinner.visibility = View.GONE
                holder.image.setImageBitmap(bitmap)
            }
        }
        override fun onViewRecycled(holder: RecyclerView.ViewHolder) { if (holder is Holder) { holder.job?.cancel(); holder.image.setImageDrawable(null) } }
    }
}
