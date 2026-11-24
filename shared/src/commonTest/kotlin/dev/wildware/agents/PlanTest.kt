package dev.wildware.agents

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A hosted team's plan (`GET /api/plan`) and the free plan's refusals (`plan_limit`). */
class PlanTest {
    private val urls = mutableListOf<String>()

    private fun client(status: HttpStatusCode = HttpStatusCode.OK, body: String) =
        DashboardClient("https://dash.test/", "device-key", MockEngine { request ->
            urls += request.url.toString()
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        })

    private val freeBody = """{"plan":"free","limits":{"people":2,"agents":3,"storageBytes":524288000,"historyDays":30},
        "used":{"people":1,"agents":2,"storageBytes":125829120},"upgradeUrl":"https://billing.test/teams/acme"}"""

    @Test
    fun `a free team reads its limits and use`() = runTest {
        val plan = client(body = freeBody).plan()!!
        assertEquals("https://dash.test/api/plan", urls.single())
        assertTrue(plan.free)
        assertEquals("Free plan", plan.title)
        assertEquals(listOf("People 1 of 2", "Agents 2 of 3", "Files 120 MB of 500 MB", "History: last 30 days"), plan.lines)
        assertEquals("https://billing.test/teams/acme", plan.upgradeUrl)
    }

    @Test
    fun `a team on the Team plan shows only its name`() = runTest {
        val plan = client(body = """{"plan":"team","limits":{"people":5,"agents":null,"storageBytes":null,"historyDays":null},"used":{"people":3,"agents":9,"storageBytes":0},"upgradeUrl":null}""").plan()!!
        assertEquals("Team plan", plan.title)
        assertEquals(emptyList(), plan.lines)
    }

    @Test
    fun `a self-hosted dashboard or an older one has no plan`() = runTest {
        assertNull(client(body = """{"plan":null}""").plan())
        assertNull(client(HttpStatusCode.NotFound, """{"error":"not_found"}""").plan())
    }

    @Test
    fun `a free plan refusal says which limit and where to upgrade`() = runTest {
        val c = client(HttpStatusCode.Conflict, """{"error":"plan_limit","message":"The free plan has room for 2 people.","limit":"people","upgradeUrl":"https://billing.test/teams/acme"}""")
        val heard = mutableListOf<PlanLimitHit>()
        c.onPlanLimit = { heard += it }
        val refused = assertFailsWith<DashboardException> { c.createInvite("member", "sam@example.test") }
        assertEquals("plan_limit", refused.code)
        assertEquals("people", refused.limit)
        assertEquals("https://billing.test/teams/acme", refused.upgradeUrl)
        assertEquals(listOf(PlanLimitHit("The free plan has room for 2 people.", "people", "https://billing.test/teams/acme")), heard)
        assertEquals(heard.single(), PlanLimitHit.of(refused))
    }

    @Test
    fun `other refusals are not plan limits`() = runTest {
        val c = client(HttpStatusCode.Conflict, """{"error":"seat_limit","message":"No free logins left."}""")
        val heard = mutableListOf<PlanLimitHit>()
        c.onPlanLimit = { heard += it }
        val refused = assertFailsWith<DashboardException> { c.createInvite("member", null) }
        assertNull(PlanLimitHit.of(refused))
        assertNull(PlanLimitHit.of(IllegalStateException("no")))
        assertEquals(emptyList(), heard)
    }

    @Test
    fun `sizes show in whole megabytes`() {
        assertEquals("0 MB", TeamPlan.megabytes(0))
        assertEquals("1 MB", TeamPlan.megabytes(10))
        assertEquals("500 MB", TeamPlan.megabytes(524_288_000))
    }
}
