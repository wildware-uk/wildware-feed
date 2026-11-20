package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.auth.SlidingLimiter
import dev.wildware.feed.server.auth.Tokens
import dev.wildware.feed.server.db.Dialect
import dev.wildware.feed.server.db.TeamTables
import dev.wildware.feed.server.db.Tenant
import dev.wildware.feed.server.domain.DomainError
import dev.wildware.feed.server.domain.People
import dev.wildware.feed.server.domain.Teams
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.uri
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest

/**
 * The admin API the control plane calls (docs/HOSTING.md §7, contract version 1): make a team and
 * its owner, invite, suspend and resume, set limits, delete, read usage, and sign someone in after
 * the control plane has checked their email itself. It knows teams, people and numbers, never what
 * a plan or price is.
 *
 * Every call is signed with one of ADMIN_API_KEYS (HMAC-SHA256 over the method, path, time and
 * body); the key never travels. No other credential works here, and the signature works nowhere
 * else. Unset keys mean every `/admin/` route answers 404. Calls are logged (call, team, status;
 * never bodies).
 */
class AdminApi(private val app: App) {
    /** 600 signed calls a minute, then 429. */
    val limiter = SlidingLimiter(limit = 600, windowMs = 60_000, clock = app.clock)

    /**
     * Signatures already used for a write, kept as long as their time is good: one call, seen by
     * someone else in transit, can't be sent again (a `POST` would make a fresh setup link or ticket).
     */
    private val seen = LinkedHashMap<String, Long>()

    /** False when [signature] was used before; otherwise remembers it. */
    @Synchronized
    fun firstUse(signature: String): Boolean {
        val now = app.clock()
        val it = seen.entries.iterator()
        while (it.hasNext()) if (it.next().value < now - 2 * MAX_SKEW_S * 1000) it.remove() else break
        if (signature in seen) return false
        seen[signature] = now
        return true
    }

    /** Idempotency keys older than a day go. */
    fun sweep() {
        app.db.update("DELETE FROM admin_requests WHERE created_at < ?", app.clock() - IDEMPOTENCY_TTL_MS)
    }

    companion object {
        const val MAX_SKEW_S = 300L
        const val IDEMPOTENCY_TTL_MS = 24L * 60 * 60 * 1000
        /** A first call still running after this long is taken as dead, and the key can be used again. */
        const val RUNNING_STALE_MS = 5L * 60 * 1000
        const val BODY_MAX = 64 * 1024
        const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        fun sha256Hex(bytes: ByteArray): String = Tokens.hex(MessageDigest.getInstance("SHA-256").digest(bytes))

        /** The signed message: five lines, no trailing newline. */
        fun message(timestamp: String, method: String, pathAndQuery: String, body: ByteArray): String =
            listOf("v1", timestamp, method, pathAndQuery, if (body.isEmpty()) EMPTY_SHA256 else sha256Hex(body)).joinToString("\n")

        fun sign(key: String, message: String): String = "v1=" + Tokens.hex(Tokens.hmac(key, message))
    }
}

/** A refusal with the contract's `{error, message}`. */
private class AdminError(val status: Int, val code: String, message: String, val retryAfter: Long? = null) : RuntimeException(message)

private fun bad(message: String) = AdminError(400, "invalid_request", message)
private fun noTeam() = AdminError(404, "no_such_team", "There is no such team.")

/** One parsed, verified admin call. */
private class AdminCall(val call: ApplicationCall, val method: String, val segments: List<String>, val body: ByteArray) {
    val json: JsonObject by lazy {
        if (body.isEmpty()) throw bad("The body must be a JSON object.")
        Wire.parseStrict(body.toString(Charsets.UTF_8)) as? JsonObject ?: throw bad("The body must be a JSON object.")
    }

    fun string(o: JsonObject, key: String, max: Int, required: Boolean = true): String? {
        val v = o[key]
        if (v == null || v is JsonNull) {
            if (required) throw bad("$key is required.")
            return null
        }
        if (v !is JsonPrimitive || !v.isString) throw bad("$key must be a string.")
        val s = v.content.trim()
        if (s.isEmpty() && required) throw bad("$key is required.")
        if (s.length > max) throw bad("$key is at most $max characters.")
        return s
    }

