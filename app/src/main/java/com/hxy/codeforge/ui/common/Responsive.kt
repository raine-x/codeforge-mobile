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
    /** 竖屏（高大于宽，不论手机或平板）：手机单栏布局，默认关闭侧栏，设置全屏展开。 */
    Compact,

    /** 横屏（宽大于高）：双栏平板布局，侧栏固定宽度。 */
    Medium,

    /** 横屏大屏（宽大于高且 >= 840dp）：双栏平板布局，侧栏更宽。 */
    Expanded,
}

/**
 * 窗口是否处于横屏形态（内容坐标系下宽严格大于高）。
 * 不论手机或平板，只要高大于宽（含等宽）就按竖屏处理。
 */
internal fun resolveIsLandscape(widthDp: Int, heightDp: Int): Boolean = widthDp > heightDp

/**
 * 统一布局档位判定规则（不区分手机/平板物理尺寸，完全按当前内容宽高比决定）：
 * - 竖屏（高 >= 宽）：始终返回 [WindowMode.Compact]（默认关闭侧栏抽屉、设置全屏展开）；
 * - 横屏（宽 > 高）：始终使用平板双栏布局（>= 840dp 为 [WindowMode.Expanded]，其余为 [WindowMode.Medium]）。
 */
internal fun resolveWindowMode(widthDp: Int, heightDp: Int): WindowMode =
    if (!resolveIsLandscape(widthDp, heightDp)) {
        WindowMode.Compact
    } else if (widthDp >= 840) {
        WindowMode.Expanded
    } else {
        WindowMode.Medium
    }

/**
 * 当前窗口档位。
 *
 * 手机竖屏锁窗口 + 内容旋转时优先读取 [LocalOrientation]（旋转后内容盒的真实宽高），
 * 平板或未挂载旋转层时回退到 `LocalConfiguration` 的窗口尺寸。
 */
@Composable
@ReadOnlyComposable
fun currentWindowMode(): WindowMode {
    LocalOrientation.current?.let { o ->
        return resolveWindowMode(o.widthDp, o.heightDp)
    }
    val config = LocalConfiguration.current
    return resolveWindowMode(config.screenWidthDp, config.screenHeightDp)
}

/** 是否横屏（内容坐标系，宽大于高）。用于布局切换、设置页全屏判定及输入框高度等细节。 */
@Composable
@ReadOnlyComposable
fun isLandscape(): Boolean {
    LocalOrientation.current?.let { return resolveIsLandscape(it.widthDp, it.heightDp) }
    val config = LocalConfiguration.current
    return resolveIsLandscape(config.screenWidthDp, config.screenHeightDp)
}
