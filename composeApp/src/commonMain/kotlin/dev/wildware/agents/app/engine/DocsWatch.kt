package dev.wildware.agents.app.engine

import dev.wildware.agents.DashboardClient
import dev.wildware.agents.DashboardException
import dev.wildware.agents.DocAuthor
import dev.wildware.agents.DocChange
import dev.wildware.agents.DocPage
import dev.wildware.agents.DocSave
import dev.wildware.agents.DocView
import dev.wildware.agents.app.feed.DocBacklink
import dev.wildware.agents.app.feed.DocConflict
import dev.wildware.agents.app.feed.DocItem
import dev.wildware.agents.app.feed.DocVersionItem
import dev.wildware.agents.app.feed.DocsAction
import dev.wildware.agents.app.feed.DocsState
import dev.wildware.agents.app.feed.OpenDoc
import dev.wildware.agents.app.feed.ViewedVersion
import dev.wildware.agents.createDoc
import dev.wildware.agents.deleteDoc
import dev.wildware.agents.doc
import dev.wildware.agents.docByTitle
import dev.wildware.agents.docVersion
import dev.wildware.agents.docVersions
import dev.wildware.agents.docs
import dev.wildware.agents.restoreDoc
import dev.wildware.agents.saveDoc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * The Docs tab while it is on screen (server migration 076): the channel's docs, read when it opens
 * and again whenever the live stream says a doc there changed ([changes]), and the doc open.
 *
 * An open doc someone else saves is read again at once, unless it is being edited: then it only
 * says a newer version is there, and the save asks to merge. A save the dashboard refuses as stale
 * comes back as a [DocConflict] with both texts; nothing is saved over the other person's.
 */
