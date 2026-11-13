package app.anibrave;

/** Derives user-agent identity from the running engine so overrides never lag an upgrade. */
final class UserAgents {
    /** "Chrome/157.0.8103.79" (DevTools Browser field) -> "157"; otherwise the fallback. */
    static String chromeMajor(String browser, String fallback) {
        if (browser == null) return fallback;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:Chrome|Chromium|HeadlessChrome)/(\\d{2,4})\\.").matcher(browser);
        return m.find() ? m.group(1) : fallback;
    }
    private UserAgents() {}
}
