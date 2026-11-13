package app.anihome.launcher.banners

import org.junit.Assert.*
import org.junit.Test

class AutomaticBannerPolicyTest {
    private val calculator = BannerEntry("calculator", "Calculator", "images/calculator.webp", listOf("org.example.calculator"), listOf("Calculator"), emptyList(), "a".repeat(64))
    @Test fun identicalLabelsNeverSubstituteForApplicationIdentity() {
        assertNull(AutomaticBannerPolicy.match(listOf(calculator), "other.vendor.calculator"))
        assertEquals(calculator, AutomaticBannerPolicy.match(listOf(calculator), "org.example.calculator"))
    }
    @Test fun unknownAndMissingPackagesFallBackToTheAppIcon() {
        assertNull(AutomaticBannerPolicy.match(listOf(calculator), null))
        assertNull(AutomaticBannerPolicy.match(listOf(calculator), ""))
        assertNull(AutomaticBannerPolicy.match(emptyList(), "org.example.calculator"))
    }

    private val prime = BannerEntry("prime-video", "Prime Video", "images/prime-video.webp", listOf("com.amazon.avod.thirdpartyclient"),
        listOf("Amazon Prime Video"), listOf("primevideo.com"), "b".repeat(64))
    private fun tile(kind: app.anihome.launcher.data.HomeTile.Kind, label: String, pkg: String? = null, url: String? = null, shortcut: String? = null) =
        app.anihome.launcher.data.HomeTile(1, kind, label, packageName = pkg, url = url, position = 0, shortcutId = shortcut)
    @Test fun websitesMatchByHostThenName() {
        val web = app.anihome.launcher.data.HomeTile.Kind.WEB
        assertEquals(prime, AutomaticBannerPolicy.match(listOf(prime), tile(web, "Watch", url = "https://www.primevideo.com/region/eu")) { false })
        assertEquals(prime, AutomaticBannerPolicy.match(listOf(prime), tile(web, "Amazon Prime Video", url = "https://example.org")) { false })
        assertNull(AutomaticBannerPolicy.match(listOf(prime), tile(web, "Example", url = "https://example.org")) { false })
    }
    @Test fun browserShortcutsMatchByNameAndAppShortcutsByPackage() {
        val app = app.anihome.launcher.data.HomeTile.Kind.APP
        // A browser publishing a site shortcut must not stamp its own banner on it.
        assertEquals(prime, AutomaticBannerPolicy.match(listOf(prime, calculator), tile(app, "Prime Video", "app.anibrave.main", shortcut = "s")) { it == "app.anibrave.main" })
        assertNull(AutomaticBannerPolicy.match(listOf(prime), tile(app, "Some site", "app.anibrave.main", shortcut = "s")) { true })
        // An app's own shortcut (e.g. Downloads) uses that app's banner.
        assertEquals(prime, AutomaticBannerPolicy.match(listOf(prime), tile(app, "Downloads", "com.amazon.avod.thirdpartyclient", shortcut = "d")) { false })
    }
}