    fun count(o: JsonObject, key: String): Long? {
        val v = o[key] ?: return null
        if (v is JsonNull) return null
        val n = (v as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: throw bad("$key must be a whole number.")
        if (n < 0) throw bad("$key can't be negative.")
        return n
    }
}

fun Route.adminRoutes(app: App) {
    route("/admin/{...}") { handle { call.admin(app) } }
    route("/admin") { handle { call.admin(app) } }
}

private suspend fun ApplicationCall.admin(app: App) {
    val keys = app.config.adminApiKeys
    if (keys.isEmpty()) return respondJson(404, Wire.error("not_found", "no such route"))
    val method = request.httpMethod.value
    val pattern = StringBuilder()
    var teamId: String? = null
    val status = try {
        val body = readBody()
        verify(app, keys, body)
        val segments = Guard.normalise(request.path()).removePrefix("/admin").trim('/').split('/').filter { it.isNotEmpty() }
        val verdict = app.admin.limiter.take("admin")
        if (!verdict.allowed) throw AdminError(429, "rate_limited", "Too many admin calls. Try again shortly.", verdict.retryAfterSeconds)
        val c = AdminCall(this, method, segments, body)
        teamId = segments.getOrNull(1)?.takeIf { segments.firstOrNull() == "teams" }
        route(app, c, pattern)
    } catch (e: AdminError) {
        val headers = e.retryAfter?.let { arrayOf("Retry-After" to it.toString()) } ?: emptyArray()
        respondJson(e.status, Wire.error(e.code, e.message), Wire.JSON, "Cache-Control" to "no-store", *headers)
        e.status
    } catch (e: DomainError) {
        respondJson(e.status, Wire.error(e.code, e.message), Wire.JSON, "Cache-Control" to "no-store")
        e.status
    }
    // The audit line: which call, which team, how it went. Never a body, never an email.
    println("admin: $method /admin/${pattern.ifEmpty { "?" }}${teamId?.let { " team=$it" }.orEmpty()} status=$status")
}

private suspend fun ApplicationCall.readBody(): ByteArray {
    val declared = request.headers["Content-Length"]?.toLongOrNull()
    if (declared != null && declared > AdminApi.BODY_MAX) throw AdminError(413, "invalid_request", "The body is at most ${AdminApi.BODY_MAX} bytes.")
    // A request with neither a length nor chunks has no body (a DELETE, a GET): don't wait for one.
    if ((declared == null || declared == 0L) && request.headers["Transfer-Encoding"] == null) return ByteArray(0)
    return receiveAtMost(AdminApi.BODY_MAX) ?: throw AdminError(413, "invalid_request", "The body is at most ${AdminApi.BODY_MAX} bytes.")
}

/** The contract's signature check: a known key, a fresh time, over exactly what was sent. */
private fun ApplicationCall.verify(app: App, keys: List<String>, body: ByteArray) {
    fun refuse(code: String, message: String): Nothing = throw AdminError(401, code, message)
    val timestamp = request.headers["Feed-Admin-Timestamp"]?.trim()
    val signature = request.headers["Feed-Admin-Signature"]?.trim()
    if (timestamp == null || signature == null) refuse("bad_signature", "The call isn't signed.")
    val seconds = timestamp.takeIf { Regex("^[0-9]{1,12}$").matches(it) }?.toLong() ?: refuse("bad_signature", "Feed-Admin-Timestamp must be unix seconds.")
    if (kotlin.math.abs(app.clock() / 1000 - seconds) > AdminApi.MAX_SKEW_S) refuse("stale_request", "The call's time is more than ${AdminApi.MAX_SKEW_S} s off.")
    val message = AdminApi.message(timestamp, request.httpMethod.value, request.uri, body)
    // Every key is checked, in constant time, whichever matches.
    var ok = false
    for (key in keys) if (Tokens.same(AdminApi.sign(key, message), signature)) ok = true
    if (!ok) refuse("bad_signature", "The signature doesn't match.")
    // A write is taken once per signature; sign each attempt afresh.
    val method = request.httpMethod.value
    if (method != "GET" && method != "HEAD" && !app.admin.firstUse(signature)) {
        refuse("stale_request", "This signed call was used already. Sign it again, with a new time.")
    }
}

/** Run [c], answering it; the status sent. [pattern] gets the route's shape, for the audit line. */
private suspend fun ApplicationCall.route(app: App, c: AdminCall, pattern: StringBuilder): Int {
    val s = c.segments
    fun named(p: String) { pattern.append(p) }
    return when {
        c.method == "POST" && s == listOf("teams") -> { named("teams"); idempotent(app, c) { createTeam(app, c) } }
        c.method == "GET" && s == listOf("teams") -> { named("teams"); listTeams(app) }
        c.method == "GET" && s.size == 2 && s[0] == "teams" -> { named("teams/{id}"); ok(200, buildJsonObject { put("team", teamJson(app, liveTeam(app, s[1]))) }) }
        c.method == "DELETE" && s.size == 2 && s[0] == "teams" -> { named("teams/{id}"); deleteTeam(app, s[1]) }
        c.method == "GET" && s.size == 3 && s[0] == "teams" && s[2] == "usage" -> { named("teams/{id}/usage"); ok(200, usage(app, openTeam(app, s[1]))) }
        c.method == "GET" && s == listOf("usage") -> { named("usage"); usageSince(app) }
        c.method == "PUT" && s.size == 3 && s[0] == "teams" && s[2] == "limits" -> { named("teams/{id}/limits"); setLimits(app, c, s[1]) }
        c.method == "PUT" && s.size == 3 && s[0] == "teams" && s[2] == "suspended" -> { named("teams/{id}/suspended"); setSuspended(app, c, s[1]) }
        c.method == "POST" && s.size == 3 && s[0] == "teams" && s[2] == "adopt" -> { named("teams/{id}/adopt"); idempotent(app, c) { adopt(app, c, s[1]) } }
        c.method == "POST" && s.size == 3 && s[0] == "teams" && s[2] == "invites" -> { named("teams/{id}/invites"); idempotent(app, c) { invite(app, c, s[1]) } }
        c.method == "POST" && s.size == 3 && s[0] == "teams" && s[2] == "setup-links" -> { named("teams/{id}/setup-links"); setupLink(app, c, s[1]) }
        c.method == "POST" && s.size == 3 && s[0] == "teams" && s[2] == "sign-in-tickets" -> { named("teams/{id}/sign-in-tickets"); ticket(app, c, s[1]) }
        c.method == "POST" && s == listOf("memberships", "lookup") -> { named("memberships/lookup"); lookup(app, c) }
        else -> throw AdminError(404, "not_found", "No such admin route.")
    }
}

private suspend fun ApplicationCall.ok(status: Int, body: JsonObject, vararg headers: Pair<String, String>): Int {
    respondJson(status, body, Wire.JSON, "Cache-Control" to "no-store", *headers)
    return status
}

// ---- idempotency ------------------------------------------------------------------------------------

/** What a first call answered, and how to make its one-time link again on a replay. */
private class Outcome(val status: Int, val body: JsonObject, val regenerate: JsonObject? = null)

/**
 * The contract's idempotency: the key and a fingerprint of the call are kept a day. The same call
 * again gets the first answer again (`Idempotent-Replayed: true`) with any one-time link freshly
 * made and the earlier one revoked, so no secret is ever kept for a replay.
 */
private suspend fun ApplicationCall.idempotent(app: App, c: AdminCall, run: suspend () -> Outcome): Int {
    val key = request.headers["Idempotency-Key"]
    if (key == null || key.isEmpty() || key.length > 255 || key.any { it.code !in 0x20..0x7e }) {
        throw bad("Idempotency-Key is required: 1 to 255 printable ASCII characters.")
    }
    val fingerprint = AdminApi.sha256Hex("${c.method}\n${request.path()}\n${AdminApi.sha256Hex(c.body)}".toByteArray())
    val db = app.db
    val now = app.clock()
    data class Held(val fingerprint: String, val state: String, val status: Int?, val body: String?, val createdAt: Long)
    fun held() = db.one("SELECT fingerprint, state, status, body, created_at FROM admin_requests WHERE idem_key = ?", key) {
        Held(it.getString(1), it.getString(2), it.getInt(3).takeIf { _ -> it.getObject(3) != null }, it.getString(4), it.getLong(5))
    }
    var existing = held()
    if (existing != null && (existing.createdAt < now - AdminApi.IDEMPOTENCY_TTL_MS || (existing.state == "running" && existing.createdAt < now - AdminApi.RUNNING_STALE_MS))) {
        db.update("DELETE FROM admin_requests WHERE idem_key = ?", key)
        existing = null
    }
    if (existing == null) {
        val claimed = runCatching {
            db.update("INSERT INTO admin_requests (idem_key, fingerprint, state, created_at) VALUES (?, ?, 'running', ?)", key, fingerprint, now)
        }.isSuccess
        if (!claimed) existing = held() ?: throw AdminError(409, "idempotency_in_progress", "A call with this Idempotency-Key is still running.")
    }
    if (existing != null) {
        if (existing.fingerprint != fingerprint) throw AdminError(422, "idempotency_key_reused", "This Idempotency-Key was used for a different call.")
        if (existing.state == "running") throw AdminError(409, "idempotency_in_progress", "A call with this Idempotency-Key is still running.")
        val stored = Wire.parseStrict(existing.body.orEmpty()) as? JsonObject ?: JsonObject(emptyMap())
        val answer = (stored["answer"] as? JsonObject) ?: JsonObject(emptyMap())
        val again = (stored["regenerate"] as? JsonObject)?.let { regenerate(app, it, answer) } ?: answer
        return ok(existing.status ?: 200, again, "Idempotent-Replayed" to "true")
    }
    val outcome = try {
        run()
    } catch (e: AdminError) {
        Outcome(e.status, Wire.error(e.code, e.message))
    } catch (e: DomainError) {
        Outcome(e.status, Wire.error(e.code, e.message))
    } catch (e: Throwable) {
        // A failure of ours: forget the key, so the same call can be tried again.
        db.update("DELETE FROM admin_requests WHERE idem_key = ?", key)
        throw e
    }
    val kept = buildJsonObject {
        put("answer", stripSecrets(outcome.body))
        outcome.regenerate?.let { put("regenerate", it) }
    }
    db.update("UPDATE admin_requests SET state = 'done', status = ?, body = ? WHERE idem_key = ?", outcome.status, Wire.encode(kept), key)
    return ok(outcome.status, outcome.body)
}

/** The answer with its one-time links taken out, for keeping. */
private fun stripSecrets(body: JsonObject): JsonObject = JsonObject(
    body.mapValues { (k, v) ->
        when {
            k == "setupUrl" || k == "url" -> JsonNull
            v is JsonObject -> stripSecrets(v)
            else -> v
        }
    },
)

/** A replayed answer with its one-time link made again, the earlier one revoked. */
private fun regenerate(app: App, how: JsonObject, answer: JsonObject): JsonObject {
    val team = (how["team"] as? JsonPrimitive)?.content ?: return answer
    val t = app.teams.find(team)?.takeIf { it.open } ?: return answer
    return Tenant.with(team) {
        when ((how["kind"] as? JsonPrimitive)?.content) {
            "setup" -> {
                val member = (how["membership"] as? JsonPrimitive)?.content ?: return@with answer
                val person = app.people.find(member)?.takeIf { it.active && !it.hasPassword } ?: return@with withOwner(answer, null, null)
                val (code, expires) = app.teams.mintCode("setup", person.id, Teams.SETUP_TTL_MS)
                withOwner(answer, "${app.teams.address(t)}/setup/$code", expires)
            }
            "invite" -> {
                val invite = (how["invite"] as? JsonPrimitive)?.content ?: return@with answer
                val (fresh, token) = app.people.reissueInvite(invite) ?: return@with answer
                JsonObject(answer + mapOf("url" to JsonPrimitive("${app.teams.address(t)}/join/$token"), "expiresAt" to JsonPrimitive(Wire.isoOf(fresh.expiresAt))))
            }
            else -> answer
        }
    }
}

private fun withOwner(answer: JsonObject, url: String?, expires: Long?): JsonObject {
    val owner = answer["owner"] as? JsonObject ?: return answer
    return JsonObject(
        answer + ("owner" to JsonObject(owner + mapOf(
            "setupUrl" to (url?.let(::JsonPrimitive) ?: JsonNull),
            "setupExpiresAt" to (expires?.let { JsonPrimitive(Wire.isoOf(it)) } ?: JsonNull),
        ))),
    )
}

// ---- teams ------------------------------------------------------------------------------------------

private fun teamJson(app: App, t: Teams.Team): JsonObject = buildJsonObject {
    put("id", t.id)
    put("slug", t.slug)
    put("name", t.name)
    put("address", app.teams.address(t))
    put("externalRef", t.externalRef?.let(::JsonPrimitive) ?: JsonNull)
    put("limits", buildJsonObject {
        put("seats", t.seatLimit?.let(::JsonPrimitive) ?: JsonNull)
        put("storageBytes", t.storageLimit?.let(::JsonPrimitive) ?: JsonNull)
    })
    put("suspended", t.suspended)
    put("suspendedReason", t.suspendedReason?.let(::JsonPrimitive) ?: JsonNull)
    put("state", t.state.wire)
    // Whether the team's first owner can sign in with a password: their setup link is used, or wasn't needed (the
    // home team's founder has the server's). Read only, no link made.
    put("ownerSetupDone", Tenant.with(t.id) { app.people.firstOwner()?.let { app.people.passwordHashOf(it) != null } ?: false })
    put("createdAt", Wire.isoOf(t.createdAt))
}

/** A team the admin API can still see: active or being deleted. */
private fun liveTeam(app: App, id: String): Teams.Team = app.teams.find(id)?.takeIf { it.state != Teams.State.DELETED } ?: throw noTeam()

/** A team still open: not being deleted. */
private fun openTeam(app: App, id: String): Teams.Team = app.teams.find(id)?.takeIf { it.open } ?: throw noTeam()

private fun createTeam(app: App, c: AdminCall): Outcome {
    val body = c.json
    val slug = c.string(body, "slug", 64)!!.lowercase()
    val name = c.string(body, "name", People.NAME_MAX)!!
    val owner = body["owner"] as? JsonObject ?: throw bad("owner is required: {email, name}.")
    val email = c.string(owner, "email", 254)!!.lowercase()
    val ownerName = c.string(owner, "name", People.NAME_MAX)!!
    if (!Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email)) throw bad("owner.email doesn't look like an email address.")
    val limits = body["limits"]?.let { it as? JsonObject ?: throw bad("limits must be an object.") }
    val seats = limits?.let { c.count(it, "seats") }
    val storage = limits?.let { c.count(it, "storageBytes") }
    val ref = c.string(body, "externalRef", Teams.EXTERNAL_REF_MAX, required = false)
    app.teams.slugProblem(slug)?.let { throw it }
    // The team and its owner together, or neither: a failure leaves no ownerless team holding the slug.
    return app.db.transaction { _ -> createTeamAndOwner(app, slug, name, ref, seats, storage, email, ownerName) }
}

