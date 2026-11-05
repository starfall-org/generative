package org.starfall.generative

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.appdata.ui.AppDataScreen
import org.starfall.generative.shared.ui.theme.GenerativeTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-xhdpi", application = LibraryTestApplication::class)
class AppDataImportInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun importRunsWithoutBlockingNavigationOrTheDataControlsScreen() {
        var backCalls = 0
        compose.setContent {
            GenerativeTheme(darkTheme = false, amoledMode = false) {
                AppDataScreen(
                    conversationCount = 5,
                    providerCount = 2,
                    onClearAllConversations = {},
                    onResetAllData = {},
                    importInProgress = true,
                    onBack = { backCalls++ }
                )
            }
        }

        compose.onNodeWithText("Importing in the background. You can leave this screen.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Import Complete Backup (ZIP)").assertIsNotEnabled()
        // Changes that could corrupt a full restore stay unavailable, but navigation does not.
        compose.onNodeWithTag("app-data-manage").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Clear All Chat History").assertDoesNotExist()
        compose.onNodeWithText("Reset All Application Data").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, backCalls) }
    }

    @Test fun destructiveActionsAreBelowBackupAndRequireSeparateConfirmation() {
        var cleared = 0
        var reset = 0
        compose.setContent {
            GenerativeTheme(darkTheme = false, amoledMode = false) {
                AppDataScreen(5, 2, onClearAllConversations = { cleared++ },
                    onResetAllData = { reset++ }, onBack = {})
            }
        }
        val backup = compose.onNodeWithText("Import Complete Backup (ZIP)")
            .performScrollTo().fetchSemanticsNode().boundsInRoot
        val manage = compose.onNodeWithTag("app-data-manage")
            .performScrollTo().fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("Manage data must follow backup", manage.top > backup.top)
        compose.onNodeWithText("Clear All Chat History").assertDoesNotExist()
        compose.onNodeWithText("Reset All Application Data").assertDoesNotExist()

        compose.onNodeWithTag("app-data-manage").performClick()
        compose.onNodeWithTag("app-data-clear-chats").assertExists()
        compose.onNodeWithTag("app-data-reset-all").assertExists()
        compose.onNodeWithTag("app-data-clear-chats").performClick()
        compose.onNodeWithText("Clear All Chats").assertExists()
        compose.runOnIdle { assertEquals(0, cleared) }
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { assertEquals(0, cleared) }

        compose.onNodeWithTag("app-data-manage").performClick()
        compose.onNodeWithTag("app-data-clear-chats").performClick()
        compose.onNodeWithText("Clear All").performClick()
        compose.runOnIdle {
            assertEquals(1, cleared)
            assertEquals(0, reset)
        }

        compose.onNodeWithTag("app-data-manage").performClick()
        compose.onNodeWithTag("app-data-reset-all").performClick()
        compose.onNodeWithText("Factory Reset Data").assertExists()
        compose.runOnIdle { assertEquals(0, reset) }
        compose.onNodeWithText("Reset Everything").performClick()
        compose.runOnIdle { assertEquals(1, reset) }
    }

    @Test fun importActionsBecomeAvailableAgainWhenTheBackgroundJobFinishes() {
        var importing by mutableStateOf(true)
        compose.setContent {
            GenerativeTheme(darkTheme = false, amoledMode = false) {
                AppDataScreen(0, 0, {}, {}, importInProgress = importing, onBack = {})
            }
        }
        compose.runOnIdle { importing = false }
        compose.onNodeWithText("Importing in the background. You can leave this screen.")
            .assertDoesNotExist()
        compose.onNodeWithText("Import Complete Backup (ZIP)").performScrollTo().assertIsEnabled()
    }
}
