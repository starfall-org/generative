package org.starfall.generative.features.home.chat.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import org.starfall.generative.features.home.chat.model.ActiveChatStreamState
import org.starfall.generative.features.home.chat.model.ChatStreamStatus
import org.starfall.generative.features.home.chat.model.StreamFreezeSignal
import org.starfall.generative.features.home.chat.model.StreamMetricsSnapshot
import org.starfall.generative.features.home.chat.model.StreamTextSnapshot
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.MessageVersion
import org.starfall.generative.shared.model.StoredMessage

/**
 * Debug-only, isolated, ordinary Activity for device FrameMetrics / gfxinfo / Perfetto.
 * Unlike Compose instrumentation tests it uses the real runtime frame clock, and
 * ADB native swipe events can target the foreground window.
 *
 * adb shell am start -n <debug app id>/
 *   org.starfall.generative.features.home.chat.ui.StreamPerformanceBenchmarkActivity
 *   --es mode baseline|stream --es scenario blocks|overflow
 */
class StreamPerformanceBenchmarkActivity : ComponentActivity() {
    private val stop = StreamFreezeSignal()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val streamed = intent.getStringExtra("mode") == "stream"
        val scenario = intent.getStringExtra("scenario") ?: "blocks"
        val seed = when (scenario) {
            "overflow" -> buildString {
                append("# Pathological open paragraph\n\n")
                repeat(500) {
                    append("An unclosed paragraph contains enough ordinary words to trigger " +
                        "the parser's unstable tail fallback without silently losing data. ")
                }
            }
            else -> buildString {
                append("# Long streaming answer\n\n")
                repeat(120) { i ->
                    append("## Section ${i + 1}\n\n")
                    append("The previous stable content should not be re-measured for every new token. " +
                        "This tests scrolling and streamed Markdown on a real device.\n\n")
                    append("- First item\n- Second item\n\n")
                }
            }
        }
        var snapshot = StreamTextSnapshot.of(seed)
        val stream = MutableStateFlow<ActiveChatStreamState?>(newState(snapshot, 1L))
        val messages = listOf(
            StoredMessage("bench-user", ChatRole.USER, listOf(MessageVersion(content = "Measure streaming hardware cost"))),
            StoredMessage("bench-answer", ChatRole.MODEL, listOf(MessageVersion()))
        )
        val list = ChatMessageListState(initialMessages = messages)

        // Warm up the initial Markdown parser before the sampled 14-second publication window.
        if (streamed) lifecycleScope.launch(Dispatchers.Default) {
            delay(5_000)
            repeat(280) { i ->
                delay(50)
                val chunk = if (scenario == "overflow")
                    " A small appended token group should not cause the whole long response to re-layout."
                else "- Live line ${i + 1}: **streamed Markdown** with more content.\n"
                snapshot = snapshot.append(chunk, (i + 2).toLong())
                stream.value = newState(snapshot, (i + 2).toLong())
            }
        }

        setContent {
            MaterialTheme {
                ChatMessageList(
                    state = list,
                    messages = messages,
                    streaming = true,
                    autoScroll = false,
                    historyLoading = false,
                    activeStream = stream,
                    appBarHeight = 48.dp,
                    bottomClearance = 64.dp,
                    modifier = Modifier.fillMaxSize()
                ) { message ->
                    if (message.id == "bench-answer") {
                        AssistantMessageCard(
                            message = message,
                            isStreaming = true,
                            onCopy = {},
                            onRegenerate = {},
                            onEdit = {},
                            onDelete = {},
                            onRead = {},
                            onSwitchVersion = {},
                            conversationId = "benchmark",
                            streamStateFlow = stream
                        )
                    } else Text(message.activeVersion.content)
                }
            }
        }
    }

    private fun newState(text: StreamTextSnapshot, revision: Long) = ActiveChatStreamState(
        conversationId = "benchmark",
        messageId = "bench-answer",
        generationId = 1L,
        revision = revision,
        status = ChatStreamStatus.STREAMING,
        content = text,
        reasoning = StreamTextSnapshot.of(),
        stopSignal = stop,
        lastCommitMetrics = null,
        cumulativeMetrics = StreamMetricsSnapshot()
    )
}
