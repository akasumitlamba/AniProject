package app.anihome.launcher.ui

import android.app.Notification
import android.content.Intent
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.text.format.DateFormat
import android.view.Gravity
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
    private val expandedKeys = mutableSetOf<String>()
    val isShowing: Boolean get() = dialog?.isShowing == true
    fun dismiss() { dialog?.dismiss() }
    fun resume() { if (quickSettings.consumeReturn()) show() }
    fun onConfigurationChanged() {
        dialog?.window?.let { quickSettings.resize(SidePanelLayout.apply(activity, it).height) }
    }
    private fun dp(n: Int) = ArtworkUi.dp(activity, n)
    fun setAutomaticRotation(automatic: Boolean) = quickSettings.setAutomaticRotation(automatic)
    fun showSettings() { show(); quickSettings.showSettings() }
    fun show(direction: String = "RIGHT") {
        if (dialog?.isShowing == true) return
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(2), dp(10), dp(4)); setBackgroundColor(0xff141414.toInt())
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            val navigation = androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)?.getInsetsIgnoringVisibility(androidx.core.view.WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
            view.setPadding(dp(10) + safe.left, dp(2) + safe.top, dp(10) + safe.right, dp(4) + maxOf(safe.bottom, navigation))
            insets
        }
        val heading = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        val headingTitle = ArtworkUi.title(activity, "Notifications").apply { textSize = 16f }
        heading.addView(headingTitle, LinearLayout.LayoutParams(0, dp(40), 1f))
        val clear = ArtworkUi.plainIcon(activity, R.drawable.ic_clear_all, "Clear all notifications") { }
        compactIcon(clear, 40)
        heading.addView(clear)
        root.addView(heading)
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
        val emptyScroll = ScrollView(activity).apply { addView(empty) }
        val adapter = Cards()
        clear.setOnClickListener {
            expandedKeys.clear()
            adapter.notifyItemRangeChanged(0, adapter.itemCount)
            HomeNotifications.clearAll { success ->
                if (!success && isShowing) Toast.makeText(activity, "Some notifications could not be dismissed", Toast.LENGTH_SHORT).show()
            }
        }
        val list = RecyclerView(activity).apply { layoutManager = LinearLayoutManager(activity); this.adapter = adapter; itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply {
            addDuration = 140; removeDuration = 160; moveDuration = 160; changeDuration = 0; supportsChangeAnimations = false
        } }
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(view: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder) = false
            override fun getSwipeDirs(view: RecyclerView, holder: RecyclerView.ViewHolder): Int {
                val item = adapter.items.getOrNull(holder.bindingAdapterPosition)
                return if (item?.isClearable == true) super.getSwipeDirs(view, holder) else 0
            }
            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) {
                (holder as? Card)?.key?.let { dismissNotification(it, adapter) }
            }
        }).attachToRecyclerView(list)
        val notifications = FrameLayout(activity).apply {
            addView(list, FrameLayout.LayoutParams(-1, -1))
            addView(emptyScroll, FrameLayout.LayoutParams(-1, -1))
        }
        root.addView(notifications, LinearLayout.LayoutParams(-1, 0, 1f))
        val settings = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, 0) }
        root.addView(settings)
        quickSettings.onSectionChanged = { section ->
            val inSettings = section == "Settings"
            headingTitle.text = if (inSettings) "Home settings" else "Notifications"
            notifications.visibility = if (inSettings) View.GONE else View.VISIBLE
            clear.visibility = if (inSettings) View.GONE else View.VISIBLE
            settings.layoutParams = LinearLayout.LayoutParams(-1, if (inSettings) 0 else -2, if (inSettings) 1f else 0f)
        }
        val sheetHeight = SidePanelLayout.bounds(activity).height
        quickSettings.attach(settings, sheetHeight)
        root.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) quickSettings.resize(bottom - top)
        }
        val sheet = object : AppCompatDialog(activity, R.style.Theme_AniHome_Sidebar) {
            override fun onWindowFocusChanged(hasFocus: Boolean) {
                super.onWindowFocusChanged(hasFocus)
                if (hasFocus) window?.let(HomeFullscreen::apply)
            }
        }.apply { setContentView(root) }
        dialog = sheet
        sheet.setCancelable(true)
        sheet.setCanceledOnTouchOutside(true)
        sheet.setOnDismissListener { quickSettings.onSectionChanged = null; quickSettings.detach(); collector?.cancel(); collector = null; adapter.items = emptyList(); expandedKeys.clear(); dialog = null }
        sheet.window?.let { SidePanelLayout.apply(activity, it); it.setWindowAnimations(0) }
        sheet.show()
        sheet.window?.let { SidePanelLayout.apply(activity, it); it.setWindowAnimations(0) }
        MotionUi.enter(root, direction)
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
                emptyScroll.visibility = if (state.text.isEmpty()) View.GONE else View.VISIBLE
                state.visibility = if (state.text.isEmpty()) View.GONE else View.VISIBLE
                access.visibility = if (enabled) View.GONE else View.VISIBLE
                clear.isEnabled = enabled && notifications.any { it.isClearable }
                clear.alpha = if (clear.isEnabled) 1f else 0.4f
            }
        }
    }
    private fun dismissNotification(key: String, adapter: Cards) {
        expandedKeys.remove(key)
        // Reset expansion and any swipe translation even if Android refuses cancellation.
        adapter.notifyDataSetChanged()
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
                background = android.graphics.drawable.GradientDrawable().apply { setColor(0xff1c1c1c.toInt()); cornerRadius = dp(12).toFloat() }
                foreground = androidx.core.content.ContextCompat.getDrawable(activity, R.drawable.bg_drawer_item)
                clipToOutline = true; isFocusable = true; isClickable = true
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
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
                val menu = PopupMenu(activity, holder.itemView)
                menu.menu.add("Notification settings").setOnMenuItemClickListener {
                    val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, sbn.packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, n.channelId)
                    runCatching { activity.startActivity(intent); dismiss() }
                        .onFailure { Toast.makeText(activity, "Notification settings unavailable", Toast.LENGTH_SHORT).show() }
                    true
                }
                menu.menu.add("Snooze for 1 hour").setOnMenuItemClickListener {
                    if (!HomeNotifications.snooze(sbn.key)) Toast.makeText(activity, "This notification cannot be snoozed", Toast.LENGTH_SHORT).show()
                    true
                }
                menu.show(); true
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
    private class Card(root: View, val label: TextView, val title: TextView, val body: TextView, val close: ImageButton, val icon: ImageView, val actions: LinearLayout, val actionScroll: View, val expand: ImageButton) : RecyclerView.ViewHolder(root) {
        var job: Job? = null; var key: String? = null
    }
}
