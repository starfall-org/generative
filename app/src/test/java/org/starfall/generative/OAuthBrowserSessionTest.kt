package org.starfall.generative

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.starfall.generative.shared.auth.OAuthBrowser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class OAuthBrowserSessionTest {
    @Test fun completingNestedAuthorizationDoesNotStartAnIntermediateActivity() = exerciseSession(cancel = false)

    @Test fun cancellingNestedAuthorizationDoesNotStartAnIntermediateActivity() = exerciseSession(cancel = true)

    @Test fun allOAuthProvidersUseExternalBrowserWithoutActivityBackStack() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val application = shadowOf(app)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val opened = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val request = scope.async {
            OAuthBrowser.withSession {
                OAuthBrowser.open(app, "https://app.devin.ai/auth/cli/continue?state=test")
                opened.complete(Unit)
                release.await()
            }
        }
        try {
            awaitMain { opened.isCompleted }
            val intent = application.nextStartedActivity
            assertBrowserIntent(intent, "app.devin.ai")
            assertNull(application.nextStartedActivity)
            assertFalse(request.isCompleted)
            release.complete(Unit)
            awaitMain { request.isCompleted }
            assertFalse(request.isCancelled)
            // Completion doesn't relaunch the app or produce an empty Activity.
            assertNull(application.nextStartedActivity)
        } finally { scope.cancel(); shadowOf(Looper.getMainLooper()).idle() }
    }

    @Test fun externalBrowserRejectsUntrustedOrNonHttpsUrls() {
        for (url in listOf("javascript:alert(1)", "http://app.devin.ai/auth/cli/continue", "https:///missing")) {
            try {
                OAuthBrowser.externalIntent(url)
                fail("Must reject invalid external OAuth URL: $url")
            } catch (_: IllegalArgumentException) { }
        }
    }

    private fun exerciseSession(cancel: Boolean) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val application = shadowOf(app)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val opened = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val request = scope.async {
            OAuthBrowser.withSession {
                // Nested repository/adapter scopes share one session without deadlocking.
                OAuthBrowser.withSession {
                    OAuthBrowser.open(app, "https://accounts.google.com/o/oauth2/v2/auth")
                    opened.complete(Unit)
                    release.await()
                }
            }
        }
        try {
            awaitMain { opened.isCompleted }
            assertBrowserIntent(application.nextStartedActivity, "accounts.google.com")
            assertNull(application.nextStartedActivity)
            if (cancel) request.cancel() else release.complete(Unit)
            awaitMain { request.isCompleted }
            assertEquals(cancel, request.isCancelled)
            // No intermediate Activity needs to be closed or resumed after cancellation.
            assertNull(application.nextStartedActivity)
        } finally { scope.cancel(); shadowOf(Looper.getMainLooper()).idle() }
    }

    private fun assertBrowserIntent(intent: Intent, host: String) {
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https", intent.data?.scheme)
        assertEquals(host, intent.data?.host)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertNull(intent.component)
    }

    private fun awaitMain(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Authorization did not complete", condition())
    }
}
