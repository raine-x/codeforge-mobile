package com.hxy.codeforge.ui.browser

import com.hxy.codeforge.render.RenderBlock
import com.hxy.codeforge.render.RenderSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「裸 HTML → 可交互网页」这条路的 JVM 单测。
 *
 * ## 为什么这些断言重要
 *
 * 引擎没有 Html 块类型 —— 裸 HTML 会以纯文本形式落在 paragraph 里。
 * 壳侧靠文本特征把它认出来，**认错了就是 bug**：
 * 把正文里贴的一小段 `<div>` 当整页渲染会吞掉周围的字；
 * 把整页当正文显示就是一屏尖括号。所以边界必须钉死。
 */
class HtmlDetectionTest {

    private fun para(text: String): RenderBlock = RenderBlock(
        id = 0,
        type = "paragraph",
        spans = listOf(RenderSpan(type = "text", text = text)),
    )

    private val fullPage = """
        <!DOCTYPE html>
        <html lang="zh-CN">
        <head><meta charset="utf-8"><title>题</title></head>
        <body><button id="btn">展开解析</button></body>
        </html>
    """.trimIndent()

    @Test
    fun recognisesFullHtmlDocument() {
        assertTrue(looksLikeHtmlDocument(fullPage))
    }

    @Test
    fun recognisesHtmlWithoutDoctypeButWithClosingTags() {
        val page = """
            <html><head><title>t</title></head>
            <body><p>content</p></body></html>
        """.trimIndent()
        assertTrue(looksLikeHtmlDocument(page))
    }

    @Test
    fun rejectsShortSnippets() {
        // 正文里贴的一小段标签 —— 绝不能当整页
        assertFalse(looksLikeHtmlDocument("<div>提示</div>"))
        assertFalse(looksLikeHtmlDocument("<br/>"))
        assertFalse(looksLikeHtmlDocument("<b>粗</b>"))
    }

    @Test
    fun rejectsInlineHtmlMixedWithText() {
        // 以 < 开头但后面还跟着正文 —— 不是整页
        assertFalse(looksLikeHtmlDocument("<div>提示</div> 后面还有一段说明文字，别把它整段吃掉。"))
        assertFalse(looksLikeHtmlDocument("前面有字 <div>x</div>"))
    }

    @Test
    fun rejectsPlainProse() {
        assertFalse(looksLikeHtmlDocument("这是一段普通说明文字，完全没有尖括号。"))
        assertFalse(looksLikeHtmlDocument(""))
    }

    @Test
    fun mergesHtmlBlocksSplitByBlankLines() {
        // markdown 的 HTML 块在空行处断开：一份带空行的文档会变成好几段
        val head = "<!DOCTYPE html>\n<html><head>\n<meta charset=\"utf-8\">\n</head>"
        val body = "<body><button>展开解析</button></body>\n</html>"
        val merged = mergeHtmlBlocks(listOf(para(head), para(body)))

        assertEquals(1, merged.size)
        assertEquals("html_raw", merged[0].type)
        assertTrue(merged[0].text.contains("<!DOCTYPE html>"))
        assertTrue(merged[0].text.contains("</html>"))
        // 空行要还原 —— 文档里的空行是有语义的
        assertTrue(merged[0].text.contains("\n\n"))
    }

    @Test
    fun keepsNonHtmlParagraphsUntouched() {
        val blocks = listOf(
            para("这是说明文字。"),
            RenderBlock(id = 1, type = "heading", level = 2, spans = listOf(RenderSpan("text", "标题"))),
        )
        val merged = mergeHtmlBlocks(blocks)
        assertEquals(2, merged.size)
        assertEquals("paragraph", merged[0].type)
        assertEquals("heading", merged[1].type)
    }

    @Test
    fun doesNotSwallowFollowingNormalParagraph() {
        // 短片段后面跟正文：既不能合并，也不能把正文吃掉
        val blocks = listOf(para("<div>x</div>"), para("这是后面的说明文字。"))
        val merged = mergeHtmlBlocks(blocks)
        assertEquals(2, merged.size)
        assertEquals("paragraph", merged[0].type)
        assertEquals("paragraph", merged[1].type)
    }

    @Test
    fun doctypeAloneIsNotACompleteDocument() {
        // 回归：文档前半段只有 doctype + <head>，它**没写完**。
        // 当成完整文档会把后半截（<body>）漏在页面外。
        val head = "<!DOCTYPE html>\n<html><head>\n<meta charset=\"utf-8\">\n</head>"
        assertFalse(looksLikeHtmlDocument(head))
    }

