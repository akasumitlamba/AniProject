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
import androidx.core.view.doOnNextLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.view.updateLayoutParams
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
    private lateinit var board: HomeBoardAdapter
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
        updateDefaultPrompt()
    }
    private val homePreferences by lazy { getSharedPreferences("anihome", MODE_PRIVATE) }

    private lateinit var updater: app.anihome.launcher.updates.AppUpdater
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updater = app.anihome.launcher.updates.AppUpdater(this)
        live = this
        InstalledApps.watch(applicationContext)
        app.anihome.launcher.controls.SystemBar.watch(application)
        RotationControl.applyHome(this)
        notificationPanel = NotificationPanel(this, ::populateHomeSettings)
        restoreNotificationPanel = savedInstanceState?.getBoolean("notification_panel_open") == true
        WallpaperSupport.apply(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        hideStatusBar()
        store = TileStore(this)
        DrawerWarmup.prepare(applicationContext)
        binding.widgetGrid.visibility = View.GONE
        widgets = WidgetController(this, binding.grid, ::setDragScrolling) {
            binding.empty.visibility = View.GONE
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val margin = resources.getDimensionPixelSize(R.dimen.page_padding)
            val vertical = resources.getBoolean(R.bool.vertical_controls)
            binding.content.updatePadding(
                left = bars.left + resources.getDimensionPixelSize(R.dimen.page_padding),
                top = bars.top,
                right = bars.right + if (vertical) dp(72) else margin,
                bottom = bars.bottom + if (vertical) 0 else dp(72)
            )
            binding.controlRail.updateLayoutParams<FrameLayout.LayoutParams> {
                topMargin = if (resources.getBoolean(R.bool.vertical_controls)) bars.top + dp(8) else 0
                bottomMargin = bars.bottom + dp(8)
                marginStart = bars.left + dp(8)
                marginEnd = bars.right + dp(8)
            }
            insets
        }

        tiles = TileAdapter(onOpen = ::open, onOptions = { showOptions(it) }, onRemove = ::confirmRemove, onStartDrag = ::startHomeDrag)
        tiles.showLabels = homePreferences.getBoolean("show_tile_labels", true)
        board = HomeBoardAdapter(this, tiles, widgets)
        binding.grid.layoutManager = HomeBoardLayoutManager(this, board) { tiles.showLabels }
        binding.grid.adapter = board
        binding.grid.itemAnimator = null
        widgets.onEditRequested = { setEditMode(true) }
        widgets.onStartDrag = ::startHomeDrag
        widgets.onResizeStarted = { id -> board.beginGeometryChange("widget:$id") }
        widgets.onResizePosition = { id, dx, dy -> board.resizeAnchor("widget:$id", dx, dy) }
        widgets.onResizeFinished = { board.pack((binding.grid.layoutManager as HomeBoardLayoutManager).columns); board.persist() }
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
        binding.btnDismissDefault.setOnClickListener {
            homePreferences.edit().putBoolean("default_prompt_dismissed", true).apply()
            updateDefaultPrompt()
        }

        configureRail()

        // A home screen never "goes back"; Back just leaves edit mode.
        onBackPressedDispatcher.addCallback(this) { if (tiles.editMode) setEditMode(false) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.anihome.launcher.notifications.HomeNotifications.badges.collect { active ->
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
    private val notificationControl by lazy { FrameLayout(this) }
    private val notificationBadge by lazy { BadgeUi.create(this) }
    private val dockStatus by lazy { DockStatus(this) }
    private val dockClock by lazy { DockClock(this) }
    private val dockAppearance by lazy { DockAppearance(this, binding.controlRail) }
    private fun hideStatusBar() = HomeFullscreen.apply(window)
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideStatusBar() }
    /** "pinned" shows chosen apps; "recent" shows apps last opened from AniHome (no usage access needed). */
    private val recentDock get() = homePreferences.getString("dock_mode", "pinned") == "recent"
    private var dockSignature = ""
    private fun dockApps() = if (recentDock) AppFrequency.recentComponents(this, 5) else DockApps.pins(this)
    private fun setDockMode(recent: Boolean) {
        homePreferences.edit().putString("dock_mode", if (recent) "recent" else "pinned").apply()
        configureRail()
    }
    private fun showDockMenu(anchor: View, component: android.content.ComponentName? = null, label: String? = null) {
        val items = mutableListOf<HomeMenu.Item>()
        if (component != null && !recentDock) items += HomeMenu.Item(1, "Unpin from dock", R.drawable.ic_close, inline = true, short = "Unpin") { DockApps.toggle(this, component); configureRail() }
        if (component != null && recentDock) items += HomeMenu.Item(4, "Pin this app", R.drawable.ic_pin_dock, inline = true, short = "Pin") {
            if (DockApps.pins(this).size >= 5) Toast.makeText(this, "Dock stores up to five pinned apps.", Toast.LENGTH_SHORT).show()
            else if (component !in DockApps.pins(this)) DockApps.toggle(this, component)
            setDockMode(false)
        }
        if (component != null) items += HomeMenu.Item(5, "App info", R.drawable.ic_info, inline = true, short = "Info") {
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + component.packageName))) }
        }
        if (!recentDock) items += HomeMenu.Item(2, "Choose pinned apps", R.drawable.ic_apps) { showDockPicker() }
        items += HomeMenu.Item(3, if (recentDock) "Show pinned apps" else "Show recently used apps", R.drawable.ic_recents,
            if (recentDock) "Pick the apps yourself" else "Fills itself with apps you open from AniHome") { setDockMode(!recentDock) }
        HomeMenu.show(anchor, label ?: "Dock", items)
    }
    private fun showDockPicker() {
        startActivity(Intent(this, AppDrawerActivity::class.java).putExtra(AppDrawerActivity.EXTRA_DOCK_PICK, true))
    }
    private fun configureRail() {
        val iconShape = app.anihome.launcher.data.IconShape.selected(this)
        val vertical = resources.getBoolean(R.bool.vertical_controls)
        val controlSize = if (vertical) 52 else 44
        val showSearch = homePreferences.getBoolean("show_dock_search", true)
        val showRecents = !tiles.editMode && homePreferences.getBoolean("show_recents_button", AdaptiveLayout.tablet(this@HomeActivity))
        val all = listOf(binding.railBrand, binding.btnSearch, binding.btnAdd, binding.btnNotifications,
            binding.btnSettings, binding.btnEdit, binding.btnMore, binding.btnApps, clock, recents, notificationBadge, notificationControl, dockStatus)
        all.forEach { (it.parent as? ViewGroup)?.removeView(it) }
        val rail = binding.controlRail
        rail.removeAllViews()
        dockAppearance.apply()
        rail.setOnLongClickListener { showMoreMenu(rail); true }
        rail.orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        rail.gravity = if (vertical) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
        rail.updateLayoutParams<FrameLayout.LayoutParams> {
            width = if (vertical) dp(56) else ViewGroup.LayoutParams.MATCH_PARENT
            height = if (vertical) ViewGroup.LayoutParams.MATCH_PARENT else dp(56)
            gravity = if (vertical) Gravity.END else Gravity.BOTTOM
        }
        rail.setPadding(dp(if (vertical) 2 else 6), dp(4), dp(if (vertical) 2 else 6), dp(4))
        fun roundDockIcon(button: android.widget.ImageButton) {
            val inset = dp((controlSize - 36) / 2)
            button.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            button.setPadding(inset, inset, inset, inset)
            // Artwork is already cropped using the user's global shape.
            button.clipToOutline = false
        }
        fun add(parent: LinearLayout, view: View, size: Int = controlSize) {
            view.visibility = View.VISIBLE
            if (view is android.widget.ImageButton) {
                val artwork = 36
                val inset = dp((size - artwork) / 2)
                view.setPadding(inset, inset, inset, inset)
            }
            parent.addView(view, LinearLayout.LayoutParams(dp(size), dp(size)))
        }
        notificationControl.removeAllViews()
        binding.btnNotifications.setImageResource(R.drawable.ic_chat)
        val messageInset = dp((controlSize - 36) / 2)
        binding.btnNotifications.setPadding(messageInset, messageInset, messageInset, messageInset)
        binding.btnNotifications.visibility = View.VISIBLE
        notificationControl.addView(binding.btnNotifications, FrameLayout.LayoutParams(-1, -1))
        notificationControl.addView(notificationBadge, FrameLayout.LayoutParams(-2, dp(16), Gravity.TOP or Gravity.END))
        if (!vertical) add(rail, notificationControl)
        notificationBadge.textSize = 9f
        notificationBadge.minWidth = dp(if (vertical) 16 else 20)
        notificationBadge.setPadding(dp(if (vertical) 1 else 4), 0, dp(if (vertical) 1 else 4), 0)
        (dockClock.parent as? ViewGroup)?.removeView(dockClock)
        dockClock.vertical = vertical
        dockClock.setOnClickListener { notificationPanel.show() }
        rail.addView(dockClock, LinearLayout.LayoutParams(if (vertical) -1 else -2, if (vertical) -2 else -1).apply {
            if (vertical) { topMargin = dp(6); bottomMargin = dp(10) } else { marginStart = dp(4); marginEnd = dp(8) }
        })
        binding.btnSearch.setImageResource(R.drawable.ic_google)
        binding.btnSearch.contentDescription = "Search or open address"
        val browser = DockBrowser.selected(this)
        if (browser != null) {
            val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(browser, 0)).toString() }.getOrDefault("selected browser")
            binding.btnSearch.contentDescription = "Search or open address with $label"
            packageManager.getLaunchIntentForPackage(browser)?.component?.let { component ->
                IconCache.cachedDockIcon(applicationContext, component.packageName, component.className)?.let {
                    binding.btnSearch.setImageBitmap(it); roundDockIcon(binding.btnSearch)
                }
                lifecycleScope.launch {
                    val icon = withContext(Dispatchers.IO) { IconCache.dockIcon(applicationContext, component.packageName, component.className) }
                    if (DockBrowser.selected(this@HomeActivity) == browser && app.anihome.launcher.data.IconShape.selected(this@HomeActivity) == iconShape && !isDestroyed && icon != null) {
                        binding.btnSearch.setImageBitmap(icon)
                        roundDockIcon(binding.btnSearch)
                    }
                }
            }
        }
        binding.btnSearch.tooltipText = binding.btnSearch.contentDescription
        if (showSearch) add(rail, binding.btnSearch)
        val group = LinearLayout(this).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            contentDescription = if (recentDock) "Recently used apps. Hold for dock options" else "Pinned apps. Hold for dock options"
            setOnLongClickListener { showDockMenu(this); true }
        }
        if (tiles.editMode) {
            binding.btnAdd.tooltipText = "Add to Home"
            binding.btnEdit.tooltipText = "Done"
            listOf(binding.btnAdd, binding.btnEdit).forEach {
                it.background = android.graphics.drawable.InsetDrawable(androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_dock_apps), dp(4))
            }
            add(group, binding.btnAdd); add(group, binding.btnEdit)
        } else {
            dockApps().also { dockSignature = it.joinToString() }.forEach { component ->
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
                    showDockMenu(button, component, label); true
                }
                // Bind cached artwork immediately; never flash a generic placeholder after rotation.
                val cached = IconCache.cachedDockIcon(applicationContext, component.packageName, component.className)
                if (cached != null) { button.setImageBitmap(cached); roundDockIcon(button) }
                else {
                    button.setImageDrawable(null)
                    lifecycleScope.launch {
                        val icon = withContext(Dispatchers.IO) { IconCache.dockIcon(applicationContext, component.packageName, component.className) }
                        if (!isDestroyed && icon != null) {
                            button.alpha = 0f; button.setImageBitmap(icon); roundDockIcon(button)
                            button.animate().alpha(1f).setDuration(MotionUi.duration(button, 160)).start()
                        }
                    }
                }
                add(group, button)
                button.layoutParams = (button.layoutParams as LinearLayout.LayoutParams).apply {
                    if (vertical) { topMargin = dp(3); bottomMargin = dp(3) }
                    else { marginStart = dp(3); marginEnd = dp(3) }
                }
            }
        }
        if (vertical) rail.addView(NestedScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(group, ViewGroup.LayoutParams(-1, -2)) }, LinearLayout.LayoutParams(-1, 0, 1f))
        else rail.addView(android.widget.HorizontalScrollView(this).apply {
            isFillViewport = true; isHorizontalScrollBarEnabled = false
            addView(group, ViewGroup.LayoutParams(-2, -1))
        }, LinearLayout.LayoutParams(0, -1, 1f))
        if (showRecents) add(rail, recents)
        if (vertical) add(rail, notificationControl)
        add(rail, binding.btnApps)
        // Inset the visible circle equally while preserving the full button touch target.
        binding.btnApps.background = android.graphics.drawable.InsetDrawable(
            androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_dock_apps), dp(4)
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        stopDragScroll()
        touchHelper.attachToRecyclerView(null)
        configureRail()
        binding.grid.requestLayout()
        tiles.notifyDataSetChanged()
        widgets.updateLayout()
        touchHelper.attachToRecyclerView(binding.grid)
        ViewCompat.requestApplyInsets(binding.root)
        binding.root.doOnLayout {
            notificationPanel.onConfigurationChanged()
        }
    }

    private var emptyGesture = false
    private var editExitAt = 0L
    private var gestureConsumed = false
    private var scrollUpAtTouch = false
    private var scrollDownAtTouch = false
    private var scrollStartY = 0
    private var pointerX = 0f
    private var pointerY = 0f
    private var dragging = false
    private var dragCancelled = false
    private var tileOrderEpoch = 0L
    private var tileOrderWrites = 0
    private val gestures by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent) = true
            override fun onSingleTapUp(event: MotionEvent): Boolean {
                if (!emptyGesture || !tiles.editMode || dragging || gestureConsumed) return false
                gestureConsumed = true
                editExitAt = android.os.SystemClock.uptimeMillis()
                setEditMode(false)
                return true
            }
            override fun onDoubleTap(event: MotionEvent): Boolean {
                if (tiles.editMode || android.os.SystemClock.uptimeMillis() - editExitAt < android.view.ViewConfiguration.getDoubleTapTimeout() || !homePreferences.getBoolean("double_tap_lock", HomeControlsService.connected())) return false
                gestureConsumed = true
                systemAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                return true
            }
            override fun onLongPress(event: MotionEvent) {
                if (emptyGesture && !dragging) {
                    gestureConsumed = true; cancelHomeTouch(event); binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    if (!homePreferences.getBoolean("layout_locked", false)) setEditMode(true)
                    showHomeMenu(event.rawX.toInt(), event.rawY.toInt())
                }
            }
            override fun onFling(start: MotionEvent?, end: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (start == null || gestureConsumed || dragging) return false
                val swipe = HomeInteractionPolicy.swipe(end.x - start.x, end.y - start.y, velocityX, velocityY, dp(64).toFloat(), dp(200).toFloat()) ?: return false
                if (!HomeInteractionPolicy.gestureAllowed(swipe, scrollUpAtTouch, scrollDownAtTouch, binding.homeScroll.scrollY != scrollStartY)) return false
                val action = HomeInteractionPolicy.action(homePreferences.getString("gesture_action_${swipe.name}", null), swipe, AdaptiveLayout.tablet(this@HomeActivity))
                if (action == HomeInteractionPolicy.Action.NONE) return false
                gestureConsumed = true
                when (action) {
                    HomeInteractionPolicy.Action.APPS -> startActivity(Intent(this@HomeActivity, AppDrawerActivity::class.java).putExtra("motion_direction", swipe.name))
                    HomeInteractionPolicy.Action.RECENTS -> systemAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                    HomeInteractionPolicy.Action.NOTIFICATIONS -> notificationPanel.show(swipe.name)
                    HomeInteractionPolicy.Action.SEARCH -> showSearch()
                    HomeInteractionPolicy.Action.LOCK -> systemAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                    HomeInteractionPolicy.Action.NONE -> Unit
                }
                return true
            }
        })
    }
    private fun contains(view: View?, x: Int, y: Int): Boolean {
        val bounds = Rect()
        return view != null && view.visibility == View.VISIBLE && view.getGlobalVisibleRect(bounds) && bounds.contains(x, y)
    }
    private fun emptyHomeSpace(event: MotionEvent): Boolean {
        val x = event.rawX.toInt(); val y = event.rawY.toInt()
        val edges = ViewCompat.getRootWindowInsets(binding.root)?.getInsets(WindowInsetsCompat.Type.systemGestures())
        if (edges != null && (x < edges.left || x >= resources.displayMetrics.widthPixels - edges.right || y >= resources.displayMetrics.heightPixels - edges.bottom)) return false
        if (!contains(binding.root, x, y) || contains(binding.controlRail, x, y) || contains(binding.defaultBanner, x, y)) return false
        // Never steal a gesture from an app tile, a provider widget, or its controls.
        for (i in 0 until binding.grid.childCount) {
            val tile = binding.grid.getChildAt(i)
            if ((tiles.editMode || board.isWidget(binding.grid.getChildAdapterPosition(tile))) && contains(tile, x, y)) return false
            if (contains(tile.findViewById(R.id.card_frame), x, y) || contains(tile.findViewById(R.id.label), x, y)) return false
        }
        for (i in 0 until binding.widgetGrid.childCount) {
            val container = binding.widgetGrid.getChildAt(i) as? ViewGroup ?: continue
            for (j in 0 until container.childCount) if (contains(container.getChildAt(j), x, y)) return false
        }
        return true
    }
    private fun cancelHomeTouch(event: MotionEvent) {
        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        pointerX = event.rawX
        pointerY = event.rawY
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) dragCancelled = true
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            dragCancelled = false
            emptyGesture = !dragging && emptyHomeSpace(event)
            gestureConsumed = false
            scrollUpAtTouch = binding.homeScroll.canScrollVertically(-1)
            scrollDownAtTouch = binding.homeScroll.canScrollVertically(1)
            scrollStartY = binding.homeScroll.scrollY
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
        if (event.actionMasked == MotionEvent.ACTION_UP) HomeMenu.finishGesture()
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) emptyGesture = false
        return result
    }
    private val dragScroll = object : Runnable {
        override fun run() {
            if (!dragging) return
            val viewport = Rect()
            if (binding.homeScroll.getGlobalVisibleRect(viewport)) {
                val step = HomeInteractionPolicy.scrollStep(pointerY - viewport.top, viewport.height(), dp(64), dp(18))
                if (step != 0) { binding.homeScroll.scrollBy(0, step); binding.grid.invalidate() }
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
        if (intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) { setEditMode(false); showSettingsMenu(binding.root); return }
        if (intent.getBooleanExtra(EXTRA_TRANSFER, false)) { transferHistory(); return }
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
        dockStatus.start(); dockClock.start()
        dockAppearance.start()
    }

    override fun onDestroy() {
        if (live === this) live = null
        super.onDestroy()
    }

    override fun onStop() {
        restoreNotificationPanel = restoreNotificationPanel || (isChangingConfigurations && notificationPanel.isShowing)
        notificationPanel.dismiss()
        stopDragScroll()
        DrawerItemWarmup.pause()
        emptyGesture = false
        if (::widgets.isInitialized) widgets.stop()
        dockStatus.stop(); dockClock.stop()
        dockAppearance.stop()
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

    private var appliedIconShape: String? = null
    override fun onResume() {
        super.onResume()
        val shape = app.anihome.launcher.data.IconShape.selected(this).name
        if (appliedIconShape != shape) { appliedIconShape = shape; tiles.notifyDataSetChanged() }
        hideStatusBar()
        RotationControl.applyHome(this)
        // Recent-apps dock follows what was opened while Home was away.
        if (recentDock && !tiles.editMode && dockApps().joinToString() != dockSignature) configureRail()
        if (restoreNotificationPanel) {
            restoreNotificationPanel = false
            binding.root.doOnLayout {
                if (!isFinishing && !isDestroyed) notificationPanel.show(restoreSection = true)
            }
        }
        else notificationPanel.resume()
        configureRail()
        DrawerWarmup.prepare(applicationContext)
        DrawerItemWarmup.warm(applicationContext)
        reload()
        updateDefaultPrompt()
    }

    private var loadedBoard = false
    private var reloadJob: kotlinx.coroutines.Job? = null
    private fun reload() {
        val epoch = tileOrderEpoch
        reloadJob?.cancel()
        reloadJob = lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { store.all() }
            legacyHistoryAvailable = withContext(Dispatchers.IO) { HistoryMigration.hasHistory(this@HomeActivity) }
            if (dragging || tileOrderWrites > 0 || epoch != tileOrderEpoch) return@launch
            val previous = tiles.currentList.map { it.id }.toSet()
            val added = if (!loadedBoard) null else items.lastOrNull { it.id !in previous }
            loadedBoard = true
            tiles.submitList(items)
            board.loaded = true
            if (!homePreferences.getBoolean("banner_backfill_v1", false)) {
                homePreferences.edit().putBoolean("banner_backfill_v1", true).apply()
                if (withContext(Dispatchers.IO) { store.queueMissingBanners() } > 0) { TileEvents.notifyChanged(); return@launch }
            }
            if (items.any { it.artworkOrigin == "pending" }) app.anihome.launcher.banners.AutomaticBanners.enqueue(applicationContext)
            added?.let { tile -> binding.grid.post {
                val index = tiles.currentList.indexOfFirst { it.id == tile.id }
                val view = binding.grid.findViewHolderForAdapterPosition(index)?.itemView
                if (view != null) {
                    if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
                        binding.homeScroll.smoothScrollTo(0, view.top)
                        view.animate().cancel(); view.alpha = 1f
                    } else binding.homeScroll.scrollTo(0, view.top)
                }
            } }
            binding.empty.visibility = View.GONE
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
            changed = store.backfillColor(tile.id, tile.iconPath, TileColors.fromIcon(bitmap)) || changed
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

    /**
     * iOS-style: holding still shows only the item's menu; holding and then moving dismisses the
     * menu and drags the item, and Home enters edit mode when the finger lifts.
     */
    private var editAfterDrag = false
    private fun startHomeDrag(holder: RecyclerView.ViewHolder) {
        val key = board.keyAt(holder.absoluteAdapterPosition) ?: return
        if (homePreferences.getBoolean("layout_locked", false)) {
            tiles.currentList.firstOrNull { "tile:${it.id}" == key }?.let { showOptions(it) }
                ?: key.removePrefix("widget:").toIntOrNull()?.let { widgets.showOptions(it, holder.itemView) }
            return
        }
        editAfterDrag = !tiles.editMode
        touchHelper.startDrag(holder)
    }

    private fun setEditMode(enabled: Boolean) {
        if (!enabled) HomeMenu.dismiss()
        if (enabled && homePreferences.getBoolean("layout_locked", false)) {
            Toast.makeText(this, "Home layout is locked. Unlock it in Home settings.", Toast.LENGTH_SHORT).show()
            return
        }
        tiles.editMode = enabled
        board.editing = enabled
        widgets.setEditMode(enabled)
        binding.grid.requestLayout()
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

    private val aniBraveInstaller by lazy { app.anihome.launcher.updates.AniBraveInstaller(this) }
    private fun openApp(tile: HomeTile) {
        val pkg = tile.packageName ?: return
        // The default AniBrave tile stays on Home before AniBrave is installed and offers to install it.
        if (pkg == app.anihome.launcher.updates.AniBraveInstaller.PACKAGE && !app.anihome.launcher.updates.AniBraveInstaller.installed(packageManager)) {
            aniBraveInstaller.offer(); return
        }
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
        if (!InstalledApps.isInstalled(this, pkg)) lifecycleScope.launch(Dispatchers.IO) { store.remove(tile.id, updatePins = false); TileEvents.notifyChanged() }
    }

    // ----- tile management -----

    private fun showOptions(tile: HomeTile, keepGesture: Boolean = false) {
        val component = if (tile.isApp && tile.activityName != null) android.content.ComponentName(tile.packageName!!, tile.activityName) else null
        val actions = (if (tile.isApp) AppShortcuts.menuItems(this, tile.packageName, component) else emptyList()).toMutableList()
        if (tile.isApp) actions += HomeMenu.Item(7, if (tile.iconOnly) "Use banner tile" else "Use app icon", R.drawable.ic_apps, inline = true, short = if (tile.iconOnly) "Banner" else "Icon") {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    store.setTileStyle(tile.id, !tile.iconOnly)
                    if (!tile.iconOnly) IconCache.dockIcon(applicationContext, tile.packageName!!, tile.activityName)
                }
                board.priority = "tile:${tile.id}"
                TileEvents.notifyChanged()
            }
        }
        actions += HomeMenu.Item(1, getString(R.string.action_rename), R.drawable.ic_edit, inline = true, short = "Rename") { rename(tile) }
        if (tile.isWeb) {
            actions += HomeMenu.Item(2, getString(R.string.action_refresh_icon), R.drawable.ic_refresh, inline = true, short = "Refresh") { refreshIcon(tile) }
        } else {
            if (component != null) {
                val docked = component in DockApps.pins(this)
                actions += HomeMenu.Item(8, if (docked) "Unpin from dock" else "Pin to dock", R.drawable.ic_pin_dock, inline = true, short = if (docked) "Unpin" else "Dock") {
                    if (!docked && DockApps.pins(this).size >= 5) Toast.makeText(this, "Dock stores up to five apps. Unpin one first.", Toast.LENGTH_SHORT).show()
                    else { DockApps.toggle(this, component); configureRail() }
                }
            }
            actions += HomeMenu.Item(3, getString(R.string.action_app_info), R.drawable.ic_info, inline = true, short = "Info") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + tile.packageName)))
            }
            actions += HomeMenu.Item(9, "Uninstall", R.drawable.ic_delete, inline = true, short = "Uninstall") {
                runCatching { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + tile.packageName))) }
            }
        }
        actions += HomeMenu.Item(4, "Choose tile image", R.drawable.ic_gallery, inline = true, short = "Image") {
            startActivity(TileArtworkActivity.intent(this, tile.id))
        }
        if (tile.artworkPath != null) actions += HomeMenu.Item(5, "Reset tile image", R.drawable.ic_refresh, inline = true, short = "Reset") {
            lifecycleScope.launch(Dispatchers.IO) { store.setArtwork(tile.id, null); TileEvents.notifyChanged() }
        }
        actions += HomeMenu.Item(6, getString(R.string.action_remove), R.drawable.ic_close, inline = true, short = "Remove") { confirmRemove(tile) }
        val position = tiles.currentList.indexOfFirst { it.id == tile.id }
        val anchor = binding.grid.findViewHolderForAdapterPosition(position)?.itemView ?: binding.btnSettings
        HomeMenu.show(anchor, tile.label, actions, keepGesture)
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
        if (tile.isShortcut) {
            MaterialAlertDialogBuilder(this).setTitle("Remove ${tile.label} from Home?")
                .setMessage("The app stays installed. Android may require the shortcut to be pinned again later.")
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_remove) { _, _ -> removeTile(tile, false) }.show()
        } else removeTile(tile, true)
    }
    private fun removeTile(tile: HomeTile, undoable: Boolean) {
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { store.remove(tile.id, preserveAssets = undoable) } }
                .onSuccess {
                    TileEvents.notifyChanged()
                    if (undoable) {
                        var undo = false
                        com.google.android.material.snackbar.Snackbar.make(binding.root, "${tile.label} removed from Home", 4000)
                            .setAction("Undo") {
                                undo = true
                                lifecycleScope.launch {
                                    runCatching { withContext(Dispatchers.IO) { store.restore(tile) } }
                                        .onSuccess { TileEvents.notifyChanged() }
                                        .onFailure { Toast.makeText(this@HomeActivity, "Could not restore tile", Toast.LENGTH_LONG).show() }
                                }
                            }.addCallback(object : com.google.android.material.snackbar.Snackbar.Callback() {
                                override fun onDismissed(bar: com.google.android.material.snackbar.Snackbar?, event: Int) {
                                    if (!undo) lifecycleScope.launch(Dispatchers.IO) { store.discardAssets(tile) }
                                }
                            }).show()
                    }
                }.onFailure { Toast.makeText(this@HomeActivity, "Could not remove tile. Please retry.", Toast.LENGTH_LONG).show() }
        }
    }

    /** Long press on empty Home: everything about the home screen itself, at the finger. */
    private fun showHomeMenu(x: Int, y: Int) {
        val locked = homePreferences.getBoolean("layout_locked", false)
        HomeMenu.showAt(binding.root, x, y, "Home screen", listOf(
            HomeMenu.Item(1, "Add widget", R.drawable.ic_desktop, "Weather, clock, battery, notes and app widgets") { widgets.pick() },
            HomeMenu.Item(2, getString(R.string.action_add_app), R.drawable.ic_apps, "Choose one or more apps") {
                startActivity(Intent(this, AppDrawerActivity::class.java).putExtra(AppDrawerActivity.EXTRA_PICK, true))
            },
            HomeMenu.Item(3, getString(R.string.action_add_site), R.drawable.ic_add, "Add one or more websites") { Dialogs.addSites(this, ::addSites).show() },
            HomeMenu.Item(4, "Wallpaper", R.drawable.ic_gallery, "Change the home and lock screen picture") {
                runCatching { startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Choose wallpaper")) }
                    .onFailure { Toast.makeText(this, "No wallpaper picker is installed", Toast.LENGTH_SHORT).show() }
            },
            HomeMenu.Item(5, "AniHome Settings", R.drawable.ic_settings, "Layout, dock, panel and gestures") { showSettingsMenu(binding.root) },
            HomeMenu.Item(6, if (locked) "Unlock layout" else "Lock layout", R.drawable.ic_lock, "Prevent accidental changes", checked = locked) {
                homePreferences.edit().putBoolean("layout_locked", !locked).apply()
                if (!locked) setEditMode(false)
            },
        ))
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
        startActivity(Intent(this, AppDrawerActivity::class.java).putExtra(AppDrawerActivity.EXTRA_SEARCH, true))
    }

    private fun showSettingsMenu(@Suppress("UNUSED_PARAMETER") anchor: View) {
        notificationPanel.dismiss()
        startActivity(Intent(this, HomeSettingsActivity::class.java))
    }
    /**
     * Builds every launcher setting into [parent]. [ui] is the screen showing them (the
     * AniHome Settings app or this Home); dialogs and messages use it, while Home-side effects
     * (dock, tiles, rotation) still apply to this live launcher.
     */
    internal fun populateHomeSettings(parent: LinearLayout, ui: AppCompatActivity = this) {
        // Tablet full-screen panel: two-pane settings; phones and the side panel: section cards.
        val panel = SettingsPanel(ui, twoPane = if (ui is HomeSettingsActivity) ui.resources.configuration.screenWidthDp >= 840 else SettingsPanel.twoPane(this))
        panel.section("Rotation", R.drawable.ic_rotation, summary = { if (RotationControl.mode(this) == "Auto") "Auto-rotate" else "Locked at current orientation" }) {
            panel.toggle(this, "Auto-rotate", RotationControl.mode(ui) == "Auto") {
                notificationPanel.setAutomaticRotation(it)
            }
            panel.text(this, "Turn off to lock the current orientation. Rotation follows Android system settings.")
            if (!Settings.System.canWrite(ui)) panel.action(this, "System rotation access") {
                runCatching { ui.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))) }
            }
        }
        panel.section("Dock", R.drawable.ic_settings_dock) {
            lateinit var opacity: View
            panel.toggle(this, "Dynamic opacity", homePreferences.getBoolean("dock_dynamic_opacity", true)) {
                homePreferences.edit().putBoolean("dock_dynamic_opacity", it).apply()
                opacity.visibility = if (it) View.GONE else View.VISIBLE
                dockAppearance.apply()
            }
            opacity = panel.slider(this, "Opacity", homePreferences.getInt("dock_opacity", 72)) {
                homePreferences.edit().putInt("dock_opacity", it).apply(); dockAppearance.apply()
            }
            opacity.visibility = if (homePreferences.getBoolean("dock_dynamic_opacity", true)) View.GONE else View.VISIBLE
            panel.toggle(this, "Show search icon", homePreferences.getBoolean("show_dock_search", true)) {
                homePreferences.edit().putBoolean("show_dock_search", it).apply(); configureRail()
            }
            val browsers = DockBrowser.choices(ui)
            panel.choice(this, "Web browser", browsers.map { it.label }, browsers.indexOfFirst { it.packageName == DockBrowser.selected(ui) }.coerceAtLeast(0)) {
                DockBrowser.select(ui, browsers[it].packageName); configureRail()
            }
            panel.choice(this, "Dock shows", listOf("Pinned apps", "Recently used apps"), if (recentDock) 1 else 0) { setDockMode(it == 1) }
            panel.action(this, "Choose pinned apps") { notificationPanel.dismiss(); showDockPicker() }
            panel.toggle(this, "Show recent apps", homePreferences.getBoolean("show_recents_button", AdaptiveLayout.tablet(ui))) {
                homePreferences.edit().putBoolean("show_recents_button", it).apply(); configureRail()
            }
        }
        panel.section("Application menu", R.drawable.ic_settings_apps) {
            ShapePicker.row(ui, this) { tiles.notifyDataSetChanged(); configureRail() }
            panel.slider(this, "Background opacity", app.anihome.launcher.data.DrawerPreferences(ui).opacity()) {
                app.anihome.launcher.data.DrawerPreferences(ui).setOpacity(it)
            }
            panel.toggle(this, "Show recent apps", app.anihome.launcher.data.DrawerPreferences(ui).showRecentApps()) {
                app.anihome.launcher.data.DrawerPreferences(ui).setShowRecentApps(it)
            }
            panel.toggle(this, "Show recent files", app.anihome.launcher.data.DrawerPreferences(ui).showRecentFiles()) {
                app.anihome.launcher.data.DrawerPreferences(ui).setShowRecentFiles(it)
                DrawerWarmup.prepare(applicationContext)
            }
        }
        panel.section("Banner store", R.drawable.ic_settings_store) {
            panel.text(this, "Downloaded banners stay on this device for offline use.")
            panel.action(this, "Store repository") { chooseBannerStore(ui) }
        }
        panel.section("Notification panel", R.drawable.ic_settings_layout,
            summary = { if (PanelStyle.wide(this)) "Full screen" else "Side panel" }) {
            panel.choice(this, "Layout", listOf("Side panel", "Full screen"), if (PanelStyle.wide(ui)) 1 else 0) {
                PanelStyle.setWide(ui, it == 1)
            }
            panel.text(this, "Full screen puts the controls and tall brightness and volume sliders across the top, with notifications in two columns below. Settings open side by side.")
            panel.choice(this, "Background", listOf("Blurred glass", "Solid"), if (PanelStyle.blur(ui)) 0 else 1) {
                PanelStyle.set(ui, "panel_blur", it == 0)
            }
            if (android.os.Build.VERSION.SDK_INT < 31) panel.text(this, "This Android version can't blur other apps, so over apps the glass is nearly solid.")
            panel.toggle(this, "Brightness slider", PanelStyle.brightness(ui)) { PanelStyle.set(ui, "cc_show_brightness", it) }
            panel.toggle(this, "Volume slider", PanelStyle.volume(ui)) { PanelStyle.set(ui, "cc_show_volume", it) }
            panel.toggle(this, "Now playing card", PanelStyle.media(ui)) { PanelStyle.set(ui, "cc_show_media", it) }
            panel.text(this, "Choose and order the round controls with the pencil at the top of the panel.")
        }
        panel.section("Swipe-down panel", R.drawable.ic_notifications,
            summary = { if (app.anihome.launcher.controls.SystemBar.edgePanel(this)) "On in every app" else "Off" }) {
            val bar = app.anihome.launcher.controls.SystemBar
            panel.toggle(this, "Swipe down from the top opens AniHome's panel", bar.edgePanel(ui)) {
                bar.setEdgePanel(ui, it)
                if (it && !HomeControlsService.connected()) {
                    Toast.makeText(ui, "Turn on AniHome controls in Accessibility, then come back", Toast.LENGTH_LONG).show()
                    runCatching { ui.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                }
            }
            panel.text(this, "Android's own status bar stays as it is. Swiping down from the top edge opens AniHome's notifications and controls instead of Android's shade, in any app. The lock screen is left alone.")
        }
        panel.section("Sound & Bluetooth", R.drawable.ic_cc_volume,
            summary = { if (app.anihome.launcher.controls.VolumePanel.enabled(this)) "AniHome volume panel" else "Android volume panel" }) {
            panel.toggle(this, "AniHome volume panel", app.anihome.launcher.controls.VolumePanel.enabled(ui)) {
                app.anihome.launcher.controls.VolumePanel.setEnabled(ui, it)
                if (it && !HomeControlsService.connected()) {
                    Toast.makeText(ui, "Turn on AniHome controls in Accessibility, then come back", Toast.LENGTH_LONG).show()
                    runCatching { ui.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                } else if (it) app.anihome.launcher.controls.ShizukuGate.require(ui, "Per-app volume")
            }
            panel.text(this, "Volume buttons open AniHome's panel: media, ring, notifications, alarm, and a slider for each app playing sound (with Shizuku). Calls and the lock screen keep Android's panel.")
            panel.toggle(this, "Keep two Bluetooth audio devices connected", getSharedPreferences("anihome", MODE_PRIVATE).getBoolean("bt_two_devices", false)) { on ->
                if (!app.anihome.launcher.controls.ShizukuGate.require(ui, "Two Bluetooth audio devices")) return@toggle
                notificationPanel.connectPrivileged()
                window.decorView.postDelayed({
                    notificationPanel.systemToggle("bt_max_devices", on) { result ->
                        if (result.isEmpty()) {
                            getSharedPreferences("anihome", MODE_PRIVATE).edit().putBoolean("bt_two_devices", on).apply()
                            Toast.makeText(ui, "Saved. Turn Bluetooth off and on to apply.", Toast.LENGTH_LONG).show()
                        } else Toast.makeText(ui, "This device didn't allow it: $result", Toast.LENGTH_LONG).show()
                    }
                }, 800)
            }
            panel.text(this, "Lets two headphones or speakers stay connected so you can switch between them in the Bluetooth list instantly. Android plays sound on one at a time, unless your phone has its own dual-audio feature.")
        }
        panel.section("Gestures", R.drawable.ic_gestures) {
            if (!HomeControlsService.connected()) panel.text(this, "Lock and Recent apps need AniHome controls in Android accessibility settings.")
            panel.toggle(this, "Double tap to lock", homePreferences.getBoolean("double_tap_lock", HomeControlsService.connected())) {
                homePreferences.edit().putBoolean("double_tap_lock", it).apply()
                if (it && !HomeControlsService.connected()) runCatching { ui.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
            HomeInteractionPolicy.Swipe.entries.forEach { direction ->
                val actions = HomeInteractionPolicy.Action.entries
                val current = HomeInteractionPolicy.action(homePreferences.getString("gesture_action_${direction.name}", null), direction, AdaptiveLayout.tablet(ui))
                panel.choice(this, direction.label, actions.map { it.label }, actions.indexOf(current)) {
                    homePreferences.edit().putString("gesture_action_${direction.name}", actions[it].name).apply()
                }
            }
        }
        panel.section("Home layout", R.drawable.ic_settings_layout) {
            val columns = listOf(0) + (2..6)
            panel.choice(this, "Columns", columns.map { if (it == 0) "Automatic" else "$it per row" }, columns.indexOf(HomeLayout.selection(ui)).coerceAtLeast(0)) {
                homePreferences.edit().putInt("home_columns", columns[it]).apply()
                binding.grid.requestLayout(); widgets.updateLayout()
            }
            panel.toggle(this, "Lock layout", homePreferences.getBoolean("layout_locked", false)) {
                homePreferences.edit().putBoolean("layout_locked", it).apply()
                if (it && tiles.editMode) setEditMode(false)
            }
        }
        panel.section("App and website names", R.drawable.ic_settings_labels) {
            panel.toggle(this, "Show names", tiles.showLabels) {
                tiles.showLabels = it; homePreferences.edit().putBoolean("show_tile_labels", it).apply()
            }
        }
        panel.section(getString(R.string.action_about), R.drawable.ic_info) {
            panel.text(this, getString(R.string.about_body))
            panel.action(this, "Update app") { (if (ui is HomeSettingsActivity) ui.updater else updater).check(this) }
            panel.action(this, "AniProject on GitHub") {
                runCatching { ui.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/akasumitlamba/AniProject"))) }
            }
        }
        if (!isDefaultHome()) panel.section(getString(R.string.action_default_home), R.drawable.ic_home) {
            panel.action(this, "Set as home") { notificationPanel.dismiss(); openHomeSettings() }
        }
        if (legacyHistoryAvailable && !homePreferences.getBoolean("history_imported", false)) panel.section("Transfer watch history", R.drawable.ic_settings_history) {
            panel.action(this, "Transfer to AniBrave") { notificationPanel.dismiss(); if (ui === this) transferHistory() else { startActivity(Intent(this@HomeActivity, HomeActivity::class.java).putExtra(EXTRA_TRANSFER, true)); ui.finish() } }
        }
        panel.attach(parent)
    }

    private fun chooseBannerStore(ui: AppCompatActivity = this) {
        val input = android.widget.EditText(ui).apply {
            setText(app.anihome.launcher.banners.BannerSource.configured(this@HomeActivity))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(-1); textSize = 14f; maxLines = 4
        }
        val body = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(android.widget.TextView(context).apply {
                text = "Use a GitHub repository with banners/catalog.json, or an HTTPS catalog.json URL from another store. The catalog must use AniHome’s banner format."
                textSize = 13f; setTextColor(0xffbbbbbb.toInt()); setPadding(0, 0, 0, dp(12))
            })
            addView(input, LinearLayout.LayoutParams(-1, -2))
        }
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(ui).setTitle("Banner store")
            .setView(body).setNegativeButton("Cancel", null).setNeutralButton("Use default") { _, _ ->
                app.anihome.launcher.banners.BannerSource.select(this, "")
            }.setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                runCatching { app.anihome.launcher.banners.BannerSource.select(this, input.text.toString()) }
                    .onSuccess { dialog.dismiss(); Toast.makeText(ui, "Banner store saved", Toast.LENGTH_SHORT).show() }
                    .onFailure { input.error = "Enter a GitHub repository or HTTPS catalog URL" }
            }
        }
        dialog.show()
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

    private fun updateDefaultPrompt() {
        val isHome = isDefaultHome()
        if (isHome) homePreferences.edit().putBoolean("was_default_home", true).apply()
        else if (homePreferences.getBoolean("was_default_home", false)) {
            homePreferences.edit().putBoolean("was_default_home", false).remove("default_prompt_dismissed").apply()
        }
        binding.defaultBanner.visibility = if (isHome || homePreferences.getBoolean("default_prompt_dismissed", false)) View.GONE else View.VISIBLE
    }
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
        ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
    ) {
        private var key: String? = null
        private var optionsTask: Runnable? = null
        private var moved = false
        private var gripX = 0f
        private var gripY = 0f
        private var dropX = 0f
        private var dropY = 0f
        private var lifted: View? = null
        // ItemTouchHelper normally animates a manual drop back to its original bounds.
        // The board owns placement; release must commit before that recovery frame.
        override fun getAnimationDuration(recycler: RecyclerView, type: Int, dx: Float, dy: Float): Long =
            if (type == ItemTouchHelper.ANIMATION_TYPE_DRAG) 0L else super.getAnimationDuration(recycler, type, dx, dy)
        override fun isLongPressDragEnabled() = false
        override fun onMove(view: RecyclerView, holder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false
        override fun onSelectedChanged(holder: RecyclerView.ViewHolder?, state: Int) {
            super.onSelectedChanged(holder, state)
            if (state == ItemTouchHelper.ACTION_STATE_DRAG && holder != null) {
                key = board.keyAt(holder.absoluteAdapterPosition)
                board.beginDrag()
                val location = IntArray(2)
                holder.itemView.getLocationOnScreen(location)
                gripX = pointerX - location[0]; gripY = pointerY - location[1]
                moved = false
                optionsTask?.let(binding.grid::removeCallbacks)
                optionsTask = Runnable {
                    if (!moved && holder.itemView.isAttachedToWindow) {
                        val tile = tiles.currentList.firstOrNull { "tile:${it.id}" == key }
                        if (tile != null) showOptions(tile, true)
                        else key?.removePrefix("widget:")?.toIntOrNull()?.let { widgets.showOptions(it, holder.itemView) }
                    }
                }.also { binding.grid.postDelayed(it, 160) }
                dropX = holder.itemView.left.toFloat(); dropY = holder.itemView.top.toFloat() - binding.grid.paddingTop
                tileOrderEpoch++
                setDragScrolling(true)
                // Lift: the picked-up card rises above its neighbours.
                lifted = holder.itemView.also { view ->
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    view.animate().scaleX(1.05f).scaleY(1.05f).translationZ(dp(12).toFloat())
                        .setDuration(MotionUi.duration(view, 160)).setInterpolator(MotionUi.STANDARD).start()
                }
            } else {
                optionsTask?.let(binding.grid::removeCallbacks); optionsTask = null
                (binding.grid.layoutManager as HomeBoardLayoutManager).settlePreview(key)
                val droppedKey = key
                val view = lifted
                // Where the card is on screen at release, so it can glide into its slot.
                val from = view?.let { IntArray(2).also(it::getLocationOnScreen) }
                key = null; lifted = null
                val enterEdit = editAfterDrag && moved && !dragCancelled
                editAfterDrag = false
                board.finishDrag(dragCancelled || !moved)
                stopDragScroll()
                if (enterEdit) setEditMode(true)
                if (droppedKey != null && from != null) settleDrop(droppedKey, from[0].toFloat(), from[1].toFloat())
                view?.animate()?.scaleX(1f)?.scaleY(1f)?.translationZ(0f)?.setDuration(MotionUi.duration(view, 180))?.start()
            }
        }
        override fun onChildDraw(canvas: android.graphics.Canvas, recycler: RecyclerView, holder: RecyclerView.ViewHolder,
            dx: Float, dy: Float, state: Int, active: Boolean) {
            // Keep the entire dragged item inside the visible scrolling viewport. Ancestor
            // clipping must never make a card disappear before edge scrolling catches up.
            val visible = Rect()
            val bounded = active && state == ItemTouchHelper.ACTION_STATE_DRAG && recycler.getLocalVisibleRect(visible)
            val location = IntArray(2)
            recycler.getLocationOnScreen(location)
            val fingerDx = pointerX - location[0] - gripX - holder.itemView.left
            val fingerDy = pointerY - location[1] - gripY - holder.itemView.top
            val safeDx = if (bounded) fingerDx.coerceIn(
                (visible.left + dp(6) - holder.itemView.left).toFloat(),
                (visible.right - dp(6) - holder.itemView.right).coerceAtLeast(visible.left + dp(6) - holder.itemView.left).toFloat()) else dx
            val safeDy = if (bounded) fingerDy.coerceIn(
                (visible.top + dp(6) - holder.itemView.top).toFloat(),
                (visible.bottom - dp(6) - holder.itemView.bottom).coerceAtLeast(visible.top + dp(6) - holder.itemView.top).toFloat()) else dy
            if (active && state == ItemTouchHelper.ACTION_STATE_DRAG) {
                if (!moved && kotlin.math.hypot(dx, dy) > android.view.ViewConfiguration.get(this@HomeActivity).scaledTouchSlop) {
                    moved = true; optionsTask?.let(recycler::removeCallbacks); HomeMenu.dismiss()
                }
                dropX = holder.itemView.left + safeDx
                dropY = holder.itemView.top + safeDy - recycler.paddingTop
                val layout = recycler.layoutManager as HomeBoardLayoutManager
                val placement = board.placements.firstOrNull { it.key == key }
                val draggedKey = key
                if (moved && placement != null && draggedKey != null) {
                    val x = kotlin.math.round((dropX - recycler.paddingLeft - dp(8)) / layout.cellWidth).toInt()
                    val y = kotlin.math.round((dropY - dp(8)) / layout.cellHeight).toInt()
                    if (board.previewMove(draggedKey, x, y, layout.columns)) layout.preview(draggedKey)
                }
            }
            super.onChildDraw(canvas, recycler, holder, safeDx, safeDy, state, active)
        }
        /** FLIP: after the board relayouts, animate the dropped card from its release point. */
        private fun settleDrop(droppedKey: String, fromX: Float, fromY: Float) {
            binding.grid.doOnNextLayout {
                val position = (0 until board.itemCount).firstOrNull { board.keyAt(it) == droppedKey } ?: return@doOnNextLayout
                val target = binding.grid.findViewHolderForAdapterPosition(position)?.itemView ?: return@doOnNextLayout
                if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return@doOnNextLayout
                val to = IntArray(2).also(target::getLocationOnScreen)
                val dx = fromX - (to[0] - target.translationX); val dy = fromY - (to[1] - target.translationY)
                if (kotlin.math.hypot(dx, dy) < 1f) return@doOnNextLayout
                target.animate().cancel()
                target.translationX = dx; target.translationY = dy
                target.scaleX = 1.05f; target.scaleY = 1.05f
                target.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
                    .setDuration(240).setInterpolator(MotionUi.EMPHASIZED_DECELERATE).start()
            }
        }
        override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) = Unit
        override fun clearView(recycler: RecyclerView, holder: RecyclerView.ViewHolder) {
            super.clearView(recycler, holder)
            (recycler.layoutManager as HomeBoardLayoutManager).settlePreview()
            optionsTask?.let(recycler::removeCallbacks)
            optionsTask = null
            tileOrderEpoch++; stopDragScroll()
        }
    }
    companion object {
        const val EXTRA_OPEN_SETTINGS = "anihome.open_settings"
        const val EXTRA_TRANSFER = "anihome.transfer_history"
        /** The running launcher, which owns the dock/tiles that settings change. */
        @Volatile internal var live: HomeActivity? = null
    }
}
