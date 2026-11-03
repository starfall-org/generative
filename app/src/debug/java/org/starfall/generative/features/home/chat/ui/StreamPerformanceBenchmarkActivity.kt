package org.starfall.generative.features.home.chat.ui

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
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
        Log.i("StreamPerfBenchmark", "activity_created uptimeMs=" + SystemClock.uptimeMillis())
        val streamed = intent.getStringExtra("mode") == "stream"
        val warmupMs = intent.getIntExtra("warmupMs", 15_000).coerceIn(0, 45_000)
        val chunkDelayMs = intent.getIntExtra("chunkDelayMs", 55).coerceIn(20, 500)
        val chunkCount = intent.getIntExtra("chunkCount", 500).coerceIn(1, 1_000)
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

        // The remote device can need more than 10 seconds to open a very long Text.
        // Start well AFTER foreground focus and keep streaming for the full measurement.
        if (streamed) lifecycleScope.launch(Dispatchers.Default) {
            delay(warmupMs.toLong())
            Log.i("StreamPerfBenchmark", "producer_started uptimeMs=" + SystemClock.uptimeMillis())
            repeat(chunkCount) { i ->
                delay(chunkDelayMs.toLong())
                val chunk = if (scenario == "overflow")
                    " A small appended token group should not cause the whole long response to re-layout."
                else "- Live line ${i + 1}: **streamed Markdown** with more content.\n"
                snapshot = snapshot.append(chunk, (i + 2).toLong())
                stream.value = newState(snapshot, (i + 2).toLong())
                if (i % 50 == 0) Log.i("StreamPerfBenchmark", "published=" + (i + 1) +
                    " uptimeMs=" + SystemClock.uptimeMillis())
            }
            Log.i("StreamPerfBenchmark", "producer_finished uptimeMs=" + SystemClock.uptimeMillis())
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Log.i("StreamPerfBenchmark", "focus=" + hasFocus +
            " uptimeMs=" + SystemClock.uptimeMillis())
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
