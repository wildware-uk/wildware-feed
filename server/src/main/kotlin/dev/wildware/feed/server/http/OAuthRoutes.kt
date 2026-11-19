package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.auth.SlidingLimiter
import dev.wildware.feed.server.auth.Tokens
import dev.wildware.feed.server.domain.Agents
import dev.wildware.feed.server.domain.DomainError
import dev.wildware.feed.server.domain.OAuth
import dev.wildware.feed.server.domain.jsString
import dev.wildware.feed.server.domain.requiredText
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import io.ktor.http.parseQueryString
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.routing.Route
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * OAuth 2.1 for MCP clients, as the MCP authorization spec asks (spec/11-oauth.md): the two
 * metadata documents, dynamic client registration, the authorize page the owner signs in to and
 * allows a client on, the token endpoint and revocation. See [OAuth] for the rules on codes and
 * tokens, [OAuthPage] for the page.
 *
 * Everything here is public, so each endpoint is limited per client address, and bodies are small.
 * Nothing here logs a code or a token.
 */
fun Route.oauthRoutes(app: App) {
    val urls = OAuthUrls(app.config.publicBaseUrl)

    endpoint(
        "/.well-known/oauth-protected-resource",
        HttpMethod.Get to { call.respondMetadata(urls.protectedResource(urls.base)) },
        HttpMethod.Options to { call.preflight() },
    )
    endpoint(
        "/.well-known/oauth-protected-resource/mcp",
        HttpMethod.Get to { call.respondMetadata(urls.protectedResource(urls.mcp)) },
        HttpMethod.Options to { call.preflight() },
    )
    endpoint(
        "/.well-known/oauth-authorization-server",
        HttpMethod.Get to { call.respondMetadata(urls.authorizationServer()) },
        HttpMethod.Options to { call.preflight() },
    )

    endpoint(
        "/oauth/register",
        HttpMethod.Post to {
            if (!call.allowedOAuth(app.oauthRegisterLimiter, app)) return@to
            val body = call.smallBody(REGISTER_BODY_MAX) ?: return@to
            val parsed = Wire.parseStrict(body) as? JsonObject
                ?: return@to call.oauthError(400, "invalid_client_metadata", "the body must be a JSON object")
            try {
                val client = register(app, parsed, call.addressOrNull(app))
                call.respondJson(201, client, Wire.JSON_UTF8, *CORS, "Cache-Control" to "no-store")
            } catch (e: OAuth.Refused) {
                call.oauthError(e.status, e.error, e.description)
            }
        },
        HttpMethod.Options to { call.preflight() },
    )

    endpoint(
        "/oauth/authorize",
        HttpMethod.Get to {
            if (!call.allowedOAuth(app.oauthAuthorizeLimiter, app, page = true)) return@to
            when (val parsed = AuthRequest.parse(app, urls, call.request.queryParameters)) {
                is AuthRequest.Parsed.Fatal -> call.page(400, OAuthPage.problem(parsed.message))
                is AuthRequest.Parsed.Ok -> {
                    val nonce = OwnerSession.read(app, call)
                    if (nonce == null) call.page(200, OAuthPage.signIn(parsed.request, null))
                    else call.page(200, consent(app, parsed.request, nonce, null), parsed.request.redirectUri)
                }
            }
        },
        HttpMethod.Post to {
            if (!call.sameOrigin(app)) return@to call.page(403, OAuthPage.problem("This form was sent from another site, so it was ignored. Start again from the app you are connecting."))
            if (!call.allowedOAuth(app.oauthAuthorizeLimiter, app, page = true)) return@to
            val body = call.smallBody(FORM_BODY_MAX) ?: return@to
            val form = parseQueryString(body)
            val request = when (val parsed = AuthRequest.parse(app, urls, form)) {
                is AuthRequest.Parsed.Fatal -> return@to call.page(400, OAuthPage.problem(parsed.message))
                is AuthRequest.Parsed.Ok -> parsed.request
            }
            when (form["action"]) {
                "sign_in" -> signIn(app, call, request, form["password"])
                "allow", "deny" -> decide(app, call, request, form)
                "sign_out" -> call.respondRedirect303("/oauth/authorize?${request.query()}", "Set-Cookie" to OwnerSession.clear(app), *NO_STORE)
                else -> call.page(400, OAuthPage.problem("That was not a choice this page offers. Start again from the app you are connecting."))
            }
        },
    )

    // The owner's side (behind the guard, like every /api route): an agent's connections, and revoking one.
    endpoint(
        "/api/agents/{id}/connections",
        HttpMethod.Get to {
            val agent = app.agents.find(call.parameters["id"].orEmpty()) ?: throw dev.wildware.feed.server.domain.notFound("no such agent")
            call.respondOwner(200, buildJsonObject {
                put("connections", JsonArray(app.oauth.connections(agent.id).map { c ->
                    buildJsonObject {
                        put("id", c.id)
                        put("clientName", c.clientName)
                        put("redirectOrigin", formTarget(c.redirectUri)?.removeSuffix(":") ?: c.redirectUri)
                        put("createdAt", Wire.isoOf(c.createdAt))
                        put("lastUsedAt", c.lastUsedAt?.let { JsonPrimitive(Wire.isoOf(it)) } ?: JsonNull)
                    }
                }))
            })
        },
    )
    endpoint(
        "/api/agents/{id}/connections/{connection}",
        HttpMethod.Delete to {
            val revoked = app.oauth.revokeConnection(call.parameters["id"].orEmpty(), call.parameters["connection"].orEmpty())
            call.respondOwner(200, buildJsonObject { put("revoked", revoked) })
        },
    )

    endpoint(
        "/oauth/token",
        HttpMethod.Post to {
            if (!call.allowedOAuth(app.oauthTokenLimiter, app)) return@to
            val form = call.formBody() ?: return@to
            try {
                val client = call.clientOf(app, form)
                val resource = form["resource"]?.let { urls.resource(it) ?: throw OAuth.Refused("invalid_target", "this server is the resource ${urls.mcp}") }
                val live = { id: String -> app.agents.find(id)?.revokedAt == null }
                val issued = when (form["grant_type"]) {
                    "authorization_code" -> app.oauth.redeem(
                        form.required("code"), client.id, form["redirect_uri"], form.required("code_verifier"), resource, live,
                    )
                    "refresh_token" -> app.oauth.refresh(form.required("refresh_token"), client.id, resource, live)
                    null -> throw OAuth.Refused("invalid_request", "grant_type is required")
                    else -> throw OAuth.Refused("unsupported_grant_type", "grant_type must be authorization_code or refresh_token")
                }
                call.respondJson(
                    200,
                    buildJsonObject {
                        put("access_token", issued.accessToken)
                        put("token_type", "Bearer")
                        put("expires_in", issued.expiresInS)
                        put("refresh_token", issued.refreshToken)
                        put("scope", issued.scope)
                    },
                    Wire.JSON_UTF8, *CORS, *NO_STORE,
                )
            } catch (e: OAuth.Refused) {
                call.oauthError(e.status, e.error, e.description)
            }
        },
        HttpMethod.Options to { call.preflight() },
    )

    endpoint(
        "/oauth/revoke",
        HttpMethod.Post to {
            if (!call.allowedOAuth(app.oauthTokenLimiter, app)) return@to
            val form = call.formBody() ?: return@to
            try {
                val client = call.clientOf(app, form)
                app.oauth.revoke(form.required("token"), client.id)
                call.respondRaw(200, "", null, *CORS, *NO_STORE)
            } catch (e: OAuth.Refused) {
                call.oauthError(e.status, e.error, e.description)
            }
        },
        HttpMethod.Options to { call.preflight() },
    )
}

