package org.starfall.generative

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.features.home.chat.logic.estimateMessageTokens
import org.starfall.generative.features.home.chat.logic.estimateTextTokens
import org.starfall.generative.features.home.chat.logic.generateConversationSummary
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.Conversation
import org.starfall.generative.shared.model.ConversationSummary
import org.starfall.generative.shared.model.ConversationSummaryRequest
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.model.MessageVersion
import org.starfall.generative.shared.model.SummaryRole
import org.starfall.generative.shared.model.language.ModelConfiguration

class ConversationSummaryGenerationTest {
    private val conversation = Conversation("chat", "Summary fixture", 0, 0,
        listOf(message("turn", "Remember the user's decisions.")))
    private val model = ModelConfiguration(contextWindowTokens = 32_000,
        modelJson = Json.parseToJsonElement("""{"outputTokenLimit":16384}""").jsonObject)

    private data class Call(val messages: List<StoredMessage>, val prompt: String, val output: Int) {
        val inputTokens get() = (if (prompt.isBlank()) 0 else estimateTextTokens(prompt) + 4) +
            messages.sumOf { estimateMessageTokens(it, false) }
        val instruction get() = messages.last().content
        fun assertUserInstruction() {
            assertTrue("Summarization should not send a system prompt", prompt.isBlank())
            assertEquals("Summary instruction must be the last user message", ChatRole.USER, messages.last().role)
            assertEquals("summary_instruction", messages.last().id)
        }
    }

    private fun message(id: String, text: String) =
        StoredMessage(id, ChatRole.USER, listOf(MessageVersion(content = text)))

    private suspend fun failure(block: suspend () -> Unit): Exception {
        try { block() } catch (e: Exception) { return e }
        throw AssertionError("Expected the summary request to fail")
    }

    @Test fun singlePassPreservesCustomTargetsAboveTheSliderAndItsBoundaries() = runBlocking {
        for (target in listOf(1, 6000, 6001, 8000, 10000)) {
            val calls = mutableListOf<Call>()
            val text = generateConversationSummary(conversation, model, "", ConversationSummaryRequest(target),
                complete = { messages, prompt, output ->
                    calls += Call(messages, prompt, output)
                    "  summary  "
                })
            assertEquals("summary", text)
            assertEquals(1, calls.size)
            assertEquals(target, calls.single().output)
            assertTrue(calls.single().instruction.contains("approximately $target tokens"))
            calls.single().assertUserInstruction()
            assertTrue(calls.single().inputTokens + target <= model.contextWindowTokens)
        }
    }

    @Test fun chunkedRequestsReserveLargeFinalOutputAndBudgetEveryPartialAndMerge() = runBlocking {
        val smallModel = model.copy(contextWindowTokens = 12_000)
        val longConversation = conversation.copy(messages = listOf(message("long", "x".repeat(96_000))))
        for (target in listOf(6001, 8000, 10000)) {
            val calls = mutableListOf<Call>()
            val text = generateConversationSummary(longConversation, smallModel, "",
                ConversationSummaryRequest(target, chunked = true, tokensPerChunk = 100_000),
                complete = { messages, prompt, output ->
                    calls += Call(messages, prompt, output)
                    assertTrue("Input plus output must fit for every pass", calls.last().inputTokens + output <= 12_000)
                    "s".repeat(output * 4)
                })
            assertTrue(calls.size > 2)
            assertEquals(target, calls.last().output)
            assertEquals(target.toLong(), estimateTextTokens(text))
            assertTrue(calls.dropLast(1).all { it.output < target && it.instruction.contains("at most ${it.output} tokens") })
            calls.forEach { it.assertUserInstruction() }
        }
    }

    @Test fun outputLimitIsInclusiveAndRejectsOversizeTargetsBeforeCompletion() = runBlocking {
        for (metadata in listOf("""{"max_output_tokens":10000}""",
            """{"outputTokenLimit":"10000"}""",
            """{"limits":{"max_completion_tokens":10000}}""",
            """{"capabilities":{"limits":{"maxOutputTokens":10000}}}""")) {
            val limitedModel = model.copy(modelJson = Json.parseToJsonElement(metadata).jsonObject)
            var completions = 0
            generateConversationSummary(conversation, limitedModel, "", ConversationSummaryRequest(10_000),
                complete = { _, _, output -> completions++; assertEquals(10_000, output); "summary" })
            val error = failure {
                generateConversationSummary(conversation, limitedModel, "", ConversationSummaryRequest(10_001),
                    complete = { _, _, _ -> completions++; "must not run" })
            }
            assertTrue(error.message.orEmpty().contains("output limit of 10000"))
            assertEquals(1, completions)
        }
    }

