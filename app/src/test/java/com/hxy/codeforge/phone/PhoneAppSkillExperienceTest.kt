package com.hxy.codeforge.phone

import com.hxy.codeforge.phone.PhoneAppSkillStore.EXPERIENCE_HEADING
import com.hxy.codeforge.phone.PhoneAppSkillStore.MAX_EXPERIENCE_LINES
import com.hxy.codeforge.phone.PhoneAppSkillStore.MAX_LESSON_CHARS
import com.hxy.codeforge.phone.PhoneAppSkillStore.MAX_SKILL_CHARS
import com.hxy.codeforge.phone.PhoneAppSkillStore.mergeExperience
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
}
