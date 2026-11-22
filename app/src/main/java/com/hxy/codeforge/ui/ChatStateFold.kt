package com.hxy.codeforge.ui

import com.hxy.codeforge.core.CoreEvent
import com.hxy.codeforge.model.ActionPanelState
import com.hxy.codeforge.model.ChatMessage
import com.hxy.codeforge.model.MessageSegment
import com.hxy.codeforge.model.MsgRole
import com.hxy.codeforge.model.SessionItem
import com.hxy.codeforge.model.ToolCall
import com.hxy.codeforge.model.ToolState

/*
 * 事件折叠的纯函数部分（`ChatUiState` 进、`ChatUiState` 出）。
 *
 * 为什么单拆出来：`ChatViewModel.onEvent` 里夹着通知、传输层、`PhoneTaskController` 等
 * 依赖 Android/单例的副作用，没法在纯 JVM 里直接跑。把「状态怎么变」抽成无副作用函数，
 * 就能用单测钉住这块最容易回归的逻辑（流式拼接、工具卡归属、会话切换清理）。
 * 副作用（通知、`planPending`、`transport` 调用）仍留在 ViewModel 里，不许搬进来。
 * 约束：本文件不许 import Android、`transport`、单例对象。
 */

/** 正文增量并入末段（末段是正文就接着写，否则新起一段）。 */
internal fun appendTextSegment(segs: List<MessageSegment>, text: String): List<MessageSegment> {
    val last = segs.lastOrNull()
    return if (last is MessageSegment.Text) {
        segs.dropLast(1) + last.copy(text = last.text + text)
    } else {
        segs + MessageSegment.Text(text)
    }
}

/** 思考增量并入末段（末段是思考就接着写，否则新起一段 —— 段序即真实发生顺序）。 */
internal fun appendThinkingSegment(segs: List<MessageSegment>, text: String): List<MessageSegment> {
    val last = segs.lastOrNull()
    return if (last is MessageSegment.Thinking) {
        segs.dropLast(1) + last.copy(text = last.text + text)
    } else {
        segs + MessageSegment.Thinking(text)
    }
}

/** 工具段按 id 就地更新；没有就追加到末尾（保证「思考-工具-正文」的真实顺序）。 */
internal fun upsertToolSegment(segs: List<MessageSegment>, call: ToolCall): List<MessageSegment> {
    val at = segs.indexOfFirst { it is MessageSegment.Tool && it.call.id == call.id }
    return if (at >= 0) {
        segs.toMutableList().also { it[at] = MessageSegment.Tool(call) }
    } else {
        segs + MessageSegment.Tool(call)
    }
}

/** 从末尾往前找助手消息，遇到用户消息即为上一轮边界（通知行跳过）；找不到返回 -1。 */
internal fun currentTurnAssistantIndex(msgs: List<ChatMessage>): Int {
    for (i in msgs.indices.reversed()) {
        val m = msgs[i]
        if (m.role == MsgRole.Assistant) return i
        if (m.role == MsgRole.User) return -1
    }
    return -1
}

/**
 * 单条消息内的收尾清扫：还在「运行中」的工具调用改为 [ToolState.Interrupted]。
 *
 * ToolEnd 在断线/取消/异常收尾时可能被整个错过，那时工具行会永远停在橙点扫光。
 * tools 与 segments 是同一份数据的两份拷贝，必须一起改（同 [foldToolEnd]）；
 * 迟到的 tool_result 仍会按 id 命中覆盖，真实结果不会丢。
 */
internal fun sweepMessage(m: ChatMessage): ChatMessage =
    if (m.tools.none { it.state == ToolState.Running }) m else m.copy(
        tools = m.tools.map {
            if (it.state == ToolState.Running) it.copy(state = ToolState.Interrupted) else it
        },
        segments = m.segments.map { seg ->
            if (seg is MessageSegment.Tool && seg.call.state == ToolState.Running) {
                MessageSegment.Tool(seg.call.copy(state = ToolState.Interrupted))
            } else {
                seg
            }
        },
    )

/**
 * 后台会话判定：带 sessionId、不属于当前会话、且属于消息流折叠类型。
 * History/SessionClaimed（切会话本身）与 ApprovalRequested 不在此列。
 */
