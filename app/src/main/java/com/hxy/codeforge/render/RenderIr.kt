package com.hxy.codeforge.render

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** OpenRender 引擎 IR 数据模型。 */
internal data class RenderMathImage(
    val mime: String,
    val width: Int,
    val height: Int,
    /** PNG（ratex）或 SVG 字节；JSON 里是 base64。 */
    val data: ByteArray,
)

internal data class RenderSpan(
    val type: String,
    val text: String = "",
    val url: String = "",
    val title: String = "",
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    val name: String = "",
    val math: RenderMathImage? = null,
    /** `src` 字段（image / audio span）。 */
    val src: String = "",
)

internal data class RenderTable(
    /** 每列对齐：none / left / center / right。 */
    val align: List<String>,
    val header: List<List<RenderSpan>>,
    val rows: List<List<List<RenderSpan>>>,
)

internal data class RenderBlock(
    val id: Long,
    val type: String,
    val level: Int = 0,
    /** 列表嵌套深度（0 = 顶层）。 */
    val depth: Int = 0,
    /** 引用嵌套深度（0 = 非引用）。 */
    val quote: Int = 0,
    val ordered: Boolean? = null,
    val number: Long = 0L,
    val checked: Boolean? = null,
    val closed: Boolean = true,
    /** GFM 警告块：note / tip / important / warning / caution。 */
    val alert: String = "",
    val name: String = "",
    val lang: String = "",
    val text: String = "",
    val url: String = "",
    val title: String = "",
    val description: String = "",
    val math: RenderMathImage? = null,
    val table: RenderTable? = null,
    val spans: List<RenderSpan> = emptyList(),
)

/**
 * 按 `block_id` 应用 delta。
 *
 * `put` 覆盖同 id 块，`append` 只增长流式代码块，`close` 标记闭合，
 * `remove` 删除尾部收缩后消失的块。块 id 稠密递增，UI 可以按 id 做 key。
 */
internal class RenderApplier {
    private val blocks = LinkedHashMap<Long, RenderBlock>()

    var revision: Long = 0L
        private set

    var failed: Boolean = false
        private set

    /** 返回 true 表示这份 delta 需要刷新界面。 */
    fun apply(deltaJson: String?): Boolean {
        if (deltaJson == null) {
            // JNI 侧返回 null = 原生调用失败；继续用空块渲染会吞掉正文，
            // 置 failed 让界面退回纯文本兜底。
            failed = true
            return false
        }
        if (deltaJson.isEmpty()) return false
        val delta = runCatching { JSONObject(deltaJson) }.getOrNull() ?: return false
        if (delta.has("error")) {
            failed = true
            return false
        }
        revision = delta.optLong("rev", revision)
        val ops = delta.optJSONArray("ops") ?: return false
        if (ops.length() == 0) return false
        for (i in 0 until ops.length()) {
            val op = ops.optJSONObject(i) ?: continue
            when (op.optString("op")) {
                "put" -> op.optJSONObject("block")?.let { blocks[it.optLong("id")] = parseBlock(it) }
                "append" -> {
                    val id = op.optLong("id", -1L)
                    val old = blocks[id] ?: continue
                    blocks[id] = old.copy(text = old.text + op.optString("text"))
                }
                "close" -> {
                    val id = op.optLong("id", -1L)
                    val old = blocks[id] ?: continue
                    blocks[id] = old.copy(closed = true)
                }
                "remove" -> blocks.remove(op.optLong("id", -1L))
            }
        }
        return true
    }

    fun snapshot(): List<RenderBlock> = blocks.entries.sortedBy { it.key }.map { it.value }

    fun clear() {
        blocks.clear()
        revision = 0L
        failed = false
    }
}

