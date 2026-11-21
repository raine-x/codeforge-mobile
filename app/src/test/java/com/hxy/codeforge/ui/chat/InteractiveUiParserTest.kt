package com.hxy.codeforge.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 原生交互卡 ` ```ui ` JSON 解析与交互式 SVG 判定纯 JVM 单测。 */
class InteractiveUiParserTest {

    @Test
    fun `parses composite ui card with choices, tabs, form, and actions`() {
        val json = """
            {
              "title": "选择部署方案",
              "subtitle": "点击选项或填写参数后直接回发",
              "blocks": [
                {
                  "kind": "choices",
                  "prompt": "你倾向哪种架构？",
                  "mode": "single",
                  "auto_send": false,
                  "confirm_label": "确定方案",
                  "options": [
                    {"id": "a", "label": "单体优先", "desc": "交付快", "value": "选单体"},
                    {"id": "b", "label": "模块化", "desc": "易扩展", "value": "选模块化"}
                  ]
                },
                {
                  "kind": "tabs",
                  "items": [
                    {"label": "方案A", "body": "低成本", "pros": ["快"], "cons": ["耦合"], "select_payload": "用方案A"}
                  ]
                },
                {
                  "kind": "form",
                  "submit_label": "应用",
                  "submit_action": "prefill",
                  "fields": [
                    {"id": "cache", "type": "toggle", "label": "启用缓存", "default": true},
                    {"id": "workers", "type": "slider", "label": "并发数", "min": 1, "max": 8, "step": 1, "default": 4, "unit": "个"},
                    {"id": "region", "type": "select", "label": "区域", "options": ["华东", "华南"], "default": "华东"}
                  ]
                },
                {
                  "kind": "actions",
                  "items": [
                    {"label": "直接生成代码", "action": "send", "payload": "请直接生成代码", "style": "primary"}
                  ]
                }
              ]
            }
        """.trimIndent()

        val spec = parseInteractiveUiSpec(json)
        assertNotNull(spec)
        assertEquals("选择部署方案", spec!!.title)
        assertEquals(4, spec.blocks.size)

        val choices = spec.blocks[0] as UiBlock.Choices
        assertEquals("single", choices.mode)
        assertFalse(choices.autoSend)
        assertEquals("确定方案", choices.confirmLabel)
        assertEquals(2, choices.options.size)

        val tabs = spec.blocks[1] as UiBlock.Tabs
        assertEquals("用方案A", tabs.items.single().selectPayload)

        val form = spec.blocks[2] as UiBlock.Form
        assertEquals("prefill", form.submitAction)
        assertEquals(3, form.fields.size)

        val actions = spec.blocks[3] as UiBlock.Actions
        assertEquals("send", actions.items.single().action)
        assertEquals("primary", actions.items.single().style)
    }

    @Test
    fun `parses top-level single block without blocks wrapper and quiz mode`() {
        val json = """
            {
              "kind": "quiz",
              "prompt": "Android Binder 跨进程拷贝几次？",
              "options": [
                {"label": "1次", "correct": true, "explain": "mmap 映射共享内核缓冲区"},
                {"label": "2次", "correct": false, "explain": "传统 IPC 才需要 2 次"}
              ]
            }
        """.trimIndent()

        val spec = parseInteractiveUiSpec(json)
        assertNotNull(spec)
        val quiz = spec!!.blocks.single() as UiBlock.Choices
        assertEquals("quiz", quiz.mode)
        assertEquals(true, quiz.options[0].correct)
        assertEquals(false, quiz.options[1].correct)
    }

    @Test
    fun `returns null on invalid json or empty blocks`() {
        assertNull(parseInteractiveUiSpec("not json"))
        assertNull(parseInteractiveUiSpec("""{"title":"空"}"""))
        assertNull(parseInteractiveUiSpec("""{"kind":"unknown"}"""))
    }

    @Test
    fun `detects interactive vs static svg`() {
        assertFalse(isInteractiveSvg("""<svg viewBox="0 0 100 100"><rect width="50" height="50"/></svg>"""))
        assertTrue(isInteractiveSvg("""<svg viewBox="0 0 100 100"><g data-cf-send="选A"><rect/></g></svg>"""))
        assertTrue(isInteractiveSvg("""<svg viewBox="0 0 100 100"><g data-cf-prefill="展开讲"><rect/></g></svg>"""))
    }
}
