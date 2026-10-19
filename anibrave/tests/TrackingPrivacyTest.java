package app.anibrave;

public final class TrackingPrivacyTest {
    public static void main(String[] args) {
        if (!TrackingPrivacy.normalContext("normal", "normal")) throw new AssertionError("normal profile allowed");
        for (String normal : new String[]{null,"","normal"})
            for (String source : new String[]{null,"","private","normal-other"})
                if (TrackingPrivacy.normalContext(normal,source)) throw new AssertionError("private/unknown context allowed");
        System.out.println("13 tracking privacy checks passed");
    }
}
