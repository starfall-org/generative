package org.starfall.generative

import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.features.home.chat.ui.*
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.MessageVersion
import org.starfall.generative.shared.model.StoredMessage

class ChatMessageListStateTest {
    private fun message(id: String, role: ChatRole) = StoredMessage(id, role, listOf(MessageVersion(content = id)))

    @Test fun initialEndBoundaryUsesChronologicalStableTurns() {
        val messages = listOf(message("u1", ChatRole.USER), message("m1", ChatRole.MODEL),
            message("tool-block", ChatRole.MODEL), message("u2", ChatRole.USER), message("m2", ChatRole.MODEL))
        assertEquals(listOf(listOf("u1", "m1", "tool-block"), listOf("u2", "m2")),
            chatMessageTurns(messages).map { it.map { message -> message.id } })
        val state = ChatMessageListState(initialMessages = messages)
        assertEquals("Initial position is the boundary after the chronological turns", 2,
            state.listState.firstVisibleItemIndex)
        assertEquals(0, state.listState.firstVisibleItemScrollOffset)
        assertEquals(0L, state.requestCount)
        assertTrue(chatMessageTurns(emptyList()).isEmpty())
    }

    @Test fun assistantOnlyHistoryAndQueuedPromotionKeepStableKeys() {
        val assistant = message("m1", ChatRole.MODEL)
        val queued = message("q", ChatRole.USER).copy(isQueued = true)
        val before = chatMessageTurns(listOf(assistant, queued))
        val after = chatMessageTurns(listOf(assistant, queued.copy(isQueued = false), message("reply", ChatRole.MODEL)))
        assertEquals(before.map { it.first().id }, after.map { it.first().id })
    }

    @Test fun bottomGapIsDeclarativeAndCappedByUsableViewport() {
        assertEquals(168f, chatBottomGap(800.dp, 600.dp).value, 0.001f)
        assertEquals(180f, chatBottomGap(1000.dp, 180.dp).value, 0.001f)
        assertEquals(0f, chatBottomGap(800.dp, (-1).dp).value, 0.001f)
    }

    @Test fun sendHasPriorityOverContentFollowAndDoesNotEnablePreference() {
        val state = ChatMessageListState()
        state.send("user")
        val send = state.request!!
        state.follow(enabled = true)
        assertEquals(send, state.request)
        state.complete(send)
        state.follow(enabled = false)
        assertNull(state.request)
    }

    @Test fun manualTakeoverCancelsRequestsAndMinimapDoesNotEnableFollow() {
        val state = ChatMessageListState()
        state.follow(enabled = true)
        state.manualScroll(towardsEnd = false)
        state.follow(enabled = true)
        assertNull(state.request)
        state.navigate("old")
        val navigation = state.request!!
        state.follow(enabled = true)
        assertEquals(navigation, state.request)
        state.complete(navigation)
        assertTrue(state.detached)
        state.jump()
        assertFalse(state.detached)
        assertEquals(ChatListAction.Jump, state.request!!.action)
    }

    @Test fun repeatedMinimapTargetKeepsTheInFlightNativeNavigation() {
        val state = ChatMessageListState()
        state.navigate("reply", toEnd = true)
        val first = state.request
        val count = state.requestCount
        state.navigate("reply", toEnd = true)
        assertEquals(first, state.request)
        assertEquals(count, state.requestCount)
        state.navigate("reply", toEnd = false)
        assertNotEquals(first, state.request)
        assertTrue(state.detached)
    }

    @Test fun latestFollowSupersedesObsoleteFollowButCannotClearNewerSend() {
        val state = ChatMessageListState()
        state.follow(enabled = true)
        val first = state.request!!
        state.follow(enabled = true)
        assertNotEquals(first, state.request)
        state.send("new-user")
        state.complete(first)
        assertEquals(ChatListAction.Send("new-user"), state.request!!.action)
    }

