package app.anihome.launcher.controls

import android.content.*
import android.content.pm.PackageManager
import android.os.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku

/** The caller must opt in to Shizuku; missing/revoked privileges never masquerade as success. */
internal class RadioBridge(private val activity: AppCompatActivity, private val changed: () -> Unit) : DefaultLifecycleObserver {
    var service: IRadioService? = null
        private set
    val ready get() = service?.asBinder()?.isBinderAlive == true
    private var binding = false
    private val handler = Handler(Looper.getMainLooper())
    private val bindingTimeout: Runnable = Runnable {
        if (binding) {
            runCatching { Shizuku.unbindUserService(args, connection, true) }
            binding = false; managerWarmed = false; changed(); retryConnection()
        }
    }
    private var warming = false
    private var managerWarmed = false
    private val finishWarm = Runnable {
        activity.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { ShizukuBroker.refresh() }.onFailure {
                    android.util.Log.w("AniHomeRadio", "Broker recovery failed", it)
                }
            }
            warming = false; bind()
        }
    }
    private var destroyed = false
    private var retryCount = 0
    private val retry = Runnable { bind() }
    private fun retryConnection() {
        if (!destroyed && retryCount++ < 2) handler.postDelayed(retry, 1500)
    }
    fun reconnect() {
        if (ready || binding || warming || destroyed) return
        retryCount = 0
        managerWarmed = false
        handler.removeCallbacks(retry)
        bind()
    }
    val connecting get() = binding || warming
    private val args = Shizuku.UserServiceArgs(ComponentName(activity, RadioService::class.java))
        .daemon(false).processNameSuffix("radios").tag("anihome-radios").version(21)
    private val connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            handler.removeCallbacks(bindingTimeout)
            service = IRadioService.Stub.asInterface(binder); binding = false; retryCount = 0; changed()
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null; binding = false; changed(); retryConnection() }
    }
    private val received = Shizuku.OnBinderReceivedListener { bind() }
    private val dead = Shizuku.OnBinderDeadListener { service = null; binding = false; changed() }
    private val permission = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) bind() else changed()
    }
    init {
        activity.lifecycle.addObserver(this)
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(permission)
    }
    private fun bind() {
        if (ready || binding || warming || destroyed) return
        runCatching {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                if (!managerWarmed) {
                    managerWarmed = true; warming = true
                    // Its exported binder-request receiver wakes the broker in the background.
                    // The server must already be running and permission already granted.
                    activity.sendBroadcast(Intent("rikka.shizuku.intent.action.REQUEST_BINDER")
                        .setPackage("moe.shizuku.privileged.api")
                        .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES))
                    handler.postDelayed(finishWarm, 1000)
                    return
                }
                binding = true; Shizuku.bindUserService(args, connection)
                handler.postDelayed(bindingTimeout, 10000)
            }
        }.onFailure { warming = false; binding = false; changed(); retryConnection() }
    }
    fun authorize(): Boolean {
        return runCatching {
            if (!Shizuku.pingBinder()) return@runCatching false
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                reconnect()
            } else Shizuku.requestPermission(420)
            true
        }.getOrDefault(false)
    }
    override fun onDestroy(owner: LifecycleOwner) {
        destroyed = true
        handler.removeCallbacks(retry)
        handler.removeCallbacks(finishWarm)
        handler.removeCallbacks(bindingTimeout)
        Shizuku.removeBinderReceivedListener(received); Shizuku.removeBinderDeadListener(dead)
        Shizuku.removeRequestPermissionResultListener(permission)
        runCatching { Shizuku.unbindUserService(args, connection, true) }
        service = null
    }
}
