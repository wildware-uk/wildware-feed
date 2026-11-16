package dev.wildware.agents

/**
 * Turns what someone said ("the garden game agent") into the project they meant.
 * Siri hands over loose phrases, so filler words are ignored and the closest name wins.
 */
object ProjectMatcher {
    private val filler = setOf("the", "agent", "agents", "project", "board", "for", "a", "an", "my", "on", "in", "to")

    fun bestMatch(spoken: String, projects: List<ProjectRef>): ProjectRef? {
        val wanted = words(spoken)
        if (wanted.isEmpty()) return null
        return projects
            .map { it to score(wanted, it) }
            .filter { (_, score) -> score > 0.0 }
            .maxByOrNull { (_, score) -> score }
            ?.first
    }

    private fun score(wanted: List<String>, project: ProjectRef): Double {
        val names = listOf(words(project.name), words(project.slug.replace('-', ' ')))
        return names.maxOf { have ->
            when {
                have.isEmpty() -> 0.0
                have == wanted -> 3.0
                have.joinToString("") == wanted.joinToString("") -> 2.5
                else -> {
                    val shared = wanted.count { word -> have.any { it == word || it.startsWith(word) || word.startsWith(it) } }
                    shared.toDouble() / maxOf(wanted.size, have.size)
                }
            }
        }
    }

    private fun words(text: String): List<String> =
        text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() && it !in filler }
}
