package com.hxy.codeforge.core

import com.hxy.codeforge.model.ApprovalCard
import com.hxy.codeforge.model.ChatMessage
import com.hxy.codeforge.model.CheckpointStep
import com.hxy.codeforge.model.ContextUsage
import com.hxy.codeforge.model.EditableUserMessage
import com.hxy.codeforge.model.GoalStatus
import com.hxy.codeforge.model.MsgRole
import com.hxy.codeforge.model.NoticeKind
import com.hxy.codeforge.model.SessionItem
import com.hxy.codeforge.model.SubagentTask
import com.hxy.codeforge.model.TodoItem
import com.hxy.codeforge.model.TodoStatus
import com.hxy.codeforge.model.ToolCall
import com.hxy.codeforge.model.ToolState
import org.json.JSONArray
import org.json.JSONObject

/**
 * 内核 WS 下行帧 → [CoreEvent] 的纯解析（只依赖 org.json，可纯 JVM 单测）。
 *
 * 一种帧一个解析器，登记在 [parsers]；[parse] 只做「查表、调用」。
 * 帧名与键名以内核 `pkg/agent/agent.go` 与 `pkg/server/ws_handler.go` 为准，
 * 同名帧载荷键不统一的差异（P-008）收在各自解析器里。未登记的帧返回 null，
 * 内核以后新增事件不会让旧壳崩掉。
 *
 * 新增事件：在 [CoreEvent] 加子类、在 `ChatViewModel.onEvent` 加分支、在本文件登记一个解析器。
 */
internal object CoreFrames {

    /** 单帧解析上下文：[o] 原始 JSON，[sid] 已回落到当前会话的会话 id，[nextHistoryId] 历史消息 id 发号器。 */
    class Ctx(val o: JSONObject, val sid: String, val nextHistoryId: () -> String)

