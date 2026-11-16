package app.anihome.launcher.ui

import android.graphics.BitmapFactory
import android.view.View
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.widget.ImageButton
import app.anihome.launcher.R
import com.google.android.material.button.MaterialButton
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.anihome.launcher.banners.BannerApiException
import app.anihome.launcher.banners.BannerEntry
import app.anihome.launcher.banners.BannerRepository
import app.anihome.launcher.banners.BannerRules
import app.anihome.launcher.banners.SiteMatcher
import app.anihome.launcher.data.HomeTile
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Browse locally. Selecting a banner only loads the existing crop editor; save remains explicit. */
class BannerStoreDialog(private val activity: AppCompatActivity, private val tile: HomeTile,
    private val onSelect: (File) -> Unit, private val onGallery: () -> Unit, private val onAI: () -> Unit) {
    private val repository = BannerRepository(activity)
    private val scope = CoroutineScope(activity.lifecycleScope.coroutineContext + SupervisorJob(activity.lifecycleScope.coroutineContext[Job]))
    private var entries = emptyList<BannerEntry>()
    private var suggestedEntry: BannerEntry? = null
    private var allowNetwork = false
    private var favouritesOnly = true
    private lateinit var status: TextView
    private lateinit var refreshButton: ImageButton
    private var statusDetails = ""
    private var retryOffered = false
    private var networkGeneration = 0
    private lateinit var adapter: Banners
    private lateinit var dialog: androidx.appcompat.app.AppCompatDialog
    private lateinit var query: EditText
    private lateinit var loading: android.widget.ProgressBar
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    fun show() {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(12))
            background = ArtworkUi.surface(activity)
        }
        val toolbar = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        toolbar.addView(ArtworkUi.title(activity, "Banners"), LinearLayout.LayoutParams(0, dp(48), 1f))
        refreshButton = ArtworkUi.icon(activity, R.drawable.ic_refresh, "Refresh banners") { reload(true, retryOffered) }
        toolbar.addView(refreshButton)
        toolbar.addView(ArtworkUi.icon(activity, R.drawable.ic_more, "More banner options") { anchor ->
            HomeMenu.show(anchor, "Banner options", listOf(
                HomeMenu.Item(1, "Gallery", R.drawable.ic_gallery) { dialog.dismiss(); onGallery() },
                HomeMenu.Item(2, "AI prompt", R.drawable.ic_sparkles) { dialog.dismiss(); onAI() }
            ))
        })
        toolbar.addView(ArtworkUi.icon(activity, R.drawable.ic_close, "Close banners") { dialog.dismiss() })
        root.addView(toolbar)
        query = EditText(activity).apply {
            hint = "Search banners"; isSingleLine = true; textSize = 16f
            setTextColor(-1); setHintTextColor(0xffaab4c5.toInt())
            background = ArtworkUi.surface(activity, 0xff0b1423.toInt())
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
            compoundDrawablePadding = dp(10); setPadding(dp(14), 0, dp(14), 0)
        }
        root.addView(query, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12); bottomMargin = dp(10) })
        val filters = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        lateinit var favourites: MaterialButton
        lateinit var all: MaterialButton
        fun updateFilters() {
            favourites.isChecked = favouritesOnly; all.isChecked = !favouritesOnly
            favourites.alpha = if (favouritesOnly) 1f else 0.55f
            all.alpha = if (favouritesOnly) 0.55f else 1f
        }
        favourites = ArtworkUi.chip(activity, "For tablets", R.drawable.ic_tv) { favouritesOnly = true; updateFilters(); filter() }.apply { isCheckable = true }
        all = ArtworkUi.chip(activity, "All", R.drawable.ic_apps) { favouritesOnly = false; updateFilters(); filter() }.apply { isCheckable = true }
        filters.addView(favourites, LinearLayout.LayoutParams(-2, dp(48)))
        filters.addView(all, LinearLayout.LayoutParams(-2, dp(48)).apply { marginStart = dp(8) })
        updateFilters(); root.addView(filters)
        status = TextView(activity).apply {
            textSize = 12f; setTextColor(0xffb9ccdc.toInt()); setPadding(dp(4), dp(8), dp(4), dp(8))
            visibility = View.GONE
            setOnClickListener {
                if (statusDetails.isNotBlank()) MaterialAlertDialogBuilder(activity).setTitle("Banner updates")
                    .setMessage(statusDetails).setPositiveButton("OK", null).show()
            }
        }
        root.addView(status)
        adapter = Banners()
        val grid = RecyclerView(activity).apply {
            val widthDp = activity.resources.displayMetrics.widthPixels / activity.resources.displayMetrics.density
            layoutManager = GridLayoutManager(activity, if (widthDp >= 840) 4 else if (widthDp >= 600) 3 else 2)
            this.adapter = this@BannerStoreDialog.adapter
            clipToPadding = false; setPadding(0, dp(10), 0, dp(4))
        }
        // The list comes from the online store; a spinner shows until it arrives.
        loading = android.widget.ProgressBar(activity).apply {
            isIndeterminate = true; indeterminateTintList = android.content.res.ColorStateList.valueOf(ControlCenter.ACCENT)
            contentDescription = "Loading banners"; visibility = View.GONE
        }
        val listArea = android.widget.FrameLayout(activity).apply {
            addView(grid, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(loading, android.widget.FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        }
        root.addView(listArea, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog = androidx.appcompat.app.AppCompatDialog(activity).apply { setContentView(root) }
        dialog.setOnDismissListener { scope.cancel() }
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { filter() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val metrics = activity.resources.displayMetrics
            setLayout(minOf(metrics.widthPixels - dp(32), dp(1100)), (metrics.heightPixels * 0.9f).toInt())
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        // Opening the store is a request to browse it: fetch the current list and let previews
        // download. Offline (or paused), it falls back to the saved list and cached banners.
        // Previously it opened offline-only, so any banner not already on the device stayed blank.
        reload(true)
    }
    private fun setStatus(message: String?, details: String = "") {
        status.text = message; statusDetails = details
        status.visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE
        status.contentDescription = if (details.isBlank()) message else "$message. Tap for details."
    }
    private fun refreshLabel(retry: Boolean) {
        refreshButton.contentDescription = if (retry) "Retry banner updates" else "Refresh banners"
        refreshButton.tooltipText = refreshButton.contentDescription
    }
    /** The banner the matching rules pick for this tile (same rules as automatic banners). */
    private fun suggested(): BannerEntry? = if (tile.isApp) null else SiteMatcher.match(entries, tile.url, tile.label)
    private fun isMatch(entry: BannerEntry, suggestion: BannerEntry?) =
        entry == suggestion || BannerRules.matches(entry, tile.packageName, tile.label, tile.url)
    private fun filter() {
        val suggestion = suggested(); suggestedEntry = suggestion
        val text = BannerRules.normalizedName(query.text.toString())
        val visible = entries.filter { entry ->
            if (text.isNotEmpty()) BannerRules.normalizedName(entry.name).contains(text) || entry.aliases.any { name -> BannerRules.normalizedName(name).contains(text) }
            else !favouritesOnly || entry.tabletPriority >= 60 || isMatch(entry, suggestion)
        }
        adapter.items = BannerRules.ordered(visible, tile.packageName, tile.label, tile.url).sortedByDescending { it == suggestion }
        adapter.notifyDataSetChanged()
        if (entries.isNotEmpty() && visible.isEmpty()) setStatus("No banners found")
        else if (status.text == "No banners found") setStatus(null)
    }
    private fun showFailure(error: Throwable) {
        val api = error as? BannerApiException
        allowNetwork = false
        retryOffered = true; refreshLabel(true)
        val reason = api?.friendlyReason ?: "The online banner source is unavailable."
        setStatus("Offline banners · Tap for details", "$reason Banners you already downloaded still work. Online checks are paused; use the refresh icon to retry. No account or access token is needed.")
    }
    private fun reload(refresh: Boolean, retry: Boolean = false) {
        if (refresh) { allowNetwork = false; networkGeneration++ }
        val generation = networkGeneration
        setStatus(null)
        loading.visibility = View.VISIBLE
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                if (retry) repository.clearCooldown()
                runCatching { repository.catalog(refresh) }
            }
            if (generation != networkGeneration) return@launch
            loading.visibility = View.GONE
            entries = result.getOrNull() ?: withContext(Dispatchers.IO) { runCatching { repository.catalog() }.getOrDefault(emptyList()) }
            if (refresh && result.isSuccess) { allowNetwork = true; retryOffered = false; refreshLabel(false) }
            filter()
            setStatus(if (entries.isEmpty()) "No banners available · Try Gallery in More" else null)
            val pausedReason = withContext(Dispatchers.IO) { repository.cooldownReason() }
            if (result.isFailure && refresh) showFailure(result.exceptionOrNull()!!)
            else if (pausedReason != null) {
                retryOffered = true; refreshLabel(true)
                setStatus("Offline banners · Tap for details", "$pausedReason Use the refresh icon to retry. Banners you already downloaded still work.")
            }
        }
    }
    private inner class Banners : RecyclerView.Adapter<Holder>() {
        var items = emptyList<BannerEntry>()
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(6)); isFocusable = true
                background = activity.getDrawable(R.drawable.bg_home_menu_row)
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(dp(2), dp(2), dp(2), dp(6)) }
            }
            val image = object : androidx.appcompat.widget.AppCompatImageView(activity) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val width = View.MeasureSpec.getSize(widthMeasureSpec)
                    super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec((width / 1.62f).toInt(), View.MeasureSpec.EXACTLY))
                }
            }.apply { scaleType = ImageView.ScaleType.FIT_CENTER; background = ArtworkUi.surface(activity, 0xff0e1628.toInt()); clipToOutline = true }
            val label = TextView(activity).apply { setTextColor(-1); textSize = 12f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(4), dp(8), dp(4), dp(6)); compoundDrawablePadding = dp(4) }
            val spinner = android.widget.ProgressBar(activity).apply {
                isIndeterminate = true; indeterminateTintList = android.content.res.ColorStateList.valueOf(0x99FFFFFF.toInt())
            }
            val missing = TextView(activity).apply { text = "Preview unavailable"; textSize = 11f; setTextColor(0x99FFFFFF.toInt()); gravity = Gravity.CENTER }
            val frame = android.widget.FrameLayout(activity).apply {
                addView(image, android.widget.FrameLayout.LayoutParams(-1, -2))
                addView(spinner, android.widget.FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
                addView(missing, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            }
            card.addView(frame, LinearLayout.LayoutParams(-1, -2)); card.addView(label)
            return Holder(card, image, label, spinner, missing)
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = items[position]
            val generation = networkGeneration
            holder.job?.cancel(); holder.id = entry.id; holder.image.setImageDrawable(null)
            holder.spinner.visibility = View.VISIBLE; holder.missing.visibility = View.GONE
            val matched = isMatch(entry, suggestedEntry)
            holder.label.text = entry.name
            holder.label.setCompoundDrawablesWithIntrinsicBounds(0, 0, if (matched) R.drawable.ic_check else 0, 0)
            holder.card.contentDescription = "${entry.name}, preview banner"
            holder.card.setOnClickListener {
                scope.launch {
                    setStatus("Opening…")
                    val result = withContext(Dispatchers.IO) { runCatching { repository.image(entry, allowNetwork) } }
                    val file = result.getOrNull()
                    val error = result.exceptionOrNull()
                    if (file != null) { dialog.dismiss(); onSelect(file) }
                    else if ((error as? BannerApiException)?.paused == true) showFailure(error)
                    else if (allowNetwork) setStatus("Couldn't load ${entry.name}", (error as? BannerApiException)?.friendlyReason ?: "Check your connection, then tap the banner again.")
                    else setStatus("Banner unavailable offline", "Refresh to update, or use Gallery / AI prompt in More.")
                }
            }
            holder.job = scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val file = repository.image(entry, allowNetwork)
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.absolutePath, bounds)
                        require(bounds.outWidth > 0 && bounds.outHeight > 0)
                        var sample = 1
                        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
                        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                    }
                }
                val bitmap = result.getOrNull()
                // Only a paused/limited source takes the store offline; one missing banner just stays blank.
                val error = result.exceptionOrNull()
                if (generation == networkGeneration && (error as? BannerApiException)?.paused == true) showFailure(error)
                if (holder.id == entry.id) {
                    holder.image.setImageBitmap(bitmap)
                    holder.spinner.visibility = View.GONE
                    holder.missing.visibility = if (bitmap == null) View.VISIBLE else View.GONE
                } else bitmap?.recycle()
            }
        }
        override fun onViewRecycled(holder: Holder) { holder.job?.cancel(); holder.id = null; holder.image.setImageDrawable(null) }
    }
    private class Holder(val card: LinearLayout, val image: ImageView, val label: TextView, val spinner: View, val missing: View) : RecyclerView.ViewHolder(card) {
        var id: String? = null; var job: Job? = null
    }
}
