package org.starfall.generative.features.home.chat.logic

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.Conversation
import org.starfall.generative.shared.model.ConversationSummaryRequest
import org.starfall.generative.shared.model.DEFAULT_CHAT_SUMMARY_PROMPT
import org.starfall.generative.shared.model.MessageVersion
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.model.generation.hasToolCalls
import org.starfall.generative.shared.model.language.DEFAULT_CONTEXT_WINDOW_TOKENS
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.language.sendsThinkingBack

/** Summary-only request planning, with an injectable completion for offline regression fixtures. */
internal suspend fun generateConversationSummary(
    conversation: Conversation,
    model: ModelConfiguration,
    prompt: String,
    request: ConversationSummaryRequest,
    complete: suspend (List<StoredMessage>, String, Int) -> String,
    report: (String, Float) -> Unit = { _, _ -> }
): String {
    val target = request.targetTokens
    require(target > 0) { "Summary target must be a positive whole number of tokens" }
    require(!request.chunked || request.tokensPerChunk in 1..100_000) {
        "Summary tokens per pass must be between 1 and 100000"
    }
    val budget = SummaryRequestBudget(model)
    val instructions = prompt.ifBlank { DEFAULT_CHAT_SUMMARY_PROMPT }
    val finalPrompt = "$instructions\n\nTarget summary length: approximately $target tokens. Return only the summary."
    val finalInputBudget = budget.inputBudget(finalPrompt, target)
    val effective = effectiveContext(conversation, conversation.messages, "")
    // Retain a previous system-role summary as user-visible context, not a new system instruction.
    val source = if (effective.systemPrompt.isBlank()) effective.messages else
        listOf(summaryMessage("previous_summary", effective.systemPrompt)) + effective.messages

    suspend fun finish(messages: List<StoredMessage>, instruction: String, outputTokens: Int): String {
        // Budget the instruction as one user message (including its message framing).
        budget.requireFits(messages, instruction, outputTokens)
        val text = complete(messages + summaryMessage("summary_instruction", instruction), "", outputTokens).trim()
        // Some transports append an error as text instead of throwing. Never persist it as a summary.
        val transportError = Regex("(?:^|\\s)\\[Error: (.*)]$", RegexOption.DOT_MATCHES_ALL).find(text)
        if (transportError != null) error(transportError.groupValues[1])
        check(text.isNotBlank()) { "Summary returned no content" }
        return text
    }

    if (!request.chunked) {
        report("Summarizing conversation…", 0.35f)
        return finish(source, finalPrompt, target)
    }

    // Leave room for the final requested output, including when it is larger than the slider range.
    require(finalInputBudget >= 16) {
        "Summary target leaves too little input space in the summary model's context window; reduce the target"
    }
    val partialTarget = minOf(target, (finalInputBudget / 2).coerceAtLeast(1))
    val partialPrompt = "$instructions\n\nThis is part of a conversation. Produce a compact partial summary for later merging. " +
        "Target summary length: at most $partialTarget tokens. Return only the summary."
    val partialInputBudget = budget.inputBudget(partialPrompt, partialTarget)
    require(partialInputBudget >= 16) {
        "Summary prompt leaves too little input space in the summary model's context window"
    }
    val chunks = chunkSummaryMessages(source, minOf(request.tokensPerChunk, partialInputBudget))
    chunks.forEach { budget.requireFits(it, partialPrompt, partialTarget) }
    var merging = chunks.mapIndexed { index, chunk ->
        report("Summarizing part ${index + 1}/${chunks.size}…", (index + 1).toFloat() / (chunks.size + 1))
        finish(chunk, partialPrompt, partialTarget)
    }.mapIndexed { index, text -> summaryMessage("summary_part_$index", text) }

    report("Merging summaries…", 0.9f)
    while (budget.messageTokens(merging) > finalInputBudget) {
        val batches = chunkSummaryMessages(merging, partialInputBudget)
        val reduced = batches.mapIndexed { index, batch ->
            summaryMessage("merge_$index", finish(batch, partialPrompt, partialTarget))
        }
        check(budget.messageTokens(reduced) < budget.messageTokens(merging)) {
            "Summary could not be compressed to fit the summary model's context window"
        }
        merging = reduced
    }
    return finish(merging, finalPrompt, target)
}

private fun summaryMessage(id: String, text: String) =
    StoredMessage(id, ChatRole.USER, listOf(MessageVersion(content = text)))

private class SummaryRequestBudget(private val model: ModelConfiguration) {
    private val contextWindow = model.contextWindowTokens.takeIf { it > 0 } ?: DEFAULT_CONTEXT_WINDOW_TOKENS
    private val outputLimit = summaryModelOutputLimit(model.modelJson)

    fun messageTokens(messages: List<StoredMessage>): Long {
        val includeReasoning = model.sendsThinkingBack(messages.hasToolCalls())
        return messages.sumOf { estimateMessageTokens(it, includeReasoning) }
    }

    fun inputBudget(prompt: String, outputTokens: Int): Int {
        require(outputLimit == null || outputTokens <= outputLimit) {
            "Summary target exceeds the summary model's output limit of $outputLimit tokens"
        }
        // Long arithmetic avoids wrapping large custom targets. Reserve user-message framing too.
        val available = contextWindow.toLong() - outputTokens - estimateTextTokens(prompt) - 4
        require(available >= 0) {
            "Summary target and prompt exceed the summary model's context window of $contextWindow tokens; reduce the target"
        }
        return available.toInt()
    }

    fun requireFits(messages: List<StoredMessage>, prompt: String, outputTokens: Int) {
        require(messageTokens(messages) <= inputBudget(prompt, outputTokens)) {
            "Summary input and target exceed the summary model's context window of $contextWindow tokens; " +
                "use multiple passes or reduce the target or tokens per pass"
        }
    }
}

/** Only explicit model output limits are ceilings; the provider's maxTokens is a request default. */
private fun summaryModelOutputLimit(metadata: JsonObject): Int? {
    val keys = listOf("outputTokenLimit", "max_output_tokens", "max_completion_tokens", "maxOutputTokens")
    val capabilities = metadata["capabilities"] as? JsonObject
    val containers = listOfNotNull(metadata, metadata["limits"] as? JsonObject,
        metadata["architecture"] as? JsonObject, metadata["metadata"] as? JsonObject,
        capabilities, capabilities?.get("limits") as? JsonObject)
    return containers.flatMap { source -> keys.mapNotNull { key ->
        (source[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
            ?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
    } }.minOrNull()
}
