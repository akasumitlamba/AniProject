package app.anibrave;

/** Geometry in dp. Keep a usable page beside the rail; narrow windows use an overlay. */
final class TabLayoutPolicy {
    static final int COMPACT = 56, EXPANDED = 240;
    static int reserved(int width, boolean expanded) {
        return expanded && width >= EXPANDED + 600 ? EXPANDED : COMPACT;
    }
    static int panel(int width, boolean expanded) {
        return expanded ? Math.min(EXPANDED, Math.max(COMPACT, width - 48)) : COMPACT;
    }
    private TabLayoutPolicy() {}
}
