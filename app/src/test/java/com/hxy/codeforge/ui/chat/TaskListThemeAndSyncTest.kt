package com.hxy.codeforge.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守住「任务清单弹窗主题适配 + 主动/实时同步」回归边界：
 *  1. Theme.kt 的 LightColors / DarkColors 必须配齐 surfaceContainer*，避免 M3 默认淡紫色；
 *  2. AppRoot.kt 统一挂载带主题描边与实底的 TaskListDialog，不再使用裸 AlertDialog TodoSheet；
 *  3. 打开任务清单与重连/前后台切换时触发 pullTodos() 对齐内核最新状态。
 */
class TaskListThemeAndSyncTest {

    private val themeFile = File("src/main/java/com/hxy/codeforge/ui/theme/Theme.kt")
    private val appRootFile = File("src/main/java/com/hxy/codeforge/ui/AppRoot.kt")
    private val stripsFile = File("src/main/java/com/hxy/codeforge/ui/chat/StatusStrips.kt")
    private val vmFile = File("src/main/java/com/hxy/codeforge/ui/ChatViewModel.kt")

    @Test
    fun `Theme 配色覆盖 surfaceContainer 系列色板`() {
        val text = themeFile.readText()
        val lightBlock = text.substringAfter("private val LightColors").substringBefore("private val DarkColors")
        val darkBlock = text.substringAfter("private val DarkColors").substringBefore("private val AppTypography")
        for (token in listOf("surfaceContainer =", "surfaceContainerHigh =", "surfaceContainerLow =")) {
            assertTrue("LightColors 缺少 $token", lightBlock.contains(token))
            assertTrue("DarkColors 缺少 $token", darkBlock.contains(token))
        }
    }

    @Test
    fun `AppRoot 挂载 TaskListDialog 并移除未适配主题的 TodoSheet`() {
        val appRoot = appRootFile.readText()
        assertTrue("AppRoot 应复用 TaskListDialog", appRoot.contains("TaskListDialog("))
        assertFalse("AppRoot 不应保留裸 AlertDialog 版 TodoSheet", appRoot.contains("fun TodoSheet("))
        assertTrue("打开任务清单应触发 viewModel.refreshTodos()", appRoot.contains("viewModel.refreshTodos()"))
    }

    @Test
    fun `TaskListDialog 实底不透明且支持多行展示`() {
        val strips = stripsFile.readText()
        val block = strips.substringAfter("fun TaskListDialog(").substringBefore("private fun TodoRow(")
        assertTrue(
            "TaskListDialog 需强制 surface alpha=1f 防止壁纸透底",
            block.contains("MaterialTheme.colorScheme.surface.copy(alpha = 1f)"),
        )
        assertTrue(
            "TaskListDialog 内 TodoRow 应放开多行换行",
            block.contains("TodoRow(it, singleLine = false)"),
        )
    }

    @Test
    fun `ChatViewModel 暴露 refreshTodos 并在就绪与切回前台时拉取`() {
        val vm = vmFile.readText()
        assertTrue("ChatViewModel 应提供 refreshTodos()", vm.contains("fun refreshTodos()"))
        assertTrue("ChatViewModel 应调用 transport?.pullTodos()", vm.contains("transport?.pullTodos()"))
    }
}