    private val parsers: Map<String, (Ctx) -> CoreEvent?> = buildMap {
        fun reg(vararg types: String, fn: (Ctx) -> CoreEvent?) {
            for (t in types) put(t, fn)
        }

        // 首帧 payload：sessions 是数组键；config.display_name 是当前模型名
        reg("ready") { c ->
            CoreEvent.Ready(
                sessions = parseSessions(c.o.optJSONArray("sessions") ?: JSONArray()),
                tools = c.o.optJSONArray("tools")?.let { arr ->
                    buildList { for (i in 0 until arr.length()) add(arr.optString(i)) }
                } ?: emptyList(),
                model = c.o.optJSONObject("config")?.optString("display_name").orEmpty(),
            )
        }

        // title 必须带上：新建会话时内核回的 title 是空的，重命名/切换时是真标题
        reg("session") { c -> CoreEvent.SessionClaimed(c.sid, c.o.optString("title")) }

        // 注意：sessions 帧的数组键是 items，不是 sessions —— 与 ready 帧不同
        reg("sessions") { c ->
            val arr = c.o.optJSONArray("items") ?: c.o.optJSONArray("sessions") ?: JSONArray()
            CoreEvent.Sessions(parseSessions(arr))
        }

        reg("history") { c ->
            CoreEvent.History(
                sessionId = c.sid,
                title = displayTitle(c.o.optString("title", "会话")),
                messages = parseHistory(c.o.optJSONArray("messages") ?: JSONArray(), c.nextHistoryId),
            )
        }

        reg("busy") { c -> CoreEvent.Busy(c.sid) }
        reg("idle", "done") { c -> CoreEvent.Idle(c.sid) }

        // 后台任务状态变化：帧里只有 id/状态，输出由 /api/tasks 拉取，避免大日志挤占 WS
        reg("task") { c ->
            CoreEvent.TaskChanged(
                id = c.o.optString("id"),
                status = c.o.optString("status", "running"),
                exitCode = c.o.optInt("exit_code", 0),
            )
        }

        reg("text") { c -> CoreEvent.Delta(c.sid, c.o.optString("text")) }

        // 思考流是字面量 "reasoning"，不在 agent.go 常量表里
        reg("reasoning") { c -> CoreEvent.Thinking(c.sid, c.o.optString("text")) }

        reg("tool_pending", "tool_call") { c ->
            CoreEvent.ToolStart(
                sessionId = c.sid,
                toolCallId = c.o.optString("tool_call_id"),
                name = c.o.optString("tool_name", "tool"),
                input = c.o.opt("tool_input")?.toString() ?: "",
            )
        }

        reg("tool_result") { c ->
            val result = c.o.optJSONObject("result")
            val isErr = result?.optBoolean("is_error", false)
                ?: result?.optBoolean("success", true)?.not() ?: false
            val stats = c.o.optJSONObject("diff_stats")
            CoreEvent.ToolEnd(
                sessionId = c.sid,
                toolCallId = c.o.optString("tool_call_id"),
                output = result?.opt("content")?.toString()
                    ?: result?.opt("data")?.toString().orEmpty(),
                failed = isErr,
                diffAdded = stats?.optInt("added", 0) ?: 0,
                diffRemoved = stats?.optInt("removed", 0) ?: 0,
                diff = c.o.optString("diff").ifEmpty { null },
            )
        }

        reg("hitl_request") { c ->
            CoreEvent.ApprovalRequested(
                c.sid,
                ApprovalCard(
                    id = c.o.optString("approval_id"),
                    tool = c.o.optString("tool"),
                    action = c.o.optString("action"),
                    reason = c.o.optString("reason"),
                    detail = c.o.optString("detail"),
                    diff = c.o.optString("diff").ifEmpty { null },
                    subagentId = c.o.optString("subagent_id").ifEmpty { null },
                ),
            )
        }

        reg("hitl_timeout") { c ->
            CoreEvent.ApprovalTimedOut(
                approvalId = c.o.optString("approval_id"),
                sessionId = c.sid,
                message = c.o.optString("message", "请求操作审批超时，该操作未执行"),
            )
        }

        reg("hitl_resolved") { c -> CoreEvent.ApprovalResolved(c.o.optString("approval_id")) }

        // 目标模式账本：goal / round / max_rounds / verdict / findings
        reg("goal") { c ->
            CoreEvent.Goal(
                GoalStatus(
                    goal = c.o.optString("goal"),
                    round = c.o.optInt("round", 0),
                    maxRounds = c.o.optInt("max_rounds", 0),
                    verdict = c.o.optString("verdict"),
                    findings = c.o.optString("findings"),
                    step = c.o.optInt("step", 0),
                    open = c.o.optBoolean("open", false),
                    exhausted = c.o.optBoolean("exhausted", false),
                ),
            )
        }

        reg("retry") { c ->
            val attempt = c.o.optInt("attempt", 1)
            val max = c.o.optInt("max_attempts", 1)
            val cause = c.o.optString("error").ifEmpty { "上游瞬时故障" }
            CoreEvent.NoticeEvent(c.sid, NoticeKind.Retry, "$cause，正在重试（$attempt/$max）")
        }

        reg("compress") { c ->
            // 面板字段既可能嵌在 compress 对象里，也可能平铺在帧顶层（P-008）
            val p = c.o.optJSONObject("compress") ?: c.o
            val before = p.optLong("before", 0)
            val after = p.optLong("after", 0)
            val reason = p.optString("reason").orEmpty()
            val degraded = p.optBoolean("degraded", false)
            val text = buildString {
                append(NOTICE_COMPRESS_DONE)
                append(" · ")
                append(if (before > 0) "$before → $after tokens" else "已摘要早期对话")
                if (degraded) append("（压缩失败，改为截断最旧消息）")
                if (reason.isNotBlank()) append(" · $reason")
            }
            CoreEvent.NoticeEvent(c.sid, NoticeKind.Compress, text)
        }

        reg("rewind") { c ->
            val r = c.o.optJSONObject("rewind")
            val restored = r?.optInt("restored", 0) ?: 0
            val deleted = r?.optInt("deleted", 0) ?: 0
            val failed = r?.optInt("failed", 0) ?: 0
            val text = buildString {
                append("文件已回滚到检查点：还原 $restored 个")
                if (deleted > 0) append("，删除 $deleted 个")
                if (failed > 0) append("，$failed 个失败")
            }
            CoreEvent.NoticeEvent(c.sid, NoticeKind.Rewind, text)
        }

        reg("steer") { c -> CoreEvent.NoticeEvent(c.sid, NoticeKind.Steer, "转向指令已并入当前上下文") }
        reg("steer_queued") { c ->
            CoreEvent.NoticeEvent(c.sid, NoticeKind.Steer, "转向指令已排队，当前步骤结束后并入")
        }
        reg("info", "workspace_blocked") { c ->
            CoreEvent.NoticeEvent(c.sid, NoticeKind.Info, c.o.optString("text"))
        }

        // edit：历史被截断重发，视图交给随后的 history 快照重建，这里只作废流式态
        reg("edit") { c -> CoreEvent.StreamReset(c.sid) }

        reg("todo") { c ->
            val arr = c.o.optJSONArray("todos") ?: JSONArray()
            val items = buildList {
                for (i in 0 until arr.length()) {
                    val t = arr.optJSONObject(i) ?: continue
                    add(
                        TodoItem(
                            content = t.optString("content"),
                            status = when (t.optString("status")) {
                                "in_progress" -> TodoStatus.InProgress
                                "completed" -> TodoStatus.Completed
                                "cancelled" -> TodoStatus.Cancelled
                                else -> TodoStatus.Pending
                            },
                            priority = t.optInt("priority", 0),
                            sort = t.optInt("sort", i),
                        ),
                    )
                }
            }
            CoreEvent.Todos(c.sid, items)
        }

        reg("context") { c ->
            CoreEvent.ContextUpdate(
                c.sid,
                ContextUsage(
                    used = c.o.optLong("used", 0),
                    budget = c.o.optLong("budget", 0),
                    percent = c.o.optDouble("percent", 0.0).toFloat(),
                    model = c.o.optString("model"),
                    overBudget = c.o.optBoolean("over_budget", false),
                    // window = 模型库的 ctx_in（0 = 未声明；内核按默认预算 120000 压缩）
                    window = c.o.optLong("window", 0),
                    compressed = c.o.optBoolean("compressed", false),
                ),
            )
        }

        reg("subagent") { c ->
            val s = c.o.optJSONObject("subagent") ?: return@reg null
            CoreEvent.SubagentUpdate(
                c.sid,
                SubagentTask(
                    id = s.optString("id"),
                    mode = s.optString("mode"),
                    status = s.optString("status"),
                    detail = s.optString("detail"),
                    summary = s.optString("summary"),
                ),
            )
        }

        // checkpoints：steps 按「文件改动步」聚合，editables 给出可编辑锚点 back，
        // resume_back>=0 表示有未跑完的轮次可续。注意 sessionId 取帧里的原值，不回落当前会话。
        reg("checkpoints") { c ->
            val stepsArr = c.o.optJSONArray("steps")
            val steps = (0 until (stepsArr?.length() ?: 0)).mapNotNull { i ->
                val so = stepsArr?.optJSONObject(i) ?: return@mapNotNull null
                CheckpointStep(
                    step = so.optInt("step"),
                    files = so.optInt("files"),
                    at = so.optLong("at"),
                    sample = so.optString("sample"),
                )
            }
            val editArr = c.o.optJSONArray("editables")
            val editables = (0 until (editArr?.length() ?: 0)).mapNotNull { i ->
                val eo = editArr?.optJSONObject(i) ?: return@mapNotNull null
                EditableUserMessage(back = eo.optInt("back"), text = eo.optString("text"))
            }
            CoreEvent.Checkpoints(
                sessionId = c.o.optString("session_id"),
                steps = steps,
                editables = editables,
                resumeBack = c.o.optInt("resume_back", -1),
            )
        }

        reg("error") { c -> CoreEvent.Failure(c.o.optString("error", "未知错误")) }
        reg("warn") { c -> CoreEvent.Warning(c.o.optString("error", "请求过于频繁，稍后重试")) }
    }

