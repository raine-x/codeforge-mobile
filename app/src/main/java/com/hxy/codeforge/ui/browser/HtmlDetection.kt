package com.hxy.codeforge.ui.browser

import com.hxy.codeforge.render.RenderBlock

/**
 * 原文级完整 HTML 文档识别（在送入渲染引擎之前判定，围栏代码块走 `code_block` 通路不在此列）。
 * CommonMark 会在空行处切断 HTML 块并把缩进 ≥4 空格的行变成 code_block，
 * 因此须在解析分块前抽出完整文档（含 docType / html 闭合并满足最小长度）。
 */
internal fun looksLikeWholeHtmlDocument(raw: String): Boolean {
    val t = raw.trim()
    // 围栏形态先排除：整段被 ``` 包着的是代码，不是文档。
    if (t.startsWith("```") || t.startsWith("~~~")) return false
    // 模型实际输出常是「解说 + 文档 + 解说」：前后两边有中文说明。
    // 严格要求整条消息以 `<` 开头、以 `>` 收尾会直接判定失败，退回引擎碎片化。
    val doc = extractHtmlDocument(t) ?: return false
    if (doc.length < 40) return false
    val lower = doc.lowercase()
    // 完整文档的闭合标记：只看开头没有意义 ——
    // 文档前半段（doctype + <head>）还没写完，不能当整页。
    val closed = lower.contains("</html>") || lower.contains("</body>")
    if (!closed) return false
    return lower.startsWith("<!doctype html") || lower.contains("<html")
}

/**
 * 从「解说 + 文档 + 解说」中抽取内层 HTML 文档。
 *
 * 找不到文档头（`<!DOCTYPE html` 或 `<html`）返回 null；
 * 找不到闭合时，取到文本末尾当作文档尾，由调用方按「未闭合」拒绝，
 * 避免半截文档提前转成网页。
 */
internal fun extractHtmlDocument(raw: String): String? {
    val (start, end) = findBareHtmlDocumentBounds(raw) ?: return null
    return raw.substring(start, end)
}

/** 在跳过代码围栏与行内反引号的前提下定位裸 HTML 文档起止区间。 */
private fun findBareHtmlDocumentBounds(raw: String): Pair<Int, Int>? {
    val lower = raw.lowercase()
    if (!lower.contains("<!doctype html") && !lower.contains("<html")) return null
    val codeRanges = findFencedAndInlineCodeRanges(raw)
    fun inCode(pos: Int): Boolean = codeRanges.any { pos in it.first until it.second }

    fun indexOfOutsideCode(needle: String, fromIndex: Int = 0): Int {
        var pos = fromIndex
        while (pos < lower.length) {
            val idx = lower.indexOf(needle, pos)
            if (idx < 0) return -1
            if (!inCode(idx)) return idx
            pos = idx + needle.length
        }
        return -1
    }

    fun lastIndexOfOutsideCode(needle: String, startFrom: Int): Int {
        var pos = startFrom
        var last = -1
        while (pos < lower.length) {
            val idx = lower.indexOf(needle, pos)
            if (idx < 0) break
            if (!inCode(idx)) last = idx
            pos = idx + needle.length
        }
        return last
    }

    val doctype = indexOfOutsideCode("<!doctype html")
    val htmlTag = indexOfOutsideCode("<html")
    val start = listOfNotNull(
        doctype.takeIf { it >= 0 },
        htmlTag.takeIf { it >= 0 },
    ).minOrNull() ?: return null

    val htmlEndIdx = lastIndexOfOutsideCode("</html>", start)
    val bodyEndIdx = lastIndexOfOutsideCode("</body>", start)
    val end = when {
        htmlEndIdx >= 0 -> htmlEndIdx + "</html>".length
        bodyEndIdx >= 0 -> bodyEndIdx + "</body>".length
        else -> raw.length
    }
    return if (end > start) start to end else null
}

