package dev.wildware.agents

import kotlinx.serialization.Serializable

/**
 * Which alerts this device wants: kinds, and for updates their levels and priorities. A list left
 * out (null) allows everything on that axis, which is also what a device that never chose gets.
 * The dashboard applies the same rule to the pushes it sends; the desktop and Android apply it to
 * the alerts they raise themselves.
 */
@Serializable
data class AlertPrefs(
    val types: List<String>? = null,
    val levels: List<String>? = null,
    val priorities: List<String>? = null,
) {
    enum class Axis { Types, Levels, Priorities }

    companion object {
        const val Request = "request"
        const val Message = "message"
        const val Comment = "comment"
        const val Update = "update"

        /** Every choice on each axis, in the order the settings show them. */
        val AllTypes = listOf(Request, Message, Comment, Update)
        val AllLevels = listOf("error", "warn", "success", "info")
        val AllPriorities = listOf("high", "medium", "low")

        /** Whether [prefs] lets an alert of [type] through; [level] and [priority] only matter for updates. */
        fun allows(prefs: AlertPrefs?, type: String, level: String? = null, priority: String? = null): Boolean {
            if (prefs == null) return true
            if (prefs.types != null && type !in prefs.types) return false
            if (type != Update) return true
            if (level != null && prefs.levels != null && level !in prefs.levels) return false
            if (priority != null && prefs.priorities != null && priority !in prefs.priorities) return false
            return true
        }

        /** Whether [value] is on, along [axis]. */
        fun isOn(prefs: AlertPrefs?, axis: Axis, value: String): Boolean {
            val list = when (axis) {
                Axis.Types -> prefs?.types
                Axis.Levels -> prefs?.levels
                Axis.Priorities -> prefs?.priorities
            }
            return list == null || value in list
        }

        /**
         * [prefs] with [value] switched along [axis]. An axis left out counts as "everything", so the
         * first switch off lists the rest; an axis back to everything is left out again, and a
         * choice that is everything everywhere is null.
         */
        fun toggle(prefs: AlertPrefs?, axis: Axis, value: String): AlertPrefs? {
            val all = when (axis) {
                Axis.Types -> AllTypes
                Axis.Levels -> AllLevels
                Axis.Priorities -> AllPriorities
            }
            val current = when (axis) {
                Axis.Types -> prefs?.types
                Axis.Levels -> prefs?.levels
                Axis.Priorities -> prefs?.priorities
            } ?: all
            val next = if (value in current) current - value else all.filter { it in current || it == value }
            val stored = next.takeUnless { it.size == all.size && it.containsAll(all) }
            val base = prefs ?: AlertPrefs()
            val changed = when (axis) {
                Axis.Types -> base.copy(types = stored)
                Axis.Levels -> base.copy(levels = stored)
                Axis.Priorities -> base.copy(priorities = stored)
            }
            return changed.takeUnless { it.types == null && it.levels == null && it.priorities == null }
        }
    }
}