/** The URLs this server answers to as a resource and an authorization server. */
class OAuthUrls(publicBaseUrl: String) {
    val base: String = canonical(publicBaseUrl) ?: publicBaseUrl.trimEnd('/')
    val mcp = "$base/mcp"
    val issuer = base
    val resourceMetadata = "$base/.well-known/oauth-protected-resource/mcp"

    /** [asked] as this server's canonical resource (the whole server or its MCP endpoint), else null. */
    fun resource(asked: String): String? = canonical(asked)?.takeIf { it == base || it == mcp }

    fun protectedResource(resource: String) = buildJsonObject {
        put("resource", resource)
        put("authorization_servers", JsonArray(listOf(JsonPrimitive(issuer))))
        put("scopes_supported", JsonArray(listOf(JsonPrimitive(OAuth.SCOPE))))
        put("bearer_methods_supported", JsonArray(listOf(JsonPrimitive("header"))))
        put("resource_name", "Wildware Feed")
    }

    fun authorizationServer() = buildJsonObject {
        fun list(vararg values: String) = JsonArray(values.map(::JsonPrimitive))
        put("issuer", issuer)
        put("authorization_endpoint", "$base/oauth/authorize")
        put("token_endpoint", "$base/oauth/token")
        put("registration_endpoint", "$base/oauth/register")
        put("revocation_endpoint", "$base/oauth/revoke")
        put("scopes_supported", list(OAuth.SCOPE))
        put("response_types_supported", list("code"))
        put("response_modes_supported", list("query"))
        put("grant_types_supported", list("authorization_code", "refresh_token"))
        put("token_endpoint_auth_methods_supported", list("none"))
        put("revocation_endpoint_auth_methods_supported", list("none"))
        put("code_challenge_methods_supported", list("S256"))
        put("authorization_response_iss_parameter_supported", true)
    }