private fun createTeamAndOwner(app: App, slug: String, name: String, ref: String?, seats: Long?, storage: Long?, email: String, ownerName: String): Outcome {
    val team = app.teams.create(slug, name, ref, seats, storage)
    return Tenant.with(team.id) {
        val (person, newAccount) = app.people.addOwner(email, ownerName)
        val setup = if (!person.hasPassword) app.teams.mintCode("setup", person.id, Teams.SETUP_TTL_MS) else null
        Outcome(
            201,
            buildJsonObject {
                put("team", teamJson(app, team))
                put("owner", buildJsonObject {
                    put("accountId", person.accountId)
                    put("membershipId", person.id)
                    put("email", email)
                    put("newAccount", newAccount)
                    put("setupUrl", setup?.let { JsonPrimitive("${app.teams.address(team)}/setup/${it.first}") } ?: JsonNull)
                    put("setupExpiresAt", setup?.let { JsonPrimitive(Wire.isoOf(it.second)) } ?: JsonNull)
                })
            },
            buildJsonObject { put("kind", "setup"); put("team", team.id); put("membership", person.id) },
        )
    }
}

/**
 * Take on a team the admin API didn't make (the home team of a server that ran before the control
 * plane): give it a slug, a name and the control plane's reference, and make the email the control
 * plane has proved its first owner's. Nothing moves: its id, address, rows, files, keys and tokens
 * stay as they are. Answers like a new team, with no setup link.
 */
