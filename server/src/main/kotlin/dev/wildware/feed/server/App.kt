package dev.wildware.feed.server

import dev.wildware.feed.server.auth.SlidingLimiter
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.domain.Agents
import dev.wildware.feed.server.domain.Devices
import dev.wildware.feed.server.domain.Reads

/**
 * Everything one running server holds: its settings, the database and the in-memory state that a
 * restart forgets (limiters). The desktop app builds one of these to host the server itself.
 */
class App(val config: Config, val db: Database, val clock: () -> Long = System::currentTimeMillis) : AutoCloseable {
    val agents = Agents(db, config.tokenSecret, clock)
    val devices = Devices(db, config.tokenSecret, clock)
    val reads = Reads(db, clock)

    /** Shared by the login form and device sign-in: 5 wrong guesses in 15 minutes per address. */
    val loginLimiter = SlidingLimiter(limit = 5, windowMs = 900_000, clock = clock)

    /** 120 requests a minute per agent token, one limiter per route as in the TS server. */
    val mcpLimiter = SlidingLimiter(limit = 120, windowMs = 60_000, clock = clock)
    val agentStreamLimiter = SlidingLimiter(limit = 120, windowMs = 60_000, clock = clock)

    override fun close() = db.close()

    companion object {
        fun open(config: Config) = App(config, Database.open(config.dataDir))
    }
}
