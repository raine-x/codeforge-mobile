package com.hxy.codeforge.ui.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 内置浏览器的壳侧控制器。
 *
 * 消息流里的 HTML 块在好几层 Composable 之下（OpenRenderContent →
 * RenderBlockView → CodeFenceBlock），要让它能唤起全屏浏览器，最省事的
 * 是用 CompositionLocal 往下传一个控制器，而不是给每一层都加回调参数。
 */
class BrowserController {

    /** 当前要打开的目标；null = 浏览器没开。 */
    var target by mutableStateOf<BrowserTarget?>(null)
        private set

    /** 元素被「添加到对话」时的回调（由 AppRoot 接到 ChatViewModel）。 */
    var onElementAdded: ((PickedElement) -> Unit)? = null

    /** 「用系统浏览器打开」的回调。 */
    var onOpenExternal: ((String) -> Unit)? = null

    fun open(t: BrowserTarget) {
        target = t
    }

    fun close() {
        target = null
    }
}

/**
 * 浏览器控制器。null 表示「本处没有浏览器能力」——
 * 比如单测或预览环境，调用方要能优雅降级而不是崩。
 */
val LocalBrowserController = compositionLocalOf<BrowserController?> { null }
