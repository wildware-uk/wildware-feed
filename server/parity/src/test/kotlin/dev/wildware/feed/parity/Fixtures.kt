package dev.wildware.feed.parity

import kotlinx.serialization.json.JsonObject

/** Small builders the tests share. Each makes fresh rows with unique names, so tests never collide. */
object Fixtures {
    class Project(val id: String, val slug: String, val name: String)

    suspend fun project(agent: Agent = Parity.alpha, name: String = "Parity ${Parity.suffix()}"): Project {
        val p = agent.ok("create_project", obj("name" to name))["project"].o
        return Project(p["id"].s, p["slug"].s, p["name"].s)
    }

    suspend fun update(project: Project, agent: Agent = Parity.alpha, body: String = "update ${Parity.suffix()}", extra: JsonObject = obj()): JsonObject =
        agent.ok("post_update", JsonObject(obj("project" to project.slug, "body" to body) + extra))["update"].o

    suspend fun message(agent: Agent = Parity.alpha, args: JsonObject): JsonObject =
        agent.ok("post_message", args)["message"].o
}
