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

    @Test
    fun `choices never auto-send by default and support multi-select, custom note, and open bottom buttons`() {
        val json = """
            {
              "kind": "choices",
              "prompt": "需要哪些模块？",
              "mode": "multi",
              "options": [
                {"id": "auth", "label": "登录鉴权", "value": "登录鉴权"},
                {"id": "pay", "label": "支付", "value": "支付"},
                {"id": "log", "label": "审计日志", "value": "审计日志"}
              ],
              "buttons": [
                {"label": "先填入输入框", "action": "prefill", "payload": "按以上需求补充："}
              ]
            }
        """.trimIndent()
        val spec = parseInteractiveUiSpec(json)!!
        val choices = spec.blocks.single() as UiBlock.Choices
        assertEquals("multi", choices.mode)
        assertFalse(choices.autoSend)
        assertTrue(choices.allowCustom)
        assertEquals(1, choices.actions.size)
        assertEquals("prefill", choices.actions.single().action)

        // 多选 + 自定义补充
        assertEquals(
            "需要哪些模块？：登录鉴权、审计日志；补充：兼容离线模式",
            formatChoicesReply(choices, listOf("auth", "log"), "兼容离线模式"),
        )
        // 仅填自定义内容也能正常产出
        assertEquals(
            "需要哪些模块？：先只做原型验证",
            formatChoicesReply(choices, emptyList(), "先只做原型验证"),
        )

        // 即使旧模型漏传 auto_send 或显式写 auto_send:true，单选也不直接发送
        val singleSpec = parseInteractiveUiSpec(
            """{"kind":"choices","prompt":"选语言","mode":"single","auto_send":true,"options":["Kotlin","Go"]}""",
        )!!
        val singleChoices = singleSpec.blocks.single() as UiBlock.Choices
        assertFalse(singleChoices.autoSend)
        assertTrue(singleChoices.allowCustom)

        // 卡片底部开放按钮会自动附带前面的选项与表单内容，也支持 include_selections=false 独立动作
        val merged = composeCardActionPayload(
            item = UiActionItem(label = "生成方案", action = "send", payload = "请据此给出目录结构"),
            choiceSummaries = listOf("选语言：Kotlin"),
            formSummaries = listOf("- 启用缓存：是"),
        )
        assertTrue(merged.contains("选语言：Kotlin"))
        assertTrue(merged.contains("- 启用缓存：是"))
        assertTrue(merged.contains("请据此给出目录结构"))

        val standalone = composeCardActionPayload(
            item = UiActionItem(label = "跳过", action = "send", payload = "由你直接推荐默认方案", includeSelections = false),
            choiceSummaries = listOf("选语言：Kotlin"),
            formSummaries = emptyList(),
        )
        assertEquals("由你直接推荐默认方案", standalone)
    }

    @Test
    fun `parses form select with id-label objects, resolves id default, and preserves out-of-range slider default`() {
        val json = """
            {
              "title": "收敛到最终方向",
              "blocks": [
                {
                  "kind": "form",
                  "submit_label": "生成最终方案",
                  "submit_action": "send",
                  "fields": [
                    {"id": "person", "type": "text", "label": "你是谁", "default": ""},
                    {
                      "id": "form",
                      "type": "select",
                      "label": "你能交付的形态",
                      "options": [
                        {"id": "html", "label": "只能交单文件 HTML 网页"},
                        {"id": "hw", "label": "能做实物硬件（Arduino/ESP32/传感器）"},
                        {"id": "both", "label": "两者都行", "value": "HTML和实物都可以"}
                      ],
                      "default": "hw"
                    },
                    {"id": "time", "type": "slider", "label": "可用天数", "default": 28},
                    {"id": "budget", "type": "slider", "label": "预算上限", "default": 500}
                  ]
                }
              ]
            }
        """.trimIndent()

        val spec = parseInteractiveUiSpec(json)
        assertNotNull(spec)
        val form = spec!!.blocks.single() as UiBlock.Form
        assertEquals("生成最终方案", form.submitLabel)
        assertEquals("send", form.submitAction)
        assertEquals(4, form.fields.size)

        val select = form.fields[1] as UiFormField.Select
        assertEquals(
            listOf("只能交单文件 HTML 网页", "能做实物硬件（Arduino/ESP32/传感器）", "两者都行"),
            select.options,
        )
        assertFalse(select.options.any { it.startsWith("{") })
        assertEquals("能做实物硬件（Arduino/ESP32/传感器）", select.default)
        assertEquals("HTML和实物都可以", select.valuesByOption["两者都行"])

        val daysSlider = form.fields[2] as UiFormField.NumberSlider
        assertEquals(0f, daysSlider.min)
        assertEquals(100f, daysSlider.max)
        assertEquals(28f, daysSlider.default)

        val budgetSlider = form.fields[3] as UiFormField.NumberSlider
        assertEquals(0f, budgetSlider.min)
        assertEquals(500f, budgetSlider.max)
        assertEquals(500f, budgetSlider.default)
    }
}
