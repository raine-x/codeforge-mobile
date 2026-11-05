package com.hxy.codeforge.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * 窗口宽度档位。
 *
 * ## 档位跟着哪个尺寸走
 *
 * 窗口已锁竖屏，但内容会跟手旋转（[RotateWithDevice]）——横持时内容盒是
 * 「物理高 × 物理宽」，比 LocalConfiguration 宽得多。所以判定优先读
 * [LocalOrientation]（旋转后内容盒的真实尺寸），没有（预览/未挂旋转层）
 * 才退回 LocalConfiguration，并保留平板竖屏的兜底。
 */
enum class WindowMode {
    /** 手机竖屏：单栏，会话列表走抽屉。 */
    Compact,

    /** 手机横屏、小平板竖屏：双栏，列表固定宽度。 */
    Medium,

    /** 平板横屏、折叠屏展开：三栏，多一块工具面板。 */
    Expanded,
}

/**
 * 当前窗口档位。
 *
 * 阈值参考 Material 3 的 window size class（600 / 840dp）。
 * 配置兜底额外加一条「宽大于高且不窄于 480dp」：少数平板竖屏只有 560dp 宽，
 * 按 600 判会掉进单栏，但那个宽度完全放得下双栏。
 */
@Composable
@ReadOnlyComposable
fun currentWindowMode(): WindowMode {
    LocalOrientation.current?.let { o ->
        return when {
            o.widthDp >= 840 -> WindowMode.Expanded
            o.widthDp >= 600 -> WindowMode.Medium
            else -> WindowMode.Compact
        }
    }
    val config = LocalConfiguration.current
    val w = config.screenWidthDp
    val h = config.screenHeightDp
    return when {
        w >= 840 -> WindowMode.Expanded
        w >= 600 -> WindowMode.Medium
        w > h && w >= 480 -> WindowMode.Medium
        else -> WindowMode.Compact
    }
}

/** 是否横屏（内容坐标系）。用于细节调整（比如输入框单行高度）。 */
@Composable
@ReadOnlyComposable
fun isLandscape(): Boolean {
    LocalOrientation.current?.let { return it.isLandscape }
    val config = LocalConfiguration.current
    return config.screenWidthDp > config.screenHeightDp
}
