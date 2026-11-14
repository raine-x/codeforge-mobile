package com.hxy.codeforge.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * 窗口宽度档位。
 *
 * 跟随系统原生旋转后 `LocalConfiguration` 直接反映真实窗口尺寸：
 * 竖屏（高 >= 宽）进入 [Compact]，横屏（宽 > 高）按宽度进入 [Medium] 或 [Expanded]。
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
 * 窗口是否处于横屏形态（宽严格大于高）。
 * 不论手机或平板，只要高大于宽（含等宽）就按竖屏处理。
 */
internal fun resolveIsLandscape(widthDp: Int, heightDp: Int): Boolean = widthDp > heightDp

/**
 * 统一布局档位判定规则（不区分手机/平板物理尺寸，完全按当前窗口宽高比决定）：
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

/** 当前窗口档位：由系统配置下的真实窗口宽高直接判定。 */
@Composable
@ReadOnlyComposable
fun currentWindowMode(): WindowMode {
    val config = LocalConfiguration.current
    return resolveWindowMode(config.screenWidthDp, config.screenHeightDp)
}

/** 是否横屏（宽大于高）。用于布局切换、设置页全屏判定及输入框高度等细节。 */
@Composable
@ReadOnlyComposable
fun isLandscape(): Boolean {
    val config = LocalConfiguration.current
    return resolveIsLandscape(config.screenWidthDp, config.screenHeightDp)
}