/**
 * 是否「看起来像一整份 HTML 文档」（放宽版，每条消息都认）。
 *
 * 与 [looksLikeWholeHtmlDocument] 的区别：本函数允许「解说 + 文档 + 解说」，
 * 直接抽取内层文档。流式未闭合时也认得出来，适合提前占位显示「正在生成」。
 */
internal fun looksLikeHtmlDocumentLoose(raw: String): Boolean {
    val t = raw.trim()
    if (t.startsWith("```") || t.startsWith("~~~")) return false
    return extractHtmlDocument(t) != null
}

/** [splitProseAroundHtml] 的切分结果：解说文 / 内层文档 / 文档后解说。 */
data class HtmlSplit(val before: String, val html: String?, val after: String)

/**
 * 把「解说 + 文档 + 解说」切成三段，渲染时只把文档段挂 WebView，解说走正文。
 *
 * 找不到文档头时 `html` 为 null，`before` 即原文。
 * 未闭合（流式中）时文档段取到文本末尾，`after` 为空。
 */
internal fun splitProseAroundHtml(raw: String): HtmlSplit {
    val (start, end) = findBareHtmlDocumentBounds(raw) ?: return HtmlSplit(raw, null, "")
    return HtmlSplit(
        before = raw.substring(0, start).trim(),
        html = raw.substring(start, end),
        after = raw.substring(end).trim(),
    )
}

/**
 * 块级 HTML 文档保守判定（渲染引擎无原生 Html 块类型，裸 HTML 会落为普通 paragraph）。
 * 要求整段以 `<` 开头、以 `>` 结尾且包含闭合文档标记或富媒体脚本标签。
 */
internal fun looksLikeHtmlDocument(raw: String): Boolean {
    val t = raw.trim()
    if (t.length < 40 || !t.startsWith("<") || !t.endsWith(">")) return false
    val lower = t.lowercase()
    // 需含闭合标记，避免半截 docType + <head> 被误判为完整文档而截断后续 <body>
    val closed = lower.contains("</html>") || lower.contains("</body>")
    if (closed && (lower.contains("<!doctype html") || lower.contains("<html"))) return true
    // 整块富 HTML（不含 svg，svg 走独立渲染分支）
    val rich = lower.contains("<style") || lower.contains("<script") || lower.contains("<canvas")
    return rich && t.length >= 120
}

/** 合并被空行切断的连续裸 HTML paragraph 为完整文档块；未命中整文档特征则原样保留。 */
internal fun mergeHtmlBlocks(blocks: List<RenderBlock>): List<RenderBlock> {
    if (blocks.isEmpty()) return blocks
    val out = ArrayList<RenderBlock>(blocks.size)
    var i = 0
    while (i < blocks.size) {
        val b = blocks[i]
        if (b.type == "paragraph" && isBareHtmlParagraph(b)) {
            // 先把后面连续的裸 HTML 段全并进来，再判断整组像不像一份文档。
            // 顺序不能反：单看第一段会误判（文档前半段往往只有 doctype + <head>）。
            val group = ArrayList<String>()
            var j = i
            while (j < blocks.size && blocks[j].type == "paragraph" && isBareHtmlParagraph(blocks[j])) {
                group.add(blocks[j].spans.joinToString("") { it.text })
                j++
            }
            val joined = group.joinToString("\n\n")
            if (looksLikeHtmlDocument(joined)) {
                out.add(b.copy(type = "html_raw", text = joined, spans = emptyList()))
                i = j
                continue
            }
            if (looksLikeSvgMarkup(joined)) {
                out.add(b.copy(type = "svg_raw", text = joined, spans = emptyList()))
                i = j
                continue
            }
            for (k in i until j) out.add(blocks[k])
            i = j
            continue
        }
        out.add(b)
        i++
    }
    return out
}