    @Test fun missingOrInvalidCatalogOutputLimitsDoNotInventASixThousandTokenCap() = runBlocking {
        for (value in listOf("null", "0", "-1", "2147483648", "1.5", "true", "[]", "\"bad\"")) {
            val unknownLimit = model.copy(contextWindowTokens = 0,
                modelJson = Json.parseToJsonElement("""{"max_output_tokens":$value}""").jsonObject)
            generateConversationSummary(conversation, unknownLimit, "", ConversationSummaryRequest(10_000),
                complete = { _, _, output -> assertEquals(10_000, output); "summary" })
        }
        val multipleLimits = model.copy(modelJson =
            Json.parseToJsonElement("""{"outputTokenLimit":10000,"limits":{"max_output_tokens":8000}}""").jsonObject)
        val error = failure {
            generateConversationSummary(conversation, multipleLimits, "", ConversationSummaryRequest(8001),
                complete = { _, _, _ -> throw AssertionError("Over-limit request reached completion") })
        }
        assertTrue(error.message.orEmpty().contains("output limit of 8000"))
    }

    @Test fun contextBudgetIncludesInputPromptAndOutputAtTheExactBoundary() = runBlocking {
        var call: Call? = null
        generateConversationSummary(conversation, model, "", ConversationSummaryRequest(10_000),
            complete = { messages, prompt, output -> call = Call(messages, prompt, output); "summary" })
        val exactWindow = (call!!.inputTokens + call.output).toInt()
        var completions = 0
        generateConversationSummary(conversation, model.copy(contextWindowTokens = exactWindow), "",
            ConversationSummaryRequest(10_000), complete = { _, _, _ -> completions++; "summary" })
        val error = failure {
            generateConversationSummary(conversation, model.copy(contextWindowTokens = exactWindow - 1), "",
                ConversationSummaryRequest(10_000), complete = { _, _, _ -> completions++; "must not run" })
        }
        assertEquals(1, completions)
        assertTrue(error.message.orEmpty().contains("context window"))
        assertTrue(error.message.orEmpty().contains("multiple passes"))
    }

    @Test fun invalidAndOverflowSizedRequestsFailBeforeAnyCompletion() = runBlocking {
        for (target in listOf(0, -1, Int.MIN_VALUE, Int.MAX_VALUE)) {
            val error = failure {
                generateConversationSummary(conversation, model, "", ConversationSummaryRequest(target),
                    complete = { _, _, _ -> throw AssertionError("Invalid request reached completion") })
            }
            assertTrue(error is IllegalArgumentException)
        }
        val overflowError = failure {
            generateConversationSummary(conversation, ModelConfiguration(contextWindowTokens = Int.MAX_VALUE), "",
                ConversationSummaryRequest(Int.MAX_VALUE),
                complete = { _, _, _ -> throw AssertionError("Overflow-sized target reached completion") })
        }
        assertTrue(overflowError.message.orEmpty().contains("context window"))
        val promptError = failure {
            generateConversationSummary(conversation, model, "x".repeat(128_000), ConversationSummaryRequest(10_000),
                complete = { _, _, _ -> throw AssertionError("Oversized prompt reached completion") })
        }
        assertTrue(promptError.message.orEmpty().contains("prompt"))
    }

    @Test fun perPassBoundaryIsValidatedOnlyWhenChunkingIsEnabled() = runBlocking {
        for (limit in listOf(1, 100_000)) {
            generateConversationSummary(conversation, model, "",
                ConversationSummaryRequest(8000, chunked = true, tokensPerChunk = limit),
                complete = { _, _, _ -> "summary" })
        }
        for (limit in listOf(0, -1, 100_001, Int.MAX_VALUE)) {
            val error = failure {
                generateConversationSummary(conversation, model, "",
                    ConversationSummaryRequest(8000, chunked = true, tokensPerChunk = limit),
                    complete = { _, _, _ -> throw AssertionError("Invalid pass size reached completion") })
            }
            assertTrue(error.message.orEmpty().contains("between 1 and 100000"))
            generateConversationSummary(conversation, model, "",
                ConversationSummaryRequest(8000, chunked = false, tokensPerChunk = limit),
                complete = { _, _, _ -> "summary" })
        }
    }

