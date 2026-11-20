package dev.wildware.agents

/**
 * Where the owner has been, for Back and Forward, as a browser keeps it: a list of places and a
 * mark at the one showing. Visiting somewhere new drops what was ahead of the mark. It keeps the
 * newest [limit] places; older ones fall off the far end.
 *
 * A visit to the place just behind or just ahead of the mark moves the mark there instead of adding
 * a copy. So closing a thread by hand, after opening it, is the same as going Back, and Back after
 * that goes where the owner was before the thread, not into the thread again.
 */
class NavHistory<T>(initial: T, val limit: Int = DefaultLimit) {
    init {
        require(limit >= 1) { "a history keeps at least one place" }
    }

    private val places = ArrayList<T>().apply { add(initial) }
    private var at = 0

    /** The place showing now. */
    val current: T get() = places[at]

    val canGoBack: Boolean get() = at > 0
    val canGoForward: Boolean get() = at < places.lastIndex

    /** How many places it holds, for tests. */
    val size: Int get() = places.size

    /** What a [visit] did. */
    enum class Move { Stayed, Added, Back, Forward }

    /** The owner is now at [place]. */
    fun visit(place: T): Move {
        if (place == current) return Move.Stayed
        if (canGoBack && places[at - 1] == place) {
            at--
            return Move.Back
        }
        if (canGoForward && places[at + 1] == place) {
            at++
            return Move.Forward
        }
        while (places.lastIndex > at) places.removeAt(places.lastIndex)
        places.add(place)
        at++
        while (places.size > limit) {
            places.removeAt(0)
            at--
        }
        return Move.Added
    }

    /** Steps back one place and returns it, or null at the start. */
    fun back(): T? {
        if (!canGoBack) return null
        at--
        return current
    }

    /** Steps forward one place and returns it, or null at the end. */
    fun forward(): T? {
        if (!canGoForward) return null
        at++
        return current
    }

    /** Changes what the current place is, without adding one: the app settling where it started. */
    fun replace(place: T) {
        places[at] = place
    }

    companion object {
        const val DefaultLimit = 50
    }
}
