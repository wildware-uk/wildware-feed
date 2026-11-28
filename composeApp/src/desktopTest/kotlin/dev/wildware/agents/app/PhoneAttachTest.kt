package dev.wildware.agents.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FilePicker
import dev.wildware.agents.app.feed.LocalDocumentPicker
import dev.wildware.agents.app.feed.LocalFilePicker
import dev.wildware.agents.app.feed.PendingFile
import kotlin.test.Test
import kotlin.test.assertEquals

/** A phone has two pickers, its photos and its files: the paperclip asks which, and any file can go. */
@OptIn(ExperimentalTestApi::class)
class PhoneAttachTest {
    private val events = mutableListOf<FeedEvent>()

    private fun androidx.compose.ui.test.ComposeUiTest.phone(photos: FilePicker, files: FilePicker) = setContent {
        CompositionLocalProvider(LocalFilePicker provides photos, LocalDocumentPicker provides files) {
            AgentTheme(dark = true) { FeedScreen(FakeFeed.state.copy(openThread = null), onEvent = { events += it }) }
        }
    }

    @Test
    fun theFilesChoiceAttachesAnyFileAndItGoesWithTheMessage() = runDesktopComposeUiTest(1440, 900) {
        val pdf = PendingFile("report.pdf", "application/pdf", byteArrayOf(0x25, 0x50, 0x44, 0x46))
        val zip = PendingFile("logs.zip", "application/zip", byteArrayOf(0x50, 0x4B, 3, 4))
        var photosOpened = 0
        phone(photos = { photosOpened++; it(emptyList()) }, files = { it(listOf(pdf, zip)) })
        onNodeWithContentDescription("Attach files").performClick()
        onNodeWithText("Photos and videos").assertExists()
        onNodeWithText("Files").performClick()
        onNodeWithContentDescription("Attached: report.pdf").assertExists()
        onNodeWithContentDescription("Attached: logs.zip").assertExists()
        assertEquals(0, photosOpened)
        onNodeWithContentDescription("Send").performClick()
        val post = events.filterIsInstance<FeedEvent.Post>().single()
        assertEquals(listOf("report.pdf" to "application/pdf", "logs.zip" to "application/zip"), post.files.map { it.name to it.mimeType })
    }

    @Test
    fun aFileTooBigForTheDashboardIsLeftOutWithAReason() = runDesktopComposeUiTest(1440, 900) {
        val big = PendingFile("scan.png", "image/png", ByteArray(10 * 1024 * 1024 + 1))
        val empty = PendingFile("blank.txt", "text/plain", ByteArray(0))
        val log = PendingFile("build.log", "text/plain", "slow".encodeToByteArray())
        phone(photos = { it(listOf(big)) }, files = { it(listOf(empty, log)) })
        onNodeWithContentDescription("Attach files").performClick()
        onNodeWithText("Photos and videos").performClick()
        onNodeWithText("scan.png is over 10 MB, so it was left out.").assertExists()
        onNodeWithContentDescription("Attached: scan.png").assertDoesNotExist()
        onNodeWithContentDescription("Attach files").performClick()
        onNodeWithText("Files").performClick()
        onNodeWithText("blank.txt is empty, so it was left out.").assertExists()
        onNodeWithContentDescription("Attached: build.log").assertExists()
    }
}
