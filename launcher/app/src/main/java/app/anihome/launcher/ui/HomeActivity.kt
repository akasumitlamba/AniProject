package app.anihome.launcher.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Gravity
import android.graphics.Rect
import android.content.res.Configuration
import android.os.Build
import android.app.role.RoleManager
import android.widget.LinearLayout
import androidx.core.widget.NestedScrollView
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.view.updateLayoutParams
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import app.anihome.launcher.R
import app.anihome.launcher.data.HomeTile
import app.anihome.launcher.data.IconCache
import app.anihome.launcher.data.InstalledApps
import app.anihome.launcher.data.SiteIcons
import app.anihome.launcher.data.TileColors
import app.anihome.launcher.data.TileEvents
import app.anihome.launcher.data.TileStore
import app.anihome.launcher.databinding.ActivityHomeBinding
import app.anihome.launcher.data.HistoryMigration
import androidx.activity.result.contract.ActivityResultContracts
import android.accessibilityservice.AccessibilityService
import app.anihome.launcher.controls.HomeControlsService
import android.app.Activity
import app.anihome.launcher.web.WebActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The home screen. Registered for the HOME intent so it replaces the stock launcher; the grid
 * mixes native apps and website tiles and treats them identically.
 */
class HomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHomeBinding
    private lateinit var store: TileStore
    private lateinit var tiles: TileAdapter
    private lateinit var touchHelper: ItemTouchHelper
    private lateinit var widgets: WidgetController
    private var legacyHistoryAvailable = false
    private var restoreNotificationPanel = false
    private lateinit var notificationPanel: NotificationPanel
    private val historyTransfer = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data?.getBooleanExtra("imported", false) == true) {
            homePreferences.edit().putBoolean("history_imported", true).apply()
            Toast.makeText(this, "Watch history copied to AniBrave. Original history is preserved.", Toast.LENGTH_LONG).show()
        } else if (result.resultCode != Activity.RESULT_CANCELED) Toast.makeText(this, "History was not transferred. Original history is preserved.", Toast.LENGTH_LONG).show()
        HistoryMigration.removeTemporaryCopy(this)
    }
    private val homeRoleRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        binding.defaultBanner.visibility = if (isDefaultHome()) View.GONE else View.VISIBLE
    }
    private val homePreferences by lazy { getSharedPreferences("anihome", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RotationControl.applyHome(this)
        notificationPanel = NotificationPanel(this, ::populateHomeSettings)
        restoreNotificationPanel = savedInstanceState?.getBoolean("notification_panel_open") == true
        WallpaperSupport.apply(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        hideStatusBar()
        store = TileStore(this)
        widgets = WidgetController(this, binding.widgetGrid, ::setDragScrolling) {
            binding.empty.visibility = if ((!::tiles.isInitialized || tiles.itemCount == 0) && binding.widgetGrid.visibility != View.VISIBLE) View.VISIBLE else View.GONE
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val margin = resources.getDimensionPixelSize(R.dimen.page_padding)
            val vertical = resources.getBoolean(R.bool.vertical_controls)
            binding.content.updatePadding(
                left = bars.left + resources.getDimensionPixelSize(R.dimen.page_padding),
                top = bars.top,
                right = bars.right + if (vertical) dp(64) + margin else margin,
                bottom = bars.bottom + if (vertical) 0 else dp(64) + margin
            )
            binding.controlRail.updateLayoutParams<FrameLayout.LayoutParams> {
                topMargin = if (resources.getBoolean(R.bool.vertical_controls)) bars.top + margin else 0
                bottomMargin = bars.bottom + margin
                marginStart = bars.left + margin
                marginEnd = bars.right + margin
            }
            insets
        }

        tiles = TileAdapter(onOpen = ::open, onOptions = ::showOptions, onRemove = ::confirmRemove, onStartDrag = { touchHelper.startDrag(it) })
        tiles.showLabels = homePreferences.getBoolean("show_tile_labels", false)
        binding.grid.layoutManager = GridLayoutManager(this, HomeLayout.columns(this))
        binding.grid.adapter = tiles
        binding.grid.itemAnimator?.changeDuration = 0
        touchHelper = ItemTouchHelper(DragCallback())
        touchHelper.attachToRecyclerView(binding.grid)

        binding.btnEdit.setImageResource(R.drawable.ic_check)
        binding.btnEdit.contentDescription = "Finish editing Home"
        binding.btnEdit.setOnClickListener { setEditMode(false) }
        binding.btnNotifications.setOnClickListener { notificationPanel.show() }
        binding.btnMore.setOnClickListener { showMoreMenu(it) }
        binding.btnSearch.imageTintList = null
        binding.btnSearch.setOnClickListener { showSearch() }
        binding.btnAdd.setOnClickListener { showAddMenu(it) }
        binding.btnApps.setOnClickListener { startActivity(Intent(this, AppDrawerActivity::class.java)) }
        binding.btnSettings.setOnClickListener { showSettingsMenu(it) }
        binding.btnDefaultHome.setOnClickListener { openHomeSettings() }

        configureRail()

        // A home screen never "goes back"; Back just leaves edit mode.
        onBackPressedDispatcher.addCallback(this) { if (tiles.editMode) setEditMode(false) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.anihome.launcher.notifications.HomeNotifications.notifications.collect { active ->
                    val keys = app.anihome.launcher.notifications.NotificationPolicy.visible(active.map {
                        app.anihome.launcher.notifications.NotificationPolicy.Entry(it.key, it.groupKey, it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0, it.isClearable)
                    })
                    val visible = active.filter { it.key in keys }
                    tiles.notificationCounts = visible.groupingBy { it.packageName }.eachCount()
                    BadgeUi.bind(notificationBadge, visible.size)
                    binding.btnNotifications.contentDescription = "Notifications, ${visible.size} active"
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { TileEvents.flow.collect { reload() } }
        }
    }

    private fun dp(value: Int) = ArtworkUi.dp(this, value)

    /** Reparent existing controls instead of recreating the activity or registering widget hosts again. */
    private val clock by lazy { android.widget.TextClock(this).apply {
        setTextColor(android.graphics.Color.WHITE); textSize = 13f; gravity = Gravity.CENTER
        format24Hour = "HH:mm\ndd MMM"; format12Hour = "h:mm\ndd MMM"; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    } }
    private val recents by lazy { ArtworkUi.plainIcon(this, R.drawable.ic_recents, "Recent apps") { systemAction(AccessibilityService.GLOBAL_ACTION_RECENTS) } }
    private val notificationBadge by lazy { BadgeUi.create(this) }
    private val dockStatus by lazy { DockStatus(this) }
    private fun hideStatusBar() = HomeFullscreen.apply(window)
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideStatusBar() }
    private fun showDockPicker() {
        startActivity(Intent(this, AppDrawerActivity::class.java).putExtra(AppDrawerActivity.EXTRA_DOCK_PICK, true))
    }
    private fun configureRail() {
        val vertical = resources.getBoolean(R.bool.vertical_controls)
        val controlSize = if (vertical) 52 else 44
        val showSearch = homePreferences.getBoolean("show_dock_search", true)
        val showRecents = !tiles.editMode && homePreferences.getBoolean("show_recents_button", AdaptiveLayout.tablet(this@HomeActivity))
        val all = listOf(binding.railBrand, binding.btnSearch, binding.btnAdd, binding.btnNotifications,
            binding.btnSettings, binding.btnEdit, binding.btnMore, binding.btnApps, clock, recents, notificationBadge, dockStatus)
        all.forEach { (it.parent as? ViewGroup)?.removeView(it) }
        val rail = binding.controlRail
        rail.removeAllViews()
        rail.orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        rail.gravity = if (vertical) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
        rail.updateLayoutParams<FrameLayout.LayoutParams> {
            width = if (vertical) dp(56) else ViewGroup.LayoutParams.MATCH_PARENT
            height = if (vertical) ViewGroup.LayoutParams.MATCH_PARENT else dp(56)
            gravity = if (vertical) Gravity.END else Gravity.BOTTOM
        }
        rail.setPadding(dp(2), dp(4), dp(2), dp(4))
        fun add(parent: LinearLayout, view: View, size: Int = controlSize) {
            view.visibility = View.VISIBLE
            if (view is android.widget.ImageButton) view.setPadding(dp((size - 32) / 2), dp((size - 32) / 2), dp((size - 32) / 2), dp((size - 32) / 2))
            parent.addView(view, LinearLayout.LayoutParams(dp(size), dp(size)))
        }
        clock.textSize = if (vertical) 12f else 11f
        clock.setOnClickListener { notificationPanel.show() }
        clock.tooltipText = "Notifications"
        val clockBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(clock, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(dockStatus, LinearLayout.LayoutParams(-1, dp(16)))
        }
        val clockArea = FrameLayout(this).apply {
            addView(clockBlock, FrameLayout.LayoutParams(-1, -1))
            addView(notificationBadge, FrameLayout.LayoutParams(-2, dp(20), Gravity.TOP or Gravity.END))
        }
        rail.addView(clockArea, LinearLayout.LayoutParams(dp(if (vertical) 52 else 76), dp(if (vertical) 76 else 48)))
        binding.btnSearch.setImageResource(R.drawable.ic_google)
        binding.btnSearch.contentDescription = "Search or open address"
        val browser = DockBrowser.selected(this)
        if (browser != null) {
            val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(browser, 0)).toString() }.getOrDefault("selected browser")
            binding.btnSearch.contentDescription = "Search or open address with $label"
            packageManager.getLaunchIntentForPackage(browser)?.component?.let { component ->
                lifecycleScope.launch {
                    val icon = withContext(Dispatchers.IO) { IconCache.appIcon(applicationContext, component.packageName, component.className) }
                    if (DockBrowser.selected(this@HomeActivity) == browser && binding.btnSearch.isAttachedToWindow) {
                        binding.btnSearch.setImageDrawable(androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(resources, icon).apply { isCircular = true; setAntiAlias(true) })
                    }
                }
            }
        }
        binding.btnSearch.tooltipText = binding.btnSearch.contentDescription
        if (showSearch) add(rail, binding.btnSearch)
        val group = LinearLayout(this).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            contentDescription = "Pinned apps. Hold to choose dock apps"
            setOnLongClickListener { showDockPicker(); true }
        }
        if (tiles.editMode) {
            add(group, binding.btnAdd); add(group, binding.btnEdit)
        } else {
            val page = resources.getDimensionPixelSize(R.dimen.page_padding) / resources.displayMetrics.density
            val length = if (vertical) resources.configuration.screenHeightDp - (page * 2).toInt() - 40 else resources.configuration.screenWidthDp - (page * 2).toInt()
            val count = AdaptiveLayout.dockCapacity(length, vertical, showRecents, false, showSearch)
            DockApps.pins(this).take(count).forEach { component ->
                val button = ArtworkUi.plainIcon(this, R.drawable.ic_apps, "Open pinned app") {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
                        AppFrequency.record(this, component.packageName, component.className)
                    }.onFailure { Toast.makeText(this, "App unavailable. Hold its dock icon to remove it.", Toast.LENGTH_SHORT).show() }
                }
                button.imageTintList = null
                val label = runCatching { packageManager.getActivityInfo(component, 0).loadLabel(packageManager).toString() }.getOrDefault(component.packageName)
                button.contentDescription = label
                button.setOnLongClickListener {
                    HomeMenu.show(button, label, listOf(
                        HomeMenu.Item(1, "Unpin from dock", R.drawable.ic_close) { DockApps.toggle(this, component); configureRail() },
                        HomeMenu.Item(2, "Choose dock apps", R.drawable.ic_apps) { showDockPicker() }
                    )); true
                }
                lifecycleScope.launch {
                    val icon = withContext(Dispatchers.IO) { IconCache.appIcon(applicationContext, component.packageName, component.className) }
                    if (button.isAttachedToWindow) button.setImageDrawable(androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(resources, icon).apply { isCircular = true; setAntiAlias(true) })
                }
                add(group, button)
            }
        }
        if (vertical) rail.addView(NestedScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(group, ViewGroup.LayoutParams(-1, -2)) }, LinearLayout.LayoutParams(-1, 0, 1f))
        else rail.addView(group, LinearLayout.LayoutParams(0, -1, 1f))
        if (showRecents) add(rail, recents)
        add(rail, binding.btnApps)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        stopDragScroll()
        touchHelper.attachToRecyclerView(null)
        configureRail()
        (binding.grid.layoutManager as GridLayoutManager).spanCount = HomeLayout.columns(this)
        tiles.notifyDataSetChanged()
        widgets.updateLayout()
        touchHelper.attachToRecyclerView(binding.grid)
        ViewCompat.requestApplyInsets(binding.root)
        binding.root.doOnLayout {
            notificationPanel.onConfigurationChanged()
        }
    }

    private var emptyGesture = false
    private var gestureConsumed = false
    private var gestureAtTop = false
    private var pointerY = 0f
    private var dragging = false
    private val gestures by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent) = true
            override fun onDoubleTap(event: MotionEvent): Boolean {
                if (tiles.editMode || !homePreferences.getBoolean("double_tap_lock", true)) return false
                gestureConsumed = true
                systemAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                return true
            }
            override fun onLongPress(event: MotionEvent) {
                if (emptyGesture && !dragging) { gestureConsumed = true; cancelHomeTouch(event); binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); setEditMode(true) }
            }
            override fun onFling(start: MotionEvent?, end: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (start == null || gestureConsumed || dragging) return false
                val swipe = HomeInteractionPolicy.swipe(end.x - start.x, end.y - start.y, velocityX, velocityY, dp(64).toFloat(), dp(200).toFloat()) ?: return false
                val action = HomeInteractionPolicy.action(homePreferences.getString("gesture_action_${swipe.name}", null), swipe, AdaptiveLayout.tablet(this@HomeActivity))
                if (action == HomeInteractionPolicy.Action.NONE) return false
                gestureConsumed = true
                when (action) {
                    HomeInteractionPolicy.Action.APPS -> startActivity(Intent(this@HomeActivity, AppDrawerActivity::class.java))
                    HomeInteractionPolicy.Action.RECENTS -> systemAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                    HomeInteractionPolicy.Action.NOTIFICATIONS -> notificationPanel.show()
                    HomeInteractionPolicy.Action.SEARCH -> showSearch()
                    HomeInteractionPolicy.Action.LOCK -> systemAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                    HomeInteractionPolicy.Action.NONE -> Unit
                }
                return true
            }
        })
    }
    private fun contains(view: View, x: Int, y: Int): Boolean {
        val bounds = Rect()
        return view.visibility == View.VISIBLE && view.getGlobalVisibleRect(bounds) && bounds.contains(x, y)
    }
    private fun emptyHomeSpace(event: MotionEvent): Boolean {
        val x = event.rawX.toInt(); val y = event.rawY.toInt()
        val edges = ViewCompat.getRootWindowInsets(binding.root)?.getInsets(WindowInsetsCompat.Type.systemGestures())
        if (edges != null && (x < edges.left || x >= resources.displayMetrics.widthPixels - edges.right || y >= resources.displayMetrics.heightPixels - edges.bottom)) return false
        if (!contains(binding.root, x, y) || contains(binding.controlRail, x, y) || contains(binding.defaultBanner, x, y)) return false
        // Never steal a gesture from an app tile, a provider widget, or its controls.
        for (i in 0 until binding.grid.childCount) {
            val tile = binding.grid.getChildAt(i)
            if (tiles.editMode && contains(tile, x, y)) return false
            if (contains(tile.findViewById(R.id.card_frame), x, y) || contains(tile.findViewById(R.id.label), x, y)) return false
        }
        for (i in 0 until binding.widgetGrid.childCount) if (contains(binding.widgetGrid.getChildAt(i), x, y)) return false
        return true
    }
    private fun cancelHomeTouch(event: MotionEvent) {
        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        pointerY = event.rawY
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            emptyGesture = !dragging && emptyHomeSpace(event)
            gestureConsumed = false
            gestureAtTop = binding.homeScroll.scrollY == 0
            if (!emptyGesture) {
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                gestures.onTouchEvent(cancel)
                cancel.recycle()
            }
        }
        if (emptyGesture) gestures.onTouchEvent(event)
        val consumed = emptyGesture && gestureConsumed
        val result = if (consumed) {
            cancelHomeTouch(event)
            true
        } else super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) emptyGesture = false
        return result
    }
    private val dragScroll = object : Runnable {
        override fun run() {
            if (!dragging) return
            val viewport = Rect()
            if (binding.homeScroll.getGlobalVisibleRect(viewport)) {
                val step = HomeInteractionPolicy.scrollStep(pointerY - viewport.top, viewport.height(), dp(64), dp(18))
                if (step != 0) binding.homeScroll.scrollBy(0, step)
            }
            ViewCompat.postOnAnimation(binding.homeScroll, this)
        }
    }
    private fun setDragScrolling(active: Boolean) {
        if (!active) { stopDragScroll(); return }
        dragging = true
        binding.homeScroll.requestDisallowInterceptTouchEvent(true)
        binding.homeScroll.removeCallbacks(dragScroll)
        ViewCompat.postOnAnimation(binding.homeScroll, dragScroll)
    }
    private fun stopDragScroll() {
        dragging = false
        binding.homeScroll.removeCallbacks(dragScroll)
        binding.homeScroll.requestDisallowInterceptTouchEvent(false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // HOME key while already home: close overlays and reset editing.
        notificationPanel.dismiss()
        setEditMode(false)
        binding.homeScroll.smoothScrollTo(0, 0)
    }

    @Deprecated("Widget host configuration returns through the Android host API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!::widgets.isInitialized || !widgets.onActivityResult(requestCode, resultCode)) super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onStart() {
        super.onStart()
        if (::widgets.isInitialized) widgets.start()
        dockStatus.start()
    }

    override fun onStop() {
        restoreNotificationPanel = restoreNotificationPanel || (isChangingConfigurations && notificationPanel.isShowing)
        notificationPanel.dismiss()
        stopDragScroll()
        emptyGesture = false
        if (::widgets.isInitialized) widgets.stop()
        dockStatus.stop()
        super.onStop()
    }

    internal fun applyRotationFromPanel() {
        restoreNotificationPanel = false
        RotationControl.applyHome(this)
        notificationPanel.onConfigurationChanged()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("notification_panel_open", restoreNotificationPanel || notificationPanel.isShowing)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        hideStatusBar()
        RotationControl.applyHome(this)
        if (restoreNotificationPanel) {
            restoreNotificationPanel = false
            binding.root.doOnLayout {
                if (!isFinishing && !isDestroyed) notificationPanel.show()
            }
        }
        else notificationPanel.resume()
        configureRail()
        reload()
        binding.defaultBanner.visibility = if (isDefaultHome()) View.GONE else View.VISIBLE
    }

    private fun reload() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { store.all() }
            legacyHistoryAvailable = withContext(Dispatchers.IO) { HistoryMigration.hasHistory(this@HomeActivity) }
            if (dragging) return@launch
            tiles.submitList(items)
            binding.empty.visibility = if (items.isEmpty() && !widgets.hasWidgets) View.VISIBLE else View.GONE
            withContext(Dispatchers.IO) { backfillColors(items) }
        }
    }

    /** Computes and stores a brand colour for tiles that do not have one yet. */
    private fun backfillColors(items: List<HomeTile>) {
        var changed = false
        items.filter { it.color == null }.forEach { tile ->
            val bitmap = when (tile.kind) {
                HomeTile.Kind.APP -> tile.iconPath?.let(IconCache::fileIcon) ?: IconCache.appIcon(this, tile.packageName!!, tile.activityName)
                HomeTile.Kind.WEB -> tile.iconPath?.let(IconCache::fileIcon)
            } ?: return@forEach
            store.setIcon(tile.id, tile.iconPath, TileColors.fromIcon(bitmap))
            changed = true
        }
        if (changed) TileEvents.notifyChanged()
    }

    private fun systemAction(action: Int) {
        if (action == AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN && android.os.Build.VERSION.SDK_INT < 28) {
            Toast.makeText(this, "Screen off needs Android 9 or later. Use your device power button.", Toast.LENGTH_LONG).show()
            return
        }
        if (HomeControlsService.perform(action)) return
        MaterialAlertDialogBuilder(this).setTitle("Enable AniHome controls")
            .setMessage("Enable AniHome controls in Android Accessibility settings to use screen off and recent apps. Screen content is not read.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Open settings") { _, _ ->
                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    .onFailure { Toast.makeText(this, "Accessibility settings are unavailable on this device.", Toast.LENGTH_LONG).show() }
            }.show()
    }

    private fun setEditMode(enabled: Boolean) {
        tiles.editMode = enabled
        widgets.setEditMode(enabled)
        configureRail()
        binding.btnSettings.isActivated = enabled
        ViewCompat.setStateDescription(binding.btnSettings, if (enabled) "Editing home" else "Home settings")
    }

    // ----- opening tiles -----

    private fun open(tile: HomeTile) {
        when (tile.kind) {
            HomeTile.Kind.WEB -> tile.url?.let(::openUrl)
            HomeTile.Kind.APP -> openApp(tile)
        }
    }

    private fun openUrl(url: String) = startActivity(WebActivity.intent(this, url))

    private fun openApp(tile: HomeTile) {
        val pkg = tile.packageName ?: return
        if (tile.isShortcut) {
            runCatching {
                val user = getSystemService(android.os.UserManager::class.java).getUserForSerialNumber(tile.shortcutUser ?: -1)
                    ?: error("Shortcut profile is unavailable")
                getSystemService(android.content.pm.LauncherApps::class.java).startShortcut(pkg, tile.shortcutId!!, null, null, user)
            }.onFailure { Toast.makeText(this, "Shortcut unavailable. Try adding it again from its app.", Toast.LENGTH_LONG).show() }
            return
        }
        val intents = listOfNotNull(InstalledApps.launchIntent(tile), InstalledApps.fallbackLaunchIntent(this, pkg))
        for (intent in intents) {
            try {
                startActivity(intent)
                AppFrequency.record(this, tile.packageName!!, intent.component?.className ?: tile.activityName)
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        Toast.makeText(this, R.string.app_missing, Toast.LENGTH_SHORT).show()
        if (!InstalledApps.isInstalled(this, pkg)) lifecycleScope.launch(Dispatchers.IO) { store.remove(tile.id); TileEvents.notifyChanged() }
    }

    // ----- tile management -----

    private fun showOptions(tile: HomeTile) {
        val actions = mutableListOf<HomeMenu.Item>()
        if (tile.isWeb) {
            actions += HomeMenu.Item(1, getString(R.string.action_rename), R.drawable.ic_edit) { rename(tile) }
            actions += HomeMenu.Item(2, getString(R.string.action_refresh_icon), R.drawable.ic_refresh) { refreshIcon(tile) }
        } else {
            actions += HomeMenu.Item(3, getString(R.string.action_app_info), R.drawable.ic_settings) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + tile.packageName)))
            }
        }
        actions += HomeMenu.Item(4, "Choose tile image", R.drawable.ic_desktop, "Banner Store, Gallery or your own image") {
            startActivity(TileArtworkActivity.intent(this, tile.id))
        }
        if (tile.artworkPath != null) actions += HomeMenu.Item(5, "Reset tile image", R.drawable.ic_refresh) {
            lifecycleScope.launch(Dispatchers.IO) { store.setArtwork(tile.id, null); TileEvents.notifyChanged() }
        }
        actions += HomeMenu.Item(6, getString(R.string.action_remove), R.drawable.ic_close, "Remove this tile from Home") { confirmRemove(tile) }
        val position = tiles.currentList.indexOfFirst { it.id == tile.id }
        val anchor = binding.grid.findViewHolderForAdapterPosition(position)?.itemView ?: binding.btnSettings
        HomeMenu.show(anchor, tile.label, actions)
    }

    private fun rename(tile: HomeTile) {
        Dialogs.input(this, R.string.rename_title, null, tile.label) { value ->
            if (value.isBlank()) return@input
            lifecycleScope.launch(Dispatchers.IO) { store.rename(tile.id, value); TileEvents.notifyChanged() }
        }.show()
    }

    private fun refreshIcon(tile: HomeTile) {
        val url = tile.url ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val path = SiteIcons.fetch(this@HomeActivity, tile.id, url)
            if (path != null) store.setIcon(tile.id, path, TileColors.fromIcon(IconCache.fileIcon(path)))
            TileEvents.notifyChanged()
        }
    }

    private fun confirmRemove(tile: HomeTile) {
        MaterialAlertDialogBuilder(this)
            .setTitle(tile.label)
            .setMessage(R.string.action_remove)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_remove) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) { store.remove(tile.id); TileEvents.notifyChanged() }
            }
            .show()
    }

    private fun showAddMenu(anchor: View) {
        HomeMenu.show(anchor, "Add to Home", listOf(
            HomeMenu.Item(1, getString(R.string.action_add_app), R.drawable.ic_apps, "Choose one or more apps") {
                startActivity(Intent(this, AppDrawerActivity::class.java).putExtra(AppDrawerActivity.EXTRA_PICK, true))
            },
            HomeMenu.Item(2, getString(R.string.action_add_site), R.drawable.ic_add, "Add one or more websites") { Dialogs.addSites(this, ::addSites).show() },
            HomeMenu.Item(3, "Add widget", R.drawable.ic_desktop, "AniHome widgets and installed providers") { widgets.pick() },
        ))
    }

    private fun addSites(sites: List<BatchSites.Site>) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { store.addSites(sites.map { it.url to (it.name ?: SiteIcons.smartTitle(it.url)) }) }
            }
            result.onFailure { Toast.makeText(this@HomeActivity, "Couldn’t add websites. Try again.", Toast.LENGTH_LONG).show() }
            val added = result.getOrNull() ?: return@launch
            TileEvents.notifyChanged()
            Toast.makeText(this@HomeActivity, "${added.size} websites added", Toast.LENGTH_SHORT).show()
            // Artwork can arrive later; the complete batch is already committed and visible.
            added.forEach { tile ->
                val path = SiteIcons.fetch(this@HomeActivity, tile.id, tile.url!!)
                if (path != null) withContext(Dispatchers.IO) { store.setIcon(tile.id, path, TileColors.fromIcon(IconCache.fileIcon(path))) }
                TileEvents.notifyChanged()
            }
        }
    }

    private fun showSearch() {
        Dialogs.input(this, R.string.search_title, R.string.search_hint, null) { query ->
            val q = query.trim()
            if (q.isEmpty()) return@input
            val url = SiteIcons.normalizeUrl(q).takeIf { q.contains('.') && !q.contains(' ') }
                ?: "https://www.google.com/search?q=" + Uri.encode(q)
            val browserIntent = DockBrowser.intent(this, q, url, q.contains('.') && !q.contains(' '))
            if (browserIntent == null) openUrl(url)
            else runCatching { startActivity(browserIntent) }.onFailure {
                Toast.makeText(this, "Selected browser unavailable. Choose another in Dock settings.", Toast.LENGTH_LONG).show()
            }
        }.show()
    }

    private fun showSettingsMenu(@Suppress("UNUSED_PARAMETER") anchor: View) {
        notificationPanel.showSettings()
    }
    private fun populateHomeSettings(parent: LinearLayout) {
        val panel = SettingsPanel(this)
        panel.section("Rotation", R.drawable.ic_rotation) {
            panel.text(this, "Rotation works in AniHome without extra access. Allow system control to apply it to other apps too.")
            addView(ArtworkUi.textChip(this@HomeActivity, "Allow system rotation control") {
                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))) }
            })
            addView(ArtworkUi.textChip(this@HomeActivity, "Follow device rotation setting") { RotationControl.followDevice(this@HomeActivity) })
        }
        panel.section("Dock", R.drawable.ic_recents) {
            panel.toggle(this, "Show search/browser icon", homePreferences.getBoolean("show_dock_search", true)) {
                homePreferences.edit().putBoolean("show_dock_search", it).apply()
                configureRail()
            }
            panel.text(this, "Dock browser")
            val browsers = DockBrowser.choices(this@HomeActivity)
            val browserAdapter = object : android.widget.ArrayAdapter<String>(this@HomeActivity, android.R.layout.simple_spinner_item, browsers.map { it.label }) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = super.getView(position, convertView, parent).apply {
                    (this as android.widget.TextView).setTextColor(-1); textSize = 13f; minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL
                }
                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View = super.getDropDownView(position, convertView, parent).apply {
                    (this as android.widget.TextView).setTextColor(-1); textSize = 13f; setBackgroundColor(0xff202020.toInt()); minHeight = dp(48)
                }
            }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            addView(androidx.appcompat.widget.AppCompatSpinner(this@HomeActivity).apply {
                adapter = browserAdapter; contentDescription = "Dock browser"
                setSelection(browsers.indexOfFirst { it.packageName == DockBrowser.selected(this@HomeActivity) }.coerceAtLeast(0))
                onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                        if (DockBrowser.selected(this@HomeActivity) != browsers[position].packageName) {
                            DockBrowser.select(this@HomeActivity, browsers[position].packageName); configureRail()
                        }
                    }
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                }
            }, LinearLayout.LayoutParams(-1, dp(48)))
            addView(ArtworkUi.textChip(this@HomeActivity, "Choose pinned apps") { notificationPanel.dismiss(); showDockPicker() })
            panel.toggle(this, "Show recent apps button", homePreferences.getBoolean("show_recents_button", AdaptiveLayout.tablet(this@HomeActivity))) {
                homePreferences.edit().putBoolean("show_recents_button", it).apply()
                configureRail()
            }
        }
        panel.section("Gestures", R.drawable.ic_gestures) {
            panel.toggle(this, "Double tap empty Home to lock", homePreferences.getBoolean("double_tap_lock", true)) {
                homePreferences.edit().putBoolean("double_tap_lock", it).apply()
            }
            HomeInteractionPolicy.Swipe.entries.forEach { direction ->
                val row = LinearLayout(this@HomeActivity).apply { gravity = Gravity.CENTER_VERTICAL; orientation = if (AdaptiveLayout.tablet(this@HomeActivity)) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
                row.addView(android.widget.TextView(this@HomeActivity).apply {
                    text = direction.label; textSize = 13f; setTextColor(-1)
                }, if (AdaptiveLayout.tablet(this@HomeActivity)) LinearLayout.LayoutParams(0, dp(48), 1f) else LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
                val actions = HomeInteractionPolicy.Action.entries
                val adapter = object : android.widget.ArrayAdapter<String>(this@HomeActivity, android.R.layout.simple_spinner_item, actions.map { it.label }) {
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = super.getView(position, convertView, parent).apply { (this as android.widget.TextView).setTextColor(-1); textSize = 13f }
                    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View = super.getDropDownView(position, convertView, parent).apply { (this as android.widget.TextView).setTextColor(-1); setBackgroundColor(0xff202020.toInt()); minimumHeight = dp(48); textSize = 13f }
                }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                row.addView(androidx.appcompat.widget.AppCompatSpinner(this@HomeActivity).apply {
                    this.adapter = adapter
                    contentDescription = "${direction.label} gesture action"
                    val selected = HomeInteractionPolicy.action(homePreferences.getString("gesture_action_${direction.name}", null), direction, AdaptiveLayout.tablet(this@HomeActivity))
                    setSelection(actions.indexOf(selected))
                    onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                            homePreferences.edit().putString("gesture_action_${direction.name}", actions[position].name).apply()
                        }
                        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                    }
                }, if (AdaptiveLayout.tablet(this@HomeActivity)) LinearLayout.LayoutParams(0, dp(48), 1.25f) else LinearLayout.LayoutParams(-1, dp(48)))
                addView(row)
            }
        }
        panel.section("Home layout", R.drawable.ic_apps) {
            val options = android.widget.RadioGroup(this@HomeActivity)
            (listOf(0) + (2..6)).forEach { columns ->
                options.addView(androidx.appcompat.widget.AppCompatRadioButton(this@HomeActivity).apply {
                    id = View.generateViewId(); text = if (columns == 0) "Automatic" else "$columns per row"
                    setTextColor(android.graphics.Color.WHITE); textSize = 13f
                    isChecked = HomeLayout.selection(this@HomeActivity) == columns
                    setOnClickListener {
                        homePreferences.edit().putInt("home_columns", columns).apply()
                        (binding.grid.layoutManager as GridLayoutManager).spanCount = HomeLayout.columns(this@HomeActivity)
                        binding.grid.requestLayout()
                        widgets.updateLayout()
                    }
                })
            }
            addView(options)
        }
        panel.section(getString(R.string.action_show_app_names), R.drawable.ic_label) {
            panel.toggle(this, "Show names below tiles", tiles.showLabels) {
                tiles.showLabels = it
                homePreferences.edit().putBoolean("show_tile_labels", it).apply()
            }
        }
        panel.section(getString(R.string.action_about), R.drawable.ic_info) {
            panel.text(this, getString(R.string.about_body))
        }
        if (!isDefaultHome()) panel.section(getString(R.string.action_default_home), R.drawable.ic_home) {
            addView(ArtworkUi.chip(this@HomeActivity, "Choose default launcher", R.drawable.ic_home) { notificationPanel.dismiss(); openHomeSettings() })
        }
        if (legacyHistoryAvailable && !homePreferences.getBoolean("history_imported", false)) panel.section("Transfer watch history", R.drawable.ic_refresh) {
            panel.text(this, "Copy existing local history to AniBrave")
            addView(ArtworkUi.chip(this@HomeActivity, "Transfer", R.drawable.ic_refresh) { notificationPanel.dismiss(); transferHistory() })
        }
        panel.attach(parent)
    }

    private fun showMoreMenu(anchor: View) {
        HomeMenu.show(anchor, "Home controls", listOf(
            HomeMenu.Item(2, "Settings", R.drawable.ic_settings, "Layout and app names") { showSettingsMenu(anchor) },
            HomeMenu.Item(3, "Screen off", R.drawable.ic_screen_off, "Lock the screen") { systemAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) },
            HomeMenu.Item(4, "Notifications", R.drawable.ic_notifications, "View alerts in AniHome") { notificationPanel.show() },
            HomeMenu.Item(5, "Recent apps", R.drawable.ic_recents, "Switch between open apps") { systemAction(AccessibilityService.GLOBAL_ACTION_RECENTS) },
        ))
    }

    private fun transferHistory() {
        MaterialAlertDialogBuilder(this).setTitle("Transfer watch history")
            .setMessage("Copy your existing local watch history to AniBrave? It stays on this device. AniHome will keep the original data.")
            .setNegativeButton("Cancel", null).setPositiveButton("Copy") { _, _ ->
                lifecycleScope.launch {
                    runCatching {
                        val transfer = withContext(Dispatchers.IO) { HistoryMigration.intent(this@HomeActivity) }
                        historyTransfer.launch(transfer)
                    }.onFailure {
                        HistoryMigration.removeTemporaryCopy(this@HomeActivity)
                        Toast.makeText(this@HomeActivity, "Could not transfer history. Make sure AniBrave is updated. Your original history is preserved.", Toast.LENGTH_LONG).show()
                    }
                }
            }.show()
    }

    // ----- default launcher -----

    private fun isDefaultHome(): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val roles = getSystemService(android.app.role.RoleManager::class.java)
            if (roles?.isRoleHeld(android.app.role.RoleManager.ROLE_HOME) == true) return true
        }
        val resolved = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
        return resolved?.activityInfo?.packageName == packageName
    }

    private fun openHomeSettings() {
        if (Build.VERSION.SDK_INT >= 29) {
            val roles = getSystemService(RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                runCatching { homeRoleRequest.launch(roles.createRequestRoleIntent(RoleManager.ROLE_HOME)) }
                    .onSuccess { return }
            }
        }
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }
    }

    private inner class DragCallback : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0,
    ) {
        override fun isLongPressDragEnabled() = false
        override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (!HomeInteractionPolicy.validMove(from, to, tiles.itemCount)) return false
            tiles.move(from, to)
            return true
        }
        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                setDragScrolling(true)
            } else stopDragScroll()
        }
        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            stopDragScroll()
            val order = tiles.currentList.map { it.id }
            lifecycleScope.launch(Dispatchers.IO) { store.reorder(order) }
        }
    }
}
