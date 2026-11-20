package dev.wildware.agents.app.update

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** When the app looks for updates, and what it makes of the answers. Time here is the test's own. */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateCheckerTest {
    private val current = SemVer(1, 0, 1)

    @Test
    fun checksOnceSignedInThenEverySixHours() = runTest {
        val fake = FakeReleaseSource()
        val source = MutableStateFlow<ReleaseSource?>(null)
        val checker = UpdateChecker(current, source, MutableStateFlow(true))
        checker.start(backgroundScope)
        runCurrent()
        assertEquals(0, fake.checks, "nothing to ask before signing in")

        source.value = fake
        runCurrent()
        assertEquals(1, fake.checks, "checks straight after sign-in")
        assertEquals("1.0.2", checker.available.value?.version)

        advanceTimeBy(6.hours - 1.minutes)
        assertEquals(1, fake.checks)
        advanceTimeBy(2.minutes)
        assertEquals(2, fake.checks, "and again six hours later")
        advanceTimeBy(6.hours)
        assertEquals(3, fake.checks)
    }

    @Test
    fun sameOrOlderVersionsAreIgnored() = runTest {
        val fake = FakeReleaseSource(latest = release("1.0.1"))
        val checker = UpdateChecker(current, MutableStateFlow(fake), MutableStateFlow(true))
        checker.start(backgroundScope)
        runCurrent()
        assertNull(checker.available.value)

        fake.latest = release("1.0.0")
        assertEquals(CheckResult.UpToDate, checker.checkNow())
        assertNull(checker.available.value)

        fake.latest = null
        assertEquals(CheckResult.UpToDate, checker.checkNow(), "no release at all is up to date too")
    }

    @Test
    fun offlineOrRateLimitedIsQuietAndKeepsWhatItKnew() = runTest {
        val fake = FakeReleaseSource()
        val checker = UpdateChecker(current, MutableStateFlow(fake), MutableStateFlow(true))
        checker.start(backgroundScope)
        runCurrent()
        assertEquals("1.0.2", checker.available.value?.version)

        fake.failWith = ReleaseSourceException("Couldn't reach GitHub.")
        advanceTimeBy(6.hours + 1.minutes)
        assertEquals(2, fake.checks)
        assertEquals("1.0.2", checker.available.value?.version, "a failed check forgets nothing")

        fake.failWith = ReleaseSourceException(GitHubReleaseSource.SlowDownMessage)
        val result = checker.checkNow()
        assertIs<CheckResult.Failed>(result)
        assertEquals(GitHubReleaseSource.SlowDownMessage, result.message)

        fake.failWith = null
        advanceTimeBy(6.hours + 1.minutes)
        assertEquals("1.0.2", checker.available.value?.version, "and the next one carries on")
    }

    @Test
    fun withAutomaticOffOnlyAskingByHandChecks() = runTest {
        val fake = FakeReleaseSource()
        val automatic = MutableStateFlow(false)
        val checker = UpdateChecker(current, MutableStateFlow(fake), automatic)
        checker.start(backgroundScope)
        advanceTimeBy(13.hours)
        assertEquals(0, fake.checks)

        assertIs<CheckResult.Newer>(checker.checkNow())
        assertEquals(1, fake.checks)

        automatic.value = true
        runCurrent()
        assertEquals(2, fake.checks, "turning it on checks straight away")
    }

    @Test
    fun aReleaseWithoutThisComputersFileYetIsNotOffered() = runTest {
        val withoutMac = release("1.0.2", *allAssets("1.0.2").filter { it.kind != "mac-dmg" }.toTypedArray())
        val fake = FakeReleaseSource(latest = withoutMac)
        val hasMacFile: (Release) -> Boolean = { r -> r.assets.any { it.kind == "mac-dmg" } }
        val checker = UpdateChecker(current, MutableStateFlow(fake), MutableStateFlow(true), offers = hasMacFile)
        assertEquals(CheckResult.UpToDate, checker.checkNow(), "the Mac file is still with Apple")
        assertNull(checker.available.value)

        fake.latest = release("1.0.2")
        assertIs<CheckResult.Newer>(checker.checkNow(), "the next check finds it")
        assertEquals("1.0.2", checker.available.value?.version)
    }

    @Test
    fun checkingWithNowhereToCheckSaysSo() = runTest {
        val checker = UpdateChecker(current, MutableStateFlow(null), MutableStateFlow(true))
        assertIs<CheckResult.Failed>(checker.checkNow())
    }
}
