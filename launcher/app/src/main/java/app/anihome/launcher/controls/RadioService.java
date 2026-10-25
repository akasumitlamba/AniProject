package app.anihome.launcher.controls;

import android.bluetooth.*;
import android.content.Context;
import android.net.wifi.*;

/** Shizuku-only service. Exposes radio and rotation operations, never caller-supplied commands or file access. */
@android.annotation.SuppressLint("MissingPermission")
@SuppressWarnings("deprecation")
public final class RadioService extends IRadioService.Stub {
    private final Context shell;
    private final WifiManager wifi;
    public RadioService(Context context) throws Exception {
        shell = context.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
        // ContextImpl otherwise inherits the host's "android" attribution. WifiService checks UID/package.
        java.lang.reflect.Field op = shell.getClass().getDeclaredField("mOpPackageName");
        op.setAccessible(true); op.set(shell, "com.android.shell");
        wifi = (WifiManager) shell.getSystemService(Context.WIFI_SERVICE);
    }
    public RadioService() throws Exception { this(systemContext()); }
    private static Context systemContext() throws Exception {
        Class<?> type = Class.forName("android.app.ActivityThread");
        Object thread = type.getMethod("currentActivityThread").invoke(null);
        if (thread == null) thread = type.getMethod("systemMain").invoke(null);
        return (Context) type.getMethod("getSystemContext").invoke(thread);
    }
    @Override public String applyRotation(int mode, int angle) {
        return "Forced display rotation has been removed. Use Android auto-rotate or lock the current orientation.";
    }
    @Override public boolean scanWifi() { return wifi.startScan(); }
    @Override public boolean setWifiPower(boolean enabled) { return wifi.setWifiEnabled(enabled); }
    @Override public String connectWifi(String ssid, String capabilities, String password) {
        if (!WifiConfigPolicy.validSsid(ssid) || capabilities == null) return "UNSUPPORTED";
        boolean psk = capabilities.contains("PSK"), sae = capabilities.contains("SAE") && !psk;
        boolean enterprise = capabilities.contains("EAP"), wep = capabilities.contains("WEP");
        int key = psk ? WifiConfiguration.KeyMgmt.WPA_PSK : sae ? WifiConfiguration.KeyMgmt.SAE :
            enterprise ? WifiConfiguration.KeyMgmt.WPA_EAP : capabilities.contains("OWE") ? WifiConfiguration.KeyMgmt.OWE : WifiConfiguration.KeyMgmt.NONE;
        int id = -1;
        WifiConfiguration existing = null;
        for (WifiConfiguration saved : wifi.getConfiguredNetworks()) {
            if (WifiConfigPolicy.quote(ssid).equals(saved.SSID) && saved.allowedKeyManagement.get(key)) { id = saved.networkId; existing = saved; break; }
        }
        if (id < 0 || password != null) {
            if (enterprise || wep) return "UNSUPPORTED";
            if ((psk || sae) && (password == null || password.isEmpty())) return "PASSWORD";
            if (psk && !WifiConfigPolicy.validPsk(password)) return "PASSWORD";
            WifiConfiguration config = existing == null ? new WifiConfiguration() : existing;
            config.SSID = WifiConfigPolicy.quote(ssid); config.allowedKeyManagement.set(key);
            if (psk || sae) config.preSharedKey = psk ? WifiConfigPolicy.encodePsk(password) : WifiConfigPolicy.quote(password);
            if (sae || key == WifiConfiguration.KeyMgmt.OWE) {
                try { WifiConfiguration.class.getField("requirePMF").setBoolean(config, true); }
                catch (Exception ignored) { return "UNSUPPORTED"; }
            }
            if (id >= 0) { config.networkId = id; id = wifi.updateNetwork(config); }
            else id = wifi.addNetwork(config);
            if (id < 0) return "FAILED";
        }
        if (!wifi.enableNetwork(id, true)) return "FAILED";
        wifi.reconnect();
        return "CONNECTING";
    }
    @Override public boolean setBluetoothPower(boolean enabled) {
        BluetoothAdapter adapter = ((BluetoothManager) shell.getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
        return adapter != null && (enabled ? adapter.enable() : adapter.disable());
    }
    @Override public boolean connectBluetooth(android.os.IBinder binder, int profile, String address) {
        if (binder == null || !BluetoothAdapter.checkBluetoothAddress(address)) return false;
        String type = profile == BluetoothProfile.A2DP ? "android.bluetooth.IBluetoothA2dp" :
            profile == BluetoothProfile.HEADSET ? "android.bluetooth.IBluetoothHeadset" : null;
        if (type == null) return false;
        try {
            // Accept only the declared system profile interface, never an arbitrary binder API.
            if (!type.equals(binder.getInterfaceDescriptor())) return false;
            Object proxy = Class.forName(type + "$Stub").getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
            BluetoothDevice device = ((BluetoothManager) shell.getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter().getRemoteDevice(address);
            for (java.lang.reflect.Method method : Class.forName(type).getMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (!method.getName().equals("connect") || types.length == 0 || types[0] != BluetoothDevice.class) continue;
                Object[] arguments = new Object[types.length]; arguments[0] = device;
                for (int i = 1; i < types.length; i++) {
                    if (!types[i].getName().equals("android.content.AttributionSource")) return false;
                    arguments[i] = Context.class.getMethod("getAttributionSource").invoke(shell);
                }
                return Boolean.TRUE.equals(method.invoke(proxy, arguments));
            }
        } catch (Exception failure) {
            Throwable cause = failure instanceof java.lang.reflect.InvocationTargetException ? failure.getCause() : failure;
            android.util.Log.w("AniHomeRadio", cause.getClass().getSimpleName() + " at " + cause.getStackTrace()[0]);
        }
        return false;
    }
    @Override public int bluetoothConnectTransaction(int profile) {
        String type = profile == BluetoothProfile.A2DP ? "android.bluetooth.IBluetoothA2dp" :
            profile == BluetoothProfile.HEADSET ? "android.bluetooth.IBluetoothHeadset" : null;
        if (type == null) return -1;
        try {
            for (java.lang.reflect.Method method : Class.forName(type).getMethods()) {
                Class<?>[] args = method.getParameterTypes();
                if (!method.getName().equals("connect") || method.getReturnType() != boolean.class || args.length == 0 || args[0] != BluetoothDevice.class) continue;
                if (args.length > 2 || (args.length == 2 && !args[1].getName().equals("android.content.AttributionSource"))) return -1;
                java.lang.reflect.Field transaction = Class.forName(type + "$Stub").getDeclaredField("TRANSACTION_connect");
                transaction.setAccessible(true);
                return transaction.getInt(null) | (args.length == 2 ? 0x10000 : 0);
            }
        } catch (Exception ignored) { /* Unsupported platform interface, no guessed transaction IDs. */ }
        return -1;
    }
    @Override public void destroy() { System.exit(0); }
}
