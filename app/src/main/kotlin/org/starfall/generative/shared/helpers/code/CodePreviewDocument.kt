package org.starfall.generative.shared.helpers.code

import java.util.Base64
import org.starfall.generative.shared.helpers.code.codeLanguage

internal const val PREVIEW_ORIGIN = "https://preview.generative.invalid/"
internal val previewRuntimeAssets = setOf("runtime.js")
internal val previewLibraryUrls = listOf(
    "https://unpkg.com/react@18.3.1/umd/react.production.min.js",
    "https://unpkg.com/react-dom@18.3.1/umd/react-dom.production.min.js",
    "https://unpkg.com/@babel/standalone@7.26.9/babel.min.js"
)
internal fun canPreviewCode(language: String): Boolean = codeLanguage(language) in setOf("html", "jsx", "tsx", "javascript", "typescript")

internal fun codePreviewDocument(language: String, code: String): String {
    val lang = codeLanguage(language)
    require(canPreviewCode(lang))
    val head = """<meta name="viewport" content="width=device-width,initial-scale=1"><script src="${PREVIEW_ORIGIN}runtime/runtime.js"></script>"""
    if (lang == "html") {
        val tag = Regex("<head(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE).find(code)
        return if (tag != null) code.replaceRange(tag.range.last + 1, tag.range.last + 1, head)
            else "<!doctype html>$head$code"
    }
    // Base64 keeps arbitrary source (including </script>) out of the HTML parser.
    val encoded = Base64.getEncoder().encodeToString(code.toByteArray(Charsets.UTF_8))
    val libraries = previewLibraryUrls.joinToString("\n") { url -> "<script src=\"$url\"></script>" }
    return """<!doctype html><html><head>$head
        $libraries
        <style>body{margin:0;font-family:system-ui,sans-serif}#preview-console{white-space:pre-wrap;overflow-wrap:anywhere;padding:16px;font:13px monospace}</style>
        </head><body><div id="root"></div><pre id="preview-console"></pre>
        <script>window.runCodePreview("$encoded","$lang");</script></body></html>""".trimIndent()
}
