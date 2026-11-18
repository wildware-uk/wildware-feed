package dev.wildware.feed.parity

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Every parity class: skipped, not failed, when no server is configured. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class ParityTest {
    @BeforeAll
    fun needsServer() {
        assumeTrue(Parity.configured, "set PARITY_BASE_URL, PARITY_ADMIN_PASSWORD and PARITY_CLI to run the parity suite")
    }

    val owner get() = Parity.owner
    val alpha get() = Parity.alpha
    val beta get() = Parity.beta

    fun parity(timeout: Duration = 4.minutes, block: suspend () -> Unit) = runBlocking { withTimeout(timeout) { block() } }

    fun uniq(prefix: String) = "$prefix-${Parity.suffix()}"
}

/** Build JSON tersely: obj("a" to 1, "b" to obj(...), "c" to listOf(...)). */
fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to v.toJson() })
fun arr(vararg items: Any?): JsonArray = JsonArray(items.map { it.toJson() })

fun Any?.toJson(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(entries.associate { (k, v) -> k.toString() to v.toJson() })
    is Iterable<*> -> JsonArray(map { it.toJson() })
    is Array<*> -> JsonArray(map { it.toJson() })
    else -> error("cannot turn ${this::class} into JSON")
}

operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)
operator fun JsonElement?.get(index: Int): JsonElement = (this ?: error("indexing null")).jsonArray[index]
val JsonElement?.str: String? get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
val JsonElement?.s: String get() = str ?: error("expected a string, got $this")
val JsonElement?.int: Int get() = (this as? JsonPrimitive)?.content?.toInt() ?: error("expected a number, got $this")
val JsonElement?.long: Long get() = (this as? JsonPrimitive)?.content?.toLong() ?: error("expected a number, got $this")
val JsonElement?.bool: Boolean get() = (this as? JsonPrimitive)?.content?.toBooleanStrict() ?: error("expected a boolean, got $this")
val JsonElement?.arr: JsonArray get() = this?.jsonArray ?: error("expected an array, got null")
val JsonElement?.o: JsonObject get() = this?.jsonObject ?: error("expected an object, got null")
val JsonElement?.isNull: Boolean get() = this == null || this is JsonNull

/** Poll until [check] returns non-null, for things the server does in the background. */
suspend fun <T : Any> eventually(timeout: Duration = 30.seconds, every: Duration = 250.milliseconds, what: String = "condition", check: suspend () -> T?): T {
    val deadline = System.nanoTime() + timeout.inWholeNanoseconds
    while (true) {
        check()?.let { return it }
        if (System.nanoTime() > deadline) error("timed out after $timeout waiting for $what")
        delay(every)
    }
}

/**
 * A decided fix (server/spec/README.md, "Decided fixes") that the server under test may not have
 * yet. The test states the fixed behaviour. While a server lacks it the test is skipped, with the
 * reason, rather than failed; with PARITY_REQUIRE_FIXES=1 it must pass. Once every server has the
 * fix, unwrap the block so it is required everywhere.
 */
suspend fun pendingFix(number: Int, what: String, block: suspend () -> Unit) {
    if (System.getenv("PARITY_REQUIRE_FIXES") == "1") return block()
    try {
        block()
    } catch (e: AssertionError) {
        org.junit.jupiter.api.Assumptions.abort<Unit>("decided fix $number ($what) is not in this server yet: ${e.message}")
    } catch (e: IllegalStateException) {
        org.junit.jupiter.api.Assumptions.abort<Unit>("decided fix $number ($what) is not in this server yet: ${e.message}")
    }
}
