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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        InstalledApps.watch(applicationContext)
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
    private val dockAppearance by lazy { DockAppearance(this, binding.controlRail) }
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
            button.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setOval(inset, inset, view.width - inset, view.height - inset)
                }
            }
            button.clipToOutline = true
            button.invalidateOutline()
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
        dockStatus.onPowerIndicatorsChanged = { _ ->
            if (vertical) androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                clock, 16, 19, 1, android.util.TypedValue.COMPLEX_UNIT_SP
            ) else {
                androidx.core.widget.TextViewCompat.setAutoSizeTextTypeWithDefaults(clock, androidx.core.widget.TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE)
                clock.textSize = 16f
            }
        }
        clock.gravity = if (vertical) Gravity.CENTER else Gravity.START or Gravity.CENTER_VERTICAL
        clock.format24Hour = "HH:mm"
        clock.format12Hour = "h:mm"
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
        clock.includeFontPadding = false
        clock.setOnClickListener { notificationPanel.show() }
        clock.tooltipText = "Notifications"
        val timeAndDate = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (vertical) Gravity.CENTER else Gravity.START or Gravity.CENTER_VERTICAL
            addView(LinearLayout(this@HomeActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(clock, if (vertical) LinearLayout.LayoutParams(0, -1, 1f) else LinearLayout.LayoutParams(-2, -1))
            }, LinearLayout.LayoutParams(if (vertical) -1 else -2, dp(if (vertical) 24 else 20)))
            addView(android.widget.TextClock(this@HomeActivity).apply {
                format24Hour = "dd MMM"; format12Hour = "dd MMM"
                setTextColor(-1); textSize = if (vertical) 14f else 16f
                gravity = if (vertical) Gravity.CENTER else Gravity.START or Gravity.CENTER_VERTICAL
                includeFontPadding = false; setOnClickListener { notificationPanel.show() }
            }, LinearLayout.LayoutParams(if (vertical) -1 else -2, dp(if (vertical) 20 else 18)))
        }
        val clockBlock = LinearLayout(this).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            if (vertical) {
                addView(timeAndDate, LinearLayout.LayoutParams(-1, dp(44)))
                addView(dockStatus, LinearLayout.LayoutParams(-1, dp(28)))
            } else {
                addView(dockStatus, LinearLayout.LayoutParams(dp(28), -1))
                addView(timeAndDate, LinearLayout.LayoutParams(-2, -1).apply { marginStart = dp(2) })
            }
        }
        val clockArea = FrameLayout(this).apply {
            addView(clockBlock, FrameLayout.LayoutParams(-1, -1))
        }
        rail.addView(clockArea, LinearLayout.LayoutParams(if (vertical) dp(52) else -2, dp(if (vertical) 78 else 48)))
        binding.btnSearch.setImageResource(R.drawable.ic_google)
        binding.btnSearch.contentDescription = "Search or open address"
        val browser = DockBrowser.selected(this)
        if (browser != null) {
            val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(browser, 0)).toString() }.getOrDefault("selected browser")
            binding.btnSearch.contentDescription = "Search or open address with $label"
            packageManager.getLaunchIntentForPackage(browser)?.component?.let { component ->
                lifecycleScope.launch {
                    val icon = withContext(Dispatchers.IO) { IconCache.dockIcon(applicationContext, component.packageName, component.className) }
                    if (DockBrowser.selected(this@HomeActivity) == browser && !isDestroyed && icon != null) {
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
            contentDescription = "Pinned apps. Hold to choose dock apps"
            setOnLongClickListener { showDockPicker(); true }
        }
        if (tiles.editMode) {
            binding.btnAdd.tooltipText = "Add to Home"
            binding.btnEdit.tooltipText = "Done"
            listOf(binding.btnAdd, binding.btnEdit).forEach {
                it.background = android.graphics.drawable.InsetDrawable(androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_dock_apps), dp(4))
            }
            add(group, binding.btnAdd); add(group, binding.btnEdit)
        } else {
            val page = resources.getDimensionPixelSize(R.dimen.page_padding) / resources.displayMetrics.density
            val length = if (vertical) resources.configuration.screenHeightDp - (page * 2).toInt() - 40 else resources.configuration.screenWidthDp - (page * 2).toInt() - 20
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
                    val icon = withContext(Dispatchers.IO) { IconCache.dockIcon(applicationContext, component.packageName, component.className) }
                    if (!isDestroyed && icon != null) { button.setImageBitmap(icon); roundDockIcon(button) }
                }
                add(group, button)
                button.layoutParams = (button.layoutParams as LinearLayout.LayoutParams).apply {
                    if (vertical) { topMargin = dp(3); bottomMargin = dp(3) }
                    else { marginStart = dp(3); marginEnd = dp(3) }
                }
            }
        }
        if (vertical) rail.addView(NestedScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(group, ViewGroup.LayoutParams(-1, -2)) }, LinearLayout.LayoutParams(-1, 0, 1f))
        else rail.addView(group, LinearLayout.LayoutParams(0, -1, 1f))
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
                if (emptyGesture && !dragging) { gestureConsumed = true; cancelHomeTouch(event); binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); setEditMode(true) }
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
        dockAppearance.start()
    }

    override fun onStop() {
        restoreNotificationPanel = restoreNotificationPanel || (isChangingConfigurations && notificationPanel.isShowing)
        notificationPanel.dismiss()
        stopDragScroll()
        DrawerItemWarmup.pause()
        emptyGesture = false
        if (::widgets.isInitialized) widgets.stop()
        dockStatus.stop()
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

    override fun onResume() {
        super.onResume()
        hideStatusBar()
        RotationControl.applyHome(this)
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
            if (items.any { it.isApp && it.artworkOrigin == "pending" }) app.anihome.launcher.banners.AutomaticBanners.enqueue(applicationContext)
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

    private fun startHomeDrag(holder: RecyclerView.ViewHolder) {
        if (homePreferences.getBoolean("layout_locked", false)) { setEditMode(true); return }
        val key = board.keyAt(holder.absoluteAdapterPosition) ?: return
        if (tiles.editMode) { touchHelper.startDrag(holder); return }
        setEditMode(true)
        binding.grid.doOnLayout {
            val position = (0 until board.itemCount).firstOrNull { board.keyAt(it) == key } ?: return@doOnLayout
            binding.grid.findViewHolderForAdapterPosition(position)?.let { touchHelper.startDrag(it) }
        }
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
        if (!InstalledApps.isInstalled(this, pkg)) lifecycleScope.launch(Dispatchers.IO) { store.remove(tile.id, updatePins = false); TileEvents.notifyChanged() }
    }

    // ----- tile management -----

    private fun showOptions(tile: HomeTile, keepGesture: Boolean = false) {
        val actions = mutableListOf<HomeMenu.Item>()
        if (tile.isApp) actions += HomeMenu.Item(7, if (tile.iconOnly) "Use banner tile" else "Use app icon", R.drawable.ic_apps) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    store.setTileStyle(tile.id, !tile.iconOnly)
                    if (!tile.iconOnly) IconCache.dockIcon(applicationContext, tile.packageName!!, tile.activityName)
                }
                board.priority = "tile:${tile.id}"
                TileEvents.notifyChanged()
            }
        }
        actions += HomeMenu.Item(1, getString(R.string.action_rename), R.drawable.ic_edit) { rename(tile) }
        if (tile.isWeb) {
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
        notificationPanel.showSettings()
    }
    private fun populateHomeSettings(parent: LinearLayout) {
        val panel = SettingsPanel(this)
        panel.section("Rotation", R.drawable.ic_rotation, summary = { if (RotationControl.mode(this) == "Auto") "Auto-rotate" else "Locked at current orientation" }) {
            panel.toggle(this, "Auto-rotate", RotationControl.mode(this@HomeActivity) == "Auto") {
                notificationPanel.setAutomaticRotation(it)
            }
            panel.text(this, "Turn off to lock the current orientation. Rotation follows Android system settings.")
            if (!Settings.System.canWrite(this@HomeActivity)) panel.action(this, "System rotation access") {
                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))) }
            }
        }
        panel.section("Dock", R.drawable.ic_recents) {
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
            val browsers = DockBrowser.choices(this@HomeActivity)
            panel.choice(this, "Web browser", browsers.map { it.label }, browsers.indexOfFirst { it.packageName == DockBrowser.selected(this@HomeActivity) }.coerceAtLeast(0)) {
                DockBrowser.select(this@HomeActivity, browsers[it].packageName); configureRail()
            }
            panel.action(this, "Pinned apps") { notificationPanel.dismiss(); showDockPicker() }
            panel.toggle(this, "Show recent apps", homePreferences.getBoolean("show_recents_button", AdaptiveLayout.tablet(this@HomeActivity))) {
                homePreferences.edit().putBoolean("show_recents_button", it).apply(); configureRail()
            }
        }
        panel.section("Gestures", R.drawable.ic_gestures) {
            if (!HomeControlsService.connected()) panel.text(this, "Lock and Recent apps need AniHome controls in Android accessibility settings.")
            panel.toggle(this, "Double tap to lock", homePreferences.getBoolean("double_tap_lock", HomeControlsService.connected())) {
                homePreferences.edit().putBoolean("double_tap_lock", it).apply()
                if (it && !HomeControlsService.connected()) runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
            HomeInteractionPolicy.Swipe.entries.forEach { direction ->
                val actions = HomeInteractionPolicy.Action.entries
                val current = HomeInteractionPolicy.action(homePreferences.getString("gesture_action_${direction.name}", null), direction, AdaptiveLayout.tablet(this@HomeActivity))
                panel.choice(this, direction.label, actions.map { it.label }, actions.indexOf(current)) {
                    homePreferences.edit().putString("gesture_action_${direction.name}", actions[it].name).apply()
                }
            }
        }
        panel.section("Home layout", R.drawable.ic_apps) {
            val columns = listOf(0) + (2..6)
            panel.choice(this, "Columns", columns.map { if (it == 0) "Automatic" else "$it per row" }, columns.indexOf(HomeLayout.selection(this@HomeActivity)).coerceAtLeast(0)) {
                homePreferences.edit().putInt("home_columns", columns[it]).apply()
                binding.grid.requestLayout(); widgets.updateLayout()
            }
            panel.toggle(this, "Lock layout", homePreferences.getBoolean("layout_locked", false)) {
                homePreferences.edit().putBoolean("layout_locked", it).apply()
                if (it && tiles.editMode) setEditMode(false)
            }
        }
        panel.section("App and website names", R.drawable.ic_label) {
            panel.toggle(this, "Show names", tiles.showLabels) {
                tiles.showLabels = it; homePreferences.edit().putBoolean("show_tile_labels", it).apply()
            }
        }
        panel.section(getString(R.string.action_about), R.drawable.ic_info) {
            panel.text(this, getString(R.string.about_body))
            panel.action(this, "AniProject on GitHub") {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/akasumitlamba/AniProject"))) }
            }
        }
        if (!isDefaultHome()) panel.section(getString(R.string.action_default_home), R.drawable.ic_home) {
            panel.action(this, "Set as home") { notificationPanel.dismiss(); openHomeSettings() }
        }
        if (legacyHistoryAvailable && !homePreferences.getBoolean("history_imported", false)) panel.section("Transfer watch history", R.drawable.ic_refresh) {
            panel.action(this, "Transfer to AniBrave") { notificationPanel.dismiss(); transferHistory() }
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
            } else {
                optionsTask?.let(binding.grid::removeCallbacks); optionsTask = null
                val layout = binding.grid.layoutManager as HomeBoardLayoutManager
                val droppedKey = key
                key = null
                if (moved && !dragCancelled && droppedKey != null) {
                    board.moveToCell(droppedKey, kotlin.math.round(dropX / layout.cellWidth).toInt(), kotlin.math.round(dropY / layout.cellHeight).toInt(), layout.columns)
                }
                stopDragScroll()
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
                if (placement != null) {
                    val x = kotlin.math.round(dropX / layout.cellWidth).toInt().coerceIn(0, (layout.columns - placement.columns).coerceAtLeast(0))
                    val y = kotlin.math.round(dropY / layout.cellHeight).toInt().coerceAtLeast(0)
                    val rect = android.graphics.RectF(x * layout.cellWidth + dp(4), y * layout.cellHeight + recycler.paddingTop + dp(4), (x + placement.columns) * layout.cellWidth - dp(4), (y + placement.rows) * layout.cellHeight + recycler.paddingTop - dp(4))
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0x448fdaff }
                    canvas.drawRoundRect(rect, dp(14).toFloat(), dp(14).toFloat(), paint)
                    paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = dp(2).toFloat(); paint.color = 0xff9edfff.toInt()
                    canvas.drawRoundRect(rect, dp(14).toFloat(), dp(14).toFloat(), paint)
                }
            }
            super.onChildDraw(canvas, recycler, holder, safeDx, safeDy, state, active)
        }
        override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) = Unit
        override fun clearView(recycler: RecyclerView, holder: RecyclerView.ViewHolder) {
            super.clearView(recycler, holder)
            optionsTask?.let(recycler::removeCallbacks)
            optionsTask = null
            tileOrderEpoch++; stopDragScroll()
        }
    }
}
