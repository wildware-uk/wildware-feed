package dev.wildware.agents

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonElement

/** What a queued item does once it reaches the dashboard. */
@Serializable
enum class OutboxKind {
    /** A post or reply: [OutboxItem.thread] says where, with any photos. */
    Message,

    /** A new card marked as work in [OutboxItem.thread]'s project. */
    Ticket,

    /** An answer to a question an agent asked, tapped on an alert. */
    Answer,

    /** An answer given in the app itself: [OutboxItem.value] is the answer as JSON, sent as it is. */
    RequestAnswer,

    /** Lets the agent go without an answer. [OutboxItem.card] is the request. */
    Dismiss,

    /** Puts [OutboxItem.emoji] on [OutboxItem.card], or takes it off when [OutboxItem.on] is false. */
    React,

    /** Deletes [OutboxItem.card]: a message or an update. */
    Delete,

    /**
     * Puts a tag on [OutboxItem.card], or takes it off when [OutboxItem.on] is false. [OutboxItem.value]
     * is the tag's id, or a new word when [OutboxItem.create].
     */
    Tag,
    /** Sets [OutboxItem.card]'s work state to [OutboxItem.value] (JSON: a state, or null for "not work"). */
    Status,
    /** Pins [OutboxItem.card] above the feed, or unpins it when [OutboxItem.on] is false. */
    Pin,

    /** Sets the field [OutboxItem.field] on [OutboxItem.card] to [OutboxItem.value], or takes it off when that is null. */
    Field,
}

/** A photo or file waiting to go up with its message. [name] is the file in the outbox's folder. */
@Serializable
data class OutboxFile(val name: String, val filename: String, val mimeType: String)

/** The answer a notification button gave, kept so it can be sent once there is signal. */
@Serializable
data class OutboxAnswer(
    val requestId: String,
    val kind: String,
    val options: List<String>? = null,
    val actionId: String,
    val typedText: String? = null,
)

/** A tag a post goes up wearing: a tag's id, or with [create] a new word the project doesn't have yet. */
@Serializable
data class OutboxTag(val tag: String, val create: Boolean = false)

/**
 * One thing the owner sent that hasn't reached the dashboard yet. Every send goes through the outbox,
 * so nothing typed, said or shared is lost when there's no signal.
 */
@Serializable
data class OutboxItem(
    val id: String,
    val createdAtMillis: Long,
    val kind: OutboxKind,
    /** Where it goes, as the owner would say it: "To Garden Game", "Reply to Builder". */
    val label: String,
    val thread: Map<String, String> = emptyMap(),
    val text: String? = null,
    val files: List<OutboxFile> = emptyList(),
    val answer: OutboxAnswer? = null,
    /** A ticket whose post went up but whose "work" mark didn't: retrying only marks it, never posts twice. */
    val ticketMessageId: String? = null,
    val attempts: Int = 0,
    val nextAttemptAtMillis: Long = 0,
    /** Another process is sending it until then, so this one leaves it alone. */
    val claimedUntilMillis: Long = 0,
    val lastError: String? = null,
    /** The dashboard refused it for good. It stays listed until the owner deletes it or tries again. */
    val gaveUp: Boolean = false,
    /** A reply's "answering": the line in the same thread it answers. Not part of where it goes. */
    val answers: String? = null,
    /** The card a reaction, delete, dismissal or in-app answer is about. */
    val card: CardRef? = null,
    val emoji: String? = null,
    /** For a reaction: on or off. Never a toggle, so sending twice can't undo it. */
    val on: Boolean? = null,
    /** An in-app answer, as JSON text; for a tag, the tag's id or new word; for a field, its new value. */
    val value: String? = null,
    /** For a tag: make the word on the way, as the project doesn't have it yet. */
    val create: Boolean? = null,
    /** For a field: its name. */
    val field: String? = null,
    /** For a post: the tags it goes up wearing, put on once it is posted. */
    val tags: List<OutboxTag> = emptyList(),
) {
    /** Items in one thread go in the order they were written. */
    val threadKey: String
        get() = when (kind) {
            OutboxKind.Answer -> "request=${answer?.requestId}"
            OutboxKind.RequestAnswer, OutboxKind.Dismiss -> "request=${card?.id}"
            OutboxKind.React, OutboxKind.Delete, OutboxKind.Tag, OutboxKind.Status, OutboxKind.Pin, OutboxKind.Field -> "card=${card?.kind}/${card?.id}"
            else -> thread.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" }
        }

    /** The question this answers or dismisses, if it does. */
    val requestId: String?
        get() = when (kind) {
            OutboxKind.Answer -> answer?.requestId
            OutboxKind.RequestAnswer, OutboxKind.Dismiss -> card?.id
            else -> null
        }
}

