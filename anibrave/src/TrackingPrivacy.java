package app.anibrave;

/** CDP supplies distinct context IDs for normal and off-the-record profiles.
 * Unknown context identity must never authorize persistent watch history. */
final class TrackingPrivacy {
    static boolean normalContext(String normal, String source) {
        return normal != null && !normal.isEmpty() && normal.equals(source);
    }
}
