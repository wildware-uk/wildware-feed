package dev.wildware.feed.parity

import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The live streams. Spec: server/spec/04-stream-and-events.md (numbers refer to its parity notes). */
class StreamTest : ParityTest() {
    private val ownerHeaders: io.ktor.client.request.HttpRequestBuilder.() -> Unit get() = owner.auth

    private suspend fun lastSeq() = owner.get("/api/snapshot?limit=1").obj()["seq"].long

    private fun agentStream(agent: Agent) = { b: io.ktor.client.request.HttpRequestBuilder -> b.bearer(agent.token) }

    @Test
    fun `1 and 2 the owner stream wants a session, then opens with retry and a comment`() = parity {
        val refused = call(HttpMethod.Get, "/api/stream")
        assertEquals(401, refused.status.value)
        assertEquals("""{"error":"unauthenticated"}""", refused.bodyAsText())

        withStream("/api/stream", { ownerHeaders(); header(HttpHeaders.AcceptEncoding, "gzip, br") }) { stream ->
            val headers = stream.response.headers
            assertEquals(200, stream.response.status.value)
            assertEquals("text/event-stream; charset=utf-8", headers[HttpHeaders.ContentType])
            assertEquals("no-cache, no-store, no-transform", headers[HttpHeaders.CacheControl])
            assertEquals("no", headers["x-accel-buffering"])
            assertNull(headers[HttpHeaders.ContentEncoding])
            assertNull(headers[HttpHeaders.ContentLength])
            val first = stream.await(what = "the retry frame") { true }
            assertEquals("2000", first.retry)
            assertEquals("connected", stream.await(what = "the connected comment") { true }.comment)
        }

        // A device key opens it too.
        val device = Device.signIn()
        withStream("/api/stream", { bearer(device.token) }) { assertEquals(200, it.response.status.value) }
    }

    @Test
    fun `3 an idle stream gets a heartbeat comment every 15 seconds`() = parity {
        withStream("/api/stream", ownerHeaders) { stream ->
            val beat = stream.await(timeout = 20.seconds, what = "a heartbeat") { it.comment?.startsWith("heartbeat ") == true }
            assertTrue(Regex("^heartbeat \\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z$").matches(beat.comment!!), beat.comment)
        }
    }

    @Test
    fun `4 and 5 events carry the whole envelope, and each update brings a notification`() = parity {
        withStream("/api/stream", ownerHeaders) { stream ->
            val name = uniq("Streamed")
            val project = alpha.ok("create_project", obj("name" to name))["project"].o
            val created = stream.awaitEvent("project.created") { it.obj["payload"]["projectId"].s == project["id"].s }
            val envelope = created.obj
            assertEquals(listOf("type", "seq", "at", "payload"), envelope.keys.toList())
            assertEquals("project.created", envelope["type"].s)
            assertEquals(created.seq, envelope["seq"].long)
            assertEquals(obj("projectId" to project["id"].s, "slug" to project["slug"].s), envelope["payload"])

            alpha.ok("create_project", obj("name" to name))
            val update = alpha.ok("post_update", obj("project" to project["slug"].s, "body" to "hi"))["update"]["id"].s
            val frames = mutableListOf<SseFrame>()
            frames += stream.awaitEvent("update.created") { it.obj["payload"]["updateId"].s == update }
            if (stream.seen.none { it.event == "notification.created" && it.obj["payload"]["projectId"].str == project["id"].s }) {
                stream.awaitEvent("notification.created") { it.obj["payload"]["projectId"].str == project["id"].s }
            }
            val sinceCreate = stream.seen.dropWhile { it !== created }
            assertTrue(sinceCreate.none { it.event == "project.created" && it !== created }, "a repeat create_project publishes nothing")
            val notification = sinceCreate.firstOrNull { it.event == "notification.created" && it.obj["payload"]["projectId"].str == project["id"].s }
            val updateFrame = frames.single()
            assertTrue(notification != null, "a notification was published for the update")
            assertEquals(updateFrame.seq!! + 1, notification.seq, "the notification is the next seq")
            assertEquals(listOf("updateId", "projectId", "agentId"), updateFrame.obj["payload"].o.keys.toList())
            assertTrue(sinceCreate.indexOf(updateFrame) < sinceCreate.indexOf(notification), "the update (seq N) comes before its notification (N+1)")
        }
    }

