package com.hxy.codeforge.ui

import com.hxy.codeforge.ui.chat.parseMediaJumpTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 渲染能力提示词回归测试：校验注入标记一致、通道清单完整及去偏向路由规则。 */
class CapabilityPromptTest {

    /** 正文须以 [INJECT_CAPABILITY_MARKER] 开头，回放历史才能剥离注入段（见 P-042）。 */
    @Test
    fun promptStartsWithCapabilityMarker() {
        assertTrue(
            "提示词正文必须以定位标记开头，否则回放时切不掉注入段",
            CLIENT_CAPABILITY_PROMPT.startsWith(INJECT_CAPABILITY_MARKER),
        )
    }

    /** 真实提示词拼进上行文本后必须能被完整剥离。 */
    @Test
    fun realPromptIsStrippedFromUserText() {
        val wire = "找段视频\n\n" + CLIENT_CAPABILITY_PROMPT
        assertTrue("注入段必须能被剥离", stripInjectedText(wire) == "找段视频")
    }

    // 通道契约

    /**
     * 宿主支持的每种呈现通道都必须在提示词里出现。
     *
     * 漏一条 = 模型不知道有这条路 = 它会退回到「写个网页」这个万能解法。
     */
    @Test
    fun everyNativeChannelIsAdvertised() {
        val required = listOf(
            "```image",   // 图片围栏
            "```video",   // 视频围栏
            "```audio",   // 音频围栏
            "```svg",     // 矢量图围栏
            "```ui",      // 原生交互卡围栏
            "data-cf-send", // 声明式点击回传
            "```html",    // 网页围栏
            "[ ▶视频",     // 音视频跳转卡片
            "[ ▶音频",
            "![说明]",    // markdown 图片语法
            "表格",        // GFM 表格
            "LaTeX",       // 公式
        )
        for (r in required) {
            assertTrue("提示词缺少通道：$r", CLIENT_CAPABILITY_PROMPT.contains(r))
        }
    }

    /** 相对路径是宿主附件的落点格式（`/api/upload_file` 返回的相对路径），必须写明。 */
    @Test
    fun workspaceRelativePathIsMentioned() {
        assertTrue(
            "提示词必须说明可用工作区相对路径（如 attachments/a.png）",
            CLIENT_CAPABILITY_PROMPT.contains("attachments/a.png"),
        )
    }

    /** 验证偏向性适用范围清单已移除，改为按内容路由。 */
    @Test
    fun scopeListIsGone() {
        for (gone in listOf(
            "适用范围",
            "均可使用此能力",
            "前端呈现与用户交互",
            "输出方式（二者效果一致",
        )) {
            assertFalse("提示词不该再有偏向性清单：$gone", CLIENT_CAPABILITY_PROMPT.contains(gone))
        }
    }

    /** 只宣告「网页」一种能力的开场白是旧版特征，新版必须先讲「按格式选」。 */
    @Test
    fun openingIsNotHtmlOnly() {
        assertFalse(
            "开场不再只讲网页",
            CLIENT_CAPABILITY_PROMPT.contains("本客户端支持在对话中内嵌可交互网页"),
        )
        assertTrue(
            "开场必须点明「按格式渲染、不同内容用不同格式」",
            CLIENT_CAPABILITY_PROMPT.contains("不同内容用不同格式"),
        )
    }

    /** 验证网页专属约束条件化挂载，避免误导普通内容优先使用 HTML。 */
    @Test
    fun htmlConstraintsAreConditional() {
        assertTrue(
            "网页约束必须条件化",
            CLIENT_CAPABILITY_PROMPT.contains("仅写网页时适用"),
        )
        assertTrue(
            "SVG 约束必须条件化且包含 viewBox、透明底融入主题与移动端字号要求",
            CLIENT_CAPABILITY_PROMPT.contains("仅写 SVG 时适用") &&
                CLIENT_CAPABILITY_PROMPT.contains("viewBox") &&
                CLIENT_CAPABILITY_PROMPT.contains("重叠") &&
                CLIENT_CAPABILITY_PROMPT.contains("透明") &&
                CLIENT_CAPABILITY_PROMPT.contains("≥14px"),
        )
        assertTrue(
            "必须包含工具调用与执行协同规范",
            CLIENT_CAPABILITY_PROMPT.contains("【工具调用与执行协同规范】") &&
                CLIENT_CAPABILITY_PROMPT.contains("并发调用") &&
                CLIENT_CAPABILITY_PROMPT.contains("精确替换"),
        )
    }

    /** 否定式禁令会把模型锚定到那个失败形态；路由段用肯定句表达「X 用 Y」。 */
    @Test
    fun routingIsStatedPositively() {
        assertTrue(
            "必须给出「怎么选」的路由段",
            CLIENT_CAPABILITY_PROMPT.contains("怎么选"),
        )
        assertTrue(
            "视频必须给出原生通道路由",
            CLIENT_CAPABILITY_PROMPT.contains("放一段视频或音频"),
        )
    }

    /** 验证提示词中的音视频跳转示例与 [parseMediaJumpTag] 语法一致。 */
    @Test
    fun mediaJumpExampleInPromptParsesWithRealParser() {
        val example = "[ ▶视频 地址 ]"
        assertTrue(
            "提示词里必须给出这个示例",
            CLIENT_CAPABILITY_PROMPT.contains(example),
        )
        val parsed = parseMediaJumpTag(example)
        assertTrue("示例必须能被真实解析函数认出，实际=$parsed", parsed != null)
        assertEquals("视频", parsed?.first)
        assertEquals("地址", parsed?.second)
    }

    /** 反例：Markdown 链接形态不是标签，解析函数必须不认（否则会误吞正常链接）。 */
    @Test
    fun markdownLinkFormIsNotMistakenForMediaTag() {
        val parsed = parseMediaJumpTag("[ ▶视频 描述文字 ](https://example.com/a.mp4)")
        assertNull("Markdown 链接形态不该被当成标签，实际解析出=$parsed", parsed)
    }
}
