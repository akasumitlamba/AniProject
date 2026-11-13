package app.anihome.launcher.controls

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import app.anihome.launcher.R
import org.lsposed.hiddenapibypass.HiddenApiBypass

/** Device-type icons and per-device actions (disconnect, auto-connect, forget). */
@android.annotation.SuppressLint("MissingPermission")
internal object BluetoothDevices {
    fun icon(device: BluetoothDevice): Int {
        val cls = runCatching { device.bluetoothClass }.getOrNull() ?: return R.drawable.ic_bluetooth
        val name = (runCatching { device.name }.getOrNull() ?: "").lowercase()
        return when (cls.majorDeviceClass) {
            BluetoothClass.Device.Major.AUDIO_VIDEO -> when (cls.deviceClass) {
                BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES, BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET -> R.drawable.ic_bt_headphones
                BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE ->
                    if (listOf("bud", "pod", "tws", "ear").any { it in name }) R.drawable.ic_bt_earbuds else R.drawable.ic_bt_headphones
                BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER, BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO,
                BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO -> R.drawable.ic_bt_speaker
                BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> R.drawable.ic_bt_car
                BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER, BluetoothClass.Device.AUDIO_VIDEO_VIDEO_MONITOR -> R.drawable.ic_tv
                else -> if (listOf("bud", "pod", "tws").any { it in name }) R.drawable.ic_bt_earbuds else R.drawable.ic_bt_headphones
            }
            BluetoothClass.Device.Major.COMPUTER -> R.drawable.ic_bt_laptop
            BluetoothClass.Device.Major.PHONE -> R.drawable.ic_bt_phone
            BluetoothClass.Device.Major.WEARABLE -> R.drawable.ic_bt_watch
            BluetoothClass.Device.Major.PERIPHERAL -> when {
                cls.deviceClass and 0x40 != 0 -> R.drawable.ic_bt_keyboard
                cls.deviceClass and 0x80 != 0 -> R.drawable.ic_bt_mouse
                else -> R.drawable.ic_bt_gamepad
            }
            BluetoothClass.Device.Major.TOY -> R.drawable.ic_bt_gamepad
            else -> R.drawable.ic_bluetooth
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("quick_connections", Context.MODE_PRIVATE)
    fun autoConnect(context: Context, device: BluetoothDevice) = prefs(context).getBoolean("auto:${device.address}", true)

    /** Invoke a hidden profile method by name; profile proxies carry Android's own permission checks. */
    private fun call(proxy: Any, name: String, vararg args: Any): Boolean = runCatching {
        // Hidden-API restrictions start at Android 9; older versions reflect directly.
        val methods = if (android.os.Build.VERSION.SDK_INT >= 28) HiddenApiBypass.getDeclaredMethods(proxy.javaClass).filterIsInstance<java.lang.reflect.Method>()
            else proxy.javaClass.declaredMethods.toList()
        val method = methods.first { it.name == name && it.parameterTypes.size == args.size }
        method.isAccessible = true
        (method.invoke(proxy, *args) as? Boolean) ?: true
    }.getOrDefault(false)

    fun disconnect(profiles: Map<Int, BluetoothProfile>, device: BluetoothDevice): Boolean =
        profiles.values.map { call(it, "disconnect", device) }.any { it }

    /**
     * Auto-connect off: Android stops reconnecting this device on its own (connection policy
     * "forbidden"); it can still be connected by tapping it. On: restores the default.
     */
    fun setAutoConnect(context: Context, profiles: Map<Int, BluetoothProfile>, device: BluetoothDevice, enabled: Boolean): Boolean {
        val ok = profiles.values.map { proxy ->
            if (android.os.Build.VERSION.SDK_INT >= 30) call(proxy, "setConnectionPolicy", device, if (enabled) 100 else 0)
            else call(proxy, "setPriority", device, if (enabled) 100 else 0)
        }.any { it }
        if (ok) prefs(context).edit().putBoolean("auto:${device.address}", enabled).apply()
        return ok
    }

    fun forget(device: BluetoothDevice): Boolean = call(device, "removeBond")
}
