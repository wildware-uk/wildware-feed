package dev.wildware.feed.parity

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The owner. By default the session cookie from the login form (sent by hand: it is marked Secure
 * and the server under test is plain http), with an Origin header on every request as a browser's
 * fetch sends. With PARITY_OWNER_AUTH=device the owner is a device key instead, which is all a
 * server without the deprecated website accepts.
 */
class Owner(val cookie: String? = null, val key: String? = null) {
    /** The owner's credentials on a request. */
    val auth: HttpRequestBuilder.() -> Unit = {
        if (key != null) bearer(key) else header(HttpHeaders.Cookie, cookie!!)
        header(HttpHeaders.Origin, Parity.baseUrl)
    }

    suspend fun send(method: HttpMethod, path: String, body: JsonElement? = null, configure: HttpRequestBuilder.() -> Unit = {}) =
        call(method, path, body) {
            auth()
            configure()
        }

    suspend fun get(path: String, configure: HttpRequestBuilder.() -> Unit = {}) = send(HttpMethod.Get, path, null, configure)
    suspend fun post(path: String, body: JsonElement? = null) = send(HttpMethod.Post, path, body)
    suspend fun patch(path: String, body: JsonElement? = null) = send(HttpMethod.Patch, path, body)
    suspend fun put(path: String, body: JsonElement? = null) = send(HttpMethod.Put, path, body)
    suspend fun delete(path: String, body: JsonElement? = null) = send(HttpMethod.Delete, path, body)

    companion object {
        /** The owner as PARITY_OWNER_AUTH says: the login form (default) or a device key. */
        suspend fun signIn(password: String = Parity.adminPassword): Owner =
            if (Parity.ownerAuth == "device") Owner(key = Device.signIn("parity-owner").token) else signInWithCookie(password)

        /** Sign in through the login form exactly as the browser does. */
        suspend fun signInWithCookie(password: String = Parity.adminPassword): Owner {
            val response = loginForm(password)
            val cookie = sessionCookie(response) ?: fail("login set no ad_session cookie: ${response.status}")
            return Owner(cookie = cookie)
        }

        suspend fun loginForm(password: String, accept: String = "text/html"): HttpResponse =
            call(HttpMethod.Post, "/login") {
                header(HttpHeaders.Origin, Parity.baseUrl)
                header(HttpHeaders.Accept, accept)
                setBody(FormDataContent(Parameters.build { append("password", password) }))
            }

        fun sessionCookie(response: HttpResponse): String? =
            response.headers.getAll(HttpHeaders.SetCookie).orEmpty()
                .firstOrNull { it.startsWith("ad_session=") }
                ?.substringBefore(';')
    }
}

/** A native app signed in with a device key. */
class Device(val id: String, val token: String) {
    suspend fun send(method: HttpMethod, path: String, body: JsonElement? = null, configure: HttpRequestBuilder.() -> Unit = {}) =
        call(method, path, body) { bearer(token); configure() }

    suspend fun get(path: String) = send(HttpMethod.Get, path)

    companion object {
        suspend fun signIn(name: String = "parity-device-${Parity.suffix()}"): Device {
            val response = call(HttpMethod.Post, "/api/devices/sign-in", buildJsonObject {
                put("password", Parity.adminPassword)
                put("name", name)
            })
            assertEquals(201, response.status.value, "device sign-in")
            val body = response.obj()
            return Device(body["device"]!!.jsonObject["id"]!!.jsonPrimitive.content, body["token"]!!.jsonPrimitive.content)
        }
    }
}

/** An agent: a bearer token speaking MCP over Streamable HTTP (JSON-RPC in, JSON out). */
class Agent(val name: String, val token: String) {
    private val ids = AtomicLong(1)
    private val throttled = AtomicInteger(0)

    /** True while a call of this agent's is waiting out a 429 before it tries again. */
    val waitingOutRateLimit: Boolean get() = throttled.get() > 0

    /**
     * One JSON-RPC request. A 429 is waited out (honouring Retry-After) and retried, as a real
     * agent must, unless [honourRateLimit] is false: the suite as a whole posts faster than one
     * token's 120 requests a minute.
     */
    suspend fun rpc(method: String, params: JsonObject? = null, honourRateLimit: Boolean = true): HttpResponse {
        while (true) {
            val response = call(HttpMethod.Post, "/mcp", buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", ids.getAndIncrement())
                put("method", method)
                if (params != null) put("params", params)
            }) {
                bearer(token)
                header(HttpHeaders.Accept, "application/json, text/event-stream")
            }
            if (response.status.value != 429 || !honourRateLimit) return response
            val wait = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull() ?: 5
            throttled.incrementAndGet()
            try {
                kotlinx.coroutines.delay(wait * 1000)
            } finally {
                throttled.decrementAndGet()
            }
        }
    }

    /** The JSON-RPC envelope of one call; fails the test on a transport-level problem. */
    suspend fun rpcResult(method: String, params: JsonObject? = null): JsonObject {
        val response = rpc(method, params)
        assertEquals(200, response.status.value, "MCP $method HTTP status")
        return response.obj()
    }

    /** tools/call, returning the whole `result` (content, structuredContent, isError). */
    suspend fun tool(name: String, arguments: JsonObject = JsonObject(emptyMap())): ToolResult {
        val envelope = rpcResult("tools/call", buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        })
        val result = envelope["result"]?.jsonObject ?: fail("tools/call $name had no result: $envelope")
        return ToolResult(name, result)
    }

    /** A tool call that must succeed; returns its structured content. */
    suspend fun ok(name: String, arguments: JsonObject = JsonObject(emptyMap())): JsonObject {
        val result = tool(name, arguments)
        if (result.isError) fail("$name failed: ${result.raw}")
        return result.structured
    }
}

class ToolResult(val tool: String, val raw: JsonObject) {
    val isError: Boolean get() = raw["isError"]?.jsonPrimitive?.content == "true"
    val structured: JsonObject get() = raw["structuredContent"]?.jsonObject ?: JsonObject(emptyMap())
    val text: String get() = raw["content"]?.jsonArray?.joinToString("\n") {
        it.jsonObject["text"]?.jsonPrimitive?.content ?: ""
    } ?: ""
}
