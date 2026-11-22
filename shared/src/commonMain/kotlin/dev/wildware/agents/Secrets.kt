package dev.wildware.agents

import kotlinx.serialization.Serializable

/**
 * The dashboard's shared secrets, as `GET /api/secrets` lists them: names and who may use them,
 * never values (docs/SECRETS.md). [enabled] is false when the server has no SECRETS_KEY.
 */
@Serializable
data class SecretsSnapshot(val enabled: Boolean = false, val secrets: List<SecretView> = emptyList())

/** One secret, without its value. Times are ISO-8601. [createdBy] is "owner" or "agent:<id>". */
@Serializable
data class SecretView(
    val id: String,
    val name: String,
    val note: String? = null,
    val allAgents: Boolean = false,
    val agents: List<SecretAgent> = emptyList(),
    val bytes: Int = 0,
    val createdBy: String = "owner",
    val createdAt: String,
    val updatedAt: String,
    val pausedAt: String? = null,
    val lastUsedAt: String? = null,
)

@Serializable
data class SecretAgent(val id: String, val name: String? = null)

/** One line of the audit: who did what to which secret, when. [actor] kind is "owner", "agent" or "server". */
@Serializable
data class SecretAuditEntry(
    val id: String,
    val secretId: String? = null,
    val secretName: String,
    val action: String,
    val actor: SecretActor,
    val detail: String? = null,
    val at: String,
)

@Serializable
data class SecretActor(val kind: String, val id: String? = null, val name: String? = null)

/**
 * A value the owner asked to see. [toString] leaves it out, so it never reaches a log by accident.
 * [encoding] is "utf8", or "base64" for bytes that are not text.
 */
@Serializable
class RevealedSecret(val value: String, val encoding: String = "utf8") {
    override fun toString() = "RevealedSecret(${value.length} characters, $encoding)"
}

/**
 * One box on a question that asks for several secrets: the [name] its value is kept under, the
 * agent's [label] above it and [hint] under it, whether it takes several lines, and whether it may
 * be left empty. Never a value.
 */
@Serializable
data class SecretFieldView(
    val name: String,
    val label: String? = null,
    val hint: String? = null,
    val multiline: Boolean = false,
    val optional: Boolean = false,
)

/** Who may use a secret: every agent, or the named ones. */
data class SecretScope(val allAgents: Boolean, val agentIds: List<String>)

@Serializable
internal data class SecretBody(val secret: SecretView)

@Serializable
internal data class SecretAuditBody(val entries: List<SecretAuditEntry> = emptyList())
