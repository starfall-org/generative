package org.starfall.generative

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.starfall.generative.shared.model.remote.TerminalInputMode
import org.starfall.generative.shared.subfeatures.terminal.RemoteTerminalBridge
import org.starfall.generative.shared.subfeatures.terminal.RemoteTerminalView

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RemoteTerminalBackgroundTest {
    @Test
    fun uninitializedRendererDoesNotPaintBlackOverLightThemeBackground() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bridge = RemoteTerminalBridge(context, TerminalInputMode.REMOTE)
        val host = RemoteTerminalView(context, bridge)
        try {
            // Termux's own onDraw paints black before an emulator is attached.
            assertNull(host.terminalView.mEmulator)
            assertEquals(View.INVISIBLE, host.terminalView.visibility)
            host.layout(0, 0, 80, 80)
            host.setBackgroundColor(Color.WHITE)
            assertEquals(Color.WHITE, pixel(host))
            host.setBackgroundColor(Color.rgb(241, 245, 249))
            assertEquals(Color.rgb(241, 245, 249), pixel(host))
        } finally {
            bridge.close()
        }
    }

    private fun pixel(view: View): Int {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        return try {
            view.draw(Canvas(bitmap))
            bitmap.getPixel(40, 40)
        } finally {
            bitmap.recycle()
        }
    }
}
