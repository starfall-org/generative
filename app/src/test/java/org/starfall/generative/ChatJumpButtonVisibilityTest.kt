package org.starfall.generative

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.home.chat.ui.*
import org.starfall.generative.shared.model.*

/** Real LazyColumn layout drives this policy; no scroll controller is involved. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-xhdpi", application = LibraryTestApplication::class)
class ChatJumpButtonVisibilityTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var scope: CoroutineScope
    private fun message(id: String, role: ChatRole) =
        StoredMessage(id, role, listOf(MessageVersion(content = id)))
    private val messages = (1..30).flatMap {
        listOf(message("u$it", ChatRole.USER), message("a$it", ChatRole.MODEL))
    }

    private fun show(state: ChatMessageListState, height: () -> Dp = { 800.dp },
        bottom: () -> Dp = { 100.dp }, replyHeight: () -> Dp = { 4000.dp },
        streaming: () -> Boolean = { false }) {
        compose.setContent { MaterialTheme {
            scope = rememberCoroutineScope()
            ChatMessageList(state, messages, streaming(), false, false, null, 80.dp, bottom(),
                modifier = Modifier.fillMaxWidth().height(height())) { message ->
                Box(Modifier.fillMaxWidth().height(if (message.id == "a30") replyHeight() else 40.dp))
            }
        } }
    }

    private fun positionEndAtDistance(state: ChatMessageListState, distance: Float) {
        compose.runOnIdle {
            val end = checkNotNull(state.latestContentBounds.bounds).boundsInRoot.bottom
            val viewport = checkNotNull(state.jumpViewportBounds.bounds).boundsInRoot.bottom
            scope.launch { state.listState.scrollBy(end - viewport - distance) }
        }
        compose.waitForIdle()
    }

    @Test fun measuredContentEndUsesExactlyTwoScreensAndHidesOnReturn() {
        val state = ChatMessageListState(messages)
        show(state)
        compose.runOnIdle { assertFalse(state.showJumpToLatest) }
        compose.runOnIdle {
            assertEquals("Only the composer obscures the button's readable viewport; gap is not content",
                state.listState.layoutInfo.viewportSize.height - with(compose.density) { 100.dp.toPx() },
                state.jumpViewportBounds.bounds!!.boundsInRoot.bottom.toFloat(), 1f)
        }
        val threshold = state.listState.layoutInfo.viewportSize.height * 2f
        positionEndAtDistance(state, threshold - 2f)
        compose.runOnIdle { assertFalse("A hidden last line alone must not show Jump", state.showJumpToLatest) }
        positionEndAtDistance(state, threshold)
        compose.runOnIdle { assertTrue("The two-screen boundary is inclusive", state.showJumpToLatest) }
        positionEndAtDistance(state, threshold - 2f)
        compose.runOnIdle {
            assertFalse(state.showJumpToLatest)
            assertEquals("Button policy never emits scroll intents", 0L, state.requestCount)
            assertFalse(state.detached)
        }
    }

    @Test fun visibilityCatchesUpWhenScrollingStops() {
        val state = ChatMessageListState(messages)
        val finishScroll = CompletableDeferred<Unit>()
        show(state)
        compose.runOnIdle {
            assertFalse(state.showJumpToLatest)
            scope.launch {
                state.listState.scroll {
                    scrollBy(-5000f)
                    finishScroll.await()
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(state.listState.isScrollInProgress)
            assertFalse("Visibility stays unchanged during scrolling", state.showJumpToLatest)
            finishScroll.complete(Unit)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(state.listState.isScrollInProgress)
            assertTrue("Visibility catches up after scrolling", state.showJumpToLatest)
        }
    }

    @Test fun virtualizedShortFinalReplyUsesNativeEstimateAndReturnsToRealEnd() {
        val state = ChatMessageListState(messages)
        show(state, replyHeight = { 40.dp })
        compose.runOnIdle { assertFalse("Trailing padding and unused turn room are not content", state.showJumpToLatest) }
        compose.runOnIdle { scope.launch { state.listState.scrollToItem(0) } }
        compose.waitForIdle()
        compose.runOnIdle {
            assertNull("The final turn is genuinely virtualized", state.latestContentBounds.bounds)
            assertTrue(state.showJumpToLatest)
            state.jump()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertNotNull(state.latestContentBounds.bounds)
            assertFalse(state.showJumpToLatest)
        }
    }

    @Test fun asynchronousGrowthAndTerminalStatusOnlyChangeVisibility() {
        val state = ChatMessageListState(messages)
        var replyHeight by mutableStateOf(500.dp)
        var streaming by mutableStateOf(true)
        show(state, replyHeight = { replyHeight }, streaming = { streaming })
        compose.runOnIdle { assertFalse(state.showJumpToLatest); replyHeight = 4000.dp }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(state.showJumpToLatest)
            streaming = false
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(state.showJumpToLatest)
            assertEquals("Relayout/completion cannot cause scroll through button policy", 0L, state.requestCount)
        }
    }

    @Test fun screenAndComposerChangesReevaluateTheSameThreshold() {
        val state = ChatMessageListState(messages)
        var screenHeight by mutableStateOf(800.dp)
        var composerHeight by mutableStateOf(100.dp)
        show(state, height = { screenHeight }, bottom = { composerHeight })
        positionEndAtDistance(state, state.listState.layoutInfo.viewportSize.height * 2f - 100f)
        compose.runOnIdle { assertFalse(state.showJumpToLatest); screenHeight = 300.dp }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(state.showJumpToLatest); screenHeight = 800.dp }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(state.showJumpToLatest); composerHeight = 400.dp }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(state.showJumpToLatest); composerHeight = 100.dp }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(state.showJumpToLatest) }
    }
}
