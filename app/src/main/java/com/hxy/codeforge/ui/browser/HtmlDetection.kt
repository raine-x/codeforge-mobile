package com.hxy.codeforge.ui.browser

import com.hxy.codeforge.render.RenderBlock

/**
 * 「这段文本是不是一整份 HTML 页面」的判定（**原文级**）。
 *
 * ## 为什么要有这一层（与 [looksLikeHtmlDocument] 的区别）
 *
 * 引擎把 markdown 切块时，CommonMark 的 HTML 块（type 6）**在空行处结束**，
 * 之后的内容重新按 markdown 解析 —— 于是缩进 ≥4 空格的 HTML 行变成
 * `code_block`，正文被从中间打断：
 *
 * ```
 * paragraph "<!DOCTYPE html>\n"
 * paragraph "<html>\n"
 * code_block "<div id=\"a\">\n\n    <span>x</span>\n\n</div>\n"   ← 缩进还被吃掉 4 格
 * paragraph "</body>\n"
 * ```
 *
 * 碎片化之后**再也拼不回原文**：缩进丢了、块边界丢了。
 * 所以「先认出来、再决定怎么渲染」必须发生在**喂给引擎之前** ——
 * 这就是本函数与 [looksLikeHtmlDocument] 的分工：
 *
 * - [looksLikeHtmlDocument]：判定「已切碎的块拼起来像不像一份文档」（合并阶段用）
 * - 本函数：判定「原文本身是不是一整份文档」（喂引擎之前用，认出来就直接渲染）
 *
 * ## 判定门槛（沿用 [looksLikeHtmlDocument] 的保守原则）
 *
 * 认错的代价是双向的：把正文里贴的一小段标签当整页渲染会吞掉周围文字；
 * 把整页当正文显示则是一屏尖括号。所以：
 *
 *  - 必须有文档头（`<!DOCTYPE html` 或 `<html>`，可出现在解说之后）；
 *  - 必须是完整文档（有 `</html>` / `</body>` 闭合）；
 *  - 抽出的文档要够长。
 *
 * ## 围栏形态不在这里
 *
 * ```html 围栏由引擎产出 `code_block` + `lang="html"`，渲染层另有通路，
 * 不该走这里 —— 围栏里的内容是**代码**，不是文档。
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
    val lower = raw.lowercase()
    val doctype = lower.indexOf("<!doctype html")
    val htmlTag = lower.indexOf("<html")
    val start = listOfNotNull(
        doctype.takeIf { it >= 0 },
        htmlTag.takeIf { it >= 0 },
    ).minOrNull() ?: return null
    val htmlEndIdx = lower.lastIndexOf("</html>")
    val bodyEndIdx = lower.lastIndexOf("</body>")
    val end = when {
        htmlEndIdx >= 0 -> htmlEndIdx + "</html>".length
        bodyEndIdx >= 0 -> bodyEndIdx + "</body>".length
        else -> raw.length
    }
    if (end <= start) return null
    return raw.substring(start, end)
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
    val lower = raw.lowercase()
    val doctype = lower.indexOf("<!doctype html")
    val htmlTag = lower.indexOf("<html")
    val start = listOfNotNull(
        doctype.takeIf { it >= 0 },
        htmlTag.takeIf { it >= 0 },
    ).minOrNull() ?: return HtmlSplit(raw, null, "")
    val htmlEndIdx = lower.lastIndexOf("</html>")
    val bodyEndIdx = lower.lastIndexOf("</body>")
    val end = when {
        htmlEndIdx >= 0 -> htmlEndIdx + "</html>".length
        bodyEndIdx >= 0 -> bodyEndIdx + "</body>".length
        else -> raw.length
    }
    if (end <= start) return HtmlSplit(raw, null, "")
    return HtmlSplit(
        before = raw.substring(0, start).trim(),
        html = raw.substring(start, end),
        after = raw.substring(end).trim(),
    )
}

/**
 * 「这段文本是不是一份 HTML 页面」的判定。
 *
 * ## 为什么壳侧要自己认
 *
 * OpenRender 没有 Html 块类型 —— 裸 HTML 会被当普通文本塞进 paragraph
 * （引擎 `markdown.rs` 里 `Event::Html` → `push_text`）。
 * 要支持「AI 直接输出 HTML」这条路，只能壳侧按文本特征认。
 *
 * ## 为什么判定要保守
 *
 * 认错了有两种后果：把正文里贴的一小段标签当整页渲染（吞掉周围文字），
 * 或者把整页当正文显示（一屏尖括号）。所以门槛定得高：
 *
 *  - 整段必须以 `<` 开头 —— 行内 HTML 混在文字里的一律不认；
 *  - 必须是完整文档（doctype，或 `<html>` 且有闭合），或带 style/script 的整块；
 *  - 必须够长，且以 `>` 收尾 —— 短片段多半只是正文里的一行标签。
 */
internal fun looksLikeHtmlDocument(raw: String): Boolean {
    val t = raw.trim()
    if (t.length < 40 || !t.startsWith("<") || !t.endsWith(">")) return false
    val lower = t.lowercase()
    // 完整文档：必须有闭合标记。
    //
    // 为什么不能只看 doctype：markdown 的 HTML 块在空行处断开，
    // 一份文档的前半段常常就是 `<!DOCTYPE html><html><head>…</head>` ——
    // 它有 doctype 但**还没写完**，当成完整文档就会把后半截（<body>）漏在页面外。
    val closed = lower.contains("</html>") || lower.contains("</body>")
    if (closed && (lower.contains("<!doctype html") || lower.contains("<html"))) return true
    // 整块富 HTML（不含 svg —— svg 有专门的渲染路径，见 OpenRenderMessage）
    val rich = lower.contains("<style") || lower.contains("<script") || lower.contains("<canvas")
    return rich && t.length >= 120
}

/**
 * 把连续的「裸 HTML 段落」合并成一份完整文档。
 *
 * ## 为什么需要合并
 *
 * markdown 的 HTML 块在**空行**处断开。一份带空行的 HTML 文档
 * （`<head>` 与 `<body>` 之间通常有空行）会被切成好几段，
 * 逐段看谁都不像完整文档。合并之后才认得出来。
 *
 * ## 合并规则
 *
 * 连续的、spans 全是纯文本的、以 `<` 开头的 paragraph 归为一组；
 * 组内用空行拼回（markdown 原文那里就是空行断开的）。
 * 合并后若仍不像完整文档，就原样逐块输出 —— 不改变原有行为。
 */
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
            for (k in i until j) out.add(blocks[k])
            i = j
            continue
        }
        out.add(b)
        i++
    }
    return out
}

/** 是不是「纯 HTML 文本」的段落：spans 全是 text，且拼起来以 `<` 开头。 */
private fun isBareHtmlParagraph(b: RenderBlock): Boolean =
    b.spans.isNotEmpty() &&
        b.spans.all { it.type == "text" } &&
        b.spans.joinToString("") { it.text }.trimStart().startsWith("<")
