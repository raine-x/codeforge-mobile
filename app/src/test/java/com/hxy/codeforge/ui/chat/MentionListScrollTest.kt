package com.hxy.codeforge.ui.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** `@` 提及候选列表可滚动且不硬截断的源码守护测试。 */
class MentionListScrollTest {
    @Test
    fun completionPlacesCursorAfterSpaceAndPreservesFollowingText() {
        for (suffix in listOf("", "请继续")) {
            val result = completeMention(TextFieldValue("@ph" + suffix, TextRange(3)), "phone")
            assertEquals("@phone " + suffix, result.text)
            assertEquals(TextRange(7), result.selection)
        }
    }

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
