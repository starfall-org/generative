package org.starfall.generative

import org.junit.Assert.assertEquals
import org.junit.Test
import org.starfall.generative.features.home.chat.ui.chatAppBarOverlayAlpha

class ChatAppBarOpacityTest {
    @Test
    fun themedScrimFadesFromClearBottomToEightyPercentAtStatusBar() {
        assertEquals(0.80f, chatAppBarOverlayAlpha(0f), 0.0001f)
        assertEquals(0.40f, chatAppBarOverlayAlpha(0.5f), 0.0001f)
        assertEquals(0f, chatAppBarOverlayAlpha(1f), 0.0001f)
    }

    @Test
    fun scrimGradientClampsPositionsOutsideTheAppBar() {
        assertEquals(0.80f, chatAppBarOverlayAlpha(-1f), 0.0001f)
        assertEquals(0f, chatAppBarOverlayAlpha(2f), 0.0001f)
    }
}
