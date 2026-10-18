package app.anihome.launcher.notifications

import org.junit.Assert.*
import org.junit.Test

class NotificationPolicyTest {
    private fun entry(key: String, group: String? = null, summary: Boolean = false, clear: Boolean = true) = NotificationPolicy.Entry(key, group, summary, clear)
    @Test fun ongoingNotificationsAreNeverClearAllTargets() {
        assertEquals(listOf("message"), NotificationPolicy.dismissible(listOf(entry("music", clear = false), entry("message"))))
    }
    @Test fun summariesWithoutChildrenStayVisible() {
        assertEquals(setOf("summary"), NotificationPolicy.visible(listOf(entry("summary", "group", true))))
    }
    @Test fun duplicateSummaryIsHiddenButOtherGroupsRemainVisible() {
        assertEquals(setOf("child", "other"), NotificationPolicy.visible(listOf(entry("summary", "chat", true), entry("child", "chat"), entry("other", "other", true))))
    }
    @Test fun emptyNotificationsHaveNoDismissTargets() {
        assertTrue(NotificationPolicy.visible(emptyList()).isEmpty()); assertTrue(NotificationPolicy.dismissible(emptyList()).isEmpty())
    }
    @Test fun lastChildDismissalAlsoClearsHiddenSummary() {
        val items = listOf(entry("summary", "chat", true), entry("child", "chat"), entry("other", "other"))
        assertEquals(setOf("child", "summary"), NotificationPolicy.dismissalTargets(items, setOf("child")).toSet())
    }
    @Test fun dismissingOneChildPreservesOtherChildrenAndTheirSummary() {
        val items = listOf(entry("summary", "chat", true), entry("first", "chat"), entry("second", "chat"))
        assertEquals(listOf("first"), NotificationPolicy.dismissalTargets(items, setOf("first")))
    }
    @Test fun ongoingChildPreventsGroupSummaryCancellation() {
        val items = listOf(entry("summary", "chat", true), entry("first", "chat"), entry("ongoing", "chat", clear = false))
        assertEquals(listOf("first"), NotificationPolicy.dismissalTargets(items, setOf("first")))
    }
    @Test fun clearAllTargetsOnlyClearableNotificationsAcrossGroups() {
        val items = listOf(entry("summary", "chat", true), entry("first", "chat"), entry("second", "chat"), entry("ongoing", clear = false))
        assertEquals(setOf("summary", "first", "second"), NotificationPolicy.dismissalTargets(items, items.map { it.key }.toSet()).toSet())
    }
    @Test fun staleKeysCannotDismissUnrelatedNotifications() {
        assertTrue(NotificationPolicy.dismissalTargets(listOf(entry("new")), setOf("old")).isEmpty())
    }
    @Test fun clearAllDoesNotCancelSummaryAboveAnOngoingChild() {
        val items = listOf(entry("summary", "chat", true), entry("first", "chat"), entry("ongoing", "chat", clear = false))
        assertEquals(listOf("first"), NotificationPolicy.dismissalTargets(items, items.map { it.key }.toSet()))
    }
}
