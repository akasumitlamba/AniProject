package app.anihome.launcher.banners

import org.junit.Assert.*
import org.junit.Test

class BannerFetchPolicyTest {
    @Test fun privateDeletedDeniedAndRateLimitedSourcesPauseForFifteenMinutes() {
        for (status in listOf(401, 403, 404, 410, 429)) {
            val pause = BannerFetchPolicy.afterHttpFailure(status, 1000)
            assertEquals(status, pause.status)
            assertEquals(901000L, pause.untilMillis)
            assertFalse(BannerFetchPolicy.permits(pause, 1000))
            assertFalse(BannerFetchPolicy.permits(pause, 900999))
            assertTrue(BannerFetchPolicy.permits(pause, 901000))
        }
    }
    @Test fun explicitRetryCanBypassAnActivePause() {
        val pause = BannerFetchPolicy.afterHttpFailure(404, 1000)
        assertFalse(BannerFetchPolicy.permits(pause, 2000))
        assertTrue(BannerFetchPolicy.permits(pause, 2000, explicitRetry = true))
    }
    @Test fun clearedPolicyAllowsAnExplicitRequestAgain() {
        assertTrue(BannerFetchPolicy.permits(BannerFetchPolicy.Pause(), 2000))
    }
    @Test fun serverErrorsAreNotPersistedAsPrivateOrDeletedRepos() {
        assertEquals(BannerFetchPolicy.Pause(), BannerFetchPolicy.afterHttpFailure(500, 1000))
        assertEquals(BannerFetchPolicy.Pause(), BannerFetchPolicy.afterHttpFailure(503, 1000))
    }
    @Test fun friendlyErrorsDoNotEchoUrlOrCredentialMaterial() {
        for (status in listOf(401, 403, 404, 410, 429, 500)) {
            val reason = BannerFetchPolicy.reason(status)
            assertTrue(reason.isNotBlank())
            assertFalse(reason.contains("https://"))
            assertFalse(reason.contains("Bearer"))
        }
    }
}
