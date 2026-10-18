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
    fun showSettings() { show(); quickSettings.showSettings() }
    fun show() {
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
        heading.addView(ArtworkUi.title(activity, "Notifications").apply { textSize = 16f }, LinearLayout.LayoutParams(0, dp(40), 1f))
        val clear = ArtworkUi.plainIcon(activity, R.drawable.ic_clear_all, "Clear all notifications") { HomeNotifications.clearAll() }
        compactIcon(clear, 40)
        heading.addView(clear)
        heading.addView(ArtworkUi.plainIcon(activity, R.drawable.ic_close, "Close notifications") { dismiss() }.also { compactIcon(it, 40) })
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
        val list = RecyclerView(activity).apply { layoutManager = LinearLayoutManager(activity); this.adapter = adapter; itemAnimator = null }
        val notifications = FrameLayout(activity).apply {
            addView(list, FrameLayout.LayoutParams(-1, -1))
            addView(emptyScroll, FrameLayout.LayoutParams(-1, -1))
        }
        root.addView(notifications, LinearLayout.LayoutParams(-1, 0, 1f))
        val settings = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, 0) }
        root.addView(settings)
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
        sheet.setOnDismissListener { quickSettings.detach(); collector?.cancel(); collector = null; adapter.items = emptyList(); expandedKeys.clear(); dialog = null }
        sheet.window?.let { SidePanelLayout.apply(activity, it) }
        sheet.show()
        sheet.window?.let { SidePanelLayout.apply(activity, it) }
        androidx.core.view.ViewCompat.requestApplyInsets(root)
        if (HomeNotifications.enabled(activity) && !HomeNotifications.isConnected()) runCatching { HomeNotifications.reconnect(activity) }
        collector = activity.lifecycleScope.launch {
            combine(HomeNotifications.notifications, HomeNotifications.connection) { items, connected -> items to connected }.collect { (notifications, connected) ->
                val visible = NotificationPolicy.visible(notifications.map {
                    NotificationPolicy.Entry(it.key, it.groupKey, it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0, it.isClearable)
                })
                adapter.items = notifications.filter { it.key in visible }; adapter.notifyDataSetChanged()
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
    private fun compactIcon(icon: ImageButton, size: Int) {
        icon.layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        val padding = (size - 20) / 2
        icon.setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }
    private inner class Cards : RecyclerView.Adapter<Card>() {
        var items = emptyList<StatusBarNotification>()
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
            holder.close.setOnClickListener { HomeNotifications.dismiss(sbn.key) }
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
                if (!expandedKeys.add(sbn.key)) expandedKeys.remove(sbn.key)
                updateExpanded()
            }
            holder.itemView.setOnClickListener {
                n.contentIntent?.let { intent ->
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
