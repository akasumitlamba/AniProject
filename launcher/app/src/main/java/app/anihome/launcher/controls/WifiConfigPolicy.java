package app.anihome.launcher.controls;

import java.nio.charset.StandardCharsets;

/** Validate credentials before handing them to Android; no values are logged or retained. */
public final class WifiConfigPolicy {
    private WifiConfigPolicy() { }
    public static boolean validSsid(String ssid) {
        return ssid != null && !ssid.isEmpty() && ssid.getBytes(StandardCharsets.UTF_8).length <= 32;
    }
    public static boolean hexPsk(String password) {
        return password != null && password.matches("[0-9a-fA-F]{64}");
    }
    public static boolean validPsk(String password) {
        return password != null && ((password.length() >= 8 && password.length() <= 63) || hexPsk(password));
    }
    public static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
    public static String encodePsk(String password) { return hexPsk(password) ? password : quote(password); }
}
