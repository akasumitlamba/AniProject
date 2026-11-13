package app.anibrave;

/** Classifies DevTools protocol errors that are expected during navigation. */
final class EngineErrors {
    private static final String[] RACES = {
        "Cannot find context with specified id",
        "Execution context was destroyed",
        "No target with given id",
        "Session with given id not found",
        "Target closed",
    };
    static boolean transientRace(String message) {
        if (message == null) return false;
        for (String race : RACES) if (message.contains(race)) return true;
        return false;
    }
    private EngineErrors() {}
}
