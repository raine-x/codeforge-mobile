package com.hxy.codeforge.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * @ 提及候选列表必须能滚动 —— 这条测试守住「别把列表改回不可滚」。
 *
 * ## 为什么需要守护
 *
 * 用户反馈：「@补全弹出的窗口怎么不支持滑动？」根因有两层：
 *  1. 候选被 `.take(4)` 硬截断 —— 只出 4 条，永远「装得下」，也就没有可滑的内容；
 *  2. 列表用普通 `Column` + `forEach` —— 一旦放开条数上限，没有高度上限也没有滚动，
 *     会把整张输入卡顶出屏幕。
 *
 * 现在两条都改了：候选不再截断（留 50 条兜底），列表改 `LazyColumn` + `heightIn` 上限。
 * 这类「手感回归」很容易在重构中被顺手改回去，所以用源码断言把它钉住。
 */
class MentionListScrollTest {

    private val composer: File = File("src/main/java/com/hxy/codeforge/ui/chat/Composer.kt")

    private fun source(): String {
        assertTrue("找不到 Composer.kt：${composer.absolutePath}", composer.isFile)
        return composer.readText()
    }

    @Test
    fun `候选列表用 LazyColumn 而不是普通 Column`() {
        val text = source()
        assertTrue(
            "@ 候选列表应使用 LazyColumn（普通 Column 无法滚动）",
            text.contains("LazyColumn("),
        )
    }

    @Test
    fun `候选列表有高度上限`() {
        val text = source()
        assertTrue(
            "@ 候选列表应设高度上限（heightIn），否则会把输入卡顶出屏幕",
            text.contains("heightIn(max = 240.dp)"),
        )
    }

    @Test
    fun `候选不再被截断成 4 条`() {
        val text = source()
        // 只看 mentionMatches 的计算块 —— 附件/元素芯片另有 take(4)（那里是刻意的）
        val block = text.substringAfter("val mentionMatches")
            .substringBefore("fun insertMention")
        assertFalse(
            "@ 候选不应再被 take(4) 截断 —— 装得多的技能/插件根本补不出来",
            block.contains("take(4)"),
        )
    }
}
