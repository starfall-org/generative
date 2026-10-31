package org.starfall.generative

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.starfall.generative.shared.model.*
import org.starfall.generative.features.providers.shared.logic.ConfigurationViewModel
import org.starfall.generative.features.settings.logic.SettingsViewModel
import java.util.UUID
import android.os.ParcelFileDescriptor
import kotlinx.serialization.json.*
import org.starfall.generative.features.home.chat.logic.ChatViewModel

/** Explicit ADB-only smoke test; uses the provider already entered on the device, never embeds a credential. */
class HelixLiveTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun discoverChatTitleManualAndAutomaticSummary() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveHelixTest") == "true")
        lateinit var chat: ChatViewModel
        lateinit var config: ConfigurationViewModel
        lateinit var settings: SettingsViewModel
        compose.runOnUiThread {
            val factory = (compose.activity.application as GenerativeApplication).container.viewModelFactory
            val owner = ViewModelProvider(compose.activity, factory)
            chat = owner[ChatViewModel::class.java]
            config = owner[ConfigurationViewModel::class.java]
            settings = owner[SettingsViewModel::class.java]
        }
        val gear = hasContentDescription("Settings") and SemanticsMatcher("chat toolbar settings") {
            it.boundsInRoot.left > compose.activity.resources.displayMetrics.widthPixels / 2
        }
        for (attempt in 0..8) {
            if (compose.onAllNodes(gear).fetchSemanticsNodes().isNotEmpty()) break
            back()
        }
        compose.onNode(gear).performClick()
        val providersMenuLabel = compose.activity.getString(R.string.app_menu_language_providers)
        if (compose.onAllNodesWithText(providersMenuLabel).fetchSemanticsNodes().isEmpty()) back()
        compose.onNodeWithText(providersMenuLabel).performClick()
        val existing = chat.providers.value.find { it.baseUrl.trimEnd('/') == "https://helixmind.online/v1" }
        if (existing == null) {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            val credentials = ParcelFileDescriptor.AutoCloseInputStream(
                automation.executeShellCommand("cat /data/local/tmp/generative-helix-test.json")
            ).bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
            compose.onNodeWithTag("add_provider").performClick()
            compose.onNode(hasSetTextAction() and hasText(text(R.string.common_name))).performScrollTo().performTextReplacement("Helix Test")
            compose.onNode(hasSetTextAction() and hasText(text(R.string.provider_base_url))).performScrollTo().performTextReplacement(credentials.getValue("base").jsonPrimitive.content)
            compose.onNode(hasSetTextAction() and hasText("Platform Default")).performScrollTo().performTextReplacement(credentials.getValue("key").jsonPrimitive.content)
            compose.onNodeWithText(text(R.string.common_save)).performClick()
        } else compose.onNodeWithText(existing.name).performClick()
        compose.waitUntil(20_000) { chat.providers.value.any { it.baseUrl.trimEnd('/') == "https://helixmind.online/v1" } }
        val provider = chat.providers.value.first { it.baseUrl.trimEnd('/') == "https://helixmind.online/v1" }
        compose.onNodeWithText(text(R.string.provider_models)).performClick()
        compose.onNodeWithContentDescription(text(R.string.open_model_catalog)).performClick()
        compose.waitUntil(40_000) { compose.onAllNodesWithText("qwen3.6-35b-a3b").fetchSemanticsNodes().isNotEmpty() }
        if (compose.onAllNodesWithContentDescription(text(R.string.select_all_visible_models)).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithContentDescription(text(R.string.select_all_visible_models)).performClick()
        }
        compose.waitUntil(20_000) {
            chat.providers.value.find { it.id == provider.id }?.config?.modelConfigs?.get("qwen3.6-35b-a3b")?.contextWindowTokens == 262144
        }
        val discovered = chat.providers.value.first { it.id == provider.id }
        assertEquals(5, discovered.config.modelIds?.size)
        assertEquals(1048576, discovered.config.modelConfigs.getValue("deepseek-v4-flash-0731-thinking").contextWindowTokens)
        for (attempt in 0..12) {
            if (compose.onAllNodes(gear).fetchSemanticsNodes().isNotEmpty()) break
            back()
        }

        val modelId = "qwen3.6-35b-a3b"
        val originalModel = discovered.config.modelConfigs.getValue(modelId)
        val oldQuickMcp = chat.toolSettings.value.quickMcp
        val oldHelpers = chat.toolSettings.value.system
        compose.runOnUiThread {
            chat.mcpServers.value.forEach { chat.setQuickMcp(it.id, false) }
            chat.setSystemTool("title_generation", SystemToolConfig(providerId = provider.id, modelId = modelId))
            chat.setSystemTool("chat_summary", SystemToolConfig(providerId = provider.id, modelId = modelId))
            chat.selectModel(provider.id, modelId)
            chat.startNewChat()
        }
        compose.waitUntil(10_000) {
            settings.preferences.value.selectedModel.modelId == modelId && chat.toolSettings.value.system["title_generation"]?.modelId == modelId
        }
        try {
            val prompt = "My project is Cedar. Launch is Friday. The theme is blue. Remember these facts. Reply with only HELIX_CHAT_OK."
            send(prompt)
            compose.waitUntil(120_000) { !chat.isGenerating.value && chat.currentConversation.value?.messages?.lastOrNull()?.content?.contains("HELIX_CHAT_OK") == true }
            val fallback = prompt
            compose.waitUntil(120_000) { chat.currentConversation.value?.title?.let { it != fallback && it != "New Chat" } == true }
            assertNull(chat.chatError.value)

            compose.runOnUiThread { assertTrue(chat.startConversationSummary(ConversationSummaryRequest(200))) }
            compose.waitUntil(120_000) { chat.currentConversation.value?.summary?.content?.isNotBlank() == true && chat.summaryProgress.value == null }
            send("What is my project, launch day and theme color? Answer in one short English sentence.")
            compose.waitUntil(120_000) { !chat.isGenerating.value && chat.currentConversation.value?.messages?.lastOrNull()?.role == ChatRole.MODEL }
            val answer = chat.currentConversation.value!!.messages.last().content.lowercase()
            assertTrue(answer.contains("cedar") && answer.contains("friday") && answer.contains("blue"))

            compose.runOnUiThread { config.saveModelConfiguration(provider.id, modelId, originalModel.copy(contextWindowTokens = 1000)) }
            compose.waitUntil(10_000) { chat.providers.value.first { it.id == provider.id }.config.modelConfigs[modelId]?.contextWindowTokens == 1000 }
            val now = System.currentTimeMillis()
            val facts = "Project Cedar launches Friday with a blue theme. ".repeat(90).take(3700)
            val fixture = Conversation(UUID.randomUUID().toString(), "Helix ADB Context Test", now, now,
                messages = listOf(StoredMessage("near", ChatRole.USER, listOf(MessageVersion(content = facts)))))
            compose.runOnUiThread { chat.selectConversation(fixture) }
            compose.waitUntil(10_000) { chat.contextWindowStatus.value?.shouldSuggestSummary == true }
            assertFalse(chat.contextWindowStatus.value!!.shouldAutoSummarize)
            compose.onNodeWithText(compose.activity.getString(R.string.context_summary_suggestion, chat.contextWindowStatus.value!!.percentage)).assertIsDisplayed()
            compose.runOnUiThread {
                chat.selectConversation(fixture.copy(messages = fixture.messages + StoredMessage("limit", ChatRole.USER,
                    listOf(MessageVersion(content = "Keep the project facts accurate. ".repeat(12))))))
            }
            compose.waitUntil(180_000) { chat.currentConversation.value?.summary?.throughMessageId == "limit" && chat.summaryProgress.value == null }
            assertTrue(chat.currentConversation.value!!.summary!!.content.isNotBlank())
            assertFalse(chat.contextWindowStatus.value!!.shouldAutoSummarize)
        } finally {
            compose.runOnUiThread {
                config.saveModelConfiguration(provider.id, modelId, originalModel)
                chat.mcpServers.value.forEach { chat.setQuickMcp(it.id, oldQuickMcp[it.id] ?: true) }
                oldHelpers["title_generation"]?.let { chat.setSystemTool("title_generation", it) }
                oldHelpers["chat_summary"]?.let { chat.setSystemTool("chat_summary", it) }
            }
            compose.waitUntil(10_000) { chat.providers.value.first { it.id == provider.id }.config.modelConfigs[modelId]?.contextWindowTokens == originalModel.contextWindowTokens }
        }
    }

    private fun text(id: Int) = compose.activity.getString(id)
    private fun back() {
        ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("input keyevent 4")).use { it.readBytes() }
        compose.waitForIdle()
    }
    private fun send(text: String) {
        compose.onNode(hasSetTextAction()).performTextReplacement(text)
        compose.onNodeWithContentDescription("Send message").performClick()
    }
}
