package app.anihome.launcher.notifications

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Live device notifications only: never persisted, logged, backed up or transmitted. */
class HomeNotifications : NotificationListenerService() {
    private val current = linkedMapOf<String, StatusBarNotification>()
    override fun onListenerConnected() {
        connected = this
        current.clear()
        runCatching { activeNotifications?.forEach { current[it.key] = it } }
        publish(); connectionState.value = true
    }
    override fun onNotificationPosted(sbn: StatusBarNotification) { current[sbn.key] = sbn; publish() }
    override fun onNotificationRemoved(sbn: StatusBarNotification) { current.remove(sbn.key); publish() }
    override fun onListenerDisconnected() { clear() }
    override fun onDestroy() { clear(); super.onDestroy() }
    private fun clear() {
        current.clear()
        if (connected === this) { connected = null; state.value = emptyList(); connectionState.value = false }
    }
    private fun publish() { state.value = current.values.sortedByDescending { it.postTime } }
    companion object {
        @Volatile private var connected: HomeNotifications? = null
        private val connectionState = MutableStateFlow(false)
        val connection = connectionState.asStateFlow()
        private val state = MutableStateFlow<List<StatusBarNotification>>(emptyList())
        val notifications = state.asStateFlow()
        fun enabled(context: Context) = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
        fun isConnected() = connected != null
        fun reconnect(context: Context) { requestRebind(ComponentName(context, HomeNotifications::class.java)) }
        fun dismiss(key: String) { connected?.let { service ->
            if (service.current[key]?.isClearable == true) runCatching { service.cancelNotification(key) }
        } }
        fun clearAll() { connected?.let { service ->
            val keys = NotificationPolicy.dismissible(service.current.values.map {
                NotificationPolicy.Entry(it.key, it.groupKey, false, it.isClearable)
            })
            if (keys.isNotEmpty()) runCatching { service.cancelNotifications(keys.toTypedArray()) }
        } }
    }
}
