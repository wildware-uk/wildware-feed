package dev.wildware.agents.app

import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedFilter
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.WorkStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The work filters: what each keeps, matching the website's chips. */
class WorkFilterTest {
    private val update = FakeFeed.state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Update>().first()
    private val post = FakeFeed.state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Post>().first()
    private val question = FakeFeed.state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Question>().first()

    private fun update(status: WorkStatus?) = update.copy(status = status)
    private fun post(status: WorkStatus?) = post.copy(status = status)

    @Test
    fun openWorkIsEverythingNotFinished() {
        for (status in listOf(WorkStatus.Todo, WorkStatus.InProgress, WorkStatus.Blocked)) {
            assertTrue(FeedFilter.OpenWork.matches(update(status)), "$status update")
            assertTrue(FeedFilter.OpenWork.matches(post(status)), "$status post")
        }
        for (status in listOf(WorkStatus.Done, WorkStatus.Cancelled, null)) {
            assertFalse(FeedFilter.OpenWork.matches(update(status)), "$status update")
        }
        assertFalse(FeedFilter.OpenWork.matches(question))
    }

    @Test
    fun eachStateKeepsOnlyItsOwn() {
        assertTrue(FeedFilter.Todo.matches(post(WorkStatus.Todo)))
        assertFalse(FeedFilter.Todo.matches(post(WorkStatus.InProgress)))
        assertTrue(FeedFilter.InProgress.matches(update(WorkStatus.InProgress)))
        assertTrue(FeedFilter.Blocked.matches(update(WorkStatus.Blocked)))
        assertTrue(FeedFilter.Done.matches(update(WorkStatus.Done)))
        assertFalse(FeedFilter.Done.matches(update(null)))
    }

    @Test
    fun theChipsComeInTheWebsitesOrder() {
        assertEquals(
            listOf("All", "Waiting", "Updates", "Errors", "From you", "Open work", "To do", "In progress", "Blocked", "Done"),
            FeedFilter.entries.map { it.label },
        )
    }
}