enum class DeliveryOutcome {
    Sent,

    /** No signal, a timeout or the server struggling: it will be tried again. */
    Retry,

    /** The dashboard said no in a way that won't change by asking again. */
    GiveUp,
}

data class DeliveryResult(
    val outcome: DeliveryOutcome,
    /** The HTTP status, or null when the dashboard was never reached. */
    val status: Int? = null,
    val message: String? = null,
    val ticketMessageId: String? = null,
    /** A post or reply as the dashboard stored it, so the feed can show it without waiting for the stream. */
    val sent: MessageView? = null,
) {
    val isSent: Boolean get() = outcome == DeliveryOutcome.Sent

    /** Refused for good: Swift reads this rather than the enum, whose case names it spells its own way. */
    val isRefused: Boolean get() = outcome == DeliveryOutcome.GiveUp
}

/** The outbox's rules: what to send next, when to try again, and when to stop trying. Storage is the app's. */
object Outbox {
    /** How long a process may hold an item while sending before others may take it over. */
    const val ClaimMillis = 2 * 60 * 1000L
    const val FirstRetryMillis = 10 * 1000L
    const val MaxRetryMillis = 15 * 60 * 1000L

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val listSerializer = ListSerializer(OutboxItem.serializer())

    fun encode(items: List<OutboxItem>): String = json.encodeToString(listSerializer, items)

    /** The stored queue. A file that can't be read is treated as empty rather than stopping every send. */
    fun decode(text: String): List<OutboxItem> =
        if (text.isBlank()) emptyList() else runCatching { json.decodeFromString(listSerializer, text) }.getOrElse { emptyList() }

    fun message(id: String, nowMillis: Long, label: String, thread: Map<String, String>, text: String?, files: List<OutboxFile>) =
        OutboxItem(id, nowMillis, OutboxKind.Message, label, thread, text?.trim()?.takeIf { it.isNotEmpty() }, files)

    fun ticket(id: String, nowMillis: Long, project: ProjectRef, text: String) =
        OutboxItem(id, nowMillis, OutboxKind.Ticket, "Ticket for ${project.name}", mapOf("project" to project.slug), text.trim())

    fun answer(id: String, nowMillis: Long, agentName: String, answer: OutboxAnswer) =
        OutboxItem(id, nowMillis, OutboxKind.Answer, "Answer to $agentName", answer = answer)

    /** A reply that answers one line of the thread ([answers] is that line's id). */
    fun reply(id: String, nowMillis: Long, label: String, thread: Map<String, String>, text: String?, answers: String?, files: List<OutboxFile>) =
        message(id, nowMillis, label, thread, text, files).copy(answers = answers)

    fun requestAnswer(id: String, nowMillis: Long, agentName: String, requestId: String, value: JsonElement) =
        OutboxItem(id, nowMillis, OutboxKind.RequestAnswer, "Answer to $agentName", card = CardRef(CardKinds.Request, requestId), value = value.toString())

    fun dismiss(id: String, nowMillis: Long, agentName: String, requestId: String) =
        OutboxItem(id, nowMillis, OutboxKind.Dismiss, "Dismiss ${agentName}'s question", card = CardRef(CardKinds.Request, requestId))

    fun react(id: String, nowMillis: Long, card: CardRef, emoji: String, on: Boolean) =
        OutboxItem(id, nowMillis, OutboxKind.React, if (on) "React $emoji" else "Take back $emoji", card = card, emoji = emoji, on = on)

    fun delete(id: String, nowMillis: Long, card: CardRef) =
        OutboxItem(id, nowMillis, OutboxKind.Delete, "Delete", card = card)

