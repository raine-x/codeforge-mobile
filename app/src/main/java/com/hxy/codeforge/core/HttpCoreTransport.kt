package com.hxy.codeforge.core

import com.hxy.codeforge.model.ApprovalCard
import com.hxy.codeforge.model.ChatMessage
import com.hxy.codeforge.model.ContextUsage
import com.hxy.codeforge.model.MsgRole
import com.hxy.codeforge.model.NoticeKind
import com.hxy.codeforge.model.SessionItem
import com.hxy.codeforge.model.SubagentTask
import com.hxy.codeforge.model.TodoItem
import com.hxy.codeforge.model.TodoStatus
import com.hxy.codeforge.model.ToolCall
import com.hxy.codeforge.model.ToolState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 真实内核连接：HTTP + WebSocket，打 127.0.0.1:<内核随机端口>。
 *
 * ## 鉴权是 Cookie，不是 query 参数
 *
 * 内核 `validToken()` 只读名为 `codeforge_token` 的 Cookie（`TokenCookie` 常量），
 * 不看 `?token=`。令牌由 `handleRoot`（GET `/`）通过 Set-Cookie 下发。
 * 所以连接顺序必须是：**先 GET `/` 收下 Cookie，再连 `/ws`**。
 * 顺序反了会直接 401 —— 这是接壳时最容易踩的一处。
 *
 * ## 端口从哪来
 *
 * 内核在 `port: 0` 时会绑定随机端口并把 `PORT=xxxxx` 打到 stdout 首行，
 * [CoreService] 读到后传进来。此处不自己猜端口。
 */
