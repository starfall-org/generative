package org.starfall.generative

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import org.starfall.generative.features.home.shared.logic.ChatBackgroundService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChatBackgroundServiceTest {
    @Test fun generationHasAnOngoingQuietServiceNotificationThatOpensChat() {
        val controller = Robolectric.buildService(ChatBackgroundService::class.java).create()
        try {
            val service = controller.get()
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(Intent(), 0, 1))
            val notification = shadowOf(service).lastForegroundNotification
            assertNotNull(notification)
            assertEquals(Notification.CATEGORY_SERVICE, notification.category)
            assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
            assertEquals(service.getString(R.string.notification_ai_thinking),
                notification.extras.getString(Notification.EXTRA_TITLE))
            assertFalse(notification.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
            assertEquals(MainActivity::class.java.name,
                shadowOf(notification.contentIntent).savedIntent.component!!.className)
            val manager = service.getSystemService(NotificationManager::class.java)
            assertEquals(NotificationManager.IMPORTANCE_LOW,
                manager.getNotificationChannel(notification.channelId).importance)
        } finally { controller.destroy() }
    }

    @Test fun generationHoldsCpuLockUntilServiceIsDestroyed() {
        val controller = Robolectric.buildService(ChatBackgroundService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(), 0, 1)
        val wakeLock = ShadowPowerManager.getLatestWakeLock()
        assertNotNull(wakeLock)
        assertTrue(wakeLock.isHeld)
        controller.destroy()
        assertFalse(wakeLock.isHeld)
    }
}
