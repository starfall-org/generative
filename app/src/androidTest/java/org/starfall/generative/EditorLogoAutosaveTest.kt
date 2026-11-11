package org.starfall.generative

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.starfall.generative.features.mcp.editor.ui.ServerEditor
import org.starfall.generative.features.providers.editor.ui.ModelEditor
import org.starfall.generative.features.providers.editor.ui.ProviderEditor
import org.starfall.generative.features.providers.list.ui.ProviderGroupNameDialog
import org.starfall.generative.features.speech.ui.SpeechEditor
import org.starfall.generative.shared.model.*
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.provider.*

class EditorLogoAutosaveTest {
    @get:Rule val compose = createComposeRule()

    private val provider = ModelProvider("provider", "Original provider", ModelApiType.OPENAI,
        baseUrl = "https://example.test/v1", icon = "old.png",
        config = ProviderConfiguration(modelConfigs = mapOf("model" to ModelConfiguration(icon = "old.png", temperature = 0.4))))
    private val server = McpInfo("server", "Original MCP", url = "https://example.test/mcp", icon = "old.png")
    private val speech = SpeechService("speech", "Original speech", icon = "old.png")

    @Test fun removingLogoSavesImmediatelyInEveryEntityEditorWithoutClosingIt() {
        var page by mutableIntStateOf(0)
        lateinit var removeLabel: String
        lateinit var saveLabel: String
        val saved = mutableListOf<Int>()
        fun savedIcon(icon: String?) { assertNull(icon); saved += page }
        compose.setScreenContent { MaterialTheme {
            removeLabel = stringResource(R.string.common_remove)
            saveLabel = stringResource(R.string.common_save)
            key(page) {
                when (page) {
                    0 -> ProviderEditor(provider, false, onSaveModels = { _, _ -> }, onReorderModels = { _, _ -> },
                        onDismiss = { fail("Logo changes must keep the editor open") }, onSave = { fail("Manual Save must not be needed") },
                        onSaveIcon = { savedIcon(it.icon) })
                    1 -> ModelEditor(provider, "model", setOf("model"), onSave = { _, _ -> fail("Manual Save must not be needed") },
                        onBack = {}, onSaveIcon = { id, icon -> assertEquals("model", id); savedIcon(icon) })
                    2 -> ServerEditor(server, false, onRefreshTools = { Result.success(emptyList()) },
                        onAuthorizeOAuth = { Result.success(it) }, onClearOAuth = { it },
                        cachedTools = null, cachedError = null, cachedLoading = false, toolSettings = ToolSettings(),
                        onSetToolEnabled = { _, _, _ -> }, onSetToolParameter = { _, _, _, _ -> },
                        onDismiss = {}, onSave = { fail("Manual Save must not be needed") }, onSaveIcon = { savedIcon(it.icon) })
                    3 -> SpeechEditor(speech, false, emptyList(), false, onTest = { _, _ -> }, onDismiss = {},
                        onSave = { fail("Manual Save must not be needed") }, onSaveIcon = { savedIcon(it.icon) })
                    4 -> ProviderGroupNameDialog("Edit group", "Group", "old.png", onDismiss = {},
                        onSave = { _, _ -> fail("Manual Save must not be needed") }, onSaveIcon = { _, icon -> savedIcon(icon) })
                }
            }
        } }
        repeat(5) { index ->
            compose.runOnIdle { page = index }
            compose.onNodeWithContentDescription(removeLabel).assertExists("Editor $index must offer logo removal").performClick()
            compose.runOnIdle { assertEquals((0..index).toList(), saved) }
            compose.onNodeWithText(saveLabel).assertExists()
        }
    }

}