private fun parseBlock(o: JSONObject): RenderBlock {
    val marker = o.optJSONObject("marker")
    return RenderBlock(
        id = o.optLong("id"),
        type = o.optString("type", "paragraph"),
        level = o.optInt("level"),
        depth = o.optInt("depth"),
        quote = o.optInt("quote"),
        ordered = marker?.optBoolean("ordered"),
        number = marker?.optLong("number", 0L) ?: 0L,
        checked = if (o.has("checked")) o.optBoolean("checked") else null,
        closed = !o.has("closed") || o.optBoolean("closed"),
        alert = o.optString("alert"),
        name = o.optString("name"),
        lang = o.optString("lang"),
        text = o.optString("text"),
        url = o.optString("url"),
        title = o.optString("title"),
        description = o.optString("description"),
        math = o.optJSONObject("math")?.let(::parseMath),
        table = o.optJSONObject("table")?.let(::parseTable),
        spans = parseSpans(o.optJSONArray("spans")),
    )
}

private fun parseTable(o: JSONObject): RenderTable = RenderTable(
    align = o.optJSONArray("align").toStringList(),
    header = parseCells(o.optJSONArray("header")),
    rows = (0 until (o.optJSONArray("rows")?.length() ?: 0)).map { i ->
        parseCells(o.optJSONArray("rows")?.optJSONArray(i))
    },
)

private fun parseCells(a: JSONArray?): List<List<RenderSpan>> {
    if (a == null) return emptyList()
    return (0 until a.length()).map { i -> parseSpans(a.optJSONArray(i)) }
}

private fun parseSpans(a: JSONArray?): List<RenderSpan> {
    if (a == null) return emptyList()
    return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(::parseSpan) }
}

private fun parseSpan(o: JSONObject): RenderSpan {
    val type = o.optString("type", "text")
    // LaTeX 行内公式使用 tex 字段，图片与音频使用 alt 字段
    val text = when (type) {
        "latex_inline" -> o.optString("tex")
        "image", "audio" -> o.optString("alt").ifEmpty { o.optString("text") }
        else -> o.optString("text")
    }
    return RenderSpan(
        type = type,
        text = text,
        url = o.optString("url"),
        title = o.optString("title"),
        bold = o.optBoolean("bold"),
        italic = o.optBoolean("italic"),
        strike = o.optBoolean("strike"),
        width = o.optInt("width"),
        height = o.optInt("height"),
        name = o.optString("name"),
        math = o.optJSONObject("math")?.let(::parseMath),
        src = o.optString("src"),
    )
}

private fun parseMath(o: JSONObject): RenderMathImage? {
    val mime = o.optString("mime")
    val encoded = o.optString("data")
    if (encoded.isEmpty()) return null
    val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return null
    return RenderMathImage(
        mime = mime,
        width = o.optInt("width"),
        height = o.optInt("height"),
        data = bytes,
    )
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).map { optString(it) }
}

/**
 * 增量 Markdown 文档宿主状态：通过前缀 diff 仅将新增文本喂入原生引擎，
 * 在文本被整体替换（重发/编辑）时自动 reset 重算。
 */
