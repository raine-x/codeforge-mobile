package app.codeforge.render

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * OpenRender 的 IR 数据模型（`crates/openrender-core/src/ir.rs` 的 Kotlin 映射）。
 *
 * 引擎输出的是语言无关的渲染指令，不输出像素：排版、媒体、手势全部由宿主决定。
 * 这里只保留壳需要的字段；字段名与 `json.rs` 的线格式一一对应。
 */
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
    // json.rs 的 write_span 按类型换键：latex_inline 用 tex，image/audio 用 alt
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
 * 一份流式 Markdown 文档的宿主状态。
 *
 * 输入是「到目前为止的完整文本」（内核 Delta 事件把增量折进了消息文本），
 * 这里自己做前缀 diff：只有新增部分喂给引擎；文本被整体替换（重试/编辑重发）
 * 时 reset 后重喂。这样每次解析成本 = 新增文本 + 固定尾部窗口，而不是全文。
 */
internal class OpenRenderDocument(
    private val density: Float,
    private val fontSize: Float = 15f,
    /**
     * 应用 cacheDir —— 公式渲染需要落地 Unicode 回退字体，Android 上没有
     * 可发现的系统字体（见 [OpenRenderNative.useRatex]）。
     */
    private val cacheDir: String = "",
) : AutoCloseable {

    // 库能 load 不代表符号能解析（包名/R8/ABI 任一错都会在首次调用时抛
    // UnsatisfiedLinkError）——构造与首调都包在 runCatching 里，失败退纯文本。
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
        // GLM/Qwen 系用 \( \) / \[ \] 写公式；pulldown-cmark 的 math 扩展只认 $ / $$，
        // 先做定界符归一（跳过代码围栏与已有 $ 公式，保住 \\[2pt] 这类间距命令）。
        val safeEngine = engine ?: return emptyList()
        val target = normalizeMathDelimiters(source)
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
 * 把 `\(…\)` / `\[…\]` 归一成 `$…$` / `$$…$$`。
 *
 * pulldown-cmark 的 math 扩展只认 `$` / `$$`，但 GLM/Qwen 系模型常用反斜杠定界符。
 * 归一必须跳过两类区域：
 *  - 代码围栏内：反斜杠是代码内容，改了就是错的；
 *  - 已有 `$$…$$` 内：`\\[2pt]` 是 LaTeX 行距命令，不能被当成块级定界符。
 *
 * 这是逐字符映射，天然前缀稳定，所以下游的前缀 diff 仍然成立；当前实现每次
 * 归一都扫全文，但这一步远轻于一次完整 Markdown 解析，且只有含反斜杠定界符的
 * 消息才会触发。
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
            if (runLen >= 3) {
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

