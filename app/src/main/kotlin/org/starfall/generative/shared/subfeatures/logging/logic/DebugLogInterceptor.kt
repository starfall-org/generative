package org.starfall.generative.shared.subfeatures.logging.logic

import okhttp3.Interceptor
import okhttp3.Response
import org.starfall.generative.shared.model.logging.DebugLogCategory.NETWORK
import java.io.IOException

/** Network diagnostics use native Logcat priorities and never read request/response bodies. */
class DebugLogInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!DebugLog.isEnabled(NETWORK)) return chain.proceed(request)
        val label = "${request.method} ${DebugLogRedactor.redact(request.url.toString())}"
        val id = DebugLog.beginRequest("HTTP", label,
            method = request.method, endpoint = request.url.toString())
        val startNs = System.nanoTime()
        val response = try {
            chain.proceed(request)
        } catch (error: IOException) {
            DebugLog.finishRequest(id, "HTTP", "✗ $label (${elapsedMs(startNs)} ms)",
                true, error = error)
            throw error
        }
        DebugLog.finishRequest(id, "HTTP",
            "← ${response.code} $label (${elapsedMs(startNs)} ms)",
            failed = response.code >= 400, statusCode = response.code)
        return response
    }

    private fun elapsedMs(startNs: Long) = (System.nanoTime() - startNs) / 1_000_000
}