/** 判断段落 spans 是否全为纯文本且以 `<` 开头。 */
internal fun isBareHtmlParagraph(b: RenderBlock): Boolean =
    b.spans.isNotEmpty() &&
        b.spans.all { it.type == "text" } &&
        b.spans.joinToString("") { it.text }.trimStart().startsWith("<")

/** 判定文本是否为完整的 SVG 矢量图源码（允许前置 XML 声明、DOCTYPE 或注释）。 */
internal fun looksLikeSvgMarkup(raw: String): Boolean {
    val cleaned = stripTrailingXmlComments(stripLeadingXmlProlog(raw.trim()))
    if (!cleaned.startsWith("<svg", ignoreCase = true)) return false
    val nextChar = cleaned.getOrNull(4) ?: return false
    if (!nextChar.isWhitespace() && nextChar != '>') return false
    val lower = cleaned.lowercase()
    return lower.endsWith("</svg>") || (!lower.contains("</svg>") && lower.endsWith("/>"))
}

private fun stripTrailingXmlComments(raw: String): String {
    var s = raw.trimEnd()
    while (s.endsWith("-->")) {
        val start = s.lastIndexOf("<!--")
        if (start < 0) break
        s = s.substring(0, start).trimEnd()
    }
    return s
}

/**
 * 判定一段尚未闭合的文本（如 ```xml 或未标语言围栏）是否正在流式输出 SVG 源码，
 * 以便在流式结束前展示加载占位而不倾倒原始 XML 标签。
 */
internal fun looksLikeStreamingSvgStart(raw: String): Boolean {
    val stripped = stripLeadingXmlProlog(raw.trimStart())
    if (!stripped.startsWith("<svg", ignoreCase = true)) return false
    val after = stripped.getOrNull(4)
    return after == null || after.isWhitespace() || after == '>'
}

/** 剥除 SVG 前置的 XML 声明、DOCTYPE 与注释。 */
internal fun stripLeadingXmlProlog(raw: String): String {
    var s = raw.trimStart()
    while (true) {
        s = s.trimStart()
        when {
            s.startsWith("<?xml", ignoreCase = true) -> {
                val end = s.indexOf("?>")
                if (end < 0) break
                s = s.substring(end + 2)
            }
            s.startsWith("<!doctype", ignoreCase = true) -> {
                val end = s.indexOf('>')
                if (end < 0) break
                s = s.substring(end + 1)
            }
            s.startsWith("<!--") -> {
                val end = s.indexOf("-->")
                if (end < 0) break
                s = s.substring(end + 3)
            }
            else -> break
        }
    }
    return s.trimStart()
}

/** [splitProseAroundBareSvg] 的切分片段：[isSvg] 为 true 表示裸 SVG 段，[closed] 表示是否已闭合 `</svg>`。 */
internal data class BareSvgSegment(
    val text: String,
    val isSvg: Boolean,
    val closed: Boolean = true,
)

/**
 * 快速检查原文在代码围栏与行内反引号之外是否包含裸 `<svg ...>` 块。
 *
 * 为什么要在喂给 OpenRender 引擎之前识别：
 * 1. CommonMark 的 HTML 块在空行处断开，空行后缩进 >= 4 空格的 `<g>`/`<path>` 会变成 `code_block`；
 * 2. 若 `<svg>` 与说明文字处在同一段落，`appendRichText` 会把未知标签剥壳只留文字。
 */
internal fun hasBareSvgOutsideFences(raw: String): Boolean {
    if (!raw.contains("<svg", ignoreCase = true)) return false
    return splitProseAroundBareSvg(raw).any { it.isSvg }
}

/**
 * 将「解说 + 裸 `<svg>...</svg>` + 解说」（支持多段裸 SVG）安全切开，跳过 ```` ``` ```` / `~~~` 代码围栏与行内反引号。
 */
