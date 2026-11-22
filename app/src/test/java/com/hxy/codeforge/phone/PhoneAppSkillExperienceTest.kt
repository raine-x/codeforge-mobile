package com.hxy.codeforge.phone

import com.hxy.codeforge.phone.PhoneAppSkillStore.EXPERIENCE_HEADING
import com.hxy.codeforge.phone.PhoneAppSkillStore.MAX_EXPERIENCE_LINES
import com.hxy.codeforge.phone.PhoneAppSkillStore.MAX_LESSON_CHARS
import com.hxy.codeforge.phone.PhoneAppSkillStore.MAX_RECENT_FLOWS
import com.hxy.codeforge.phone.PhoneAppSkillStore.MAX_SKILL_CHARS
import com.hxy.codeforge.phone.PhoneAppSkillStore.RECENT_FLOW_HEADING
import com.hxy.codeforge.phone.PhoneAppSkillStore.buildSkillIndex
import com.hxy.codeforge.phone.PhoneAppSkillStore.mergeExperience
import com.hxy.codeforge.phone.PhoneAppSkillStore.mergeRecentFlow
import com.hxy.codeforge.phone.PhoneAppSkillStore.packageSkillId
import com.hxy.codeforge.phone.PhoneAppSkillStore.pruneExperience
import com.hxy.codeforge.phone.PhoneAppSkillStore.readSkillLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 应用技能「经验沉淀」合并逻辑（纯函数，不依赖 Android）。 */
class PhoneAppSkillExperienceTest {

    private val base = "# 微信\n\n1. 点击右上角搜索。"

    @Test
    fun `首次追加会新建经验小节且保留原文`() {
        val r = mergeExperience(base, "coords", "搜索框约在 x≈0.5, y≈0.08", "2026-11-21")
        assertTrue(r.added)
        assertEquals(1, r.lessonCount)
        assertTrue(r.content.startsWith(base))
        assertTrue(r.content.contains(EXPERIENCE_HEADING))
        assertTrue(r.content.contains("- [coords][2026-11-21] 搜索框约在 x≈0.5, y≈0.08"))
    }

    @Test
    fun `同义重复不再写入`() {
        val first = mergeExperience(base, "tip", "发送前要等键盘收起", "2026-11-21")
        val dup = mergeExperience(first.content, "pitfall", "发送前  要等键盘收起", "2026-11-22")
        assertFalse(dup.added)
        assertEquals(first.content, dup.content)
        assertEquals(1, dup.lessonCount)
    }

    @Test
    fun `未知类型回落为 tip`() {
        val r = mergeExperience(base, "weird", "一条经验", "2026-11-21")
        assertTrue(r.content.contains("- [tip][2026-11-21] 一条经验"))
    }

    @Test
    fun `多次追加按顺序累积`() {
        var c = base
        c = mergeExperience(c, "tip", "甲", "2026-11-21").content
        c = mergeExperience(c, "flow", "乙", "2026-11-21").content
        val r = mergeExperience(c, "pitfall", "丙", "2026-11-21")
        assertEquals(3, r.lessonCount)
        val lines = r.content.lines().filter { it.startsWith("- [") }
        assertEquals(listOf("甲", "乙", "丙"), lines.map { it.substringAfterLast("] ") })
    }

    @Test
    fun `条数超过上限时丢最旧的`() {
        var c = base
        for (i in 1..(MAX_EXPERIENCE_LINES + 5)) {
            c = mergeExperience(c, "tip", "经验$i", "2026-11-21").content
        }
        val lines = c.lines().filter { it.startsWith("- [") }
        assertEquals(MAX_EXPERIENCE_LINES, lines.size)
        assertFalse(lines.any { it.endsWith("经验1") })
        assertTrue(lines.last().endsWith("经验${MAX_EXPERIENCE_LINES + 5}"))
    }

    @Test
    fun `单条经验会被截断到上限`() {
        val r = mergeExperience(base, "tip", "长".repeat(MAX_LESSON_CHARS + 100), "2026-11-21")
        val line = r.content.lines().first { it.startsWith("- [") }
        assertEquals(MAX_LESSON_CHARS, line.substringAfterLast("] ").length)
    }

    @Test
    fun `全文超限时先丢旧经验并保留新写入的一条`() {
        val huge = "正文".repeat(MAX_SKILL_CHARS / 2 - 100)
        var c = huge
        repeat(10) { c = mergeExperience(c, "tip", "经验-$it", "2026-11-21").content }
        assertTrue(c.length <= MAX_SKILL_CHARS + 200)
        assertTrue(c.contains("经验-9"))
    }

