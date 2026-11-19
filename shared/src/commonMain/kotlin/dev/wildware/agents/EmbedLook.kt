package dev.wildware.agents

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Where a picture in an embed comes from: an upload on this dashboard, or an https address. */
sealed interface EmbedImage {
    data class Media(val id: String) : EmbedImage
    data class Url(val url: String) : EmbedImage
}

/** The small corner glyph that says where an embed came from. */
sealed interface EmbedMark {
    val label: String

    /** One of the marks the app draws itself: slack, github, gitlab, jira, confluence, linear, notion, figma, link. */
    data class Drawn(val name: String, override val label: String) : EmbedMark
    data class Image(val image: EmbedImage, override val label: String) : EmbedMark
}

/** One drawable piece of an embed, its values already filled in. */
sealed interface EmbedBlock {
    enum class Size { Sm, Md, Lg }
    enum class Tone { Normal, Muted, Accent }
    enum class ChipStyle { Pill, Lozenge, Tag }
    enum class Shape { Circle, Rounded, Square }

    data class Header(
        val avatar: EmbedImage?,
        val title: String?,
        val subtitle: String?,
        val meta: String?,
        val shape: Shape = Shape.Circle,
        val size: Size = Size.Md,
        val stacked: Boolean = false,
        /** Drawn in a tinted bar across the top, as a GitHub comment's header is. */
        val bar: Boolean = false,
    ) : EmbedBlock
    data class Heading(val text: String, val level: Int) : EmbedBlock
    data class Text(val text: String, val tone: Tone = Tone.Normal, val size: Size = Size.Md) : EmbedBlock
    data class Markdown(val text: String, val size: Size = Size.Md) : EmbedBlock
    data class Code(val text: String, val language: String?) : EmbedBlock
    data class Quote(val text: String) : EmbedBlock
    data class Fields(val items: List<Pair<String, String>>, val inline: Boolean) : EmbedBlock
    data class Chips(val chips: List<String>, val style: ChipStyle = ChipStyle.Pill) : EmbedBlock
    data class Image(val image: EmbedImage, val alt: String?) : EmbedBlock
    data object Divider : EmbedBlock
    data class Footer(val text: String?, val url: String?) : EmbedBlock
}

/** A look's palette and frame: what the card is drawn in. Colours are `#rrggbb`. */
data class EmbedStyle(
    val accent: String? = null,
    val background: String? = null,
    val foreground: String? = null,
    val muted: String? = null,
    val border: String? = null,
    /** "sans", "serif" or "mono". */
    val font: String = "sans",
    /** "none", "sm", "md" or "lg". */
    val radius: String? = null,
    val bordered: Boolean = true,
    val stripe: Boolean = false,
    /** "tight", "normal" or "roomy". */
    val padding: String = "normal",
)

/** One embed ready to draw: its look's style, its badge and mark, and its blocks. */
data class ResolvedEmbed(
    val id: String,
    val typeName: String,
    val style: EmbedStyle,
    /** The labelled badge along the top, if the look has one. */
    val badge: String?,
    val badgeIcon: String?,
    val mark: EmbedMark?,
    val blocks: List<EmbedBlock>,
    /** Line the body up under the header's words rather than its avatar. */
    val indent: Boolean,
    /** A dark card, for the code colours: no background (it takes the app's) counts as dark. */
    val dark: Boolean,
    val sourceUrl: String?,
)

/**
 * Agents post things in the look of where they came from (a Slack message, a GitHub comment, a Jira
 * ticket): a look is a spec of blocks with `{{name}}` placeholders, an embed is its values. This
 * fills one in, by the website's rules (`embeds.ts`): a block whose values never arrived is left out,
 * a divider that separates nothing goes, links are http(s) only and pictures an upload or https.
 * Anything it doesn't understand is skipped, so a newer look never breaks a card.
 */
