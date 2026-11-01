package org.starfall.generative

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.starfall.generative.features.providers.list.ui.ProvidersDemoScreen

/** The developer demo mounts the actual ProvidersScreen with a long, isolated folder. */
@RunWith(AndroidJUnit4::class)
class ProvidersDemoDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun longFolderCanScrollDeepIntoRealProvidersLayout() {
        compose.setContent {
            MaterialTheme { ProvidersDemoScreen(onBack = {}) }
        }
        compose.onNodeWithTag("provider_group_demo-long", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("provider_folder_grid_demo-long", useUnmergedTree = true)
            .performScrollToIndex(44)
        compose.waitForIdle()
        compose.onNodeWithTag("provider_folder_provider_demo-long-44", useUnmergedTree = true)
            .assertExists()
    }
}
