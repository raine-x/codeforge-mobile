package app.codeforge.model

/**
 * 消息角色。内核只有 user / assistant 两种会渲染到界面，system 不进列表；
 * Notice 是壳自己折出来的「通知行」（重试/压缩/回滚等事件），不是内核消息。
 */
enum class MsgRole { User, Assistant, Notice }

/** 通知行种类：对应内核 retry / compress / rewind / steer / info / warn 事件。 */
enum class NoticeKind { Info, Retry, Compress, Rewind, Steer, Warn }

/** 工具调用状态。 */
enum class ToolState { Running, Ok, Failed }

/**
 * 一次工具调用。
 *
 * 字段对应内核 agent.Event 的 tool_call_id / tool_name / tool_input / result /
 * diff_stats / diff —— 原生 UI 只是换了个呈现方式，数据一字不改。
 */
data class ToolCall(
    val id: String,
    val name: String,
    val input: String,
    val output: String? = null,
    val state: ToolState = ToolState.Running,
    val diffAdded: Int = 0,
    val diffRemoved: Int = 0,
    val diff: String? = null,
)

/**
 * 一条聊天消息。
 *
 * [streaming] 为 true 时界面显示光标动画并禁用「复制」；流结束置 false。
 * [isWarn] 对应内核的 warn 事件（限流等），与 [isError] 分开：
 * error 要改东西才能好，warn 等一下重试就好 —— 两者在界面上必须长得不一样。
 */
data class ChatMessage(
    val id: String,
    val role: MsgRole,
    val text: String,
    val thinking: String? = null,
    val tools: List<ToolCall> = emptyList(),
    val streaming: Boolean = false,
    val isError: Boolean = false,
    val isWarn: Boolean = false,
    val time: Long = 0L,
    /** [MsgRole.Notice] 时的种类，气泡据此选图标与配色。 */
    val kind: NoticeKind? = null,
    /**
     * 原样输出：模型输出 markdown 源码本身（比如被要求「给我看源文件」）时，
     * 按纯文本等宽展示而不是渲染成富文本 —— 渲染会把「代码」变成「版式」。
     */
    val verbatim: Boolean = false,
    /** 用户消息附件（相对工作区路径 attachments/xxx）；气泡渲染缩略图/文件片。 */
    val attachments: List<String> = emptyList(),
)

/** 会话列表项。 */
data class SessionItem(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val busy: Boolean = false,
)

/** 任务项状态：pending / in_progress / completed / cancelled。 */
enum class TodoStatus { Pending, InProgress, Completed, Cancelled }

/** 内核 todo 帧的一项（store.TodoRow）。 */
data class TodoItem(
    val content: String,
    val status: TodoStatus,
    val priority: Int = 0,
    val sort: Int = 0,
)

/** 子智能体任务（tools/scope.go 的 SubagentEvent）。 */
data class SubagentTask(
    val id: String,
    val mode: String,
    val status: String,
    val detail: String = "",
    val summary: String = "",
)

/** 上下文占用（context 帧）。percent 可能超过 100（超预算），展示时再夹取。 */
data class ContextUsage(
    val used: Long,
    val budget: Long,
    val percent: Float,
    val model: String = "",
    val overBudget: Boolean = false,
)

/** GET /api/config 的设置页摘要（只取壳要展示/可写的字段）。 */
data class KernelConfigSummary(
    val model: String,
    val displayName: String,
    val provider: String,
    val maxTokens: Int,
    val temperature: Double,
    val retryMaxAttempts: Int,
    val retryMode: String,
    val retryIntervalSec: Int,
    val maxSteps: Int,
    val permMode: String,
    val workDir: String,
    val apiKeySet: Boolean,
)

/** 子智能体策略（/api/subagents）。 */
data class SubagentPolicy(
    val enabled: Boolean,
    val maxConcurrent: Int,
    val maxSteps: Int,
    val allowWrite: Boolean,
    val allowDelete: Boolean,
    val allowMemory: Boolean,
)

/**
 * 待审批的工具调用（HITL）。
 *
 * 字段与内核 hitl_request 帧一一对应。subagentId 非空表示这是子智能体发起的，
 * 并行委派时会同时挂多张卡片，界面上要能区分。
 */
data class ApprovalCard(
    val id: String,
    val tool: String,
    val action: String,
    val reason: String,
    val detail: String,
    val diff: String? = null,
    val subagentId: String? = null,
)

/** 权限模式：只读 / 请求 / 自主。对应内核的权限策略三档。 */
enum class PermissionMode(val label: String) {
    ReadOnly("只读"),
    Ask("请求"),
    Auto("自主"),
}

/** 检查点步骤（checkpoints 帧 steps[]）：回滚菜单的候选行。 */
data class CheckpointStep(
    val step: Int,
    val files: Int,
    val at: Long,
    val sample: String = "",
)

/** 可编辑的用户消息（checkpoints 帧 editables[]）：back 是编辑重发的截断锚点。 */
data class EditableUserMessage(
    val back: Int,
    val text: String,
)

/** 模型库条目（/api/models/list 的 models[]）。 */
data class LibraryModel(
    val id: String,
    val name: String,
    val provider: String = "",
)