package dev.ii.tools.update

/**
 * Lightweight Markdown parser for GitHub release notes (headings, bullets/numbered lists,
 * blockquotes, fenced code, paragraphs, and inline bold, italic, code and links).
 */
sealed interface MarkdownBlock {
    data class Heading(val level: Int, val spans: List<InlineSpan>) : MarkdownBlock
    data class Paragraph(val spans: List<InlineSpan>) : MarkdownBlock
    data class Bullet(val indent: Int, val marker: String?, val spans: List<InlineSpan>) : MarkdownBlock
    data class Quote(val spans: List<InlineSpan>) : MarkdownBlock
    data class CodeBlock(val code: String) : MarkdownBlock
}

data class InlineSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val linkUrl: String? = null,
)

object UpdateMarkdown {
    private val headingRegex = Regex("""^\s{0,3}(#{1,6})\s+(.+?)\s*#*\s*$""")
    private val unorderedRegex = Regex("""^(\s*)[-*+]\s+(.+)$""")
    private val orderedRegex = Regex("""^(\s*)(\d+[.)])\s+(.+)$""")
    private val hrRegex = Regex("""^\s{0,3}([-*_])(?:\s*\1){2,}\s*$""")
    /** A line that is only an image (a release's banner): the app shows no pictures. */
    private val imageRegex = Regex("""^!\[[^\]]*]\([^)]*\)$""")

    fun parse(markdown: String): List<MarkdownBlock> {
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = ArrayList<MarkdownBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                i++
                continue
            }

