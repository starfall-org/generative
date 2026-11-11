package org.starfall.generative.features.home.chat.logic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.starfall.generative.features.home.chat.model.ActiveChatStreamState
import org.starfall.generative.features.home.chat.model.ChatStreamStatus
import org.starfall.generative.features.home.chat.model.StreamCommitMetrics
import org.starfall.generative.features.home.chat.model.StreamMetricsSnapshot
import org.starfall.generative.features.home.chat.model.StreamTextSnapshot
import org.starfall.generative.features.home.chat.model.StreamFreezeSignal

/** Owns buffered text and the paced, message-scoped display state for one generation. */
internal class ChatStreamDisplay(
    private val conversationId: String,
    initialMessageId: String,
    private val generationId: Long,
    initialContent: String,
    initialReasoning: String,
    alreadyInsideVisibleContent: Boolean,
    private val scope: CoroutineScope,
    private val charactersPerBatch: Int = STREAM_CHARS_PER_BATCH,
    private val commitIntervalMillis: Long = STREAM_COMMIT_INTERVAL_MS,
    private val monotonicTimeMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val waitForInterval: suspend (Long) -> Unit = { delay(it) },
    private val publish: (String, ActiveChatStreamState) -> Unit
) {
    private var messageId = initialMessageId
    private var revision = 0L
    private var status = ChatStreamStatus.STREAMING
    private var preparingAttachments = false
    private var lastCommitMetrics: StreamCommitMetrics? = null
    private var cumulativeMetrics = StreamMetricsSnapshot()
    private var lastBatchAtMillis: Long? = null
    private var explicitReasoningPreferred = initialReasoning.isNotEmpty()
    private var displayedContent = StreamTextSnapshot.of(initialContent)
    private var displayedInlineReasoning = StreamTextSnapshot.of(initialReasoning)
    private var displayedExplicitReasoning = StreamTextSnapshot.of(initialReasoning)
    private var contentBatcher = newBatcher()
    private var inlineReasoningBatcher = newBatcher()
    private var explicitReasoningBatcher = newBatcher()
    private var responseSplitter = StreamResponseTextSplitter(
        initialContentLength = initialContent.length,
        alreadyInsideVisibleContent = alreadyInsideVisibleContent
    )
    private var displayFinished = false
    private var frozenForStop = false
    private val stopSignal = StreamFreezeSignal()
    private var pipelineEpoch = 0L
    private var renderJob: Job? = null

    init {
        require(charactersPerBatch > 0) { "charactersPerBatch must be positive" }
        require(commitIntervalMillis >= 0L) { "commitIntervalMillis cannot be negative" }
    }

    val visibleContentLength: Int
        get() = responseSplitter.visibleContentLength

    val visibleReasoningLength: Int
        get() = responseSplitter.visibleReasoningLength

    val cumulativeStreamMetrics: StreamMetricsSnapshot
        get() = cumulativeMetrics

    @Synchronized
    fun publishInitialState() {
        if (!frozenForStop) publishCurrentState()
    }

    @Synchronized
    fun setPreparingAttachments(active: Boolean) {
        if (frozenForStop || displayFinished || preparingAttachments == active) return
        preparingAttachments = active
        revision++
        publishCurrentState()
    }

    /** Freeze the currently published snapshot. No pending batch is flushed and no new state is published. */
    @Synchronized
    fun freezeForStop(): ActiveChatStreamState {
        if (!frozenForStop) {
            frozenForStop = true
            displayFinished = true
            pipelineEpoch++
            renderJob?.cancel()
            renderJob = null
            stopSignal.freeze()
        }
        return activeStreamState()
    }

    @Synchronized
    fun appendText(fragment: String) {
        if (frozenForStop || displayFinished) return
        val pieces = responseSplitter.append(fragment)
        contentBatcher.append(pieces.content)
        inlineReasoningBatcher.append(pieces.reasoning)
        scheduleStreamDrain()
    }

    @Synchronized
    fun appendExplicitReasoning(text: String) {
        if (frozenForStop || displayFinished) return
        if (text.isEmpty()) return
        explicitReasoningPreferred = true
        explicitReasoningBatcher.append(text)
        scheduleStreamDrain()
    }

    /**
     * Commits all text received so far, including a short character chunk, without finishing the stream.
     * Called at tool boundaries so pre-tool text stays visible while the tool runs and stored offsets match.
     */
    @Synchronized
    fun flushPending() {
        if (displayFinished || frozenForStop) return
        val committedAt = monotonicTimeMillis()
        val contentBatch = contentBatcher.takeAllPending(committedAt)
        val inlineReasoningBatch = inlineReasoningBatcher.takeAllPending(committedAt)
        val explicitReasoningBatch = explicitReasoningBatcher.takeAllPending(committedAt)
        if (contentBatch == null && inlineReasoningBatch == null && explicitReasoningBatch == null) return
        publishCommittedBatches(contentBatch, inlineReasoningBatch, explicitReasoningBatch)
    }

    /** Flushes all complete chunks and the final short tail; safe to call more than once. */
    @Synchronized
    fun finish(finalStatus: ChatStreamStatus) {
        if (displayFinished || frozenForStop) return
        displayFinished = true
        pipelineEpoch++
        renderJob?.cancel()
        renderJob = null

        val unresolved = responseSplitter.finish()
        contentBatcher.append(unresolved.content)
        inlineReasoningBatcher.append(unresolved.reasoning)
        val finishedAt = monotonicTimeMillis()
        publishCommittedBatches(
            contentBatch = contentBatcher.finishAndTakeRemainder(finishedAt),
            inlineReasoningBatch = inlineReasoningBatcher.finishAndTakeRemainder(finishedAt),
            explicitReasoningBatch = explicitReasoningBatcher.finishAndTakeRemainder(finishedAt),
            newStatus = finalStatus
        )
    }

    /** Starts display state for the next assistant message in a multi-turn tool response. */
    @Synchronized
    fun resetForMessage(newMessageId: String) {
        check(displayFinished && !frozenForStop) { "Finish the current stream display before resetting it" }
        messageId = newMessageId
        displayedContent = StreamTextSnapshot.of()
        displayedInlineReasoning = StreamTextSnapshot.of()
        displayedExplicitReasoning = StreamTextSnapshot.of()
        explicitReasoningPreferred = false
        contentBatcher = newBatcher()
        inlineReasoningBatcher = newBatcher()
        explicitReasoningBatcher = newBatcher()
        responseSplitter = StreamResponseTextSplitter()
        displayFinished = false
        status = ChatStreamStatus.STREAMING
        lastCommitMetrics = null
        lastBatchAtMillis = null
        pipelineEpoch++
        revision++
        publishCurrentState()
    }

    private fun newBatcher() = StreamCharBatcher(charactersPerBatch, monotonicTimeMillis)

    private fun activeStreamState() = ActiveChatStreamState(
        conversationId = conversationId,
        messageId = messageId,
        generationId = generationId,
        revision = revision,
        status = status,
        content = displayedContent,
        reasoning = if (explicitReasoningPreferred) displayedExplicitReasoning else displayedInlineReasoning,
        stopSignal = stopSignal,
        lastCommitMetrics = lastCommitMetrics,
        cumulativeMetrics = cumulativeMetrics,
        preparingAttachments = preparingAttachments
    )

    private fun publishCurrentState() = publish(conversationId, activeStreamState())

    private fun hasPendingText() = listOf(contentBatcher, inlineReasoningBatcher, explicitReasoningBatcher)
        .any { it.hasPendingText }

    /** Takes all pending text from every channel as one time-window commit. */
    @Synchronized
    private fun takeRenderWindow(committedAtMillis: Long): Triple<StreamTextBatch?, StreamTextBatch?, StreamTextBatch?> =
        Triple(
            contentBatcher.takeAllPending(committedAtMillis),
            inlineReasoningBatcher.takeAllPending(committedAtMillis),
            explicitReasoningBatcher.takeAllPending(committedAtMillis)
        )

    @Synchronized
    private fun publishCommittedBatches(
        contentBatch: StreamTextBatch? = null,
        inlineReasoningBatch: StreamTextBatch? = null,
        explicitReasoningBatch: StreamTextBatch? = null,
        newStatus: ChatStreamStatus = status
    ) {
        if (frozenForStop) return
        val batches = listOfNotNull(contentBatch, inlineReasoningBatch, explicitReasoningBatch)
        if (batches.isEmpty()) {
            if (newStatus != status) {
                status = newStatus
                revision++
                publishCurrentState()
            }
            return
        }

        revision++
        contentBatch?.let { displayedContent = displayedContent.append(it.text, revision) }
        inlineReasoningBatch?.let {
            displayedInlineReasoning = displayedInlineReasoning.append(it.text, revision)
        }
        explicitReasoningBatch?.let {
            explicitReasoningPreferred = true
            displayedExplicitReasoning = displayedExplicitReasoning.append(it.text, revision)
        }
        status = newStatus
        val commitMetrics = StreamCommitMetrics(
            characterCount = batches.sumOf { it.characterCount },
            queuedCharacters = contentBatcher.pendingCharacterCount + inlineReasoningBatcher.pendingCharacterCount + explicitReasoningBatcher.pendingCharacterCount,
            chunkWaitMillis = batches.maxOf { it.chunkWaitMillis },
            inputToVisibleLatencyMillis = batches.maxOf { it.inputToVisibleLatencyMillis },
            terminal = batches.any { it.terminal }
        )
        lastCommitMetrics = commitMetrics
        cumulativeMetrics = cumulativeMetrics.copy(
            batchCount = cumulativeMetrics.batchCount + 1L,
            committedCharacters = cumulativeMetrics.committedCharacters + commitMetrics.characterCount.toLong(),
            maxQueuedCharacters = maxOf(cumulativeMetrics.maxQueuedCharacters, commitMetrics.queuedCharacters),
            totalChunkWaitMillis = cumulativeMetrics.totalChunkWaitMillis + commitMetrics.chunkWaitMillis,
            totalInputToVisibleLatencyMillis = cumulativeMetrics.totalInputToVisibleLatencyMillis +
                commitMetrics.inputToVisibleLatencyMillis
        )
        publishCurrentState()
        lastBatchAtMillis = monotonicTimeMillis()
    }

    private fun scheduleStreamDrain() {
        if (displayFinished || renderJob?.isActive == true || !hasPendingText()) return
        val scheduledEpoch = pipelineEpoch
        val scheduledJob = scope.launch(start = CoroutineStart.LAZY) {
            while (isActive && !displayFinished && scheduledEpoch == pipelineEpoch) {
                val now = monotonicTimeMillis()
                val pacingWait = lastBatchAtMillis?.let {
                    (commitIntervalMillis - (now - it)).coerceAtLeast(0L)
                } ?: 0L

                if (pacingWait == 0L) {
                    val window = takeRenderWindow(now)
                    if (window.first != null || window.second != null || window.third != null) {
                        publishCommittedBatches(
                            contentBatch = window.first,
                            inlineReasoningBatch = window.second,
                            explicitReasoningBatch = window.third
                        )
                        continue
                    }
                }
                if (pacingWait <= 0L || !hasPendingText()) break
                waitForInterval(pacingWait)
            }
        }
        renderJob = scheduledJob
        scheduledJob.invokeOnCompletion {
            if (renderJob === scheduledJob) renderJob = null
        }
        scheduledJob.start()
    }
}
