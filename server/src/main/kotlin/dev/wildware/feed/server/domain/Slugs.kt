package dev.wildware.feed.server.domain

import java.text.Normalizer

/** The three ways a word becomes a slug (spec/02 §7.1, §7.5). Each copies a JavaScript regex chain. */
object Slugs {
    const val MAX_LENGTH = 64
    private val PATTERN = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")

    /** `slugify`: NFKD, drop combining marks, lowercase, runs of anything else become one hyphen. */
    fun slugify(value: String): String {
        val folded = Normalizer.normalize(value, Normalizer.Form.NFKD)
            .filterNot { it in '̀'..'ͯ' }
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
        return folded.trim('-').take(MAX_LENGTH).trim('-')
    }

    fun isSlug(value: String) = value.length <= MAX_LENGTH && PATTERN.matches(value)

    fun assertSlug(value: String): String {
        val normalised = value.jsTrim().lowercase()
        if (!isSlug(normalised)) {
            throw invalid("slug must be lowercase letters, digits and single hyphens, at most $MAX_LENGTH characters")
        }
        return normalised
    }

    /** The slug a project gets: the one asked for, else one made from its name. */
    fun slugFor(name: String, slug: String?): String {
        if (slug != null && slug.jsTrim().isNotEmpty()) return assertSlug(slug)
        val derived = slugify(name)
        if (derived.isEmpty()) throw invalid("could not derive a slug from name; pass slug explicitly")
        return derived
    }

    /** `tagSlug`: no folding, so `café` is `caf`; one hyphen comes off each end. */
    fun tag(name: String): String {
        val dashed = JsText.collapseSpaces(name.jsTrim().lowercase(), "-")
            .filter { it in 'a'..'z' || it in '0'..'9' || it == '-' }
            .replace(Regex("-+"), "-")
        return dashed.removePrefix("-").removeSuffix("-")
    }

    /** `fieldSlug`: dots and underscores survive; every hyphen and dot comes off the ends. */
    fun field(name: String): String {
        val dashed = JsText.collapseSpaces(name.jsTrim().lowercase(), "-")
            .filter { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' || it == '-' }
            .replace(Regex("-+"), "-")
        return dashed.trim('-', '.')
    }
}
