package dev.wildware.feed.server

import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.domain.Agents
import dev.wildware.feed.server.domain.DomainError
import dev.wildware.feed.server.http.Wire
import dev.wildware.feed.server.http.serve
import kotlin.system.exitProcess

/** `feed-server` with no arguments serves; with a command it is the admin CLI. */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        val config = try {
            Config.load()
        } catch (e: Config.Invalid) {
            System.err.println(e.message)
            exitProcess(1)
        }
        if (CrashReports.start(System.getenv())) println("Crash reports: on (SENTRY_DSN)")
        val app = App.open(config)
        println(if (config.databaseUrl != null) "Database: Postgres" else "Database: SQLite in ${config.dataDir}")
        println(config.s3?.let { "Media: bucket ${it.bucket}" } ?: "Media: on disk in ${config.dataDir}")
        println("Listening on http://${config.host}:${config.port}")
        dev.wildware.feed.server.http.FeedServer(app).start(wait = true)
        return
    }
    if (args[0] == "export") exitProcess(Export.run(args.drop(1), System.getenv(), ::println, System.err::println))
    if (args[0] == "migrate") exitProcess(Migrate.run(args.drop(1), System.getenv(), ::println, System.err::println))
    exitProcess(Cli(System.getenv(), ::println, System.err::println).run(args.toList()))
}

/**
 * The admin CLI, with the TS server's commands, output and exit codes (spec/01-config-and-auth.md
 * §10): 0 fine, 1 the command failed, 2 the command line was wrong.
 */
class Cli(private val env: Map<String, String>, private val out: (String) -> Unit, private val err: (String) -> Unit) {
    private val usage = """
        |agent-dashboard <command> [arguments]
        |
        |  mint-token <name>                   Create an agent and print its bearer token. Printed once, never stored.
        |  hash-password <password> | --stdin  Print an argon2id hash for ADMIN_PASSWORD_HASH.
        |  list-tokens [--revoked]             List agents and when they were last seen. Tokens are not recoverable.
        |  rename-token <agent-id> <name>      Give an agent a different display name. The token keeps working.
        |  revoke-token <agent-id>             Switch an agent token off for good.
        |  help                                Print this usage.
        |
        |Configuration is read from the environment; see .env.example.
    """.trimMargin()

    private fun usageError(message: String): Int {
        err(message)
        err("")
        err(usage)
        return 2
    }

    private fun <T> agents(block: (Agents) -> T): T {
        val config = Config.load(env)
        return Database.open(config).use { db -> block(Agents(db, config.tokenSecret)) }
    }

    fun run(args: List<String>): Int {
        val name = args.firstOrNull()
        if (name.isNullOrEmpty()) return usageError("no command given")
        val rest = args.drop(1)
        return try {
            when (name) {
                "help", "--help", "-h" -> { out(usage); 0 }
                "mint-token" -> {
                    val agentName = rest.firstOrNull()
                    if (agentName.isNullOrEmpty()) return usageError("mint-token needs a name, e.g. claude-code@laptop")
                    val (agent, token) = agents { it.mint(agentName) }
                    out("agent ${agent.id}  ${agent.name}")
                    out(token)
                    out("")
                    out("This is the only time the token is shown. Store it in the agent MCP config.")
                    0
                }
                "hash-password" -> {
                    val first = rest.firstOrNull()
                    if (first.isNullOrEmpty()) return usageError("hash-password needs a password, or --stdin to read one")
                    val password = if (first == "--stdin") {
                        System.`in`.readBytes().toString(Charsets.UTF_8).removeSuffix("\n").removeSuffix("\r")
                            .also { if (it.isEmpty()) return usageError("hash-password read an empty password") }
                    } else first
                    out(Passwords.hash(password))
                    out("")
                    out("Quote it in .env — the hash contains '$', which a shell would expand.")
                    0
                }
                "list-tokens" -> {
                    val list = agents { it.list(includeRevoked = "--revoked" in rest) }
                    if (list.isEmpty()) out("no agents yet — mint one with: mint-token <name>")
                    for (a in list) {
                        val seen = a.lastSeenAt?.let(Wire::isoOf) ?: "never seen"
                        out("${a.id}  ${a.name}  ($seen)${if (a.revokedAt != null) "  REVOKED" else ""}")
                    }
                    0
                }
                "rename-token" -> {
                    val id = rest.firstOrNull()
                    val newName = rest.drop(1).joinToString(" ").trim()
                    if (id.isNullOrEmpty() || newName.isEmpty()) return usageError("rename-token needs an agent id and a name (see list-tokens)")
                    val renamed = agents { it.rename(id, newName) }
                    out("${renamed.id} is now ${renamed.name}")
                    0
                }
                "revoke-token" -> {
                    val id = rest.firstOrNull()
                    if (id.isNullOrEmpty()) return usageError("revoke-token needs an agent id (see list-tokens)")
                    out(if (agents { it.revoke(id) }) "revoked $id" else "$id was already revoked")
                    0
                }
                else -> usageError("unknown command: $name")
            }
        } catch (e: DomainError) {
            err(e.message); 1
        } catch (e: Config.Invalid) {
            err(e.message ?: "Invalid configuration"); 1
        }
    }
}
