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
 * 内核 WebSocket 下行帧到 [CoreEvent] 的纯解析器；未登记帧返回 null，保持向前兼容。
 * 注意：同名或相近帧的载荷键名不统一（如 `sessions` 用 `items`，`ready` 用 `sessions`，P-008）。
 */
internal object CoreFrames {

    class Ctx(val o: JSONObject, val sid: String, val nextHistoryId: () -> String)

    private val parsers: Map<String, (Ctx) -> CoreEvent?> = buildMap {
        fun reg(vararg types: String, fn: (Ctx) -> CoreEvent?) {
            for (t in types) put(t, fn)
        }

        reg("ready") { c ->
            CoreEvent.Ready(
                sessions = parseSessions(c.o.optJSONArray("sessions") ?: JSONArray()),
                tools = c.o.optJSONArray("tools")?.let { arr ->
                    buildList { for (i in 0 until arr.length()) add(arr.optString(i)) }
                } ?: emptyList(),
                model = c.o.optJSONObject("config")?.optString("display_name").orEmpty(),
            )
        }

        reg("session") { c -> CoreEvent.SessionClaimed(c.sid, c.o.optString("title")) }

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

        reg("task") { c ->
            CoreEvent.TaskChanged(
                id = c.o.optString("id"),
                status = c.o.optString("status", "running"),
                exitCode = c.o.optInt("exit_code", 0),
            )
        }

        reg("text") { c -> CoreEvent.Delta(c.sid, c.o.optString("text")) }
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

        // checkpoints 帧必须保留帧内原始 session_id，不回 fallback
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

    val knownTypes: Set<String> get() = parsers.keys

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
                    title = displayTitle(o.optString("title")),
                    updatedAt = o.optLong("updated_at", 0L),
                    busy = o.optBoolean("busy", false),
                    workspace = o.optString("workspace"),
                    workspaceName = o.optString("workspace_name"),
                    archived = o.optLong("archived_at", 0L) > 0L,
                ),
            )
        }
    }

    internal fun displayTitle(raw: String): String = when (raw.trim()) {
        "", "新会话", "新建会话", "未命名" -> "新建会话"
        else -> raw
    }

    internal fun parseHistory(arr: JSONArray, nextId: () -> String): List<ChatMessage> = buildList {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val role = when (o.optString("role")) {
                "assistant" -> MsgRole.Assistant
                "user" -> MsgRole.User
                else -> continue
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
