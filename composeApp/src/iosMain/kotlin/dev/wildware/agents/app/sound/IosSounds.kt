package dev.wildware.agents.app.sound

import dev.wildware.agents.app.session.IosTextStore

/** The iPhone app's sounds, for Swift as well: an alert shown over the app leaves its sound to the app. */
object IosSounds {
    private var current: SoundBoard? = null

    /** The one board, with its switch kept at [path]. */
    internal fun board(path: String): SoundBoard = current ?: SoundBoard(IosSoundPlayer(), IosTextStore(path)).also { current = it }

    /**
     * Whether the app plays its own sounds now. When it does, an alert that arrives while the app is
     * open shows without its sound: the app's live feed plays the same one.
     */
    val playsInApp: Boolean get() = current?.playing == true
}