    @Test fun evenASingleOversizedPartialIsReducedBeforeTheFinalLargeTargetRequest() = runBlocking {
        val calls = mutableListOf<Call>()
        generateConversationSummary(conversation, model.copy(contextWindowTokens = 12_000), "",
            ConversationSummaryRequest(10_000, chunked = true), complete = { messages, prompt, output ->
                calls += Call(messages, prompt, output)
                assertTrue(calls.last().inputTokens + output <= 12_000)
                // Emulate a transport/model returning more text than its estimated output budget.
                if (calls.size == 1) "x".repeat(12_000) else "compact summary"
            })
        assertEquals(3, calls.size)
        assertEquals(10_000, calls.last().output)
    }

    @Test fun nonCompressingMergeFailsRatherThanLoopingOrSendingAnOversizedFinalRequest() = runBlocking {
        var completions = 0
        val error = failure {
            generateConversationSummary(conversation, model.copy(contextWindowTokens = 12_000), "",
                ConversationSummaryRequest(10_000, chunked = true), complete = { _, _, _ ->
                    completions++
                    "x".repeat(12_000)
                })
        }
        assertEquals(2, completions)
        assertTrue(error.message.orEmpty().contains("could not be compressed"))
    }

    @Test fun chunkedSummaryRetainsPreviousSummaryForEverySavedRole() = runBlocking {
        for (role in SummaryRole.entries) {
            val previous = conversation.copy(
                messages = listOf(message("old", "obsolete history"), message("new", "new decision")),
                summary = ConversationSummary("s", "important previous decision", "old", role))
            val calls = mutableListOf<Call>()
            generateConversationSummary(previous, model, "", ConversationSummaryRequest(8000, chunked = true),
                complete = { messages, prompt, output -> calls += Call(messages, prompt, output); "summary" })
            val inputs = calls.first().messages.joinToString("\n") { it.content }
            assertTrue(inputs.contains("important previous decision"))
            assertTrue(inputs.contains("new decision"))
            assertFalse(inputs.contains("obsolete history"))
        }
    }

    @Test fun allPassesAppendUserInstructionEvenAfterAnAssistantTurnAndRetainPriorSummary() = runBlocking {
        val previous = conversation.copy(
            messages = listOf(message("old", "obsolete history"),
                StoredMessage("reply", ChatRole.MODEL, listOf(MessageVersion(content = "latest answer")))),
            summary = ConversationSummary("s", "important previous decision", "old", SummaryRole.SYSTEM)
        )
        for (chunked in listOf(false, true)) {
            val calls = mutableListOf<Call>()
            generateConversationSummary(previous, model, "Summarize custom instructions",
                ConversationSummaryRequest(8000, chunked = chunked),
                complete = { messages, prompt, output ->
                    calls += Call(messages, prompt, output)
                    "summary"
                })
            assertEquals(if (chunked) 2 else 1, calls.size)
            calls.forEach { it.assertUserInstruction() }
            assertTrue(calls.first().messages.any { it.content.contains("important previous decision") })
            assertTrue(calls.first().messages.any { it.content.contains("latest answer") })
            assertFalse(calls.first().messages.any { it.content.contains("obsolete history") })
            assertTrue(calls.first().instruction.contains("Summarize custom instructions"))
            assertTrue(calls.last().instruction.contains("approximately 8000 tokens"))
        }
    }

    @Test fun blankAndTransportErrorTextCannotBecomeSummaries() = runBlocking {
        for (chunked in listOf(false, true)) {
            for (text in listOf(" ", " [Error: HTTP 400: token limit]", "partial output [Error: HTTP 500]")) {
                var completions = 0
                val error = failure {
                    generateConversationSummary(conversation, model, "", ConversationSummaryRequest(8000, chunked),
                        complete = { _, _, _ -> completions++; text })
                }
                assertEquals(1, completions)
                assertTrue(error.message.orEmpty().contains(if (text.isBlank()) "no content" else "HTTP"))
            }
        }
        val httpFailure = IllegalStateException("HTTP 400: requested output exceeds model limit")
        val error = failure {
            generateConversationSummary(conversation, model, "", ConversationSummaryRequest(8000),
                complete = { _, _, _ -> throw httpFailure })
        }
        assertSame(httpFailure, error)
    }
}
