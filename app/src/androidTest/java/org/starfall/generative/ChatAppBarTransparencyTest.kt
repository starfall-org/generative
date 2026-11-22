package org.starfall.generative

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.starfall.generative.features.home.chat.ui.ChatAppBar
import org.starfall.generative.features.home.chat.ui.ChatMessageList
import org.starfall.generative.features.home.chat.ui.ChatMessageListState
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.MessageVersion
import org.starfall.generative.shared.model.StoredMessage

class ChatAppBarTransparencyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun scrolledContentRemainsVisibleAcrossTheAppBarGradient() {
        val messages = listOf(StoredMessage("message", ChatRole.USER, listOf(MessageVersion(content = "Content"))))
        lateinit var state: ChatMessageListState
        lateinit var scope: CoroutineScope
        var topPadding = 0
        compose.setContent {
            state = remember { ChatMessageListState(messages) }
            scope = rememberCoroutineScope()
            topPadding = with(LocalDensity.current) { 120.dp.roundToPx() }
            MaterialTheme(colorScheme = darkColorScheme(background = Color.Black)) {
                Box(Modifier.fillMaxSize().background(Color.Black).testTag("chat-rendering")) {
                    ChatMessageList(
                        state = state, messages = messages, streaming = false, autoScroll = false,
                        historyLoading = false, activeStream = null,
                        appBarHeight = 120.dp, bottomClearance = 0.dp,
                        modifier = Modifier.fillMaxSize()
                    ) { Box(Modifier.fillMaxWidth().height(1600.dp).background(Color.Red)) }
                    ChatAppBar(
                        modelName = "Model", providerName = "Provider",
                        onOpenDrawer = {}, onNewChat = {}, onOpenSettings = {}
                    )
                }
            }
        }
        compose.runOnIdle { scope.launch { state.listState.scrollToItem(0, topPadding) } }
        compose.waitForIdle()
        val barHeight = compose.onNodeWithTag("chat-app-bar").fetchSemanticsNode().size.height
        val pixels = compose.onNodeWithTag("chat-rendering").captureToImage().toPixelMap()
        // Sample outside the controls. A second mask on the list would erase this red backdrop.
        for (fraction in listOf(0.1f, 0.5f, 0.9f)) {
            val pixel = pixels[1, (barHeight * fraction).toInt()]
            assertEquals("Backdrop at height $fraction", 1f - 0.8f * (1f - fraction), pixel.red, 0.035f)
            assertEquals(0f, pixel.green, 0.01f)
            assertEquals(0f, pixel.blue, 0.01f)
        }
    }
}
