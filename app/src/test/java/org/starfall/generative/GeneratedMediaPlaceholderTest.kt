package org.starfall.generative

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.home.chat.ui.GeneratedMediaPlaceholder
import org.starfall.generative.features.home.chat.logic.tool.imageToolPreviewRatio
import org.starfall.generative.shared.model.SystemToolConfig
import org.starfall.generative.shared.model.ToolSettings
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderConfiguration
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.media.MediaGenerationMode
import org.starfall.generative.shared.model.media.MediaRequestChain
import org.starfall.generative.shared.model.media.MediaRequestProfile
import org.starfall.generative.shared.model.media.MediaRequestStep
import org.starfall.generative.shared.helpers.media.imagePreviewAspectRatio
import org.starfall.generative.shared.model.ToolActivity
import org.starfall.generative.shared.model.toToolPayload

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h850dp-xhdpi", application = LibraryTestApplication::class)
class GeneratedMediaPlaceholderTest {
    @get:Rule val compose = createComposeRule()

    private fun json(value: String): JsonObject = Json.parseToJsonElement(value) as JsonObject

    @Test fun imageSizeAndAspectRatioOptionsProduceTheCorrectShape() {
        assertEquals(2f / 3f, imagePreviewAspectRatio(json("""{"size":"1024x1536"}"""))!!, 0.001f)
        assertEquals(3f / 2f, imagePreviewAspectRatio(json("""{"size":"1536x1024"}"""))!!, 0.001f)
        assertEquals(4f / 3f, imagePreviewAspectRatio(json("""{"parameters":{"aspectRatio":"4:3"}}"""))!!, 0.001f)
        assertEquals(9f / 16f, imagePreviewAspectRatio(json("""{"generationConfig":{"imageConfig":{"aspectRatio":"9:16","imageSize":"2K"}}}"""))!!, 0.001f)
        assertEquals(16f / 9f, imagePreviewAspectRatio(json("""{"parameters.aspectRatio":"16:9"}"""))!!, 0.001f)
        assertEquals(3f / 4f, imagePreviewAspectRatio(json("""{"size":"auto","parameters":{"aspectRatio":"3:4"}}"""))!!, 0.001f)
    }

    @Test fun unknownInvalidAndResolutionOnlySizesHaveNoAspectRatio() {
        listOf("{}", """{"size":"auto"}""", """{"size":"0x1024"}""",
            """{"size":"garbage"}""", """{"generationConfig":{"imageConfig":{"imageSize":"4K"}}}""")
            .forEach { assertNull(it, imagePreviewAspectRatio(json(it))) }
    }

    @Test fun toolPreviewUsesProfileDefaultsAndTheEffectiveCallOverride() {
        val provider = ModelProvider("p", "OpenAI", ModelApiType.OPENAI, baseUrl = "https://example.com")
        val config = SystemToolConfig(enabled = true, providerId = "p", modelId = "gpt-image-1")
            .withImageOptions(json("""{"size":"1024x1536"}"""))
        val settings = ToolSettings(system = mapOf("generate_image" to config))
        assertEquals(2f / 3f, imageToolPreviewRatio("""{"prompt":"cat"}""", settings, listOf(provider))!!, 0.001f)
        assertEquals(3f / 2f, imageToolPreviewRatio("""{"size":"1536x1024"}""", settings, listOf(provider))!!, 0.001f)
        assertNull(imageToolPreviewRatio("""{"size":"auto"}""", settings, listOf(provider)))

        val google = ModelProvider("g", "Google", ModelApiType.GOOGLE, baseUrl = "https://example.com")
        val googleConfig = SystemToolConfig(enabled = true, providerId = "g", modelId = "imagen-4")
            .withImageOptions(json("""{"parameters":{"aspectRatio":"3:4"}}"""))
        val googleSettings = ToolSettings(system = mapOf("generate_image" to googleConfig))
        assertEquals(16f / 9f, imageToolPreviewRatio("""{"parameters.aspectRatio":"16:9"}""",
            googleSettings, listOf(google))!!, 0.001f)
    }

    @Test fun customProfileCanDeclareTheAspectRatioInItsRequestBody() {
        val profile = MediaRequestProfile(
            id = "custom", name = "Custom", mode = MediaGenerationMode.CUSTOM,
            chain = MediaRequestChain(steps = listOf(MediaRequestStep(body = """{"size":"1024x1536"}""")))
        )
        val provider = ModelProvider("p", "Custom", ModelApiType.OPENAI,
            baseUrl = "https://example.com", config = ProviderConfiguration(modelConfigs = mapOf(
                "image" to ModelConfiguration(mediaRequestProfileId = "custom")
            )))
        val config = SystemToolConfig(enabled = true, providerId = "p", modelId = "image",
            imageRequestProfiles = mapOf("custom" to profile))
        val settings = ToolSettings(system = mapOf("generate_image" to config))
        assertEquals(2f / 3f, imageToolPreviewRatio("""{"prompt":"cat"}""",
            settings, listOf(provider))!!, 0.001f)
    }

    @Test fun pendingImageTileResizesWithRequestedRatioAndUsesSquareFallback() {
        var activity by mutableStateOf(ToolActivity("preview", "generate_image", arguments = """{"size":"1024x1536"}""".toToolPayload()))
        compose.setContent { MaterialTheme { GeneratedMediaPlaceholder(activity) } }

        fun assertRatio(expected: Float) {
            val bounds = compose.onNodeWithTag("media-generation-placeholder_preview")
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals(expected, bounds.width / bounds.height, 0.025f)
        }
        assertRatio(2f / 3f)
        compose.runOnIdle { activity = activity.copy(arguments = """{"size":"1536x1024"}""".toToolPayload()) }
        assertRatio(3f / 2f)
        compose.runOnIdle { activity = activity.copy(arguments = "{}".toToolPayload()) }
        assertRatio(1f)
        // The effective tool/profile options captured at invocation take precedence over raw args.
        compose.runOnIdle { activity = activity.copy(previewAspectRatio = 9f / 16f) }
        assertRatio(9f / 16f)
    }

    @Test fun failedTileRetainsDimensionsAndAccessibleStatusWithoutAnIcon() {
        val activity = ToolActivity("preview", "generate_image", status = "error",
            arguments = """{"size":"1024x1536"} """.toToolPayload())
        compose.setContent { MaterialTheme { GeneratedMediaPlaceholder(activity) } }
        val bounds = compose.onNodeWithTag("media-generation-placeholder_preview")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(2f / 3f, bounds.width / bounds.height, 0.025f)
        compose.onNodeWithTag("media-generation-failed_preview", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Image generation failed").assertExists()
    }
}