    companion object {
        /** Lower-case scheme and host, no trailing slash, no query or fragment; null if not an absolute http(s) URL. */
        fun canonical(url: String): String? {
            val u = runCatching { URI(url.trim()) }.getOrNull() ?: return null
            val scheme = u.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            if (u.rawFragment != null || u.rawQuery != null || u.rawUserInfo != null) return null
            val host = u.host?.lowercase() ?: return null
            val port = if (u.port == -1 || (scheme == "https" && u.port == 443) || (scheme == "http" && u.port == 80)) "" else ":${u.port}"
            return "$scheme://$host$port${u.rawPath.orEmpty().trimEnd('/')}"
        }
    }
}

/** A checked authorization request: who asked, where the answer goes, and the PKCE challenge. */
class AuthRequest(
    val client: OAuth.Client,
    val redirectUri: String,
    val state: String?,
    val codeChallenge: String,
    val resource: String,
    val scope: String,
    private val asked: Map<String, String>,
) {
    /** Whether the request named its redirect URI: then the token request must name it too (RFC 6749 §4.1.3). */
    val redirectUriGiven: Boolean get() = "redirect_uri" in asked

    /** The request's own parameters, to carry through the page's forms and back to GET. */
    val hidden: List<Pair<String, String>> get() = asked.toList()

    /** What the CSRF token is bound to: everything that decides what Allow does. */
    val binding: String get() = listOf(client.id, redirectUri, state.orEmpty(), codeChallenge, resource, scope).joinToString("\n")

    fun query(): String = asked.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }

    /**
     * A bad request is shown on the page, never redirected: anyone can register a client, so sending
     * errors to its redirect URI unasked would make this server an open redirector (RFC 9700
     * §4.11.2). Only the owner's own Allow or Deny goes back to the client.
     */
    sealed interface Parsed {
        class Fatal(val message: String) : Parsed
        class Ok(val request: AuthRequest) : Parsed
    }

    companion object {
        private val PARAMS = listOf("response_type", "client_id", "redirect_uri", "state", "code_challenge", "code_challenge_method", "resource", "scope")

        fun parse(app: App, urls: OAuthUrls, params: Parameters): Parsed {
            for (p in PARAMS) if ((params.getAll(p)?.size ?: 0) > 1) return Parsed.Fatal("The app sent $p more than once, so this request cannot be trusted.")
            val clientId = params["client_id"] ?: return Parsed.Fatal("The app did not say who it is (no client_id). Start again from the app.")
            val client = app.oauth.client(clientId)
                ?: return Parsed.Fatal("This app is not registered with this dashboard, or its registration expired. Remove the server from the app and add it again.")
            val redirect = app.oauth.redirectFor(client, params["redirect_uri"])
                ?: return Parsed.Fatal("The app asked to send the answer to an address it did not register, so nothing was sent. Start again from the app.")
            val state = params["state"]
            fun back(error: String, description: String) = Parsed.Fatal("The app's request was not right ($error: $description), so nothing was sent back. Tell whoever makes the app.")
            if (state != null && state.length > STATE_MAX) return back("invalid_request", "state is at most $STATE_MAX characters")
            if (params["response_type"] != "code") return back("unsupported_response_type", "response_type must be code")
            val challenge = params["code_challenge"] ?: return back("invalid_request", "code_challenge is required (PKCE, S256)")
            if (params["code_challenge_method"] != "S256") return back("invalid_request", "code_challenge_method must be S256")
            if (!OAuth.challengeWellFormed(challenge)) return back("invalid_request", "code_challenge must be a base64url SHA-256")
            val resource = when (val asked = params["resource"]) {
                null -> urls.mcp
                else -> urls.resource(asked) ?: return back("invalid_target", "this server is the resource ${urls.mcp}")
            }
            val asked = PARAMS.mapNotNull { p -> params[p]?.let { p to it } }.toMap()
            return Parsed.Ok(AuthRequest(client, redirect, state, challenge, resource, OAuth.SCOPE, asked))
        }

        /** [redirect] with [params] added to its query (null values left out). */
        fun answerUrl(redirect: String, params: List<Pair<String, String?>>): String {
            val query = params.filter { it.second != null }.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v!!)}" }
            return redirect + (if (redirect.contains('?')) "&" else "?") + query
        }

        private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
        const val STATE_MAX = 2000
    }
}

