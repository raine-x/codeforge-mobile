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
 * 真实对话流片段：思考 / 工具 / 正文按**发生顺序**排列。
 *
 * 内核一轮里可能是「思考 → 工具 → 思考 → 正文 → 工具」，旧的
 * `thinking/tools/text` 三个字段各存一份，渲染时工具总是被堆到正文前面。
 * `segments` 保留原始顺序，`thinking/tools/text` 继续维护只为兼容旧界面。
 */
sealed interface MessageSegment {
    data class Thinking(val text: String) : MessageSegment
    data class Tool(val call: ToolCall) : MessageSegment
    data class Text(val text: String) : MessageSegment
}

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
    /** 真实对话流片段（按发生顺序）；空 = 旧字段渲染，历史消息暂不填。 */
    val segments: List<MessageSegment> = emptyList(),
)

/** 会话列表项。 */
data class SessionItem(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val busy: Boolean = false,
    /** 归属工作区（= 磁盘绝对路径，分组键；空 = 未选择工作区）。 */
    val workspace: String = "",
    /** 项目显示名（内核 workspace_names 表；空时界面回落路径末段）。 */
    val workspaceName: String = "",
    /** 已归档（archived_at > 0）；侧栏不展示，归档页消费。 */
    val archived: Boolean = false,
)

/**
 * 侧栏项目（按会话的 workspace 分组聚合出来，不是独立存储）。
 *
 * [busy] = 组内任意会话在跑；[archived] = 组内全部会话都已归档。
 */
data class ProjectItem(
    val workspace: String,
    val name: String,
    val sessionCount: Int = 0,
    val busy: Boolean = false,
    val archived: Boolean = false,
)

/** 工作区文件树的一项（内核 treeNode：name/path/is_dir/size）。 */
data class TreeEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long = 0,
)

/** @plan 回合结束后弹出的计划书操作面板（默认选中计划书正文）。 */
data class ActionPanelState(
    val title: String = "计划书已生成",
    val content: String,
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

/**
 * 目标模式账本（内核 Session.Goal 的快照）。
 *
 * 与 `pkg/tools/goal.go` 的 GoalState 一一对应；Kotlin 只读，
 * 轮次预算与 PASS/FAIL 判定全部由内核算。
 */
data class GoalStatus(
    val goal: String = "",
    val round: Int = 0,
    val maxRounds: Int = 0,
    val verdict: String = "",
    val findings: String = "",
    val step: Int = 0,
    val open: Boolean = false,
    val exhausted: Boolean = false,
)

/**
 * 模型库条目（/api/models/list 的 models[]）。
 *
 * 字段与内核 `config.sanitizeEntries` 的 map 字面量逐键对齐（P-008：按写方代码核对，
 * 不按相似端点类推）。**明文密钥永不下发**，只有 [keySet] 这个布尔。
 *
 * [vision] / [video] 是**三态**：null = 未声明（≠ 不支持）。内核把未声明当乐观发送、
 * 撞上游拒收再记忆；false 才是发之前就拦 —— 表单必须能表达中间态。
 */
data class LibraryModel(
    val id: String,
    val name: String,
    /** 归属供应商 id（内核 provider_id，分组键；空 = 未归属/旧格式自带连接信息）。 */
    val provider: String = "",
    /** 供应商显示名（内核 provider_name；空则界面回落 id）。 */
    val providerName: String = "",
    /** 输入上下文上限（tokens，0 = 未设置）。 */
    val ctxIn: Int = 0,
    /** 输出上限（tokens，0 = 未设置）。 */
    val ctxOut: Int = 0,
    /** 图片能力三态：null 未声明 / true 支持 / false 不支持。 */
    val vision: Boolean? = null,
    /** 视频能力三态，语义同 [vision]。 */
    val video: Boolean? = null,
    /** 客户端节流上限（每分钟请求数，0 = 不限）。 */
    val rpm: Int = 0,
    /** 密钥来源覆盖：env | plain；空 = 继承供应商。 */
    val keySource: String = "",
    /** 环境变量名（[keySource] = env 时有效）。 */
    val keyName: String = "",
    /** 是否已有可用密钥（env 变量已设置或明文非空）。 */
    val keySet: Boolean = false,
    /** 实际会用的请求地址（内核已用供应商补齐，留空 = 继承）。 */
    val baseUrl: String = "",
    /** 实际会用的协议（openai | anthropic）。 */
    val protocol: String = "",
)

/**
 * 内核技能（GET /api/skills 的 items[]）。
 *
 * [slug] 是 SKILL.md 的目录名，也是输入框 @ 补全的 token；[name] 是内核下发的
 * display_name（未配置显示名时内核已回退成 slug）。
 */
data class SkillInfo(
    val slug: String,
    val name: String,
    val description: String = "",
    val triggers: List<String> = emptyList(),
    val enabled: Boolean = true,
)

/** 内置插件（GET /api/builtin-plugins 的 items[]）：带用途与使用场景说明。 */
data class BuiltinPluginInfo(
    val id: String,
    val name: String,
    val purpose: String = "",
    val whenToUse: String = "",
    val enabled: Boolean = false,
)

/**
 * 内核后台脚本任务（GET /api/tasks 的 items[]，pkg/tasks.Task 的镜像）。
 *
 * [status] 是内核原样的三个取值：running / exited / killed。
 * [exitCode] 只在结束后有效（被信号结束时可能为 -1）。
 * [output] 是内核保留的最近 128KiB 输出尾部，不是完整日志。
 */
data class BackgroundTask(
    val id: String,
    val label: String,
    val command: String,
    val cwd: String = "",
    val status: String = "running",
    val exitCode: Int = 0,
    val startedAt: Long = 0L,
    val endedAt: Long = 0L,
    val output: String = "",
)