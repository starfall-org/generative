package org.starfall.generative

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.features.home.chat.logic.*
import org.starfall.generative.features.home.chat.model.ContextWindowStatus
import org.starfall.generative.shared.model.*
import org.starfall.generative.shared.model.generation.GenerationEvent
import org.starfall.generative.shared.model.generation.TokenUsage
import org.starfall.generative.shared.model.language.ModelConfiguration

class ContextApiUsageTest {
    private val config = ModelConfiguration(contextWindowTokens = 1000)
    private val prompt = "System instructions"
    private fun message(id: String, role: ChatRole, text: String) = StoredMessage(id, role,
        listOf(MessageVersion(content = text, providerId = "provider", modelId = "model")))
    private fun conversation() = Conversation("chat", "Chat", 1, 1, providerId = "provider", modelId = "model",
        messages = listOf(message("u", ChatRole.USER, "Question"), message("a", ChatRole.MODEL, "Answer")))
    private fun observed(usage: TokenUsage = TokenUsage(700, 200), chat: Conversation = conversation()) =
        recordContextUsage(chat, chat.messages.last().id, usage, contextUsageKey(chat, prompt, config))
    private fun status(chat: Conversation) = contextWindowStatus(chat, prompt, config)

    @Test fun actualUsageOverridesLocalEstimateAndDrivesThresholds() {
        val actual = status(observed())
        assertEquals(900L, actual.usedTokens)
        assertEquals(90, actual.percentage)
        assertTrue(actual.shouldSuggestSummary)
        assertFalse(actual.isEstimated)
        assertEquals(TokenUsage(700, 200), actual.apiUsage)
        assertNotEquals(900L, status(conversation()).usedTokens)
    }

    @Test fun absentUsageKeepsExistingEstimator() {
        val actual = status(conversation())
        assertNull(actual.apiUsage)
        assertTrue(actual.isEstimated)
        assertEquals(estimateTextTokens(prompt) + conversation().messages.sumOf { estimateMessageTokens(it, false) },
            actual.usedTokens)
    }

    @Test fun explicitZeroAndInputOnlyUsageAreNotTreatedAsMissing() {
        assertEquals(0L, status(observed(TokenUsage(0, 0))).usedTokens)
        val inputOnly = status(observed(TokenUsage(321)))
        assertEquals(321L, inputOnly.usedTokens)
        assertEquals(TokenUsage(321), inputOnly.apiUsage)
    }

    @Test fun latestRequestReplacesUsageInsteadOfSummingTurns() {
        val first = observed()
        val next = first.copy(messages = first.messages + message("u2", ChatRole.USER, "Next") +
            message("a2", ChatRole.MODEL, "Next answer"))
        val latest = observed(TokenUsage(300, 40), next)
        assertEquals(340L, status(latest).usedTokens)
        assertFalse(status(latest).isEstimated)
    }

    @Test fun newMessagesUseApiAnchorPlusOnlyTheirLocalEstimate() {
        val first = observed()
        val nextMessage = message("u2", ChatRole.USER, "New question")
        val next = first.copy(messages = first.messages + nextMessage)
        val actual = status(next)
        val extra = estimateMessageTokens(nextMessage, false)
        assertEquals(900 + extra, actual.usedTokens)
        assertEquals(extra, actual.estimatedAdditionalTokens)
        assertTrue(actual.isEstimated)
        assertEquals(TokenUsage(700, 200), actual.apiUsage)
    }

    @Test fun editsDeletesAndVersionSwitchInvalidateAffectedHistory() {
        val original = observed()
        val edited = original.copy(messages = original.messages.map {
            if (it.id == "u") it.copy(versions = listOf(it.activeVersion.copy(content = "Edited"))) else it
        })
        assertNull(status(edited).apiUsage)
        assertNull(status(original.copy(messages = original.messages.drop(1))).apiUsage)
        val branched = original.copy(messages = original.messages.map {
            if (it.id != "u") it else it.copy(versions = it.versions + it.activeVersion.copy(content = "Branch"), activeVersionIndex = 1)
        })
        assertNull(status(branched).apiUsage)
        val restored = branched.copy(messages = branched.messages.map {
            if (it.id == "u") it.copy(activeVersionIndex = 0) else it
        })
        assertEquals(900L, status(restored).usedTokens)
    }

