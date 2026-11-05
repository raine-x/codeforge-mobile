package com.hxy.codeforge.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 工具文案规则，对照 web/dist/ui.js 的 toolLabel()。 */
class ToolLabelTest {

    @Test
    fun `文件类工具带文件名`() {
        assertEquals(
            "读取了文件 server.go",
            toolLabel("read_file", """{"path":"pkg/server/server.go"}"""),
        )
        assertEquals(
            "编辑 server.go",
            toolLabel("edit_file", """{"path":"/a/b/server.go"}"""),
        )
        assertEquals(
            "创建 new.kt",
            toolLabel("write_file", """{"path":"C:\\\\x\\\\new.kt"}"""),
        )
    }

    @Test
    fun `目录与搜索用固定短语`() {
        assertEquals("查看项目", toolLabel("list_dir", """{"path":"."}"""))
        assertEquals("搜索项目", toolLabel("search_files", """{"pattern":"foo"}"""))
    }

    @Test
    fun `命令类显示命令本身`() {
        assertEquals("运行 npm test", toolLabel("run_command", """{"command":"npm test"}"""))
    }

    @Test
    fun `插件前缀按本名匹配`() {
        assertEquals(
            "搜索了 kotlin coroutines",
            toolLabel("parallel_search.web_search", """{"query":"kotlin coroutines"}"""),
        )
        assertEquals(
            "读取了网页 https://example.com",
            toolLabel("web_fetch_exa", """{"url":"https://example.com"}"""),
        )
    }

    @Test
    fun `未知工具绝不暴露工具ID`() {
        val label = toolLabel("some_internal_tool_v2", """{"path":"x"}""")
        assertEquals("调用了外部能力", label)
        assertFalse("不能把工具 ID 摊给用户：$label", label.contains("some_internal"))
    }

    @Test
    fun `名称为空也不崩`() {
        assertEquals("调用了外部能力", toolLabel(null, null))
        assertEquals("调用了外部能力", toolLabel("", ""))
    }

    @Test
    fun `目标过长时截断`() {
        val long = "a".repeat(200)
        val label = toolLabel("read_file", """{"path":"$long"}""")
        assert(label.length < 90) { "文案应截断，实际长度 ${label.length}" }
    }
}
