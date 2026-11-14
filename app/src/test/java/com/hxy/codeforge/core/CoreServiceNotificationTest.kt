package com.hxy.codeforge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守护 CoreService 常驻通知正文映射与重复 onStartCommand 不覆写已就绪状态。
 */
class CoreServiceNotificationTest {

    @Test
    fun `notificationTextFor maps each KernelState accurately`() {
        assertEquals("内核启动中…", notificationTextFor(KernelState.Starting))
        assertEquals("内核运行中 · 127.0.0.1:43210", notificationTextFor(KernelState.Ready(43210)))

        val longReason = "x".repeat(100)
        assertEquals("内核异常：${"x".repeat(60)}", notificationTextFor(KernelState.Failed(longReason)))
    }

    @Test
    fun `onStartCommand uses live state instead of hardcoded starting text`() {
        val src = listOf(
            File("src/main/java/com/hxy/codeforge/core/CoreService.kt"),
            File("app/src/main/java/com/hxy/codeforge/core/CoreService.kt"),
        ).firstOrNull { it.exists() }?.readText().orEmpty()

        assertTrue("CoreService.kt 应能被定位读取", src.isNotEmpty())
        assertTrue(src.contains("buildNotification(notificationTextFor(_state.value))"))
        assertFalse(src.contains("buildNotification(\"内核启动中…\")"))
        assertTrue(src.contains("bootstrapped.set(false)"))
    }
}
