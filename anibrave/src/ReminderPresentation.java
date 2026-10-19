package app.anibrave;

/** In-memory presentation guard. A timeout hides a card, never changes the watch database. */
final class ReminderPresentation {
    static final int TIMEOUT_MS = 8000;
    private String page = "", anime = "";
    private int episode;
    void enter(String url) {
        if (url == null || url.isEmpty()) return;
        if (!page.equals(url)) { page = url; anime = ""; episode = 0; }
    }
    boolean accept(String url, String key, int number) {
        enter(url);
        if (anime.equals(key) && episode == number) return false;
        anime = key; episode = number;
        return true;
    }
    void reset() { page = ""; anime = ""; episode = 0; }
}
