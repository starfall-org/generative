package org.starfall.generative

import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.home.chat.ui.ConversationSummaryDialog
import org.starfall.generative.features.providers.shared.logic.ModelConnectionTests
import org.starfall.generative.features.providers.shared.ui.ModelConnectionDialog
import org.starfall.generative.shared.model.ConversationSummary
import org.starfall.generative.shared.model.SummaryRole
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderConfiguration
import org.starfall.generative.shared.ui.theme.GenerativeTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h640dp-xhdpi", application = LibraryTestApplication::class)
class DialogControlsLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun summaryRolesStayVisibleAndClickableWhileLongSummaryScrolls() {
        var summary by mutableStateOf(ConversationSummary("summary", (0..99).joinToString("\n") { "Paragraph $it" }, "message"))
        lateinit var userLabel: String
        lateinit var closeLabel: String
        lateinit var deleteLabel: String
        compose.setContent {
            GenerativeTheme(darkTheme = false, amoledMode = false) {
                userLabel = stringResource(R.string.common_user)
                closeLabel = stringResource(R.string.common_close)
                deleteLabel = stringResource(R.string.summary_delete)
                ConversationSummaryDialog(summary, onRoleChange = { summary = summary.copy(role = it) }, onDelete = {}, onDismiss = {})
            }
        }
        val cancel = compose.onNodeWithText(closeLabel).fetchSemanticsNode().boundsInRoot
        val delete = compose.onNodeWithText(deleteLabel).fetchSemanticsNode().boundsInRoot
        assertTrue("Delete must follow Close without a Save button", delete.left > cancel.left)
        val roles = compose.onNodeWithTag("summary-role-selector").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val content = compose.onNodeWithTag("summary-dialog-content")
        assertTrue("The role selector belongs below the scrolling content", roles.top >= content.fetchSemanticsNode().boundsInRoot.bottom)
        content.performTouchInput { swipeUp() }
        assertTrue("The summary must actually scroll", content.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
        val after = compose.onNodeWithTag("summary-role-selector").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(roles.top, after.top, 1f)
        compose.onNodeWithText(userLabel).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(SummaryRole.USER, summary.role) }
    }

    @Test fun connectionToolbarShowsIdleCountsAndKeepsBatchActionsAboveScrollingList() {
        val models = (0..29).associate { "model-$it" to ModelConfiguration() }
        val provider = ModelProvider("provider", "Provider", ModelApiType.OPENAI, baseUrl = "https://example.test",
            config = ProviderConfiguration(modelConfigs = models))
        lateinit var checkAll: String
        lateinit var removeUnavailable: String
        lateinit var checkModel: String
        lateinit var deleteModel: String
        lateinit var idleProgress: String
        compose.setContent {
            val scope = rememberCoroutineScope()
            val tests = remember { ModelConnectionTests(scope) { _, id ->
                if (id == "model-0") Result.failure(IllegalStateException("Unavailable")) else Result.success("OK")
            } }
            GenerativeTheme(darkTheme = false, amoledMode = false) {
                checkAll = stringResource(R.string.provider_test_all_chat_models)
                removeUnavailable = stringResource(R.string.remove_unavailable_models)
                checkModel = stringResource(R.string.test_model, "model-29")
                deleteModel = stringResource(R.string.delete_model, "model-29")
                idleProgress = stringResource(R.string.connection_count_progress, 0, models.size)
                ModelConnectionDialog(provider, tests, available = true, onRemoveModels = {}, onDismiss = {})
            }
        }
        compose.onNodeWithContentDescription(idleProgress).assertIsDisplayed()
        val toolbar = compose.onNodeWithTag("connection-toolbar").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        compose.onNode(hasContentDescription(checkAll) and hasAnyAncestor(hasTestTag("connection-toolbar")))
            .assertIsDisplayed().performClick()
        val batchCheck = compose.onNode(hasContentDescription(checkAll) and hasAnyAncestor(hasTestTag("connection-toolbar")))
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val batchDelete = compose.onNode(hasContentDescription(removeUnavailable) and hasAnyAncestor(hasTestTag("connection-toolbar")))
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Batch check must precede batch delete, like the per-model actions", batchCheck.left < batchDelete.left)
        compose.onNodeWithTag("connection-model-list").performScrollToIndex(29)
        val modelCheck = compose.onNodeWithContentDescription(checkModel).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val modelDelete = compose.onNodeWithContentDescription(deleteModel).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Per-model check must precede delete", modelCheck.left < modelDelete.left)
        val after = compose.onNodeWithTag("connection-toolbar").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(toolbar.top, after.top, 1f)
        assertEquals(toolbar.height, after.height, 1f)
        compose.onNodeWithContentDescription(checkAll).assertIsDisplayed()
        compose.onNodeWithContentDescription(removeUnavailable).assertIsDisplayed()
        val footer = compose.onNodeWithTag("app-dialog-buttons").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Batch actions belong above the model list and footer", after.bottom < footer.top)
    }
}