internal fun isBackgroundSessionEvent(e: CoreEvent, currentSessionId: String): Boolean {
    val sid = when (e) {
        is CoreEvent.Delta -> e.sessionId
        is CoreEvent.Thinking -> e.sessionId
        is CoreEvent.ToolStart -> e.sessionId
        is CoreEvent.ToolEnd -> e.sessionId
        is CoreEvent.StreamReset -> e.sessionId
        is CoreEvent.NoticeEvent -> e.sessionId ?: return false
        is CoreEvent.Todos -> e.sessionId
        is CoreEvent.ContextUpdate -> e.sessionId
        is CoreEvent.SubagentUpdate -> e.sessionId
        is CoreEvent.Busy -> e.sessionId
        is CoreEvent.Idle -> e.sessionId
        else -> return false
    }
    return sid.isNotEmpty() && currentSessionId.isNotEmpty() && sid != currentSessionId
}

/** 助手正文增量：并入当前流式消息，没有就新建一条。 */
internal fun ChatUiState.foldAssistantText(text: String, newId: () -> String, now: Long): ChatUiState {
    val msgs = messages.toMutableList()
    val last = msgs.lastOrNull()
    if (last != null && last.role == MsgRole.Assistant && last.streaming) {
        msgs[msgs.lastIndex] = last.copy(
            text = last.text + text,
            segments = appendTextSegment(last.segments, text),
        )
    } else {
        msgs.add(
            ChatMessage(
                id = newId(),
                role = MsgRole.Assistant,
                text = text,
                streaming = true,
                time = now,
                segments = listOf(MessageSegment.Text(text)),
            ),
        )
    }
    return copy(messages = msgs, pendingStage = "")
}

/** 思考增量：与正文一致按**增量**追加（内核 reasoning 帧是流式增量）。 */
internal fun ChatUiState.foldThinking(text: String, newId: () -> String, now: Long): ChatUiState {
    val msgs = messages.toMutableList()
    val last = msgs.lastOrNull()
    if (last != null && last.role == MsgRole.Assistant && last.streaming) {
        msgs[msgs.lastIndex] = last.copy(
            thinking = (last.thinking ?: "") + text,
            segments = appendThinkingSegment(last.segments, text),
        )
    } else {
        msgs.add(
            ChatMessage(
                id = newId(),
                role = MsgRole.Assistant,
                text = "",
                thinking = text,
                streaming = true,
                time = now,
                segments = listOf(MessageSegment.Thinking(text)),
            ),
        )
    }
    return copy(messages = msgs, pendingStage = "")
}

/**
 * 工具卡片挂在「当前这条助手消息」上。只从**最后一条用户消息之后**找助手消息，
 * 否则新工具卡会被挂到上一轮已结束的助手消息后面。
 */
internal fun ChatUiState.foldToolStart(call: ToolCall, newId: () -> String, now: Long): ChatUiState {
    val msgs = messages.toMutableList()
    var idx = currentTurnAssistantIndex(msgs)
    if (idx < 0) {
        msgs.add(
            ChatMessage(
                id = newId(),
                role = MsgRole.Assistant,
                text = "",
                streaming = true,
                time = now,
            ),
        )
        idx = msgs.lastIndex
    }
    val target = msgs[idx]
    val tools = target.tools.toMutableList()
    val at = tools.indexOfFirst { it.id == call.id }
    if (at >= 0) tools[at] = call else tools.add(call)
    msgs[idx] = target.copy(
        tools = tools,
        segments = upsertToolSegment(target.segments, call),
    )
    return copy(messages = msgs, pendingStage = "")
}

/** 工具结果回填：tools 与 segments 两份拷贝一起改，否则按段渲染的卡永远停在「执行中」。 */
internal fun ChatUiState.foldToolEnd(event: CoreEvent.ToolEnd): ChatUiState {
    val msgs = messages.toMutableList()
    for (i in msgs.indices.reversed()) {
        val m = msgs[i]
        val at = m.tools.indexOfFirst { it.id == event.toolCallId }
        if (at >= 0) {
            val tools = m.tools.toMutableList()
            tools[at] = tools[at].copy(
                output = event.output,
                state = if (event.failed) ToolState.Failed else ToolState.Ok,
                diffAdded = event.diffAdded,
                diffRemoved = event.diffRemoved,
                diff = event.diff,
            )
            val segs = m.segments.map { seg ->
                if (seg is MessageSegment.Tool && seg.call.id == event.toolCallId) {
                    MessageSegment.Tool(tools[at])
                } else {
                    seg
                }
            }
            msgs[i] = m.copy(tools = tools, segments = segs)
            break
        }
    }
    return copy(messages = msgs)
}

