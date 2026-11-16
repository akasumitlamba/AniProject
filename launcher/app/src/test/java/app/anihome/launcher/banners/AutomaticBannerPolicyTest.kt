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

    // Real catalog shapes: an app entry with no website listed, and look-alike names.
    private fun entry(id: String, name: String, pkg: List<String>, hosts: List<String>, aliases: List<String>) =
        BannerEntry(id, name, "images/$id.webp", pkg, aliases, hosts, "c".repeat(64))
    private val hotstar = entry("jiohotstar", "JioHotstar", listOf("in.startv.hotstar"), emptyList(), listOf("JioHotstar", "Jio Hotstar", "Hotstar", "Disney+ Hotstar"))
    private val myjio = entry("myjio", "MyJio", listOf("com.jio.myjio"), listOf("jio.com"), listOf("MyJio", "My Jio", "Jio"))
    private val saavn = entry("jiosaavn", "JioSaavn", listOf("com.jio.media.jiobeats"), listOf("jiosaavn.com"), listOf("JioSaavn"))
    private val youtube = entry("youtube", "YouTube", listOf("com.google.android.youtube"), listOf("youtube.com", "m.youtube.com"), listOf("YouTube"))
    private val ytMusic = entry("youtube-music", "YouTube Music", listOf("com.google.android.apps.youtube.music"), listOf("music.youtube.com"), listOf("YouTube Music", "YT Music"))
    private val netflix = entry("netflix", "Netflix", listOf("com.netflix.mediaclient"), listOf("netflix.com"), listOf("Netflix"))
    private val catalog = listOf(hotstar, myjio, saavn, youtube, ytMusic, netflix, prime, calculator)

    @Test fun siteWithoutListedWebsiteMatchesItsAppBannerByAddressWords() {
        assertEquals(hotstar, SiteMatcher.match(catalog, "https://www.hotstar.com/in/home", "JioHotstar - Watch TV Shows"))
        assertEquals(hotstar, SiteMatcher.match(catalog, "https://www.jiohotstar.com/", ""))
        assertEquals(hotstar, SiteMatcher.match(catalog, null, "JioHotstar - Watch TV Shows, Movies"))
    }
    @Test fun mostSpecificWebsiteWinsOverParentAndLookAlikes() {
        assertEquals(ytMusic, SiteMatcher.match(catalog, "https://music.youtube.com/watch?v=1", "Song - YouTube Music"))
        assertEquals(youtube, SiteMatcher.match(catalog, "https://m.youtube.com/watch?v=1", "Video - YouTube"))
        assertEquals(saavn, SiteMatcher.match(catalog, "https://www.jiosaavn.com/", ""))
        assertEquals(myjio, SiteMatcher.match(catalog, "https://www.jio.com/selfcare", ""))
    }
    @Test fun appAndItsWebsiteShareOneBanner() {
        val appKind = app.anihome.launcher.data.HomeTile.Kind.APP
        val webKind = app.anihome.launcher.data.HomeTile.Kind.WEB
        assertEquals(hotstar, AutomaticBannerPolicy.match(catalog, tile(appKind, "JioHotstar", "in.startv.hotstar")) { false })
        assertEquals(hotstar, AutomaticBannerPolicy.match(catalog, tile(webKind, "Hotstar", url = "https://www.hotstar.com/in")) { false })
        // A browser's fullscreen site shortcut, with or without the site address attached.
        assertEquals(hotstar, AutomaticBannerPolicy.match(catalog, tile(appKind, "JioHotstar - Watch TV Sho", "app.anibrave.main", "https://www.hotstar.com", "s")) { true })
        assertEquals(netflix, AutomaticBannerPolicy.match(catalog, tile(appKind, "Netflix India - Watch TV", "app.anibrave.main", shortcut = "s")) { true })
    }
    @Test fun unknownOrAmbiguousSitesKeepTheirIcon() {
        assertNull(SiteMatcher.match(catalog, "https://example.org/", "Example Domain"))
        assertNull(SiteMatcher.match(catalog, "https://blog.example.com/", "Notes - My blog"))
        // Short words never match by containment ("jio" alone would hit every Jio app).
        assertNull(SiteMatcher.match(listOf(saavn, hotstar), "https://jiotv.example/", "Jio"))
    }
}

