package org.starfall.generative

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.starfall.generative.shared.model.*
import org.starfall.generative.shared.subfeatures.modelpicker.ui.ModelCapabilityBadges
import org.starfall.generative.features.providers.editor.ui.ProviderEditor
import org.starfall.generative.features.providers.editor.ui.ModelEditor
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.language.DiscoveredModel
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.shared.model.provider.ProviderConfiguration
import org.starfall.generative.shared.model.provider.ProviderAuxiliaryChange
import org.starfall.generative.shared.model.provider.withAuxiliaryChange

class ProviderEditorBehaviorTest {
    @Test fun modelJsonExpandsAndManualModelsShowEmptyObject() {
        compose.setScreenContent { MaterialTheme {
            ModelEditor(provider, "", emptySet(), onSave = { _, _ -> }, onBack = {})
        } }
        compose.onNodeWithTag("model-json-code").assertDoesNotExist()
        compose.onNodeWithTag("model-json-toggle").performScrollTo().performClick()
        compose.onNodeWithText("{}").assertExists()
        compose.onNodeWithTag("model-json-toggle").performClick()
        compose.onNodeWithTag("model-json-code").assertDoesNotExist()
    }

    @Test fun editingFetchedModelShowsAndPreservesApiJson() {
        val raw = buildJsonObject { put("id", "custom-model"); put("context_window", 32000); put("owned_by", "vendor") }
        var saved: ModelConfiguration? = null
        compose.setScreenContent { MaterialTheme {
            ModelEditor(provider.copy(config = ProviderConfiguration(modelConfigs = mapOf(
                "custom-model" to ModelConfiguration(modelJson = raw, contextWindowTokens = 32000)
            ))), "custom-model", setOf("custom-model"), onSave = { _, config -> saved = config }, onBack = {})
        } }
        compose.onNodeWithTag("model-json-toggle").performScrollTo().performClick()
        compose.onNode(hasText("\"owned_by\": \"vendor\"", substring = true)).assertExists()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertEquals(raw, saved!!.modelJson)
            assertEquals(32000, saved.contextWindowTokens)
        }
    }

    @Test fun faviconDialogShowsUrlValidationResetAndClose() {
        compose.setScreenContent { MaterialTheme {
            org.starfall.generative.shared.subfeatures.icons.ui.IconPickerRow(null, onChange = {})
        } }
        compose.onNodeWithContentDescription("Get logo automatically").performClick()
        compose.onNode(hasSetTextAction() and hasText("Custom homepage URL")).assertIsDisplayed()
        compose.onNodeWithText("Get", useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Enter a homepage or image URL or set the provider Base URL.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Reset").performClick()
        compose.onNode(hasSetTextAction() and hasText("Custom homepage URL")).assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Custom homepage URL").assertDoesNotExist()
    }

    @Test fun streamingSegmentsAndPassbackFollowReasoning() {
        var saved: ModelConfiguration? = null
        compose.setScreenContent { MaterialTheme {
            ModelEditor(provider, "custom-model", setOf("custom-model"),
                onSave = { _, config -> saved = config }, onBack = {})
        } }
        compose.onNodeWithText("Send Thinking Content").performScrollTo().assertIsDisplayed()
compose.onNode(hasText("Reasoning")).performScrollTo().performClick()
        compose.onNodeWithText("Send Thinking Content").assertDoesNotExist()
        compose.onNode(hasText("Reasoning")).performScrollTo().performClick()
        compose.onNodeWithText("Send Thinking Content").assertExists()
        listOf("On" to true, "Off" to false, "Default" to null).forEach { (label, value) ->
            compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("streaming-mode")))
                .performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithText("Save").performClick()
            compose.runOnIdle { assertEquals(value, saved?.supportStream) }
        }
    }

    @Test fun providerInitialsAndModelBadgesUpdateWithType() {
        var config by androidx.compose.runtime.mutableStateOf(ModelConfiguration(contextWindowTokens = 128_000))
        compose.setScreenContent { MaterialTheme {
            androidx.compose.foundation.layout.Column {
                org.starfall.generative.shared.subfeatures.icons.ui.EntityIcon(null, text =
                    org.starfall.generative.shared.subfeatures.icons.ui.providerInitials("My Provider Name"))
                org.starfall.generative.shared.subfeatures.modelpicker.ui.ModelCapabilityBadges(config)
            }
        } }
        compose.onNodeWithText("MP").assertIsDisplayed()
        compose.onNodeWithText("Chat").assertIsDisplayed()
        compose.onNodeWithText("128k").assertIsDisplayed()
        assertTrue(compose.onNodeWithText("Chat").fetchSemanticsNode().boundsInRoot.right <
            compose.onNodeWithText("128k").fetchSemanticsNode().boundsInRoot.left)
        compose.onNodeWithText("Image").assertDoesNotExist()
        compose.onNodeWithContentDescription("Image").assertExists()
        compose.onNodeWithContentDescription("Thinking").assertExists()
        compose.onNodeWithContentDescription("Tools").assertExists()
        compose.runOnIdle { config = config.copy(contextWindowTokens = 1_000_000) }
        compose.onNodeWithText("1M").assertIsDisplayed()
        compose.runOnIdle { config = config.copy(modelType = ModelType.IMAGE_GENERATION) }
        compose.onNodeWithText(ModelType.IMAGE_GENERATION.displayName).assertIsDisplayed()
        compose.onNodeWithText("1M").assertDoesNotExist()
        compose.onNodeWithContentDescription("Image").assertDoesNotExist()
        compose.onNodeWithContentDescription("Thinking").assertDoesNotExist()
        compose.onNodeWithContentDescription("Tools").assertDoesNotExist()
    }

    @Test fun newChatCompletionsProviderStartsWithNameAndBaseUrl() {
        var saved: ModelProvider? = null
        compose.setScreenContent { MaterialTheme {
            ProviderEditor(provider.copy(name = "", baseUrl = ""), true,
                onSaveModels = { _, _ -> }, onReorderModels = { _, _ -> },
                onDismiss = {}, onSave = { saved = it })
        } }

        compose.onNode(hasSetTextAction() and hasText("OpenAI")).assertExists()
        compose.onNode(hasSetTextAction() and hasText("Base URL"))
            .assertTextContains("https://api.openai.com/v1")
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertEquals("OpenAI", saved?.name)
            assertEquals("https://api.openai.com/v1", saved?.baseUrl)
        }
    }

    @Test fun choosingChatCompletionsPrefillsDefaultsWithoutOverwritingCustomValues() {
        val responses = provider.copy(type = ModelApiType.OPENAI_RESPONSES,
            name = ModelApiType.OPENAI_RESPONSES.defaultName,
            baseUrl = ModelApiType.OPENAI_RESPONSES.defaultBaseUrl)
        compose.setScreenContent { MaterialTheme {
            ProviderEditor(responses, false,
                onSaveModels = { _, _ -> }, onReorderModels = { _, _ -> },
                onDismiss = {}, onSave = {})
        } }

        compose.onNodeWithText("API Type").assertIsDisplayed()
        compose.onNodeWithTag("provider-type-selector").assertHasClickAction().performClick()
        compose.onNodeWithText("Chat Completions").performClick()
        compose.onNode(hasSetTextAction() and hasText("OpenAI")).assertExists()
        compose.onNode(hasSetTextAction() and hasText("Base URL"))
            .assertTextContains("https://api.openai.com/v1")

        compose.onNodeWithTag("provider-type-selector").performClick()
        compose.onNodeWithText("OpenAI Responses").performClick()
        compose.onNode(hasSetTextAction() and hasText("OpenAI Responses")).assertExists()

        compose.onNode(hasSetTextAction() and hasText("OpenAI Responses"))
            .performTextReplacement("Custom gateway")
        compose.onNode(hasSetTextAction() and hasText("Base URL"))
            .performTextReplacement("https://example.test/v1")
        compose.onNodeWithTag("provider-type-selector").performClick()
        compose.onNodeWithText("Chat Completions").performClick()
        compose.onNode(hasSetTextAction() and hasText("Custom gateway")).assertExists()
        compose.onNode(hasSetTextAction() and hasText("Base URL"))
            .assertTextContains("https://example.test/v1")
    }

    @Test fun connectionFieldsRestoreUrlSelectAuthAndToggleKeyVisibility() {
        var saved: ModelProvider? = null
        compose.setScreenContent { MaterialTheme {
            ProviderEditor(provider, false, onSaveModels = { _, _ -> }, onReorderModels = { _, _ -> },
                onDismiss = {}, onSave = { saved = it })
        } }
        compose.onNodeWithContentDescription("Restore default URL").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("Base URL")).assertTextContains("https://api.openai.com/v1")
        compose.onNodeWithContentDescription("Select authentication type").performScrollTo().performClick()
        compose.onNodeWithText("URL Query").performClick()
        compose.onNode(hasSetTextAction() and hasText("URL Query")).performScrollTo().performTextReplacement("test-key")
        compose.onNodeWithContentDescription("Show API key").performClick()
        compose.onNode(hasSetTextAction() and hasText("URL Query")).assertTextContains("test-key")
        compose.onNodeWithContentDescription("Hide API key").performClick()
        compose.onNodeWithContentDescription("Show API key").assertExists()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertEquals("https://api.openai.com/v1", saved?.baseUrl)
            assertEquals(AuthMethod.QUERY_PARAM, saved?.auth?.method)
            assertEquals("test-key", saved?.auth?.value)
        }
    }

    @Test fun apiKeyGrowsToThreeLinesAndThenStopsGrowing() {
        compose.setScreenContent { MaterialTheme {
            ProviderEditor(provider, false, onSaveModels = { _, _ -> }, onReorderModels = { _, _ -> },
                onDismiss = {}, onSave = {})
        } }
        val field = compose.onNodeWithTag("provider_api_key").performScrollTo()
        val singleLineHeight = field.fetchSemanticsNode().boundsInRoot.height

        field.performTextReplacement("k".repeat(256))
        compose.waitForIdle()
        val threeLineHeight = field.fetchSemanticsNode().boundsInRoot.height
        assertTrue("A long API key should expand the field", threeLineHeight > singleLineHeight)

        field.performTextReplacement("k".repeat(1_024))
        compose.waitForIdle()
        assertEquals(
            "API key field should stop growing after three lines",
            threeLineHeight,
            field.fetchSemanticsNode().boundsInRoot.height,
            1f
        )
    }

    @Test fun headersDialogOpensOnlyWhenButtonClicked() {
        compose.setScreenContent { MaterialTheme {
            ProviderEditor(provider.copy(config = provider.config.copy(headers = mapOf("X-Test" to "value"))), false,
                onSaveModels = { _, _ -> }, onReorderModels = { _, _ -> }, onDismiss = {}, onSave = {})
        } }
        compose.onNodeWithText("Headers").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("X-Test").assertDoesNotExist()
        compose.onNodeWithText("Headers").performClick()
        compose.onNodeWithText("X-Test").assertExists()
        compose.onNodeWithContentDescription("Add").assertExists()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithText("X-Test").assertDoesNotExist()
    }

    @Test fun multipleApiKeysImportExistingKeyAddSelectEditAndDelete() {
        var saved: ModelProvider? = null
        compose.setScreenContent { MaterialTheme {
            ProviderEditor(provider.copy(auth = Authorization(value = "sk-existing-0123456789")), false,
                onSaveModels = { _, _ -> }, onReorderModels = { _, _ -> }, onDismiss = {}, onSave = { saved = it })
        } }
        compose.onNodeWithText("Manage API Keys").assertDoesNotExist()
        compose.onNodeWithText("Multiple API Keys").performScrollTo().performClick().assertIsOn()
        compose.onNodeWithText("Manage API Keys").performScrollTo().performClick()
        compose.onNodeWithText("sk-e••••6789").assertIsSelected()
        compose.onNodeWithContentDescription("Add").performClick()
        compose.onNodeWithTag("provider_api_key_label").performTextReplacement("Backup")
        compose.onNodeWithTag("provider_managed_api_key").performTextReplacement("backup-secret-key")
        compose.onNode(hasContentDescription("Show API key") and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithTag("provider_managed_api_key").assertTextContains("backup-secret-key")
        compose.onNodeWithContentDescription("Add").performClick()
        compose.onNodeWithText("Backup").assertExists()
        assertTrue(compose.onNodeWithText("sk-e••••6789").fetchSemanticsNode().boundsInRoot.top <
            compose.onNodeWithText("Backup").fetchSemanticsNode().boundsInRoot.top)
        compose.onNodeWithText("Backup").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText("Manage API Keys").performScrollTo().performClick()
        compose.onNodeWithText("Backup").assertIsSelected()
        compose.onNodeWithContentDescription("API key actions for Backup").performClick()
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithTag("provider_api_key_label").performTextReplacement("Renamed")
        compose.onNodeWithContentDescription("Save API key").performClick()
        compose.onNodeWithText("Renamed").assertIsSelected()
        compose.onNodeWithContentDescription("API key actions for Renamed").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Renamed").assertDoesNotExist()
        compose.onNodeWithText("sk-e••••6789").assertIsSelected()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertTrue(saved!!.config.multipleApiKeys)
            assertEquals(1, saved.config.apiKeys.size)
            assertEquals("sk-existing-0123456789", saved.auth.token)
        }
    }

    @Test fun newApiKeysPersistWithoutProviderSaveAndEditedLabelCanBeCleared() {
        var persisted = provider.copy(auth = Authorization(value = "primary-secret"))
        var fullSaves = 0
        compose.setScreenContent { MaterialTheme {
            ProviderEditor(persisted, false,
                onSaveModels = { _, _ -> }, onReorderModels = { _, _ -> },
                onDismiss = {}, onSave = { fullSaves++ },
                onUpdateAuxiliary = { _, change -> persisted = persisted.withAuxiliaryChange(change) })
        } }
        compose.onNodeWithText("Multiple API Keys").performScrollTo().performClick()
        compose.onNodeWithText("Manage API Keys").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Add").performClick()
        compose.onNodeWithTag("provider_api_key_label").performTextReplacement("Backup")
        compose.onNodeWithTag("provider_managed_api_key").performTextReplacement("backup-secret")
        compose.onNodeWithContentDescription("Add").performClick()
        compose.runOnIdle {
            assertTrue(persisted.config.multipleApiKeys)
            assertEquals(listOf("primary-secret", "backup-secret"), persisted.config.apiKeys.map { it.value })
            assertEquals("primary-secret", persisted.auth.token)
            assertEquals(0, fullSaves)
        }
        compose.onNodeWithContentDescription("API key actions for Backup").performClick()
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithTag("provider_api_key_label").performTextReplacement("")
        compose.onNodeWithContentDescription("Save API key").performClick()
        compose.runOnIdle {
            assertEquals("", persisted.config.apiKeys.last().label)
            assertEquals(0, fullSaves)
        }
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithText("Manage API Keys").performScrollTo().performClick()
        compose.onNodeWithText("Backup").assertDoesNotExist()
        compose.onNodeWithText("back••••cret").assertExists()
    }

    @Test fun addingHeaderAutoSavesButOverwritingExistingHeaderNeedsProviderSave() {
        var persisted = provider.copy(config = provider.config.copy(headers = mapOf("X-Existing" to "old")))
        var fullSaved: ModelProvider? = null
        compose.setScreenContent { MaterialTheme {
            ProviderEditor(persisted, false,
                onSaveModels = { _, _ -> }, onReorderModels = { _, _ -> },
                onDismiss = {}, onSave = { fullSaved = it },
                onUpdateAuxiliary = { _, change -> persisted = persisted.withAuxiliaryChange(change) })
        } }
        compose.onNodeWithText("Headers").performScrollTo().performClick()
        compose.onNodeWithTag("provider-header-0-value").performTextReplacement("changed")
        compose.onNodeWithContentDescription("Close").performClick()
        compose.runOnIdle { assertEquals("old", persisted.config.headers["X-Existing"]) }
        compose.onNodeWithText("Headers").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Add").performClick()
        compose.onNodeWithTag("provider-header-1-key").performTextReplacement("X-New")
        compose.onNodeWithTag("provider-header-1-value").performTextReplacement("added")
        compose.onNodeWithContentDescription("Close").performClick()
        compose.runOnIdle {
            assertEquals(mapOf("X-Existing" to "old", "X-New" to "added"), persisted.config.headers)
            assertNull(fullSaved)
        }
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertEquals("changed", fullSaved?.config?.headers?.get("X-Existing"))
            assertEquals("added", fullSaved?.config?.headers?.get("X-New"))
        }
        compose.onNodeWithText("Headers").performScrollTo().performClick()
        compose.onNodeWithTag("provider-header-1-key").assertExists()
        compose.onNodeWithTag("provider-header-1-delete").performClick()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.runOnIdle {
            assertEquals(mapOf("X-Existing" to "old"), persisted.config.headers)
        }
    }

    @Test fun connectionDialogTestsOnlyTextModelsAndRemovesFailedModels() {
        val calls = mutableListOf<String>()
        var saved = emptyMap<String, ModelConfiguration>()
        val models = mapOf("text-ok" to ModelConfiguration(), "text-bad" to ModelConfiguration(),
            "image-only" to ModelConfiguration(modelType = ModelType.IMAGE_GENERATION))
        compose.setScreenContent { MaterialTheme {
            ProviderEditor(provider.copy(config = ProviderConfiguration(modelConfigs = models)), false,
                onSaveModels = { _, value -> saved = value }, onReorderModels = { _, _ -> },
                onDismiss = {}, onSave = {}, onTestConnection = { _, id ->
                    calls += id
                    if (id == "text-bad") Result.failure(IllegalStateException("Unavailable model")) else Result.success("OK")
                })
        } }
        compose.onNodeWithText("Models").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Test connection").performClick()
        compose.onNode(hasText("Check Connection") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNode(hasText("image-only") and hasAnyAncestor(isDialog())).assertDoesNotExist()
        compose.onNodeWithContentDescription("Remove unavailable models").assertDoesNotExist()
        compose.onNodeWithContentDescription("Restore models marked for removal").assertDoesNotExist()
        val compactHeight = compose.onNodeWithTag("connection-model-text-ok").fetchSemanticsNode().boundsInRoot.height
        assertTrue("Model rows without a logo should be compact", compactHeight <= with(compose.density) { 72.dp.toPx() })
        compose.onNode(hasText("Close") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithContentDescription("Test all chat models").performClick()
        compose.waitUntil(5_000) { calls.size == 2 }
        compose.waitForIdle()
        val deleteBounds = compose.onNodeWithContentDescription("Remove unavailable models").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val testBounds = compose.onNodeWithContentDescription("Test all chat models").fetchSemanticsNode().boundsInRoot
        val closeBounds = compose.onNode(hasText("Close") and hasAnyAncestor(isDialog())).fetchSemanticsNode().boundsInRoot
        assertTrue("Delete unavailable belongs on the left", deleteBounds.right < testBounds.left)
        assertTrue("Test All belongs above the fixed footer", testBounds.bottom <= closeBounds.top)
        compose.onNodeWithContentDescription("Remove unavailable models").performClick()
        compose.onNodeWithContentDescription("Remove unavailable models").assertDoesNotExist()
        compose.onNodeWithContentDescription("Restore models marked for removal").assertIsDisplayed()
        compose.onNodeWithTag("connection-model-text-bad").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Will be removed when this dialog closes")
        )
        compose.onNodeWithContentDescription("Restore text-bad").assertIsDisplayed()
        compose.runOnIdle { assertEquals(setOf("text-ok", "text-bad"), calls.toSet()) }
        compose.onNode(hasText("Close") and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle { assertEquals(setOf("text-ok", "image-only"), saved.keys) }
        compose.onNodeWithText("text-bad").assertDoesNotExist()
    }

    @Test fun emptyConnectionReplyWarnsInsideDialogWithoutOutsideResultBanner() {
        val models = mapOf("text-empty" to ModelConfiguration(), "text-ok" to ModelConfiguration())
        compose.setScreenContent { MaterialTheme {
            ProviderEditor(provider.copy(config = ProviderConfiguration(modelConfigs = models)), false,
                onSaveModels = { _, _ -> }, onReorderModels = { _, _ -> }, onDismiss = {}, onSave = {},
                onTestConnection = { _, id -> if (id == "text-empty") Result.success("") else Result.success("OK") })
        } }
        compose.onNodeWithText("Models").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Test connection").performClick()
        compose.onNodeWithContentDescription("Test all chat models").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("No content").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithContentDescription("1 passed").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithContentDescription("1 returned no content").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("No content").assertIsDisplayed()
        compose.onNodeWithContentDescription("Remove unavailable models").assertDoesNotExist()
        // The dialog content reports the run with icons and counts without an outside banner.
        compose.onNodeWithContentDescription("1 passed").assertIsDisplayed()
        compose.onNodeWithContentDescription("1 returned no content").assertIsDisplayed()
        compose.onNodeWithText("Connection tests finished").assertDoesNotExist()
        compose.onNodeWithTag("model-connection-status").assertDoesNotExist()
        compose.onNode(hasText("Close") and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Connection tests finished").assertDoesNotExist()
        compose.onNodeWithTag("model-connection-status").assertDoesNotExist()
    }

    @Test fun modelCatalogShowsApiMetadataAndCheckboxSelection() {
        var saved = emptyMap<String, ModelConfiguration>()
        compose.setScreenContent { MaterialTheme {
            ProviderEditor(provider.copy(config = ProviderConfiguration()), false,
                onSaveModels = { _, value -> saved = value }, onReorderModels = { _, _ -> },
                onDismiss = {}, onSave = {}, onFetchModels = {
                    listOf(DiscoveredModel("catalog-test-model", displayName = "Catalog Model", metadata = buildJsonObject {
                        put("owned_by", "Vendor")
                        put("context_window", 32000)
                        put("created", 1704067200)
                    }))
                })
        } }
        compose.onNodeWithText("Models").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("Open model catalog")[0].performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Catalog Model").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Owned by: Vendor").assertDoesNotExist()
        compose.onNodeWithText("Added").assertDoesNotExist()
        compose.onNodeWithText("Catalog Model").performClick()
        compose.onNodeWithText("Owned by: Vendor").assertIsDisplayed()
        compose.onNodeWithText("Created: 1704067200").assertDoesNotExist()
        compose.onNodeWithText("Created: 2024-", substring = true).assertExists()
        compose.onNodeWithContentDescription("Select model catalog-test-model").performClick()
        compose.runOnIdle { assertTrue("catalog-test-model" in saved) }
        compose.onNode(hasText("Catalog Model") and hasContentDescription("Collapse")).performClick()
        compose.onNodeWithText("Owned by: Vendor").assertDoesNotExist()
        compose.runOnIdle { assertTrue("catalog-test-model" in saved) }
        compose.onNodeWithContentDescription("Select model catalog-test-model").performClick()
        compose.runOnIdle { assertFalse("catalog-test-model" in saved) }
    }
    @get:Rule val compose = createComposeRule()
    private val provider = ModelProvider("p", "Primary", ModelApiType.OPENAI,
        baseUrl = "https://example.com/v1",
        config = ProviderConfiguration(modelConfigs = mapOf("custom-model" to ModelConfiguration())))

    @Test fun providerTabsSwipeAndModelCardOpensEditor() {
        compose.setScreenContent {
            MaterialTheme {
                ProviderEditor(provider, false,
                    onSaveModels = { _, _ -> }, onReorderModels = { _, _ -> },
                    onDismiss = {}, onSave = {})
            }
        }
        compose.onNodeWithText("Type").assertIsDisplayed()
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Test connection").assertIsDisplayed()
        compose.onNodeWithText("custom-model", useUnmergedTree = true).assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithText("Edit Model").assertIsDisplayed()
    }

    @Test fun providerTitleReflectsNameEdits() {
        compose.setScreenContent {
            MaterialTheme {
                ProviderEditor(provider, false,
                    onSaveModels = { _, _ -> }, onReorderModels = { _, _ -> },
                    onDismiss = {}, onSave = {})
            }
        }
        compose.onNode(hasSetTextAction() and hasText("Primary")).performTextReplacement("Renamed")
        compose.onNode(hasText("Renamed") and !hasSetTextAction()).assertIsDisplayed()
    }

}