/** 当前会话忙碌：运行态与会话列表 busy 标记一起写（侧栏项目行的转圈从 busy 聚合）。 */
internal fun ChatUiState.foldBusy(sessionId: String): ChatUiState {
    val hasStream = messages.lastOrNull()?.let { it.role == MsgRole.Assistant && it.streaming } == true
    val nextStage = if (hasStream) "" else pendingStage.ifBlank { "正在连接模型，等待响应…" }
    return copy(running = true, banner = null, pendingStage = nextStage)
        .applySessions(sessions.map { if (it.id == sessionId) it.copy(busy = true) else it })
}

/**
 * 一轮结束：清流式标记与挂着的运行中工具行；[showPlan] 为真时把最后一条助手正文装进操作面板。
 */
internal fun ChatUiState.foldIdle(sessionId: String, showPlan: Boolean): ChatUiState {
    val msgs = messages.map { m ->
        sweepMessage(if (m.streaming) m.copy(streaming = false) else m)
    }
    val panel = if (showPlan) {
        msgs.lastOrNull { it.role == MsgRole.Assistant && it.text.isNotBlank() }
            ?.let { ActionPanelState(content = it.text) } ?: actionPanel
    } else {
        actionPanel
    }
    return copy(
        running = false,
        pendingStage = "",
        // 轮次结束：已排队的转向指令此时已被内核处理，提示状态复位
        steerQueued = false,
        messages = msgs,
        actionPanel = panel,
    ).applySessions(sessions.map { if (it.id == sessionId) it.copy(busy = false) else it })
}

/** 失败收尾：同样要清流式标记与挂着的运行中工具行，并挂上错误横幅。 */
internal fun ChatUiState.foldFailure(banner: Banner): ChatUiState = copy(
    running = false,
    pendingStage = "",
    banner = banner,
    messages = messages.map { m ->
        sweepMessage(if (m.streaming) m.copy(streaming = false) else m)
    },
)

/**
 * 会话列表帧。内核的列表可能不含「刚建好还没说话」的会话，直接覆盖会让新建对话在侧栏消失，
 * 所以当前会话不在列表里就本地补一条。
 */
internal fun ChatUiState.foldSessions(items: List<SessionItem>, now: Long): ChatUiState {
    val cur = currentSessionId
    val patched = if (cur.isNotBlank() && items.none { it.id == cur }) {
        listOf(
            SessionItem(
                id = cur,
                title = "新建会话",
                updatedAt = now,
                busy = false,
                workspace = currentWorkspace,
                workspaceName = currentWorkspaceName,
            ),
        ) + items
    } else {
        items
    }
    return applySessions(mergeBusy(patched, sessions))
}

/**
 * session 帧（new_session 才下发）。「id 变了」就是新建的确定信号：
 * 新会话是空白上下文，会话级状态必须一起清掉，否则界面还挂着上一条会话的消息与运行态。
 */
internal fun ChatUiState.foldSessionClaimed(sessionId: String, rawTitle: String, now: Long): ChatUiState {
    val newTitle = rawTitle.ifBlank { "新建会话" }
    val exists = sessions.any { it.id == sessionId }
    val isNew = sessionId != currentSessionId
    val patched = if (exists) {
        sessions.map {
            if (it.id == sessionId) it.copy(title = rawTitle.ifBlank { it.title }) else it
        }
    } else {
        listOf(
            SessionItem(
                id = sessionId,
                title = newTitle,
                updatedAt = now,
                busy = false,
                workspace = currentWorkspace,
                workspaceName = currentWorkspaceName,
            ),
        ) + sessions
    }
    return copy(
        currentSessionId = sessionId,
        title = newTitle,
        messages = if (isNew) emptyList() else messages,
        running = if (isNew) false else running,
        pendingStage = if (isNew) "" else pendingStage,
        approvals = if (isNew) emptyList() else approvals,
        todos = if (isNew) emptyList() else todos,
        subagents = if (isNew) emptyList() else subagents,
        contextUsage = if (isNew) null else contextUsage,
        goal = if (isNew) null else goal,
        checkpoints = if (isNew) emptyList() else checkpoints,
        resumeBack = if (isNew) -1 else resumeBack,
        editableAnchors = if (isNew) emptyList() else editableAnchors,
        actionPanel = if (isNew) null else actionPanel,
        editText = if (isNew) null else editText,
        editAttachments = if (isNew) emptyList() else editAttachments,
        editBack = if (isNew) null else editBack,
    ).applySessions(patched)
}
