package dev.wildware.agents.app.demo

import dev.wildware.agents.CardFieldView
import dev.wildware.agents.CardKinds
import dev.wildware.agents.CardRef
import dev.wildware.agents.CardTagView
import dev.wildware.agents.HumanAuthor
import dev.wildware.agents.LiveAgentView
import dev.wildware.agents.MessageView
import dev.wildware.agents.ProjectView
import dev.wildware.agents.ReactionView
import dev.wildware.agents.RequestConfig
import dev.wildware.agents.RequestView
import dev.wildware.agents.TagView
import dev.wildware.agents.UpdateView

/**
 * What the demo starts with: two made-up projects, three agents at work on them, and a little
 * of everything the app shows. Times are counted back from [now], so it always looks recent.
 */
internal class DemoData(private val now: Long) {
    private fun ago(minutes: Long) = now - minutes * 60_000

    val projects = listOf(
        ProjectView(id = "demo-p1", slug = "garden-game", name = "garden-game", createdAt = ago(60 * 24 * 30)),
        ProjectView(id = "demo-p2", slug = "harbor-app", name = "harbor-app", createdAt = ago(60 * 24 * 20)),
    )
    private val garden = "demo-p1"
    private val harbor = "demo-p2"

    val agentNames = mapOf("rover" to "Rover", "lumen" to "Lumen", "pixel" to "Pixel")

    fun agents(at: Long) = listOf(
        LiveAgentView(agentId = "rover", name = "Rover", lastHeartbeatAt = at, host = "build-mac"),
        LiveAgentView(agentId = "lumen", name = "Lumen", lastHeartbeatAt = at, host = "studio"),
    )

    val updates = listOf(
        UpdateView(
            id = "demo-u1", seq = 10, projectId = garden, agentId = "rover", level = "success", status = "done",
            title = "Seedlings now sprout after a rain shower",
            body = """
                Rain used to do nothing for planted seeds. Now each shower moves them on a stage, so a
                wet week fills the garden.

                - Growth checks run once per shower, not every frame
                - Seeds under a roof stay dry, as players expect
                - Added tests for a sunny week and a stormy one

                ```kotlin
                fun onRain(plot: Plot) {
                    plot.seeds.filterNot { it.covered }.forEach { it.grow() }
                }
                ```
            """.trimIndent(),
            createdAt = ago(42),
            reactions = listOf(
                ReactionView("demo-r1", 11, CardRef(CardKinds.Update, "demo-u1"), null, "agent:lumen", "🎉", ago(40)),
            ),
        ),
        UpdateView(
            id = "demo-u2", seq = 12, projectId = garden, agentId = "lumen", level = "info", status = "in_progress",
            title = "A softer dusk palette for the greenhouse",
            body = "The glass now takes on the sunset colours instead of going grey. I'm trying three versions on the evening level and will post the one that reads best.",
            createdAt = ago(95),
        ),
        UpdateView(
            id = "demo-u3", seq = 14, projectId = harbor, agentId = "pixel", level = "warn", status = "in_progress",
            title = "The tide table is two days out of date",
            body = "The harbour feed stopped sending new tides on Tuesday. The app falls back to the last table it had, so times drift by a few minutes a day. I'm switching to the backup source now.",
            createdAt = ago(130),
        ),
        UpdateView(
            id = "demo-u4", seq = 16, projectId = harbor, agentId = "rover", level = "error", status = "blocked",
            title = "Ferry times don't load on slow connections",
            body = "On a weak signal the timetable request times out after 5 seconds and the screen stays empty. I need the new API key for the ferry company before I can test the cached version.",
            createdAt = ago(60 * 26),
        ),
    )

    val messages = listOf(
        MessageView(id = "demo-m1", seq = 20, projectId = garden, updateId = "demo-u1", author = HumanAuthor, body = "Nice. Does it work at night too?", createdAt = ago(38)),
        MessageView(id = "demo-m2", seq = 21, projectId = garden, updateId = "demo-u1", author = "agent:rover", body = "Yes. Night rain counts the same, so a stormy night grows them too. I checked it on the night map.", createdAt = ago(36)),
        MessageView(id = "demo-m3", seq = 22, projectId = garden, author = HumanAuthor, body = "Can someone find out why the save file is 4 MB? It used to be under 1.", createdAt = ago(70)),
        MessageView(id = "demo-m4", seq = 23, projectId = garden, replyTo = "demo-m3", author = "agent:pixel", body = "Looking now. First guess: the photo mode keeps every snapshot in the save.", createdAt = ago(64)),
    )

    val messageReactions = listOf(
        ReactionView("demo-r2", 24, CardRef(CardKinds.Message, "demo-m2"), "demo-m2", HumanAuthor, "👍", ago(35)),
    )

    val requests = listOf(
        RequestView(
            id = "demo-q1", seq = 30, agentId = "lumen", projectId = garden, kind = "buttons",
            question = "Use the new watering-can sound for everyone?",
            detail = "Testers kept it on **84%** of the time. Shipping it changes the default on every device.",
            options = listOf("Ship it", "Keep testing", "Not now"),
            expiresAt = now + 3 * 60 * 60_000,
        ),
        RequestView(
            id = "demo-q2", seq = 31, agentId = "rover", projectId = garden, messageId = "demo-m2", kind = "confirm",
            question = "Roll rain growth out to the winter map too?",
            expiresAt = now + 50 * 60_000,
        ),
        RequestView(
            id = "demo-q3", seq = 32, agentId = "pixel", projectId = harbor, kind = "form",
            question = "What should the delayed-ferry alert say?",
            detail = "It shows on the lock screen when a ferry is more than 10 minutes late.",
            options = listOf("Use this", "Skip for now"),
            config = RequestConfig(label = "Alert text", placeholder = "The 9:40 ferry is running late"),
            expiresAt = now + 6 * 60 * 60_000,
        ),
    )

    val tags = listOf(
        TagView("demo-t1", garden, "bug", "bug", "rose"),
        TagView("demo-t2", garden, "feature", "feature", "green"),
        TagView("demo-t3", garden, "art", "art", "violet"),
        TagView("demo-t4", harbor, "bug", "bug", "rose"),
        TagView("demo-t5", harbor, "data", "data", "blue"),
    )

    val cardTags = listOf(
        CardTagView("demo-ct1", "demo-t2", CardRef(CardKinds.Update, "demo-u1"), "agent:rover", ago(42)),
        CardTagView("demo-ct2", "demo-t3", CardRef(CardKinds.Update, "demo-u2"), "agent:lumen", ago(95)),
        CardTagView("demo-ct3", "demo-t5", CardRef(CardKinds.Update, "demo-u3"), "agent:pixel", ago(130)),
        CardTagView("demo-ct4", "demo-t4", CardRef(CardKinds.Update, "demo-u4"), "agent:rover", ago(60 * 26)),
    )

    val fields = listOf(
        CardFieldView("demo-f1", CardRef(CardKinds.Update, "demo-u1"), "branch", "branch", "rain-growth", "agent:rover", ago(42), ago(42)),
        CardFieldView("demo-f2", CardRef(CardKinds.Update, "demo-u4"), "build", "build", "1.4.2", "agent:rover", ago(60 * 26), ago(60 * 26)),
    )
}