/**
 * The owner's sign-in on the authorize page: a cookie signed with SESSION_SECRET holding an expiry
 * and a random nonce. It is bound to the current password hash, so changing the password ends it.
 * The nonce is what the page's CSRF tokens are made from.
 */
object OwnerSession {
    const val TTL_MS = 12 * 60 * 60_000L

    fun cookieName(app: App) = if (secure(app)) "__Host-feed_owner" else "feed_owner"

    private fun secure(app: App) = app.config.publicBaseUrl.startsWith("https://")

    private fun signature(app: App, expires: Long, nonce: String) = Tokens.base64url(
        Tokens.hmac(app.config.sessionSecret, "oauth-owner:v1:$expires:$nonce:${Tokens.hex(MessageDigest.getInstance("SHA-256").digest(app.config.adminPasswordHash.toByteArray()))}"),
    )

    /** A Set-Cookie header for a new session. */
    fun mint(app: App): String {
        val expires = app.clock() + TTL_MS
        val nonce = Tokens.random()
        val value = "v1.$expires.$nonce.${signature(app, expires, nonce)}"
        return "${cookieName(app)}=$value; Path=/; Max-Age=${TTL_MS / 1000}; HttpOnly; SameSite=Lax" + if (secure(app)) "; Secure" else ""
    }

    /** A Set-Cookie header that ends the session. */
    fun clear(app: App): String =
        "${cookieName(app)}=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax" + if (secure(app)) "; Secure" else ""

    /** The session's nonce, or null when there is no valid session. */
    fun read(app: App, call: ApplicationCall): String? {
        val raw = call.request.cookies.rawCookies[cookieName(app)] ?: return null
        val parts = raw.split('.')
        if (parts.size != 4 || parts[0] != "v1") return null
        val expires = parts[1].toLongOrNull() ?: return null
        if (expires <= app.clock() || !Tokens.wellFormed(parts[2])) return null
        return parts[2].takeIf { Tokens.same(signature(app, expires, it), parts[3]) }
    }

    fun csrf(app: App, nonce: String, request: AuthRequest): String =
        Tokens.base64url(Tokens.hmac(app.config.sessionSecret, "oauth-csrf:v1:$nonce:${request.binding}"))
}

/**
 * The Allow page. A new agent is the default. An existing agent is offered first only when the
 * owner allowed this same redirect target for it before ([OAuth.agentsAllowedFor]); never because of
 * the client's name, which anyone registering a client can choose.
 */
private fun consent(app: App, request: AuthRequest, nonce: String, error: String?, name: String? = null): String {
    val agents = app.agents.list(includeRevoked = false)
    val suggested = (name ?: request.client.name).take(OAuth.NAME_MAX)
    val before = app.oauth.agentsAllowedFor(request.redirectUri)
    val existing = agents.lastOrNull { it.id in before }?.id
    return OAuthPage.consent(request, agents, OwnerSession.csrf(app, nonce, request), suggested, existing, error)
}