internal class OpenRenderDocument(
    private val density: Float,
    private val fontSize: Float = 15f,
    /** 用于落地公式渲染所需的 Unicode 回退字体。 */
    private val cacheDir: String = "",
) : AutoCloseable {

    // 库成功加载不保证 ABI 符号一定可解析，构造与调用统一兜底
    private val engine = if (OpenRenderNative.available) {
        runCatching { OpenRenderNative() }.getOrNull()
    } else {
        null
    }
    private val applier = RenderApplier()
    private var fed = ""
    private var finished = false

    var lastSnapshot: List<RenderBlock> = emptyList()
        private set

    val available: Boolean get() = OpenRenderNative.available
    val failed: Boolean get() = engine == null || applier.failed

    init {
        runCatching {
            engine?.configure()
            // 公式走 ratex：PNG 像素尺寸 = em × DPR，宿主按 dp 展示即与正文 15sp 对齐
            engine?.useRatex(fontSize, density.coerceAtLeast(1f), cacheDir)
        }
    }

    fun sync(source: String, streaming: Boolean): List<RenderBlock> {
        val safeEngine = engine ?: return emptyList()
        val target = normalizeMathDelimiters(normalizeMarkdownForRender(source))
        if (target.isEmpty()) {
            if (fed.isNotEmpty() || finished) {
                runCatching { safeEngine.reset() }
                applier.clear()
                fed = ""
                finished = false
            }
            lastSnapshot = emptyList()
            return lastSnapshot
        }

        if (finished && streaming) {
            // 同一条消息重新进入流式（重试）——内容可能被替换，统一重来
            runCatching { safeEngine.reset() }
            applier.clear()
            fed = ""
            finished = false
        }

        if (target == fed) {
            if (!streaming && !finished) {
                apply(runCatching { safeEngine.finish() }.getOrNull())
                finished = true
            }
            return lastSnapshot
        }

        if (!target.startsWith(fed)) {
            runCatching { safeEngine.reset() }
            applier.clear()
            fed = ""
            finished = false
        }

        val delta = target.substring(fed.length)
        fed = target
        apply(runCatching { safeEngine.feed(delta) }.getOrNull())

        if (!streaming) {
            apply(runCatching { safeEngine.finish() }.getOrNull())
            finished = true
        }
        return lastSnapshot
    }

    private fun apply(deltaJson: String?) {
        if (applier.apply(deltaJson)) {
            lastSnapshot = applier.snapshot()
        }
    }

    override fun close() {
        runCatching { engine?.close() }
    }
}

/**
 * 将 `\(…\)` / `\[…\]` 归一化为 `$…$` / `$$…$$` 以兼容 LaTeX 解析器，
 * 跳过代码围栏及已有 `$$…$$` 块内（避免误改 `\\[2pt]` 等行距命令）。
 */
