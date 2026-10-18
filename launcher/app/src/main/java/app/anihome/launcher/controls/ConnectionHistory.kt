package app.anihome.launcher.controls

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Protected framework broadcast: remember successful connections even when Home is not visible. */
class ConnectionHistory : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        @Suppress("DEPRECATION") val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
        context.getSharedPreferences("quick_connections", Context.MODE_PRIVATE).edit()
            .putLong("bt:${device.address}", System.currentTimeMillis()).apply()
    }
}
