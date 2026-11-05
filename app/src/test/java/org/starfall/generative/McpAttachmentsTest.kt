package org.starfall.generative

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.starfall.generative.features.home.chat.logic.tool.McpAttachments
import org.starfall.generative.shared.model.ToolDefinition
import org.starfall.generative.database.preferences.McpAttachmentMode
import org.starfall.generative.database.preferences.McpAttachmentSettings
import org.starfall.generative.database.preferences.McpDynamicStrategy

class McpAttachmentsTest {
    private fun entries(count: Int, toolsEach: Int = 4) = (1..count).map { index ->
        McpAttachments.Entry("id$index", "Server $index", (1..toolsEach).map { tool ->
            ToolDefinition("s${index}_t$tool", "desc", buildJsonObject {}, "id$index", "t$tool")
        })
    }

    private fun McpAttachments.attachedIds(count: Int) = (1..count).map { "id$it" }.filter { includes(it) }

    @Test fun classicAttachesEverythingAndOnlyDropsDescriptionsWhenAsked() {
        val withDescriptions = McpAttachments(entries(8), McpAttachmentSettings(mode = McpAttachmentMode.CLASSIC))
        assertFalse(withDescriptions.active)
        assertEquals(8, withDescriptions.attachedIds(8).size)
        assertEquals("desc", withDescriptions.present(entries(1).single().tools.first()).description)

        val without = McpAttachments(entries(8), McpAttachmentSettings(mode = McpAttachmentMode.CLASSIC, includeToolDescription = false))
        assertEquals("", without.present(entries(1).single().tools.first()).description)
    }

    @Test fun dynamicNeedsBothMinimums() {
        assertFalse(McpAttachments(entries(4), McpAttachmentSettings()).active)
        assertFalse(McpAttachments(entries(5, toolsEach = 3), McpAttachmentSettings()).active)
        assertTrue(McpAttachments(entries(5), McpAttachmentSettings()).active)
        assertTrue(McpAttachments(entries(2, toolsEach = 1), McpAttachmentSettings(minMcps = 0, minTools = 0)).active)
        assertTrue(McpAttachments(entries(3, toolsEach = 1), McpAttachmentSettings(minMcps = 3, minTools = 0)).active)
    }

    @Test fun alwaysAttachedProviderDoesNotCountTowardDynamicThresholds() {
        val regular = entries(4)
        val pinned = entries(1).single().let { entry ->
            entry.copy(
                serverId = "web",
                tools = entry.tools.map { it.copy(serverId = "web") },
                alwaysAttached = true
            )
        }
        val attachments = McpAttachments(
            regular + pinned,
            McpAttachmentSettings(minMcps = 5, minTools = 0, strategy = McpDynamicStrategy.ALL)
        )
        assertFalse(attachments.active)
        assertEquals(listOf("id1", "id2", "id3", "id4"), attachments.attachedIds(4))
        assertTrue(attachments.includes("web"))
    }

    @Test fun pinnedProviderRemainsAttachedWhenDynamicThresholdsAreMet() {
        val regular = entries(5)
        val pinned = entries(1).single().let { entry ->
            entry.copy(
                serverId = "web",
                tools = entry.tools.map { it.copy(serverId = "web") },
                alwaysAttached = true
            )
        }
        val attachments = McpAttachments(regular + pinned, McpAttachmentSettings(minMcps = 5, minTools = 0, strategy = McpDynamicStrategy.ALL))
        assertTrue(attachments.active)
        assertTrue(attachments.includes("web"))
        assertFalse(attachments.includes("id1"))
    }

    @Test fun alwaysAttachedProviderToolsDoNotCountTowardToolThreshold() {
        val regular = entries(5, toolsEach = 3)
        val pinned = entries(1, toolsEach = 5).single().let { entry ->
            entry.copy(
                serverId = "web",
                tools = entry.tools.map { it.copy(serverId = "web") },
                alwaysAttached = true
            )
        }
        val attachments = McpAttachments(
            regular + pinned,
            McpAttachmentSettings(minMcps = 0, minTools = 20, strategy = McpDynamicStrategy.ALL)
        )
        assertFalse(attachments.active)
        assertTrue(attachments.includes("web"))
        assertTrue(attachments.includes("id1"))
    }