    @Test
    fun `经验小节之后的其他章节原样保留`() {
        val withTail = base + "\n\n" + EXPERIENCE_HEADING + "\n- [tip][2026-01-01] 旧\n\n## 附录\n保留我"
        val r = mergeExperience(withTail, "tip", "新", "2026-11-21")
        assertTrue(r.content.contains("## 附录\n保留我"))
        assertEquals(2, r.lessonCount)
        assertTrue(r.content.indexOf("新") < r.content.indexOf("## 附录"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `空经验直接拒绝`() {
        mergeExperience(base, "tip", "   ", "2026-11-21")
    }

    @Test
    fun `行号索引与按行精准读取对齐`() {
        val doc = """
            # 微信操作
            1. 打开微信
            2. 点击发现

            $EXPERIENCE_HEADING
            - [coords][2026-11-20] 搜索入口在 (0.85, 0.06)
            - [pitfall][2026-11-20] 旧版发送按钮无效

            $RECENT_FLOW_HEADING
            - [2026-11-21] 打开应用 → 点「搜索」
        """.trimIndent()
        val idx = buildSkillIndex(doc)
        assertEquals(3, idx.sections.size)
        assertEquals(1, idx.sections[0].startLine)
        val pitfallEntry = idx.entries.first { it.tag == "pitfall" }
        assertTrue(pitfallEntry.preview.contains("旧版发送按钮无效"))

        val (s, e, slice) = readSkillLines(doc, pitfallEntry.line, pitfallEntry.line)
        assertEquals(pitfallEntry.line, s)
        assertEquals(pitfallEntry.line, e)
        assertTrue(slice.startsWith("${pitfallEntry.line}: "))
        assertTrue(slice.contains("旧版发送按钮无效"))
    }

    @Test
    fun `支持按精确行号与关键词原地替换过时经验`() {
        var doc = mergeExperience(base, "coords", "旧搜索位置 (0.50, 0.10)", "2026-11-01").content
        doc = mergeExperience(doc, "pitfall", "普通点击筛选失效", "2026-11-01").content
        val idx = buildSkillIndex(doc)
        val oldLine = idx.entries.first { it.preview.contains("旧搜索位置") }.line

        val r1 = mergeExperience(doc, "coords", "新版搜索在右上角 (0.88, 0.06)", "2026-11-22", targetLine = oldLine)
        assertTrue(r1.replaced)
        assertEquals(2, r1.lessonCount)
        assertFalse(r1.content.contains("旧搜索位置"))
        assertTrue(r1.content.contains("新版搜索在右上角"))

        val r2 = mergeExperience(r1.content, "pitfall", "筛选需传 prefer_gesture=true", "2026-11-22", replaces = "普通点击筛选")
        assertTrue(r2.replaced)
        assertEquals(2, r2.lessonCount)
        assertFalse(r2.content.contains("普通点击筛选失效"))
    }

    @Test
    fun `支持按行号或关键词精准删除失效条目且不误删标题`() {
        var doc = mergeExperience(base, "tip", "条目一", "2026-11-20").content
        doc = mergeExperience(doc, "tip", "过时条目二", "2026-11-20").content
        val idx = buildSkillIndex(doc)
        val targetLine = idx.entries.first { it.preview.contains("过时条目二") }.line

        val (after1, count1) = pruneExperience(doc, targetLine = targetLine)
        assertEquals(1, count1)
        assertFalse(after1.contains("过时条目二"))
        assertTrue(after1.contains("条目一"))

        val (after2, count2) = pruneExperience(after1, match = "条目一")
        assertEquals(1, count2)
        assertFalse(after2.contains("条目一"))
        assertTrue(after2.contains(base))
    }

    @Test
    fun `最近成功路径保留最新上限且不影响经验小节`() {
        var doc = mergeExperience(base, "coords", "固定入口", "2026-11-20").content
        for (i in 1..(MAX_RECENT_FLOWS + 2)) {
            doc = mergeRecentFlow(doc, "路径$i → 完成", "2026-11-22")
        }
        assertTrue(doc.contains(EXPERIENCE_HEADING))
        assertTrue(doc.contains("固定入口"))
        assertTrue(doc.contains(RECENT_FLOW_HEADING))
        val flows = doc.substringAfter(RECENT_FLOW_HEADING).lines().filter { it.startsWith("- [") }
        assertEquals(MAX_RECENT_FLOWS, flows.size)
        assertFalse(flows.any { it.contains("路径1 ") })
        assertTrue(flows.last().contains("路径${MAX_RECENT_FLOWS + 2}"))
    }

    @Test
    fun `按包名生成规范化技能 id`() {
        assertEquals("pkg-com-tencent-mm", packageSkillId("com.tencent.mm"))
    }
}
