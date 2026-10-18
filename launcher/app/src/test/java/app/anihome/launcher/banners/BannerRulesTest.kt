package app.anihome.launcher.banners

import org.junit.Assert.*
import org.junit.Test

class BannerRulesTest {
    private val banner = BannerEntry("chatgpt", "ChatGPT", "images/chatgpt.webp", listOf("com.openai.chatgpt"), listOf("Chat GPT"), listOf("chatgpt.com"), "a".repeat(64))
    @Test fun exactTargetPrecedesTabletFavouritesThenNamesBreakPriorityTies() {
        val favourite = banner.copy(id = "netflix", name = "Netflix", packages = emptyList(), aliases = emptyList(), webHosts = emptyList(), tabletPriority = 100)
        val other = favourite.copy(id = "games", name = "Games")
        assertEquals(listOf("chatgpt", "games", "netflix"), BannerRules.ordered(listOf(favourite, other, banner), "com.openai.chatgpt", "Target", null).map { it.id })
    }
    @Test fun rankingKeepsPackageAndHostAheadOfNames() {
        assertEquals(3, BannerRules.matchPriority(banner, "com.openai.chatgpt", "ChatGPT", "https://chatgpt.com"))
        assertEquals(2, BannerRules.matchPriority(banner, null, "ChatGPT", "https://chatgpt.com"))
        assertEquals(1, BannerRules.matchPriority(banner, null, "Chat GPT", null))
        assertEquals(0, BannerRules.matchPriority(banner, null, "Unknown", "not a valid URL"))
    }
    @Test fun exactPackageAndNormalizedAliasMatch() {
        assertTrue(BannerRules.matches(banner, "com.openai.chatgpt", "Different label", null))
        assertTrue(BannerRules.matches(banner, null, "Chat-GPT", null))
    }
    @Test fun partialNamesAndLookalikePackagesDoNotMatch() {
        assertFalse(BannerRules.matches(banner, "com.openai.chatgpt.fake", "ChatGPT Bot", null))
        assertFalse(BannerRules.matches(banner, null, "Chat", null))
    }
    @Test fun exactWebHostOnly() {
        assertTrue(BannerRules.matches(banner, null, "AI", "https://www.chatgpt.com/chat"))
        assertFalse(BannerRules.matches(banner, null, "AI", "https://chatgpt.com.evil.test"))
        assertFalse(BannerRules.matches(banner, null, "AI", "https://evil.test/chatgpt.com"))
    }
    @Test fun canonicalFilesOnly() {
        assertTrue(BannerRules.safeFile("images/grok-ai.webp"))
        assertFalse(BannerRules.safeFile("images/../../secret.png"))
        assertFalse(BannerRules.safeFile("https://evil.test/banner.png"))
        assertFalse(BannerRules.safeFile("images/App.PNG"))
    }
    @Test fun hashMatchesKnownSha256() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", BannerRules.hash("abc".toByteArray()))
    }
}
