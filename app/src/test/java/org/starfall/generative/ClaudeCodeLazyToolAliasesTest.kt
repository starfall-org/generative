package org.starfall.generative

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.integrations.modelapis.anthropic.aliasClaudeCodeTools
import org.starfall.generative.integrations.modelapis.anthropic.restoreClaudeCodeToolNames
import org.starfall.generative.shared.helpers.networking.obj
import org.starfall.generative.shared.helpers.networking.str
import org.starfall.generative.shared.helpers.networking.text

class ClaudeCodeLazyToolAliasesTest {
    @Test fun historicalNamesAreRegisteredBeforeLazyContentIsRead() {
        var reads = 0
        val original = listOf("declared", "history_only").map { name ->
            obj("type" to str("tool_use"), "name" to str(name), "input" to obj())
        }
        val blocks = JsonArray(object : AbstractList<JsonElement>() {
            override val size: Int get() = original.size
            override fun get(index: Int): JsonElement { reads++; return original[index] }
        })
        val request = obj("tools" to JsonArray(listOf(obj("name" to str("declared")))),
            "messages" to JsonArray(listOf(obj("role" to str("assistant"), "content" to blocks))))
        val aliased = aliasClaudeCodeTools(request)
        assertEquals(2, reads)
        val mapped = aliased.getValue("messages").jsonArray[0].jsonObject.getValue("content").jsonArray
        val alias = mapped[1].jsonObject.text("name")
        assertNotEquals("history_only", alias)
        assertEquals(3, reads)
        val response = obj("content" to JsonArray(listOf(obj("type" to str("tool_use"),
            "name" to str(alias), "input" to obj()))))
        val restored = restoreClaudeCodeToolNames(request, response)
        assertEquals("history_only", restored.getValue("content").jsonArray[0].jsonObject.text("name"))
    }
}
