package app.codeforge.core

import app.codeforge.model.ApprovalCard
import app.codeforge.model.ChatMessage
import app.codeforge.model.ContextUsage
import app.codeforge.model.MsgRole
import app.codeforge.model.NoticeKind
import app.codeforge.model.SessionItem
import app.codeforge.model.SubagentTask
import app.codeforge.model.TodoItem
import app.codeforge.model.TodoStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * 界面开发期用的假内核。
 *
 * 存在的理由：libcodeforge.so 还没接进来，但界面的所有交互（流式输出、工具卡片、
 * 审批、打断、贴底跟随）都要能验证。它产出的 [CoreEvent] 与真实内核同构，
 * 换成 [HttpCoreTransport] 时 UI 一行不用改。
 *
 * 故意把一轮对话编得比较全：思考 → 正文流式 → 读文件 → 写文件（触发审批）→ 收尾。
 * 这样一次点击就能把 P0 的所有界面元素都过一遍。
 */
class MockCoreTransport : CoreTransport {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _events = MutableSharedFlow<CoreEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<CoreEvent> = _events.asSharedFlow()

    private val idGen = AtomicInteger(0)
    private var runJob: Job? = null

    private val sessions = mutableListOf(
        SessionItem("s1", "把端口改成随机绑定", System.currentTimeMillis() - 120_000, false),
        SessionItem("s2", "排查 nativeLibraryDir 的 exec 限制", System.currentTimeMillis() - 3_600_000, false),
        SessionItem("s3", "重写消息列表的贴底逻辑", System.currentTimeMillis() - 86_400_000, false),
    )
    private var currentSession = "s1"

    private fun nextId(prefix: String) = "$prefix-${idGen.incrementAndGet()}"

    override fun connect() {
        scope.launch {
            delay(150)
            emit(
                CoreEvent.Ready(
                    sessions = sessions.toList(),
                    tools = listOf("read_file", "edit_file", "run_command", "grep"),
                    model = "Sonnet 5.5",
                ),
            )
            emit(CoreEvent.Sessions(sessions.toList()))
            emit(
                CoreEvent.History(
                    sessionId = currentSession,
                    title = "把端口改成随机绑定",
                    messages = seedHistory(),
                ),
            )
            emit(
                CoreEvent.Todos(
                    currentSession,
                    listOf(
                        TodoItem("定位端口读取点", TodoStatus.Completed, 0, 0),
                        TodoItem("改 Addr() 支持 port:0", TodoStatus.InProgress, 1, 1),
                        TodoItem("验证 stdout 握手", TodoStatus.Pending, 1, 2),
                    ),
                ),
            )
            emit(
                CoreEvent.ContextUpdate(
                    currentSession,
                    ContextUsage(used = 18_432, budget = 200_000, percent = 9.2f, model = "Sonnet 5.5"),
                ),
            )
        }
    }

    /** 预置两条历史消息，让界面一进来就有内容可看（而不是一片空白）。 */
    private fun seedHistory(): List<ChatMessage> = listOf(
        ChatMessage(
            id = "seed-1",
            role = MsgRole.User,
            text = "帮我看下内核的端口是怎么定的，我想改成随机绑定。",
            time = System.currentTimeMillis() - 180_000,
        ),
        ChatMessage(
            id = "seed-2",
            role = MsgRole.Assistant,
            text = "端口目前是从 `config/default.yaml` 的 `server.port` 读死值，`Server.Addr()` 直接用。改成随机绑定要动两处：",
            tools = listOf(
                app.codeforge.model.ToolCall(
                    id = "seed-tool-1",
                    name = "read_file",
                    input = """{"path":"pkg/server/server.go"}""",
                    output = "137: // Port 返回监听端口\n140: func (s *Server) Addr() string {",
                    state = app.codeforge.model.ToolState.Ok,
                ),
            ),
            time = System.currentTimeMillis() - 150_000,
        ),
    )

    override fun disconnect() {
        runJob?.cancel()
        runJob = null
        // 整个作用域一起收掉：ViewModel 销毁后不该还有协程在往外发事件。
        scope.cancel()
    }

    override fun listSessions() {
        emit(CoreEvent.Sessions(sessions.toList()))
    }

