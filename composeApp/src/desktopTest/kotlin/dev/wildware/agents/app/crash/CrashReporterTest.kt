package dev.wildware.agents.app.crash

import dev.wildware.agents.MemoryTextStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrashReporterTest {
    private val config = CrashReportConfig("https://key@sentry.example/1", "wildware-feed@1.0.0", "production", null)

    private class FakeSdk : CrashSdk {
        var starts = 0
        var stops = 0
        var allowed: () -> Boolean = { false }

        override fun start(config: CrashReportConfig, allowed: () -> Boolean) {
            starts++
            this.allowed = allowed
        }

        override fun stop() {
            stops++
        }
    }

    @Test
    fun `crash reports are on unless turned off`() {
        val sdk = FakeSdk()
        val setting = CrashReporter(config, sdk).start(MemoryTextStore())
        assertTrue(setting.enabled.value)
        assertEquals(1, sdk.starts)
        assertTrue(sdk.allowed())
    }

    @Test
    fun `turning them off stops the next report at once and the SDK`() {
        val sdk = FakeSdk()
        val store = MemoryTextStore()
        val setting = CrashReporter(config, sdk).start(store)
        val allowed = sdk.allowed
        setting.set(false)
        assertFalse(allowed(), "a report already on its way must see the switch off")
        assertEquals(1, sdk.stops)
        assertFalse(setting.enabled.value)
        assertEquals("off", store.read())
    }

    @Test
    fun `off is remembered and nothing starts next time`() {
        val store = MemoryTextStore().apply { write("off") }
        val sdk = FakeSdk()
        val setting = CrashReporter(config, sdk).start(store)
        assertFalse(setting.enabled.value)
        assertEquals(0, sdk.starts)
    }

    @Test
    fun `turning them back on starts the SDK again`() {
        val store = MemoryTextStore().apply { write("off") }
        val sdk = FakeSdk()
        val setting = CrashReporter(config, sdk).start(store)
        setting.set(true)
        assertEquals(1, sdk.starts)
        assertTrue(sdk.allowed())
        assertEquals("on", store.read())
    }
}
