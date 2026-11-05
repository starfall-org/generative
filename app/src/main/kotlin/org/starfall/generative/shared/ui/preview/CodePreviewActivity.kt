package org.starfall.generative.shared.ui.preview

import org.starfall.generative.shared.helpers.code.PREVIEW_ORIGIN
import org.starfall.generative.shared.helpers.code.previewRuntimeAssets
import org.starfall.generative.shared.helpers.code.codePreviewDocument

import org.starfall.generative.shared.ui.layout.edgeToEdgeTopAppBarColors
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import org.starfall.generative.R
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import org.starfall.generative.database.preferences.AppPreferences
import org.starfall.generative.database.preferences.AppPreferencesRepository
import org.starfall.generative.shared.ui.theme.GenerativeTheme
import org.starfall.generative.shared.ui.RuntimeAppLocale
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.layout.enableTransparentSystemBars
import org.starfall.generative.shared.ui.layout.LocalItemTransparency

/** Private Activity keeps generated scripts away from native app state and credentials. */
class CodePreviewActivity : ComponentActivity() {
    private var previewFile: File? = null

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableTransparentSystemBars()
        val name = intent.getStringExtra(EXTRA_DOCUMENT)
        previewFile = name?.takeIf { it.matches(Regex("[a-f0-9-]{36}\\.html")) }?.let { File(cacheDir, "code-preview/$it") }
        val document = previewFile?.let { runCatching { it.readText() }.getOrNull() }
        setContent {
            val preferencesFlow = remember { AppPreferencesRepository(applicationContext).appPreferencesFlow }
            val appearance by preferencesFlow.collectAsState(initial = AppPreferences())
            RuntimeAppLocale(appearance.useSystemLanguage, appearance.selectedLanguage) {
            GenerativeTheme(
                themeMode = appearance.themeMode,
                amoledMode = appearance.useAmoled,
                dynamicColor = appearance.useDynamicColor,
                colorSchemeName = appearance.colorSchemeName,
                customPalette = appearance.customPalette
            ) {
                CompositionLocalProvider(LocalItemTransparency provides appearance.itemTransparency) {
                    var webView by remember { mutableStateOf<WebView?>(null) }
                    DisposableEffect(Unit) { onDispose { webView?.stopLoading(); webView?.destroy(); webView = null } }
                    Scaffold(topBar = {
                        TopAppBar(
                            colors = edgeToEdgeTopAppBarColors(),
                title = { Text(stringResource(R.string.code_preview_title)) }, navigationIcon = {
                            IconButton(onClick = { finish() }) { SharedIcon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) }
                        }, actions = {
                            if (document != null) IconButton(onClick = {
                                webView?.loadDataWithBaseURL(PREVIEW_ORIGIN, document, "text/html", "UTF-8", null)
                            }) { SharedIcon(Icons.Outlined.Refresh, stringResource(R.string.code_preview_reload)) }
                        })
                    }) { padding ->
                        if (document == null) Text(stringResource(R.string.code_preview_unavailable), Modifier.padding(padding))
                        else AndroidView(modifier = Modifier.fillMaxSize().padding(padding), factory = { context ->
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
                                                return WebResourceResponse("application/javascript", "UTF-8", assets.open("code-preview/$filename"))
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
            }
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) previewFile?.delete()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_DOCUMENT = "preview_document"
        fun open(context: Context, language: String, code: String) {
            val directory = File(context.cacheDir, "code-preview").apply { mkdirs() }
            directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.delete() }
            val file = File(directory, "${UUID.randomUUID()}.html")
            file.writeText(codePreviewDocument(language, code))
            context.startActivity(Intent(context, CodePreviewActivity::class.java).putExtra(EXTRA_DOCUMENT, file.name))
        }
    }
}
