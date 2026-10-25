package app.anihome.launcher.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.anihome.launcher.R
import app.anihome.launcher.data.*
import app.anihome.launcher.databinding.ActivityAppDrawerBinding
import app.anihome.launcher.databinding.ItemAppBinding
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Locale

/** Opening apps and selecting apps are separate modes. Selections survive search and rotation. */
class AppDrawerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAppDrawerBinding
    private lateinit var store: TileStore
    private lateinit var preferences: DrawerPreferences
    private var all: List<InstalledApp> = emptyList()
    private var onHome: Set<String> = emptySet()
    private var homeWebsites: List<HomeTile> = emptyList()
    private var favoritesSection: View? = null
    private var searchResults: android.widget.LinearLayout? = null
    private var webResults: android.widget.LinearLayout? = null
    private val selected = linkedSetOf<String>()
    private var showHidden = false
    private var adding = false
    private val adapter = Adapter()
    private val iconWorkers = Semaphore(4)
    private val dockPickMode by lazy { intent.getBooleanExtra(EXTRA_DOCK_PICK, false) }
    private val pickMode by lazy { dockPickMode || intent.getBooleanExtra(EXTRA_PICK, false) }
    private fun InstalledApp.key() = "$packageName/$activityName"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DrawerItemWarmup.pause()
        InstalledApps.watch(applicationContext)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        RotationControl.applyHome(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityAppDrawerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        entryPending = savedInstanceState == null
        if (!entryPending) entryFinished.complete(Unit)
        if (entryPending) binding.root.alpha = 0f
        store = TileStore(this); preferences = DrawerPreferences(this)
        selected.addAll(savedInstanceState?.getStringArrayList("selected").orEmpty())
        if (savedInstanceState == null && dockPickMode) selected.addAll(DockApps.pins(this).map { "${it.packageName}/${it.className}" })
        showHidden = savedInstanceState?.getBoolean("show_hidden") ?: false
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.displayCutout())
            val page = resources.getDimensionPixelSize(R.dimen.page_padding)
            binding.root.updatePadding(left = bars.left + page, right = bars.right + page, top = bars.top, bottom = bars.bottom)
            insets
        }
        val availableDp = resources.configuration.screenWidthDp - 2 * resources.getDimension(R.dimen.page_padding) / resources.displayMetrics.density
        binding.list.layoutManager = GridLayoutManager(this, if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) 4 else (availableDp / 96f).toInt().coerceIn(3, 8))
        binding.list.adapter = adapter
        binding.list.itemAnimator = null
        if (!(AdaptiveLayout.tablet(this) && resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT)) {
            fitColumns(binding.list, 8)
        }
        buildLibrary()
        binding.btnBack.setOnClickListener { finish() }
        if (!pickMode) {
            searchResults = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
            binding.root.addView(searchResults, 1)
            webResults = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
            binding.root.addView(webResults, 1)
        }
        binding.search.doAfterTextChanged { filter(); renderFiles() }
        binding.search.setOnEditorActionListener { _, action, event ->
            if (!pickMode && (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH || event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_UP)) {
                val query = binding.search.text.toString().trim()
                if (query.isEmpty()) return@setOnEditorActionListener false
                val website = homeWebsites.firstOrNull { it.label.contains(query, true) }
                when {
                    looksLikeAddress(query) -> searchWeb(query)
                    adapter.items.isNotEmpty() -> launch(adapter.items.first())
                    query.isNotEmpty() && website != null -> openWebsite(website)
                    query.isNotEmpty() -> searchWeb(query)
                }
                true
            } else false
        }
        if (intent.getBooleanExtra(EXTRA_SEARCH, false) && savedInstanceState == null) {
            binding.search.requestFocus()
            binding.search.postDelayed({
                if (!isFinishing) getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(binding.search, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }, 300)
        }
        binding.btnMore.setOnClickListener { anchor ->
            HomeMenu.show(anchor, "Apps", listOf(
                HomeMenu.Item(1, if (showHidden) "Show visible apps" else "Hidden apps (${preferences.hidden().size})", R.drawable.ic_visibility, checked = showHidden) {
                    showHidden = !showHidden; filter()
                }
            ))
        }
        binding.btnAddSelected.visibility = if (pickMode) View.VISIBLE else View.GONE
        binding.btnAddSelected.setOnClickListener {
            if (dockPickMode) { DockApps.set(this, selected.mapNotNull { key -> all.firstOrNull { it.key() == key }?.component }); finish() }
            else addApps(all.filter { it.key() in selected }, true)
        }
        val warm = DrawerWarmup.current()
        val cached = warm?.apps ?: InstalledApps.cached()
        if (cached != null) {
            all = cached
            val tiles = warm?.tiles.orEmpty()
            onHome = if (dockPickMode) emptySet() else tiles.filter { it.isApp }.map { "${it.packageName}/${it.activityName}" }.toSet()
            homeWebsites = tiles.filter { it.isWeb }
            cachedFiles = warm?.files.orEmpty()
            filter(); renderFiles(); binding.loading.visibility = View.GONE
            revealDrawer()
        }

    }
    override fun onResume() { super.onResume(); RotationControl.applyHome(this); load(); refreshFiles() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("selected", ArrayList(selected)); outState.putBoolean("show_hidden", showHidden)
        super.onSaveInstanceState(outState)
    }
    private var entryPending = false
    private val entryFinished = CompletableDeferred<Unit>()
    private val webBrowserLabel by lazy { DockBrowser.choices(this).firstOrNull { it.packageName == DockBrowser.selected(this) }?.label ?: "browser" }
    private suspend fun waitForEntry() {
        if (!entryPending) entryFinished.await()
    }

    private fun revealDrawer() {
        libraryContent?.visibility = View.VISIBLE
        if (!entryPending) { entryFinished.complete(Unit); return }
        entryPending = false
        MotionUi.enter(binding.root, intent.getStringExtra("motion_direction") ?: "LEFT") { entryFinished.complete(Unit) }
    }
    private var loadJob: Job? = null
    private fun load() {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val apps = InstalledApps.list(this@AppDrawerActivity, refresh = true)
                    val tiles = store.all()
                    val home = tiles.filter { it.isApp }.map { "${it.packageName}/${it.activityName}" }.toSet()
                    Triple(apps, home, tiles.filter { it.isWeb })
                }
            }
            waitForEntry()
            binding.loading.visibility = View.GONE
            result.onSuccess { (apps, home, websites) ->
                if (apps == all && home == onHome && websites == homeWebsites && !entryPending) return@onSuccess
                homeWebsites = websites
                all = apps; onHome = if (dockPickMode) emptySet() else home; if (!dockPickMode) selected.removeAll(home); filter()
                revealDrawer()
            }
                .onFailure { revealDrawer(); binding.hint.text = "Couldn’t load apps. Reopen Apps to retry."; binding.hint.visibility = View.VISIBLE }
        }
    }
    private fun filter() {
        val q = binding.search.text?.toString().orEmpty().trim().lowercase(Locale.ROOT)
        val hidden = preferences.hidden()
        adapter.items = all.filter { (it.key() in hidden) == showHidden && (q.isEmpty() || it.label.lowercase(Locale.ROOT).contains(q) || it.packageName.lowercase(Locale.ROOT).contains(q)) }
        adapter.notifyDataSetChanged()
        favorites.items = all.filter { it.key() in onHome && it.key() !in hidden && (q.isEmpty() || it.label.lowercase(Locale.ROOT).contains(q)) }
        favorites.notifyDataSetChanged()
        favoritesSection?.visibility = if (favorites.items.isEmpty()) View.GONE else View.VISIBLE
        renderSearchResults()
        frequent.items = AppFrequency.rank(this, all.filter { it.key() !in hidden }, onHome).take(if (AdaptiveLayout.tablet(this)) 8 else AdaptiveLayout.columns(resources.configuration.screenWidthDp - 28f, resources.configuration.fontScale, 5)).filter {
            q.isEmpty() || it.label.lowercase(Locale.ROOT).contains(q) || it.packageName.lowercase(Locale.ROOT).contains(q)
        }
        frequent.notifyDataSetChanged()
        binding.hint.text = when {
            showHidden -> if (adapter.items.isEmpty()) "No hidden apps" else ""
            binding.loading.visibility == View.VISIBLE -> ""
            all.isEmpty() -> "No apps available"
            adapter.items.isEmpty() -> "No matching apps"
            dockPickMode -> "Choose up to five apps; the dock shows as many as fit."
            pickMode -> "Choose apps, then add them together"
            else -> ""
        }
        binding.hint.visibility = if (binding.hint.text.isEmpty()) View.GONE else View.VISIBLE
        binding.btnAddSelected.text = if (dockPickMode) "Save dock (${selected.size}/5)" else "Add selected (${selected.size})"
        binding.btnAddSelected.isEnabled = (dockPickMode || selected.isNotEmpty()) && !adding
    }
    private val favorites = Adapter()
    private val frequent = Adapter()
    private var wideLibrary = false
    private var libraryContent: View? = null
    private var cachedFiles: List<RecentFiles.File> = emptyList()
    private lateinit var recentBody: RecyclerView
    private var filesJob: Job? = null
    private var filesRefresh: Job? = null
    private val fileObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            filesRefresh?.cancel()
            filesRefresh = lifecycleScope.launch { delay(300); refreshFiles() }
        }
    }
    override fun onStart() {
        super.onStart()
        androidx.core.content.ContextCompat.registerReceiver(this, packagesChanged, android.content.IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED); addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }, androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        if (!pickMode) runCatching { contentResolver.registerContentObserver(Uri.parse("content://media"), true, fileObserver) }
    }
    private val packagesChanged = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            InstalledApps.invalidate(); load()
        }
    }
    override fun onStop() {
        unregisterReceiver(packagesChanged)
        loadJob?.cancel()
        contentResolver.unregisterContentObserver(fileObserver)
        filesRefresh?.cancel(); filesJob?.cancel()
        super.onStop()
    }
    private val mediaPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { refreshFiles() }
    private fun fitColumns(grid: RecyclerView, maximum: Int) {
        grid.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left > 0 && (right - left != oldRight - oldLeft || grid.tag != resources.configuration.fontScale)) {
                val width = (right - left - grid.paddingLeft - grid.paddingRight) / resources.displayMetrics.density
                val columns = AdaptiveLayout.columns(width, resources.configuration.fontScale, maximum, if (AdaptiveLayout.tablet(this)) 96f else 80f)
                (grid.layoutManager as GridLayoutManager).spanCount = columns
                grid.tag = resources.configuration.fontScale
            }
        }
    }
    private fun buildPhoneLibrary() {
        val parent = binding.list.parent as android.widget.LinearLayout
        val index = parent.indexOfChild(binding.list)
        if (resources.configuration.screenHeightDp >= 560) {
            val grid = RecyclerView(this).apply {
                adapter = frequent; layoutManager = GridLayoutManager(this@AppDrawerActivity, 4); itemAnimator = null
            }
            fitColumns(grid, 5)
            parent.addView(grid, index, android.widget.LinearLayout.LayoutParams(-1, -2))
            parent.addView(View(this).apply { setBackgroundColor(0xff484848.toInt()) }, index + 1,
                android.widget.LinearLayout.LayoutParams(-1, ArtworkUi.dp(this, 1)).apply { topMargin = ArtworkUi.dp(this@AppDrawerActivity, 6); bottomMargin = ArtworkUi.dp(this@AppDrawerActivity, 8) })
        }
        recentBody = RecyclerView(this).apply { layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@AppDrawerActivity); itemAnimator = null }
        val recent = recentBody.apply { visibility = View.GONE }
        val sheet = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xff1c1c1c.toInt()); cornerRadius = ArtworkUi.dp(context, 16).toFloat()
            }
            clipToOutline = true
        }
        val header = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            isClickable = true; isFocusable = true
            contentDescription = "Recent files"
            val value = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
            foreground = androidx.core.content.ContextCompat.getDrawable(context, value.resourceId)
        }
        header.addView(View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xff8a8a8a.toInt()); cornerRadius = ArtworkUi.dp(this@AppDrawerActivity, 2).toFloat()
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, android.widget.LinearLayout.LayoutParams(ArtworkUi.dp(this, 28), ArtworkUi.dp(this, 3)).apply { topMargin = ArtworkUi.dp(this@AppDrawerActivity, 8) })
        val label = android.widget.LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(ArtworkUi.dp(context, 12), 0, ArtworkUi.dp(context, 12), 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        label.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_folder); setColorFilter(0xffcccccc.toInt())
        }, android.widget.LinearLayout.LayoutParams(ArtworkUi.dp(this, 20), ArtworkUi.dp(this, 20)))
        label.addView(android.widget.TextView(this).apply {
            text = "Recent files"; textSize = 14f; setTextColor(-1)
            setPadding(ArtworkUi.dp(context, 10), 0, 0, 0)
        })
        header.addView(label, android.widget.LinearLayout.LayoutParams(-1, ArtworkUi.dp(this, 44)))
        sheet.addView(header)
        sheet.addView(recent, android.widget.LinearLayout.LayoutParams(-1, 0))
        parent.addView(sheet, android.widget.LinearLayout.LayoutParams(-1, -2))
        var expanded = false
        var paneHeight = ArtworkUi.dp(this, resources.configuration.screenHeightDp / 2)
        var expansion: android.animation.ValueAnimator? = null
        fun describeState() {
            ViewCompat.setStateDescription(header, if (expanded) "Expanded" else "Collapsed")
            ViewCompat.replaceAccessibilityAction(header, androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                if (expanded) "Collapse recent files" else "Expand recent files") { view, _ -> view.performClick() }
        }
        header.setOnClickListener {
            expansion?.cancel()
            expanded = !expanded
            describeState()
            recent.visibility = View.VISIBLE
            expansion = android.animation.ValueAnimator.ofInt(recent.layoutParams.height, if (expanded) paneHeight else 0).apply {
                duration = 180
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { animator -> recent.layoutParams = recent.layoutParams.apply { height = animator.animatedValue as Int } }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (!expanded) recent.visibility = View.GONE
                    }
                })
                start()
            }
        }
        describeState()
        binding.root.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            if (bottom > top) {
                paneHeight = ((bottom - top - binding.root.paddingTop - binding.root.paddingBottom) / 2)
                    .coerceAtLeast(ArtworkUi.dp(this@AppDrawerActivity, 80))
                if (expanded && expansion?.isRunning != true && recent.layoutParams.height != paneHeight) {
                    recent.layoutParams = recent.layoutParams.apply { height = paneHeight }
                }
            }
        }
        binding.search.hint = "Search apps and files"
    }
    private fun buildLibrary() {
        binding.root.setBackgroundColor(0xff141414.toInt())
        if (pickMode) return
        if (!AdaptiveLayout.tablet(this)) { buildPhoneLibrary(); return }
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        wideLibrary = landscape
        val container = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        libraryContent = container
        container.visibility = View.INVISIBLE
        val parent = binding.list.parent as android.widget.LinearLayout
        val index = parent.indexOfChild(binding.list)
        parent.removeView(binding.list)
        parent.addView(container, index, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        fun section(title: String) = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(ArtworkUi.dp(context, 8), 0, ArtworkUi.dp(context, 8), 0)
            addView(ArtworkUi.title(this@AppDrawerActivity, title).apply { textSize = 16f }, android.widget.LinearLayout.LayoutParams(-1, ArtworkUi.dp(context, 36)))
            addView(View(context).apply { setBackgroundColor(0xff444444.toInt()) }, android.widget.LinearLayout.LayoutParams(-1, ArtworkUi.dp(context, 1)))
        }
        val recent = section("Recent files")
        recentBody = RecyclerView(this).apply { layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@AppDrawerActivity); itemAnimator = null }
        recent.addView(recentBody, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        container.addView(recent, android.widget.LinearLayout.LayoutParams(0, -1, if (landscape) .25f else .30f))
        val appsColumn = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        container.addView(appsColumn, android.widget.LinearLayout.LayoutParams(0, -1, if (landscape) .75f else .70f))
        val fav = section("On Home").also { favoritesSection = it }
        fav.addView(RecyclerView(this).apply {
            adapter = favorites
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@AppDrawerActivity, RecyclerView.HORIZONTAL, false)
            itemAnimator = null
        }, android.widget.LinearLayout.LayoutParams(-1, ArtworkUi.dp(this, 112)))
        appsColumn.addView(fav, android.widget.LinearLayout.LayoutParams(-1, -2))
        val apps = section("All applications")
        apps.addView(binding.list, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        appsColumn.addView(apps, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        val pageDp = resources.getDimension(R.dimen.page_padding) / resources.displayMetrics.density
        val gridWidth = (resources.configuration.screenWidthDp - pageDp * 2) * (if (landscape) .75f else .70f) - 16
        (binding.list.layoutManager as GridLayoutManager).spanCount = if (!landscape) 4 else (gridWidth / 96).toInt().coerceIn(2, 8)
        binding.search.hint = "Search apps and files"
    }
    private fun refreshFiles() {
        if (pickMode || !::recentBody.isInitialized) return
        filesJob?.cancel()
        filesJob = lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) { RecentFiles.read(this@AppDrawerActivity) }
            waitForEntry()
            if (files != cachedFiles) { cachedFiles = files; renderFiles() }
        }
    }
    private fun renderFiles() {
        if (!::recentBody.isInitialized) return
        val q = binding.search.text?.toString().orEmpty().trim()
        val files = cachedFiles.filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
        val permissionNeeded = !RecentFiles.hasMediaAccess(this)
        recentBody.adapter = object : RecyclerView.Adapter<RecentHolder>() {
            override fun getItemCount() = files.size + (if (permissionNeeded) 1 else 0) + (if (files.isEmpty()) 1 else 0)
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = RecentHolder(android.widget.FrameLayout(this@AppDrawerActivity).apply {
                layoutParams = RecyclerView.LayoutParams(-1, -2)
            })
            override fun onBindViewHolder(holder: RecentHolder, position: Int) {
                holder.frame.removeAllViews()
                val index = position - if (permissionNeeded) 1 else 0
                val child = if (index < 0) ArtworkUi.textChip(this@AppDrawerActivity, "Allow media") {
                    mediaPermission.launch(if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO, android.Manifest.permission.READ_MEDIA_AUDIO) else arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE))
                } else if (files.isEmpty()) android.widget.TextView(this@AppDrawerActivity).apply {
                    text = if (q.isNotEmpty()) "No matching files" else if (!permissionNeeded) "No recent files" else "Allow access to see recent screenshots, downloads and shared media."
                    setTextColor(0xffbbbbbb.toInt()); textSize = 13f; setPadding(0, ArtworkUi.dp(context, 12), 0, 0)
                } else recentRow(files[index])
                holder.frame.addView(child)
            }
        }
    }
    private class RecentHolder(val frame: android.widget.FrameLayout) : RecyclerView.ViewHolder(frame)
    private fun recentRow(file: RecentFiles.File): View {
        val row = android.widget.LinearLayout(this@AppDrawerActivity).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL; minimumHeight = ArtworkUi.dp(context, 56)
                background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_drawer_item)
                setPadding(0, ArtworkUi.dp(context, 6), 0, ArtworkUi.dp(context, 6))
                isFocusable = true
            }
            row.addView(android.widget.ImageView(this@AppDrawerActivity).apply { setImageResource(R.drawable.ic_file); setColorFilter(-1) }, android.widget.LinearLayout.LayoutParams(ArtworkUi.dp(this@AppDrawerActivity, 24), ArtworkUi.dp(this@AppDrawerActivity, 24)))
            row.addView(android.widget.TextView(this@AppDrawerActivity).apply {
                text = file.name + "\n" + android.text.format.DateUtils.getRelativeTimeSpanString(file.modified, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS)
                setTextColor(-1); textSize = 12f; maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(ArtworkUi.dp(context, 8), 0, 0, 0)
            }, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
            row.setOnClickListener { openRecentFile(file) }
            row.addView(android.widget.ImageButton(this).apply {
                setImageResource(R.drawable.ic_more_vertical); setColorFilter(-1)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                contentDescription = "More options for ${file.name}"
                setPadding(ArtworkUi.dp(context, 12), ArtworkUi.dp(context, 12), ArtworkUi.dp(context, 12), ArtworkUi.dp(context, 12))
                setOnClickListener { anchor ->
                    HomeMenu.show(anchor, file.name, listOf(
                        HomeMenu.Item(1, "Open", R.drawable.ic_file) { openRecentFile(file) },
                        HomeMenu.Item(2, "Share", R.drawable.ic_share) { openRecentFile(file, share = true) },
                        HomeMenu.Item(3, "File info", R.drawable.ic_info) { showRecentFileInfo(file) }
                    ))
                }
            }, android.widget.LinearLayout.LayoutParams(ArtworkUi.dp(this, 48), ArtworkUi.dp(this, 48)))
        return row
    }
    private fun recentFileUnavailable() {
        Toast.makeText(this, "This file is unavailable or access has changed. Refreshing recent files.", Toast.LENGTH_LONG).show()
        refreshFiles()
    }
    private fun openRecentFile(file: RecentFiles.File, share: Boolean = false) {
        lifecycleScope.launch {
            val available = withContext(Dispatchers.IO) {
                runCatching { contentResolver.openAssetFileDescriptor(file.uri, "r")?.use { true } ?: false }.getOrDefault(false)
            }
            if (!available) { recentFileUnavailable(); return@launch }
            val intent = if (share) Intent(Intent.ACTION_SEND).apply {
                type = file.mime
                putExtra(Intent.EXTRA_STREAM, file.uri)
                clipData = android.content.ClipData.newUri(contentResolver, file.name, file.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else Intent(Intent.ACTION_VIEW).setDataAndType(file.uri, file.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { startActivity(if (share) Intent.createChooser(intent, "Share file") else intent) }
                .onFailure { Toast.makeText(this@AppDrawerActivity, "No app can handle this file, or access has changed.", Toast.LENGTH_LONG).show() }
        }
    }
    private fun showRecentFileInfo(file: RecentFiles.File) {
        lifecycleScope.launch {
            val metadata = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.query(file.uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        check(cursor.moveToFirst())
                        (cursor.getString(0) ?: file.name) to (if (cursor.isNull(1)) null else cursor.getLong(1))
                    }
                }.getOrNull()
            }
            if (metadata == null) { recentFileUnavailable(); return@launch }
            val size = metadata.second?.takeIf { it >= 0 }?.let { android.text.format.Formatter.formatFileSize(this@AppDrawerActivity, it) } ?: "Unknown"
            val modified = if (file.modified > 0) java.text.DateFormat.getDateTimeInstance().format(java.util.Date(file.modified)) else "Unknown"
            androidx.appcompat.app.AlertDialog.Builder(this@AppDrawerActivity)
                .setTitle("File info")
                .setMessage("Name: ${metadata.first}\n\nType: ${file.mime}\n\nSize: $size\n\nModified: $modified")
                .setPositiveButton("Done", null).show()
        }
    }
    private fun launch(app: InstalledApp) {
        runCatching {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            AppFrequency.record(this, app.packageName, app.activityName)
            finish()
        }.onFailure { Toast.makeText(this, R.string.app_missing, Toast.LENGTH_SHORT).show() }
    }
    private fun options(app: InstalledApp, anchor: View) {
        val hidden = app.key() in preferences.hidden()
        val actions = mutableListOf<HomeMenu.Item>()
        if (app.key() !in onHome) actions += HomeMenu.Item(1, "Add to Home", R.drawable.ic_add) { addApps(listOf(app), true) }
        val docked = app.component in DockApps.pins(this)
        actions += HomeMenu.Item(5, if (docked) "Unpin from dock" else "Pin to dock", R.drawable.ic_apps) {
            if (!docked && DockApps.pins(this).size >= 5) Toast.makeText(this, "Dock stores up to five apps. Unpin one first.", Toast.LENGTH_SHORT).show()
            else { DockApps.toggle(this, app.component); Toast.makeText(this, if (docked) "Removed from dock" else "Pinned to dock", Toast.LENGTH_SHORT).show() }
        }
        actions += HomeMenu.Item(2, if (hidden) "Show again" else "Hide from Apps", R.drawable.ic_visibility) {
            preferences.hide(app.key(), !hidden); filter()
        }
        actions += HomeMenu.Item(3, "Uninstall", R.drawable.ic_delete) {
            runCatching { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}"))) }
                .onFailure { appInfo(app) }
        }
        actions += HomeMenu.Item(4, "App info", R.drawable.ic_settings) { appInfo(app) }
        HomeMenu.show(anchor, app.label, actions)
    }
    private fun renderSearchResults() {
        val container = searchResults ?: return
        container.removeAllViews()
        webResults?.removeAllViews()
        val query = binding.search.text?.toString().orEmpty().trim()
        container.visibility = if (query.isEmpty() || pickMode || showHidden) View.GONE else View.VISIBLE
        webResults?.visibility = container.visibility
        if (container.visibility != View.VISIBLE) return
        val matches = homeWebsites.filter { it.label.contains(query, true) || it.url.orEmpty().contains(query, true) }.take(3)
        matches.forEach { tile -> container.addView(ArtworkUi.chip(this, tile.label, R.drawable.ic_home) { openWebsite(tile) }) }
        val browser = webBrowserLabel
        webResults?.addView(ArtworkUi.chip(this, if (looksLikeAddress(query)) "Open address in $browser" else if (DockBrowser.selected(this) == null) "Search Google on the web" else "Search web in $browser", R.drawable.ic_search) { searchWeb(query) }.apply {
            contentDescription = if (looksLikeAddress(query)) "Open $query in $browser" else "Search the web for $query in $browser"
        }, android.widget.LinearLayout.LayoutParams(-1, ArtworkUi.dp(this, 44)).apply { topMargin = ArtworkUi.dp(this@AppDrawerActivity, 6) })
    }
    private fun looksLikeAddress(query: String) = query.contains('.') && query.none { it.isWhitespace() }
    private fun openWebsite(tile: HomeTile) {
        tile.url?.let { startActivity(app.anihome.launcher.web.WebActivity.intent(this, it)); finish() }
    }
    private fun searchWeb(query: String) {
        val url = if (looksLikeAddress(query)) SiteIcons.normalizeUrl(query) ?: return else "https://www.google.com/search?q=" + Uri.encode(query)
        val target = DockBrowser.intent(this, query, url, looksLikeAddress(query)) ?: app.anihome.launcher.web.WebActivity.intent(this, url)
        runCatching { startActivity(target); finish() }.onFailure { Toast.makeText(this, "Browser unavailable. Choose another in Dock settings.", Toast.LENGTH_LONG).show() }
    }
    private fun appInfo(app: InstalledApp) {
        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))) }
            .onFailure { Toast.makeText(this, "App settings unavailable", Toast.LENGTH_SHORT).show() }
    }
    private fun addApps(apps: List<InstalledApp>, finishAfter: Boolean) {
        if (adding || apps.isEmpty()) return
        adding = true; filter()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { store.addApps(apps) } }
            adding = false
            result.onSuccess { count ->
                onHome = onHome + apps.map { it.key() }; selected.removeAll(onHome)
                TileEvents.notifyChanged(); filter()
                Toast.makeText(this@AppDrawerActivity, "$count added to Home", Toast.LENGTH_SHORT).show()
                if (finishAfter) finish()
            }.onFailure { filter(); Toast.makeText(this@AppDrawerActivity, "Couldn’t add apps. Try again.", Toast.LENGTH_LONG).show() }
        }
    }
    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        var items = emptyList<InstalledApp>()
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val item = DrawerItemWarmup.take(parent)
            if (this == favorites && AdaptiveLayout.tablet(this@AppDrawerActivity)) item.root.layoutParams = RecyclerView.LayoutParams(ArtworkUi.dp(this@AppDrawerActivity, 96), ViewGroup.LayoutParams.MATCH_PARENT)
            return Holder(item)
        }
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])
        override fun onViewRecycled(holder: Holder) { holder.job?.cancel() }
    }
    private inner class Holder(private val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root) {
        var job: Job? = null
        private var key: String? = null
        fun bind(app: InstalledApp) {
            job?.cancel(); key = app.key()
            binding.label.text = app.label
            val cached = IconCache.cachedAppIcon(app.packageName, app.activityName)
            if (cached != null) binding.icon.setImageBitmap(cached) else binding.icon.setImageResource(R.drawable.ic_apps)
            // No PackageManager or bitmap conversion on the UI thread.
            job = if (cached != null) null else lifecycleScope.launch {
                val icon = withContext(Dispatchers.IO) { iconWorkers.withPermit { IconCache.appIcon(applicationContext, app.packageName, app.activityName) } }
                if (key == app.key()) binding.icon.setImageBitmap(icon)
            }
            val checked = app.key() in onHome || app.key() in selected
            binding.onHome.visibility = if (pickMode && checked) View.VISIBLE else View.GONE
            binding.root.alpha = if (pickMode && app.key() in onHome) 0.55f else 1f
            binding.root.contentDescription = app.label + if (pickMode && checked) ", selected or already on Home" else ""
            binding.root.setOnClickListener {
                if (!pickMode) launch(app)
                else if (app.key() !in onHome && !adding) {
                    if (app.key() in selected) selected.remove(app.key())
                    else if (!dockPickMode || selected.size < 5) selected.add(app.key())
                    else Toast.makeText(this@AppDrawerActivity, "Choose up to five dock apps", Toast.LENGTH_SHORT).show()
                    filter()
                }
            }
            binding.root.setOnLongClickListener { options(app, it); true }
        }
    }
    companion object { const val EXTRA_SEARCH = "search_mode"; const val EXTRA_PICK = "pick"; const val EXTRA_DOCK_PICK = "dock_pick" }
}
