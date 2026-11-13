package app.anibrave;

public final class EngineErrorsTest {
    public static void main(String[] args) {
        check(EngineErrors.transientRace("Cannot find context with specified id"), "stale context");
        check(EngineErrors.transientRace("Session with given id not found"), "closed session");
        check(!EngineErrors.transientRace("Invalid parameters"), "real failure stays visible");
        check(!EngineErrors.transientRace(null), "missing message");
        System.out.println("EngineErrorsTest passed");
    }
    private static void check(boolean value, String name) { if (!value) throw new AssertionError(name); }
}