    /** [tag] is a tag id, or a new word to make on the way when [create]. */
    fun tag(id: String, nowMillis: Long, card: CardRef, tag: String, on: Boolean, create: Boolean) =
        OutboxItem(id, nowMillis, OutboxKind.Tag, if (on) "Tag" else "Untag", card = card, on = on, value = tag, create = create.takeIf { it })
    /** Moves a card's work along, or takes it off the work list with a null [status]. */
    fun status(id: String, nowMillis: Long, card: CardRef, status: String?) = OutboxItem(
        id, nowMillis, OutboxKind.Status,
        label = status?.let { "Mark ${WorkStates.label(it)}" } ?: "Mark as not work",
        card = card,
        value = (status?.let(::JsonPrimitive) ?: JsonNull).toString(),
    )
    fun field(id: String, nowMillis: Long, card: CardRef, name: String, value: String?) =
        OutboxItem(id, nowMillis, OutboxKind.Field, if (value == null) "Remove $name" else "Set $name", card = card, field = name, value = value)

    fun pin(id: String, nowMillis: Long, card: CardRef, pinned: Boolean) =
        OutboxItem(id, nowMillis, OutboxKind.Pin, if (pinned) "Pin" else "Unpin", card = card, on = pinned)

    /**
     * Whether a failure is worth trying again. Not reaching the dashboard, a timeout, rate limiting
     * and server errors pass. A lost login (401) can come back when the phone links again, so it waits too.
     * Any other 4xx means the dashboard read it and refused it, and will refuse it again.
     */
    fun outcomeFor(status: Int?): DeliveryOutcome = when {
        status == null -> DeliveryOutcome.Retry
        status in 200..299 -> DeliveryOutcome.Sent
        status == 401 || status == 408 || status == 425 || status == 429 -> DeliveryOutcome.Retry
        status in 400..499 -> DeliveryOutcome.GiveUp
        else -> DeliveryOutcome.Retry
    }

    /** The wait after [attempts] failures: 10 s, doubling, at most 15 min. */
    fun backoffMillis(attempts: Int): Long {
        if (attempts <= 0) return 0
        val doublings = minOf(attempts - 1, 20)
        return minOf(FirstRetryMillis shl doublings, MaxRetryMillis)
    }

    /** Still to send, oldest first. Given-up items are not "waiting", they are listed apart. */
    fun waiting(items: List<OutboxItem>): List<OutboxItem> = items.filter { !it.gaveUp }.sortedBy { it.createdAtMillis }

    fun failed(items: List<OutboxItem>): List<OutboxItem> = items.filter { it.gaveUp }.sortedBy { it.createdAtMillis }

    /**
     * The next item to send: the oldest that is due and not held by another process, and with nothing older
     * still waiting in its thread, so replies never overtake each other. [ignoreBackoff] is for moments that
     * change the odds (the network came back, the owner opened the app); [skip] holds what this pass already tried.
     */
    fun next(items: List<OutboxItem>, nowMillis: Long, ignoreBackoff: Boolean, skip: List<String>): OutboxItem? {
        val live = waiting(items)
        // Compared by place in the list, not by time: two written in the same millisecond still keep their order.
        return live.withIndex().firstOrNull { (index, item) ->
            item.id !in skip &&
                item.claimedUntilMillis <= nowMillis &&
                (ignoreBackoff || item.nextAttemptAtMillis <= nowMillis) &&
                live.subList(0, index).none { it.threadKey == item.threadKey }
        }?.value
    }

    fun claim(items: List<OutboxItem>, id: String, nowMillis: Long): List<OutboxItem> =
        items.map { if (it.id == id) it.copy(claimedUntilMillis = nowMillis + ClaimMillis) else it }

