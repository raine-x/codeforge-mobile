package com.hxy.codeforge.phone

import com.hxy.codeforge.ui.chat.toolLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手机操控「应用技能库」解析、匹配、渐进式摘要与 MCP 工具契约单测。
 */
class PhoneAppSkillStoreTest {

    @Test
    fun `parses markdown frontmatter with inline and block yaml lists`() {
        val md = """
            ---
            id: wechat-demo
            name: 微信
            description: 微信搜索联系人与发送消息规范
            packages: [com.tencent.mm, com.tencent.wework.*]
            app_names:
              - 微信
              - WeChat
            tags: [社交, 聊天]
            priority: 95
            ---

            # 微信操控步骤

            1. 点击右上角搜索图标。
        """.trimIndent()

        val parsed = PhoneAppSkillStore.parseSkillDocument(
            raw = md,
            fallbackId = "fallback",
            source = PhoneAppSkillSource.BUILTIN,
        )
        assertNotNull(parsed)
        val skill = parsed!!
        assertEquals("wechat-demo", skill.id)
        assertEquals("微信", skill.name)
        assertEquals("微信搜索联系人与发送消息规范", skill.description)
        assertEquals(listOf("com.tencent.mm", "com.tencent.wework.*"), skill.packages)
        assertEquals(listOf("微信", "WeChat"), skill.appNames)
        assertEquals(listOf("社交", "聊天"), skill.tags)
        assertEquals(95, skill.priority)
        assertTrue(skill.content.contains("点击右上角搜索图标"))
    }

    @Test
    fun `parses pure yaml skill document cleanly`() {
        val yaml = """
            id: custom-clock
            name: 时钟
            description: 优先使用 calendar_reminder 设置闹钟
            packages: [com.android.deskclock]
            app_names: [时钟, Clock]
            priority: 88
            content: |
              1. 先调用 phone.calendar_reminder(action="set_alarm")
              2. 若系统不支持再打开时钟界面
        """.trimIndent()

        val skill = PhoneAppSkillStore.parseSkillDocument(
            raw = yaml,
            fallbackId = "custom-clock",
            source = PhoneAppSkillSource.CUSTOM,
        )
        assertNotNull(skill)
        assertEquals("custom-clock", skill!!.id)
        assertEquals(88, skill.priority)
        assertTrue(skill.content.contains("set_alarm"))
    }

    @Test
    fun `summary json exposes metadata only without leaking full content`() {
        val skill = PhoneAppSkill(
            id = "amap",
            name = "高德地图",
            description = "高德地点搜索与路线规划要点",
            packages = listOf("com.autonavi.minimap"),
            appNames = listOf("高德地图"),
            tags = listOf("地图"),
            priority = 90,
            content = "很长的操作正文步骤 1 2 3 4 5",
        )
        val arr = PhoneAppSkillStore.toSummaryJsonArray(listOf(skill))
        assertEquals(1, arr.length())
        val obj = arr.getJSONObject(0)
        assertEquals("amap", obj.getString("id"))
        assertEquals("高德地图", obj.getString("name"))
        assertEquals("高德地点搜索与路线规划要点", obj.getString("description"))
        assertFalse("渐进式摘要不应直接暴露全文 content", obj.has("content"))

        val detail = PhoneAppSkillStore.toDetailJson(skill)
        assertTrue(detail.getString("content").contains("很长的操作正文步骤"))
    }

    @Test
    fun `registry and chinese tool labels include app_skill and ui_batch`() {
        val def = PhoneToolRegistry.find("app_skill")
        assertNotNull(def)
        val batchDef = PhoneToolRegistry.find("ui_batch")
        assertNotNull(batchDef)
        assertEquals(20, PhoneToolRegistry.tools.size)
        assertEquals("检索应用技能库", toolLabel("app_skill", """{"action":"list"}"""))
        assertEquals("读取应用技能「wechat」", toolLabel("app_skill", """{"action":"get","id":"wechat"}"""))
        assertEquals("沉淀新的应用操作技能", toolLabel("app_skill", """{"action":"save"}"""))
        assertEquals("连贯执行同页多步手机操作", toolLabel("ui_batch", """{"steps":[{"type":"click"}]}"""))
    }

    @Test
    fun `random click delay and safe coordinate jitter stay within bounds`() {
        val cfgFast = PhoneControlConfig(
            randomClickDelayEnabled = true,
            clickDelayPreset = PhoneClickDelayPreset.FAST,
        )
        repeat(20) {
            val pre = PhoneControlPrefs.nextRandomPreClickDelayMs(cfgFast)
            val hold = PhoneControlPrefs.nextRandomTapHoldMs(cfgFast)
            assertTrue(pre in 60L..160L)
            assertTrue(hold in 85L..125L)
            val x = PhoneControlPrefs.jitterCoordinateInBounds(100f, 60, 140, enabled = true, maxJitterPx = 3f)
            assertTrue(x in 66f..134f)
        }
        val cfgOff = PhoneControlConfig(randomClickDelayEnabled = false)
        assertEquals(0L, PhoneControlPrefs.nextRandomPreClickDelayMs(cfgOff))
        assertEquals(105L, PhoneControlPrefs.nextRandomTapHoldMs(cfgOff))
        assertEquals(100f, PhoneControlPrefs.jitterCoordinateInBounds(100f, 60, 140, enabled = false), 0.001f)
    }
}