class DocsWatch(
    private val client: DashboardClient,
    private val scope: CoroutineScope,
    private val changes: Flow<DocChange>,
    /** "5m ago", for a time in epoch milliseconds. */
    private val ago: (Long) -> String,
) {
    private val mutable = MutableStateFlow(DocsState())
    val state: StateFlow<DocsState> = mutable.asStateFlow()

    private var live: Job? = null
    private var listing: Job? = null

    /** The channel's id, from its last list: a live change names its channel by id. */
    private var channelId: String? = null

    fun handle(action: DocsAction) {
        when (action) {
            is DocsAction.Show -> show(action.channel)
            is DocsAction.Search -> search(action.query)
            is DocsAction.Open -> open(action.id)
            is DocsAction.OpenTitle -> openTitle(action.channel, action.title)
            DocsAction.Close -> mutable.value = mutable.value.copy(open = null, opening = null, missing = null)
            is DocsAction.Create -> create(action.title)
            is DocsAction.Edit -> openDoc { it.copy(editing = action.on, newerVersion = if (action.on) it.newerVersion else null, problem = null) }
            is DocsAction.Save -> save(action.title, action.body, action.baseVersion)
            DocsAction.Delete -> delete()
            is DocsAction.History -> history(action.open)
            is DocsAction.ViewVersion -> view(action.version)
            is DocsAction.Restore -> restore(action.version)
            DocsAction.DismissConflict -> openDoc { it.copy(conflict = null) }
            DocsAction.DismissProblem -> {
                mutable.value = mutable.value.copy(problem = null, missing = null)
                openDoc { it.copy(problem = null) }
            }
        }
    }

    /** Shows [channel]'s docs, or (null) stops reading them: the tab is off screen. */
    @OptIn(FlowPreview::class)
    private fun show(channel: String?) {
        if (channel == null) {
            live?.cancel()
            live = null
            return
        }
        if (channel == mutable.value.channel && live != null) return
        live?.cancel()
        if (channel != mutable.value.channel) {
            channelId = null
            mutable.value = DocsState(channel = channel)
        }
        live = scope.launch {
            launch { list() }
            changes.filter { it.change == "resync" || it.projectId == channelId || channelId == null }.collect { changed(it) }
        }
    }

    private fun changed(change: DocChange) {
        launchList()
        val open = mutable.value.open ?: return
        if (change.change != "resync" && change.docId != open.id) return
        if (change.change == "deleted") {
            mutable.value = mutable.value.copy(open = null, problem = "“${open.title}” was deleted.")
            return
        }
        if (change.change != "resync" && change.version <= open.version) return
        if (open.editing) {
            // Their words never replace what is being typed: the save will ask to merge.
            if (change.version > open.version) openDoc { it.copy(newerVersion = change.version) }
        } else {
            scope.launch { reload(open.id) }
        }
    }

    private fun search(query: String) {
        if (query == mutable.value.query) return
        mutable.value = mutable.value.copy(query = query)
        launchList()
    }

    private fun launchList() {
        listing?.cancel()
        listing = scope.launch { list() }
    }

    private suspend fun list() {
        val channel = mutable.value.channel ?: return
        val query = mutable.value.query
        mutable.value = mutable.value.copy(loading = true)
        try {
            val read = client.docs(channel, query.ifBlank { null })
            if (mutable.value.channel != channel || mutable.value.query != query) return
            channelId = read.projectId.ifEmpty { null }
            mutable.value = mutable.value.copy(docs = read.docs.map(::itemOf), canEdit = read.canEdit, loaded = true, loading = false, problem = null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Throwable) {
            if (mutable.value.channel == channel) mutable.value = mutable.value.copy(loaded = true, loading = false, problem = problemOf(failed, "read this channel's docs"))
        }
    }

    private fun open(id: String) {
        mutable.value = mutable.value.copy(opening = id, missing = null)
        scope.launch { reload(id, opening = true) }
    }

    private suspend fun reload(id: String, opening: Boolean = false) {
        try {
            val page = client.doc(id)
            if (opening && mutable.value.opening != id) return
            if (!opening && mutable.value.open?.id != id) return
            showPage(page, keep = mutable.value.open?.takeIf { it.id == id })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Throwable) {
            mutable.value = mutable.value.copy(opening = null, problem = problemOf(failed, "open this doc"))
        }
    }

    /** A [[link]]: the doc titled [title] in [channel], made now when it is missing and this person may. */
    private fun openTitle(channel: String, title: String) {
        mutable.value = mutable.value.copy(opening = title, missing = null)
        scope.launch {
            try {
                val found = client.docByTitle(channel, title)
                if (mutable.value.opening != title) return@launch
                when {
                    found != null -> showPage(found, keep = null)
                    mutable.value.canEdit || mutable.value.channel != channel -> try {
                        showPage(client.createDoc(channel, title), keep = null, editing = true)
                        launchList()
                    } catch (refused: DashboardException) {
                        // May only read: say the doc isn't written yet rather than fail.
                        if (refused.status == 403) mutable.value = mutable.value.copy(opening = null, missing = title) else throw refused
                    }
                    else -> mutable.value = mutable.value.copy(opening = null, missing = title)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Throwable) {
                mutable.value = mutable.value.copy(opening = null, problem = problemOf(failed, "open “$title”"))
            }
        }
    }

    private fun create(title: String) {
        val channel = mutable.value.channel ?: return
        val name = title.trim()
        if (name.isEmpty()) return
        mutable.value = mutable.value.copy(opening = name, problem = null)
        scope.launch {
            try {
                showPage(client.createDoc(channel, name), keep = null, editing = true)
                launchList()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Throwable) {
                mutable.value = mutable.value.copy(opening = null, problem = problemOf(failed, "make “$name”"))
            }
        }
    }

    private fun save(title: String, body: String, baseVersion: Long) {
        val open = mutable.value.open ?: return
        openDoc { it.copy(saving = true, problem = null) }
        scope.launch {
            try {
                val renamed = title.trim().takeIf { it.isNotEmpty() && it != open.title }
                when (val result = client.saveDoc(open.id, baseVersion, body, renamed)) {
                    is DocSave.Saved -> {
                        showPage(result.page, keep = null)
                        launchList()
                    }
                    is DocSave.Conflict -> openDoc {
                        it.copy(
                            saving = false,
                            conflict = DocConflict(
                                theirs = result.current.body, theirsTitle = result.current.title, theirsVersion = result.current.version,
                                theirsBy = result.current.updatedBy?.name ?: "Someone", mine = body, mineTitle = title,
                            ),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Throwable) {
                openDoc { it.copy(saving = false, problem = problemOf(failed, "save it")) }
            }
        }
    }

    private fun delete() {
        val open = mutable.value.open ?: return
        scope.launch {
            try {
                client.deleteDoc(open.id)
                mutable.value = mutable.value.copy(open = null)
                launchList()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Throwable) {
                openDoc { it.copy(problem = problemOf(failed, "delete it")) }
            }
        }
    }

    private fun history(on: Boolean) {
        val open = mutable.value.open ?: return
        if (!on) {
            openDoc { it.copy(history = null, viewing = null) }
            return
        }
        openDoc { it.copy(history = it.history ?: emptyList()) }
        scope.launch {
            try {
                val read = client.docVersions(open.id)
                openDoc { doc ->
                    if (doc.id != open.id || doc.history == null) doc
                    else doc.copy(history = read.versions.map { v ->
                        DocVersionItem(v.version, v.title, nameOf(v.savedBy), v.savedBy?.kind == "agent", ago(v.savedAtMillis), v.restoredFrom, v.size)
                    })
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Throwable) {
                openDoc { it.copy(problem = problemOf(failed, "read its history")) }
            }
        }
    }

    private fun view(version: Long?) {
        val open = mutable.value.open ?: return
        if (version == null) {
            openDoc { it.copy(viewing = null) }
            return
        }
        scope.launch {
            try {
                val v = client.docVersion(open.id, version)
                openDoc { if (it.id != open.id) it else it.copy(viewing = ViewedVersion(v.version, v.title, v.body.orEmpty(), nameOf(v.savedBy), ago(v.savedAtMillis))) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Throwable) {
                openDoc { it.copy(problem = problemOf(failed, "read version $version")) }
            }
        }
    }

    private fun restore(version: Long) {
        val open = mutable.value.open ?: return
        openDoc { it.copy(saving = true, problem = null) }
        scope.launch {
            try {
                when (val result = client.restoreDoc(open.id, version, open.version)) {
                    is DocSave.Saved -> {
                        showPage(result.page, keep = null)
                        launchList()
                    }
                    // Someone saved since this was read: show theirs, then the person can restore again.
                    is DocSave.Conflict -> {
                        reload(open.id)
                        openDoc { it.copy(saving = false, problem = "${result.current.updatedBy?.name ?: "Someone"} saved a newer version first. Look again, then restore.") }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Throwable) {
                openDoc { it.copy(saving = false, problem = problemOf(failed, "restore version $version")) }
            }
        }
    }

    private fun showPage(page: DocPage, keep: OpenDoc?, editing: Boolean = false) {
        val d = page.doc
        mutable.value = mutable.value.copy(
            opening = null,
            missing = null,
            open = OpenDoc(
                id = d.id, title = d.title, body = d.body, version = d.version,
                savedBy = nameOf(d.updatedBy), savedByAgent = d.updatedBy?.kind == "agent", savedAgo = ago(d.updatedAtMillis),
                canEdit = page.canEdit, backlinks = page.backlinks.map { DocBacklink(it.id, it.title) },
                editing = editing || (keep?.editing == true && page.canEdit),
                // An open History list is read again for the new version.
                history = null,
            ),
        )
        if (keep?.history != null) history(true)
    }

    private inline fun openDoc(change: (OpenDoc) -> OpenDoc) {
        val open = mutable.value.open ?: return
        mutable.value = mutable.value.copy(open = change(open))
    }

    private fun itemOf(d: DocView) = DocItem(d.id, d.title, nameOf(d.updatedBy), d.updatedBy?.kind == "agent", ago(d.updatedAtMillis), d.size)

    private fun nameOf(who: DocAuthor?) = who?.name?.ifBlank { null } ?: "Someone"

    private fun problemOf(failed: Throwable, what: String) =
        if (failed is DashboardException) "Couldn't $what. ${failed.message}" else "Couldn't $what. Check the connection and try again."
}