    /** Records how a send went: a sent item leaves the queue; a failed one waits or gives up. */
    fun afterAttempt(items: List<OutboxItem>, id: String, result: DeliveryResult, nowMillis: Long): List<OutboxItem> =
        items.mapNotNull { item ->
            if (item.id != id) return@mapNotNull item
            val attempts = item.attempts + 1
            when (result.outcome) {
                DeliveryOutcome.Sent -> null
                DeliveryOutcome.Retry -> item.copy(
                    attempts = attempts,
                    nextAttemptAtMillis = nowMillis + backoffMillis(attempts),
                    claimedUntilMillis = 0,
                    lastError = result.message,
                    ticketMessageId = result.ticketMessageId ?: item.ticketMessageId,
                )
                DeliveryOutcome.GiveUp -> item.copy(
                    attempts = attempts,
                    claimedUntilMillis = 0,
                    lastError = result.message ?: "The dashboard refused it.",
                    gaveUp = true,
                    ticketMessageId = result.ticketMessageId ?: item.ticketMessageId,
                )
            }
        }

    /** A send cut short (the process was stopped): it is tried again later, like no signal. */
    fun interrupted(): DeliveryResult = DeliveryResult(DeliveryOutcome.Retry, message = "Stopped before it finished.")

    /** The owner asked to try a given-up or waiting item again now. */
    fun retryNow(items: List<OutboxItem>, id: String): List<OutboxItem> =
        items.map { if (it.id == id) it.copy(gaveUp = false, nextAttemptAtMillis = 0, claimedUntilMillis = 0) else it }

    fun remove(items: List<OutboxItem>, id: String): List<OutboxItem> = items.filter { it.id != id }

    /** When the next waiting item falls due, to wake up for it. Null when nothing waits. */
    fun nextWakeMillis(items: List<OutboxItem>): Long? = waiting(items).minOfOrNull { maxOf(it.nextAttemptAtMillis, it.claimedUntilMillis) }

    /** Files no item uses any more, to delete from the outbox's folder. */
    fun orphanedFiles(before: List<OutboxItem>, after: List<OutboxItem>): List<String> {
        val kept = after.flatMap { it.files }.map { it.name }.toSet()
        return before.flatMap { it.files }.map { it.name }.filter { it !in kept }.distinct()
    }

    /** One line about where an item stands, for the "Waiting to send" list. */
    fun status(item: OutboxItem, nowMillis: Long): String {
        if (item.gaveUp) return "Couldn't send: ${item.lastError ?: "the dashboard refused it."}"
        if (item.claimedUntilMillis > nowMillis) return "Sending…"
        if (item.attempts == 0) return "Waiting to send"
        val wait = item.nextAttemptAtMillis - nowMillis
        return when {
            wait <= 60_000 -> "Trying again shortly"
            else -> "Trying again in ${(wait + 59_999) / 60_000} min"
        }
    }

    /** The count shown beside "Waiting to send". */
    fun badge(items: List<OutboxItem>): Int = items.size
}

/**
 * Sends one queued item. Never throws for a failed send: the result says whether to try again.
 * [attachments] are the item's files, read back from disk in the same order.
 */
