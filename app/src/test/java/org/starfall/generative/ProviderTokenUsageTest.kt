package org.starfall.generative

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.generation.TokenUsage
import org.starfall.generative.shared.helpers.networking.providerTokenUsage

class ProviderTokenUsageTest {
    private fun usage(type: ModelApiType, json: String) = providerTokenUsage(type, Json.parseToJsonElement(json).jsonObject)

    @Test fun openAiCachedInputAndReasoningAreAlreadyInReportedCounts() {
        val json = """{"usage":{"prompt_tokens":120,"completion_tokens":40,"total_tokens":160,
            "prompt_tokens_details":{"cached_tokens":100},"completion_tokens_details":{"reasoning_tokens":30}}}"""
        assertEquals(TokenUsage(120, 40), usage(ModelApiType.OPENAI, json))
        assertEquals(TokenUsage(120, 40), usage(ModelApiType.GITHUB_COPILOT, json))
        val response = """{"usage":{"input_tokens":120,"output_tokens":40,"total_tokens":160,
            "input_tokens_details":{"cached_tokens":100},"output_tokens_details":{"reasoning_tokens":30}}}"""
        assertEquals(TokenUsage(120, 40), usage(ModelApiType.OPENAI_RESPONSES, response))
        assertEquals(TokenUsage(120, 40), usage(ModelApiType.OPENAI_CODEX, response))
    }

    @Test fun anthropicAddsDistinctCacheCategoriesButNotTheirBreakdown() {
        val json = """{"usage":{"input_tokens":10,"cache_read_input_tokens":100,"cache_creation_input_tokens":20,
            "cache_creation":{"ephemeral_5m_input_tokens":15,"ephemeral_1h_input_tokens":5},"output_tokens":30}}"""
        assertEquals(TokenUsage(130, 30), usage(ModelApiType.ANTHROPIC, json))
        assertEquals(TokenUsage(130, 30), usage(ModelApiType.CLAUDE_CODE, json))
    }

    @Test fun googleAddsThoughtOutputWithoutCountingCachedPromptTwice() {
        val json = """{"usageMetadata":{"promptTokenCount":100,"cachedContentTokenCount":90,
            "candidatesTokenCount":10,"thoughtsTokenCount":20,"totalTokenCount":130}}"""
        assertEquals(TokenUsage(100, 30), usage(ModelApiType.GOOGLE, json))
        assertEquals(TokenUsage(100, 30), usage(ModelApiType.ANTIGRAVITY, json))
        assertEquals(TokenUsage(112, 30), usage(ModelApiType.GOOGLE,
            json.replace("\"promptTokenCount\":100", "\"promptTokenCount\":100,\"toolUsePromptTokenCount\":12")))
    }

    @Test fun ollamaNativeCountersUse64BitValues() {
        assertEquals(TokenUsage(3_000_000_000L, 4),
            usage(ModelApiType.OLLAMA, """{"prompt_eval_count":3000000000,"eval_count":4}"""))
    }

    @Test fun zeroIsValidWhileMissingOrMalformedInputIsUnavailable() {
        assertEquals(TokenUsage(0, 0), usage(ModelApiType.OPENAI, """{"usage":{"prompt_tokens":0,"completion_tokens":0}}"""))
        assertEquals(TokenUsage(5), usage(ModelApiType.OPENAI, """{"usage":{"prompt_tokens":5}}"""))
        assertEquals(TokenUsage(5), usage(ModelApiType.OPENAI, """{"usage":{"prompt_tokens":5,"completion_tokens":null}}"""))
        for (json in listOf("{}", """{"usage":null}""", """{"usage":[]}""",
            """{"usage":{"total_tokens":100}}""", """{"usage":{"completion_tokens":100}}""",
            """{"usage":{"prompt_tokens":-1}}""", """{"usage":{"prompt_tokens":"bad"}}""",
            """{"usage":{"prompt_tokens":1.5}}""", """{"usage":{"prompt_tokens":true}}""",
            """{"usage":{"prompt_tokens":5,"completion_tokens":-1}}""",
            """{"usage":{"prompt_tokens":9223372036854775807,"completion_tokens":1}}""")) {
            assertNull(json, usage(ModelApiType.OPENAI, json))
        }
    }

    @Test fun invalidCacheOrThoughtCountsNeverProduceMadeUpUsage() {
        assertNull(usage(ModelApiType.ANTHROPIC,
            """{"usage":{"input_tokens":1,"cache_read_input_tokens":"bad"}}"""))
        assertNull(usage(ModelApiType.ANTHROPIC,
            """{"usage":{"input_tokens":9223372036854775807,"cache_creation_input_tokens":1}}"""))
        assertNull(usage(ModelApiType.GOOGLE,
            """{"usageMetadata":{"promptTokenCount":1,"candidatesTokenCount":9223372036854775807,"thoughtsTokenCount":1}}"""))
        assertNull(usage(ModelApiType.OLLAMA, """{"eval_count":8}"""))
    }
}
