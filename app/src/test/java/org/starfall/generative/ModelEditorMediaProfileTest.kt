package org.starfall.generative

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.providers.editor.ui.ModelEditor
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.shared.model.media.MediaGenerationMode
import org.starfall.generative.shared.model.media.MediaRequestProfile
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderConfiguration
import org.starfall.generative.shared.ui.theme.GenerativeTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h850dp-xhdpi", application = LibraryTestApplication::class)
class ModelEditorMediaProfileTest {
    @get:Rule val compose = createComposeRule()

    @Test fun imageModelShowsOnlyItsProfileSelectorAndPersistsSelectedMode() {
        val selected = MediaRequestProfile("image-custom", "Image gateway", mode = MediaGenerationMode.CUSTOM)
        var saved: ModelConfiguration? = null
        val provider = ModelProvider(
            id = "provider", name = "Provider", type = ModelApiType.OPENAI, baseUrl = "https://example.com/v1",
            config = ProviderConfiguration(modelConfigs = mapOf(
                "image-test" to ModelConfiguration(modelType = ModelType.IMAGE_GENERATION)
            ))
        )
        compose.setContent {
            GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
                ModelEditor(
                    provider = provider, initialModelId = "image-test", existingModelIds = setOf("image-test"),
                    customImageMediaProfiles = mapOf(selected.id to selected),
                    onSave = { _, config -> saved = config }, onBack = {}
                )
            }
        }
        compose.onAllNodesWithTag("media-profile-selector").assertCountEquals(1)
        compose.onNodeWithTag("media-profile-selector").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Image Profile").assertIsDisplayed()
        compose.onNodeWithText("API Type").assertDoesNotExist()
        compose.onNodeWithTag("media-profile-selector").performClick()
        compose.onNodeWithText("Image gateway · Custom").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertNotNull(saved)
            assertEquals(selected.id, saved?.mediaRequestProfileId)
            assertEquals(MediaGenerationMode.CUSTOM, saved?.mediaApiType)
        }
    }

    @Test fun modelMetadataCannotSelectModelIndependentCustomProfile() {
        val standalone = MediaRequestProfile("standalone", "Independent", requiresModel = false)
        var saved: ModelConfiguration? = null
        val provider = ModelProvider(
            id = "provider", name = "Provider", type = ModelApiType.OPENAI,
            baseUrl = "https://example.test/v1",
            config = ProviderConfiguration(modelConfigs = mapOf(
                "image-model" to ModelConfiguration(modelType = ModelType.IMAGE_GENERATION,
                    mediaApiType = MediaGenerationMode.CUSTOM,
                    mediaRequestProfileId = standalone.id)
            ))
        )
        compose.setContent {
            GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
                ModelEditor(provider = provider, initialModelId = "image-model",
                    existingModelIds = setOf("image-model"),
                    customImageMediaProfiles = mapOf(standalone.id to standalone),
                    onSave = { _, config -> saved = config }, onBack = {})
            }
        }
        compose.onNodeWithTag("media-profile-selector").performScrollTo().performClick()
        compose.onNodeWithText("Independent · Custom").assertDoesNotExist()
        compose.onNodeWithText("Default · Default").assertIsDisplayed().performClick()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertNotNull(saved)
            assertEquals("imagegen-default", saved?.mediaRequestProfileId)
            assertEquals(MediaGenerationMode.DEFAULT, saved?.mediaApiType)
        }
    }

    @Test fun videoModelLabelsItsSingleProfileSelectorCorrectly() {
        val provider = ModelProvider(
            id = "video-provider", name = "Provider", type = ModelApiType.OPENAI,
            baseUrl = "https://example.com/v1", config = ProviderConfiguration(modelConfigs = mapOf(
                "video-test" to ModelConfiguration(modelType = ModelType.VIDEO_GENERATION)
            ))
        )
        compose.setContent {
            GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
                ModelEditor(
                    provider = provider, initialModelId = "video-test", existingModelIds = setOf("video-test"),
                    onSave = { _, _ -> }, onBack = {}
                )
            }
        }
        compose.onAllNodesWithTag("media-profile-selector").assertCountEquals(1)
        compose.onNodeWithTag("media-profile-selector").performScrollTo()
        compose.onNodeWithText("Video Profile").assertIsDisplayed()
        compose.onNodeWithText("API Type").assertDoesNotExist()
    }
    @Test fun switchingBetweenImageAndVideoTypeClearsOldMediaProfile() {
        val customImage = MediaRequestProfile("image-custom", "Image gateway", mode = MediaGenerationMode.CUSTOM)
        val provider = ModelProvider(
            id = "provider", name = "Provider", type = ModelApiType.OPENAI,
            baseUrl = "https://example.test/v1",
            config = ProviderConfiguration(modelConfigs = mapOf(
                "media-model" to ModelConfiguration(modelType = ModelType.IMAGE_GENERATION,
                    mediaRequestProfileId = customImage.id, mediaApiType = MediaGenerationMode.CUSTOM)
            ))
        )
        var saved: ModelConfiguration? = null
        compose.setContent {
            GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
                ModelEditor(provider = provider, initialModelId = "media-model",
                    existingModelIds = setOf("media-model"),
                    customImageMediaProfiles = mapOf(customImage.id to customImage),
                    onSave = { _, config -> saved = config }, onBack = {})
            }
        }
        compose.onNodeWithTag("model-type-selector").performScrollTo().performClick()
        compose.onNodeWithText("Video Generation").performClick()
        compose.onNodeWithTag("media-profile-selector").assertExists()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertEquals(ModelType.VIDEO_GENERATION, saved?.modelType)
            assertEquals(MediaGenerationMode.DEFAULT, saved?.mediaApiType)
            assertEquals("videogen-default", saved?.mediaRequestProfileId)
        }
    }

}
