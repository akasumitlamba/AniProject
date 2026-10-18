package app.anihome.launcher.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BannerPromptTest {
    @Test fun includesExactAppAndPackageWithoutInventingAnotherBrand() {
        val prompt = BannerPrompt.forTile("Grok Bot", "example.grokbot")
        assertTrue(prompt.contains("\"Grok Bot\""))
        assertTrue(prompt.contains("example.grokbot"))
        assertTrue(prompt.contains("1.62:1"))
        assertTrue(prompt.contains("preserve its visual identity"))
        assertTrue(prompt.contains("safe margins"))
        assertTrue(prompt.contains("no border"))
    }
    @Test fun websiteIdentityIsIncludedAndMissingIdentityIsOmitted() {
        assertTrue(BannerPrompt.forTile("AniKoto", "https://anikototv.to/").contains("https://anikototv.to/"))
        assertFalse(BannerPrompt.forTile("My app", null).contains("Identify this destination by:"))
    }
}
