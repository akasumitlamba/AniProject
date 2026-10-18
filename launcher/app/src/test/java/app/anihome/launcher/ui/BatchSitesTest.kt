package app.anihome.launcher.ui

import org.junit.Assert.*
import org.junit.Test

class BatchSitesTest {
    private fun parse(text: String) = BatchSites.parse(text) {
        if (it.contains('.') && !it.contains(' ')) if (it.startsWith("https://")) it else "https://$it" else null
    }
    @Test fun mixedNamedAndUnnamedSitesKeepInputOrder() {
        val draft = parse("youtube.com\nAniKoto | https://anikototv.to/\n\ngithub.com")
        assertTrue(draft.invalidLines.isEmpty())
        assertEquals(listOf("https://youtube.com", "https://anikototv.to/", "https://github.com"), draft.sites.map { it.url })
        assertEquals("AniKoto", draft.sites[1].name)
        assertNull(draft.sites[0].name)
    }
    @Test fun invalidLinesReferToOriginalDraftIncludingBlankLines() {
        assertEquals(listOf(3, 4), parse("youtube.com\n\nnot a url\nName | ").invalidLines)
    }
    @Test fun duplicateUrlsDoNotReplaceFirstCustomName() {
        val draft = parse("My YouTube | youtube.com\nOther | https://youtube.com")
        assertEquals(1, draft.sites.size); assertEquals("My YouTube", draft.sites.single().name)
    }
    @Test fun blankDraftAddsNothing() { assertTrue(parse("\n  \n").sites.isEmpty()) }
}
