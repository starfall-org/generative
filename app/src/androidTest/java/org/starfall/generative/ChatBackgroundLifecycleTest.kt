package org.starfall.generative

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.appwiring.ChatSessionHandle

class ChatBackgroundLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val notifications get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }

    private fun waitFor(predicate: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 20_000
        while (!predicate() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        assertTrue("Expected service notification state", predicate())
    }

    private fun hasGenerationNotification() = notifications.activeNotifications.any {
        it.id == 4556 && it.notification.category == Notification.CATEGORY_SERVICE &&
            it.notification.flags and Notification.FLAG_ONGOING_EVENT != 0
    }

    private fun startRequest(activity: MainActivity): Job {
        val container = (activity.application as GenerativeApplication).container
        val chat = ViewModelProvider(activity, container.viewModelFactory)[ChatSessionHandle::class.java].chat
        return chat.viewModelScope.launch {
            container.chatSession.trackRequest(coroutineContext.job)
            awaitCancellation()
        }
    }

    @Test fun generationNotificationSurvivesBackgroundAndScreenOffAndStopsWhenIdle() {
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        shell("input keyevent 224")
        shell("wm dismiss-keyguard")
        var request: Job? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { request = startRequest(it) }
                shell("input keyevent 4")
                waitFor { scenario.state == Lifecycle.State.CREATED }
                waitFor { hasGenerationNotification() }
                request!!.cancel()
                waitFor { !hasGenerationNotification() }

                // Leaving an idle app must not create any service notification.
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.moveToState(Lifecycle.State.CREATED)
                instrumentation.waitForIdleSync()
                assertFalse(hasGenerationNotification())

                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.onActivity { request = startRequest(it) }
                waitFor { hasGenerationNotification() }
                shell("input keyevent 223")
                waitFor { scenario.state == Lifecycle.State.CREATED }
                assertTrue(hasGenerationNotification())
                scenario.moveToState(Lifecycle.State.DESTROYED)
                assertTrue(request!!.isActive)
                assertTrue(hasGenerationNotification())
                request!!.cancel()
                waitFor { !hasGenerationNotification() }
            } finally {
                request?.cancel()
                shell("input keyevent 224")
                shell("wm dismiss-keyguard")
            }
        }
    }
}
