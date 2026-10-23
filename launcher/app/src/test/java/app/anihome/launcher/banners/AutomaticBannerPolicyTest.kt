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
}
