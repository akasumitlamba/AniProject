package app.anihome.launcher.ui

import org.junit.Assert.*
import org.junit.Test

class BuiltInWidgetPolicyTest {
    @Test fun `new built in IDs never overlap provider or pending IDs`() {
        assertEquals(-2, BuiltInWidgetPolicy.nextId(listOf(1, 10, 100)))
        assertEquals(-8, BuiltInWidgetPolicy.nextId(listOf(-2, -7, 1, 10)))
        assertEquals(-2, BuiltInWidgetPolicy.nextId(emptyList()))
    }
    @Test fun `legacy provider type stays separate from built ins and unknown types`() {
        assertTrue(BuiltInWidgetPolicy.isProvider(BuiltInWidgetPolicy.PROVIDER))
        for (kind in BuiltInWidgetPolicy.kinds) {
            assertTrue(BuiltInWidgetPolicy.isBuiltIn(kind))
            assertFalse(BuiltInWidgetPolicy.isProvider(kind))
        }
        assertFalse(BuiltInWidgetPolicy.isProvider("future-type"))
        assertFalse(BuiltInWidgetPolicy.isBuiltIn("future-type"))
    }
    @Test fun `host routing rejects synthetic pending and corrupt IDs even with provider kind`() {
        assertTrue(BuiltInWidgetPolicy.usesProviderHost(BuiltInWidgetPolicy.PROVIDER, 42))
        for (id in listOf(-200, -2, -1, 0)) assertFalse(BuiltInWidgetPolicy.usesProviderHost(BuiltInWidgetPolicy.PROVIDER, id))
        for (kind in BuiltInWidgetPolicy.kinds + "future-type") {
            for (id in listOf(-2, 0, 42)) assertFalse(BuiltInWidgetPolicy.usesProviderHost(kind, id))
        }
    }
}
