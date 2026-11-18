package dev.wildware.agents

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Where the outbox keeps its list and the files waiting to go up. The app supplies a lasting one. */
interface OutboxStorage {
    fun load(): String
    fun save(text: String)
    fun writeFile(name: String, bytes: ByteArray)
    fun readFile(name: String): ByteArray?
    fun deleteFile(name: String)

    /**
     * Reads the list, changes it and writes it back as one step. Where other processes share the
     * outbox (the iPhone's share sheet, Siri and alert buttons), this holds the file so none of their
     * writes is lost. Writing nothing when nothing changed keeps those processes from waking for it.
     */
    fun update(change: (String) -> String) {
        val before = load()
        val after = change(before)
        if (after != before) save(after)
    }
}

/** Keeps the outbox in memory: lost when the app closes. For tests and previews. */
class MemoryOutboxStorage : OutboxStorage {
    var text: String = ""
    val files = mutableMapOf<String, ByteArray>()
    override fun load() = text
    override fun save(text: String) {
        this.text = text
    }
    override fun writeFile(name: String, bytes: ByteArray) {
        files[name] = bytes
    }
    override fun readFile(name: String) = files[name]
    override fun deleteFile(name: String) {
        files.remove(name)
    }
}

/**
 * Sends what the owner did, in order, surviving no signal and restarts. The rules (what goes next,
 * when to try again, when to give up) are [Outbox]'s, the same ones the iPhone app follows; this
 * runs them. Nothing is shown in the feed until the server has it: the live stream brings it back,
 * so the feed never disagrees with the server. [items] is the "waiting to send" list.
 */
class OutboxQueue @OptIn(ExperimentalTime::class) constructor(
    private val client: DashboardClient,
    private val storage: OutboxStorage,
    scope: CoroutineScope,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val confined: CoroutineScope = confinedScope(scope)

    private val mutableItems = MutableStateFlow(Outbox.decode(runCatching { storage.load() }.getOrDefault("")))

    /** Everything not yet sent, given-up items included. [Outbox.waiting] and [Outbox.failed] split it. */
    val items: StateFlow<List<OutboxItem>> = mutableItems.asStateFlow()

    /**
     * Told about each item this queue sent, before it leaves [items]: the feed shows a reply from
     * here rather than from the live stream, so a dead stream can't hide it, and it never blinks
     * out between leaving the outbox and arriving in its thread.
     */
    var onSent: (suspend (OutboxItem, DeliveryResult) -> Unit)? = null

    private val kicks = Channel<Boolean>(Channel.CONFLATED)
    private var runner: Job? = null

    /** Starts sending, and keeps at it until [stop]. Things added before this wait. */
    fun start() {
        if (runner?.isActive == true) return
        runner = confined.launch {
            var ignoreBackoff = true
            while (true) {
                drain(ignoreBackoff)
                // Only the first item in each thread can go next, so only those set the wake-up.
                val heads = Outbox.waiting(mutableItems.value).distinctBy { it.threadKey }
                val wake = Outbox.nextWakeMillis(heads)
                val wait = wake?.let { maxOf(MinWaitMillis, it - clock()) } ?: Long.MAX_VALUE
                ignoreBackoff = withTimeoutOrNull(wait) { kicks.receive() } ?: false
            }
        }
    }

    fun stop() {
        runner?.cancel()
        runner = null
    }

    /** Try now. [ignoreBackoff] when the odds changed: the network came back, or the owner opened the app. */
    fun kick(ignoreBackoff: Boolean = false) {
        kicks.trySend(ignoreBackoff)
    }

    /** Adds an item, with its files' bytes, and sends it as soon as it can. */
    fun add(item: OutboxItem, files: Map<String, ByteArray> = emptyMap()) {
        confined.launch {
            files.forEach { (name, bytes) -> runCatching { storage.writeFile(name, bytes) } }
            update { it + item }
            kick()
        }
    }

    fun retryNow(id: String) {
        confined.launch {
            update { Outbox.retryNow(it, id) }
            kick(ignoreBackoff = true)
        }
    }

    /** Takes an item out without sending it, and its files with it. */
    fun remove(id: String) {
        confined.launch { update { Outbox.remove(it, id) } }
    }

    /** Reads the list again because another process changed it, and sends anything new. */
    fun reload() {
        confined.launch {
            runCatching { storage.load() }.onSuccess { mutableItems.value = Outbox.decode(it) }
            kick()
        }
    }

    /**
     * Changes the list as stored, not as last seen: another process may have added to it since.
     * If the storage fails, the change still holds in memory, so the screen stays right.
     */
    private fun update(change: (List<OutboxItem>) -> List<OutboxItem>) {
        var before = mutableItems.value
        var after = before
        val stored = runCatching {
            storage.update { text ->
                before = Outbox.decode(text)
                after = change(before)
                if (after == before) text else Outbox.encode(after)
            }
        }
        if (stored.isFailure) {
            before = mutableItems.value
            after = change(before)
        }
        mutableItems.value = after
        Outbox.orphanedFiles(before, after).forEach { runCatching { storage.deleteFile(it) } }
    }

    private suspend fun drain(ignoreBackoff: Boolean) {
        val tried = mutableListOf<String>()
        while (true) {
            // Picked and claimed in one held write, so no other process sends the same item meanwhile.
            var picked: OutboxItem? = null
            update { items ->
                val next = Outbox.next(items, clock(), ignoreBackoff, tried)
                picked = next
                if (next == null) items else Outbox.claim(items, next.id, clock())
            }
            val item = picked ?: return
            tried += item.id
            val result = try {
                val attachments = item.files.map { file ->
                    val bytes = storage.readFile(file.name) ?: throw MissingFile(file.filename)
                    Attachment(file.filename, file.mimeType, bytes)
                }
                client.deliver(item, attachments)
            } catch (missing: MissingFile) {
                DeliveryResult(DeliveryOutcome.GiveUp, message = "${missing.filename} is no longer on this device.")
            }
            if (result.isSent) {
                try {
                    onSent?.invoke(item, result)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // Showing it early is a nicety; the stream still brings it.
                }
            }
            update { Outbox.afterAttempt(it, item.id, result, clock()) }
        }
    }

    private class MissingFile(val filename: String) : Exception()

    private companion object {
        /** Never spin: even an item that looks due waits this long before the next pass. */
        const val MinWaitMillis = 1_000L
    }
}

