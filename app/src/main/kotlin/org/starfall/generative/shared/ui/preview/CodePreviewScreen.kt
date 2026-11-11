package org.starfall.generative.shared.ui.preview

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.starfall.generative.R
import org.starfall.generative.shared.helpers.code.PREVIEW_ORIGIN
import org.starfall.generative.shared.helpers.code.codePreviewDocument
import org.starfall.generative.shared.helpers.code.previewRuntimeAssets
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.screens.PreviewScreenTemplate
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

/** The navigation argument is a small private filename, never the potentially large source code. */
internal fun createCodePreviewFile(context: Context, language: String, code: String): File {
    val directory = File(context.cacheDir, "code-preview").apply { mkdirs() }
    directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }
        ?.forEach { it.delete() }
    return File(directory, "${UUID.randomUUID()}.html").apply {
        writeText(codePreviewDocument(language, code))
    }
}

internal fun codePreviewFile(context: Context, name: String): File? =
    name.takeIf { it.matches(Regex("[a-f0-9-]{36}\\.html")) }
        ?.let { File(context.cacheDir, "code-preview/$it") }

/** Generated code runs in a WebView with no native bridge or access to app files or content. */
@Composable
internal fun CodePreviewScreen(document: String?, onBack: () -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(Unit) {
        onDispose { webView?.stopLoading(); webView?.destroy(); webView = null }
    }
    PreviewScreenTemplate(
        title = stringResource(R.string.code_preview_title),
        onBack = onBack,
        modifier = Modifier.background(MaterialTheme.colorScheme.background),
        scrollable = false,
        horizontalPadding = 0.dp,
        topPadding = 0.dp,
        bottomPadding = 0.dp,
        actions = {
            if (document != null) IconButton(onClick = {
                webView?.loadDataWithBaseURL(PREVIEW_ORIGIN, document, "text/html", "UTF-8", null)
            }) { SharedIcon(Icons.Outlined.Refresh, stringResource(R.string.code_preview_reload)) }
        }
    ) {
        if (document == null) Text(stringResource(R.string.code_preview_unavailable))
        else AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                @Suppress("DEPRECATION")
                settings.allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                settings.allowUniversalAccessFromFileURLs = false
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        request.url.scheme !in setOf("https", "http")

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val uri = request.url
                        if (uri.host == "preview.generative.invalid") {
                            val filename = uri.path?.removePrefix("/runtime/")
                            if (uri.path?.startsWith("/runtime/") == true && filename in previewRuntimeAssets) {
                                return WebResourceResponse("application/javascript", "UTF-8", context.assets.open("code-preview/$filename"))
                            }
                            return WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                        }
                        if (uri.scheme in setOf("file", "content", "intent")) {
                            return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                        }
                        return null
                    }
                }
                webChromeClient = WebChromeClient()
                webView = this
                loadDataWithBaseURL(PREVIEW_ORIGIN, document, "text/html", "UTF-8", null)
            }
        })
    }
}
