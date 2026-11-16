package dev.wildware.agents

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ContentExtensionCategoriesTest {
    @Test
    fun `the content extension lists every request category`() {
        val spec = File("../iosApp/project.yml").readText()
        val listed = Regex("""UNNotificationExtensionCategory:\s*\[([^\]]*)]""")
            .find(spec)!!.groupValues[1].split(",").map { it.trim() }

        val missing = RequestActions.categoryIds - listed.toSet()
        assertTrue(missing.isEmpty(), "Add $missing to UNNotificationExtensionCategory in iosApp/project.yml")
    }
}