internal fun splitProseAroundBareSvg(raw: String): List<BareSvgSegment> {
    if (!raw.contains("<svg", ignoreCase = true)) {
        return listOf(BareSvgSegment(raw, isSvg = false))
    }

    val fenceRanges = findFencedAndInlineCodeRanges(raw)
    fun inCode(pos: Int): Boolean = fenceRanges.any { pos in it.first until it.second }

    val segments = ArrayList<BareSvgSegment>()
    val lower = raw.lowercase()
    var cursor = 0

    while (cursor < raw.length) {
        var svgStart = -1
        var searchFrom = cursor
        while (searchFrom < raw.length) {
            val idx = lower.indexOf("<svg", searchFrom)
            if (idx < 0) break
            val afterTag = lower.getOrNull(idx + 4)
            val validOpen = afterTag == null || afterTag.isWhitespace() || afterTag == '>'
            if (validOpen && !inCode(idx)) {
                svgStart = idx
                break
            }
            searchFrom = idx + 4
        }

        if (svgStart < 0) {
            val tail = raw.substring(cursor)
            if (tail.isNotBlank()) {
                segments.add(BareSvgSegment(tail, isSvg = false))
            }
            break
        }

        // 若 <svg 前紧邻 <?xml ...?> / <!DOCTYPE ...> / XML 注释，一并划进 SVG 段，避免序言残留在正文里
        var actualStart = svgStart
        val beforeSlice = raw.substring(cursor, svgStart)
        val xmlIdx = beforeSlice.lastIndexOf("<?xml", ignoreCase = true)
        if (xmlIdx >= 0 && stripLeadingXmlProlog(beforeSlice.substring(xmlIdx)).isEmpty()) {
            actualStart = cursor + xmlIdx
        }

        val proseBefore = raw.substring(cursor, actualStart).trim()
        if (proseBefore.isNotEmpty()) {
            segments.add(BareSvgSegment(proseBefore, isSvg = false))
        }

        val closeEnd = findMatchingSvgCloseEnd(lower, svgStart)
        if (closeEnd >= 0) {
            val svgText = raw.substring(actualStart, closeEnd).trim()
            segments.add(BareSvgSegment(svgText, isSvg = true, closed = true))
            cursor = closeEnd
        } else {
            val unclosedSvg = raw.substring(actualStart).trim()
            segments.add(BareSvgSegment(unclosedSvg, isSvg = true, closed = false))
            cursor = raw.length
        }
    }

    return if (segments.isEmpty()) listOf(BareSvgSegment(raw, isSvg = false)) else segments
}

/** 按 `<svg` / `</svg>` 嵌套深度匹配最外层闭合位置，兼容子 `<svg>` 图标与自闭合 `<svg ... />`。 */
private fun findMatchingSvgCloseEnd(lower: String, svgStart: Int): Int {
    val n = lower.length
    var depth = 0
    var pos = svgStart
    while (pos < n) {
        val openIdx = lower.indexOf("<svg", pos)
        val closeIdx = lower.indexOf("</svg>", pos)
        if (openIdx >= 0 && (closeIdx < 0 || openIdx < closeIdx)) {
            val after = lower.getOrNull(openIdx + 4)
            if (after != null && (after.isWhitespace() || after == '>' || after == '/')) {
                val tagEnd = lower.indexOf('>', openIdx + 4)
                if (tagEnd < 0) return -1
                val selfClosing = tagEnd > openIdx && lower[tagEnd - 1] == '/'
                if (!selfClosing) {
                    depth++
                } else if (depth == 0) {
                    return tagEnd + 1
                }
                pos = tagEnd + 1
                continue
            }
            pos = openIdx + 4
            continue
        }
        if (closeIdx >= 0) {
            depth--
            pos = closeIdx + "</svg>".length
            if (depth <= 0) return pos
            continue
        }
        break
    }
    return -1
}

