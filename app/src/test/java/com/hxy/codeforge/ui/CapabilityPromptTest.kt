package com.hxy.codeforge.ui

import com.hxy.codeforge.ui.chat.parseMediaJumpTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 渲染能力提示词的守护测试。
 *
 * ## 为什么要有这个测试
 *
 * 早先这段注入只讲「内嵌可交互网页」，还给了一份适用范围清单（试题、图表、演示、看板……）。
 * 能力面被写窄之后，模型把本该走原生通道的内容也包成网页 —— 典型是「找到一个视频链接」
 * 时写一整页 HTML，而不是用宿主的原生播放器或音视频跳转卡片。
 *
 * 提示词是纯文本，没有编译期约束：**宿主新增一种呈现通道时，若提示词没跟上，
 * 模型就永远不知道它能用**。所以这里把「通道清单」钉死。
 */
class CapabilityPromptTest {

    // ------------------------------------------------------------ 标记一致性

    /**
     * 正文首行必须是定位标记 —— 回放时靠它把注入段从用户气泡里切掉（P-042）。
     * 正文改了首行而标记没改，整段提示词会漏回用户气泡。
     */
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

    // ------------------------------------------------------------ 通道契约

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

    // ------------------------------------------------------------ 去偏向

    /**
     * 早先的「适用范围」清单是偏向的根源：它把试题、图表、看板一律划给网页。
     * 这类清单必须不再出现 —— 改成按内容路由的「怎么选」段。
     */
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

    /**
     * 网页的专属约束必须挂在「仅写网页时适用」之下。
     *
     * 早先这些约束是全局的，隐含「要富展示就写 HTML」；条件化之后网页才只是
     * 「需要动手交互」时的一个选项。
     */
    @Test
    fun htmlConstraintsAreConditional() {
        assertTrue(
            "网页约束必须条件化",
            CLIENT_CAPABILITY_PROMPT.contains("仅写网页时适用"),
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

    // ------------------------------------------------------------ 提示词 ↔ 实现

    /**
     * 提示词里给出的音视频跳转示例，必须能被客户端**真实的解析函数**认出来。
     *
     * 这条是本文件最有价值的一条：提示词与实现是两处独立的文本，
     * 写错字面量（多一个括号、写成 Markdown 链接形态）不会有任何编译期报错，
     * 只会让模型输出的标签渲染不出来。
     * 实测踩过：提示词写「[ ▶视频 地址 ]」，模型理解成 Markdown 链接，
     * 输出成 `[ ▶视频 描述 ](url)` —— 标签没渲染成卡片，只剩一个普通链接。
     */
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