private suspend fun signIn(app: App, call: ApplicationCall, request: AuthRequest, password: String?) {
    val address = call.addressOrNull(app)
        ?: return call.page(400, OAuthPage.problem("This server cannot tell where your request came from, so it will not check a password. Ask whoever runs it to fix ADDRESS_HEADER."))
    // Loopback stays as it is, so a desktop host's own exemption from the lockout still applies.
    when (val result = app.checkPassword(password, if (isLoopback(address)) address else rateKey(address))) {
        is PasswordCheck.Refused -> {
            val headers = result.retryAfterS?.let { arrayOf("Retry-After" to it.toString()) } ?: emptyArray()
            call.page(result.status, OAuthPage.signIn(request, result.message), extra = headers)
        }
        PasswordCheck.Ok -> call.respondRedirect303("/oauth/authorize?${request.query()}", "Set-Cookie" to OwnerSession.mint(app), *NO_STORE)
    }
}

private suspend fun decide(app: App, call: ApplicationCall, request: AuthRequest, form: Parameters) {
    val nonce = OwnerSession.read(app, call) ?: return call.page(401, OAuthPage.signIn(request, "Your sign-in expired. Sign in again."))
    val csrf = form["csrf"].orEmpty()
    if (!Tokens.same(csrf, OwnerSession.csrf(app, nonce, request))) {
        return call.page(403, OAuthPage.problem("This page expired or did not come from this dashboard, so nothing was allowed. Start again from the app you are connecting."))
    }
    val issuer = OAuthUrls(app.config.publicBaseUrl).issuer
    if (form["action"] == "deny") {
        val location = AuthRequest.answerUrl(
            request.redirectUri,
            listOf("error" to "access_denied", "error_description" to "the owner denied this client", "state" to request.state, "iss" to issuer),
        )
        return call.respondRedirect303(location, *NO_STORE)
    }
    val name = form["name"]
    val agent: Agents.Agent = try {
        when (form["choice"]) {
            "existing" -> app.agents.find(form["agent_id"].orEmpty())?.takeIf { it.revokedAt == null }
                ?: return call.page(409, consent(app, request, nonce, "That agent no longer exists. Pick another, or make a new one.", name), request.redirectUri)
            else -> {
                val clean = requiredText(name.orEmpty(), "name", OAuth.NAME_MAX)
                val taken = app.agents.list(includeRevoked = false).firstOrNull { it.name.equals(clean, ignoreCase = true) }
                if (taken != null) {
                    return call.page(
                        409,
                        consent(app, request, nonce, "An agent is already called \"$clean\". Choose it under \"An agent you already have\", or pick another name.", clean),
                        request.redirectUri,
                    )
                }
                app.agents.mint(clean).first
            }
        }
    } catch (e: DomainError) {
        return call.page(400, consent(app, request, nonce, "The name ${e.message.removePrefix("name ")}.", name), request.redirectUri)
    }
    val code = app.oauth.allow(request.client, agent.id, request.resource, request.scope, request.redirectUri, request.redirectUriGiven, request.codeChallenge)
    call.respondRedirect303(AuthRequest.answerUrl(request.redirectUri, listOf("code" to code, "state" to request.state, "iss" to issuer)), *NO_STORE)
}