private fun adopt(app: App, c: AdminCall, id: String): Outcome {
    openTeam(app, id)
    val body = c.json
    val slug = c.string(body, "slug", 64, required = false)?.lowercase()
    val name = c.string(body, "name", People.NAME_MAX, required = false)
    val ref = c.string(body, "externalRef", Teams.EXTERNAL_REF_MAX, required = false)
    val owner = body["owner"] as? JsonObject ?: throw bad("owner is required: {email}.")
    val email = c.string(owner, "email", 254)!!.lowercase()
    if (!Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email)) throw bad("owner.email doesn't look like an email address.")
    return app.db.transaction { _ ->
        val team = app.teams.adopt(id, slug, name, ref) ?: throw noTeam()
        Tenant.with(team.id) {
            val person = app.people.proveOwnerEmail(email)
            Outcome(
                200,
                buildJsonObject {
                    put("team", teamJson(app, team))
                    put("owner", buildJsonObject {
                        put("accountId", person.accountId)
                        put("membershipId", person.id)
                        put("email", email)
                        put("newAccount", false)
                        put("setupUrl", JsonNull)
                        put("setupExpiresAt", JsonNull)
                    })
                },
            )
        }
    }
}

private suspend fun ApplicationCall.listTeams(app: App): Int {
    val ref = request.queryParameters["externalRef"]
    val teams = if (ref != null) app.teams.byExternalRef(ref) else app.teams.all()
    return ok(200, buildJsonObject { put("teams", JsonArray(teams.map { teamJson(app, it) })) })
}

