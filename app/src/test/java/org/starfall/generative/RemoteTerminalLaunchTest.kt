package org.starfall.generative

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.shared.subfeatures.terminal.RemoteTerminalLaunch

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
class RemoteTerminalLaunchTest {
    @Test
    fun notificationLaunchesAreDistinctPerMachineEvenWithTheSameRequestCode() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = requireNotNull(RemoteTerminalLaunch.createIntent(context, "machine one/ssh"))
        val second = requireNotNull(RemoteTerminalLaunch.createIntent(context, "machine two/ssh"))
        val firstPending = PendingIntent.getActivity(context, 0, first, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val secondPending = PendingIntent.getActivity(context, 0, second, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        assertNotEquals(firstPending, secondPending)
        assertEquals(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP, first.flags)
        assertEquals(MainActivity::class.java.name, first.component?.className)
        assertEquals("machine one/ssh", RemoteTerminalLaunch.consumeMachineId(first))
        assertEquals("machine two/ssh", RemoteTerminalLaunch.consumeMachineId(second))
        assertNull(RemoteTerminalLaunch.consumeMachineId(first))
    }

    @Test
    fun ordinaryAppLaunchDoesNotRequestATerminal() {
        assertNull(RemoteTerminalLaunch.consumeMachineId(Intent(Intent.ACTION_MAIN)))
        assertNull(RemoteTerminalLaunch.consumeMachineId(null))
    }
}
