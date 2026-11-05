package com.hxy.codeforge.ui.browser

/**
 * 内置浏览器的数据模型。
 *
 * 这些类型只在壳侧流转：WebView 里的 JS 把元素信息回传，经 [PickedElement]
 * 落到输入框，再由发送链路带给内核。内核协议没有专门承载「页面元素」的字段，
 * 所以走的是「用户可见文本 + 追加的结构化描述」这条路（见 ChatViewModel.send）。
 */

/** 元素在页面坐标系里的矩形（CSS px，相对视口左上角）。 */
data class ElementRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
) {
    /** 人类可读的紧凑描述，写进给内核的文本里。 */
    fun compact(): String = "x=${round(x)},y=${round(y)},w=${round(width)},h=${round(height)}"

    private fun round(v: Float): String =
        if (v == v.toInt().toFloat()) v.toInt().toString() else String.format("%.1f", v)
}

/**
 * 从网页里挑中并「添加到对话」的元素。
 *
 * @param index 元素序号（元素1、元素2……），每次添加自增，是对话里引用它的唯一凭据。
 * @param selector 稳定的 CSS 选择器 —— 供 AI 在后续轮次里重新定位同一个元素。
 * @param rect 视口坐标下的矩形（CSS px）。页面滚动后视口坐标会变，所以它只是
 *             「当时长什么样」的快照，不是可复现的位置。
 * @param html 元素外层 HTML（截断到 4000 字符），供 AI 理解这个控件的结构。
 */
data class PickedElement(
    val index: Int,
    val tag: String,
    val id: String,
    val cls: String,
    val text: String,
    val selector: String,
    val rect: ElementRect,
    val styles: Map<String, String>,
    val pageUrl: String,
    val html: String,
    /**
     * 用户在「添加到对话」气泡里同时输入的文字。
     *
     * 为什么元素和文字要一起回传：气泡浮在网页上，用户能一边看着元素一边说
     * 「把这个按钮改成圆角」——如果只能二选一，就得退出浏览器回去打字，
     * 上下文全丢了。文字随元素一起送进输入框，用户接着写也行。
     */
    val userNote: String = "",
) {
    /** 对话里显示的引用名。 */
    val ref: String get() = "元素$index"

    /** 输入框 chip 上显示的摘要。 */
    val summary: String
        get() {
            val label = text.ifBlank { id.ifBlank { tag } }.replace('\n', ' ').trim()
            return "$tag · ${label.take(24)}"
        }

    /** 带给内核的结构化描述。 */
    fun toWireBlock(): String = buildString {
        append("【CodeForge 页面元素】\n")
        append("$ref | 标签=$tag")
        if (id.isNotBlank()) append(" | id=$id")
        if (cls.isNotBlank()) append(" | class=${cls.take(60)}")
        append("\n")
        append("文本=${text.replace('\n', ' ').take(200)}\n")
        if (userNote.isNotBlank()) {
            append("用户备注=${userNote.replace('\n', ' ').take(300)}\n")
        }
        append("位置=${rect.compact()}（视口坐标，CSS px）\n")
        append("选择器=$selector\n")
        if (styles.isNotEmpty()) {
            append("样式=" + styles.entries.joinToString("; ") { "${it.key}:${it.value}" } + "\n")
        }
        append("页面=$pageUrl\n")
        append("<元素HTML>\n")
        append(html)
        append("\n</元素HTML>")
    }
}

/**
 * 内置浏览器要打开的目标。
 *
 * [Html] 是 AI 生成的整页文档（对话流里点「打开」进来）；[Url] 是外链。
 */
sealed interface BrowserTarget {
    val title: String

    data class Url(override val title: String, val url: String) : BrowserTarget

    data class Html(override val title: String, val html: String) : BrowserTarget
}

/**
 * 元素选择器回传的一次点击。
 *
 * [kind] 目前只有 "element"；留着是为了以后加 "click" / "input" 时不必改协议。
 */
data class BridgeEvent(
    val kind: String,
    val payload: String,
) {
    companion object {
        const val KIND_ELEMENT = "element"
    }
}

/**
 * 一次性插入请求。
 *
 * 输入框（Composer）持有真实的输入内容，所以「把元素1加进对话」这件事
 * 必须由它来完成 —— 壳侧只负责登记元素并发出这个请求。
 *
 * [seq] 单调递增，用作 Compose 的 key：连续两次插入的 [ref] 可能是同一个
 * 「元素1」，只看 ref 会漏掉第二次。
 *
 * [note] 是用户在「添加到对话」气泡里同时输入的文字（可空）。它和元素一起
 * 插进输入框 —— 用户不用退出浏览器回去打字。
 */
data class ElementInsert(
    val seq: Long,
    val ref: String,
    val note: String = "",
)
