package org.starfall.generative

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.starfall.generative.features.home.chat.ui.AttachmentStrip

/** A missing private attachment must not crash an old conversation or remove its media frame. */
@RunWith(AndroidJUnit4::class)
class MissingChatAttachmentDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun deletedImageShowsSmallReferenceWarningInItsOriginalFrame() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val missing = File(context.cacheDir, "missing-chat-reference.png")
        missing.delete()
        compose.setContent {
            MaterialTheme {
                AttachmentStrip(listOf(missing.absolutePath), removable = false, inlinePreview = true)
            }
        }
        val warning = context.getString(R.string.attachment_reference_missing)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(warning).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(warning).assertExists()
    }
}