/** RFC 7591's request, checked: what this server will hold the client to. */
private fun register(app: App, body: JsonObject, address: String?): JsonObject {
    fun string(key: String): String? {
        val v = body[key] ?: return null
        if (v is JsonNull) return null
        return v.jsString ?: throw OAuth.Refused("invalid_client_metadata", "$key must be a string")
    }
    fun strings(key: String): List<String>? {
        val v = body[key] ?: return null
        if (v is JsonNull) return null
        val array = v as? JsonArray ?: throw OAuth.Refused("invalid_client_metadata", "$key must be an array of strings")
        return array.map { it.jsString ?: throw OAuth.Refused("invalid_client_metadata", "$key must be an array of strings") }
    }
    val redirects = strings("redirect_uris") ?: throw OAuth.Refused("invalid_redirect_uri", "redirect_uris is required")
    val grants = strings("grant_types") ?: listOf("authorization_code", "refresh_token")
    if (grants.any { it != "authorization_code" && it != "refresh_token" }) {
        throw OAuth.Refused("invalid_client_metadata", "grant_types may only be authorization_code and refresh_token")
    }
    val responses = strings("response_types") ?: listOf("code")
    if (responses.any { it != "code" }) throw OAuth.Refused("invalid_client_metadata", "response_types may only be code")
    val client = app.oauth.register(string("client_name"), string("client_uri"), redirects.distinct(), address)
    return buildJsonObject {
        put("client_id", client.id)
        put("client_id_issued_at", client.createdAt / 1000)
        put("client_name", client.name)
        if (client.clientUri != null) put("client_uri", client.clientUri)
        put("redirect_uris", JsonArray(client.redirectUris.map(::JsonPrimitive)))
        put("grant_types", JsonArray(listOf(JsonPrimitive("authorization_code"), JsonPrimitive("refresh_token"))))
        put("response_types", JsonArray(listOf(JsonPrimitive("code"))))
        // Every client here is public: it proves itself with PKCE, not a secret.
        put("token_endpoint_auth_method", "none")
        put("scope", OAuth.SCOPE)
    }
}

/** The client named by `client_id` (or HTTP Basic's user, for clients that send it there). */
private fun ApplicationCall.clientOf(app: App, form: Parameters): OAuth.Client {
    val basic = request.headers["Authorization"]?.takeIf { it.startsWith("Basic ", ignoreCase = true) }?.let {
        runCatching { String(java.util.Base64.getDecoder().decode(it.substring(6).trim())).substringBefore(':') }.getOrNull()
            ?.let { user -> java.net.URLDecoder.decode(user, Charsets.UTF_8) }
    }
    val id = form["client_id"] ?: basic ?: throw OAuth.Refused("invalid_client", "client_id is required", 401)
    if (basic != null && basic != id) throw OAuth.Refused("invalid_client", "client_id does not match the Authorization header", 401)
    return app.oauth.client(id) ?: throw OAuth.Refused("invalid_client", "unknown client_id: register again", 401)
}

private fun Parameters.required(key: String): String = this[key] ?: throw OAuth.Refused("invalid_request", "$key is required")

/** A form body (application/x-www-form-urlencoded), or null with the refusal sent. */
private suspend fun ApplicationCall.formBody(): Parameters? {
    val type = request.headers["Content-Type"]?.substringBefore(';')?.trim()?.lowercase()
    if (type != "application/x-www-form-urlencoded") {
        oauthError(400, "invalid_request", "send the parameters as application/x-www-form-urlencoded")
        return null
    }
    val body = smallBody(FORM_BODY_MAX) ?: return null
    return parseQueryString(body)
}

/** The body as text, at most [max] bytes, or null with a 413 sent. */
private suspend fun ApplicationCall.smallBody(max: Int): String? {
    val declared = request.headers["Content-Length"]?.toLongOrNull()
    if (declared != null && declared > max) {
        oauthError(413, "invalid_request", "the body is at most $max bytes")
        return null
    }
    val channel = receiveChannel()
    val buffer = ByteArray(max + 1)
    var read = 0
    while (read < buffer.size) {
        val n = channel.readAvailable(buffer, read, buffer.size - read)
        if (n < 0) break
        read += n
    }
    if (read > max) {
        oauthError(413, "invalid_request", "the body is at most $max bytes")
        return null
    }
    return String(buffer, 0, read, Charsets.UTF_8)
}

/**
 * A form post from this dashboard's own page ([pageOrigin]). A request that says nothing of where it
 * came from is not a browser's cross-site post, and the form's CSRF token still has to match.
 */
private fun ApplicationCall.sameOrigin(app: App): Boolean = pageOrigin(app.config) ?: true

private fun ApplicationCall.addressOrNull(app: App): String? = runCatching { clientAddress(app.config) }.getOrNull()

/**
 * The key an address is limited under: an IPv4 address as it is, an IPv6 one by its /64, since one
 * home or server usually holds a whole /64 and could otherwise rotate through it.
 */