            if (trimmed.startsWith("```")) {
                val codeLines = ArrayList<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    codeLines += lines[i]
                    i++
                }
                if (i < lines.size) i++
                blocks += MarkdownBlock.CodeBlock(codeLines.joinToString("\n"))
                continue
            }

            if (hrRegex.matches(line) || imageRegex.matches(trimmed)) {
                i++
                continue
            }

            headingRegex.matchEntire(line)?.let { m ->
                blocks += MarkdownBlock.Heading(m.groupValues[1].length, parseInline(m.groupValues[2]))
                i++
                continue
            }

            unorderedRegex.matchEntire(line)?.let { m ->
                val indent = m.groupValues[1].length / 2
                val text = StringBuilder(m.groupValues[2].trim())
                i++
                while (i < lines.size && isListContinuation(lines[i])) {
                    text.append(' ').append(lines[i].trim())
                    i++
                }
                blocks += MarkdownBlock.Bullet(indent = indent, marker = null, spans = parseInline(text.toString()))
                continue
            }

            orderedRegex.matchEntire(line)?.let { m ->
                val indent = m.groupValues[1].length / 2
                val marker = m.groupValues[2]
                val text = StringBuilder(m.groupValues[3].trim())
                i++
                while (i < lines.size && isListContinuation(lines[i])) {
                    text.append(' ').append(lines[i].trim())
                    i++
                }
                blocks += MarkdownBlock.Bullet(indent = indent, marker = marker, spans = parseInline(text.toString()))
                continue
            }

            if (trimmed.startsWith(">")) {
                val quoteLines = ArrayList<String>()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    quoteLines += lines[i].trimStart().removePrefix(">").trimStart()
                    i++
                }
                blocks += MarkdownBlock.Quote(parseInline(quoteLines.joinToString(" ")))
                continue
            }

            val para = ArrayList<String>()
            while (i < lines.size && !isBlockBoundary(lines[i])) {
                para += lines[i].trim()
                i++
            }
            if (para.isNotEmpty()) {
                blocks += MarkdownBlock.Paragraph(parseInline(para.joinToString(" ")))
            } else {
                i++
            }
        }
        return blocks
    }

    private fun isBlockBoundary(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return true
        if (trimmed.startsWith("```") || trimmed.startsWith(">")) return true
        if (hrRegex.matches(line)) return true
        if (headingRegex.matches(line)) return true
        if (unorderedRegex.matches(line) || orderedRegex.matches(line)) return true
        return false
    }

    private fun isListContinuation(line: String): Boolean {
        if (line.isBlank()) return false
        return !isBlockBoundary(line)
    }

    fun parseInline(text: String): List<InlineSpan> {
        val out = ArrayList<InlineSpan>()
        parseRange(text, 0, text.length, bold = false, italic = false, linkUrl = null, out = out)
        return mergeAdjacent(out)
    }

    private fun parseRange(
        src: String,
        start: Int,
        end: Int,
        bold: Boolean,
        italic: Boolean,
        linkUrl: String?,
        out: MutableList<InlineSpan>,
    ) {
        val plain = StringBuilder()
        fun flushPlain() {
            if (plain.isNotEmpty()) {
                out += InlineSpan(plain.toString(), bold = bold, italic = italic, linkUrl = linkUrl)
                plain.setLength(0)
            }
        }

        var i = start
        while (i < end) {
            val c = src[i]
            if (c == '\\' && i + 1 < end) {
                plain.append(src[i + 1])
                i += 2
                continue
            }

            if (c == '`') {
                val close = src.indexOf('`', i + 1)
                if (close in (i + 1) until end) {
                    flushPlain()
                    out += InlineSpan(src.substring(i + 1, close), bold = bold, italic = italic, code = true, linkUrl = linkUrl)
                    i = close + 1
                    continue
                }
            }

            if (c == '[') {
                val parsed = parseLink(src, i, end)
                if (parsed != null) {
                    flushPlain()
                    parseRange(src, parsed.labelStart, parsed.labelEnd, bold, italic, parsed.url, out)
                    i = parsed.nextIndex
                    continue
                }
            }

            if (c == '*' || c == '_') {
                val token = matchDelimiter(src, i, end, c)
                if (token != null) {
                    flushPlain()
                    val nextBold = bold || token.len >= 2
                    val nextItalic = italic || token.len == 1 || token.len == 3
                    parseRange(src, i + token.len, token.closeIndex, nextBold, nextItalic, linkUrl, out)
                    i = token.closeIndex + token.len
                    continue
                }
            }

            plain.append(c)
            i++
        }
        flushPlain()
    }

    private data class DelimMatch(val len: Int, val closeIndex: Int)

    private fun matchDelimiter(src: String, pos: Int, end: Int, ch: Char): DelimMatch? {
        var count = 0
        while (pos + count < end && src[pos + count] == ch && count < 3) count++
        if (count == 0) return null
        // Underscores inside words (e.g. `ii_link`) are literal characters, not emphasis.
        if (ch == '_' && pos > 0 && isWordChar(src[pos - 1])) return null

        for (len in count downTo 1) {
            if (pos + len >= end || src[pos + len].isWhitespace()) continue
            val close = findClosingDelimiter(src, pos + len, end, ch, len)
            if (close >= 0) return DelimMatch(len, close)
        }
        return null
    }

    private fun findClosingDelimiter(src: String, from: Int, end: Int, ch: Char, len: Int): Int {
        var i = from
        while (i <= end - len) {
            if (src[i] == '\\') {
                i += 2
                continue
            }
            if (src[i] == '`') {
                val close = src.indexOf('`', i + 1)
                if (close in (i + 1) until end) {
                    i = close + 1
                    continue
                }
            }
            var run = 0
            while (i + run < end && src[i + run] == ch) run++
            if (run >= len && i > from && !src[i - 1].isWhitespace()) {
                val after = i + len
                if (ch != '_' || after >= end || !isWordChar(src[after])) {
                    return i
                }
            }
            i += if (run > 0) run else 1
        }
        return -1
    }

    private data class LinkMatch(val labelStart: Int, val labelEnd: Int, val url: String, val nextIndex: Int)

    private fun parseLink(src: String, openBracket: Int, end: Int): LinkMatch? {
        var i = openBracket + 1
        var depth = 1
        while (i < end && depth > 0) {
            when (src[i]) {
                '\\' -> i += 2
                '`' -> {
                    val close = src.indexOf('`', i + 1)
                    i = if (close in (i + 1) until end) close + 1 else i + 1
                }
                '[' -> { depth++; i++ }
                ']' -> { depth--; if (depth > 0) i++ }
                else -> i++
            }
        }
        if (depth != 0 || i + 1 >= end || src[i + 1] != '(') return null
        val closeBracket = i
        var j = closeBracket + 2
        var parens = 1
        while (j < end && parens > 0) {
            when (src[j]) {
                '\\' -> j += 2
                '(' -> { parens++; j++ }
                ')' -> { parens--; if (parens > 0) j++ }
                else -> j++
            }
        }
        if (parens != 0) return null
        val url = src.substring(closeBracket + 2, j).trim()
        val safeUrl = url.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return null
        return LinkMatch(openBracket + 1, closeBracket, safeUrl, j + 1)
    }

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit()

    private fun mergeAdjacent(spans: List<InlineSpan>): List<InlineSpan> {
        if (spans.size <= 1) return spans
        val merged = ArrayList<InlineSpan>(spans.size)
        for (s in spans) {
            if (s.text.isEmpty()) continue
            val prev = merged.lastOrNull()
            if (prev != null && !prev.code && !s.code &&
                prev.bold == s.bold && prev.italic == s.italic && prev.linkUrl == s.linkUrl
            ) {
                merged[merged.lastIndex] = prev.copy(text = prev.text + s.text)
            } else {
                merged += s
            }
        }
        return merged
    }
}
