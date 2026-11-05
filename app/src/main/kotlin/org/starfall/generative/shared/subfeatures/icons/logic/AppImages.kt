package org.starfall.generative.shared.subfeatures.icons.logic

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.toBitmap
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Scale
import coil3.svg.SvgDecoder
import coil3.size.Precision
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import coil3.video.VideoFrameDecoder
import kotlinx.coroutines.CancellationException
import java.io.File
import java.nio.ByteBuffer

/** All image consumers share Coil's request lifecycle and caches. */
internal object AppImages {
    fun createLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(VideoFrameDecoder.Factory())
            add(SvgDecoder.Factory())
        }
        .build()

    fun loader(context: Context) = SingletonImageLoader.get(context)

    fun source(reference: String): Any = if (Uri.parse(reference).scheme == null) File(reference) else reference

    suspend fun decode(context: Context, data: Any, size: Int): Bitmap? {
        val request = ImageRequest.Builder(context)
            .data(if (data is ByteArray) ByteBuffer.wrap(data) else data)
            .memoryCachePolicy(if (data is ByteArray) CachePolicy.DISABLED else CachePolicy.ENABLED)
            .size(size, size)
            .scale(Scale.FIT)
            .precision(Precision.INEXACT)
            .allowHardware(false)
            .bitmapConfig(Bitmap.Config.ARGB_8888)
            .build()
        return try {
            (loader(context).execute(request) as? SuccessResult)?.image?.toBitmap()
        } catch (cancelled: CancellationException) { throw cancelled }
    }
}
