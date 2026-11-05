package org.starfall.generative

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.home.shared.ui.QuickActionsSheetContent
import org.starfall.generative.shared.ui.theme.GenerativeTheme

/** Avoid redundant prose while preserving distinctions that change what an action does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en-w360dp-h800dp-xhdpi", application = LibraryTestApplication::class)
class UiCopyConcisenessTest {
    @get:Rule val compose = createComposeRule()

    @Test fun quickActionsShowClearLabelsWithoutRepetitiveSubtitles() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.setContent {
            GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
                QuickActionsSheetContent(
                    onPickImage = {},
                    onPickDocument = {},
                    onTakePhoto = {},
                    onCreateImage = {},
                    onCreateVideo = {},
                    onOpenFeatureTools = {},
                    onOpenMcpConnectors = {},
                    onDismiss = {},
                    onOpenSkills = {}
                )
            }
        }
        for (key in listOf(
            R.string.quick_action_skills_description,
            R.string.quick_action_instructions_description,
            R.string.quick_action_feature_tools_description,
            R.string.quick_action_mcp_connectors_description
        )) compose.onNodeWithText(context.getString(key)).assertDoesNotExist()

        for (key in listOf(
            R.string.common_skills,
            R.string.common_feature_tools,
            R.string.common_mcp_connectors,
            R.string.quick_action_image_description,
            R.string.quick_action_video_description
        )) compose.onNodeWithText(context.getString(key)).assertExists()
        compose.onNodeWithText(context.getString(R.string.common_instructions)).assertDoesNotExist()
    }
}
