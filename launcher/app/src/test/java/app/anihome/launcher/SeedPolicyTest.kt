package app.anihome.launcher

import org.junit.Assert.*
import org.junit.Test

class SeedPolicyTest {
    @Test fun oldCompletedInstallsKeepDeliberatelyRemovedDefaults() {
        assertFalse(SeedPolicy.installDefaults(1, false))
        assertFalse(SeedPolicy.installDefaults(0, true))
    }
    @Test fun interruptedFirstInstallCanResumeWithoutCompletedMarker() {
        assertTrue(SeedPolicy.installDefaults(0, false))
    }
    @Test fun currentAndFutureSeedsNeverReinsertDefaults() {
        assertFalse(SeedPolicy.installDefaults(2, false))
        assertFalse(SeedPolicy.installDefaults(3, true))
    }
}
