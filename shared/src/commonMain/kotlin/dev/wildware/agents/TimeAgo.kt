package dev.wildware.agents

/** How long ago something happened, said out loud: "just now", "five minutes ago", "yesterday". */
object TimeAgo {
    fun spoken(thenMillis: Long, nowMillis: Long): String {
        val seconds = ((nowMillis - thenMillis) / 1000).coerceAtLeast(0)
        val minutes = (seconds + 30) / 60
        val hours = (minutes + 30) / 60
        val days = (hours + 12) / 24
        return when {
            seconds < 45 -> "just now"
            minutes <= 1 -> "a minute ago"
            minutes < 45 -> "${words(minutes)} minutes ago"
            hours <= 1 -> "an hour ago"
            hours < 22 -> "${words(hours)} hours ago"
            days <= 1 -> "yesterday"
            else -> "${words(days)} days ago"
        }
    }

    private val small = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve",
    )

    private fun words(n: Long): String = small.getOrNull(n.toInt())?.takeIf { n <= 12 } ?: n.toString()
}