/**
 * What the owner can do from the feed. Each call queues the change and returns its outbox id at
 * once; it goes up when there is signal. Files are copied into the outbox, so the caller may let go.
 */
@OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)
class OwnerActions(
    private val outbox: OutboxQueue,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val newId: () -> String = { Uuid.random().toString() },
) {
    /** Writes a new post into a project's feed. */
    fun post(project: ProjectRef, text: String?, files: List<Attachment> = emptyList()): String =
        queue(files) { id, stored -> Outbox.message(id, clock(), "To ${project.name}", mapOf("project" to project.slug), text, stored) }

    /** Posts and marks it as work, so an agent on the project picks it up. */
    fun ticket(project: ProjectRef, text: String): String = queue(emptyList()) { id, _ -> Outbox.ticket(id, clock(), project, text) }

    /**
     * Replies on a card: an update's thread, under a post, or in the talk about a question.
     * [answers] is the line in that thread this answers, shown as "answering <name>".
     */
    fun reply(card: CardRef, text: String?, answers: String? = null, files: List<Attachment> = emptyList(), label: String = "Reply"): String {
        val thread = when (card.kind) {
            CardKinds.Update -> mapOf("update" to card.id)
            CardKinds.Message -> mapOf("replyTo" to card.id)
            CardKinds.Request -> mapOf("request" to card.id)
            CardKinds.Task -> mapOf("task" to card.id)
            else -> throw IllegalArgumentException("Can't reply on a ${card.kind}.")
        }
        return queue(files) { id, stored -> Outbox.reply(id, clock(), label, thread, text, answers, stored) }
    }

    /**
     * Answers a question with [value] exactly as the server takes it: a string, a boolean, a list of
     * strings, or `{action, text}` for a form. The server checks it against the question.
     */
    fun answer(request: RequestView, agentName: String, value: JsonElement): String =
        queue(emptyList()) { id, _ -> Outbox.requestAnswer(id, clock(), agentName, request.id, value) }

    /** Lets the agent go without an answer: it hears "cancelled". */
    fun dismiss(request: RequestView, agentName: String): String =
        queue(emptyList()) { id, _ -> Outbox.dismiss(id, clock(), agentName, request.id) }

    /** Puts an emoji on a card, or takes it off: pass whether the owner's reaction is there now as [on]'s opposite. */
    fun react(card: CardRef, emoji: String, on: Boolean): String = queue(emptyList()) { id, _ -> Outbox.react(id, clock(), card, emoji, on) }

    /** Taps an emoji: on if the owner hasn't used it on this card, off if they have. */
    fun toggleReaction(card: CardRef, emoji: String, current: List<ReactionView>): String =
        react(card, emoji, on = current.none { it.isOwner && it.emoji == emoji })

    /** Marks a card as work, moves it along, or with a null [status] takes it off the work list. */
    fun setStatus(card: CardRef, status: String?): String = queue(emptyList()) { id, _ -> Outbox.status(id, clock(), card, status) }

    /** Deletes a message (a post takes its replies with it) or an update. */
    fun delete(card: CardRef): String = queue(emptyList()) { id, _ -> Outbox.delete(id, clock(), card) }

    /** Puts a tag on a card or takes it off; with [create], [tag] is a new word to make on the way. */
    fun tag(card: CardRef, tag: String, on: Boolean, create: Boolean = false): String =
        queue(emptyList()) { id, _ -> Outbox.tag(id, clock(), card, tag, on, create) }
    /** Sets a field on a card by name, or takes it off when [value] is null. */
    fun setField(card: CardRef, name: String, value: String?): String =
        queue(emptyList()) { id, _ -> Outbox.field(id, clock(), card, name, value) }

    /** Pins a card above the feed, or unpins it. */
    fun pin(card: CardRef, pinned: Boolean): String = queue(emptyList()) { id, _ -> Outbox.pin(id, clock(), card, pinned) }

    private fun queue(files: List<Attachment>, build: (String, List<OutboxFile>) -> OutboxItem): String {
        val id = newId()
        val stored = files.map { OutboxFile("$id-${newId()}", it.filename, it.mimeType) to it.bytes }
        val item = build(id, stored.map { it.first })
        outbox.add(item, stored.associate { (file, bytes) -> file.name to bytes })
        return id
    }
}
