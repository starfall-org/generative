package org.starfall.generative

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.shared.helpers.media.*
import org.starfall.generative.shared.subfeatures.media.logic.*
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.media.MediaRequestProfile
import org.starfall.generative.shared.model.media.defaultMediaProfile

class MediaProfileDefaultsTest {
    @Test fun dalleDefaultsToBase64JsonButAllowsExplicitUrl() {
        val defaults = imageGenerationRequest(ModelApiType.OPENAI, "dall-e-3", "a cat", Json.parseToJsonElement("{}").jsonObject)
        assertEquals("b64_json", defaults["response_format"]?.jsonPrimitive?.content)
        val overridden = imageGenerationRequest(ModelApiType.OPENAI, "dall-e-3", "a cat",
            Json.parseToJsonElement("""{"response_format":"url"}""").jsonObject)
        assertEquals("url", overridden["response_format"]?.jsonPrimitive?.content)
        val gptImage = imageGenerationRequest(ModelApiType.OPENAI, "gpt-image-1", "a cat",
            Json.parseToJsonElement("{}").jsonObject)
        assertFalse(gptImage.containsKey("response_format"))
        assertEquals("1", gptImage["n"]?.jsonPrimitive?.content)
    }

    @Test fun recommendedControlsAreProviderIndependent() {
        val image = suggestedMediaDisabledParams("generate_image")
        assertFalse("size" in image)
        assertFalse("parameters.aspectRatio" in image)
        assertTrue("response_format" in image)
        assertTrue("stream" in image)
        assertTrue("generationConfig.thinkingConfig.thinkingBudget" in image)
        val video = suggestedMediaDisabledParams("generate_video")
        assertFalse("input_image" in video)
        assertTrue("input_image_url" in video)
        assertTrue("input_video_url" in video)
    }

    @Test fun explicitEnableEverythingPersistsAndLegacyPreferencesAreRespected() {
        val blank = defaultMediaProfile(video = false)
        assertTrue(effectiveMediaDisabledParams(blank, "generate_image").isNotEmpty())
        assertEquals(setOf("stream"), effectiveMediaDisabledParams(blank, "generate_image", setOf("stream")))
        val intentional = blank.copy(paramsConfigured = true, disabledParams = emptySet())
        val restored = Json.decodeFromString<MediaRequestProfile>(Json.encodeToString(intentional))
        assertTrue(effectiveMediaDisabledParams(restored, "generate_image").isEmpty())
        assertEquals(setOf("quality"), effectiveMediaDisabledParams(blank.copy(disabledParams = setOf("quality")), "generate_image"))
        val custom = blank.copy(mode = org.starfall.generative.shared.model.media.MediaGenerationMode.CUSTOM)
        assertTrue(effectiveMediaDisabledParams(custom, "generate_image").isEmpty())
    }
}
