package app.anihome.launcher.ui

import android.app.Notification
import android.content.Intent
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.text.format.DateFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.anihome.launcher.R
import app.anihome.launcher.notifications.HomeNotifications
import app.anihome.launcher.notifications.NotificationPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import java.util.Date

/** Compact left sheet, opposite the Home rail. No notification content survives dismissal. */
internal class NotificationPanel(private val activity: AppCompatActivity, settingsContent: (LinearLayout) -> Unit) {
    private val quickSettings = QuickSettings(activity, settingsContent)
    private var dialog: AppCompatDialog? = null
    private var collector: Job? = null
    private var controlCenter: ControlCenter? = null
    /** Sheet hugs the right edge (when opened by a right-edge swipe over another app). */
    var rightSide = false
    /** Called once the sheet has fully closed; the over-app host finishes its activity. */
    var onDismissed: (() -> Unit)? = null
    val privilegedReady: Boolean get() = quickSettings.privilegedReady
    fun systemToggle(id: String, enabled: Boolean, done: (String) -> Unit) = quickSettings.systemToggle(id, enabled, done)
    fun connectPrivileged() = quickSettings.connectPrivileged()
    private val expandedKeys = mutableSetOf<String>()
    val isShowing: Boolean get() = dialog?.isShowing == true
    fun dismiss() { dialog?.dismiss() }
    fun resume() { if (quickSettings.consumeReturn()) show(restoreSection = true) }
    fun onConfigurationChanged() {
        dialog?.window?.let { quickSettings.resize(SidePanelLayout.apply(activity, it, rightSide, PanelStyle.wide(activity)).height) }
    }
    private fun dp(n: Int) = ArtworkUi.dp(activity, n)
    fun setAutomaticRotation(automatic: Boolean) = quickSettings.setAutomaticRotation(automatic)
    /** Settings now live in the AniHome Settings app. */
    fun showSettings() { dismiss(); activity.startActivity(Intent(activity, HomeSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    fun show(direction: String = "RIGHT", restoreSection: Boolean = false) {
        if (dialog?.isShowing == true) return
        if (!restoreSection) quickSettings.showNotifications()
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            val navigation = androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)?.getInsetsIgnoringVisibility(androidx.core.view.WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
            view.setPadding(dp(12) + safe.left, dp(8) + safe.top, dp(12) + safe.right, dp(8) + maxOf(safe.bottom, navigation))
            insets
        }
        // Settings mode keeps a plain title; notifications mode shows the Control Center instead.
        // Page header for Wi-Fi, Bluetooth and Home settings: back arrow + title.
        val heading = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(8)) }
        val back = ArtworkUi.plainIcon(activity, R.drawable.ic_back, "Back to notifications") { closeSection() }
        compactIcon(back, 44)
        heading.addView(back)
        val headingTitle = ArtworkUi.title(activity, "Home settings").apply { textSize = 18f; setPadding(dp(4), 0, 0, 0) }
        heading.addView(headingTitle, LinearLayout.LayoutParams(0, dp(44), 1f))
        heading.visibility = View.GONE
        root.addView(heading)
        val center = ControlCenter(activity, quickSettings, ::dismiss) {
            dismiss()
            activity.startActivity(Intent(activity, HomeSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        controlCenter = center
        // The Control Center grid replaces the old footer row; radio lists open under the grid.
        quickSettings.showControlRow = false
        quickSettings.radioParent = null
        val sectionRow = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(10), 0, dp(2)) }
        val sectionTitle = TextView(activity).apply { text = "Notifications"; textSize = 13f; setTextColor(0xffbdbdbd.toInt()); setTypeface(null, android.graphics.Typeface.BOLD) }
        sectionRow.addView(sectionTitle, LinearLayout.LayoutParams(0, -2, 1f))
        val clear = ArtworkUi.plainIcon(activity, R.drawable.ic_clear_all, "Clear all notifications") { }
        compactIcon(clear, 40)
        sectionRow.addView(clear)
        val state = TextView(activity).apply { textSize = 13f; setTextColor(0xffbdbdbd.toInt()); setPadding(dp(6), dp(20), dp(6), dp(12)) }
        val access = ArtworkUi.chip(activity, "Notification access", R.drawable.ic_notifications) {
            dismiss()
            runCatching { activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                .onFailure { Toast.makeText(activity, "Notification settings unavailable", Toast.LENGTH_LONG).show() }
        }
        val empty = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(state); addView(access, LinearLayout.LayoutParams(-1, dp(48)))
        }
        // Full-screen layout: controls in a left column, notifications filling the rest.
        val wide = PanelStyle.wide(activity)
        center.wideLayout = wide
        val headerContent = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(center.view, LinearLayout.LayoutParams(-1, -2))
            addView(sectionRow, LinearLayout.LayoutParams(-1, -2))
            addView(empty, LinearLayout.LayoutParams(-1, -2))
        }
        val emptyScroll = empty
        val adapter = Cards()
        lateinit var list: RecyclerView
        clear.setOnClickListener {
            expandedKeys.clear()
            // Cards slide away one after another, then the list closes up.
            val clearable = adapter.items.filter { it.isClearable }.map { it.key }.toSet()
            if (clearable.isEmpty()) { Toast.makeText(activity, "These notifications can only be removed by their apps", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            // One smooth cascade, top card first; each runs on its own GPU layer.
            val leaving = (0 until list.childCount).map { list.getChildAt(it) }
                .filter { (list.getChildViewHolder(it) as? Card)?.key in clearable }.sortedBy { it.top * 10 + it.left / 100 }
            var delay = 0L
            leaving.forEach { child ->
                child.animate().cancel()
                child.animate().translationX(child.width * 1.1f).alpha(0f).setStartDelay(delay)
                    .setDuration(MotionUi.duration(child, 240)).setInterpolator(MotionUi.EMPHASIZED_ACCELERATE).withLayer().start()
                delay += 35
            }
            clear.isEnabled = false
            list.postDelayed({
                clear.isEnabled = true
                adapter.remove(clearable)
                HomeNotifications.clearAll { success ->
                    if (!success && isShowing) Toast.makeText(activity, "Some notifications could not be dismissed", Toast.LENGTH_SHORT).show()
                }
            }, MotionUi.duration(list, delay + 240))
        }
        // Full screen: notifications flow in two columns under the controls.
        list = RecyclerView(activity).apply { layoutManager = if (wide) androidx.recyclerview.widget.GridLayoutManager(activity, 2).apply {
            // Even rows: cards sit side by side at equal height; the header spans both columns.
            spanSizeLookup = object : androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup() { override fun getSpanSize(position: Int) = if (position == 0) 2 else 1 }
        } else LinearLayoutManager(activity); this.adapter = androidx.recyclerview.widget.ConcatAdapter(Header(headerContent), adapter); itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply {
            // Removed cards have already slid away; the rest glide up into place.
            addDuration = 180; removeDuration = 90; moveDuration = 260; changeDuration = 0; supportsChangeAnimations = false
        } }
        if (!wide) list.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                // Only touches that start on the Control Center (top of the header) count.
                val header = center.view
                val overControls = header.isAttachedToWindow && run {
                    val at = IntArray(2).also(header::getLocationOnScreen); val rvAt = IntArray(2).also(rv::getLocationOnScreen)
                    val top = at[1] - rvAt[1]; e.y >= top && e.y <= top + header.height
                }
                return center.interceptSwipe(e, overControls)
            }
        })
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(view: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder) = false
            private fun clearable(holder: RecyclerView.ViewHolder) = adapter.items.getOrNull(holder.bindingAdapterPosition)?.isClearable == true
            override fun getSwipeDirs(view: RecyclerView, holder: RecyclerView.ViewHolder): Int =
                if (holder !is Card) 0 else super.getSwipeDirs(view, holder)
            // Ongoing notifications can be nudged but spring back: Android keeps them until their app removes them.
            override fun getSwipeThreshold(holder: RecyclerView.ViewHolder) = if (clearable(holder)) .4f else Float.MAX_VALUE
            override fun getSwipeEscapeVelocity(defaultValue: Float) = defaultValue * 1.5f
            override fun getSwipeVelocityThreshold(defaultValue: Float) = defaultValue * 3f
            override fun onChildDraw(c: android.graphics.Canvas, recycler: RecyclerView, holder: RecyclerView.ViewHolder, dX: Float, dY: Float, state: Int, active: Boolean) {
                val resisted = if (!clearable(holder)) dX * .35f else dX
                holder.itemView.alpha = 1f - (kotlin.math.abs(resisted) / holder.itemView.width.coerceAtLeast(1)).coerceIn(0f, .7f)
                super.onChildDraw(c, recycler, holder, resisted, dY, state, active)
            }
            override fun clearView(recycler: RecyclerView, holder: RecyclerView.ViewHolder) {
                if (!clearable(holder) && kotlin.math.abs(holder.itemView.translationX) > holder.itemView.width * .15f)
                    Toast.makeText(activity, "Android keeps this notification until its app removes it", Toast.LENGTH_SHORT).show()
                super.clearView(recycler, holder); holder.itemView.alpha = 1f
            }
            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) {
                (holder as? Card)?.key?.let { dismissNotification(it, adapter) }
            }
        }).attachToRecyclerView(list)
        val notifications = FrameLayout(activity).apply {
            addView(list, FrameLayout.LayoutParams(-1, -1))
        }
        val settings = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, 0) }
        root.addView(notifications, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(settings)
        quickSettings.onSectionChanged = { section ->
            val page = section != null
            // QuickSettings animates the page itself; a second, nested transition here left the
            // new page's rows stuck invisible until something forced a redraw.
            headingTitle.text = if (section == "Settings") "Home settings" else section.orEmpty()
            heading.visibility = if (page) View.VISIBLE else View.GONE
            notifications.visibility = if (page) View.GONE else View.VISIBLE
            if (page) center.detach() else { center.attach(); center.refresh() }
            settings.layoutParams = LinearLayout.LayoutParams(-1, if (page) 0 else -2, if (page) 1f else 0f)
            // Full screen: keep pages at a readable width, centred.
            // Full screen on narrower (phone-like) widths: keep the card list at a readable width.
            if (wide && activity.resources.configuration.screenWidthDp < 840) settings.post { val side = ((settings.width - dp(760)) / 2).coerceAtLeast(0); settings.setPadding(side, dp(4), side, 0) }
        }
        val sheetHeight = SidePanelLayout.bounds(activity).height
        quickSettings.attach(settings, sheetHeight)
        root.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) quickSettings.resize(bottom - top)
        }
        val sheet = object : AppCompatDialog(activity, R.style.Theme_AniHome_Sidebar) {
            private var leaving = false
            // Slide back toward the edge instead of vanishing; Back, outside taps and actions all share it.
            override fun dismiss() {
                if (leaving || !isShowing || instantDismiss) { super.dismiss(); return }
                leaving = true
                MotionUi.exit(root, direction) { super.dismiss() }
            }
            override fun onWindowFocusChanged(hasFocus: Boolean) {
                super.onWindowFocusChanged(hasFocus)
                if (hasFocus) window?.let(HomeFullscreen::apply)
            }
            // Back closes an open page first, then the panel.
            @Deprecated("Dialog back handling") override fun onBackPressed() {
                if (quickSettings.section != null) closeSection() else super.onBackPressed()
            }
        }.apply { setContentView(root) }
        dialog = sheet
        sheet.setCancelable(true)
        sheet.setCanceledOnTouchOutside(true)
        sheet.setOnDismissListener { styleCleanup?.invoke(); styleCleanup = null; app.anihome.launcher.controls.SystemBar.suppress("panel", false); onDismissed?.invoke(); center.detach(); controlCenter = null; quickSettings.radioParent = null; quickSettings.onSectionChanged = null; quickSettings.detach(); collector?.cancel(); collector = null; adapter.items = emptyList(); expandedKeys.clear(); dialog = null
            afterDismiss?.let { next -> afterDismiss = null; activity.window.decorView.post(next) } }
        sheet.window?.let { SidePanelLayout.apply(activity, it, rightSide, wide); it.setWindowAnimations(0) }
        sheet.show()
        sheet.window?.let { SidePanelLayout.apply(activity, it, rightSide, wide); it.setWindowAnimations(0) }
        PanelBackdrop.apply(activity, sheet.window, root, rightSide, wide || SidePanelLayout.phone(activity), overApps = activity is PanelActivity, fromTop = direction == "DOWN")
        // Live preview of panel customisations made from inside the panel.
        val styleListener: (String) -> Unit = { key ->
            when (key) {
                "panel_opacity", "panel_blur" -> PanelBackdrop.apply(activity, sheet.window, root, rightSide, wide || SidePanelLayout.phone(activity), overApps = activity is PanelActivity, fromTop = direction == "DOWN")
                "panel_layout" -> root.post { rebuild() }
                else -> center.refresh()
            }
        }
        PanelStyle.listeners += styleListener
        styleCleanup = { PanelStyle.listeners -= styleListener }
        app.anihome.launcher.controls.SystemBar.suppress("panel", true)
        MotionUi.enter(root, direction)
        if (heading.visibility != View.VISIBLE) center.attach()
        androidx.core.view.ViewCompat.requestApplyInsets(root)
        if (HomeNotifications.enabled(activity) && !HomeNotifications.isConnected()) runCatching { HomeNotifications.reconnect(activity) }
        collector = activity.lifecycleScope.launch {
            combine(HomeNotifications.notifications, HomeNotifications.connection) { items, connected -> items to connected }.collect { (notifications, connected) ->
                val visible = NotificationPolicy.visible(notifications.map {
                    NotificationPolicy.Entry(it.key, it.groupKey, it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0, it.isClearable)
                })
                expandedKeys.retainAll(visible)
                adapter.submit(notifications.filter { it.key in visible })
                val enabled = HomeNotifications.enabled(activity)
                state.text = when {
                    !enabled -> "Allow notification access to see alerts here. Everything stays on this device."
                    !connected -> "Connecting to notifications…"
                    adapter.items.isEmpty() -> "All caught up"
                    else -> ""
                }
                emptyScroll.visibility = if (state.text.isEmpty() && enabled) View.GONE else View.VISIBLE
                state.visibility = if (state.text.isEmpty()) View.GONE else View.VISIBLE
                sectionTitle.text = if (adapter.items.isEmpty()) "Notifications" else "Notifications · ${adapter.items.size}"
                access.visibility = if (enabled) View.GONE else View.VISIBLE
                clear.isEnabled = enabled && notifications.any { it.isClearable }
                clear.alpha = if (clear.isEnabled) 1f else 0.4f
            }
        }
    }
    private fun closeSection() { quickSettings.showNotifications() }
    private var styleCleanup: (() -> Unit)? = null
    private var instantDismiss = false
    private var afterDismiss: (() -> Unit)? = null
    /** Switching side panel / full screen rebuilds in place, staying on the settings page. */
    private fun rebuild() {
        val wasSettings = quickSettings.section == "Settings"
        val keepDismissed = onDismissed; onDismissed = null // An over-app host must not finish here.
        // Android delivers the old sheet's dismiss callback later; reopen only after its cleanup ran.
        afterDismiss = {
            onDismissed = keepDismissed
            if (wasSettings) showSettings() else show(restoreSection = true)
        }
        instantDismiss = true; dialog?.dismiss(); instantDismiss = false
    }
    private fun dismissNotification(key: String, adapter: Cards) {
        expandedKeys.remove(key)
        // Remove at once so the swiped card's exit animation completes; a refused dismissal
        // reappears with the next update from Android.
        adapter.remove(setOf(key))
        HomeNotifications.dismiss(key) { success ->
            if (!success && isShowing) Toast.makeText(activity, "This notification could not be dismissed", Toast.LENGTH_SHORT).show()
        }
    }
    private fun compactIcon(icon: ImageButton, size: Int) {
        icon.layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        val padding = (size - 20) / 2
        icon.setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }
    private inner class Cards : RecyclerView.Adapter<Card>() {
        var items = emptyList<StatusBarNotification>()
        fun remove(keys: Set<String>) = submit(items.filter { it.key !in keys })
        fun submit(next: List<StatusBarNotification>) {
            val previous = items
            val diff = androidx.recyclerview.widget.DiffUtil.calculateDiff(object : androidx.recyclerview.widget.DiffUtil.Callback() {
                override fun getOldListSize() = previous.size
                override fun getNewListSize() = next.size
                override fun areItemsTheSame(old: Int, new: Int) = previous[old].key == next[new].key
                override fun areContentsTheSame(old: Int, new: Int) = false
            })
            items = next; diff.dispatchUpdatesTo(this)
        }
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Card {
            val root = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(3), dp(6), dp(8))
                background = android.graphics.drawable.GradientDrawable().apply { setColor(ControlCenter.SURFACE); cornerRadius = dp(20).toFloat(); setStroke(dp(1), ControlCenter.STROKE) }
                foreground = androidx.core.content.ContextCompat.getDrawable(activity, R.drawable.bg_drawer_item)
                clipToOutline = true; isFocusable = true; isClickable = true
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = dp(8); if (PanelStyle.wide(activity)) { leftMargin = dp(4); rightMargin = dp(4) } }
                // Full screen: one fixed card height so the two columns form a tidy grid.
                if (PanelStyle.wide(activity)) minimumHeight = dp(112)
            }
            val top = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
            val label = TextView(activity).apply { textSize = 11f; setTextColor(0xffbdbdbd.toInt()); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            val icon = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            top.addView(icon, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(6) })
            top.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
            val close = ArtworkUi.plainIcon(activity, R.drawable.ic_close, "Dismiss notification") { }
            val expand = ArtworkUi.plainIcon(activity, R.drawable.ic_expand_more, "Expand notification") { }
            compactIcon(expand, 32); compactIcon(close, 32)
            top.addView(expand); top.addView(close)
            val title = TextView(activity).apply { textSize = 14f; setTextColor(-1); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setTypeface(null, android.graphics.Typeface.BOLD) }
            val body = TextView(activity).apply { textSize = 13f; setTextColor(0xffe0e0e0.toInt()); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
            val actions = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            val actionScroll = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false; addView(actions) }
            root.addView(top); root.addView(title); root.addView(body); root.addView(actionScroll)
            return Card(root, label, title, body, close, icon, actions, actionScroll, expand)
        }
        override fun onViewRecycled(holder: Card) { holder.job?.cancel(); holder.key = null }
        override fun onBindViewHolder(holder: Card, position: Int) {
            // Recycled cards may carry an exit animation's offset or fade.
            holder.itemView.animate().cancel(); holder.itemView.translationX = 0f; holder.itemView.alpha = 1f
            holder.job?.cancel()
            val sbn = items[position]; val n = sbn.notification; val extras = n.extras
            holder.key = sbn.key; holder.icon.setImageDrawable(null)
            val time = DateFormat.getTimeFormat(activity).format(Date(sbn.postTime))
            holder.label.text = "${sbn.packageName.substringAfterLast('.')} · $time"
            holder.job = activity.lifecycleScope.launch {
                val (label, icon) = withContext(Dispatchers.IO) {
                    val label = runCatching { activity.packageManager.getApplicationLabel(activity.packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
                    label to app.anihome.launcher.data.IconCache.appIcon(activity.applicationContext, sbn.packageName, null)
                }
                if (holder.key == sbn.key) { holder.label.text = "$label · $time"; holder.icon.setImageBitmap(icon) }
            }
            holder.title.text = extras.getCharSequence(Notification.EXTRA_TITLE)?.take(200)?.toString().orEmpty()
            val messages = androidx.core.app.NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)?.messages
            holder.body.text = (if (!messages.isNullOrEmpty()) messages.takeLast(3).joinToString("\n") { "${it.person?.name ?: it.sender ?: ""}: ${it.text}" }
                else extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT)
                    ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n") ?: "").toString().take(4000)
            holder.title.visibility = if (holder.title.text.isEmpty()) View.GONE else View.VISIBLE
            holder.body.visibility = if (holder.body.text.isEmpty()) View.GONE else View.VISIBLE
            holder.close.visibility = if (sbn.isClearable) View.VISIBLE else View.GONE
            holder.close.setOnClickListener { dismissNotification(sbn.key, this) }
            holder.actions.removeAllViews()
            n.actions.orEmpty().filter { it.remoteInputs.isNullOrEmpty() && it.actionIntent != null }.take(3).forEach { action ->
                holder.actions.addView(ArtworkUi.textChip(activity, action.title.toString().take(32)) {
                    runCatching { action.actionIntent.send() }.onFailure { Toast.makeText(activity, "Action is no longer available", Toast.LENGTH_SHORT).show() }
                }, LinearLayout.LayoutParams(-2, dp(48)).apply { marginEnd = dp(6); topMargin = dp(6) })
            }
            fun updateExpanded() {
                val expanded = sbn.key in expandedKeys
                holder.body.maxLines = if (expanded) 20 else 2
                holder.actionScroll.visibility = if (expanded && holder.actions.childCount > 0) View.VISIBLE else View.GONE
                holder.expand.rotation = if (expanded) 180f else 0f
                holder.expand.contentDescription = if (expanded) "Collapse notification" else "Expand notification"
            }
            updateExpanded()
            holder.expand.setOnClickListener {
                MotionUi.expand(holder.itemView.parent as ViewGroup)
                if (!expandedKeys.add(sbn.key)) expandedKeys.remove(sbn.key)
                updateExpanded()
            }
            holder.itemView.setOnLongClickListener {
                val items = mutableListOf(
                    HomeMenu.Item(1, "Notification settings", R.drawable.ic_settings, inline = true, short = "Settings") {
                        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, sbn.packageName)
                            .putExtra(Settings.EXTRA_CHANNEL_ID, n.channelId)
                        runCatching { activity.startActivity(intent); dismiss() }
                            .onFailure { Toast.makeText(activity, "Notification settings unavailable", Toast.LENGTH_SHORT).show() }
                    })
                if (sbn.isClearable) items += HomeMenu.Item(2, "Snooze for 1 hour", R.drawable.ic_recents, inline = true, short = "Snooze 1h") {
                    if (!HomeNotifications.snooze(sbn.key)) Toast.makeText(activity, "This notification cannot be snoozed", Toast.LENGTH_SHORT).show()
                }
                if (sbn.isClearable) items += HomeMenu.Item(3, "Dismiss", R.drawable.ic_close, inline = true, short = "Dismiss") { dismissNotification(sbn.key, this) }
                HomeMenu.show(holder.itemView, holder.label.text.toString(), items); true
            }
            holder.itemView.setOnClickListener {
                val open = n.contentIntent
                if (open == null) {
                    MotionUi.expand(holder.itemView.parent as ViewGroup)
                    if (!expandedKeys.add(sbn.key)) expandedKeys.remove(sbn.key)
                    updateExpanded()
                } else open.let { intent ->
                    runCatching {
                        intent.send()
                        if (sbn.isClearable && n.flags and Notification.FLAG_AUTO_CANCEL != 0) HomeNotifications.dismiss(sbn.key)
                        dismiss()
                    }.onFailure { Toast.makeText(activity, "Notification is no longer available", Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }
    /** Single non-recycled header (Control Center + section title) scrolling with the cards. */
    private class Header(private val content: View) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = 1
        override fun getItemViewType(position: Int) = 0x0cc
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            (content.parent as? ViewGroup)?.removeView(content)
            // Full-span when the list is a two-column grid; a plain row otherwise.
            content.layoutParams = RecyclerView.LayoutParams(-1, -2)
            return object : RecyclerView.ViewHolder(content) {}.apply { setIsRecyclable(false) }
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {

        }
    }
    private class Card(root: View, val label: TextView, val title: TextView, val body: TextView, val close: ImageButton, val icon: ImageView, val actions: LinearLayout, val actionScroll: View, val expand: ImageButton) : RecyclerView.ViewHolder(root) {
        var job: Job? = null; var key: String? = null
    }
}
