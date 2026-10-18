package app.anihome.launcher.banners

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BannerSourceTest {
    @Test fun catalogUsesPublicRepository() {
        assertEquals("https://raw.githubusercontent.com/akasumitlamba/AniProject/main/banners/catalog.json", BannerSource.url("catalog.json"))
    }
    @Test fun imageStaysWithinPublicBannerDirectory() {
        assertEquals("https://raw.githubusercontent.com/akasumitlamba/AniProject/main/banners/images/gmail.webp", BannerSource.url("images/gmail.webp"))
    }
    @Test fun arbitraryPathsCannotChangeRepositoryOrEscapeBannerDirectory() {
        for (path in listOf("../README.md", "images/../../secret", "https://example.com/image.webp", "catalog.json?ref=other")) {
            assertThrows(IllegalArgumentException::class.java) { BannerSource.url(path) }
        }
    }
}