internal suspend fun DashboardClient.deliverQueued(
    item: OutboxItem,
    attachments: List<Attachment>,
    /** How many bytes of the item's files have gone up so far. */
    uploaded: ((Long) -> Unit)? = null,
): DeliveryResult {
    var ticketMessageId = item.ticketMessageId
    var sent: MessageView? = null
    return try {
        when (item.kind) {
            OutboxKind.Message -> {
                // The item's id is its send key: every try of it is the same send, so the dashboard keeps one.
                val posted = sendMessage(item.thread + listOfNotNull(item.answers?.let { "answers" to it }), item.text, attachments, sendKey = item.id, sent = uploaded).message
                sent = posted
                // As on the website, a tag that won't go on doesn't undo the post: the words matter more.
                val card = posted?.let { CardRef(CardKinds.Message, it.id) }
                if (card != null) item.tags.forEach { t ->
                    try {
                        tagCard(card, t.tag, true, create = t.create)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                    }
                }
            }
            OutboxKind.Ticket -> {
                val slug = item.thread["project"] ?: return DeliveryResult(DeliveryOutcome.GiveUp, message = "It has no project.")
                ticketMessageId = ticketMessageId ?: postToProject(slug, item.text.orEmpty(), sendKey = item.id)
                markAsWork(ticketMessageId)
            }
            OutboxKind.Answer -> {
                val answer = item.answer ?: return DeliveryResult(DeliveryOutcome.GiveUp, message = "It has no answer.")
                val answered = answerFromAction(answer.requestId, answer.kind, answer.options, answer.actionId, answer.typedText)
                if (!answered) {
                    return DeliveryResult(DeliveryOutcome.GiveUp, message = "That button no longer matches the question.")
                }
            }
            OutboxKind.RequestAnswer -> {
                val requestId = item.card?.id ?: return DeliveryResult(DeliveryOutcome.GiveUp, message = "It has no question.")
                val value = item.value?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
                    ?: return DeliveryResult(DeliveryOutcome.GiveUp, message = "It has no answer.")
                answer(requestId, value)
            }
            OutboxKind.Dismiss -> {
                val requestId = item.card?.id ?: return DeliveryResult(DeliveryOutcome.GiveUp, message = "It has no question.")
                alreadyDone { dismissRequest(requestId) }
            }
            OutboxKind.React -> {
                val card = item.card ?: return DeliveryResult(DeliveryOutcome.GiveUp, message = "It has no card.")
                val emoji = item.emoji ?: return DeliveryResult(DeliveryOutcome.GiveUp, message = "It has no emoji.")
                react(card, emoji, item.on ?: true)
            }
            OutboxKind.Status -> {
                val card = item.card ?: return DeliveryResult(DeliveryOutcome.GiveUp, message = "It has no card.")
                val status = item.value?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
                    ?: return DeliveryResult(DeliveryOutcome.GiveUp, message = "It has no state.")
                setCardStatus(card, (status as? JsonPrimitive)?.takeIf { it.isString }?.content)
            }
            OutboxKind.Delete -> {
                val card = item.card ?: return DeliveryResult(DeliveryOutcome.GiveUp, message = "It has no card.")
                alreadyDone {
                    when (card.kind) {
                        CardKinds.Message -> deleteMessage(card.id)
                        CardKinds.Update -> deleteUpdate(card.id)
                        else -> throw DashboardException(400, "invalid_argument", "Only messages and updates can be deleted.")
                    }
                }
            }
            OutboxKind.Tag -> {
                val card = item.card ?: return DeliveryResult(DeliveryOutcome.GiveUp, message = "It has no card.")
                val tag = item.value ?: return DeliveryResult(DeliveryOutcome.GiveUp, message = "It has no tag.")
                tagCard(card, tag, item.on ?: true, create = item.create == true)
            }
            OutboxKind.Field -> {
                val card = item.card ?: return DeliveryResult(DeliveryOutcome.GiveUp, message = "It has no card.")
                val name = item.field ?: return DeliveryResult(DeliveryOutcome.GiveUp, message = "It has no field name.")
                setCardField(card, name, item.value)
            }
            OutboxKind.Pin -> {
                val card = item.card ?: return DeliveryResult(DeliveryOutcome.GiveUp, message = "It has no card.")
                pinCard(card, item.on ?: true)
            }
        }
        DeliveryResult(DeliveryOutcome.Sent, 200, ticketMessageId = ticketMessageId, sent = sent)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (refused: DashboardException) {
        DeliveryResult(Outbox.outcomeFor(refused.status), refused.status, refused.message, ticketMessageId)
    } catch (unreachable: Throwable) {
        DeliveryResult(DeliveryOutcome.Retry, null, unreachable.message ?: "No connection.", ticketMessageId)
    }
}

/** A delete or dismissal of something already gone has done its job: the server's "not found" or "already settled" counts as sent. */
private suspend fun alreadyDone(block: suspend () -> Unit) {
    try {
        block()
    } catch (gone: DashboardException) {
        if (gone.status != 404 && gone.status != 409) throw gone
    }
}

/** A card's work states, as the dashboard names them, in the order work moves through them. */
object WorkStates {
    val all: List<String> = listOf("todo", "in_progress", "blocked", "done", "cancelled")

    /** What the owner reads: "To do", "In progress"… */
    fun label(state: String): String = when (state) {
        "todo" -> "To do"
        "in_progress" -> "In progress"
        "blocked" -> "Blocked"
        "done" -> "Done"
        "cancelled" -> "Cancelled"
        else -> state
    }
}
