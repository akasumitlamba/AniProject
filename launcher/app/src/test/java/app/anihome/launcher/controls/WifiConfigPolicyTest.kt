package app.anihome.launcher.controls

import org.junit.Assert.*
import org.junit.Test

class WifiConfigPolicyTest {
    @Test fun ssidLimitUsesUtf8Bytes() {
        assertTrue(WifiConfigPolicy.validSsid("é".repeat(16)))
        assertFalse(WifiConfigPolicy.validSsid("é".repeat(17)))
        assertFalse(WifiConfigPolicy.validSsid(""))
    }
    @Test fun personalPassphraseBoundaries() {
        assertFalse(WifiConfigPolicy.validPsk("1234567"))
        assertTrue(WifiConfigPolicy.validPsk("12345678"))
        assertTrue(WifiConfigPolicy.validPsk("x".repeat(63)))
        assertFalse(WifiConfigPolicy.validPsk("x".repeat(64)))
    }
    @Test fun rawHexPskIsNotQuoted() {
        val hex = "abcdef01".repeat(8)
        assertTrue(WifiConfigPolicy.validPsk(hex))
        assertEquals(hex, WifiConfigPolicy.encodePsk(hex))
        assertEquals("\"test passphrase\"", WifiConfigPolicy.encodePsk("test passphrase"))
    }
    @Test fun quotesAndBackslashesRemainWithinOneString() {
        assertEquals("\"a\\\"b\\\\c\"", WifiConfigPolicy.quote("a\"b\\c"))
    }
}
