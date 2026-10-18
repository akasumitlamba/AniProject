package app.anihome.launcher.controls

import android.content.*
import android.content.pm.PackageManager
import android.os.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
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
            binding = false; changed()
        }
    }
    private fun managerStopped() = runCatching {
        activity.packageManager.getApplicationInfo("moe.shizuku.privileged.api", 0).flags and android.content.pm.ApplicationInfo.FLAG_STOPPED != 0
    }.getOrDefault(false)
    fun reconnect() {
        if (!ready && binding) {
            handler.removeCallbacks(bindingTimeout)
            runCatching { Shizuku.unbindUserService(args, connection, true) }
            binding = false
        }
        bind()
    }
    val connecting get() = binding
    private val args = Shizuku.UserServiceArgs(ComponentName(activity, RadioService::class.java))
        .daemon(false).processNameSuffix("radios").tag("anihome-radios").version(18)
    private val connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            handler.removeCallbacks(bindingTimeout)
            service = IRadioService.Stub.asInterface(binder); binding = false; changed()
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null; binding = false; changed() }
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
        if (ready || binding || managerStopped()) return
        runCatching {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                binding = true; Shizuku.bindUserService(args, connection)
                handler.postDelayed(bindingTimeout, 10000)
            }
        }.onFailure { binding = false; changed() }
    }
    fun authorize(): Boolean {
        return runCatching {
            if (!Shizuku.pingBinder() || managerStopped()) return@runCatching false
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                // A live server can outlast the manager provider on OEM firmware. Bring the
                // manager forward on explicit recovery, then rebind when the sheet reattaches.
                if (!ready) return@runCatching false
            } else Shizuku.requestPermission(420)
            true
        }.getOrDefault(false)
    }
    override fun onDestroy(owner: LifecycleOwner) {
        handler.removeCallbacks(bindingTimeout)
        Shizuku.removeBinderReceivedListener(received); Shizuku.removeBinderDeadListener(dead)
        Shizuku.removeRequestPermissionResultListener(permission)
        runCatching { Shizuku.unbindUserService(args, connection, true) }
        service = null
    }
}
