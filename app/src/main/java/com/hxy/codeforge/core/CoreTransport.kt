package com.hxy.codeforge.core

import com.hxy.codeforge.model.ApprovalCard
import com.hxy.codeforge.model.ChatMessage
import com.hxy.codeforge.model.ContextUsage
import com.hxy.codeforge.model.NoticeKind
import com.hxy.codeforge.model.SessionItem
import com.hxy.codeforge.model.SubagentTask
import com.hxy.codeforge.model.TodoItem
import kotlinx.coroutines.flow.SharedFlow

/** 上下文压缩提示文案；内核仅在压缩完成后推 `compress` 帧，开始态由客户端补充。 */
const val NOTICE_COMPRESS_START = "正在压缩上下文…"
const val NOTICE_COMPRESS_DONE = "上下文已压缩"

/** 内核传输层输出的强类型领域事件。 */
sealed interface CoreEvent {
    data class Ready(
        val sessions: List<SessionItem> = emptyList(),
        val tools: List<String> = emptyList(),
        val model: String = "",
    ) : CoreEvent

    /** 传输层连通性事件，与携带业务负载的 [Ready] 帧分离。 */
    data class Link(
        val connected: Boolean,
        val attempt: Int = 0,
        val retryInMs: Long = 0,
    ) : CoreEvent

    data class SessionClaimed(val sessionId: String, val title: String = "") : CoreEvent
    data class Sessions(val items: List<SessionItem>) : CoreEvent
    data class History(val sessionId: String, val title: String, val messages: List<ChatMessage>) : CoreEvent
    data class Busy(val sessionId: String) : CoreEvent
    data class Idle(val sessionId: String) : CoreEvent
    data class Delta(val sessionId: String, val text: String) : CoreEvent
    data class Thinking(val sessionId: String, val text: String) : CoreEvent
    data class ToolStart(val sessionId: String, val toolCallId: String, val name: String, val input: String) : CoreEvent

    data class ToolEnd(
        val sessionId: String,
        val toolCallId: String,
        val output: String,
        val failed: Boolean,
        val diffAdded: Int = 0,
        val diffRemoved: Int = 0,
        val diff: String? = null,
    ) : CoreEvent

    data class ApprovalRequested(val sessionId: String, val card: ApprovalCard) : CoreEvent
    data class ApprovalResolved(val approvalId: String) : CoreEvent

    /** 审批超时通知，需与用户主动拒绝区分。 */
    data class ApprovalTimedOut(
        val approvalId: String,
        val sessionId: String,
        val message: String,
    ) : CoreEvent

    data class Goal(val status: com.hxy.codeforge.model.GoalStatus) : CoreEvent

    data class NoticeEvent(
        val sessionId: String?,
        val kind: NoticeKind,
        val text: String,
    ) : CoreEvent

    data class Todos(val sessionId: String, val items: List<TodoItem>) : CoreEvent
    data class ContextUpdate(val sessionId: String, val usage: ContextUsage) : CoreEvent
    data class SubagentUpdate(val sessionId: String, val task: SubagentTask) : CoreEvent

    /** 历史截断信号，清理当前流态并等待新 history 快照。 */
    data class StreamReset(val sessionId: String) : CoreEvent

    data class Failure(val message: String) : CoreEvent
    data class Warning(val message: String) : CoreEvent

    data class Checkpoints(
        val sessionId: String,
        val steps: List<com.hxy.codeforge.model.CheckpointStep> = emptyList(),
        val editables: List<com.hxy.codeforge.model.EditableUserMessage> = emptyList(),
        val resumeBack: Int = -1,
    ) : CoreEvent

    /** 后台任务状态变更信号；收到后按需拉取 `/api/tasks` 获取完整输出。 */
    data class TaskChanged(
        val id: String,
        val status: String,
        val exitCode: Int = 0,
    ) : CoreEvent
}

/** 内核通信传输抽象。 */
interface CoreTransport {

    val events: SharedFlow<CoreEvent>

    fun connect()
    fun disconnect()

    /** 发送用户消息；[attachments] 为相对工作区路径（如 `attachments/xxx`）。 */
    fun sendMessage(text: String, attachments: List<String> = emptyList())

    /** 运行中追加指令并入当前上下文。 */
    fun steer(text: String)

    fun regenerate()
    fun sendVisibility(hidden: Boolean) {}
    fun pullContext() {}
    fun pullTodos() {}
    fun cancel()
    fun decideApproval(approvalId: String, allow: Boolean)
    fun listSessions()
    fun loadSession(sessionId: String)

    /** 同步传输层记录的当前会话 id，确保上行帧携带正确的 `session_id` 而非触发服务端建新会话。 */
    fun setCurrentSession(sessionId: String) {}

    fun editUserMessage(text: String, back: Int, rollbackFiles: Boolean = true) {}
    fun continueTurn() {}
    fun retry(back: Int, rollbackFiles: Boolean = true) {}
    fun rewindFiles(toStep: Int) {}
    fun pullCheckpoints() {}
    fun newSession(): Boolean
}