    @Test fun descriptionPreferenceAppliesToPreAttachedMcpButNotOnDemandMcp() {
        val allEntries = entries(8)
        val attachments = McpAttachments(
            allEntries,
            McpAttachmentSettings(strategy = McpDynamicStrategy.OVERFLOW, includeToolDescription = false)
        )
        attachments.execute("connect_mcps", buildJsonObject {
            put("mcp_ids", JsonArray(listOf(JsonPrimitive("id5")))); put("detailed", JsonPrimitive(true))
        })
        // Overflow's first four MCPs are attached beforehand and respect the setting.
        assertEquals("", attachments.present(allEntries[0].tools.first()).description)
        // A connected on-demand MCP uses connect_mcps' detailed flag rather than the global setting.
        assertEquals("desc", attachments.present(allEntries[4].tools.first()).description)
    }

    @Test fun dynamicDefinitionsUseClearActionNames() {
        val definitions = McpAttachments(entries(8), McpAttachmentSettings(strategy = McpDynamicStrategy.ALL)).definitions()
        assertEquals(listOf("list_mcps", "connect_mcps"), definitions.map { it.name })
        assertTrue(definitions[0].description.contains("connect_mcps"))
        assertTrue(definitions[1].description.contains("list_mcps"))
    }

    @Test fun overflowKeepsMcpsBelowTheMinimumClassic() {
        val attachments = McpAttachments(entries(8), McpAttachmentSettings())
        assertEquals(listOf("id1", "id2", "id3", "id4"), attachments.attachedIds(8))
        val listed = attachments.execute("list_mcps", buildJsonObject {})["mcps"]!!.jsonArray
        assertEquals(listOf("id5", "id6", "id7", "id8"), listed.map { it.jsonObject["mcp_id"]!!.jsonPrimitive.content })
    }

    @Test fun allLoadsEveryMcpOnDemandAndLimitsLoadedOnes() {
        val attachments = McpAttachments(entries(8), McpAttachmentSettings(strategy = McpDynamicStrategy.ALL, loadLimitAll = 2))
        assertTrue(attachments.attachedIds(8).isEmpty())
        val tool = entries(1).single().tools.first()

        attachments.execute("connect_mcps", buildJsonObject { put("mcp_ids", JsonArray(listOf(JsonPrimitive("id1")))) })
        assertEquals(listOf("id1"), attachments.attachedIds(8))
        assertEquals("", attachments.present(tool).description)

        attachments.execute("connect_mcps", buildJsonObject {
            put("mcp_ids", JsonArray(listOf(JsonPrimitive("id2"), JsonPrimitive("id3")))); put("detailed", JsonPrimitive(true))
        })
        // id1 was the oldest, so it is unloaded to stay within the limit.
        assertEquals(listOf("id2", "id3"), attachments.attachedIds(8))
    }

    private fun McpAttachments.load(vararg ids: String) =
        replayLoad(buildJsonObject { put("mcp_ids", JsonArray(ids.map { JsonPrimitive(it) })) })

    @Test fun loadedMcpUnloadsAfterThreeUserMessagesWithoutUse() {
        val attachments = McpAttachments(entries(8), McpAttachmentSettings(strategy = McpDynamicStrategy.ALL))
        attachments.startUserTurn()
        attachments.load("id1", "id2")
        attachments.startUserTurn()
        attachments.markUsed("id2")
        attachments.startUserTurn()
        assertEquals(listOf("id1", "id2"), attachments.attachedIds(8))
        attachments.startUserTurn()
        // id1 was last active 3 user messages ago; id2 was used one message later.
        assertEquals(listOf("id2"), attachments.attachedIds(8))
        attachments.startUserTurn()
        attachments.startUserTurn()
        assertTrue(attachments.attachedIds(8).isEmpty())
    }
}
