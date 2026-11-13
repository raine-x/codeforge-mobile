package com.hxy.codeforge.core

import com.hxy.codeforge.ui.settings.REVERSE_MODIFY_DISCLAIMER_SECONDS
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 校验内置插件列表 JSON 解析（含 `allow_modify` 的 P-031 三态语义）与免责协议倒计时常量。
 */
class BuiltinPluginInfoTest {

    @Test
    fun `allow_modify 遵循三态语义：缺失与 null 为 null，布尔值按原值保留`() {
        val reverseDefault = parseBuiltinPluginInfo(
            JSONObject("""{"id":"reverse_analysis","name":"逆向分析","enabled":true,"allow_modify":false}"""),
        )!!
        assertEquals(false, reverseDefault.allowModify)
        assertEquals(true, reverseDefault.enabled)

        val reverseEnabled = parseBuiltinPluginInfo(
            JSONObject("""{"id":"reverse_analysis","name":"逆向分析","enabled":true,"allow_modify":true}"""),
        )!!
        assertEquals(true, reverseEnabled.allowModify)

        val otherOmitted = parseBuiltinPluginInfo(
            JSONObject("""{"id":"plan","name":"计划模式","enabled":true}"""),
        )!!
        assertNull(otherOmitted.allowModify)

        val otherExplicitNull = parseBuiltinPluginInfo(
            JSONObject("""{"id":"plan","name":"计划模式","enabled":false,"allow_modify":null}"""),
        )!!
        assertNull(otherExplicitNull.allowModify)
    }

    @Test
    fun `逆向修改能力免责协议强制阅读至少 5 秒`() {
        assertEquals(5, REVERSE_MODIFY_DISCLAIMER_SECONDS)
    }
}
