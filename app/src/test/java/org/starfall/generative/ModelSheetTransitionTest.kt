package org.starfall.generative

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.constrainHeight
import org.starfall.generative.shared.ui.sheets.BottomSheet
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.home.BottomSheetsLink
import org.starfall.generative.features.home.BottomSheetsLinkState
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.ConversationSummaryRequest
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderConfiguration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-xhdpi")
class ModelSheetTransitionTest {
    @get:Rule val compose = createComposeRule()

    private val provider = ModelProvider(
        "p", "Provider", ModelApiType.OPENAI, baseUrl = "",
        config = ProviderConfiguration(modelConfigs = mapOf("m1" to ModelConfiguration(displayName = "Model One")))
    )

    @Composable
    private fun Host(
        sheets: BottomSheetsLinkState,
        providerList: List<ModelProvider> = listOf(provider),
        onStartSummary: ((ConversationSummaryRequest) -> Boolean)? = null
    ) {
        MaterialTheme {
            BottomSheetsLink(
                sheets = sheets,
                mediaKind = null,
                onMediaKindChange = {},
                onCancelEdit = {},
                onShowSkillsMenu = {},
                providers = providerList,
                providerGroups = emptyList(),
                selectedProviderId = "p",
                selectedModelName = "m1",
                conversationReasoningEffort = null,
                onSetReasoningEffort = {},
                onSelectModel = { _, _ -> },
                onSetThinkingPassback = { _, _, _ -> },
                onSetModelReasoningEffort = { _, _, _ -> },
                onSetModelTools = { _, _, _ -> },
                onEditModel = { _, _ -> },
                onEditProvider = {},
                modelPickerCollapsedGroups = emptySet(),
                modelPickerCollapsedProviders = emptySet(),
                onModelPickerCollapsedGroupsChange = {},
                onModelPickerCollapsedProvidersChange = {},
                dynamicModelsMap = emptyMap(),
                mediaProviders = emptyList(),
                mediaProviderId = "",
                mediaModelId = "",
                onMediaModelChange = { _, _ -> },
                attachments = emptyList(),
                onAttachmentsChange = {},
                onStartSummary = onStartSummary
            )
        }
    }

