package dev.wildware.agents.app.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wildware.agents.ProfileDescriptionMax
import dev.wildware.agents.ProfileRoleMax
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons

/**
 * A member's name with their role line beside it, as a profile's title shows it: "Sam · Design".
 * The role is muted and gives way first when the line is short of room.
 */
@Composable
internal fun ProfileName(name: String, profileRole: String?) {
    val colors = AgentTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.weight(1f, fill = false), style = AgentTheme.type.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!profileRole.isNullOrBlank()) {
            Text(
                "  ·  $profileRole",
                Modifier.weight(1f, fill = false),
                color = colors.textMuted,
                style = AgentTheme.type.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * What a member says about themselves: their description, or [empty] when there is none. With
 * [save] it can be changed here: their role line and description, each held to what the dashboard
 * keeps (60 and 500 characters), with a live count. A refusal shows the dashboard's words and
 * keeps what was typed.
 */
@Composable
internal fun ProfileAbout(
    key: String,
    profileRole: String?,
    description: String?,
    empty: String,
    hint: String,
    save: ((role: String, description: String, done: (String?) -> Unit) -> Unit)?,
) {
    val colors = AgentTheme.colors
    var editing by remember(key) { mutableStateOf(false) }
    Spacer(Modifier.height(14.dp))
    MetaText("ABOUT", Modifier.semantics { heading() }, style = AgentTheme.type.overline, color = colors.textMuted)
    Spacer(Modifier.height(6.dp))
    if (!editing || save == null) {
        if (description.isNullOrBlank()) {
            Text(empty, color = colors.textMuted, style = AgentTheme.type.body)
        } else {
            Text(description, color = colors.text, style = AgentTheme.type.body)
        }
        if (save != null) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                val blank = profileRole.isNullOrBlank() && description.isNullOrBlank()
                AppButton(if (blank) "Add a description" else "Edit profile", ButtonTone.Secondary, icon = Icons.Pencil, onClick = { editing = true })
            }
        }
        return
    }
    val role = remember(key) { TextFieldState(profileRole.orEmpty()) }
    val about = remember(key) { TextFieldState(description.orEmpty()) }
    var busy by remember(key) { mutableStateOf(false) }
    var problem by remember(key) { mutableStateOf<String?>(null) }
    val send = {
        val typedRole = role.text.toString().trim()
        val typedAbout = about.text.toString().trim()
        when {
            busy -> Unit
            typedRole == profileRole.orEmpty() && typedAbout == description.orEmpty() -> editing = false
            else -> {
                busy = true
                problem = null
                save(typedRole, typedAbout) { refused ->
                    busy = false
                    if (refused == null) editing = false else problem = refused
                }
            }
        }
    }
    BoxedField("Role", role, "Role line", "Development, Design, Owner…", maxLength = ProfileRoleMax)
    Spacer(Modifier.height(10.dp))
    BoxedField("Description", about, "Description", hint, multiline = true, minLines = 4, maxLength = ProfileDescriptionMax, onSubmit = send)
    problem?.let {
        Text(it, Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }, color = colors.error, style = AgentTheme.type.label)
    }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
        // What was typed is dropped: the editor starts from what is saved next time.
        AppButton("Cancel", ButtonTone.Secondary, enabled = !busy, onClick = { editing = false })
        Spacer(Modifier.width(8.dp))
        AppButton(if (busy) "Saving…" else "Save profile", ButtonTone.Primary, enabled = !busy, onClick = send)
    }
}

/** What a profile's editor hints at while the description is empty. */
internal const val PersonProfileHint = "Who you are, what you look after, when to contact you"
internal const val AgentProfileHint = "What it does, what it looks after, when to @mention it"

/**
 * One person's profile, opened from their row in the members list or the People sheet: their name
 * and role line, @handle, whether they are online, and what they say about themselves. Only you
 * can change your own; nobody changes another person's.
 */
@Composable
fun PersonProfileDialog(ui: FeedUi, personId: String) {
    val person = ui.state.team.people.firstOrNull { it.id == personId } ?: return run { ui.personOpen = null }
    val colors = AgentTheme.colors
    val close = { ui.personOpen = null }
    val presence = if (person.online || person.you) Presence.Online else Presence.Offline
    val author = personAuthor(person)
    AlertDialog(
        onDismissRequest = close,
        containerColor = colors.surfaceRaised,
        titleContentColor = colors.text,
        textContentColor = colors.textMuted,
        shape = AgentTheme.shapes.large,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(author, ProfileAvatarSize, presence = presence)
                Column(Modifier.padding(start = 14.dp)) { ProfileName(person.name, person.profileRole) }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                MetaText(listOfNotNull("@${person.handle}".takeIf { person.handle.isNotEmpty() }, if (person.you) "You" else null, presence.label).joinToString(" · "), color = colors.textMuted)
                if (person.you) PictureControls(person.id, person.avatarMediaId != null) { picture, done -> ui.emit(FeedEvent.SetMyAvatar(picture, done)) }
                ProfileAbout(
                    key = person.id,
                    profileRole = person.profileRole,
                    description = person.description,
                    empty = if (person.you) "No description yet. Say what you do and when to contact you." else "No description yet.",
                    hint = PersonProfileHint,
                    save = if (!person.you) null else { role, about, done -> ui.emit(FeedEvent.SetMyProfile(role, about, done)) },
                )
            }
        },
        confirmButton = { AppButton("Done", ButtonTone.Secondary, onClick = close) },
    )
}

/** How big a profile shows the member's picture. */
internal val ProfileAvatarSize = 56.dp

/** A person as their avatar draws them: you in the owner's colours, anyone else in theirs, with their picture. */
internal fun personAuthor(person: PersonRow): Author =
    if (person.you) Author.Owner(person.name, person.avatarMediaId)
    else Author.Person(person.name, person.handle, dev.wildware.agents.app.engine.hue(person.id), person.avatarMediaId)

/**
 * A picture for a member, picked from the device (the same picker as attachments): its middle
 * square goes up, made small. Remove takes it off, back to the letter. A refusal shows why.
 */
@Composable
internal fun PictureControls(key: String, hasPicture: Boolean, set: (picture: ByteArray?, done: (String?) -> Unit) -> Unit) {
    val colors = AgentTheme.colors
    val picker = LocalFilePicker.current
    var busy by remember(key) { mutableStateOf<String?>(null) }
    var problem by remember(key) { mutableStateOf<String?>(null) }
    val finished = { refused: String? -> busy = null; problem = refused }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (picker != null) {
            AppButton(
                when {
                    busy == "change" -> "Uploading…"
                    hasPicture -> "Change picture"
                    else -> "Add a picture"
                },
                ButtonTone.Secondary,
                icon = Icons.Image,
                enabled = busy == null,
                onClick = {
                    picker.pick { files ->
                        val image = files.firstOrNull { it.isImage }
                        when {
                            image != null -> {
                                busy = "change"
                                problem = null
                                set(image.bytes, finished)
                            }
                            files.isNotEmpty() -> problem = "Pick a picture: a PNG, JPEG, WebP or GIF."
                        }
                    }
                },
            )
        }
        if (hasPicture) {
            AppButton(if (busy == "remove") "Removing…" else "Remove picture", ButtonTone.Quiet, enabled = busy == null, onClick = {
                busy = "remove"
                problem = null
                set(null, finished)
            })
        }
    }
    problem?.let {
        Text(it, Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }, color = colors.error, style = AgentTheme.type.label)
    }
}
