package org.starfall.generative.shared.code

internal enum class CodeTokenKind { COMMENT, STRING, NUMBER, KEYWORD, TYPE, FUNCTION, TAG, ATTRIBUTE, OPERATOR }
internal data class CodeToken(val start: Int, val end: Int, val kind: CodeTokenKind)

internal fun codeLanguage(language: String): String = language.trim().lowercase().substringBefore(' ').let {
    when (it) {
        "js", "mjs", "cjs", "node", "nodejs" -> "javascript"
        "ts" -> "typescript"
        "react", "reactjs", "javascriptreact", "react/jsx", "babel" -> "jsx"
        "typescriptreact" -> "tsx"
        "htm" -> "html"
        "py" -> "python"
        "kt", "kts" -> "kotlin"
        "sh", "zsh", "shell", "shellscript" -> "bash"
        "yml" -> "yaml"
        "c++", "cc", "hpp" -> "cpp"
        "c#", "cs" -> "csharp"
        "rs" -> "rust"
        "golang" -> "go"
        "rb" -> "ruby"
        "md" -> "markdown"
        else -> it
    }
}

private val commonKeywords = "break case catch class const continue default do else enum export extends false finally for from function if implements import in instanceof interface let new null private protected public return static super switch this throw true try typeof var void while with yield async await as null undefined".split(' ').toSet()
private val extraKeywords = mapOf(
    "python" to "and assert def del elif except False global is lambda None nonlocal not or pass raise True with yield self match",
    "kotlin" to "fun val var when object companion override suspend internal open data sealed inline reified constructor init is out by package null",
    "java" to "package abstract final synchronized throws transient volatile boolean byte char double float int long short instanceof native",
    "cpp" to "include define namespace template typename using virtual bool char double float int long short unsigned signed auto struct typedef union constexpr nullptr noexcept size_t",
    "c" to "include define struct typedef union unsigned signed auto register sizeof char double float int long short NULL",
    "csharp" to "using namespace delegate event string bool int decimal double float struct get set value override virtual readonly partial lock foreach record",
    "rust" to "fn pub impl trait use mod crate self Self mut ref match move struct type where dyn unsafe loop Some None Ok Err",
    "go" to "func package defer go chan map range struct type select fallthrough nil make append len cap",
    "swift" to "func guard protocol extension struct weak unowned nil optional let var override mutating associatedtype",
    "ruby" to "def end module unless until then elsif begin rescue ensure nil self puts require attr_accessor",
    "bash" to "then fi elif esac done echo exit source export local read set unset cd pwd sudo printf",
    "sql" to "select from where insert into update delete create table alter drop join left right inner outer on group by order asc desc limit offset having union all distinct values set as null true false count sum avg and or not like in is primary key references",
    "lua" to "local function end then elseif repeat until nil and or not ipairs pairs",
    "r" to "if else repeat while function for in next break TRUE FALSE NULL NA Inf NaN library",
    "yaml" to "true false null yes no on off",
    "json" to "true false null",
    "typescript" to "type keyof readonly declare namespace abstract never any unknown number string boolean infer satisfies",
    "tsx" to "type keyof readonly declare namespace abstract never any unknown number string boolean infer satisfies"
).mapValues { (_, words) -> words.split(' ').toSet() }
private val supported = setOf("javascript", "jsx", "typescript", "tsx", "python", "kotlin", "java", "c", "cpp", "csharp", "rust", "go", "swift", "ruby", "bash", "sql", "lua", "r", "yaml", "json", "css", "scss", "html", "xml", "svg", "php", "ini", "toml", "dockerfile")
private val hashComments = setOf("python", "bash", "ruby", "r", "yaml", "toml", "dockerfile", "php")
private val slashComments = supported - setOf("python", "bash", "ruby", "r", "yaml", "toml", "dockerfile", "json", "html", "xml", "svg", "sql", "lua", "ini")
private val keywordSets = supported.associateWith { lang ->
    (if (lang in setOf("json", "yaml", "sql", "css", "scss", "html", "xml", "svg", "ini", "toml")) emptySet() else commonKeywords) + extraKeywords[lang].orEmpty()
}
private val lexers = supported.associateWith { lang ->
    val comment = when {
        lang in setOf("html", "xml", "svg") -> "<!--(?s:.*?)(?:-->|$)"
        lang in hashComments -> "#[^\\r\\n]*" + if (lang == "php") "|//[^\\r\\n]*|/\\*(?s:.*?)(?:\\*/|$)" else ""
        lang in slashComments -> "//[^\\r\\n]*|/\\*(?s:.*?)(?:\\*/|$)"
        lang in setOf("sql", "lua") -> "--[^\\r\\n]*|/\\*(?s:.*?)(?:\\*/|$)"
        lang == "ini" -> "[;#][^\\r\\n]*"
        else -> "(?!)"
    }
    val strings = "\"\"\"(?s:.*?)(?:\"\"\"|$)|'''(?s:.*?)(?:'''|$)|\"(?:\\\\.|[^\"\\\\\\r\\n])*(?:\"|$)|'(?:\\\\.|[^'\\\\\\r\\n])*(?:'|$)|`(?:\\\\.|[^`\\\\])*(?:`|$)"
    Regex("($comment)|($strings)|(\\b(?:0[xX][\\da-fA-F_]+|0[bB][01_]+|\\d[\\d_]*(?:\\.\\d[\\d_]*)?(?:[eE][+-]?\\d+)?)[a-zA-Z]*\\b)|([a-zA-Z_$][\\w$-]*)|([+*/%=!<>:&|?~^-])")
}

