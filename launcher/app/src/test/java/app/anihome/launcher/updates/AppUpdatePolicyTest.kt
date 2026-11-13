package app.anihome.launcher.updates

import org.junit.Assert.*
import org.junit.Test

class AppUpdatePolicyTest {
    private fun asset(name: String, url: String = "https://github.com/${AppUpdatePolicy.REPOSITORY}/releases/download/v1.0.1/$name") = AppUpdatePolicy.Asset(name, url, 12_000_000, null)
    @Test fun combinedReleaseSelectsOnlyAniHomeSignedReleaseFilename() {
        val home = asset("AniHome-Launcher-1.0.1-release.apk")
        assertEquals(home, AppUpdatePolicy.choose(listOf(asset("AniBrave-1.0.1-arm64-v8a.apk"), asset("AniBrowser.apk"), asset("AniHome-debug.apk"), asset("AniHome-Launcher-1.0.1-unsigned.apk"), home)))
    }
    @Test fun ambiguousOrMissingHomeAssetsAreNotInstalled() {
        val home = asset("AniHome-Launcher-1.0.1-release.apk")
        assertNull(AppUpdatePolicy.choose(emptyList()))
        assertNull(AppUpdatePolicy.choose(listOf(home, asset("AniHome-Launcher-1.0.2-release.apk"))))
    }
    @Test fun rejectsExternalOrSpoofedDownloadLinksAndInvalidSizes() {
        val home = asset("AniHome-Launcher-1.0.1-release.apk")
        for (url in listOf(home.url.replace("github.com", "github.com.evil.test"), home.url.replace("https:", "http:"), home.url.replace("AniProject", "AnotherProject"), home.url + "?token=private")) {
            assertNull(AppUpdatePolicy.choose(listOf(home.copy(url = url))))
        }
        assertNull(AppUpdatePolicy.choose(listOf(home.copy(bytes = AppUpdatePolicy.MAX_APK_BYTES + 1))))
        assertNull(AppUpdatePolicy.choose(listOf(home.copy(bytes = 0))))
    }
    @Test fun onlyExpectedHttpsDownloadRedirectsAreAllowed() {
        assertTrue(AppUpdatePolicy.allowedDownloadUrl("https://release-assets.githubusercontent.com/file?signature=example"))
        assertFalse(AppUpdatePolicy.allowedDownloadUrl("https://release-assets.githubusercontent.com.evil.test/file"))
        assertFalse(AppUpdatePolicy.allowedDownloadUrl("file:///tmp/app.apk"))
        assertFalse(AppUpdatePolicy.allowedDownloadUrl("https://user:password@github.com/file"))
    }
    @Test fun providedDigestsMustBeValidAndMatch() {
        val hash = "a".repeat(64)
        assertTrue(AppUpdatePolicy.matchesDigest(hash, "sha256:$hash"))
        assertTrue(AppUpdatePolicy.matchesDigest(hash, null))
        assertFalse(AppUpdatePolicy.matchesDigest(hash, "sha256:" + "b".repeat(64)))
        assertFalse(AppUpdatePolicy.matchesDigest(hash, "sha1:$hash"))
        assertFalse(AppUpdatePolicy.matchesDigest(hash, "sha256:wrong"))
    }
}
