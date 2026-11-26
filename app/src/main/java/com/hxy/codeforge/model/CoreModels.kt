package com.hxy.codeforge.model

/** 消息角色；Notice 为客户端合成的流程提示行（重试/压缩/回滚等）。 */
enum class MsgRole { User, Assistant, Notice }

enum class NoticeKind { Info, Retry, Compress, Rewind, Steer, Warn }

/** 工具调用状态；[Interrupted] 表示回合结束时仍未返回结果的调用（后续若到达会按 id 覆盖）。 */
enum class ToolState { Running, Ok, Failed, Interrupted }

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

/** 按实际发生顺序排列的对话流片段，避免工具卡一律前置遮挡正文。 */
sealed interface MessageSegment {
    data class Thinking(val text: String) : MessageSegment
    data class Tool(val call: ToolCall) : MessageSegment
    data class Text(val text: String) : MessageSegment
}

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
    val kind: NoticeKind? = null,
    /** 为 true 时按等宽纯文本原样展示源码，不交由富文本渲染。 */
    val verbatim: Boolean = false,
    val attachments: List<String> = emptyList(),
    val segments: List<MessageSegment> = emptyList(),
)

data class SessionItem(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val busy: Boolean = false,
    val workspace: String = "",
    val workspaceName: String = "",
    val archived: Boolean = false,
)

/** 侧栏项目聚合视图（按会话 [workspace] 分组生成）。 */
data class ProjectItem(
    val workspace: String,
    val name: String,
    val sessionCount: Int = 0,
    val busy: Boolean = false,
    val archived: Boolean = false,
)

data class TreeEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long = 0,
)

data class ActionPanelState(
    val title: String = "计划书已生成",
    val content: String,
)

enum class TodoStatus { Pending, InProgress, Completed, Cancelled }

data class TodoItem(
    val content: String,
    val status: TodoStatus,
    val priority: Int = 0,
    val sort: Int = 0,
)

data class SubagentTask(
    val id: String,
    val mode: String,
    val status: String,
    val detail: String = "",
    val summary: String = "",
)

data class ContextUsage(
    val used: Long,
    val budget: Long,
    val percent: Float,
    val model: String = "",
    val overBudget: Boolean = false,
    /** 输入上下文窗口（0 表示模型库未声明，内核将按默认预算 120K 估算）。 */
    val window: Long = 0,
    val compressed: Boolean = false,
) {
    /** 优先按模型真实窗口算展示占比，无窗口时回落内核预算百分比。 */
    val displayFraction: Float
        get() = when {
            window > 0 && used > 0 -> (used.toFloat() / window).coerceIn(0f, 1f)
            else -> (percent / 100f).coerceIn(0f, 1f)
        }
}

data class KernelConfigSummary(
    val model: String,
    val displayName: String,
    /** 内核 `llm.provider` 为协议族（anthropic | openai），非供应商 id（P-045）。 */
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
    val modelInLibrary: Boolean = true,
    val tools: List<String> = emptyList(),
)

data class SubagentPolicy(
    val enabled: Boolean,
    val maxConcurrent: Int,
    val maxSteps: Int,
    val allowWrite: Boolean,
    val allowDelete: Boolean,
    val allowMemory: Boolean,
)

data class ApprovalCard(
    val id: String,
    val tool: String,
    val action: String,
    val reason: String,
    val detail: String,
    val diff: String? = null,
    val subagentId: String? = null,
)

enum class PermissionMode(val label: String) {
    ReadOnly("只读"),
    Ask("请求"),
    Auto("自主"),
}

data class CheckpointStep(
    val step: Int,
    val files: Int,
    val at: Long,
    val sample: String = "",
)

data class EditableUserMessage(
    val back: Int,
    val text: String,
)

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
 * 模型库条目（`/api/models/list`）。
 * [vision] / [video] 为三态：null 表示未声明（乐观放行），false 表示明确不支持。
 */
data class LibraryModel(
    val id: String,
    val name: String,
    val provider: String = "",
    val providerName: String = "",
    val ctxIn: Int = 0,
    val ctxOut: Int = 0,
    val vision: Boolean? = null,
    val video: Boolean? = null,
    val rpm: Int = 0,
    val keySource: String = "",
    val keyName: String = "",
    val keySet: Boolean = false,
    val baseUrl: String = "",
    val protocol: String = "",
)

data class SkillInfo(
    val slug: String,
    val name: String,
    val description: String = "",
    val triggers: List<String> = emptyList(),
    val enabled: Boolean = true,
)

data class BuiltinPluginInfo(
    val id: String,
    val name: String,
    val purpose: String = "",
    val whenToUse: String = "",
    val enabled: Boolean = false,
    val triggers: List<String> = emptyList(),
    /** 授权重构子开关；null 表示不支持或旧内核未下发（P-031）。 */
    val allowModify: Boolean? = null,
)

/** 后台任务快照；[output] 为最近 128KiB 尾部输出。 */
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