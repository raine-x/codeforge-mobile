package com.hxy.codeforge.ui.chat

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf

/**
 * 聊天流内部渲染块（原生 `ui` 交互卡、交互式 SVG、内嵌 HTML 块）回传给宿主的动作通道。
 *
 * 用 CompositionLocal 往下传，避免给 `OpenRenderContent → RenderBlockView → CodeFenceBlock`
 * 的每一层都透传回调；同时集中保存交互卡片的本地状态（按块键名索引），
 * 防止 `LazyColumn` 滑出视口回收 item 后把用户已选的选项或表单重置。
 */
class ChatUiActions(
    val onSend: (String) -> Unit,
    val onPrefill: (String) -> Unit,
    val onToast: (String) -> Unit = {},
) {
    /** 交互卡片状态缓存：key = 块标识，value = 组件内部状态快照。 */
    private val cardStates = mutableStateMapOf<String, Any>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getState(key: String, default: () -> T): T =
        (cardStates[key] as? T) ?: default().also { cardStates[key] = it }

    fun setState(key: String, value: Any) {
        cardStates[key] = value
    }
}

val LocalChatUiActions = compositionLocalOf<ChatUiActions?> { null }