private suspend fun ApplicationCall.deleteTeam(app: App, id: String): Int {
    val team = app.teams.find(id)?.takeIf { it.state != Teams.State.DELETED } ?: throw noTeam()
    if (team.id == Tenant.HOME) throw bad("The home team can't be deleted.")
    val deleting = if (team.open) app.teams.markDeleting(id) ?: throw noTeam() else team
    return ok(202, buildJsonObject { put("team", teamJson(app, deleting)) })
}

private suspend fun ApplicationCall.setLimits(app: App, c: AdminCall, id: String): Int {
    liveTeam(app, id)
    val body = c.json
    val team = app.teams.setLimits(id, c.count(body, "seats"), c.count(body, "storageBytes")) ?: throw noTeam()
    return ok(200, buildJsonObject { put("team", teamJson(app, team)) })
}

private suspend fun ApplicationCall.setSuspended(app: App, c: AdminCall, id: String): Int {
    liveTeam(app, id)
    val body = c.json
    val suspended = (body["suspended"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
        ?: throw bad("suspended is required: true or false.")
    val reason = c.string(body, "reason", 64, required = false)
    if (reason != null && !Regex("^[A-Za-z0-9_.:-]+$").matches(reason)) throw bad("reason is a code: letters, digits, _ . : and -.")
    val message = c.string(body, "message", 280, required = false)
    val team = app.teams.setSuspended(id, suspended, reason, message) ?: throw noTeam()
    return ok(200, buildJsonObject { put("team", teamJson(app, team)) })
}

// ---- people -----------------------------------------------------------------------------------------

private fun invite(app: App, c: AdminCall, id: String): Outcome {
    val team = openTeam(app, id)
    val body = c.json
    val email = c.string(body, "email", 254)!!.lowercase()
    val role = People.Role.of(c.string(body, "role", 16)) ?: throw bad("role is owner, admin or member.")
    return Tenant.with(team.id) {
        if (app.people.findByEmail(email) != null) throw AdminError(409, "already_member", "That email is already in the team.")
        val by = app.people.firstOwner() ?: throw AdminError(409, "invalid_request", "The team has no owner to invite from.")
        val (invite, token) = try {
            app.people.invite(by, role, email)
        } catch (e: DomainError) {
            if (e.code == "conflict") throw AdminError(409, "already_member", "That email is already in the team.")
            throw e
        }
        Outcome(
            201,
            buildJsonObject {
                put("inviteId", invite.id)
                put("url", "${app.teams.address(team)}/join/$token")
                put("expiresAt", Wire.isoOf(invite.expiresAt))
            },
            buildJsonObject { put("kind", "invite"); put("team", team.id); put("invite", invite.id) },
        )
    }
}

private suspend fun ApplicationCall.setupLink(app: App, c: AdminCall, id: String): Int {
    val team = openTeam(app, id)
    val email = c.string(c.json, "email", 254)!!
    val (url, expires) = Tenant.with(team.id) {
        val person = app.people.findByEmail(email) ?: throw AdminError(404, "no_such_member", "No one in the team has that email.")
        if (person.hasPassword) throw AdminError(409, "already_set_up", "That person has set a password already.")
        val (code, expires) = app.teams.mintCode("setup", person.id, Teams.SETUP_TTL_MS)
        "${app.teams.address(team)}/setup/$code" to expires
    }
    return ok(201, buildJsonObject { put("url", url); put("expiresAt", Wire.isoOf(expires)) })
}

private suspend fun ApplicationCall.ticket(app: App, c: AdminCall, id: String): Int {
    val team = openTeam(app, id)
    val email = c.string(c.json, "email", 254)!!
    val answer = Tenant.with(team.id) {
        // Only an account whose email was proved: the control plane's code proves the person, not who typed the email in.
        val person = app.people.findByEmail(email, proved = true) ?: throw AdminError(404, "no_such_member", "No one in the team has that email.")
        // Single use and short: a retry makes a new one, and an earlier one still works until used or a minute is up.
        val (code, expires) = app.teams.mintCode("ticket", person.id, Teams.TICKET_TTL_MS, replaceEarlier = false)
        buildJsonObject {
            put("ticket", code)
            put("expiresAt", Wire.isoOf(expires))
            put("address", app.teams.address(team))
            put("membershipId", person.id)
        }
    }
    return ok(201, answer)
}

private suspend fun ApplicationCall.lookup(app: App, c: AdminCall): Int {
    val email = c.string(c.json, "email", 254)!!.lowercase()
    val rows = Tenant.allTeams {
        app.db.query(
            "SELECT m.id, m.role, t.id, t.slug, t.name, t.suspended_at FROM memberships AS m JOIN users AS u ON u.id = m.user_id " +
                "JOIN teams AS t ON t.id = m.team_id WHERE u.email = ? AND u.email_verified_at IS NOT NULL AND m.removed_at IS NULL AND t.state = 'active' ORDER BY t.seq",
            email,
        ) { rs ->
            buildJsonObject {
                put("membershipId", rs.getString(1))
                put("role", rs.getString(2))
                put("team", buildJsonObject {
                    put("id", rs.getString(3))
                    put("slug", rs.getString(4))
                    put("name", rs.getString(5))
                    put("address", app.teams.addressOf(rs.getString(3), rs.getString(4)))
                    put("suspended", rs.getObject(6) != null)
                })
            }
        }
    }
    return ok(200, buildJsonObject { put("memberships", JsonArray(rows)) })
}

// ---- usage ------------------------------------------------------------------------------------------

private fun usage(app: App, t: Teams.Team): JsonObject = Tenant.with(t.id) {
    val db = app.db
    val (people, waiting) = app.teams.seatsUsed()
    fun count(sql: String) = db.one(sql) { it.getLong(1) } ?: 0
    val activeProjects = count("SELECT count(*) FROM projects WHERE team_id = :team AND status = 'active'")
    val allProjects = count("SELECT count(*) FROM projects WHERE team_id = :team")
    buildJsonObject {
        put("teamId", t.id)
        put("seats", buildJsonObject {
            put("used", people)
            put("pendingInvites", waiting)
            put("limit", t.seatLimit?.let(::JsonPrimitive) ?: JsonNull)
        })
        put("storage", buildJsonObject {
            put("bytes", app.teams.storageUsed())
            put("limit", t.storageLimit?.let(::JsonPrimitive) ?: JsonNull)
        })
        put("agents", count("SELECT count(*) FROM agents WHERE team_id = :team AND revoked_at IS NULL"))
        put("devices", count("SELECT count(*) FROM owner_devices WHERE team_id = :team"))
        put("projects", buildJsonObject { put("active", activeProjects); put("archived", allProjects - activeProjects) })
        put("databaseBytes", databaseBytes(app))
        put("lastActivityAt", lastActivity(app)?.let { JsonPrimitive(Wire.isoOf(it)) } ?: JsonNull)
        put("suspended", t.suspended)
    }
}

/** About how much of the database is this team's: the size of its rows, table by table (Postgres); 0 on SQLite. */
private fun databaseBytes(app: App): Long {
    if (app.db.dialect != Dialect.POSTGRES) return 0
    return TeamTables.ALL.sumOf { table ->
        app.db.one("SELECT coalesce(sum(pg_column_size(x.*)), 0) FROM \"$table\" AS x WHERE x.team_id = :team") { it.getLong(1) } ?: 0
    }
}

/** When anything last happened in this team: the newest card, line, question, upload or new person. */
private fun lastActivity(app: App): Long? = listOf(
    "updates" to "created_at", "messages" to "created_at", "approvals" to "decided_at", "media" to "created_at", "memberships" to "created_at",
).mapNotNull { (table, column) ->
    app.db.one("SELECT max($column) FROM $table WHERE team_id = :team") { rs -> rs.getLong(1).takeIf { !rs.wasNull() } }
}.maxOrNull()

/** Teams changed since `since`, oldest change first, a page at a time. */
private suspend fun ApplicationCall.usageSince(app: App): Int {
    val q = request.queryParameters
    val since = q["since"]?.let { raw -> runCatching { java.time.Instant.parse(raw).toEpochMilli() }.getOrElse { throw bad("since must be an ISO 8601 time.") } } ?: 0L
    val limit = q["limit"]?.let { it.toIntOrNull()?.takeIf { n -> n in 1..500 } ?: throw bad("limit is 1 to 500.") } ?: 100
    val after = q["cursor"]?.let { raw ->
        val parts = raw.split(':', limit = 2)
        val at = parts.getOrNull(0)?.toLongOrNull()
        if (parts.size != 2 || at == null) throw bad("That cursor isn't one this server gave.")
        at to parts[1]
    }
    // Changed: its own settings, or anything in it.
    val changed = app.teams.all().filter { it.state == Teams.State.ACTIVE }.map { t ->
        val at = maxOf(t.updatedAt, t.createdAt, Tenant.with(t.id) { lastActivity(app) } ?: 0)
        Triple(at, t.id, t)
    }.filter { it.first > since }
        .sortedWith(compareBy({ it.first }, { it.second }))
        .filter { after == null || it.first > after.first || (it.first == after.first && it.second > after.second) }
    val page = changed.take(limit)
    val next = if (changed.size > limit) page.last().let { "${it.first}:${it.second}" } else null
    return ok(200, buildJsonObject {
        put("usage", JsonArray(page.map { usage(app, it.third) }))
        put("nextCursor", next?.let(::JsonPrimitive) ?: JsonNull)
    })
}
