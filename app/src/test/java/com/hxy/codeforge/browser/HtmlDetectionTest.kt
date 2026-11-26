package com.hxy.codeforge.ui.browser

import com.hxy.codeforge.render.RenderBlock
import com.hxy.codeforge.render.RenderSpan
import com.hxy.codeforge.render.RenderTable
import com.hxy.codeforge.ui.chat.buildSvgHtmlDocument
import com.hxy.codeforge.ui.chat.computeTableLayout
import com.hxy.codeforge.ui.chat.isSvgPathOrDataUri
import com.hxy.codeforge.ui.chat.needsDarkBackdropInLightMode
import com.hxy.codeforge.ui.chat.needsLightBackdropInDarkMode
import com.hxy.codeforge.ui.chat.normalizeSvgForRender
import com.hxy.codeforge.ui.chat.scaleMicroSvgFontsForRender
import com.hxy.codeforge.ui.chat.stripPlainLightCanvasBackdrop
import com.hxy.codeforge.ui.chat.svgAspect
import com.hxy.codeforge.ui.chat.svgHasOpaqueBackdrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 裸 HTML 识别与交互文档切分单元测试。 */
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

    /** 含缩进与空行的整页 HTML 须在解析分块前完成原文级识别。 */
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

    /** 元素选择脚本须基于 touchend/click 坐标调用 elementFromPoint，不依赖 hover 态。 */
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

    // 元素 + 文字一起加

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

    // 解说 + 文档 + 解说

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

    // SVG 直接显示与切分

    @Test
    fun recognisesSvgMarkupWithXmlPrologAndMultiLineBlankLines() {
        val svg = """
            <?xml version="1.0" encoding="UTF-8"?>
            <svg width="400" height="200" xmlns="http://www.w3.org/2000/svg">

                <rect width="100%" height="24" fill="#eee"/>
                <circle cx="200" cy="100" r="60" fill="#d97757"/>
            </svg>
        """.trimIndent()
        assertTrue(looksLikeSvgMarkup(svg))
        // 根标签 width=400, height=200，不能被子元素 <rect width="100%" height="24"> 污染
        val aspect = svgAspect(svg)
        assertNotNull(aspect)
        assertEquals(2.0f, aspect!!, 0.01f)
        // 无 viewBox 时自动补齐 viewBox="0 0 400 200"
        val normalized = normalizeSvgForRender(svg)
        assertTrue(normalized.contains("""viewBox="0 0 400 200""""))
    }

    @Test
    fun splitsProseAroundBareSvgWhilePreservingFencedAndInlineCode() {
        val raw = """
            下面是架构矢量图（注意行内 `<svg>` 标签不应被切开）：

            <svg viewBox='0 0 300 150' xmlns="http://www.w3.org/2000/svg">

                <g>
                    <rect x="10" y="10" width="280" height="130" rx="12"/>
                </g>
            </svg>

            以及一段代码围栏：
            ```xml
            <svg viewBox="0 0 100 100"><circle cx="50" cy="50" r="40"/></svg>
            ```
        """.trimIndent()

        assertTrue(hasBareSvgOutsideFences(raw))
        val segments = splitProseAroundBareSvg(raw)
        assertEquals(3, segments.size)
        assertFalse(segments[0].isSvg)
        assertTrue(segments[0].text.contains("`<svg>`"))
        assertTrue(segments[1].isSvg)
        assertTrue(segments[1].closed)
        assertTrue(segments[1].text.startsWith("<svg"))
        assertTrue(segments[1].text.endsWith("</svg>"))
        assertFalse(segments[2].isSvg)
        assertTrue(segments[2].text.contains("```xml"))
    }

    @Test
    fun detectsSvgPathAndDataUri() {
        assertTrue(isSvgPathOrDataUri("diagram.svg"))
        assertTrue(isSvgPathOrDataUri("https://example.com/chart.SVG?v=1#top"))
        assertTrue(isSvgPathOrDataUri("data:image/svg+xml;utf8,<svg></svg>"))
        assertTrue(isSvgPathOrDataUri("data:image/svg+xml;base64,PHN2Zz48L3N2Zz4="))
        assertFalse(isSvgPathOrDataUri("photo.png"))
    }

    @Test
    fun splitsNestedSvgAndIgnoresUnclosedBacktickAcrossBlankLine() {
        val raw = """
            前文里不小心留了一个未闭合反引号 `foo

            <svg viewBox="0 0 600 300">
              <svg x="20" y="20" width="24" height="24" viewBox="0 0 24 24">
                <circle cx="12" cy="12" r="10"/>
              </svg>
              <rect x="60" y="20" width="120" height="40"/>
            </svg>

            后文说明结束。
        """.trimIndent()

        assertTrue(hasBareSvgOutsideFences(raw))
        val segments = splitProseAroundBareSvg(raw)
        assertEquals(3, segments.size)
        assertFalse(segments[0].isSvg)
        assertTrue(segments[1].isSvg)
        assertTrue(segments[1].closed)
        // 必须包含外层完整 SVG，不能在内层 </svg> 处提前截断
        assertTrue(segments[1].text.contains("<rect x=\"60\""))
        assertTrue(segments[1].text.trimEnd().endsWith("</svg>"))
        assertFalse(segments[2].isSvg)
        assertTrue(segments[2].text.contains("后文说明结束"))
    }

    @Test
    fun recognisesSvgWithTrailingCommentAndNormalizesStyleAndXmlns() {
        val withComment = """
            <svg stroke-width="2" style="width: 640px; height: 320px;">
                <path d="M0 0 L100 100"/>
            </svg>
            <!-- generated by model -->
        """.trimIndent()

        assertTrue(looksLikeSvgMarkup(withComment))
        val aspect = svgAspect(withComment)
        assertNotNull(aspect)
        assertEquals(2.0f, aspect!!, 0.01f)

        val normalized = normalizeSvgForRender(withComment)
        assertTrue(normalized.contains("""xmlns="http://www.w3.org/2000/svg""""))
        assertTrue(normalized.contains("""viewBox="0 0 640 320""""))
        // 补齐 viewBox 后须剥除根 style 中的固定 width/height 锁定，确保移动端流体自适应
        assertFalse(normalized.contains("640px"))

        val pxViewBox = """<svg viewBox="0 0 900px 300px"><rect width="10" height="10"/></svg>"""
        assertEquals(3.0f, svgAspect(pxViewBox)!!, 0.01f)

        val html = buildSvgHtmlDocument(normalized, lightCanvas = true)
        assertTrue(html.contains("body>svg{"))
        assertFalse(html.contains("!important"))

        // 自带不透明深色背景卡片的 SVG 必须识别出底色且不触发深色模式外垫框
        val viewBoxOnlySvg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 800 430" font-family="system-ui, -apple-system, sans-serif">
              <rect width="800" height="430" rx="12" fill="#0f172a"/>
              <text x="40" y="34" fill="#f1f5f9" font-size="17">三种赢法</text>
            </svg>
        """.trimIndent()
        assertEquals(800f / 430f, svgAspect(viewBoxOnlySvg)!!, 0.01f)
        val viewBoxHtml = buildSvgHtmlDocument(normalizeSvgForRender(viewBoxOnlySvg), lightCanvas = true)
        assertTrue(viewBoxHtml.contains("body>svg{width:100%;height:100%"))
        assertTrue(viewBoxHtml.contains("""viewBox="0 0 800 430""""))
        assertTrue(viewBoxHtml.contains("""fill="#0f172a""""))

        assertTrue(svgHasOpaqueBackdrop(viewBoxOnlySvg))
        assertFalse(needsLightBackdropInDarkMode(viewBoxOnlySvg))

        // 浅色主题下模型自带的纯白整卡背景与边框须在渲染文档中透明化以融入主题，且保留内部节点框
        val whiteCardGraph = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 800 460">
              <rect width="100%" height="100%" rx="12" fill="#ffffff" stroke="#e5e7eb"/>
              <rect x="60" y="40" width="120" height="36" fill="#ffffff" stroke="#cbd5e1"/>
              <text x="80" y="64" font-size="11" fill="#1f2937">y = 2x</text>
            </svg>
        """.trimIndent()
        val stripped = stripPlainLightCanvasBackdrop(whiteCardGraph)
        assertTrue(stripped.contains("""rx="12" fill="none" stroke="none""""))
        // 内部卡片不得误伤
        assertTrue(stripped.contains("""width="120" height="36" fill="#ffffff" stroke="#cbd5e1""""))

        val lightSeamlessHtml = buildSvgHtmlDocument(whiteCardGraph, lightCanvas = true, transparentBg = true, injectBridge = true)
        assertTrue(lightSeamlessHtml.contains("""rx="12" fill="none" stroke="none""""))
        assertTrue(lightSeamlessHtml.contains("window.__cf"))
        assertEquals(1, Regex("</script>", RegexOption.IGNORE_CASE).findAll(lightSeamlessHtml).count())
        // 桌面级大画布的极小 11px 字号在渲染态应得到温和放大
        val scaled = scaleMicroSvgFontsForRender(whiteCardGraph)
        assertFalse(scaled.contains("""font-size="11""""))

        // 未闭合流式 SVG 前缀识别（含 XML 序言 + 裸 <svg）
        assertTrue(looksLikeStreamingSvgStart("""<?xml version="1.0" encoding="UTF-8"?><svg viewBox="0 0 400 300"><path"""))
        assertTrue(looksLikeStreamingSvgStart("<svg"))
        assertFalse(looksLikeStreamingSvgStart("<svgIcon/>"))
        val streamingBareSegments = splitProseAroundBareSvg("说明文字：\n<?xml version=\"1.0\"?>\n<!-- draft -->\n<svg viewBox=\"0 0 400 300\"><line")
        assertEquals(2, streamingBareSegments.size)
        assertTrue(streamingBareSegments[1].isSvg)
        assertFalse(streamingBareSegments[1].closed)
        assertTrue(streamingBareSegments[1].text.startsWith("<?xml"))

        // 透明线框流程图（深色线条、无整卡底色）在浅色端保持透明无框，仅在深色模式下判定需垫柔白底
        val transparentWireframe = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 600 400">
              <rect x="40" y="20" width="520" height="80" rx="12" fill="none" stroke="#333333"/>
              <text x="300" y="66" fill="#1f1e1c" text-anchor="middle">Camera2 / ISP</text>
            </svg>
        """.trimIndent()
        assertFalse(svgHasOpaqueBackdrop(transparentWireframe))
        assertTrue(needsLightBackdropInDarkMode(transparentWireframe))

        val seamlessHtml = buildSvgHtmlDocument(transparentWireframe, lightCanvas = true, transparentBg = true)
        assertTrue(seamlessHtml.contains("background:transparent"))

        // 解说 + ```html 围栏不得被裸 HTML 提取器从围栏内部撕裂
        val prosePlusFencedHtml = """
            下面是网页代码示例：

            ```html
            <!DOCTYPE html>
            <html><body><button>Hi</button></body></html>
            ```

            你可以复制使用。
        """.trimIndent()
        assertFalse(looksLikeWholeHtmlDocument(prosePlusFencedHtml))
        assertFalse(looksLikeHtmlDocumentLoose(prosePlusFencedHtml))

        // 浅色主题下：仅使用浅色文字且无全画布暗底的 SVG 需自动补暗色底垫防看不清
        val lightInkNoBackdrop = """
            <svg viewBox="0 0 400 320" xmlns="http://www.w3.org/2000/svg">
              <text x="20" y="26" fill="#e2e8f0" font-size="17">标题</text>
              <rect x="20" y="86" width="360" height="30" rx="7" fill="#1e293b"/>
              <text x="34" y="106" fill="#e2e8f0" font-size="13">条目</text>
            </svg>
        """.trimIndent()
        assertTrue(needsDarkBackdropInLightMode(lightInkNoBackdrop))
        assertFalse(needsDarkBackdropInLightMode(transparentWireframe))
        assertFalse(needsDarkBackdropInLightMode(viewBoxOnlySvg))

        // 5 列长文宽表在窄屏自动启用横向滚动并维持列宽可读；2 列表则维持全宽填充
        val fiveColTable = RenderTable(
            align = List(5) { "none" },
            header = listOf("#", "选题", "一句话", "赛道", "核心资产").map { listOf(RenderSpan("text", it)) },
            rows = listOf(
                listOf("1", "倒计时拆弹台", "把还剩37天考完五门变成可视化风险调度图", "学习科研", "倒排算法+崩盘预测模型")
                    .map { listOf(RenderSpan("text", it)) },
            ),
        )
        val fiveLayout = computeTableLayout(fiveColTable, cols = 5, availableDp = 328f, density = 3f)
        assertTrue(fiveLayout.horizontalScroll)
        assertTrue(fiveLayout.widthsDp.sum() > 328f)

        // 4 列长介绍表（对应截图窄列高柱痛点）在 328dp 窄屏启用横向滚动，长描述列获得宽敞可读宽度
        val fourColDenseTable = RenderTable(
            align = listOf("none", "none", "none", "none"),
            header = listOf("#", "题目", "简短介绍", "赛道").map { listOf(RenderSpan("text", it)) },
            rows = listOf(
                listOf(
                    "1",
                    "审稿意见拆解台",
                    "贴上审稿意见 -> 每条定位到原稿段落并给出回应模板；区分必须修改项与可解释的误读",
                    "学习科研",
                ).map { listOf(RenderSpan("text", it)) },
            ),
        )
        val fourLayout = computeTableLayout(fourColDenseTable, cols = 4, availableDp = 328f, density = 3f)
        assertTrue(fourLayout.horizontalScroll)
        assertTrue(fourLayout.widthsDp[2] >= 200f)

        // 2 列长短悬殊表按 sqrt(desired) 配额，长列明显宽于短列而非五五对半切
        val asymmetricTwoColTable = RenderTable(
            align = listOf("none", "none"),
            header = listOf("术语", "详细解释").map { listOf(RenderSpan("text", it)) },
            rows = listOf(
                listOf(
                    "SM-2",
                    "基于遗忘曲线的间隔重复调度算法，根据每次回忆评分动态调整下次复习间隔与难度系数，适合长周期备考记忆规划",
                ).map { listOf(RenderSpan("text", it)) },
            ),
        )
        val asymLayout = computeTableLayout(asymmetricTwoColTable, cols = 2, availableDp = 328f, density = 3f)
        assertFalse(asymLayout.horizontalScroll)
        assertEquals(328f, asymLayout.widthsDp.sum(), 0.5f)
        assertTrue(asymLayout.widthsDp[1] > asymLayout.widthsDp[0] * 1.8f)

        val twoColTable = RenderTable(
            align = listOf("none", "none"),
            header = listOf("元素", "含义").map { listOf(RenderSpan("text", it)) },
            rows = listOf(listOf("红线", "斜率 k=2 的一次函数").map { listOf(RenderSpan("text", it)) }),
        )
        val twoLayout = computeTableLayout(twoColTable, cols = 2, availableDp = 328f, density = 3f)
        assertFalse(twoLayout.horizontalScroll)
        assertEquals(328f, twoLayout.widthsDp.sum(), 0.5f)
    }

    @Test
    fun buildsIncrementalStreamingSvgPreviewSafely() {
        // 起始 <svg ...> 本身尚未闭合时暂不生成碎片，避免丢失 viewBox
        assertNull(buildStreamingSvgPreview("""<svg viewBox="0 0 400 320""""))

        // 前半个图形已闭合，尾随残缺 <path 与未闭合 <g> 须被安全裁剪并自动补齐闭合标签
        val partial = """
            <?xml version="1.0" encoding="utf-8"?>
            <svg viewBox="0 0 400 320" xmlns="http://www.w3.org/2000/svg">
              <defs><marker id="arr"><path d="M0,0 L4,2 L0,4"/></marker></defs>
              <g id="axes">
                <line x1="20" y1="160" x2="380" y2="160" stroke="#999"/>
                <text x="360" y="150">x</text>
                <path d="M 20 200 L 140 90
        """.trimIndent()
        val preview = buildStreamingSvgPreview(partial)
        assertNotNull(preview)
        assertTrue(preview!!.startsWith("<svg "))
        assertTrue(preview.contains("""<line x1="20""""))
        assertTrue(preview.contains("""<text x="360" y="150">x</text>"""))
        assertFalse(preview.contains("L 140 90"))
        assertTrue(preview.endsWith("</g></svg>"))

        // 流式未闭合样式或脚本片段不应混入半成品，脚本在流式阶段剥除
        val withStyleAndScript = """
            <svg viewBox="0 0 300 200"><rect x="0" y="0" width="50" height="50"/><script>alert(1)</script><style>.cls{fill:
        """.trimIndent()
        val cleanPreview = buildStreamingSvgPreview(withStyleAndScript)!!
        assertTrue(cleanPreview.contains("""<rect x="0""""))
        assertFalse(cleanPreview.contains("<script"))
        assertFalse(cleanPreview.contains("<style"))
        assertTrue(cleanPreview.endsWith("</svg>"))

        // 开启增量补丁脚本时注入 __cfPatchSvg 与渐显样式
        val docHtml = buildSvgHtmlDocument(
            svg = cleanPreview,
            lightCanvas = true,
            transparentBg = true,
            injectBridge = false,
            enableIncrementalPatch = true,
        )
        assertTrue(docHtml.contains("__cfPatchSvg"))
        assertTrue(docHtml.contains("cf-svg-in"))
    }
}
