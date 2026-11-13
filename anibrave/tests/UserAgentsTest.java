package app.anibrave;

public final class UserAgentsTest {
    public static void main(String[] args) {
        eq(UserAgents.chromeMajor("Chrome/157.0.8103.79", "1"), "157");
        eq(UserAgents.chromeMajor("Brave Chrome/158.0.8127.22", "1"), "158");
        eq(UserAgents.chromeMajor("", "157"), "157");
        eq(UserAgents.chromeMajor(null, "157"), "157");
        System.out.println("UserAgentsTest passed");
    }
    private static void eq(String a, String b) { if (!a.equals(b)) throw new AssertionError(a + " != " + b); }
}