/** Token ranges refer to the original source; highlighting never rewrites copied code. */
internal fun codeTokens(language: String, source: String): List<CodeToken> {
    val lang = codeLanguage(language)
    val lexer = lexers[lang] ?: return emptyList()
    val markup = lang in setOf("html", "xml", "svg", "jsx", "tsx")
    val tagRanges = if (markup) Regex("</?[A-Za-z][^<>]*>").findAll(source).map { it.range }.toList() else emptyList()
    var tagIndex = 0
    return buildList {
        for (match in lexer.findAll(source)) {
            val start = match.range.first
            while (tagIndex < tagRanges.size && tagRanges[tagIndex].last < start) tagIndex++
            val tag = tagRanges.getOrNull(tagIndex)?.takeIf { start in it }
            val word = match.value
            val before = source.substring((start - 2).coerceAtLeast(0), start)
            val next = source.indexOfFirstNonWhitespace(match.range.last + 1)
            val kind = when {
                match.groups[1] != null -> CodeTokenKind.COMMENT
                match.groups[2] != null -> if (lang == "json" && next != null && source[next] == ':') CodeTokenKind.ATTRIBUTE else CodeTokenKind.STRING
                match.groups[3] != null -> CodeTokenKind.NUMBER
                match.groups[4] != null && tag != null -> if (before.endsWith('<') || before.endsWith("</")) CodeTokenKind.TAG else CodeTokenKind.ATTRIBUTE
                match.groups[4] != null && (if (lang == "sql") word.lowercase() else word) in keywordSets.getValue(lang) -> CodeTokenKind.KEYWORD
                match.groups[4] != null && next != null && source[next] == '(' -> CodeTokenKind.FUNCTION
                match.groups[4] != null && (lang == "json" || lang == "yaml" || lang == "css" || lang == "scss") && next != null && source[next] == ':' -> CodeTokenKind.ATTRIBUTE
                match.groups[4] != null && word.first().isUpperCase() -> CodeTokenKind.TYPE
                match.groups[5] != null -> CodeTokenKind.OPERATOR
                else -> null
            }
            if (kind != null) add(CodeToken(start, match.range.last + 1, kind))
        }
    }
}

private fun String.indexOfFirstNonWhitespace(from: Int): Int? {
    var index = from
    while (index < length && this[index].isWhitespace()) index++
    return index.takeIf { it < length }
}