    @Test
    fun `6 and 7 a reconnect replays what it missed, by header or by query`() = parity {
        val project = Fixtures.project()
        val update = Fixtures.update(project)["id"].s
        val from = lastSeq()
        repeat(3) { owner.patch("/api/updates/$update", obj("pinned" to (it % 2 == 0))) }

        suspend fun replayed(configure: io.ktor.client.request.HttpRequestBuilder.() -> Unit, path: String = "/api/stream"): List<Long> =
            withStream(path, { ownerHeaders(); configure() }) { stream ->
                stream.drain(1.5.seconds).filter { it.event != null && it.event != "hello" }.map { it.seq!! }
            }

        val byHeader = replayed({ header("Last-Event-ID", from.toString()) })
        assertEquals((from + 1..from + 3).toList(), byHeader.take(3))
        assertEquals((from + 1..from + 3).toList(), replayed({}, "/api/stream?last_event_id=$from").take(3))
        assertEquals((from + 1..from + 3).toList(), replayed({ header("Last-Event-ID", "abc") }, "/api/stream?last_event_id=$from").take(3), "garbage header: the query is used")
        assertEquals((from + 2..from + 3).toList(), replayed({ header("Last-Event-ID", (from + 1).toString()) }, "/api/stream?last_event_id=$from").take(2), "a valid header wins")

        val now = lastSeq()
        assertEquals(emptyList(), replayed({ header("Last-Event-ID", now.toString()) }))
    }

    @Test
    fun `9 a cursor older than the ring gets one resync frame`() = parity {
        val project = Fixtures.project()
        val update = Fixtures.update(project)["id"].s
        val old = lastSeq()
        repeat(505) { owner.patch("/api/updates/$update", obj("pinned" to (it % 2 == 0))) }
        val last = lastSeq()
        withStream("/api/stream", { ownerHeaders(); header("Last-Event-ID", old.toString()) }) { stream ->
            val resync = stream.awaitEvent("resync")
            assertEquals(last, resync.seq)
            val body = resync.obj
            assertEquals(listOf("type", "reason", "from", "seq", "boot"), body.keys.toList())
            assertEquals(resync.boot, body["boot"].s)
            assertEquals("expired", body["reason"].s)
            assertEquals(old, body["from"].long)
            assertEquals(last, body["seq"].long)
        }
        withStream("/api/stream", { ownerHeaders(); header("Last-Event-ID", (last - 500).toString()) }) { stream ->
            val frames = stream.drain(2.seconds).filter { it.event != null && it.event != "hello" }
            assertTrue(frames.none { it.event == "resync" }, "lastSeq - 500 is still in the ring")
            assertEquals(last - 499, frames.first().seq)
        }
        // A cursor from the future (as after a restart) is "ahead".
        withStream("/api/stream", { ownerHeaders(); header("Last-Event-ID", (last + 1_000_000).toString()) }) { stream ->
            assertEquals("ahead", stream.awaitEvent("resync").obj["reason"].s)
        }
    }

