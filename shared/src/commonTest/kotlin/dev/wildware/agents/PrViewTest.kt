package dev.wildware.agents

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A pull request's chip on a card (server migration 079): its words, its colour, and whether it needs someone. */
class PrViewTest {
    private fun pr(state: String? = "open", ci: String = "none", review: String = "none", changes: Int = 0) =
        PrView(repo = "acme/app", number = 423, url = "https://github.com/acme/app/pull/423", state = state, ci = ci, review = review, changesRequested = changes)

    @Test
    fun chipStates() {
        assertEquals("#423", pr().label)
        assertEquals(PrTone.Plain, pr().tone)
        assertEquals("#423 · CI running", pr(ci = "pending").label)
        assertEquals(PrTone.Waiting, pr(ci = "pending").tone)
        assertEquals("#423 · CI passing · Approved", pr(ci = "passing", review = "approved").label)
        assertEquals(PrTone.Good, pr(ci = "passing", review = "approved").tone)
        assertEquals("#423 · CI failing · 1 change requested", pr(ci = "failing", review = "changes_requested", changes = 1).label)
        assertEquals(PrTone.Bad, pr(ci = "failing").tone)
        assertEquals("#423 · 2 changes requested", pr(review = "changes_requested", changes = 2).label)
        assertEquals("#423 · Draft · CI running", pr(state = "draft", ci = "pending").label)
        assertEquals("#423 · Merged", pr(state = "merged", ci = "failing").label)
        assertEquals(PrTone.Merged, pr(state = "merged", ci = "failing").tone)
        assertEquals("#423 · Closed", pr(state = "closed").label)
        assertEquals(PrTone.Closed, pr(state = "closed").tone)
        assertEquals("Pull request 423 in acme/app: CI failing, 1 change requested. Opens on GitHub.", pr(ci = "failing", changes = 1, review = "changes_requested").spoken)
        assertEquals("Pull request 423 in acme/app. Opens on GitHub.", pr().spoken)
    }

    @Test
    fun needsAttentionOnlyWhileOpen() {
        assertTrue(pr(ci = "failing").needsAttention)
        assertTrue(pr(state = null, review = "changes_requested", changes = 1).needsAttention)
        assertTrue(pr(state = "draft", ci = "failing").needsAttention)
        assertFalse(pr(ci = "passing").needsAttention)
        assertFalse(pr(state = "merged", ci = "failing").needsAttention)
        assertFalse(pr(state = "closed", review = "changes_requested").needsAttention)
    }

    @Test
    fun readsWhatTheServerSends() {
        val json = Json { ignoreUnknownKeys = true }
        val view = json.decodeFromString(
            UpdateView.serializer(),
            """{"id":"u1","seq":1,"projectId":"p","agentId":"a","body":"b","createdAt":1,
               "prs":[{"repo":"acme/app","number":42,"url":"https://github.com/acme/app/pull/42","state":"open","ci":"failing",
                       "failing":[{"name":"build (android)","url":"https://github.com/acme/app/runs/1"}],"review":"none","changesRequested":0,"owner":"agent:a","ownerName":"rover"}]}""",
        )
        val p = view.prs.single()
        assertEquals("#42 · CI failing", p.label)
        assertEquals("build (android)", p.failing.single().name)
        // A server from before PRs sends none.
        assertEquals(emptyList(), json.decodeFromString(UpdateView.serializer(), """{"id":"u1","seq":1,"projectId":"p","agentId":"a","body":"b","createdAt":1}""").prs)
    }
}