    @Test fun contentGrowthIsTheOnlyAutomaticFollowTriggerAndRespectsSettings() {
        val state = ChatMessageListState()
        state.contentEndPosition("reply", 100, enabled = true) // Initial layout is not a stream update.
        assertEquals(0L, state.requestCount)
        state.contentEndPosition("reply", 100, enabled = true) // Scroll moves the viewport, not the content end within its turn.
        assertEquals(0L, state.requestCount)
        state.contentEndPosition("reply", 140, enabled = false) // Auto-scroll disabled in settings.
        assertEquals(0L, state.requestCount)
        state.endVisibility("reply", true, "reply") // Only a visible latest can start following.
        state.contentEndPosition("reply", 180, enabled = true)
        assertEquals(ChatListAction.Follow, state.request?.action)
        assertEquals(1L, state.requestCount)
        state.contentEndPosition("reply", 180, enabled = true)
        assertEquals(1L, state.requestCount)
        state.manualScroll(towardsEnd = false)
        state.contentEndPosition("reply", 230, enabled = true)
        assertEquals("Manual drag pauses automatic follow", 1L, state.requestCount)
        state.resume()
        state.endVisibility("reply", true, "reply") // Manual return to the visible latest.
        state.contentEndPosition("reply", 250, enabled = true)
        assertEquals(2L, state.requestCount)
        state.contentEndPosition("another-reply", 10, enabled = true)
        assertEquals("New messages establish a new layout baseline", 2L, state.requestCount)
    }

    @Test fun nativeGrowthAnchoringSkipsDuplicateAnimatedFollowRequests() {
        val state = ChatMessageListState()
        val applied = mutableListOf<Int>()
        state.endVisibility("reply", true, "reply")
        state.contentEndPosition("reply", 100, enabled = true) { delta -> applied += delta; true }
        state.contentEndPosition("reply", 145, enabled = true) { delta -> applied += delta; true }
        state.contentEndPosition("reply", 175, enabled = true) { delta -> applied += delta; true }
        assertEquals(listOf(45, 30), applied)
        assertEquals("Measured growth has no extra animation intent", 0L, state.requestCount)

        state.manualScroll(towardsEnd = false)
        state.contentEndPosition("reply", 210, enabled = true) { delta -> applied += delta; true }
        assertEquals("Manual scroll detaches native anchoring", listOf(45, 30), applied)
        state.resume()
        state.send("user")
        state.contentEndPosition("reply", 245, enabled = true) { delta -> applied += delta; true }
        assertEquals("A send/navigation request is never overridden", listOf(45, 30), applied)
        assertEquals(ChatListAction.Send("user"), state.request?.action)
    }

    @Test fun growthNeverPinsOffscreenHistoryAndLosesPinOnManualScroll() {
        val state = ChatMessageListState()
        val offsets = mutableListOf<Int>()
        fun growth(y: Int) = state.contentEndPosition("reply", y, enabled = true) { delta ->
            offsets += delta
            true
        }
        growth(100)
        growth(150) // Auto-scroll preference is on, but the user has not reached latest.
        assertTrue("Offscreen latest may not initiate an anchor", offsets.isEmpty())
        assertEquals(0L, state.requestCount)
        assertFalse(state.isLatestPinned("reply"))

        state.endVisibility("older", true, "reply")
        growth(190)
        assertTrue(offsets.isEmpty())
        state.endVisibility("reply", true, "reply")
        growth(220)
        assertEquals(listOf(30), offsets)
        assertTrue(state.isLatestPinned("reply"))

        // The moving edge may briefly leave the viewport. Keep the pin until user interaction.
        state.endVisibility("reply", false, "reply")
        growth(250)
        assertEquals(listOf(30, 30), offsets)
        state.manualScroll(towardsEnd = false)
        growth(300)
        assertEquals(listOf(30, 30), offsets)
        state.resume() // An explicit resume alone is not proof of latest visibility.
        growth(330)
        assertEquals(listOf(30, 30), offsets)
        state.endVisibility("reply", true, "reply")
        growth(350)
        assertEquals(listOf(30, 30, 20), offsets)
    }

    @Test fun freezeCancelsOnlyAutomaticFollowNotExplicitNavigation() {
        val state = ChatMessageListState()
        state.follow(true)
        state.cancelFollow()
        assertNull(state.request)
        state.send("user")
        val send = state.request
        state.cancelFollow()
        assertEquals(send, state.request)
    }

    @Test fun onlyNativeManualReturnToVisibleRealEndResumesFollow() {
        val state = ChatMessageListState()
        state.detach()
        state.endVisibility("end", true, "end")
        assertTrue("Minimap navigation alone cannot resume following", state.detached)
        state.manualScroll(towardsEnd = true)
        state.endVisibility("old", true, "end")
        assertTrue(state.detached)
        state.endVisibility("end", true, "end")
        assertFalse(state.detached)
    }
}
