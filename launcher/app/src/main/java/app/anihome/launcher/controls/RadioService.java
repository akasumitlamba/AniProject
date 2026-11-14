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
    /**
     * Shell-level system toggles. Only these fixed identifiers are accepted and each maps to a
     * constant command; no caller text reaches a shell. Returns "" on success or a short reason.
     */
    @Override public String setSystemToggle(String id, boolean enabled) {
        try {
            switch (id) {
                case "dark":
                    return run("cmd", "uimode", "night", enabled ? "yes" : "no");
                case "battery_saver":
                    if (run("cmd", "power", "set-mode", enabled ? "1" : "0").isEmpty()) return "";
                    return run("settings", "put", "global", "low_power", enabled ? "1" : "0");
                case "location":
                    if (android.os.Build.VERSION.SDK_INT >= 30) return run("cmd", "location", "set-location-enabled", enabled ? "true" : "false");
                    return run("settings", "put", "secure", "location_mode", enabled ? "3" : "0");
                case "airplane": {
                    Object manager = shell.getSystemService(Context.CONNECTIVITY_SERVICE);
                    manager.getClass().getMethod("setAirplaneMode", boolean.class).invoke(manager, enabled);
                    return "";
                }
                case "night_light":
                    return run("settings", "put", "secure", "night_display_activated", enabled ? "1" : "0");
                case "invert":
                    return run("settings", "put", "secure", "accessibility_display_inversion_enabled", enabled ? "1" : "0");
                case "mobile_data":
                    return run("svc", "data", enabled ? "enable" : "disable");
                case "wifi_power":
                    return wifi.setWifiEnabled(enabled) ? "" : "Wi-Fi could not be changed";
                case "bt_power":
                    return setBluetoothPower(enabled) ? "" : "Bluetooth could not be changed";
                case "grant_secure_settings":
                    // One-time grant so AniHome can hide/restore the navigation bar for its gestures.
                    return run("pm", "grant", "app.anihome.launcher", "android.permission.WRITE_SECURE_SETTINGS");
                case "bt_max_devices":
                    // Developer option "Maximum connected Bluetooth audio devices". Playback still
                    // goes to one active device unless the phone supports dual audio.
                    return run("setprop", "persist.bluetooth.maxconnectedaudiodevices", enabled ? "2" : "1");
                default:
                    if (id.startsWith("wifi_autojoin:")) return wifiAutojoin(id.substring("wifi_autojoin:".length()), enabled);
                    return "Unsupported control";
            }
        } catch (Exception failure) {
            Throwable cause = failure instanceof java.lang.reflect.InvocationTargetException ? failure.getCause() : failure;
            android.util.Log.w("AniHomeRadio", "Toggle " + id + ": " + cause.getClass().getSimpleName());
            return "Android refused this change";
        }
    }
    /** Android 11+: per-network "Auto-connect" (allowAutojoin). Android 10 has no such switch. */
    private String wifiAutojoin(String ssid, boolean enabled) throws Exception {
        if (android.os.Build.VERSION.SDK_INT < 30) return "Android 10 doesn't support per-network auto-connect";
        for (WifiConfiguration config : wifi.getConfiguredNetworks()) {
            if (config.SSID != null && config.SSID.replace("\"", "").equals(ssid)) {
                WifiManager.class.getMethod("allowAutojoin", int.class, boolean.class).invoke(wifi, config.networkId, enabled);
                return "";
            }
        }
        return "Network not saved on this device";
    }
    private static String run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        StringBuilder output = new StringBuilder();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
            String line; while ((line = reader.readLine()) != null && output.length() < 400) output.append(line).append('\n');
        }
        if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) { process.destroy(); return "Timed out"; }
        String text = output.toString().trim();
        boolean failed = process.exitValue() != 0 || text.startsWith("Unknown") || text.contains("Exception") || text.contains("Error");
        return failed ? (text.isEmpty() ? "Command failed" : text.split("\n")[0]) : "";
    }
    /**
     * Apps playing sound right now. Where Android hands shell the full playback configs, each
     * app gets a volume slider. Where it anonymises them (Android 10: shell lacks
     * MODIFY_AUDIO_ROUTING), the owners come from the audio service's own report and the app
     * can be muted instead.
     */
    @Override public String activePlayers() {
        org.json.JSONArray out = new org.json.JSONArray();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        try {
            android.media.AudioManager audio = (android.media.AudioManager) shell.getSystemService(Context.AUDIO_SERVICE);
            java.lang.reflect.Method owner = android.media.AudioPlaybackConfiguration.class.getMethod("getClientUid");
            java.lang.reflect.Method proxy = android.media.AudioPlaybackConfiguration.class.getMethod("getPlayerProxy");
            for (android.media.AudioPlaybackConfiguration config : audio.getActivePlaybackConfigurations()) {
                int uid = (int) owner.invoke(config);
                if (uid <= 0 || !seen.add(uid)) continue;
                addPlayer(out, uid, proxy.invoke(config) != null);
            }
        } catch (Exception failure) {
            android.util.Log.w("AniHomeRadio", "Players: " + failure.getClass().getSimpleName());
        }
        if (out.length() == 0) {
            // "ID:15 -- type:… -- u/pid:10133/4567 -- state:started -- attr:…"
            java.util.regex.Matcher match = java.util.regex.Pattern.compile("u/pid:(\\d+)/\\d+ -- state:started").matcher(report("dumpsys", "audio"));
            while (match.find()) {
                int uid = Integer.parseInt(match.group(1));
                if (uid >= 10000 && seen.add(uid)) addPlayer(out, uid, false);
            }
        }
        return out.toString();
    }
    private void addPlayer(org.json.JSONArray out, int uid, boolean volume) {
        try {
            String[] packages = shell.getPackageManager().getPackagesForUid(uid);
            if (packages == null || packages.length == 0) return;
            out.put(new org.json.JSONObject().put("uid", uid).put("package", packages[0])
                .put("volume", volume).put("muted", report("appops", "get", packages[0], "PLAY_AUDIO").contains("ignore")));
        } catch (Exception ignored) { /* skip this player */ }
    }
    @Override public boolean setAppMuted(String packageName, boolean muted) {
        try {
            if (shell.getPackageManager().getPackageUid(packageName, 0) < 10000) return false; // apps only
            return run("appops", "set", packageName, "PLAY_AUDIO", muted ? "ignore" : "allow").isEmpty();
        } catch (Exception failure) {
            return false;
        }
    }
    /** Full output of a fixed diagnostic command (bounded), for parsing. */
    private static String report(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            StringBuilder output = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                String line; while ((line = reader.readLine()) != null && output.length() < 512_000) output.append(line).append('\n');
            }
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            return output.toString();
        } catch (Exception failure) {
            return "";
        }
    }
    @Override public boolean setAppVolume(int uid, float volume) {
        boolean applied = false;
        try {
            android.media.AudioManager audio = (android.media.AudioManager) shell.getSystemService(Context.AUDIO_SERVICE);
            java.lang.reflect.Method owner = android.media.AudioPlaybackConfiguration.class.getMethod("getClientUid");
            java.lang.reflect.Method proxy = android.media.AudioPlaybackConfiguration.class.getMethod("getPlayerProxy");
            for (android.media.AudioPlaybackConfiguration config : audio.getActivePlaybackConfigurations()) {
                if ((int) owner.invoke(config) != uid) continue;
                Object player = proxy.invoke(config);
                if (player == null) continue;
                player.getClass().getMethod("setVolume", float.class).invoke(player, Math.max(0f, Math.min(1f, volume)));
                applied = true;
            }
        } catch (Exception failure) {
            Throwable cause = failure instanceof java.lang.reflect.InvocationTargetException ? failure.getCause() : failure;
            android.util.Log.w("AniHomeRadio", "App volume: " + cause.getClass().getSimpleName() + " " + cause.getMessage());
        }
        return applied;
    }
    @Override public void destroy() { System.exit(0); }
}