internal fun normalizeMathDelimiters(src: String): String {
    if (!src.contains("\\(") && !src.contains("\\)") &&
        !src.contains("\\[") && !src.contains("\\]")
    ) {
        return src
    }

    var inFence = false
    var fenceChar = '`'
    var fenceLen = 0
    var inBlockMath = false
    val out = StringBuilder(src.length + 16)
    val lines = src.split('\n')

    fun replaceLine(line: String): String {
        val sb = StringBuilder(line.length)
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && i + 1 < line.length) {
                val next = line[i + 1]
                if (!inBlockMath && (next == '(' || next == ')' || next == '[' || next == ']')) {
                    sb.append(if (next == '(' || next == ')') "\$" else "\$\$")
                    i += 2
                    continue
                }
                sb.append(c).append(next)
                i += 2
                continue
            }
            if (c == '$' && i + 1 < line.length && line[i + 1] == '$') {
                inBlockMath = !inBlockMath
                sb.append("\$\$")
                i += 2
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    lines.forEachIndexed { index, line ->
        val trimmed = line.trimStart()
        if (inFence) {
            val runLen = trimmed.takeWhile { it == fenceChar }.length
            if (runLen >= fenceLen && trimmed.drop(runLen).isBlank()) inFence = false
            out.append(line)
        } else {
            val first = trimmed.firstOrNull()
            val runLen = if (first == '`' || first == '~') {
                trimmed.takeWhile { it == first }.length
            } else {
                0
            }
            // CommonMark 规则：反引号围栏信息串中不能包含反引号，否则属于单行内嵌代码跨度
            if (runLen >= 3 && !(first == '`' && trimmed.drop(runLen).contains('`'))) {
                inFence = true
                fenceChar = first!!
                fenceLen = runLen
                out.append(line)
            } else {
                out.append(replaceLine(line))
            }
        }
        if (index < lines.lastIndex) out.append('\n')
    }
    return out.toString()
}

private val INLINE_FENCE_TAG_REGEX = Regex("""^(?i)(svg|html|htm|xml)(?=<)|^(?i)(ui|widget|json)(?=\{)""")
private val TABLE_SEP_CELL_REGEX = Regex("""^:?-+:?$""")

private fun countTableSepColumns(line: String): Int? {
    val t = line.trim()
    if (!t.contains('|') || !t.contains('-')) return null
    val stripped = t.removePrefix("|").removeSuffix("|")
    if (stripped.isEmpty()) return null
    val cells = stripped.split('|')
    var hasDash = false
    for (cell in cells) {
        val c = cell.trim()
        if (!TABLE_SEP_CELL_REGEX.matches(c)) return null
        if (c.contains("--") || c == "-") hasDash = true
    }
    return if (hasDash) cells.size else null
}

private fun isTableLikeLine(line: String): Boolean {
    val t = line.trim()
    return t.startsWith("|") && t.count { it == '|' } >= 2
}

/**
 * 预处理 LLM 输出的常见 Markdown 格式粘连问题（仅在代码围栏外生效）：
 * 1. 围栏语言与首行正文缺少换行（如 ```` ```svg<svg ...> ````、```` ```html<!DOCTYPE html> ````、```` ```ui{...} ````）；
 * 2. 标题或正文与表格表头粘连在同一行（如 `二线：值得考虑| # | 选题 | ...` 紧跟 `|---|...|`），或表格前缺少空行。
 */
internal fun normalizeMarkdownForRender(src: String): String {
    if (src.isEmpty()) return src
    val lines = src.split('\n')
    val out = ArrayList<String>(lines.size + 4)

    var inFence = false
    var fenceChar = '`'
    var fenceLen = 0
    var i = 0

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        val indentLen = line.length - trimmed.length

        if (inFence) {
            val runLen = if (indentLen <= 3) trimmed.takeWhile { it == fenceChar }.length else 0
            if (runLen >= fenceLen && trimmed.drop(runLen).isBlank()) {
                inFence = false
            }
            out.add(line)
            i++
            continue
        }

        // 检查代码围栏起始行（允许 0..3 个前导空格）
        val first = trimmed.firstOrNull()
        val runLen = if (indentLen <= 3 && (first == '`' || first == '~')) {
            trimmed.takeWhile { it == first }.length
        } else {
            0
        }
        if (runLen >= 3) {
            val rest = trimmed.drop(runLen)
            if (!(first == '`' && rest.contains('`'))) {
                val m = INLINE_FENCE_TAG_REGEX.find(rest)
                inFence = true
                fenceChar = first!!
                fenceLen = runLen
                if (m != null) {
                    val indent = line.substring(0, indentLen)
                    val marker = trimmed.substring(0, runLen)
                    val lang = m.value
                    val body = rest.substring(m.range.last + 1)
                    out.add("$indent$marker$lang")
                    out.add(body)
                } else {
                    out.add(line)
                }
                i++
                continue
            }
        }

        // 检查 GFM 表格粘连（line[i] 为表头候选，line[i+1] 为分隔行 |---|---|）
        val sepCols = if (i + 1 < lines.size) countTableSepColumns(lines[i + 1]) else null
        if (sepCols != null && line.isNotBlank()) {
            val pipeIdx = line.indexOf('|')
            if (pipeIdx > 0 && trimmed.firstOrNull() != '|' && trimmed.firstOrNull() != '>') {
                val prefix = line.substring(0, pipeIdx).trimEnd()
                val headerPart = line.substring(pipeIdx)
                val headerCols = headerPart.trim().removePrefix("|").removeSuffix("|").split('|').size
                if (prefix.isNotEmpty() && headerCols == sepCols) {
                    out.add(prefix)
                    out.add("")
                    out.add(headerPart)
                    i++
                    continue
                }
            }
            if (pipeIdx >= 0 && out.isNotEmpty() && out.last().isNotBlank() &&
                !isTableLikeLine(out.last()) && !out.last().trimStart().startsWith(">")
            ) {
                out.add("")
            }
        }

        out.add(line)
        i++
    }
    return out.joinToString("\n")
}

