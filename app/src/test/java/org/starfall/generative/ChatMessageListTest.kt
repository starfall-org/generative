package org.starfall.generative

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.home.chat.model.*
import org.starfall.generative.features.home.chat.ui.*
import org.starfall.generative.shared.model.*

/** Container tests deliberately use opaque, synchronously laid out children, never renderer readiness. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-xhdpi", application = LibraryTestApplication::class)
class ChatMessageListTest {
    @get:Rule val compose = createComposeRule()
    private fun message(id: String, role: ChatRole, text: String = id) =
        StoredMessage(id, role, listOf(MessageVersion(content = text)))
    private fun history() = (1..100).flatMap {
        listOf(message("u$it", ChatRole.USER), message("a$it", ChatRole.MODEL))
    }

    @Test fun firstLayoutShowsActualContentEndWithNoOpeningIntent() {
        val messages = history()
        val state = ChatMessageListState(initialMessages = messages)
        var firstEndBottom: Float? = null
        compose.setContent { MaterialTheme {
            Box(Modifier.fillMaxSize()) {
                ChatMessageList(state, messages, false, false, false, null, 80.dp, 100.dp) { message ->
                    Column(Modifier.fillMaxWidth().height(if (message.id == "a100") 2400.dp else 48.dp)) {
                        Text(message.id)
                        if (message.id == "a100") {
                            Spacer(Modifier.weight(1f))
                            Text("Final readable line", Modifier.onGloballyPositioned {
                                if (firstEndBottom == null) firstEndBottom = it.boundsInRoot().bottom
                            })
                        }
                    }
                }
            }
        } }
        compose.onNodeWithText("Final readable line").assertIsDisplayed()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val gap = root.height * 0.21f
        val composer = with(compose.density) { 100.dp.toPx() }
        assertNotNull(firstEndBottom)
        assertTrue(firstEndBottom!! >= with(compose.density) { 80.dp.toPx() })
        assertTrue("First placement already shows the latest line", firstEndBottom <= root.bottom - composer - gap + 2f)
        assertFalse("History must use ordinary chronological list layout", state.listState.layoutInfo.reverseLayout)
        assertEquals("The first frame already contains the latest turn",
            messages.size / 2 - 1, state.listState.firstVisibleItemIndex)
        assertEquals("Opening sends no scroll request", 0L, state.requestCount)
    }

    @Test fun shortSendAlignsAtAppBarEvenWithContinuousFollowDisabled() {
        val messages = mutableStateOf(history())
        val state = ChatMessageListState(initialMessages = messages.value)
        compose.setContent { MaterialTheme {
            ChatMessageList(state, messages.value, true, false, false, null, 80.dp, 100.dp) {
                Text(it.id, Modifier.fillMaxWidth().height(40.dp))
            }
        } }
        compose.runOnIdle {
            messages.value += listOf(message("sent", ChatRole.USER), message("reply", ChatRole.MODEL))
            state.send("sent")
        }
        compose.onNodeWithText("sent").assertIsDisplayed()
        val top = compose.onNodeWithText("sent").fetchSemanticsNode().boundsInRoot.top
        assertEquals(compose.onNodeWithTag("chat-message-list").fetchSemanticsNode().boundsInRoot.top + with(compose.density) { 81.dp.toPx() }, top, 2f)
        assertEquals(1L, state.requestCount)
        assertNull(state.request)
    }

    @Test fun longUserSendRevealsItsStartBelowAppBarUsingNativeRelocation() {
        val messages = mutableStateOf(history())
        val state = ChatMessageListState(initialMessages = messages.value)
        compose.setContent { MaterialTheme {
            ChatMessageList(state, messages.value, false, false, false, null, 80.dp, 100.dp) {
                Column(Modifier.fillMaxWidth().height(if (it.id == "long-user") 1800.dp else 40.dp)) {
                    Text(it.id)
                }
            }
        } }
        compose.runOnIdle {
            messages.value += listOf(message("long-user", ChatRole.USER), message("reply", ChatRole.MODEL))
            state.send("long-user")
        }
        compose.waitForIdle()
        compose.onNodeWithText("long-user").assertIsDisplayed()
        val top = compose.onNodeWithText("long-user").fetchSemanticsNode().boundsInRoot.top
        assertEquals(compose.onNodeWithTag("chat-message-list").fetchSemanticsNode().boundsInRoot.top + with(compose.density) { 81.dp.toPx() }, top, 3f)
    }

    @Test fun childResizeDoesNotCauseOpeningCorrectionOrRemount() {
        var height by mutableStateOf(100.dp)
        var mounts = 0
        var disposals = 0
        val messages = listOf(message("user", ChatRole.USER), message("reply", ChatRole.MODEL))
        val state = ChatMessageListState(initialMessages = messages)
        compose.setContent { MaterialTheme {
            ChatMessageList(state, messages, false, true, false, null, 80.dp, 100.dp) {
                DisposableEffect(it.id) { mounts++; onDispose { disposals++ } }
                Column(Modifier.fillMaxWidth().height(if (it.id == "reply") height else 40.dp)
                    .testTag("resizing-${it.id}")) {
                    Spacer(Modifier.weight(1f))
                    Text(it.id)
                }
            }
        } }
        compose.runOnIdle { height = 1800.dp }
        compose.onNodeWithTag("resizing-reply").assertHeightIsEqualTo(1800.dp)
        assertEquals(2, mounts)
        assertEquals(0, disposals)
        assertEquals(0L, state.requestCount)
        // Saved-history remeasure must not issue an opening correction. Explicit native
        // navigation must still reach the newly expanded real content.
        compose.runOnIdle { state.jump() }
        compose.waitForIdle()
        compose.onNodeWithText("reply").assertIsDisplayed()
    }

    @Test fun longReplyKeepsItsNaturalHeightWhenLatestAndAfterAnotherSend() {
        val messages = mutableStateOf(history() + listOf(
            message("last-user", ChatRole.USER), message("long-reply", ChatRole.MODEL)
        ))
        val state = ChatMessageListState(initialMessages = messages.value)
        val maximumHeights = mutableMapOf<String, Int>()
        var mounts = 0
        var disposals = 0
        compose.setContent { MaterialTheme {
            ChatMessageList(state, messages.value, false, false, false, null, 80.dp, 100.dp) { message ->
                if (message.id == "long-reply") {
                    DisposableEffect(message.id) { mounts++; onDispose { disposals++ } }
                    androidx.compose.ui.layout.Layout(content = {
                        Column(Modifier.fillMaxWidth().height(1800.dp).testTag("natural-long-reply")) {
                            Text("First line of the same reply")
                            Spacer(Modifier.weight(1f))
                            Text("Last line of the same reply")
                        }
                    }) { measurables, constraints ->
                        maximumHeights[message.id] = constraints.maxHeight
                        val child = measurables.single().measure(constraints)
                        layout(child.width, child.height) { child.placeRelative(0, 0) }
                    }
                } else Text(message.id, Modifier.height(40.dp))
            }
        } }
        assertEquals("Latest content must not inherit a viewport-sized height cap",
            androidx.compose.ui.unit.Constraints.Infinity, maximumHeights["long-reply"])
        compose.onNodeWithTag("natural-long-reply").assertHeightIsEqualTo(1800.dp)
        compose.onNodeWithText("Last line of the same reply").assertIsDisplayed()
        val originalHeight = compose.onNodeWithTag("natural-long-reply").getUnclippedBoundsInRoot().let { it.bottom - it.top }
        compose.runOnIdle {
            messages.value += listOf(message("next-user", ChatRole.USER), message("next-reply", ChatRole.MODEL))
            state.navigate("long-reply", toEnd = true)
        }
        compose.waitForIdle()
        assertEquals(androidx.compose.ui.unit.Constraints.Infinity, maximumHeights["long-reply"])
        assertEquals(originalHeight,
            compose.onNodeWithTag("natural-long-reply").getUnclippedBoundsInRoot().let { it.bottom - it.top })
        assertEquals("Sending another turn must not remount the previous reply", 1, mounts)
        assertEquals(0, disposals)
        compose.onNodeWithText("Last line of the same reply").assertIsDisplayed()
        compose.runOnIdle { state.navigate("long-reply") }
        compose.waitForIdle()
        compose.onNodeWithText("First line of the same reply").assertIsDisplayed()
    }

    @Test fun stopAndCompletionDoNotIssueScrollOrRemountMessage() {
        var streaming by mutableStateOf(true)
        val stream = MutableStateFlow<ActiveChatStreamState?>(null)
        var mounts = 0
        var disposals = 0
        val messages = listOf(message("user", ChatRole.USER), message("reply", ChatRole.MODEL))
        val state = ChatMessageListState(initialMessages = messages)
        compose.setContent { MaterialTheme {
            ChatMessageList(state, messages, streaming, true, false, stream, 80.dp, 100.dp) {
                DisposableEffect(it.id) { mounts++; onDispose { disposals++ } }
                Text(it.id, Modifier.height(60.dp))
            }
        } }
        val before = state.requestCount
        val beforeOffset = state.listState.firstVisibleItemScrollOffset
        compose.runOnIdle { streaming = false; stream.value = null }
        assertEquals(before, state.requestCount)
        assertEquals(beforeOffset, state.listState.firstVisibleItemScrollOffset)
        assertEquals(2, mounts)
        assertEquals(0, disposals)
    }

    @Test fun terminalStreamDoesNotFollowMetadataRelayoutWhilePersistenceIsBusy() {
        val messages = listOf(message("user", ChatRole.USER), message("reply", ChatRole.MODEL))
        val state = ChatMessageListState(initialMessages = messages)
        val content = StreamTextSnapshot.of("Published answer")
        val reasoning = StreamTextSnapshot.of()
        val freeze = StreamFreezeSignal()
        fun snapshot(status: ChatStreamStatus) = ActiveChatStreamState(
            conversationId = "chat", messageId = "reply", generationId = 1L, revision = 1L,
            status = status, content = content, reasoning = reasoning, stopSignal = freeze,
            lastCommitMetrics = null, cumulativeMetrics = StreamMetricsSnapshot()
        )
        val stream = MutableStateFlow<ActiveChatStreamState?>(snapshot(ChatStreamStatus.STREAMING))
        var cardHeight by mutableStateOf(60.dp)
        compose.setContent { MaterialTheme {
            // Generation busy can remain true until final persistence completes.
            ChatMessageList(state, messages, true, true, false, stream, 80.dp, 100.dp) {
                Text(it.id, Modifier.height(if (it.id == "reply") cardHeight else 40.dp))
            }
        } }
        val before = state.requestCount
        listOf(ChatStreamStatus.COMPLETED, ChatStreamStatus.FAILED, ChatStreamStatus.CANCELLED).forEach { status ->
            compose.runOnIdle {
                stream.value = snapshot(status)
                cardHeight += 20.dp
            }
            compose.waitForIdle()
            assertEquals("Terminal metadata relayout must not issue follow: $status", before, state.requestCount)
        }
    }

    @Test fun dragDetachesNativeFollowAndJumpRestoresIt() {
        val state = ChatMessageListState(initialMessages = history())
        compose.setContent { MaterialTheme {
            ChatMessageList(state, history(), true, true, false, null, 80.dp, 100.dp) {
                Text(it.id, Modifier.fillMaxWidth().height(160.dp))
            }
        } }
        compose.onNodeWithTag("chat-message-list").performTouchInput { swipeDown() }
        compose.runOnIdle { assertTrue(state.detached) }
        val count = state.requestCount
        compose.runOnIdle { state.follow(true) }
        assertEquals(count, state.requestCount)
        compose.runOnIdle { state.jump() }
        compose.waitForIdle()
        assertFalse(state.detached)
        compose.onNodeWithText("a100").assertIsDisplayed()
    }

    @Test fun slowFastAndBurstyDisplayGrowthHasNoFollowBacklogOrIdleTicker() {
        val messages = listOf(message("user", ChatRole.USER), message("reply", ChatRole.MODEL))
        val state = ChatMessageListState(initialMessages = messages)
        var text by mutableStateOf("")
        val stopSignal = StreamFreezeSignal()
        fun snapshot(revision: Long) = ActiveChatStreamState(
            conversationId = "chat", messageId = "reply", generationId = 1L, revision = revision,
            status = ChatStreamStatus.STREAMING, content = StreamTextSnapshot.of(text),
            reasoning = StreamTextSnapshot.of(), stopSignal = stopSignal,
            lastCommitMetrics = null, cumulativeMetrics = StreamMetricsSnapshot()
        )
        val stream = MutableStateFlow<ActiveChatStreamState?>(snapshot(0))
        compose.setContent { MaterialTheme {
            ChatMessageList(state, messages, true, true, false, stream, 80.dp, 100.dp) {
                Text(if (it.id == "reply") text else it.id,
                    Modifier.testTag("growing-${it.id}"))
            }
        } }
        compose.mainClock.autoAdvance = false
        listOf(160L, 16L, 1L).forEachIndexed { index, cadence ->
            repeat(6) { chunk ->
                compose.runOnIdle {
                    text += (1..8).joinToString("\n", postfix = "\n") { "Visible line $index-$chunk-$it" }
                    stream.value = snapshot((index * 6 + chunk + 1).toLong())
                }
                compose.mainClock.advanceTimeBy(cadence)
            }
            compose.mainClock.advanceTimeBy(2000)
            compose.onNodeWithTag("chat-content-end-reply").assertIsDisplayed()
            assertTrue("Live content must actually grow beyond one viewport",
                compose.onNodeWithTag("growing-reply").getUnclippedBoundsInRoot().let { it.bottom - it.top } >
                    compose.onRoot().getUnclippedBoundsInRoot().let { it.bottom - it.top })
            assertNull("No queued chunk animations", state.request)
        }
        val afterGrowth = state.requestCount
        compose.mainClock.advanceTimeBy(5000)
        assertEquals("A tool wait has no follow ticker", afterGrowth, state.requestCount)
        compose.mainClock.autoAdvance = true
    }

    @Test fun firstVisiblePublicationWithoutLayoutGrowthDoesNotRequestFollow() {
        val messages = listOf(message("user", ChatRole.USER), message("reply", ChatRole.MODEL))
        val state = ChatMessageListState(initialMessages = messages)
        val stream = MutableStateFlow<ActiveChatStreamState?>(null)
        compose.setContent { MaterialTheme {
            ChatMessageList(state, messages, true, true, false, stream, 80.dp, 100.dp) {
                // Keep layout unchanged to isolate the display-data publication trigger.
                Text(it.id, Modifier.height(40.dp))
            }
        } }
        val before = state.requestCount
        compose.runOnIdle {
            stream.value = ActiveChatStreamState(
                conversationId = "chat", messageId = "reply", generationId = 1L, revision = 1L,
                status = ChatStreamStatus.STREAMING, content = StreamTextSnapshot.of("First visible chunk"),
                reasoning = StreamTextSnapshot.of(), stopSignal = StreamFreezeSignal(),
                lastCommitMetrics = null, cumulativeMetrics = StreamMetricsSnapshot()
            )
        }
        compose.waitForIdle()
        assertEquals("Only rendered content growth triggers follow, not stream publications", before, state.requestCount)
    }

    @Test fun historyFirstLayoutUsesNativeEndAcrossViewportFontAndInsetChanges() {
        data class Viewport(val height: androidx.compose.ui.unit.Dp, val fontScale: Float,
            val top: androidx.compose.ui.unit.Dp, val bottom: androidx.compose.ui.unit.Dp)
        val cases = listOf(
            Viewport(640.dp, 1f, 80.dp, 100.dp),
            Viewport(320.dp, 1.6f, 60.dp, 100.dp),
            Viewport(480.dp, 1f, 80.dp, 220.dp)
        )
        var scenario by mutableIntStateOf(0)
        var mounted: ChatMessageListState? = null
        val messages = history()
        compose.setContent { MaterialTheme {
            val viewport = cases[scenario]
            val density = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
                androidx.compose.ui.unit.Density(density.density, viewport.fontScale)) {
                key(scenario) {
                    val state = remember { ChatMessageListState(initialMessages = messages) }
                    mounted = state
                    Box(Modifier.fillMaxWidth().height(viewport.height).testTag("viewport-case")) {
                        ChatMessageList(state, messages, false, false, false, null,
                            viewport.top, viewport.bottom) { message ->
                            if (message.id == "a100") {
                                Column(Modifier.fillMaxWidth().height(1800.dp)) {
                                    Text("Start of the final answer")
                                    Spacer(Modifier.weight(1f))
                                    Text("Final readable line")
                                }
                            } else Text(message.id)
                        }
                    }
                }
            }
        } }
        cases.indices.forEach { index ->
            compose.runOnIdle { scenario = index }
            compose.waitForIdle()
            compose.onNodeWithText("Final readable line").assertIsDisplayed()
            assertFalse(mounted!!.listState.layoutInfo.reverseLayout)
            assertEquals("A new history viewport never emits an opening scroll", 0L, mounted.requestCount)
        }
    }

    @Test fun historyReadVeilsTheExistingViewportWithoutUnmountingIt() {
        val state = ChatMessageListState(initialMessages = history())
        var loading by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            ChatMessageList(state, history(), false, false, loading, null, 80.dp, 100.dp) { Text(it.id) }
        } }
        compose.onNodeWithTag("chat-message-list").assertExists()
        compose.onNodeWithTag(OPENING_VEIL_TAG).assertExists()
        compose.runOnIdle { loading = false }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("a100").assertIsDisplayed()
        compose.onNodeWithTag(OPENING_VEIL_TAG).assertDoesNotExist()
        assertEquals(0L, state.requestCount)
    }
}
