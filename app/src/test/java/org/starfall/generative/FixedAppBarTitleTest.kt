package org.starfall.generative

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.shared.ui.theme.GenerativeTheme
import org.starfall.generative.shared.ui.layout.FixedAppBarTitle

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en-w360dp-h800dp-xhdpi", application = LibraryTestApplication::class)
class FixedAppBarTitleTest {
    @get:Rule val compose = createComposeRule()

    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun twoLineTitleDoesNotIncreaseTopAppBarHeight() {
        compose.setContent {
            GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
                Column {
                    TopAppBar(
                        modifier = Modifier.testTag("short-appbar"),
                        title = { FixedAppBarTitle("Short", Modifier.testTag("short-title")) }
                    )
                    TopAppBar(
                        modifier = Modifier.testTag("long-appbar"),
                        title = { FixedAppBarTitle("Language model providers", Modifier.testTag("long-title")) }
                    )
                    FixedAppBarTitle("Speech synthesis services", Modifier.width(110.dp).testTag("narrow-title"))
                }
            }
        }
        compose.onNodeWithTag("short-title").assertHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("long-title").assertHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("narrow-title").assertHeightIsEqualTo(48.dp)
        compose.onNodeWithText("Language model providers").assertExists()
        compose.onNodeWithText("Speech synthesis services").assertExists()
        val shortHeight = compose.onNodeWithTag("short-appbar").fetchSemanticsNode().boundsInRoot.height
        val longHeight = compose.onNodeWithTag("long-appbar").fetchSemanticsNode().boundsInRoot.height
        assertEquals(shortHeight, longHeight, 0.5f)
    }
}
