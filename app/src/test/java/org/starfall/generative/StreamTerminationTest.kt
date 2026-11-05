package org.starfall.generative

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.generation.UNEXPECTED_STREAM_END
import org.starfall.generative.shared.model.generation.OUTPUT_TOKEN_LIMIT_REACHED
import org.starfall.generative.shared.helpers.networking.ToolHttp
import org.starfall.generative.shared.helpers.networking.modelResponse
import org.starfall.generative.shared.helpers.networking.obj

/** EOF is not a protocol completion signal. Partial reasoning must remain visible on failure. */
@Config(sdk = [28])
@RunWith(RobolectricTestRunner::class)
class StreamTerminationTest {
    private fun sse(vararg frames: String): String =
        frames.joinToString("") { "data: $it\n\n" }

    private fun interrupted(type: ModelApiType, body: String, completed: Boolean,
        errorMessage: String = UNEXPECTED_STREAM_END
    ) = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(body))
            val provider = ModelProvider("fixture", "Fixture", type, baseUrl = server.url("/").toString())
            val reasoning = StringBuilder()
            val text = StringBuilder()
            val result = runCatching {
                ToolHttp().modelResponse(server.url("/chat").toString(), obj(), provider, true,
                    { text.append(it) }, { reasoning.append(it) })
            }
            if (completed) assertTrue("$type: ${result.exceptionOrNull()}", result.isSuccess)
            else assertEquals(errorMessage, result.exceptionOrNull()?.message)
        }
    }

    @Test fun chatCompletionsRequiresFinishReasonOrDoneSentinel() {
        val delta = """{"choices":[{"delta":{"reasoning_content":"partial"},"finish_reason":null}]}"""
        interrupted(ModelApiType.OPENAI, sse(delta), false)
        interrupted(ModelApiType.OPENAI, sse(delta, "[DONE]"), true)
        interrupted(ModelApiType.OPENAI,
            sse("""{"choices":[{"delta":{},"finish_reason":"stop"}]}"""), true)
    }

    @Test fun anthropicRequiresMessageStopEvenAfterThinkingDeltas() {
        val delta = """{"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"partial"}}"""
        interrupted(ModelApiType.ANTHROPIC, sse(delta), false)
        interrupted(ModelApiType.ANTHROPIC, sse(delta, """{"type":"message_stop"}"""), true)
    }

    @Test fun responsesRequiresCompletedAndRejectsIncomplete() {
        val delta = """{"type":"response.output_text.delta","delta":"partial"}"""
        interrupted(ModelApiType.OPENAI_RESPONSES, sse(delta), false)
        interrupted(ModelApiType.OPENAI_RESPONSES,
            sse(delta, """{"type":"response.incomplete","response":{}}"""), false)
        interrupted(ModelApiType.OPENAI_RESPONSES,
            sse(delta, """{"type":"response.completed","response":{}}"""), true)
    }

    @Test fun tokenExhaustionGetsASeparateShortMessage() {
        interrupted(ModelApiType.OPENAI,
            sse("""{"choices":[{"delta":{},"finish_reason":"length"}]}"""),
            false, OUTPUT_TOKEN_LIMIT_REACHED)
        interrupted(ModelApiType.ANTHROPIC,
            sse("""{"type":"message_delta","delta":{"stop_reason":"max_tokens"}}"""),
            false, OUTPUT_TOKEN_LIMIT_REACHED)
        interrupted(ModelApiType.OPENAI_RESPONSES,
            sse("""{"type":"response.incomplete","response":{"incomplete_details":{"reason":"max_output_tokens"}}}"""),
            false, OUTPUT_TOKEN_LIMIT_REACHED)
        interrupted(ModelApiType.OLLAMA,
            """{"done":true,"done_reason":"length"}""" + "\n",
            false, OUTPUT_TOKEN_LIMIT_REACHED)
    }

    @Test fun ollamaRequiresDoneTrueRatherThanAnEof() {
        val delta = """{"message":{"content":"partial"},"done":false}"""
        interrupted(ModelApiType.OLLAMA, "$delta\n", false)
        interrupted(ModelApiType.OLLAMA, "$delta\n" + """{"done":true}""" + "\n", true)
    }
}