    @Test
    fun mergesDoctypeHeadWithBodyFragment() {
        // 上面那条的完整场景：前半段 + 后半段必须并成一份
        val head = "<!DOCTYPE html>\n<html><head>\n<meta charset=\"utf-8\">\n</head>"
        val body = "<body><button>展开解析</button></body>\n</html>"
        val merged = mergeHtmlBlocks(listOf(para(head), para(body)))
        assertEquals(1, merged.size)
        assertEquals("html_raw", merged[0].type)
        assertTrue(merged[0].text.contains("<body>"))
        assertTrue(merged[0].text.contains("</html>"))
    }

    @Test
    fun elementWireBlockCarriesRefAndLayout() {
        val el = PickedElement(
            index = 1,
            tag = "button",
            id = "btn",
            cls = "primary",
            text = "展开解析",
            selector = "#btn",
            rect = ElementRect(12f, 34f, 100f, 40f),
            styles = mapOf("display" to "inline-block"),
            pageUrl = "https://example.test/q",
            html = "<button id=\"btn\">展开解析</button>",
        )
        val wire = el.toWireBlock()
        assertTrue(wire.contains("元素1"))
        assertTrue(wire.contains("button"))
        assertTrue(wire.contains("展开解析"))
        assertTrue(wire.contains("#btn"))
        assertTrue(wire.contains("x=12,y=34,w=100,h=40"))
        assertTrue(wire.contains("https://example.test/q"))
        assertEquals("元素1", el.ref)
    }

    // ---------------------------------------------------------------- 原文级判定

    /**
     * 回归（真机）：整页 HTML 带缩进与空行时，markdown 会把它切成
     * 「段落 + 缩进代码块」的碎片，壳侧再也拼不回原文。
     *
     * 修法是在**喂引擎之前**判定原文是不是一整份文档，认出来就直接挂页面。
     * 这些断言守住那条判定线。
     */
    @Test
    fun recognisesWholeHtmlDocumentWithIndentationAndBlankLines() {
        val page = """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8">
            </head>
            <body>
                <div id="app">
                    <button>点我</button>
                </div>
            </body>
            </html>
        """.trimIndent()
        assertTrue(looksLikeWholeHtmlDocument(page))
    }

    @Test
    fun rejectsFencedHtmlFromWholeDocumentPath() {
        // ```html 围栏由引擎走 code_block 通路，不该被当成整页文档
        val fenced = "```html\n<!DOCTYPE html>\n<html><body><p>x</p></body></html>\n```"
        assertFalse(looksLikeWholeHtmlDocument(fenced))
    }

    @Test
    fun rejectsIncompleteHtmlDocument() {
        // 文档还没写完（没有闭合标记）—— 不能当整页
        assertFalse(
            looksLikeWholeHtmlDocument(
                "<!DOCTYPE html>\n<html>\n<head>\n  <title>题</title>\n</head>\n<body>\n  <p>内容"
            )
        )
    }

    @Test
    fun rejectsProseAndSnippets() {
        assertFalse(looksLikeWholeHtmlDocument("这是一段普通说明文字，完全没有尖括号。"))
        assertFalse(looksLikeWholeHtmlDocument("<div>提示</div>"))
        assertFalse(looksLikeWholeHtmlDocument("前面有字 <div>x</div>"))
    }

    /**
     * 回归（真机）：元素挑选必须能在**触屏**上工作。
     *
     * 原实现靠 `mousemove` 记住当前悬停元素，click 时取用 ——
     * 触屏设备根本没有 mousemove，于是 `current` 永远是 null，点什么都没反应。
     *
     * 现在改成：click / touchend 都按坐标现场 `elementFromPoint` 取元素。
     * 这条断言守住「脚本里必须有 touchend 通路，且不依赖 mousemove」。
     */
    @Test
    fun elementPickerSupportsTouchEvents() {
        val script = PageBridge.SCRIPT
        // JUnit 的签名是 assertTrue(message, condition) —— message 在前。
        assertTrue("touch 通路缺失：触屏点选会失效", script.contains("touchend"))
        assertTrue("touchend 坐标取法缺失", script.contains("changedTouches"))
        assertTrue("应按坐标现场取元素，不依赖 hover 状态", script.contains("elementFromPoint"))
        // hover 只能用于「预览高亮」，不能是取元素的唯一途径
        assertTrue("鼠标预览高亮应保留", script.contains("mousemove"))
    }

