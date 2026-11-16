package app.anihome.launcher.notifications

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Live device notifications only: never persisted, logged, backed up or transmitted. */
class HomeNotifications : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private val current = linkedMapOf<String, StatusBarNotification>()
    /**
     * Notifications only their app can remove (USB, charging…) that the user cleared anyway.
     * They stay listed, quietly at the bottom, and no longer count toward badges. Only keys are
     * kept, and a key is forgotten as soon as its notification goes away.
     */
    private val quieted by lazy { getSharedPreferences("quiet_notifications", MODE_PRIVATE) }
    override fun onListenerConnected() {
        connected = this
        current.clear()
        runCatching { activeNotifications?.forEach { current[it.key] = it } }
        publish(); connectionState.value = true
    }
    override fun onNotificationPosted(sbn: StatusBarNotification) { current[sbn.key] = sbn; publish() }
    override fun onNotificationRemoved(sbn: StatusBarNotification) { current.remove(sbn.key); publish() }
    override fun onNotificationRankingUpdate(rankingMap: RankingMap) { publish() }
    override fun onListenerDisconnected() { clear() }
    override fun onDestroy() { clear(); super.onDestroy() }
    private fun clear() {
        handler.removeCallbacksAndMessages(null)
        current.clear()
        if (connected === this) { connected = null; state.value = emptyList(); badgeState.value = emptyList(); connectionState.value = false }
    }
    private fun publish() {
        val quiet = quieted.getStringSet("keys", emptySet()).orEmpty()
        val stale = quiet.filter { it !in current }
        if (stale.isNotEmpty()) quieted.edit().putStringSet("keys", quiet - stale.toSet()).apply()
        val live = quiet - stale.toSet()
        quietState.value = live
        // Quieted ones sink to the bottom; everything else newest first.
        val items = current.values.sortedWith(compareBy<StatusBarNotification> { it.key in live }.thenByDescending { it.postTime })
        state.value = items
        val ranks = currentRanking
        badgeState.value = items.filter { item ->
            val ranking = Ranking()
            item.key !in live && (!ranks.getRanking(item.key, ranking) || ranking.canShowBadge())
        }
    }
    private fun quiet(keys: Set<String>) {
        val targets = keys.filter { key -> current[key]?.isClearable == false }.toSet()
        if (targets.isEmpty()) return
        quieted.edit().putStringSet("keys", quieted.getStringSet("keys", emptySet()).orEmpty() + targets).apply()
        publish()
    }
    companion object {
        @Volatile private var connected: HomeNotifications? = null
        private val connectionState = MutableStateFlow(false)
        val connection = connectionState.asStateFlow()
        private val state = MutableStateFlow<List<StatusBarNotification>>(emptyList())
        val notifications = state.asStateFlow()
        private val badgeState = MutableStateFlow<List<StatusBarNotification>>(emptyList())
        val badges = badgeState.asStateFlow()
        private val quietState = MutableStateFlow<Set<String>>(emptySet())
        fun isQuiet(key: String) = key in quietState.value
        /** Marks notifications Android won't dismiss as seen: kept, but quiet and unbadged. */
        fun quiet(keys: Set<String>) { connected?.quiet(keys) }
        fun enabled(context: Context) = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
        fun isConnected() = connected != null
        fun reconnect(context: Context) { requestRebind(ComponentName(context, HomeNotifications::class.java)) }
        fun dismiss(key: String, result: (Boolean) -> Unit = {}) {
            val service = connected ?: return result(false)
            service.cancel(setOf(key), result)
        }
        fun snooze(key: String): Boolean {
            val service = connected ?: return false
            return runCatching { service.snoozeNotification(key, 60 * 60 * 1000L); true }.getOrDefault(false)
        }
        /** Notification listeners may request Do Not Disturb without separate policy access. */
        fun requestDoNotDisturb(enabled: Boolean): Boolean {
            val service = connected ?: return false
            return runCatching {
                service.requestInterruptionFilter(if (enabled) INTERRUPTION_FILTER_PRIORITY else INTERRUPTION_FILTER_ALL); true
            }.getOrDefault(false)
        }
        fun clearAll(result: (Boolean) -> Unit = {}) {
            val service = connected ?: return result(false)
            service.cancel(service.current.keys.toSet(), result)
        }
    }
    private fun cancel(requested: Set<String>, result: (Boolean) -> Unit) {
        val keys = NotificationPolicy.dismissalTargets(current.values.map {
            NotificationPolicy.Entry(it.key, it.groupKey, it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0, it.isClearable)
        }, requested)
        val postedAt = keys.associateWith { current[it]?.postTime }
        if (keys.isEmpty()) { result(requested.none { it in current }); return }
        if (runCatching { cancelNotifications(keys.toTypedArray()) }.isFailure) { result(false); return }
        // Listener callbacks can be delayed or missed after a rebind. Reconcile with Android,
        // rather than permanently hiding a card that is still active in the system.
        handler.postDelayed({
            if (connected !== this) { result(false); return@postDelayed }
            val active = runCatching { activeNotifications?.toList() ?: emptyList() }.getOrNull()
            if (active == null) { result(false); return@postDelayed }
            current.clear()
            active.forEach { current[it.key] = it }
            publish()
            result(keys.none { current[it]?.postTime == postedAt[it] })
        }, 750)
    }
}
