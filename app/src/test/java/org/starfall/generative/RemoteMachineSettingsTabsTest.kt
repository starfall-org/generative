package org.starfall.generative

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.shared.ui.theme.GenerativeTheme
import org.starfall.generative.shared.ui.layout.AppSwipeTabs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h800dp-xhdpi", application = LibraryTestApplication::class)
class RemoteMachineSettingsTabsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sshKeysFirstAndGoogleOAuthSecondWithTapAndSwipe() {
        compose.setContent {
            GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
                val pager = rememberPagerState(pageCount = { 2 })
                AppSwipeTabs(
                    tabs = listOf("SSH keys", "Google OAuth"),
                    pagerState = pager,
                    modifier = Modifier.fillMaxSize(),
                    tabTag = { if (it == 0) "remote-settings-ssh-tab" else "remote-settings-oauth-tab" }
                ) { page -> Text(if (page == 0) "SSH key content" else "OAuth client content") }
            }
        }

        val ssh = compose.onNodeWithTag("remote-settings-ssh-tab")
        val oauth = compose.onNodeWithTag("remote-settings-oauth-tab")
        val pages = compose.onNodeWithTag("app-swipe-pager")
        ssh.assertIsSelected()
        oauth.assertIsNotSelected()
        assertTrue("SSH keys comes first", ssh.fetchSemanticsNode().boundsInRoot.left < oauth.fetchSemanticsNode().boundsInRoot.left)
        compose.onNodeWithText("SSH key content").assertIsDisplayed()
        oauth.performClick()
        oauth.assertIsSelected()
        compose.onNodeWithText("OAuth client content").assertIsDisplayed()
        pages.performTouchInput { swipeRight() }
        ssh.assertIsSelected()
        pages.performTouchInput { swipeLeft() }
        oauth.assertIsSelected()
    }
}
