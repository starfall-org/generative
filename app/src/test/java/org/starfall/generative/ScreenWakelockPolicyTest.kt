package org.starfall.generative

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.starfall.generative.database.preferences.ScreenWakelockMode

class ScreenWakelockPolicyTest {
    @Test fun automaticModeKeepsScreenOnWhileWaitingForApiResponse() {
        assertTrue(
            shouldKeepScreenOn(
                ScreenWakelockMode.EXCEPT_TOOL_CALLS,
                anyGenerationActive = true,
                anyResponseActive = true
            )
        )
    }

    @Test fun automaticModeReleasesScreenDuringToolCalls() {
        assertFalse(
            shouldKeepScreenOn(
                ScreenWakelockMode.EXCEPT_TOOL_CALLS,
                anyGenerationActive = true,
                anyResponseActive = false
            )
        )
    }

    @Test fun allModeKeepsScreenOnForToolCallsToo() {
        assertTrue(
            shouldKeepScreenOn(
                ScreenWakelockMode.ALL,
                anyGenerationActive = true,
                anyResponseActive = false
            )
        )
    }

    @Test fun noGenerationNeverKeepsScreenOn() {
        assertFalse(
            shouldKeepScreenOn(
                ScreenWakelockMode.ALL,
                anyGenerationActive = false,
                anyResponseActive = false
            )
        )
        assertFalse(
            shouldKeepScreenOn(
                ScreenWakelockMode.EXCEPT_TOOL_CALLS,
                anyGenerationActive = false,
                anyResponseActive = false
            )
        )
    }
}
