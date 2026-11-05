package org.starfall.generative

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.starfall.generative.database.room.AppDatabase
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.database.repositories.ModelRepository
import org.starfall.generative.integrations.modelapis.AiService
import java.util.UUID

class ProviderLinkUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun coldAndWarmLinksSaveProvidersOpenEditorAndDoNotRepeatOnRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = ModelRepository(AppDatabase.getInstance(context), AiService(context))
        val firstName = "Link test ${UUID.randomUUID()}"
        val secondName = "Link test ${UUID.randomUUID()}"
        fun intent(name: String) = Intent(Intent.ACTION_VIEW, Uri.Builder()
            .scheme("mg").authority("provider")
            .appendQueryParameter("name", name)
            .appendQueryParameter("url", "https://example.com/v1")
            .appendQueryParameter("key", "test-link-key").build(), context, MainActivity::class.java)
        fun imported() = runBlocking { repository.allProviders.first().filter { it.name in listOf(firstName, secondName) } }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun resumedActivity(): MainActivity? {
            var activity: MainActivity? = null
            instrumentation.runOnMainSync {
                activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().firstOrNull()
            }
            return activity
        }
        fun recreate() {
            val before = requireNotNull(resumedActivity())
            instrumentation.runOnMainSync { before.recreate() }
            compose.waitUntil(10_000) { resumedActivity()?.let { it !== before } == true }
        }
        try {
            // The app consumes Intent.data; ActivityScenario's URI-matching monitor cannot track it.
            context.startActivity(intent(firstName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            compose.waitUntil(10_000) { resumedActivity() != null }
                compose.waitUntil(10_000) { imported().size == 1 }
                compose.onNode(hasSetTextAction() and hasText(firstName)).assertExists()
                val saved = imported().single()
                assertEquals(ModelApiType.OPENAI, saved.type)
                assertEquals(AuthMethod.PLATFORM_DEFAULT, saved.auth.method)
                assertEquals("test-link-key", saved.auth.token)
                recreate()
                compose.waitForIdle()
                assertEquals(1, imported().size)
                val activity = requireNotNull(resumedActivity())
                instrumentation.runOnMainSync { activity.startActivity(intent(secondName)) }
                compose.waitUntil(10_000) { imported().size == 2 }
                compose.onNode(hasSetTextAction() and hasText(secondName)).assertExists()
                recreate()
                compose.waitForIdle()
                assertEquals(2, imported().size)
        } finally {
            val activity = resumedActivity()
            instrumentation.runOnMainSync { activity?.finish() }
            runBlocking { imported().forEach { repository.deleteProvider(it.id) } }
        }
    }
}
