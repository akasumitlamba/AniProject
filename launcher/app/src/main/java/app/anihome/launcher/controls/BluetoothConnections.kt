package app.anihome.launcher.controls

import android.bluetooth.BluetoothProfile
import android.content.*
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import android.os.Build
import android.bluetooth.BluetoothManager
import kotlinx.coroutines.*

/** Android requires profile-service binding to originate from an actual application process.
 * AniHome binds normally; only the narrow connect call is executed with Shizuku privileges. */
@android.annotation.SuppressLint("MissingPermission")
internal object BluetoothConnections {
    suspend fun connect(context: Context, service: IRadioService, address: String): Boolean = coroutineScope {
        listOf(BluetoothProfile.A2DP to "android.bluetooth.IBluetoothA2dp",
            BluetoothProfile.HEADSET to "android.bluetooth.IBluetoothHeadset").map { (profile, action) ->
            async {
                val intent = Intent(action)
                val info = context.packageManager.resolveService(intent, PackageManager.MATCH_SYSTEM_ONLY)?.serviceInfo ?: return@async false
                intent.component = ComponentName(info.packageName, info.name)
                val binder = CompletableDeferred<IBinder?>()
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, value: IBinder) { binder.complete(value) }
                    override fun onServiceDisconnected(name: ComponentName) { binder.complete(null) }
                    override fun onNullBinding(name: ComponentName) { binder.complete(null) }
                    override fun onBindingDied(name: ComponentName) { binder.complete(null) }
                }
                var bound = false
                try {
                    bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                    if (!bound) return@async false
                    val target = withTimeoutOrNull(6000) { binder.await() } ?: return@async false
                    withContext(Dispatchers.IO) {
                        // Android 10 checks BLUETOOTH_ADMIN, held by AniHome but not every OEM's
                        // shell package. Invoke as AniHome first; use shell only where necessary.
                        val operation = service.bluetoothConnectTransaction(profile)
                        val accepted = if (operation < 0) false else runCatching {
                            val request = Parcel.obtain(); val reply = Parcel.obtain()
                            try {
                                request.writeInterfaceToken(action)
                                request.writeInt(1)
                                context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address).writeToParcel(request, 0)
                                if (operation and 0x10000 != 0) {
                                    if (Build.VERSION.SDK_INT < 31) return@runCatching false
                                    request.writeInt(1); context.attributionSource.writeToParcel(request, 0)
                                }
                                if (!target.transact(operation and 0xffff, request, reply, 0)) return@runCatching false
                                reply.readException(); reply.readInt() != 0
                            } finally { reply.recycle(); request.recycle() }
                        }.getOrDefault(false)
                        accepted || service.connectBluetooth(target, profile, address)
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    android.util.Log.w("AniHomeRadio", "${failure.javaClass.simpleName} at ${failure.stackTrace.firstOrNull()}")
                    false
                } finally { if (bound) runCatching { context.unbindService(connection) } }
            }
        }.awaitAll().any { it }
    }
}
