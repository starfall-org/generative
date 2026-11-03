package org.starfall.generative

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.shared.storage.preferences.WordWrapMode
import org.starfall.generative.features.providers.shared.ui.*
import org.starfall.generative.shared.preview.*
import org.starfall.generative.shared.ui.code.codeWrapWidth
import org.starfall.generative.shared.code.CodeTokenKind
import org.starfall.generative.shared.code.codeTokens
import org.starfall.generative.shared.ui.code.highlightedCode

class CodeRenderingTest {
    @Test fun wrapModesUseViewportColumnAndMinimumLikeVsCode() {
        assertNull(codeWrapWidth(WordWrapMode.OFF, 300f, 800f))
        assertEquals(300f, codeWrapWidth(WordWrapMode.VIEWPORT, 300f, 800f)!!, 0f)
        assertEquals(800f, codeWrapWidth(WordWrapMode.COLUMN, 300f, 800f)!!, 0f)
        assertEquals(300f, codeWrapWidth(WordWrapMode.BOUNDED, 300f, 800f)!!, 0f)
        assertEquals(800f, codeWrapWidth(WordWrapMode.BOUNDED, 1000f, 800f)!!, 0f)
        assertEquals(WordWrapMode.OFF, WordWrapMode.fromValue("unexpected"))
        WordWrapMode.entries.forEach { assertEquals(it, WordWrapMode.fromValue(it.value)) }
    }

    @Test fun highlightingPreservesSourceAndDoesNotParseCommentsOrStringsAsKeywords() {
        val code = "const value = 42; // return 'string'\nconsole.log(\"class\");"
        val tokens = codeTokens("js", code)
        assertEquals(CodeTokenKind.KEYWORD, tokens.first().kind)
        assertTrue(tokens.any { it.kind == CodeTokenKind.NUMBER && code.substring(it.start, it.end) == "42" })
        assertTrue(tokens.any { it.kind == CodeTokenKind.COMMENT && code.substring(it.start, it.end) == "// return 'string'" })
        assertTrue(tokens.any { it.kind == CodeTokenKind.STRING && code.substring(it.start, it.end) == "\"class\"" })
        assertFalse(tokens.any { it.kind == CodeTokenKind.KEYWORD && code.substring(it.start, it.end) == "class" })
        for (dark in listOf(false, true)) {
            val highlighted = highlightedCode("js", code, dark)
            assertEquals(code, highlighted.text)
            assertTrue(highlighted.spanStyles.isNotEmpty())
        }
        assertTrue(codeTokens("unknown", code).isEmpty())
    }

    @Test fun markupPythonSqlAndMultilineCommentsAreHighlighted() {
        val html = "<div class=\"card\"><!-- <bad> --></div>"
        val tags = codeTokens("html", html)
        assertTrue(tags.any { it.kind == CodeTokenKind.TAG && html.substring(it.start, it.end) == "div" })
        assertTrue(tags.any { it.kind == CodeTokenKind.ATTRIBUTE && html.substring(it.start, it.end) == "class" })
        assertEquals(1, tags.count { it.kind == CodeTokenKind.COMMENT })
        assertTrue(codeTokens("python", "def greet():\n  # return True\n  return 'hello'").any { it.kind == CodeTokenKind.KEYWORD })
        assertTrue(codeTokens("sql", "SELECT * FROM models WHERE id = 2").any { it.kind == CodeTokenKind.KEYWORD })
        val comment = codeTokens("kotlin", "/* fun\nval */\nfun main() = Unit")
        assertEquals(CodeTokenKind.COMMENT, comment.first().kind)
        assertEquals(13, comment.first().end)
    }

    @Test fun previewSupportsAliasesAndEncodesSourceWithoutScriptInjection() {
        listOf("html", "htm", "react", "reactjs", "jsx", "tsx", "js", "javascript", "ts").forEach { assertTrue(canPreviewCode(it)) }
        listOf("python", "json", "css", "bash").forEach { assertFalse(canPreviewCode(it)) }
        val source = "export default () => <p>Tiếng Việt 中文 {'</script>'}</p>"
        val document = codePreviewDocument("react", source)
        assertFalse(document.contains(source))
        val encoded = Regex("runCodePreview\\(\"([^\"]+)\"").find(document)!!.groupValues[1]
        assertEquals(source, String(Base64.getDecoder().decode(encoded), Charsets.UTF_8))
        previewLibraryUrls.forEach { url -> assertTrue(document.contains("<script src=\"$url\"></script>")) }
        assertFalse(document.contains("${PREVIEW_ORIGIN}runtime/react.production.min.js"))
        assertFalse(document.contains("${PREVIEW_ORIGIN}runtime/babel.min.js"))
        assertEquals(setOf("runtime.js"), previewRuntimeAssets)
    }

    @Test fun htmlPreviewKeepsDocumentAndAddsRuntimeToHead() {
        val original = "<!DOCTYPE html><html><HEAD><title>Test</title></HEAD><body><p>Hello</p></body></html>"
        val document = codePreviewDocument("html", original)
        assertTrue(document.startsWith("<!DOCTYPE html><html><HEAD><meta"))
        assertTrue(document.contains("<title>Test</title>"))
        assertTrue(document.contains("<p>Hello</p>"))
        assertTrue(document.contains("runtime/runtime.js"))
        assertTrue(codePreviewDocument("html", "<b>fragment</b>").endsWith("<b>fragment</b>"))
    }
}
