package org.starfall.generative

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.shared.subfeatures.icons.logic.IconStore
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RemoteProviderIconTest {
    @Test fun downloadsResizesAndReusesRemoteIconWithoutAnotherRequest() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = IconStore(context)
        val server = MockWebServer()
        server.start()
        val bitmap = Bitmap.createBitmap(640, 320, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(bytes)))
        try {
            val url = server.url("/provider-icon.png").toString()
            val loaded = store.loadIcon(url)
            assertNotNull(loaded)
            assertTrue(maxOf(loaded!!.width, loaded.height) <= 256)
            assertNotNull(store.loadIcon(url))
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun failedIconIsNonfatalAndDoesNotRetryOnEveryRecomposition() = runBlocking {
        val store = IconStore(ApplicationProvider.getApplicationContext())
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(404))
        try {
            val url = server.url("/missing-icon.png").toString()
            assertNull(store.loadIcon(url))
            assertNull(store.loadIcon(url))
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
}
