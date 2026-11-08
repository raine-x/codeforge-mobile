package com.hxy.codeforge.core

import com.hxy.codeforge.model.ApprovalCard
import com.hxy.codeforge.model.ChatMessage
import com.hxy.codeforge.model.ContextUsage
import com.hxy.codeforge.model.NoticeKind
import com.hxy.codeforge.model.SessionItem
import com.hxy.codeforge.model.SubagentTask
import com.hxy.codeforge.model.TodoItem
import kotlinx.coroutines.flow.SharedFlow

/**
 * 压缩过程在消息流里的固定文案（协议层与 ViewModel 共用）。
 *
 * 内核只在**完成后**推 compress 帧，没有「开始」帧；开始态由壳侧补：
 * 手动压缩时立即插一条，自动压缩按 context 帧的 over_budget 推断
 * （与内核 Web 界面的 ctx 提示同一条判断）。
 */
const val NOTICE_COMPRESS_START = "正在压缩上下文…"
const val NOTICE_COMPRESS_DONE = "上下文已压缩"

/**
 * 内核推送的事件。
 *
 * 这是 [CoreTransport] 的出口抽象：Mock 与真实实现都产出同一组事件，
 * UI 层不需要知道自己连的是假数据还是 libcodeforge.so。
 *
 * 事件名对齐内核 ws_handler 的帧类型（ready/session/sessions/history/busy/
 * idle/text/reasoning/tool_pending/tool_call/tool_result/hitl_request/retry/
 * compress/rewind/steer/todo/context/subagent/error/warn …），
 * 只做了「扁平化到 UI 需要的粒度」。
 * 帧到事件的中文文案在 [HttpCoreTransport] 的解析层拼好，UI 不碰原始 JSON。
 */
sealed interface CoreEvent {
    /**
     * 连接就绪（服务端第一帧 ready）。payload 顺带带会话列表与配置视图，
     * 老内核没有这些字段时为空，ViewModel 会退回主动拉列表。
     */
    data class Ready(
        val sessions: List<SessionItem> = emptyList(),
        val tools: List<String> = emptyList(),
        val model: String = "",
    ) : CoreEvent

    /**
     * 链路连通性变化。与 [Ready] 是**两件事**，不要混用：
     *
     *  - `Link(true)` —— WebSocket 握手成功，消息已经能收发了；
     *  - `Ready` —— 内核业务层发来首帧，顺带带会话列表与模型名。
     *
     * 分开的理由：ready 帧可能因为解析失败、内核版本差异、或干脆没发而缺席，
     * 但那时链路其实是通的。只用 Ready 判定「已连接」会让界面误报未连接，
     * 而实际上发消息是能收到回复的。
     *
     * [connected] 为 false 时表示正在退避重连：[attempt] 是第几次尝试，
     * [retryInMs] 是距离下次重连的等待时长。
     */
    data class Link(
        val connected: Boolean,
        val attempt: Int = 0,
        val retryInMs: Long = 0,
    ) : CoreEvent

    /** 服务端确定了会话 id（首条消息自动建会话时）。 */
    /** 内核确认了当前会话。[title] 可能为空（新建时未命名）。 */
    data class SessionClaimed(val sessionId: String, val title: String = "") : CoreEvent

    /** 会话列表全量刷新。 */
    data class Sessions(val items: List<SessionItem>) : CoreEvent

    /** 载入某个会话的完整历史（切会话、或内核主动补帧）。 */
    data class History(val sessionId: String, val title: String, val messages: List<ChatMessage>) : CoreEvent

    /** 该会话开始运行。 */
    data class Busy(val sessionId: String) : CoreEvent

    /** 该会话运行结束。 */
    data class Idle(val sessionId: String) : CoreEvent

    /** 助手正文增量。 */
    data class Delta(val sessionId: String, val text: String) : CoreEvent

    /** 思考过程增量（内核 reasoning 帧）。 */
    data class Thinking(val sessionId: String, val text: String) : CoreEvent

    /** 工具开始执行。 */
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

    /** 需要人工审批。 */
    data class ApprovalRequested(val sessionId: String, val card: ApprovalCard) : CoreEvent

    /** 审批已结束（超时/被撤回），界面上要把卡片收掉。 */
    data class ApprovalResolved(val approvalId: String) : CoreEvent

    /** 审批超时：与「用户拒绝」必须分开提示（内核 15 分钟独立超时）。 */
    data class ApprovalTimedOut(
        val approvalId: String,
        val sessionId: String,
        val message: String,
    ) : CoreEvent

    /** 目标模式账本快照（goal 帧）。 */
    data class Goal(val status: com.hxy.codeforge.model.GoalStatus) : CoreEvent

