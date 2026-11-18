package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.auth.Sessions
import io.ktor.http.HttpMethod
import io.ktor.http.parseUrlEncodedParameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** The shared password check (spec/01 §5.1): one limiter for the form and device sign-in. */
sealed interface PasswordCheck {
    data object Ok : PasswordCheck
    data class Refused(val status: Int, val message: String, val retryAfterS: Long? = null) : PasswordCheck
}

fun App.checkPassword(password: String?, clientAddress: String): PasswordCheck {
    if (password == "") return PasswordCheck.Refused(400, "Enter your password.")
    val verdict = loginLimiter.check(clientAddress)
    if (!verdict.allowed) return PasswordCheck.Refused(429, "Too many attempts. Try again later.", (verdict.retryAfterMs + 999) / 1000)
    if (!Passwords.verify(config.adminPasswordHash, password ?: "")) {
        loginLimiter.record(clientAddress)
        return PasswordCheck.Refused(400, "That password is not right.")
    }
    loginLimiter.reset(clientAddress)
    return PasswordCheck.Ok
}

/**
 * SvelteKit answers a form action with JSON when `Accept` prefers JSON over HTML, which a bare any-type
 * Accept (or none) does: its `negotiate` picks the first listed type on a tie.
 */
fun wantsActionJson(accept: String?): Boolean {
    data class Part(val type: String, val subtype: String, val q: Double, val i: Int)
    val parts = (accept ?: "*/*").split(',').mapIndexedNotNull { i, s ->
        Regex("([^/ \\t]+)/([^; \\t]+)[ \\t]*(?:;[ \\t]*q=([0-9.]+))?").find(s)?.let {
            Part(it.groupValues[1], it.groupValues[2], it.groupValues[3].ifEmpty { "1" }.toDoubleOrNull() ?: 0.0, i)
        }
    }.sortedWith(
        compareByDescending<Part> { it.q }.thenBy { if (it.subtype == "*") 1 else 0 }.thenBy { if (it.type == "*") 1 else 0 }.thenBy { it.i },
    )
    var best: String? = null
    var min = Int.MAX_VALUE
    for (mime in listOf("application/json", "text/html")) {
        val (type, subtype) = mime.split('/')
        val priority = parts.indexOfFirst { (it.type == type || it.type == "*") && (it.subtype == subtype || it.subtype == "*") }
        if (priority != -1 && priority < min) { best = mime; min = priority }
    }
    return best == "application/json"
}

private suspend fun ApplicationCall.formFields(): Map<String, String> {
    val text = bodyText() ?: return emptyMap()
    return text.parseUrlEncodedParameters().entries().associate { it.key to it.value.first() }
}

private suspend fun ApplicationCall.actionFailure(status: Int, message: String, page: String) {
    if (wantsActionJson(request.headers["Accept"])) {
        // devalue's encoding of { error: message }.
        val data = """[{"error":1},${Wire.encode(JsonPrimitive(message))}]"""
        respondJson(200, buildJsonObject {
            put("type", JsonPrimitive("failure"))
            put("status", JsonPrimitive(status))
            put("data", JsonPrimitive(data))
        })
    } else {
        respondRaw(status, page, "text/html")
    }
}

private suspend fun ApplicationCall.actionRedirect(location: String, vararg headers: Pair<String, String>) {
    if (wantsActionJson(request.headers["Accept"])) {
        respondJson(200, buildJsonObject {
            put("type", JsonPrimitive("redirect"))
            put("status", JsonPrimitive(303))
            put("location", JsonPrimitive(location))
        }, Wire.JSON, *headers)
    } else {
        respondRedirect303(location, *headers)
    }
}

fun Route.authRoutes(app: App) {
    endpoint(
        "/login",
        HttpMethod.Get to {
            if (app.hasSessionCookie(call)) {
                call.respondRedirect303(Guard.safeRedirectTarget(call.request.queryParameters["redirectTo"]))
            } else {
                call.respondRaw(200, Pages.login(null), "text/html")
            }
        },
        HttpMethod.Post to {
            val form = call.formFields()
            val redirectTo = form["redirectTo"] ?: call.request.queryParameters["redirectTo"]
            when (val result = app.checkPassword(form["password"], call.clientAddress())) {
                is PasswordCheck.Refused -> call.actionFailure(result.status, result.message, Pages.login(result.message))
                PasswordCheck.Ok -> call.actionRedirect(
                    Guard.safeRedirectTarget(redirectTo),
                    "Set-Cookie" to Sessions.setCookie(Sessions.issue(app.config.sessionSecret, app.clock())),
                )
            }
        },
    )
    endpoint(
        "/logout",
        HttpMethod.Get to {
            if (!app.hasSessionCookie(call)) call.respondRedirect303("/login")
            else call.respondRaw(200, Pages.logout(), "text/html")
        },
        HttpMethod.Post to {
            call.actionRedirect("/login", "Set-Cookie" to Sessions.CLEAR)
        },
    )
}