    @Test
    fun `12 presence is announced on transitions only`() = parity {
        val agent = Parity.mintAgent(uniq("presence"))
        withStream("/api/stream", ownerHeaders) { stream ->
            val first = agent.ok("register_session")["session_id"].s
            val online = stream.awaitEvent("agent.presence") { it.obj["payload"]["sessionId"].str == first }
            assertEquals(true, online.obj["payload"]["online"].bool)
            assertEquals(listOf("agentId", "sessionId", "online"), online.obj["payload"].o.keys.toList())
            val agentId = online.obj["payload"]["agentId"].s

            val second = agent.ok("register_session")["session_id"].s
            agent.ok("end_session", obj("session_id" to first))
            assertTrue(stream.drain(1.seconds).none { it.event == "agent.presence" && it.obj["payload"]["agentId"].s == agentId }, "still online: nothing")
            agent.ok("end_session", obj("session_id" to second))
            val offline = stream.awaitEvent("agent.presence") { it.obj["payload"]["agentId"].s == agentId }
            assertEquals(false, offline.obj["payload"]["online"].bool)
            assertEquals(second, offline.obj["payload"]["sessionId"].s)
        }
    }

    @Test
    fun `26 the snapshot seq matches the stream`() = parity {
        withStream("/api/stream", ownerHeaders) { stream ->
            val project = alpha.ok("create_project", obj("name" to uniq("Seq")))["project"]["id"].s
            val frame = stream.awaitEvent("project.created") { it.obj["payload"]["projectId"].s == project }
            stream.drain(0.5.seconds)
            val snapshot = owner.get("/api/snapshot?limit=1")
            assertEquals("application/json; charset=utf-8", snapshot.headers[HttpHeaders.ContentType])
            assertEquals("no-store", snapshot.headers[HttpHeaders.CacheControl])
            val lastId = (stream.seen.lastOrNull { it.id != null } ?: frame).seq!!
            assertEquals(lastId, snapshot.obj()["seq"].long)
            assertTrue(snapshot.bodyAsText().startsWith("{\"seq\":"))
        }
    }

    @Test
    fun `every connection names the server's boot, and a cursor from another boot gets resync`() = parity {
        withStream("/api/stream", ownerHeaders) { stream ->
            val hello = stream.awaitEvent("hello")
            val boot = hello.obj["boot"].s
            assertEquals("$boot-${hello.obj["seq"].long}", hello.id)
            alpha.ok("create_project", obj("name" to uniq("Boot")))
            val event = stream.awaitEvent("project.created")
            assertEquals(boot, event.boot, "ids are <boot>-<seq>")
        }
        withStream("/api/stream", { ownerHeaders(); header("Last-Event-ID", "someotherboot-5") }) { stream ->
            val resync = stream.awaitEvent("resync")
            assertEquals("restarted", resync.obj["reason"].s)
            assertTrue(resync.boot != null && resync.boot != "someotherboot", "the resync carries this boot's id")
        }
    }

    @Test
    fun `revoking a device key or agent token closes its open stream`() = parity {
        val device = Device.signIn()
        withStream("/api/stream", { bearer(device.token) }) { stream ->
            stream.await { it.comment == "connected" }
            owner.delete("/api/devices/${device.id}")
            stream.awaitClosed(10.seconds)
        }
        val agent = Parity.mintAgent(uniq("revoked"))
        val id = agent.ok("post_update", obj("project" to Fixtures.project(agent).slug, "body" to "x"))["update"]["agent_id"].s
        withStream("/api/agent/stream?project=*", { bearer(agent.token) }) { stream ->
            stream.awaitEvent("work")
            Parity.revokeAgent(id)
            stream.awaitClosed(10.seconds)
        }
    }

    // ── Agent stream ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `14 and 15 the agent stream refuses bad tokens and unknown projects`() = parity {
        assertEquals(401, call(HttpMethod.Get, "/api/agent/stream") { header(HttpHeaders.Authorization, "Basic x") }.let {
            assertEquals("unsupported_scheme", it.obj()["error"].s); it.status.value
        })
        assertEquals("malformed_token", call(HttpMethod.Get, "/api/agent/stream") { bearer("short") }.obj()["error"].s)