    /**
     * 流程性通知：重试 / 压缩 / 回滚 / 转向 / 一般提示。
     * 渲染成消息流里的通知行，不打断阅读。
     */
    data class NoticeEvent(
        val sessionId: String?,
        val kind: NoticeKind,
        val text: String,
    ) : CoreEvent

    /** 任务清单全量（todo 帧）。 */
    data class Todos(val sessionId: String, val items: List<TodoItem>) : CoreEvent

    /** 上下文占用（context 帧），每轮结束刷新。 */
    data class ContextUpdate(val sessionId: String, val usage: ContextUsage) : CoreEvent

    /** 子智能体状态（subagent 帧），按 id 增量更新。 */
    data class SubagentUpdate(val sessionId: String, val task: SubagentTask) : CoreEvent

    /** 历史被编辑截断（edit 帧）：流式作废，等随后的 history 快照重建。 */
    data class StreamReset(val sessionId: String) : CoreEvent

    /** 硬错误：请求本身有问题，改了才能好。 */
    data class Failure(val message: String) : CoreEvent

    /** 软警告：等一下重试就好（限流等），不要清掉用户那句话。 */
    data class Warning(val message: String) : CoreEvent

    /** checkpoints 帧：断点回滚/编辑重发的数据源。 */
    data class Checkpoints(
        val sessionId: String,
        val steps: List<com.hxy.codeforge.model.CheckpointStep> = emptyList(),
        val editables: List<com.hxy.codeforge.model.EditableUserMessage> = emptyList(),
        val resumeBack: Int = -1,
    ) : CoreEvent

    /**
     * 后台任务状态变化（task 帧）。
     *
     * 只带 id 与终态：输出可能有几十 KB，逐帧推送会把连接挤爆。
     * 壳侧收到后重拉 GET /api/tasks 对齐列表与日志。
     */
    data class TaskChanged(
        val id: String,
        val status: String,
        val exitCode: Int = 0,
    ) : CoreEvent
}

/**
 * 与内核通信的抽象。
 *
 * 真实实现见 [HttpCoreTransport]（HTTP + WebSocket 连 127.0.0.1:随机端口）；
 * 界面开发期用 [MockCoreTransport] 驱动，两者事件完全同构。
 */
interface CoreTransport {

    val events: SharedFlow<CoreEvent>

    /** 建立连接。 */
    fun connect()

    /** 断开连接。 */
    fun disconnect()

    /**
     * 发送一条用户消息。
     * [attachments] 为相对工作区的附件路径（attachments/xxx），由壳侧选择器
     * 复制到 workspace 后填入；内核按图片/视频限额校验。
     */
    fun sendMessage(text: String, attachments: List<String> = emptyList())

    /** 运行中转向（不新开一轮，并入当前上下文）。 */
    fun steer(text: String)

    /** 重新生成最后一轮回复。 */
    fun regenerate()

    /** 上报页面可见性（内核决定要不要发系统通知）。默认空实现。 */
    fun sendVisibility(hidden: Boolean) {}

    /** 主动拉取当前会话的上下文占用（默认空实现；内核 idle 后本会自动推，此为补充）。 */
    fun pullContext() {}

    /** 打断当前生成。 */
    fun cancel()

    /** 提交审批决定。 */
    fun decideApproval(approvalId: String, allow: Boolean)

    /** 请求会话列表。 */
    fun listSessions()

    /** 切换到某个会话。 */
    fun loadSession(sessionId: String)

    /**
     * 只告知传输层「当前会话是哪个」，不发任何帧。
     *
     * 新建会话（session 帧）与历史快照（history 帧）都会确立当前会话；
     * 传输层上行帧（user_message/steer/cancel …）都带 session_id，必须与
     * 界面显示的会话**同一口径**，否则消息会落进旧会话或触发内核自动新建
     * （问题 2 的根因之一）。默认空实现供 Mock 使用。
     */
    fun setCurrentSession(sessionId: String) {}

    // ---- 断点三件套（B6-P0）：内核未就绪/测试模型下静默空操作 ----
    /** 编辑重发：截断到 back 锚点的用户消息并换文本重跑（内核原语义）。 */
    fun editUserMessage(text: String, back: Int, rollbackFiles: Boolean = true) {}
    /** 继续上一轮（断点续跑：不截断不回退、不追加假提问）。 */
    fun continueTurn() {}
    /**
     * 断点重试：保留用户原话，截掉其后未完成的回复/工具结果并可选回退文件。
     * [back] 是距最后一条用户消息的距离（0 = 最后一条）。
     */
    fun retry(back: Int, rollbackFiles: Boolean = true) {}
    /** 手动回滚：工作区文件恢复到第 toStep 步之前。 */
    fun rewindFiles(toStep: Int) {}
    /** 拉一次最新检查点列表（打开回滚菜单时调）。 */
    fun pullCheckpoints() {}

    /** 新建会话。 */
    fun newSession(): Boolean
}