/** 找出文本中所有代码围栏（``` / ~~~）与行内反引号（`...`）的字符区间，防止误切代码里的 `<svg>`。 */
private fun findFencedAndInlineCodeRanges(raw: String): List<Pair<Int, Int>> {
    val ranges = ArrayList<Pair<Int, Int>>()
    var i = 0
    val n = raw.length
    var atLineStart = true

    while (i < n) {
        val ch = raw[i]
        if (atLineStart) {
            var indent = 0
            while (i + indent < n && raw[i + indent] == ' ' && indent < 3) indent++
            val p = i + indent
            val markerChar = raw.getOrNull(p)
            if (markerChar == '`' || markerChar == '~') {
                var fenceLen = 0
                while (p + fenceLen < n && raw[p + fenceLen] == markerChar) fenceLen++
                if (fenceLen >= 3) {
                    val lineEnd = raw.indexOf('\n', p + fenceLen)
                    val info = if (lineEnd < 0) raw.substring(p + fenceLen) else raw.substring(p + fenceLen, lineEnd)
                    if (!(markerChar == '`' && info.contains('`'))) {
                        val start = i
                        if (lineEnd < 0) {
                            ranges.add(start to n)
                            break
                        }
                        var closePos = lineEnd + 1
                        var foundClose = false
                        while (closePos < n) {
                            var cp = closePos
                            var cIndent = 0
                            while (cp < n && raw[cp] == ' ' && cIndent < 3) { cp++; cIndent++ }
                            var cLen = 0
                            while (cp + cLen < n && raw[cp + cLen] == markerChar) cLen++
                            if (cLen >= fenceLen) {
                                val nextLine = raw.indexOf('\n', cp + cLen)
                                val tail = if (nextLine < 0) raw.substring(cp + cLen) else raw.substring(cp + cLen, nextLine)
                                if (tail.isBlank()) {
                                    val end = if (nextLine < 0) n else nextLine + 1
                                    ranges.add(start to end)
                                    i = end
                                    atLineStart = true
                                    foundClose = true
                                    break
                                }
                            }
                            val nextNl = raw.indexOf('\n', closePos)
                            if (nextNl < 0) break
                            closePos = nextNl + 1
                        }
                        if (!foundClose) {
                            ranges.add(start to n)
                            break
                        }
                        continue
                    }
                }
            }
        }

        if (ch == '`') {
            var ticks = 1
            while (i + ticks < n && raw[i + ticks] == '`') ticks++
            val delimiter = "`".repeat(ticks)
            val close = raw.indexOf(delimiter, i + ticks)
            // CommonMark 行内代码不跨越空行；防止不成对的反引号把后续段落的裸 <svg> 吞掉。
            if (close >= 0) {
                val spanText = raw.substring(i + ticks, close)
                val crossesBlankLine = spanText.contains("\n\n") || spanText.contains("\r\n\r\n")
                if (!crossesBlankLine) {
                    val end = close + ticks
                    ranges.add(i to end)
                    i = end
                    atLineStart = false
                    continue
                }
            }
        }

        atLineStart = (ch == '\n')
        i++
    }
    return ranges
}

/**
 * 将流式传输中的半截 `<svg ...` 裁剪到最后一个完整闭合的子元素边界，
 * 剥除未完成/危险脚本并按栈倒序自动补齐闭合标签（含 `</svg>`），
 * 使画布能够在生成过程中按图元增量呈现，而不是等到末尾整块突现。
 * 若连 `<svg ...>` 起始标签本身都尚未闭合，则返回 `null`。
 */
