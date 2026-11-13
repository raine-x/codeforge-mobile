package com.hxy.codeforge.ui.common

import android.content.pm.ActivityInfo
import com.hxy.codeforge.resolveRequestedOrientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 横竖屏自适应布局与旋转锁定的 JVM 单测。
 *
 * 守护需求：
 * - 不论手机或平板，竖屏（高 > 宽）统一使用手机布局（[WindowMode.Compact]，默认关闭侧栏），设置全屏展开；
 * - 横屏（宽 > 高）统一使用平板双栏布局（[WindowMode.Medium] / [WindowMode.Expanded]）；
 * - 平板与手机共同遵循设置中的「横持时旋转界面」开关（关闭即锁定竖屏）。
 */
class ResponsiveTest {

    @Test
    fun `portrait always selects Compact mobile layout on both phone and tablet`() {
        assertFalse(resolveIsLandscape(360, 800))
        assertEquals(WindowMode.Compact, resolveWindowMode(360, 800))

        // 大尺寸平板竖屏（即便宽度 >= 840dp）也必须走手机单栏布局
        assertFalse(resolveIsLandscape(800, 1280))
        assertEquals(WindowMode.Compact, resolveWindowMode(800, 1280))
        assertFalse(resolveIsLandscape(900, 1400))
        assertEquals(WindowMode.Compact, resolveWindowMode(900, 1400))

        // 等宽正方形不属于横屏
        assertFalse(resolveIsLandscape(700, 700))
        assertEquals(WindowMode.Compact, resolveWindowMode(700, 700))
    }

    @Test
    fun `landscape always selects tablet two-pane layout on both phone and tablet`() {
        // 小屏手机横持即便宽不足 480dp 也切双栏
        assertTrue(resolveIsLandscape(460, 320))
        assertEquals(WindowMode.Medium, resolveWindowMode(460, 320))

        // 常见手机横屏与小平板横屏
        assertTrue(resolveIsLandscape(800, 360))
        assertEquals(WindowMode.Medium, resolveWindowMode(800, 360))

        // 宽屏横屏 >= 840dp 使用 Expanded（320dp 宽侧栏）
        assertTrue(resolveIsLandscape(840, 380))
        assertEquals(WindowMode.Expanded, resolveWindowMode(840, 380))
        assertTrue(resolveIsLandscape(1280, 800))
        assertEquals(WindowMode.Expanded, resolveWindowMode(1280, 800))
    }

    @Test
    fun `requested orientation respects rotateWithDevice setting for tablet and locks portrait on phone`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            resolveRequestedOrientation(isTablet = false, rotateWithDevice = true),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            resolveRequestedOrientation(isTablet = false, rotateWithDevice = false),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            resolveRequestedOrientation(isTablet = true, rotateWithDevice = true),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            resolveRequestedOrientation(isTablet = true, rotateWithDevice = false),
        )
    }

    @Test
    fun `SettingsSheet expands full-screen in portrait mode`() {
        val src = listOf(
            File("src/main/java/com/hxy/codeforge/ui/settings/SettingsSheet.kt"),
            File("app/src/main/java/com/hxy/codeforge/ui/settings/SettingsSheet.kt"),
        ).firstOrNull { it.exists() }?.readText().orEmpty()

        assertTrue("SettingsSheet.kt 应能被定位读取", src.isNotEmpty())
        assertTrue(src.contains("val portraitFullScreen = !isLandscape()"))
        assertTrue(src.contains("Dp.Unspecified"))
        assertTrue(src.contains("RoundedCornerShape(0.dp)"))
        assertTrue(src.contains("Modifier.fillMaxSize()"))
    }
}