object EmbedLook {
    private val placeholder = Regex("""\{\{\s*([A-Za-z0-9_-]+)\s*\}\}""")
    private val mediaId = Regex("^[0-9A-HJKMNP-TV-Z]{26}$")
    private val hex = Regex("^#[0-9a-fA-F]{6}$")
    private val marks = setOf("slack", "github", "gitlab", "jira", "confluence", "linear", "notion", "figma", "link")

    private class Filled(val text: String, val filled: Boolean)

    private fun valueOf(data: JsonObject, name: String): String = when (val value = data[name]) {
        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.content }.joinToString(", ")
        is JsonPrimitive -> if (value.isString) value.content else value.content.takeUnless { it == "null" }.orEmpty()
        else -> ""
    }

    private fun fill(template: String?, data: JsonObject): Filled {
        if (template == null) return Filled("", false)
        var saw = false
        var found = false
        val text = placeholder.replace(template) { m ->
            saw = true
            valueOf(data, m.groupValues[1]).also { if (it.isNotEmpty()) found = true }
        }
        return Filled(text.trim(), !saw || found)
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
    private fun JsonElement?.obj(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())

    private fun size(value: String?) = when (value) { "sm" -> EmbedBlock.Size.Sm; "lg" -> EmbedBlock.Size.Lg; else -> EmbedBlock.Size.Md }

    /** A picture's address, or null when it is neither an upload nor https. */
    fun image(value: String): EmbedImage? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        if (mediaId.matches(trimmed)) return EmbedImage.Media(trimmed)
        return if (trimmed.lowercase().startsWith("https://")) EmbedImage.Url(trimmed) else null
    }

    /** A link out of an embed, http or https only. */
    fun link(value: String): String? {
        val trimmed = value.trim()
        val lower = trimmed.lowercase()
        return trimmed.takeIf { lower.startsWith("https://") || lower.startsWith("http://") }
    }

    fun resolve(embed: EmbedView): ResolvedEmbed {
        val spec = embed.spec
        val data = embed.data.let { d ->
            val source = embed.sourceUrl
            if (source == null || "source_url" in d) d else JsonObject(d + ("source_url" to JsonPrimitive(source)))
        }
        val theme = spec["theme"].obj()
        val frame = spec["frame"].obj()
        val chrome = spec["chrome"].obj()
        val blocks = trimDividers((spec["blocks"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { b -> block(b, data) } })

        val badge = fill(chrome.text("label"), data).text
        val badgeIcon = fill(chrome.text("icon"), data).text
        val markLabel = badge.ifEmpty { chrome.text("mark") ?: "Source" }
        val mark = fill(chrome.text("image"), data).text.takeIf { it.isNotEmpty() }?.let(::image)?.let { EmbedMark.Image(it, markLabel) }
            ?: chrome.text("mark")?.takeIf { it in marks }?.let { EmbedMark.Drawn(it, markLabel) }

        val background = theme.text("background")?.takeIf { hex.matches(it) }
        fun colour(key: String) = theme.text(key)?.takeIf { hex.matches(it) }
        val header = blocks.firstOrNull { it is EmbedBlock.Header } as EmbedBlock.Header?
        return ResolvedEmbed(
            id = embed.id,
            typeName = embed.typeName.ifEmpty { embed.type },
            style = EmbedStyle(
                accent = colour("accent"),
                background = background,
                foreground = colour("foreground"),
                muted = colour("muted"),
                border = colour("border"),
                font = theme.text("font")?.takeIf { it in setOf("sans", "serif", "mono") } ?: "sans",
                radius = theme.text("radius")?.takeIf { it in setOf("none", "sm", "md", "lg") },
                bordered = frame.flag("border") != false,
                stripe = frame.flag("stripe") == true,
                padding = frame.text("padding")?.takeIf { it in setOf("tight", "normal", "roomy") } ?: "normal",
            ),
            badge = badge.ifEmpty { null },
            badgeIcon = badgeIcon.ifEmpty { null },
            mark = mark,
            blocks = blocks,
            indent = frame.flag("indent") == true && header?.avatar != null,
            dark = background == null || run {
                val r = background.substring(1, 3).toInt(16)
                val g = background.substring(3, 5).toInt(16)
                val b = background.substring(5, 7).toInt(16)
                (r * 299 + g * 587 + b * 114) / 1000 < 140
            },
            sourceUrl = embed.sourceUrl?.let(::link),
        )
    }

    private fun block(block: JsonObject, data: JsonObject): EmbedBlock? {
        fun value() = fill(block.text("value"), data).takeIf { it.filled && it.text.isNotEmpty() }?.text
        return when (block.text("type")) {
            "divider" -> EmbedBlock.Divider
            "header" -> {
                val title = fill(block.text("title"), data).text
                val subtitle = fill(block.text("subtitle"), data).text
                val meta = fill(block.text("meta"), data).text
                if (title.isEmpty() && subtitle.isEmpty() && meta.isEmpty()) return null
                EmbedBlock.Header(
                    avatar = fill(block.text("avatar"), data).text.takeIf { it.isNotEmpty() }?.let(::image),
                    title = title.ifEmpty { null },
                    subtitle = subtitle.ifEmpty { null },
                    meta = meta.ifEmpty { null },
                    shape = when (block.text("shape")) { "rounded" -> EmbedBlock.Shape.Rounded; "square" -> EmbedBlock.Shape.Square; else -> EmbedBlock.Shape.Circle },
                    size = size(block.text("size")),
                    stacked = block.text("layout") == "stacked",
                    bar = block.flag("bar") == true,
                )
            }
            "fields" -> {
                val items = (block["items"] as? JsonArray).orEmpty().mapNotNull { item ->
                    val o = item as? JsonObject ?: return@mapNotNull null
                    val v = fill(o.text("value"), data)
                    if (!v.filled || v.text.isEmpty()) null else fill(o.text("label"), data).text to v.text
                }
                if (items.isEmpty()) null else EmbedBlock.Fields(items, inline = block.text("layout") == "inline")
            }
            "footer" -> {
                val text = fill(block.text("value"), data).text
                val url = link(fill(block.text("url"), data).text)
                if (text.isEmpty() && url == null) null else EmbedBlock.Footer(text.ifEmpty { null }, url)
            }
            "image" -> {
                val v = fill(block.text("value"), data)
                val picture = if (v.filled) image(v.text) else null
                picture?.let { EmbedBlock.Image(it, fill(block.text("alt"), data).text.ifEmpty { null }) }
            }
            "code" -> value()?.let { EmbedBlock.Code(it, fill(block.text("language"), data).text.ifEmpty { null }) }
            "heading" -> value()?.let { EmbedBlock.Heading(it, if ((block["level"] as? JsonPrimitive)?.content == "2") 2 else 1) }
            "text" -> value()?.let {
                EmbedBlock.Text(
                    it,
                    when (block.text("tone")) { "muted" -> EmbedBlock.Tone.Muted; "accent" -> EmbedBlock.Tone.Accent; else -> EmbedBlock.Tone.Normal },
                    size(block.text("size")),
                )
            }
            "markdown" -> value()?.let { EmbedBlock.Markdown(it, size(block.text("size"))) }
            "quote" -> value()?.let { EmbedBlock.Quote(it) }
            "chips" -> value()?.let { v ->
                val chips = v.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (chips.isEmpty()) null else EmbedBlock.Chips(
                    chips,
                    when (block.text("style")) { "lozenge" -> EmbedBlock.ChipStyle.Lozenge; "tag" -> EmbedBlock.ChipStyle.Tag; else -> EmbedBlock.ChipStyle.Pill },
                )
            }
            else -> null
        }
    }

    private fun trimDividers(blocks: List<EmbedBlock>): List<EmbedBlock> {
        val kept = mutableListOf<EmbedBlock>()
        for (block in blocks) {
            if (block != EmbedBlock.Divider) kept += block
            else if (kept.isNotEmpty() && kept.last() != EmbedBlock.Divider) kept += block
        }
        while (kept.lastOrNull() == EmbedBlock.Divider) kept.removeAt(kept.lastIndex)
        return kept
    }
}