    @Test fun otherProviderModelPromptConfigurationAndSummaryCannotReuseUsage() {
        val original = observed()
        assertNull(contextWindowStatus(original, prompt, config, providerId = "other").apiUsage)
        assertNull(contextWindowStatus(original, prompt, config, modelId = "other").apiUsage)
        assertNull(contextWindowStatus(original, "Different prompt", config).apiUsage)
        assertNull(contextWindowStatus(original, prompt, config.copy(temperature = 0.2)).apiUsage)
        assertNull(contextWindowStatus(original, prompt, config,
            listOf(message("prefix", ChatRole.USER, "Injected prompt"))).apiUsage)
        assertNull(status(original.copy(reasoningEffort = "high")).apiUsage)
        assertNull(status(original.copy(summary = ConversationSummary("s", "Summary", "u"))).apiUsage)
        assertNull(contextWindowStatus(original, prompt, config, toolSettings = ToolSettings(sendFileEnabled = false)).apiUsage)
    }

    @Test fun serializableSnapshotSurvivesReloadAndIgnoresDisplayOnlyChanges() {
        val original = observed()
        val decoded = Json.decodeFromString<Conversation>(Json.encodeToString(original))
        assertEquals(900L, status(decoded).usedTokens)
        val changed = decoded.copy(title = "Renamed", updatedAt = 999, messages = decoded.messages.map {
            it.copy(versions = it.versions.map { version -> version.copy(timestamp = "later", processingFinishedAt = 999) })
        })
        assertEquals(900L, status(changed).usedTokens)
    }

    @Test fun invalidOrCorruptedSnapshotFallsBackAndHugeCountsDoNotOverflow() {
        assertNull(status(observed(TokenUsage(-1, 2))).apiUsage)
        assertNull(status(observed(TokenUsage(Long.MAX_VALUE, 1))).apiUsage)
        val valid = observed()
        val corrupted = valid.copy(messages = valid.messages.map {
            if (it.id != "a") it else it.copy(versions = listOf(it.activeVersion.copy(
                contextUsage = it.activeVersion.contextUsage!!.copy(messagesFingerprint = "wrong"))))
        })
        assertNull(status(corrupted).apiUsage)
        val huge = status(observed(TokenUsage(Long.MAX_VALUE, 0)))
        assertEquals(100, huge.percentage)
        assertTrue(huge.shouldAutoSummarize)
        assertTrue(ContextWindowStatus(Long.MAX_VALUE, 1000).shouldSuggestSummary)
    }

    @Test fun generationPersistsLatestToolRoundUsageAfterFlushingText() = runBlocking {
        val initial = conversation().copy(messages = conversation().messages.map {
            if (it.id == "a") it.copy(versions = listOf(it.activeVersion.copy(content = ""))) else it
        })
        var saved: Conversation? = null
        val runner = ChatGeneration(CoroutineScope(coroutineContext + Dispatchers.Unconfined), { saved = it }, {})
        runner.startEvents(initial, "a", flow {
            emit(GenerationEvent.Text("Before tool"))
            emit(GenerationEvent.Usage(TokenUsage(600, 10)))
            emit(GenerationEvent.Tool(ToolActivity("t", "search", status = "success", response = "Result".toToolPayload())))
            emit(GenerationEvent.Text("After tool"))
            emit(GenerationEvent.Usage(TokenUsage(800, 30)))
        }, usageContextKey = contextUsageKey(initial, prompt, config))
        withTimeout(5_000) { runner.busy.first { !it } }
        assertEquals("Before toolAfter tool", saved!!.messages.last().content)
        assertEquals(830L, status(saved).usedTokens)
        assertEquals(TokenUsage(800, 30), status(saved).apiUsage)
    }

    @Test fun contentAddedAfterUsageDoesNotMasqueradeAsExactApiUsage() = runBlocking {
        var saved: Conversation? = null
        val initial = conversation()
        val runner = ChatGeneration(CoroutineScope(coroutineContext + Dispatchers.Unconfined), { saved = it }, {})
        runner.startEvents(initial, "a", flow {
            emit(GenerationEvent.Text("Part"))
            emit(GenerationEvent.Usage(TokenUsage(80, 10)))
            emit(GenerationEvent.Text(" not covered"))
        }, usageContextKey = contextUsageKey(initial, prompt, config))
        withTimeout(5_000) { runner.busy.first { !it } }
        assertNull(status(saved!!).apiUsage)
        assertTrue(status(saved).isEstimated)
    }
}
