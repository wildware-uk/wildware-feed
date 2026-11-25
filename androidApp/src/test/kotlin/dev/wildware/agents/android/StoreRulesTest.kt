package dev.wildware.agents.android

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoreRulesTest {
    @Test
    fun aCopyFromGooglePlayLinksToNoSignUpOrPlans() {
        assertFalse(StoreRules.linksToWebsite("com.android.vending"))
    }

    @Test
    fun anyOtherCopyLinksToTheWebsite() {
        // Sideloaded from the GitHub release: a browser, a file manager or the package installer.
        assertTrue(StoreRules.linksToWebsite("com.google.android.packageinstaller"))
        assertTrue(StoreRules.linksToWebsite("org.fdroid.fdroid"))
        // adb, or a phone that doesn't say.
        assertTrue(StoreRules.linksToWebsite(null))
    }
}