    // ---------------------------------------------------------------- 元素 + 文字一起加

    /**
     * 「添加到对话」气泡里同时输入的文字，必须随元素一起回传。
     *
     * 用户能一边看着元素一边说「把这个按钮改成圆角」，不用退出浏览器回去打字。
     * 少了这条通路，用户就只能二选一：要么加元素，要么加文字。
     */
    @Test
    fun elementPickerBubbleCarriesUserNote() {
        val script = PageBridge.SCRIPT
        assertTrue("气泡里应有一个备注输入框", script.contains("note"))
        assertTrue("备注应随元素一起回传", script.contains("info.note"))
    }

    /**
     * 备注从 JSON 解析出来 —— 字段名必须是 `note`（与 JS 侧写入的一致）。
     */
    @Test
    fun parsesUserNoteFromElementPayload() {
        val el = PageBridge.parseElement(
            """{"tag":"button","id":"btn","text":"展开","note":"改成圆角"}"""
        )
        assertNotNull("元素应解析成功", el)
        assertEquals("改成圆角", el!!.userNote)
    }

    /** 没有备注时是空串，不是 null —— 空串才能直接拼字符串。 */
    @Test
    fun userNoteDefaultsToEmptyString() {
        val el = PageBridge.parseElement("""{"tag":"button","text":"展开"}""")
        assertNotNull(el)
        assertEquals("", el!!.userNote)
    }

    /**
     * 备注要进到给内核的文本里 —— 模型得知道用户对元素说了什么。
     */
    @Test
    fun wireBlockCarriesUserNote() {
        val el = PickedElement(
            index = 1,
            tag = "button",
            id = "btn",
            cls = "",
            text = "展开解析",
            selector = "#btn",
            rect = ElementRect(0f, 0f, 10f, 10f),
            styles = emptyMap(),
            pageUrl = "https://example.test/q",
            html = "<button></button>",
            userNote = "改成圆角",
        )
        val wire = el.toWireBlock()
        assertTrue(wire.contains("用户备注=改成圆角"))
    }

    /** 没有备注时不要留一行空的「用户备注=」，那是噪声。 */
    @Test
    fun wireBlockOmitsEmptyUserNote() {
        val el = PickedElement(
            index = 1,
            tag = "button",
            id = "",
            cls = "",
            text = "展开解析",
            selector = "#btn",
            rect = ElementRect(0f, 0f, 10f, 10f),
            styles = emptyMap(),
            pageUrl = "",
            html = "<button></button>",
        )
        assertFalse(el.toWireBlock().contains("用户备注"))
    }

    // ------------------------------------------------- 解说 + 文档 + 解说

    @Test
    fun recognisesDocumentWrappedInProse() {
        val wrapped = "下面是一道题，点按钮看解析。\n\n$fullPage\n\n做完告诉我。"
        assertTrue(looksLikeWholeHtmlDocument(wrapped))
    }

    @Test
    fun splitsProseAroundDocument() {
        val wrapped = "前置解说如下。\n\n$fullPage\n\n后置解说收尾。"
        val split = splitProseAroundHtml(wrapped)
        assertEquals("前置解说如下。", split.before)
        assertEquals(fullPage, split.html)
        assertEquals("后置解说收尾。", split.after)
    }

    @Test
    fun splitsUnclosedDocumentToEnd() {
        val partial = "先看题。\n<!DOCTYPE html>\n<html><body><p>写到一半"
        val split = splitProseAroundHtml(partial)
        assertEquals("先看题。", split.before)
        assertEquals("<!DOCTYPE html>\n<html><body><p>写到一半", split.html)
        assertEquals("", split.after)
    }

    @Test
    fun looseRecognisesUnclosedButWholeRejects() {
        val partial = "<!DOCTYPE html>\n<html><body><p>写到一半"
        assertTrue(looksLikeHtmlDocumentLoose(partial))
        assertFalse(looksLikeWholeHtmlDocument(partial))
    }

    @Test
    fun splitWithoutDocumentReturnsOriginalAsBefore() {
        val split = splitProseAroundHtml("普通文字，没有网页。")
        assertEquals("普通文字，没有网页。", split.before)
        assertFalse(split.html != null)
        assertEquals("", split.after)
    }
}