internal fun buildStreamingSvgPreview(raw: String): String? {
    val s = stripLeadingXmlProlog(raw)
    if (!s.startsWith("<svg", ignoreCase = true)) return null
    val afterPrefix = s.getOrNull(4)
    if (afterPrefix != null && !afterPrefix.isWhitespace() && afterPrefix != '>') return null

    fun findTagEndOutsideQuotes(src: String, startPos: Int): Int {
        var idx = startPos + 1
        var quote: Char? = null
        while (idx < src.length) {
            val c = src[idx]
            if (quote != null) {
                if (c == quote) quote = null
            } else {
                when (c) {
                    '"', '\'' -> quote = c
                    '>' -> return idx
                }
            }
            idx++
        }
        return -1
    }

    val rootEnd = findTagEndOutsideQuotes(s, 0)
    if (rootEnd < 0) return null
    val rootOpenTag = s.substring(0, rootEnd + 1)
    if (rootOpenTag.dropLast(1).trimEnd().endsWith("/")) {
        return rootOpenTag
    }

    val openStack = mutableListOf("svg")
    val out = StringBuilder(s.length + 32).append(rootOpenTag)
    var safeLen = out.length
    var safeStack = openStack.toList()
    var inStyle = false
    var i = rootEnd + 1

    while (i < s.length) {
        val nextLt = s.indexOf('<', i)
        if (nextLt < 0) {
            break
        }
        if (nextLt > i) {
            out.append(s, i, nextLt)
        }
        when {
            s.startsWith("<!--", nextLt) -> {
                val endComment = s.indexOf("-->", nextLt + 4)
                if (endComment < 0) break
                val end = endComment + 3
                out.append(s, nextLt, end)
                i = end
                if (!inStyle) {
                    safeLen = out.length
                    safeStack = openStack.toList()
                }
            }
            s.startsWith("<![CDATA[", nextLt, ignoreCase = true) -> {
                val endCdata = s.indexOf("]]>", nextLt + 9)
                if (endCdata < 0) break
                val end = endCdata + 3
                out.append(s, nextLt, end)
                i = end
                if (!inStyle) {
                    safeLen = out.length
                    safeStack = openStack.toList()
                }
            }
            else -> {
                val tagEnd = findTagEndOutsideQuotes(s, nextLt)
                if (tagEnd < 0) break
                val tagText = s.substring(nextLt, tagEnd + 1)
                val inner = tagText.substring(1, tagText.length - 1).trim()
                if (inner.startsWith("?") || inner.startsWith("!")) {
                    out.append(tagText)
                    i = tagEnd + 1
                    if (!inStyle) {
                        safeLen = out.length
                        safeStack = openStack.toList()
                    }
                    continue
                }
                val isClosing = inner.startsWith("/")
                val selfClosing = !isClosing && inner.endsWith("/")
                val nameStart = if (isClosing) 1 else 0
                var nameEnd = nameStart
                while (nameEnd < inner.length && !inner[nameEnd].isWhitespace() && inner[nameEnd] != '/') {
                    nameEnd++
                }
                val tagName = inner.substring(nameStart, nameEnd)
                if (tagName.equals("script", ignoreCase = true)) {
                    if (isClosing || selfClosing) {
                        i = tagEnd + 1
                        continue
                    }
                    val closeScript = Regex("</script\\s*>", RegexOption.IGNORE_CASE).find(s, tagEnd + 1)
                        ?: break
                    i = closeScript.range.last + 1
                    continue
                }

                out.append(tagText)
                i = tagEnd + 1
                if (isClosing) {
                    val matchAt = openStack.indexOfLast { it.equals(tagName, ignoreCase = true) }
                    if (matchAt >= 0) {
                        while (openStack.size > matchAt) openStack.removeAt(openStack.lastIndex)
                    }
                    if (tagName.equals("style", ignoreCase = true)) {
                        inStyle = openStack.any { it.equals("style", ignoreCase = true) }
                    }
                } else if (!selfClosing && tagName.isNotEmpty()) {
                    openStack.add(tagName)
                    if (tagName.equals("style", ignoreCase = true)) inStyle = true
                }

                if (!inStyle) {
                    safeLen = out.length
                    safeStack = openStack.toList()
                }
                if (openStack.isEmpty()) break
            }
        }
    }

    val result = StringBuilder(safeLen + safeStack.size * 10)
    result.append(out, 0, safeLen)
    for (idx in safeStack.lastIndex downTo 0) {
        result.append("</").append(safeStack[idx]).append('>')
    }
    return result.toString()
}