internal fun rateKey(address: String): String {
    val a = address.removePrefix("[").removeSuffix("]").substringBefore('%')
    if (!a.contains(':') || !Regex("^[0-9A-Fa-f:.]+$").matches(a)) return address
    val bytes = runCatching { java.net.InetAddress.getByName(a).address }.getOrNull() ?: return address
    if (bytes.size != 16) return address
    return bytes.take(8).chunked(2).joinToString(":") { "%02x%02x".format(it[0], it[1]) } + "::/64"
}

/**
 * One from [limiter] for this client's address, or the refusal sent. With no address to go on (the
 * configured address header is missing) it refuses rather than share one bucket with everyone.
 */
private suspend fun ApplicationCall.allowedOAuth(limiter: SlidingLimiter, app: App, page: Boolean = false): Boolean {
    val address = addressOrNull(app)
    if (address == null) {
        val why = "this server cannot tell where the request came from (its address header is missing)"
        if (page) page(400, OAuthPage.problem("This server cannot tell where your request came from. Ask whoever runs it to fix ADDRESS_HEADER."))
        else respondJson(400, oauthBody("invalid_request", why), Wire.JSON_UTF8, *CORS)
        return false
    }
    val verdict = limiter.take(rateKey(address))
    if (verdict.allowed) return true
    val s = verdict.retryAfterSeconds
    if (page) page(429, OAuthPage.problem("Too many tries from this address. Wait $s seconds and start again."), extra = arrayOf("Retry-After" to s.toString()))
    else respondJson(429, oauthBody("slow_down", "too many requests from this address: retry after ${s}s"), Wire.JSON_UTF8, *CORS, "Retry-After" to s.toString())
    return false
}

private fun oauthBody(error: String, description: String) = buildJsonObject {
    put("error", error)
    put("error_description", description)
}

private suspend fun ApplicationCall.oauthError(status: Int, error: String, description: String) {
    val headers = if (status == 401) arrayOf("WWW-Authenticate" to "Basic realm=\"oauth\"") else emptyArray()
    respondJson(status, oauthBody(error, description), Wire.JSON_UTF8, *CORS, *NO_STORE, *headers)
}

private suspend fun ApplicationCall.respondMetadata(body: JsonObject) =
    respondJson(200, body, Wire.JSON_UTF8, *CORS, "Cache-Control" to "public, max-age=300")

private suspend fun ApplicationCall.preflight() = respondRaw(
    204, "", null, *CORS,
    "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers" to "Authorization, Content-Type, MCP-Protocol-Version",
    "Access-Control-Max-Age" to "86400",
)

/**
 * An HTML page, never cached, never framed, with no scripts. [redirectUri] widens form-action to
 * where Allow and Deny redirect, since browsers check a form's redirects against it too.
 */
private suspend fun ApplicationCall.page(status: Int, html: String, redirectUri: String? = null, extra: Array<Pair<String, String>> = emptyArray()) {
    val target = redirectUri?.let { formTarget(it) }?.let { " $it" }.orEmpty()
    respondRaw(
        status, html, "text/html; charset=utf-8",
        "Cache-Control" to "no-store",
        "Content-Security-Policy" to "default-src 'none'; style-src 'unsafe-inline'; img-src 'self' data:; form-action 'self'$target; frame-ancestors 'none'; base-uri 'none'",
        "X-Frame-Options" to "DENY",
        "X-Content-Type-Options" to "nosniff",
        "Referrer-Policy" to "no-referrer",
        *extra,
    )
}

/** The CSP source for a redirect URI: its origin, or `scheme:` for an app's own scheme. */
private fun formTarget(uri: String): String? {
    val u = runCatching { URI(uri) }.getOrNull() ?: return null
    val scheme = u.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return "$scheme:"
    val host = u.host ?: return null
    return "$scheme://$host${if (u.port == -1) "" else ":${u.port}"}"
}

private val CORS = arrayOf("Access-Control-Allow-Origin" to "*")
private val NO_STORE = arrayOf("Cache-Control" to "no-store", "Pragma" to "no-cache")
private const val REGISTER_BODY_MAX = 16_384
private const val FORM_BODY_MAX = 16_384
