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
    @Test fun alternateRepositoryAndCatalogLinksNormalizeToOneStore() {
        val expected = "https://raw.githubusercontent.com/example/Art/main/banners/"
        for (input in listOf("example/Art", "https://github.com/example/Art", "https://github.com/example/Art.git", "https://github.com/example/Art/tree/main/banners", expected + "catalog.json")) {
            assertEquals(expected, BannerSource.normalize(input))
        }
        assertEquals("https://raw.githubusercontent.com/example/Art/stable/store/", BannerSource.normalize("https://github.com/example/Art/blob/stable/store/catalog.json"))
        assertEquals(BannerSource.DEFAULT_BASE, BannerSource.normalize(""))
        assertEquals("https://art.example.org/store/", BannerSource.normalize("https://art.example.org/store/catalog.json"))
        assertEquals("https://art.example.org/store/images/gmail.webp", BannerSource.url("images/gmail.webp", "https://art.example.org/store/"))
    }
    @Test fun storeLinksCannotCarryCredentialsOrEscapeGithub() {
        for (input in listOf("http://github.com/example/Art", "https://github.com.evil.test/example/Art", "https://user:secret@github.com/example/Art", "https://github.com/example/Art?token=secret", "https://raw.githubusercontent.com/example/Art/main/../", "https://github.com/example/Art/issues", "https://127.0.0.1/catalog.json", "https://localhost/catalog.json", "https://art.example.org/../catalog.json")) {
            assertThrows(IllegalArgumentException::class.java) { BannerSource.normalize(input) }
        }
    }

}