class HttpCoreTransport(
    private val host: String,
    private val port: Int,
) : CoreTransport {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<CoreEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<CoreEvent> = _events.asSharedFlow()

    private val cookieJar = MemoryCookieJar()

    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(5, TimeUnit.SECONDS)
        // WS 是长连接，读超时必须关掉，否则空闲几十秒就被自己断开。
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private var currentSession = ""
    private var msgSeq = 0

    /** VM 注入的页面可见性快照：连上时随首帧上报真实值 */
    @Volatile private var visibilityHidden: Boolean = false

    private val baseUrl: String get() = "http://$host:$port"

    /** 自愈循环是否在跑。重复 connect() 不叠加循环。 */
    private val running = AtomicBoolean(false)
    private var loopJob: Job? = null

    /**
     * 建立连接，并**一直维持**它。
     *
     * 旧实现只尝试一次：失败就发一个 Failure 然后彻底放弃，于是
     * 「内核 bind 成功但 accept 还没就绪」这种毫秒级窗口会把界面永久卡在
     * 「未连接」（通知栏显示内核运行中、设置页说未连接，就是这个症状）。
     * 现在改成自愈循环：健康探测 → 取 Cookie → 连 WS → 挂起等断 → 退避重连，
     * 直到 [disconnect] 被调用。
     */
    override fun connect() {
        if (!running.compareAndSet(false, true)) return
        loopJob = scope.launch {
            var attempt = 0
            while (running.get()) {
                val startedAt = System.currentTimeMillis()
                val everOpen = try {
                    connectOnce()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
                if (!running.get()) break

                // 连上并稳定运行超过 30 秒再断 → 视为正常断开，退避归零重来；
                // 连上就断 / 根本连不上 → 累加退避。
                val stable = everOpen && System.currentTimeMillis() - startedAt > 30_000
                attempt = if (stable) 1 else attempt + 1
                val wait = backoffMs(attempt)
                _events.tryEmit(CoreEvent.Link(connected = false, attempt = attempt, retryInMs = wait))
                delay(wait)
            }
        }
    }

    /**
     * 一次完整连接尝试，**挂起直到链路断开**。
     *
     * 返回「是否曾经连上过」——决定退避是否重置。
     */
    private suspend fun connectOnce(): Boolean {
        // 1. 健康探测。只探 TCP 端口不够：内核可能已 bind 但 handler 还没接客，
        //    这时 TCP 能连上、HTTP 却拿不到响应。/api/health 免鉴权，正好用来探。
        val healthOk = withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url("$baseUrl/api/health").build())
                    .execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }
        if (!healthOk) return false

        // 2. 取 Cookie。内核重启后 token 会变，所以每次重连都重新拿一遍。
        val cookieOk = withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url("$baseUrl/").build())
                    .execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }
        if (!cookieOk) return false

        // 3. 连 WS，挂起等它断开
        val done = CompletableDeferred<Boolean>()
        val everOpen = AtomicBoolean(false)
        val req = Request.Builder().url("ws://$host:$port/ws").build()
        val socket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                everOpen.set(true)
                // 链路一通就报「已连接」，**不等 ready 帧**。
                // ready 只是带会话与配置的附加载荷，它没来不该让界面显示未连接。
                _events.tryEmit(CoreEvent.Link(connected = true))
                send(JSONObject().put("type", "visibility").put("hidden", visibilityHidden))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { handleFrame(text) }
                    .onFailure { _events.tryEmit(CoreEvent.Failure("解析内核帧失败：${it.message}")) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!done.isCompleted) done.complete(everOpen.get())
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!done.isCompleted) done.complete(everOpen.get())
            }
        })
        ws = socket
        return done.await()
    }

    /** 退避：1 → 2 → 4 → 8s 封顶。内核重启通常 1~3 秒，再长只会让界面显得更迟钝。 */
    private fun backoffMs(attempt: Int): Long =
        (1_000L shl (attempt - 1).coerceIn(0, 3)).coerceAtMost(8_000L)

    override fun disconnect() {
        running.set(false)
        loopJob?.cancel()
        loopJob = null
        ws?.close(1000, "client closing")
        ws = null
        // 不 shutdown client：自愈循环可能还要用它重连。
        // OkHttp 的空闲连接与线程会在 60 秒后自行回收。
    }

    override fun sendMessage(text: String, attachments: List<String>) {
        val o = JSONObject()
            .put("type", "user_message")
            // ⚠️ 必须带 session_id：服务端对空 session_id 会 History.Create，
            // 表现就是「说一句话新建一个会话」。currentSession 由 session/history 帧回填。
            .put("session_id", currentSession)
            .put("text", text)
        if (attachments.isNotEmpty()) {
            o.put("attachments", JSONArray(attachments))
        }
        send(o)
    }

    override fun steer(text: String) {
        send(
            JSONObject()
                .put("type", "steer")
                .put("session_id", currentSession)
                .put("text", text),
        )
    }

    override fun regenerate() {
        send(
            JSONObject()
                .put("type", "regenerate")
                .put("session_id", currentSession),
        )
    }

    override fun pullContext() {
        send(
            JSONObject()
                .put("type", "context")
                .put("session_id", currentSession),
        )
    }

    override fun pullTodos() {
        send(
            JSONObject()
                .put("type", "todos")
                .put("session_id", currentSession),
        )
    }

    override fun sendVisibility(hidden: Boolean) {
        visibilityHidden = hidden
        send(JSONObject().put("type", "visibility").put("hidden", hidden))
    }

    override fun cancel() {
        // 按会话打断：不带 id 时服务端只在「恰好一个会话在跑」时才停，
        // 多会话并发时可能停错/不停。
        send(JSONObject().put("type", "cancel").put("session_id", currentSession))
    }

    // ---- 断点三件套上行（B6-P0，帧名与键对照 ws_handler.go） ----
    override fun editUserMessage(text: String, back: Int, rollbackFiles: Boolean) {
        send(
            JSONObject()
                .put("type", "edit_user_message")
                .put("session_id", currentSession)
                .put("text", text)
                .put("back", back)
                .put("rollback_files", rollbackFiles),
        )
    }

    override fun continueTurn() {
        send(JSONObject().put("type", "continue_turn").put("session_id", currentSession))
    }

    override fun retry(back: Int, rollbackFiles: Boolean) {
        send(
            JSONObject()
                .put("type", "retry")
                .put("session_id", currentSession)
                .put("back", back.coerceAtLeast(0))
                .put("rollback_files", rollbackFiles),
        )
    }

    override fun rewindFiles(toStep: Int) {
        send(
            JSONObject()
                .put("type", "rewind")
                .put("session_id", currentSession)
                .put("to_step", toStep),
        )
    }

    override fun pullCheckpoints() {
        send(JSONObject().put("type", "checkpoints").put("session_id", currentSession))
    }

    override fun decideApproval(approvalId: String, allow: Boolean) {
        send(
            JSONObject()
                .put("type", "hitl_decision")
                .put("approval_id", approvalId)
                .put("approved", allow),
        )
    }

    override fun listSessions() {
        send(JSONObject().put("type", "list_sessions"))
    }

    override fun loadSession(sessionId: String) {
        currentSession = sessionId
        send(JSONObject().put("type", "load_session").put("session_id", sessionId))
    }

    /**
     * 同步当前会话，不发帧。
     *
     * 新建会话成功后内核只回 `session` 帧（不是 `history`），旧实现只在
     * history 分支里写 currentSession —— 于是新会话的下一条 user_message
     * 仍带旧会话 id：要么把消息送进旧会话，要么在内核已经重启/清理后
     * 因 id 为空而触发 History.Create，表现就是「发一句又自动新开一个对话」。
     */
    override fun setCurrentSession(sessionId: String) {
        if (sessionId.isNotBlank()) currentSession = sessionId
    }

    override fun newSession(): Boolean =
        send(JSONObject().put("type", "new_session"))

    /**
     * 发一帧；返回是否真的发出去了。
     *
     * ⚠️ 之前是 `ws?.send(...)` 直接吞掉失败：WS 还没连上或刚断开时，
     * 帧被静默丢弃，调用方以为成功 —— 表现就是「点新建对话没反应」。
     */
    private fun send(obj: JSONObject): Boolean {
        val socket = ws ?: return false
        return runCatching { socket.send(obj.toString()) }.getOrDefault(false)
    }

    /**
     * 解析内核帧。
     *
     * 帧类型清单对齐 pkg/agent/agent.go 的事件常量表与 ws_handler 的控制帧
     * （契约清单一共 22 种下行）。未识别的一律忽略 —— 内核以后新增事件
     * 不应该让旧壳崩掉。人话文案在这里拼好，ViewModel 只管折叠状态。
     */
    private fun handleFrame(raw: String) {
        val o = JSONObject(raw)
        val sid = o.optString("session_id").ifEmpty { currentSession }
        when (o.optString("type")) {
            "ready" -> {
                // 首帧 payload：sessions 是数组键；config.display_name 是当前模型名
                val sessions = o.optJSONArray("sessions") ?: JSONArray()
                _events.tryEmit(
                    CoreEvent.Ready(
                        sessions = parseSessions(sessions),
                        tools = o.optJSONArray("tools")?.let { arr ->
                            buildList { for (i in 0 until arr.length()) add(arr.optString(i)) }
                        } ?: emptyList(),
                        model = o.optJSONObject("config")?.optString("display_name").orEmpty(),
                    ),
                )
            }

            // title 必须带上：新建会话时内核回的 title 是空的，但重命名/切换
            // 时是真标题。之前丢掉它，顶栏会一直显示上一个会话的名字，
            // 用户以为「新建没成功」。
            "session" -> {
                // 新建会话即确立当前会话：后续上行帧必须带它（见 setCurrentSession 注释）
                currentSession = sid
                _events.tryEmit(
                    CoreEvent.SessionClaimed(sid, o.optString("title")),
                )
            }

            // 注意：sessions 帧的数组键是 items，不是 sessions —— 与 ready 帧不同
            "sessions" -> {
                val arr = o.optJSONArray("items") ?: o.optJSONArray("sessions") ?: JSONArray()
                _events.tryEmit(CoreEvent.Sessions(parseSessions(arr)))
            }

            "history" -> {
                currentSession = sid
                val arr = o.optJSONArray("messages") ?: JSONArray()
                _events.tryEmit(
                    CoreEvent.History(
                        sessionId = sid,
                        title = displayTitle(o.optString("title", "会话")),
                        messages = parseHistory(arr),
                    ),
                )
            }

            "busy" -> _events.tryEmit(CoreEvent.Busy(sid))
            "idle", "done" -> _events.tryEmit(CoreEvent.Idle(sid))

            // 后台任务状态变化（pkg/tasks 的 Event → Server 广播）。
            // 帧里只有 id/状态：输出由 /api/tasks 拉取，避免大日志挤占 WS。
            "task" -> _events.tryEmit(
                CoreEvent.TaskChanged(
                    id = o.optString("id"),
                    status = o.optString("status", "running"),
                    exitCode = o.optInt("exit_code", 0),
                ),
            )

            "text" -> _events.tryEmit(CoreEvent.Delta(sid, o.optString("text")))

            // 思考流是字面量 "reasoning"，不在 agent.go 常量表里
            "reasoning" -> _events.tryEmit(CoreEvent.Thinking(sid, o.optString("text")))

            "tool_pending", "tool_call" -> {
                val id = o.optString("tool_call_id")
                _events.tryEmit(
                    CoreEvent.ToolStart(
                        sessionId = sid,
                        toolCallId = id,
                        name = o.optString("tool_name", "tool"),
                        input = o.opt("tool_input")?.toString() ?: "",
                    ),
                )
            }

            "tool_result" -> {
                val result = o.optJSONObject("result")
                val isErr = result?.optBoolean("is_error", false)
                    ?: result?.optBoolean("success", true)?.not() ?: false
                val stats = o.optJSONObject("diff_stats")
                _events.tryEmit(
                    CoreEvent.ToolEnd(
                        sessionId = sid,
                        toolCallId = o.optString("tool_call_id"),
                        output = result?.opt("content")?.toString()
                            ?: result?.opt("data")?.toString().orEmpty(),
                        failed = isErr,
                        diffAdded = stats?.optInt("added", 0) ?: 0,
                        diffRemoved = stats?.optInt("removed", 0) ?: 0,
                        diff = o.optString("diff").ifEmpty { null },
                    ),
                )
            }

            "hitl_request" -> {
                _events.tryEmit(
                    CoreEvent.ApprovalRequested(
                        sid,
                        ApprovalCard(
                            id = o.optString("approval_id"),
                            tool = o.optString("tool"),
                            action = o.optString("action"),
                            reason = o.optString("reason"),
                            detail = o.optString("detail"),
                            diff = o.optString("diff").ifEmpty { null },
                            subagentId = o.optString("subagent_id").ifEmpty { null },
                        ),
                    ),
                )
            }

            "hitl_timeout" -> _events.tryEmit(
                CoreEvent.ApprovalTimedOut(
                    approvalId = o.optString("approval_id"),
                    sessionId = sid,
                    message = o.optString("message", "请求操作审批超时，该操作未执行"),
                ),
            )

            "hitl_resolved" -> _events.tryEmit(
                CoreEvent.ApprovalResolved(o.optString("approval_id")),
            )

            // 目标模式账本（新增契约）：goal / round / max_rounds / verdict / findings
            "goal" -> _events.tryEmit(
                CoreEvent.Goal(
                    com.hxy.codeforge.model.GoalStatus(
                        goal = o.optString("goal"),
                        round = o.optInt("round", 0),
                        maxRounds = o.optInt("max_rounds", 0),
                        verdict = o.optString("verdict"),
                        findings = o.optString("findings"),
                        step = o.optInt("step", 0),
                        open = o.optBoolean("open", false),
                        exhausted = o.optBoolean("exhausted", false),
                    ),
                ),
            )

            "retry" -> {
                val attempt = o.optInt("attempt", 1)
                val max = o.optInt("max_attempts", 1)
                val cause = o.optString("error").ifEmpty { "上游瞬时故障" }
                _events.tryEmit(
                    CoreEvent.NoticeEvent(sid, NoticeKind.Retry, "$cause，正在重试（$attempt/$max）"),
                )
            }

            "compress" -> {
                // 面板字段既可能嵌在 compress 对象里，也可能平铺在帧顶层；
                // 缺字段时按平铺回落（内核两拨构造点键名各定各的，见 P-008）
                val c = o.optJSONObject("compress") ?: o
                val before = c.optLong("before", 0)
                val after = c.optLong("after", 0)
                val reason = c.optString("reason").orEmpty()
                val degraded = c.optBoolean("degraded", false)
                val text = buildString {
                    append(NOTICE_COMPRESS_DONE)
                    append(" · ")
                    append(if (before > 0) "$before → $after tokens" else "已摘要早期对话")
                    if (degraded) append("（压缩失败，改为截断最旧消息）")
                    if (reason.isNotBlank()) append(" · $reason")
                }
                _events.tryEmit(CoreEvent.NoticeEvent(sid, NoticeKind.Compress, text))
            }

            "rewind" -> {
                val r = o.optJSONObject("rewind")
                val restored = r?.optInt("restored", 0) ?: 0
                val deleted = r?.optInt("deleted", 0) ?: 0
                val failed = r?.optInt("failed", 0) ?: 0
                val text = buildString {
                    append("文件已回滚到检查点：还原 $restored 个")
                    if (deleted > 0) append("，删除 $deleted 个")
                    if (failed > 0) append("，$failed 个失败")
                }
                _events.tryEmit(CoreEvent.NoticeEvent(sid, NoticeKind.Rewind, text))
            }

            "steer" -> _events.tryEmit(
                CoreEvent.NoticeEvent(sid, NoticeKind.Steer, "转向指令已并入当前上下文"),
            )

            "steer_queued" -> _events.tryEmit(
                CoreEvent.NoticeEvent(sid, NoticeKind.Steer, "转向指令已排队，当前步骤结束后并入"),
            )

            "info" -> _events.tryEmit(
                CoreEvent.NoticeEvent(sid, NoticeKind.Info, o.optString("text")),
            )

            "workspace_blocked" -> _events.tryEmit(
                CoreEvent.NoticeEvent(sid, NoticeKind.Info, o.optString("text")),
            )

            // edit：历史被截断重发，视图交给随后的 history 快照重建，这里只作废流式态
            "edit" -> _events.tryEmit(CoreEvent.StreamReset(sid))

            "todo" -> {
                val arr = o.optJSONArray("todos") ?: JSONArray()
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
                _events.tryEmit(CoreEvent.Todos(sid, items))
            }

            "context" -> _events.tryEmit(
                CoreEvent.ContextUpdate(
                    sid,
                    ContextUsage(
                        used = o.optLong("used", 0),
                        budget = o.optLong("budget", 0),
                        percent = o.optDouble("percent", 0.0).toFloat(),
                        model = o.optString("model"),
                        overBudget = o.optBoolean("over_budget", false),
                        // window = 模型库的 ctx_in（0 = 未声明；内核此时按
                        // 默认预算 120000 压缩，设置页要据此提示）
                        window = o.optLong("window", 0),
                        compressed = o.optBoolean("compressed", false),
                    ),
                ),
            )

            "subagent" -> {
                val s = o.optJSONObject("subagent") ?: return
                _events.tryEmit(
                    CoreEvent.SubagentUpdate(
                        sid,
                        SubagentTask(
                            id = s.optString("id"),
                            mode = s.optString("mode"),
                            status = s.optString("status"),
                            detail = s.optString("detail"),
                            summary = s.optString("summary"),
                        ),
                    ),
                )
            }

            // checkpoints：断点回滚/编辑重发数据源（steps 按「文件改动步」聚合，
            // editables 给出可编辑锚点 back，resume_back>=0 表示有未跑完的轮次可续）
            "checkpoints" -> {
                val steps = (0 until (o.optJSONArray("steps")?.length() ?: 0)).mapNotNull { i ->
                    val so = o.optJSONArray("steps").optJSONObject(i) ?: return@mapNotNull null
                    com.hxy.codeforge.model.CheckpointStep(
                        step = so.optInt("step"),
                        files = so.optInt("files"),
                        at = so.optLong("at"),
                        sample = so.optString("sample"),
                    )
                }
                val editables = (0 until (o.optJSONArray("editables")?.length() ?: 0)).mapNotNull { i ->
                    val eo = o.optJSONArray("editables").optJSONObject(i) ?: return@mapNotNull null
                    com.hxy.codeforge.model.EditableUserMessage(
                        back = eo.optInt("back"),
                        text = eo.optString("text"),
                    )
                }
                _events.tryEmit(
                    CoreEvent.Checkpoints(
                        sessionId = o.optString("session_id"),
                        steps = steps,
                        editables = editables,
                        resumeBack = o.optInt("resume_back", -1),
                    ),
                )
            }

            "error" -> _events.tryEmit(CoreEvent.Failure(o.optString("error", "未知错误")))
            "warn" -> _events.tryEmit(CoreEvent.Warning(o.optString("error", "请求过于频繁，稍后重试")))
        }
    }

    private fun parseSessions(arr: JSONArray): List<SessionItem> = buildList {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            add(
                SessionItem(
                    id = o.optString("id"),
                    // optString 的默认值只在「键不存在」时生效，title 是空串时
                    // 仍会返回 ""；占位名统一在这里归一，列表里不会出现空白行。
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
     * 会话标题的展示归一。
     *
     * 内核新建会话的默认标题是「新会话」，而界面按需求统一显示「新建会话」；
     * 首条用户消息发出后内核会并发静默请求 AI 生成小标题并推送 `sessions` 帧刷新，
     * 这里只处理占位名，不碰用户/内核起的真标题。
     */
    private fun displayTitle(raw: String): String = when (raw.trim()) {
        "", "新会话", "新建会话", "未命名" -> "新建会话"
        else -> raw
    }

    /**
     * 历史消息是 `llm.Message` 结构：`{role, content: [{type, ...}], origin?}`。
     *
     * 块类型三种都要认：
     *  - text → 用户/助手正文；
     *  - tool_use → 挂到当前（或新建的）助手消息上的工具卡片，历史态无 Running；
     *  - tool_result → role=user/tool 消息携带，按 tool_use_id 回填对应卡片的结果。
     * 文本块与工具块的顺序在 content 数组里天然保序，逐块追加即可。
     */
    private fun parseHistory(arr: JSONArray): List<ChatMessage> = buildList {
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
                            add(
                                ChatMessage(
                                    id = "h-${msgSeq++}",
                                    role = role,
                                    text = text,
                                    time = 0L,
                                ),
                            )
                        }
                    }

                    "tool_use" -> {
                        // 工具卡挂在最后一条助手消息上；没有就补一条空助手消息承载
                        var idx = indexOfLast { it.role == MsgRole.Assistant }
                        if (idx < 0) {
                            add(
                                ChatMessage(
                                    id = "h-${msgSeq++}",
                                    role = MsgRole.Assistant,
                                    text = "",
                                    time = 0L,
                                ),
                            )
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

    /** 内存 CookieJar：只服务一个 host，不需要持久化。 */
    private class MemoryCookieJar : CookieJar {
        private val store = mutableListOf<Cookie>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            synchronized(store) {
                for (c in cookies) {
                    store.removeAll { it.name == c.name }
                    store.add(c)
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            synchronized(store) { store.toList() }
    }
}