    override fun loadSession(sessionId: String) {
        currentSession = sessionId
        val title = sessions.firstOrNull { it.id == sessionId }?.title ?: "会话"
        emit(
            CoreEvent.History(
                sessionId = sessionId,
                title = title,
                messages = if (sessionId == "s1") seedHistory() else emptyList(),
            ),
        )
        emit(CoreEvent.Todos(sessionId, emptyList()))
    }

    override fun newSession(): Boolean {
        val id = nextId("s")
        sessions.add(0, SessionItem(id, "新会话", System.currentTimeMillis(), false))
        currentSession = id
        emit(CoreEvent.Sessions(sessions.toList()))
        emit(CoreEvent.History(id, "新会话", emptyList()))
        emit(CoreEvent.Todos(id, emptyList()))
        emit(CoreEvent.ContextUpdate(id, ContextUsage(0, 200_000, 0f, "Sonnet 5.5")))
        return true
    }

    override fun steer(text: String) {
        emit(
            CoreEvent.NoticeEvent(currentSession, NoticeKind.Steer, "转向指令已并入当前上下文"),
        )
    }

    override fun regenerate() {
        runJob?.cancel()
        runJob = scope.launch {
            emit(CoreEvent.Busy(currentSession))
            delay(300)
            streamText("换个思路：其实只要在 `Addr()` 里把 `port == 0` 分支交给 `net.JoinHostPort(host, \"0\")`，其余交给系统。")
            delay(150)
            emit(CoreEvent.Idle(currentSession))
            emit(
                CoreEvent.ContextUpdate(
                    currentSession,
                    ContextUsage(23_104, 200_000, percent = 11.6f, model = "Sonnet 5.5"),
                ),
            )
        }
    }

    override fun cancel() {
        runJob?.cancel()
        runJob = null
        emit(CoreEvent.Idle(currentSession))
    }

    override fun decideApproval(approvalId: String, allow: Boolean) {
        runJob?.cancel()
        runJob = scope.launch {
            emit(CoreEvent.ApprovalResolved(approvalId))
            delay(200)
            if (!allow) {
                emit(
                    CoreEvent.ToolEnd(
                        sessionId = currentSession,
                        toolCallId = "pending-edit",
                        output = "用户拒绝了这次写入",
                        failed = true,
                    ),
                )
                emit(CoreEvent.Idle(currentSession))
                return@launch
            }
            emit(
                CoreEvent.ToolEnd(
                    sessionId = currentSession,
                    toolCallId = "pending-edit",
                    output = "已修改 pkg/server/server.go",
                    failed = false,
                    diffAdded = 12,
                    diffRemoved = 3,
                    diff = """
                        @@ -137,6 +137,18 @@
                         func (s *Server) Addr() string {
                        -	return fmt.Sprintf("%s:%d", s.cfg.Server.Host, s.cfg.Server.Port)
                        +	if s.cfg.Server.Port == 0 {
                        +		return net.JoinHostPort(s.cfg.Server.Host, "0")
                        +	}
                        +	return net.JoinHostPort(s.cfg.Server.Host, strconv.Itoa(s.cfg.Server.Port))
                         }
                    """.trimIndent(),
                ),
            )
            delay(300)
            streamText(
                "改好了。现在 `port: 0` 时会交给系统挑一个空端口，绑定成功后把 `PORT=xxxxx` 打到 stdout 第一行——原生壳读这一行就知道该连哪个端口。\n\n还有一处要跟上：`stopInstance` 里也用了写死的端口，需要改成从运行时状态读。",
            )
            emit(
                CoreEvent.NoticeEvent(
                    currentSession,
                    NoticeKind.Compress,
                    "上下文已压缩：184,000 → 42,300 tokens · 接近窗口上限",
                ),
            )
            emit(
                CoreEvent.Todos(
                    currentSession,
                    listOf(
                        TodoItem("定位端口读取点", TodoStatus.Completed, 0, 0),
                        TodoItem("改 Addr() 支持 port:0", TodoStatus.Completed, 1, 1),
                        TodoItem("验证 stdout 握手", TodoStatus.InProgress, 1, 2),
                    ),
                ),
            )
            emit(CoreEvent.Idle(currentSession))
            emit(
                CoreEvent.ContextUpdate(
                    currentSession,
                    ContextUsage(42_300, 200_000, percent = 21.2f, model = "Sonnet 5.5"),
                ),
            )
        }
    }