    /** 已登记的帧名（测试用：新增解析器必须补用例）。 */
    val knownTypes: Set<String> get() = parsers.keys

    /**
     * 解析一帧。[currentSession] 是传输层当前会话（帧没带 session_id 时回落到它）。
     * 未知帧名返回 null。
     */
    fun parse(o: JSONObject, currentSession: String, nextHistoryId: () -> String): CoreEvent? {
        val sid = o.optString("session_id").ifEmpty { currentSession }
        val parser = parsers[o.optString("type")] ?: return null
        return parser(Ctx(o, sid, nextHistoryId))
    }

    internal fun parseSessions(arr: JSONArray): List<SessionItem> = buildList {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            add(
                SessionItem(
                    id = o.optString("id"),
                    // optString 的默认值只在「键不存在」时生效，title 是空串时仍会返回 ""；
                    // 占位名统一在这里归一，列表里不会出现空白行。
                    title = displayTitle(o.optString("title")),
                    updatedAt = o.optLong("updated_at", 0L),
                    busy = o.optBoolean("busy", false),
                    // 字段名以 agent.SessionMeta 为准：workspace / workspace_name / archived_at
                    workspace = o.optString("workspace"),
                    workspaceName = o.optString("workspace_name"),
                    archived = o.optLong("archived_at", 0L) > 0L,
                ),
            )
        }
    }

    /**
     * 会话标题的展示归一：内核新建会话的默认标题是「新会话」，界面统一显示「新建会话」；
     * 只处理占位名，不碰用户/内核起的真标题。
     */
    internal fun displayTitle(raw: String): String = when (raw.trim()) {
        "", "新会话", "新建会话", "未命名" -> "新建会话"
        else -> raw
    }

    /**
     * 历史消息是 `llm.Message` 结构：`{role, content: [{type, ...}], origin?}`。
     * 块类型三种都要认：text（正文）、tool_use（挂到当前助手消息的工具卡，历史态无 Running）、
     * tool_result（按 tool_use_id 回填对应卡片结果）。块顺序在 content 数组里天然保序。
     */
    internal fun parseHistory(arr: JSONArray, nextId: () -> String): List<ChatMessage> = buildList {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val role = when (o.optString("role")) {
                "assistant" -> MsgRole.Assistant
                "user" -> MsgRole.User
                else -> continue // system/tool 角色不直接成泡
            }
            val blocks = o.optJSONArray("content") ?: continue

            for (j in 0 until blocks.length()) {
                val b = blocks.optJSONObject(j) ?: continue
                when (b.optString("type")) {
                    "text" -> {
                        val text = b.optString("text")
                        if (text.isNotEmpty()) {
                            add(ChatMessage(id = nextId(), role = role, text = text, time = 0L))
                        }
                    }

                    "tool_use" -> {
                        // 工具卡挂在最后一条助手消息上；没有就补一条空助手消息承载
                        var idx = indexOfLast { it.role == MsgRole.Assistant }
                        if (idx < 0) {
                            add(ChatMessage(id = nextId(), role = MsgRole.Assistant, text = "", time = 0L))
                            idx = lastIndex
                        }
                        val target = this[idx]
                        this[idx] = target.copy(
                            tools = target.tools + ToolCall(
                                id = b.optString("id"),
                                name = b.optString("name", "tool"),
                                input = b.opt("input")?.toString() ?: "",
                                state = ToolState.Ok,
                            ),
                        )
                    }

                    "tool_result" -> {
                        val useId = b.optString("tool_use_id")
                        val content = b.opt("content")?.toString().orEmpty()
                        val isErr = b.optBoolean("is_error", false)
                        // 从后往前找同一轮里 id 匹配的工具卡（跨消息也认）
                        for (k in indices.reversed()) {
                            val at = this[k].tools.indexOfFirst { it.id == useId }
                            if (at >= 0) {
                                val m = this[k]
                                val tools = m.tools.toMutableList()
                                tools[at] = tools[at].copy(
                                    output = content,
                                    state = if (isErr) ToolState.Failed else ToolState.Ok,
                                )
                                this[k] = m.copy(tools = tools)
                                break
                            }
                        }
                    }
                }
            }
        }
    }
}
