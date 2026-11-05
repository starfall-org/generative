package org.starfall.generative

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.shared.subfeatures.logging.logic.DebugLog
import org.starfall.generative.shared.model.logging.DebugLogCategory
import org.starfall.generative.shared.model.logging.DebugLogLevel
import org.starfall.generative.shared.subfeatures.logging.ui.DebugLogPanel

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en-w420dp-h900dp-xhdpi", application = LibraryTestApplication::class)
class DebugLogPanelUiTest {
    @get:Rule val compose = createComposeRule()
    @After fun reset() = DebugLog.configure(false, emptySet())

    @Test fun listShowsCompactSummaryAndOpensCompleteEventInDialog() {
        DebugLog.configure(true, DebugLogCategory.all, DebugLogLevel.INFO)
        val id = DebugLog.beginRequest("HTTP", "GET https://example.test/v1",
            method = "GET", endpoint = "https://example.test/v1")!!
        DebugLog.finishRequest(id, "HTTP", "← 503 GET https://example.test/v1", true, statusCode = 503)
        compose.setContent { MaterialTheme { DebugLogPanel(Modifier.fillMaxSize()) } }
        compose.onNodeWithTag("debug-log-entry-$id").assertIsDisplayed()
        compose.onNodeWithText("request body confidential", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("debug-log-entry-$id").performClick()
        compose.onNodeWithTag("debug-log-detail").assertIsDisplayed()
        compose.onNodeWithText("503").assertExists()
        compose.onNodeWithText("https://example.test/v1").assertExists()
        compose.onNodeWithText("Timestamp").assertExists()
        compose.onNodeWithText("Thread").assertExists()
        compose.onNodeWithText("Duration").assertExists()
        compose.onNodeWithTag("debug-log-detail-copy").assertExists()
        compose.onNodeWithTag("debug-log-detail-close").performClick()
        compose.onNodeWithTag("debug-log-detail").assertDoesNotExist()
    }

    @Test fun toolbarFilterCombinesSourceAndSeverityWithoutApplyingOrClosing() {
        DebugLog.configure(true, DebugLogCategory.all, DebugLogLevel.TRACE)
        DebugLog.log(DebugLogCategory.NETWORK, "HTTP", DebugLogLevel.INFO) { "Request succeeded" }
        DebugLog.log(DebugLogCategory.NETWORK, "HTTP", DebugLogLevel.ERROR) { "Request failed" }
        DebugLog.log(DebugLogCategory.SYSTEM, "Logic", DebugLogLevel.ERROR) { "Logic failed" }
        val ids = DebugLog.entries.value.map { it.id }
        compose.setContent { MaterialTheme { DebugLogPanel(Modifier.fillMaxSize()) } }
        compose.onNodeWithTag("debug-log-filter-summary").assertDoesNotExist()
        compose.onNodeWithTag("debug-log-filter").performClick()
        compose.onNodeWithTag("debug-log-category-network").performClick()
        compose.onNodeWithTag("debug-log-entry-${ids[0]}").assertExists()
        compose.onNodeWithTag("debug-log-entry-${ids[1]}").assertExists()
        compose.onNodeWithTag("debug-log-entry-${ids[2]}").assertDoesNotExist()
        compose.onNodeWithTag("debug-log-level-error").performScrollTo().performClick()
        compose.onNodeWithTag("debug-log-entry-${ids[0]}").assertDoesNotExist()
        compose.onNodeWithTag("debug-log-entry-${ids[1]}").assertExists()
        compose.onNodeWithTag("debug-log-entry-${ids[2]}").assertDoesNotExist()
        compose.onNodeWithTag("debug-log-filter-summary").assertTextEquals("Network · ERROR")
        compose.runOnIdle {
            DebugLog.log(DebugLogCategory.NETWORK, "HTTP", DebugLogLevel.ERROR) { "Live API failure" }
            DebugLog.log(DebugLogCategory.SYSTEM, "Logic", DebugLogLevel.ERROR) { "Live system failure" }
            DebugLog.log(DebugLogCategory.NETWORK, "HTTP", DebugLogLevel.INFO) { "Live success" }
        }
        val liveIds = DebugLog.entries.value.takeLast(3).map { it.id }
        compose.onNodeWithTag("debug-log-entry-${liveIds[0]}").assertExists()
        compose.onNodeWithTag("debug-log-entry-${liveIds[1]}").assertDoesNotExist()
        compose.onNodeWithTag("debug-log-entry-${liveIds[2]}").assertDoesNotExist()
        compose.onNodeWithTag("debug-log-category-all").performScrollTo().performClick()
        compose.onNodeWithTag("debug-log-entry-${ids[2]}").assertExists()
        compose.onNodeWithTag("debug-log-entry-${ids[0]}").assertDoesNotExist()
        compose.onNodeWithTag("debug-log-level-all").performScrollTo().performClick()
        ids.forEach { compose.onNodeWithTag("debug-log-entry-$it").assertExists() }
        compose.onNodeWithTag("debug-log-filter-summary").assertDoesNotExist()
    }

    @Test fun searchableAndCategoryFilteredLogsStayCompact() {
        DebugLog.configure(true, DebugLogCategory.all, DebugLogLevel.INFO)
        DebugLog.log(DebugLogCategory.NETWORK, "HTTP") { "GET example" }
        DebugLog.log(DebugLogCategory.SYSTEM, "Failure", DebugLogLevel.ERROR) { "critical sample marker" }
        val ids = DebugLog.entries.value.map { it.id }
        compose.setContent { MaterialTheme { DebugLogPanel(Modifier.fillMaxSize()) } }
        compose.onNodeWithTag("debug-log-entry-${ids.first()}").assertExists()
        compose.onNodeWithTag("debug-log-entry-${ids.last()}").assertExists()
        compose.onNodeWithTag("debug-log-search").performTextInput("critical")
        compose.onNodeWithTag("debug-log-entry-${ids.first()}").assertDoesNotExist()
        compose.onNodeWithTag("debug-log-entry-${ids.last()}").assertExists()
        compose.onNodeWithTag("debug-log-search").performTextReplacement("")
        compose.onNodeWithTag("debug-log-category-filter").assertDoesNotExist()
        compose.onNodeWithTag("debug-log-level-filter").assertDoesNotExist()
        compose.onNodeWithTag("debug-log-filter").performClick()
        compose.onNodeWithTag("debug-log-category-system").performClick()
        compose.onNodeWithTag("debug-log-entry-${ids.first()}").assertDoesNotExist()
        compose.onNodeWithTag("debug-log-entry-${ids.last()}").assertExists()
        compose.onNodeWithTag("debug-log-filter-summary").assertTextEquals("System")
    }
}