    override fun sendMessage(text: String, attachments: List<String>) {
        // Mock 不消费附件（界面开发期），忽略参数
        runJob?.cancel()
        runJob = scope.launch {
            emit(CoreEvent.Busy(currentSession))
            delay(320)

            // 上游抖动一版：让重试通知行有得演
            emit(CoreEvent.NoticeEvent(currentSession, NoticeKind.Retry, "上游暂时不可用，正在重试（1/3）"))
            delay(420)

            streamThinking("先确认端口在哪里被读、被写，再看有没有别的地方依赖这个值。")

            // 子智能体并行探查：started → running → completed
            val sub = SubagentTask(id = "sub-1", mode = "explore", status = "started", detail = "扫描端口引用")
            emit(CoreEvent.SubagentUpdate(currentSession, sub))
            delay(260)
            emit(CoreEvent.SubagentUpdate(currentSession, sub.copy(status = "running", detail = "grep server.go / config")))
            delay(320)

            streamText("我先看下 `server.go` 里端口相关的几处代码。")
            delay(200)

            emit(CoreEvent.ToolStart(currentSession, "t-read", "read_file", """{"path":"pkg/server/server.go"}"""))
            delay(650)
            emit(
                CoreEvent.ToolEnd(
                    currentSession,
                    "t-read",
                    "137: // Port 返回监听端口\n140: func (s *Server) Addr() string\n204: addr := s.Addr()",
                    failed = false,
                ),
            )
            emit(
                CoreEvent.SubagentUpdate(
                    currentSession,
                    sub.copy(status = "completed", summary = "端口只在 server.go:140 与 :204 两处被引用"),
                ),
            )
            emit(
                CoreEvent.Todos(
                    currentSession,
                    listOf(
                        TodoItem("定位端口读取点", TodoStatus.Completed, 0, 0),
                        TodoItem("改 Addr() 支持 port:0", TodoStatus.InProgress, 1, 1),
                        TodoItem("验证 stdout 握手", TodoStatus.Pending, 1, 2),
                    ),
                ),
            )
            emit(
                CoreEvent.ContextUpdate(
                    currentSession,
                    ContextUsage(21_000, 200_000, percent = 10.5f, model = "Sonnet 5.5"),
                ),
            )
            delay(200)

            streamText("看到了。要改两处：`Addr()` 和 `stopInstance`。我先把 `Addr()` 改掉，这处会让内核在 `port: 0` 时自己挑端口。")
            delay(250)

            emit(
                CoreEvent.ToolStart(
                    currentSession,
                    "pending-edit",
                    "edit_file",
                    """{"path":"pkg/server/server.go","old":"return fmt.Sprintf(...)"}""",
                ),
            )
            delay(500)

            emit(
                CoreEvent.ApprovalRequested(
                    currentSession,
                    ApprovalCard(
                        id = "ap-1",
                        tool = "edit_file",
                        action = "修改 pkg/server/server.go",
                        reason = "写操作需要确认",
                        detail = "把 Addr() 里写死的端口改成 port=0 时交给系统挑。这一改会影响内核启动方式，需要宿主配合读 PORT 行。",
                        diff = """
                            @@ -140,1 +140,4 @@
                            -	return fmt.Sprintf("%s:%d", host, port)
                            +	if port == 0 {
                            +		return net.JoinHostPort(host, "0")
                            +	}
                        """.trimIndent(),
                    ),
                ),
            )
            // 审批期间不 emit Idle —— 内核此时也在等，界面应保持「运行中」
        }
    }

    private suspend fun streamThinking(full: String) {
        // 与内核 reasoning 帧同语义：发**增量**小块，ViewModel 负责拼接
        var i = 0
        while (i < full.length) {
            val step = minOf(3, full.length - i)
            emit(CoreEvent.Thinking(currentSession, full.substring(i, i + step)))
            i += step
            delay(24)
        }
    }

    private suspend fun streamText(full: String) {
        var i = 0
        while (i < full.length) {
            val step = minOf(4, full.length - i)
            emit(CoreEvent.Delta(currentSession, full.substring(i, i + step)))
            i += step
            delay(22)
        }
    }

    private fun emit(event: CoreEvent) {
        _events.tryEmit(event)
    }
}
