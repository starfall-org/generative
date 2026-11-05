package org.starfall.generative

import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.starfall.generative.integrations.speech.SpeechPlaybackNotification

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SpeechPlaybackNotificationTest {
    @Test fun playbackNotificationHasStopActionAndIsRemovedWhenStopped() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = SpeechPlaybackNotification(context)
        var stopped = false
        try {
            notification.show("ElevenLabs") { stopped = true; notification.hide() }
            val posted = manager.activeNotifications.single { it.id == SpeechPlaybackNotification.NOTIFICATION_ID }.notification
            assertEquals("Reading aloud", posted.extras.getCharSequence("android.title").toString())
            assertEquals("ElevenLabs", posted.extras.getCharSequence("android.text").toString())
            assertEquals("Stop", posted.actions.single().title.toString())
            posted.actions.single().actionIntent.send()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(stopped)
            assertFalse(manager.activeNotifications.any { it.id == SpeechPlaybackNotification.NOTIFICATION_ID })
        } finally { notification.hide() }
    }

    @Test fun oldStopActionCannotStopANewPlaybackSession() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = SpeechPlaybackNotification(context)
        var stopped = false
        try {
            notification.show("Old voice") {}
            val oldStop = manager.activeNotifications.single { it.id == SpeechPlaybackNotification.NOTIFICATION_ID }.notification.actions.single().actionIntent
            notification.hide()
            notification.show("New voice") { stopped = true }
            oldStop.send()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(stopped)
            assertTrue(manager.activeNotifications.any { it.id == SpeechPlaybackNotification.NOTIFICATION_ID })
        } finally { notification.hide() }
    }
}