        // The Kotlin server decides what an agent hears on its own and ignores a ?project= list, so it
        // refuses none (migration 060: the owner adds and removes agents, live).
        if (!Parity.kotlin) {
            val notFound = call(HttpMethod.Get, "/api/agent/stream?project=nope-zz") { bearer(alpha.token) }
            assertEquals(404, notFound.status.value)
            assertEquals("""{"error":"not_found","message":"no such project: nope-zz"}""", notFound.bodyAsText())
            val mixed = call(HttpMethod.Get, "/api/agent/stream?project=*,a") { bearer(alpha.token) }
            assertEquals(400, mixed.status.value)
            assertEquals("invalid_argument", mixed.obj()["error"].s)
        }
    }

    @Test
    fun `17 to 20 messages are handed to each client once, and reading clears the count`() = parity {
        val agent = Parity.mintAgent(uniq("listener"))
        val project = Fixtures.project(agent)
        val update = Fixtures.update(project, agent)["id"].s
        val client = "c1-${Parity.suffix()}"
        val path = "/api/agent/stream?project=${project.slug}&client=$client"

        withStream(path, agentStream(agent)) { stream ->
            assertEquals("2000", stream.await { true }.retry)
            assertEquals("connected", stream.await { true }.comment)
            val work = stream.awaitEvent("work")
            assertNull(work.id, "agent frames carry no id")
            assertEquals(listOf("type", "unread_messages", "open_tasks", "pending_approvals", "at"), work.obj.keys.toList())
            assertEquals(0, work.obj["unread_messages"].int)

            withStream("/api/stream", ownerHeaders) { owners ->
                owner.post("/api/messages", obj("update" to update, "body" to "for the agent"))
                assertEquals(1, stream.awaitEvent("work").obj["unread_messages"].int)
                val message = stream.awaitEvent("message").obj["messages"][0].o
                assertEquals(
                    listOf("message_id", "project_id", "project", "project_name", "update_id", "task_id", "reply_to", "answers", "in_reply_to", "request_id", "author", "author_name", "body", "created_at", "media"),
                    message.keys.toList(),
                )
                assertEquals("for the agent", message["body"].s)
                assertEquals("human", message["author"].s)
                assertTrue(message["author_name"].isNull)
                assertEquals(project.slug, message["project"].s)
                owners.awaitEvent("message.delivered") { it.obj["payload"]["messageId"].s == message["message_id"].s }
            }
        }

        // The same client again: counted, but not handed over twice.
        withStream(path, agentStream(agent)) { stream ->
            assertEquals(1, stream.awaitEvent("work").obj["unread_messages"].int)
            assertTrue(stream.drain(1.seconds).none { it.event == "message" })
        }
        // Another client: handed over again.
        withStream("/api/agent/stream?project=${project.slug}&client=c2-${Parity.suffix()}", agentStream(agent)) { stream ->
            stream.awaitEvent("message")
            agent.ok("get_messages")
            assertEquals(0, stream.awaitEvent("work") { it.obj["unread_messages"].int == 0 }.obj["unread_messages"].int)
        }
    }

    @Test
    fun `21 other projects are silent unless the agent is named`() = parity {
        val agent = Parity.mintAgent(uniq("scoped"))
        val mine = Fixtures.project(agent)
        val other = Fixtures.project(beta)
        // The Kotlin server ignores the ?project= list: the agent says what it follows instead.
        if (Parity.kotlin) agent.ok("subscribe", obj("channel" to mine.slug))
        withStream("/api/agent/stream?project=${mine.slug}", agentStream(agent)) { stream ->
            stream.awaitEvent("work")
            owner.post("/api/messages", obj("project" to other.slug, "body" to "not for you"))
            assertTrue(stream.drain(1.5.seconds).none { it.event == "message" })
            owner.post("/api/messages", obj("project" to other.slug, "body" to "hey @${agent.name}"))
            assertEquals("hey @${agent.name}", stream.awaitEvent("message").obj["messages"][0]["body"].s)
        }
    }

    @Test
    fun `22 an answer is a frame of its own, a withdrawal is only a count`() = parity {
        val agent = Parity.mintAgent(uniq("asker"))
        val project = Fixtures.project(agent)
        withStream("/api/agent/stream?project=${project.slug}", agentStream(agent)) { stream ->
            stream.awaitEvent("work")
            coroutineScope {
                val question = "Go? ${Parity.suffix()}"
                val held = async { agent.tool("request_input", obj("kind" to "confirm", "question" to question, "project" to project.slug)) }
                assertEquals(1, stream.awaitEvent("work") { it.obj["pending_approvals"].int == 1 }.obj["pending_approvals"].int)
                val id = awaitCreated(agent, held, "request '$question' to be pending") {
                    owner.get("/api/snapshot/requests").obj()["requests"].arr.firstOrNull { it["question"].s == question }?.get("id").str
                }
                owner.post("/api/requests/$id/answer", obj("value" to true))
                val answer = stream.awaitEvent("answer").obj
                assertEquals(
                    listOf("type", "request_id", "state", "kind", "question", "answer", "project", "project_name", "update_id", "answered_at"),
                    answer.keys.toList(),
                )
                assertEquals("answered", answer["state"].s)
                assertEquals(true, answer["answer"].bool)
                assertEquals(0, stream.awaitEvent("work").obj["pending_approvals"].int)
                held.await()
            }
        }
    }

    @Test
    fun `23 the owner's reaction on the agent's line reaches it`() = parity {
        val agent = Parity.mintAgent(uniq("reacted"))
        val project = Fixtures.project(agent)
        val line = agent.ok("post_message", obj("project" to project.slug, "body" to "  did   the thing  "))["message"]["id"].s
        withStream("/api/agent/stream?project=${project.slug}", agentStream(agent)) { stream ->
            stream.awaitEvent("work")
            owner.post("/api/messages/$line/reactions", obj("emoji" to "👍", "on" to true))
            val on = stream.awaitEvent("reaction").obj
            assertEquals(
                listOf("type", "message_id", "emoji", "on", "project", "project_name", "update_id", "task_id", "body", "author", "author_name", "mine"),
                on.keys.toList(),
            )
            assertEquals(true, on["on"].bool)
            // Who wrote the line reacted on, so the bridge never calls another agent's words "yours".
            assertEquals(true, on["mine"].bool)
            assertTrue(on["author"].s.startsWith("agent:"))
            assertEquals("did the thing", on["body"].s)
            owner.post("/api/messages/$line/reactions", obj("emoji" to "👍", "on" to false))
            assertEquals(false, stream.awaitEvent("reaction").obj["on"].bool)
            beta.ok("react", obj("message_id" to line, "emoji" to "👀"))
            assertTrue(stream.drain(1.seconds).none { it.event == "reaction" }, "another agent's reaction is not news")
        }
    }

    @Test
    fun `24 a card marked as work raises open_tasks, a pin changes nothing`() = parity {
        val agent = Parity.mintAgent(uniq("worker"))
        val project = Fixtures.project(agent)
        val update = Fixtures.update(project, agent)["id"].s
        withStream("/api/agent/stream?project=${project.slug}", agentStream(agent)) { stream ->
            assertEquals(0, stream.awaitEvent("work").obj["open_tasks"].int)
            owner.post("/api/cards/update/$update/status", obj("status" to "todo"))
            assertEquals(1, stream.awaitEvent("work").obj["open_tasks"].int)
            owner.post("/api/cards/update/$update/pin", obj("pinned" to true))
            assertTrue(stream.drain(1.seconds).none { it.event == "work" })
        }
    }

    @Test
    fun `25 the agent stream has a heartbeat too`() = parity {
        val project = Fixtures.project()
        withStream("/api/agent/stream?project=${project.slug}", agentStream(alpha)) { stream ->
            stream.await(timeout = 20.seconds, what = "a heartbeat") { it.comment?.startsWith("heartbeat ") == true }
        }
    }
}
