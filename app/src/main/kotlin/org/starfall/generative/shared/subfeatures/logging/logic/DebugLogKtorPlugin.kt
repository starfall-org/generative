package org.starfall.generative.shared.subfeatures.logging.logic

import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.api.Send
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException

private const val TAG = "HTTP (Ktor)"
private val RequestLogId = AttributeKey<Long>("debug-request-log-id")

/** Records request and response summaries of a Ktor client; bodies are never read here. */
val DebugLogKtorPlugin = createClientPlugin("GenerativeDebugLog") {
    onRequest { request, _ ->
        val label = "${request.method.value} ${request.url.buildString()}"
        DebugLog.beginRequest(TAG, label, method = request.method.value,
            endpoint = request.url.buildString())?.let { request.attributes.put(RequestLogId, it) }
    }
    on(Send) { request ->
        try {
            proceed(request)
        } catch (error: CancellationException) {
            DebugLog.finishRequest(request.attributes.getOrNull(RequestLogId), TAG,
                "✗ ${request.method.value} ${request.url.buildString()} (cancelled)", false)
            throw error
        } catch (error: Exception) {
            DebugLog.finishRequest(request.attributes.getOrNull(RequestLogId), TAG,
                "✗ ${request.method.value} ${request.url.buildString()} (${error::class.java.simpleName}: ${error.message})",
                true, error = error)
            throw error
        }
    }
    onResponse { response ->
        val call = response.call.request
        val ms = response.responseTime.timestamp - response.requestTime.timestamp
        val ok = response.status.value < 400
        val label = "${call.method.value} ${DebugLogRedactor.redact(call.url.toString())}"
        DebugLog.finishRequest(call.attributes.getOrNull(RequestLogId), TAG,
            "← ${response.status.value} $label ($ms ms)", !ok, statusCode = response.status.value)
    }
}