    @Test
    fun switchingBetweenSelectedModelAndPickerSlidesAndResizesInOneSheet() {
        compose.setContent {
            val sheets = remember { BottomSheetsLinkState().apply { openSelectedModelOverview() } }
            Host(sheets)
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false

        val initial = compose.onNodeWithTag("bottom-sheet-window").fetchSemanticsNode()
        val sheetNodeId = initial.id
        assertTrue(initial.boundsInRoot.height > 0f)

        compose.onNodeWithTag("open-model-picker-button").performClick()
        compose.mainClock.advanceTimeBy(100)
        val mid = compose.onNodeWithTag("bottom-sheet-window").fetchSemanticsNode()
        assertEquals("The sheet must stay open instead of closing and reopening", sheetNodeId, mid.id)
        val enteringLeftPx = with(compose.density) {
            compose.onNodeWithTag("model-picker-list").getUnclippedBoundsInRoot().left.toPx()
        }
        assertTrue("The picker should slide in from the right", enteringLeftPx > 40f)

        compose.mainClock.advanceTimeBy(700)
        // Material 3 owns the two sheet-height anchors; switching pages must preserve the
        // same sheet and expose the picker, not require a particular resized height.
        assertEquals(sheetNodeId, compose.onNodeWithTag("bottom-sheet-window").fetchSemanticsNode().id)
        compose.onNodeWithTag("model-picker-list").assertIsDisplayed()
        compose.onNodeWithTag("open-selected-model-button").assertIsDisplayed()

        compose.onNodeWithTag("open-selected-model-button").performClick()
        compose.mainClock.advanceTimeBy(100)
        assertEquals(sheetNodeId, compose.onNodeWithTag("bottom-sheet-window").fetchSemanticsNode().id)
        compose.mainClock.advanceTimeBy(700)
        val restored = compose.onNodeWithTag("bottom-sheet-window").fetchSemanticsNode()
        assertEquals(sheetNodeId, restored.id)
        assertTrue("The shared sheet must retain a measurable height", restored.boundsInRoot.height > 0f)
        compose.onNodeWithTag("open-model-picker-button").assertIsDisplayed()
    }

    @Test
    fun longPickerExpandsExistingSelectedModelSheetWithoutReopeningIt() {
        val models = listOf("m1") + (0 until 20).map { "other-$it" }
        val manyModelsProvider = provider.copy(config = provider.config.copy(modelIds = models))
        compose.setContent {
            val sheets = remember { BottomSheetsLinkState().apply { openSelectedModelOverview() } }
            Host(sheets, providerList = listOf(manyModelsProvider))
        }
        compose.waitForIdle()
        val before = compose.onNodeWithTag("bottom-sheet-window").fetchSemanticsNode()
        compose.onNodeWithTag("open-model-picker-button").performClick()
        compose.waitForIdle()
        val expanded = compose.onNodeWithTag("bottom-sheet-window").fetchSemanticsNode()
        assertEquals("Picker expansion should retain the same modal sheet", before.id, expanded.id)
        assertTrue("Long picker must expand upward from the overview", expanded.boundsInRoot.top < before.boundsInRoot.top)
        compose.onNodeWithTag("model-picker-list").assertIsDisplayed()
    }

    @Test
    fun contentRemeasuresWithoutManualSheetResizeState() {
        val contentHeight = mutableIntStateOf(300)
        compose.setContent {
            MaterialTheme {
                BottomSheet(onDismissRequest = {}) {
                    Layout(content = {}, modifier = Modifier) { _, constraints ->
                        layout(constraints.maxWidth, constraints.constrainHeight(contentHeight.intValue.dp.roundToPx())) {}
                    }
                }
            }
        }
        compose.waitForIdle()
        val beforeTop = compose.onNodeWithTag("bottom-sheet-window").fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { contentHeight.intValue = 400 }
        compose.waitForIdle()
        val grownTop = compose.onNodeWithTag("bottom-sheet-window").fetchSemanticsNode().boundsInRoot.top
        val growth = with(compose.density) { 100.dp.toPx() }
        assertEquals(growth, beforeTop - grownTop, 2f)
        // Material's handle is present; its native anchors now own drags and scroll flings.
        compose.onNodeWithTag("bottom-sheet-drag-handle", useUnmergedTree = true).assertExists()
    }

    @Test
    fun summarizationSlidesInSameSheetAndReturnsOrStarts() {
        var allowStart = false
        var startedRequest: ConversationSummaryRequest? = null
        compose.setContent {
            val sheets = remember { BottomSheetsLinkState().apply { openSelectedModelOverview() } }
            Host(sheets) { request -> startedRequest = request; allowStart }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val sheetId = compose.onNodeWithTag("bottom-sheet-window").fetchSemanticsNode().id

        compose.onNodeWithTag("summarize-conversation-button").performClick()
        compose.mainClock.advanceTimeBy(100)
        assertEquals(sheetId, compose.onNodeWithTag("bottom-sheet-window").fetchSemanticsNode().id)
        val enteringLeft = with(compose.density) {
            compose.onNodeWithTag("summary-sheet-content").getUnclippedBoundsInRoot().left.toPx()
        }
        assertTrue("Summary should slide in from the right", enteringLeft > 40f)
        compose.mainClock.advanceTimeBy(350)
        compose.onNodeWithText("Conversation Summarization").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.mainClock.advanceTimeBy(800)
        assertEquals(sheetId, compose.onNodeWithTag("bottom-sheet-window").fetchSemanticsNode().id)
        compose.onNodeWithTag("summarize-conversation-button").assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeBy(350)
        compose.onNodeWithText("Start summarization").performScrollTo().performClick()
        assertEquals(6000, startedRequest!!.targetTokens)
        // A paused Compose clock needs a frame to publish the failed-submit message.
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText(
            "Summarization can’t start. Set a Chat Summary language model in Chat Options, and make sure no response or summarization is already running."
        ).performScrollTo().assertIsDisplayed()
        assertEquals(sheetId, compose.onNodeWithTag("bottom-sheet-window").fetchSemanticsNode().id)

        allowStart = true
        compose.onNodeWithText("Start summarization").performClick()
        compose.mainClock.advanceTimeBy(350)
        compose.onNodeWithTag("bottom-sheet-window").assertDoesNotExist()
    }

}
