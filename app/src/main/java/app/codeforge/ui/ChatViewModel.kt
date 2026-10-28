package app.codeforge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeforge.AppVisibility
import app.codeforge.core.CoreEvent
import app.codeforge.core.CoreService
import app.codeforge.core.CoreTransport
import app.codeforge.core.EnvKit
import app.codeforge.core.HttpCoreTransport
import app.codeforge.core.KernelApi
import app.codeforge.core.KernelState
import app.codeforge.model.KernelConfigSummary
import app.codeforge.model.SubagentPolicy
import app.codeforge.model.ApprovalCard
import app.codeforge.model.ChatMessage
import app.codeforge.model.ContextUsage
import app.codeforge.model.MsgRole
import app.codeforge.model.NoticeKind
import app.codeforge.model.PermissionMode
import app.codeforge.model.SessionItem
import app.codeforge.model.SubagentTask
import app.codeforge.model.TodoItem
import app.codeforge.model.ToolCall
import app.codeforge.model.ToolState
import app.codeforge.ui.settings.AppSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/** 界面全部状态。单一数据源，UI 只读不写。 */
data class ChatUiState(
    val connected: Boolean = false,
    val sessions: List<SessionItem> = emptyList(),
    val currentSessionId: String = "",
    val title: String = "CodeForge",
    val messages: List<ChatMessage> = emptyList(),
    val running: Boolean = false,
    val approvals: List<ApprovalCard> = emptyList(),
    val permissionMode: PermissionMode = PermissionMode.Ask,
    val banner: Banner? = null,
    /** 任务清单（内核 todo 帧），空列表不渲染面板。 */
    val todos: List<TodoItem> = emptyList(),
    /** 子智能体任务卡（内核 subagent 帧），按 id 增量维护。 */
    val subagents: List<SubagentTask> = emptyList(),
    /** 上下文占用（内核 context 帧），null = 还没收到过。 */
    val contextUsage: ContextUsage? = null,
    /** 当前生效模型名（ready 帧 config.display_name），输入卡模型胶囊展示。 */
    val modelName: String = "",
    /** 模型库（/api/models/list，Ready 后拉取；空 = 内核没提供，弹层回退演示列表）。 */
    val models: List<KernelApi.ModelEntry> = emptyList(),
    /** 供应商名单（/api/providers/list）。 */
    val providers: List<String> = emptyList(),
    /** 思考强度规格（config.thinking.steps）；空 = 内核没给，弹层用固定五档。 */
    val thinkingSteps: List<String> = emptyList(),
    /** 已归档会话（侧栏折叠组，展开时拉取）。 */
    val archivedSessions: List<SessionItem> = emptyList(),
    /** 长按「编辑重发」请求：正文回填输入卡（Composer 消费后清空）。 */
    val editText: String? = null,
    val editAttachments: List<String> = emptyList(),
    /** 内核 REST 配置摘要（Ready 后拉取，设置页展示；null=内核不可用）。 */
    val kernelConfig: KernelConfigSummary? = null,
    /** 子智能体策略（设置页）。 */
    val subagentPolicy: SubagentPolicy? = null,
    /** 内置插件开关（id → 名称/启用），Ready 后拉取。 */
    val builtinPlugins: List<Triple<String, String, Boolean>> = emptyList(),
    /** MCP 插件开关。 */
    val mcpPlugins: List<app.codeforge.core.KernelApi.McpPluginInfo> = emptyList(),
    /** 技能（名称 → 描述），只读展示。 */
    val skills: List<Pair<String, String>> = emptyList(),
    /** 模型库真数据（/api/models/list；空 = 未拉到，弹层回退演示数据）。 */
    val libraryModels: List<app.codeforge.model.LibraryModel> = emptyList(),
    /** 供应商真数据。 */
    val libraryProviders: List<String> = emptyList(),
    /** 供应商完整信息（设置 → 模型服务 → 供应商管理）。 */
    val providerDetails: List<KernelApi.ProviderInfo> = emptyList(),
    /** 当前会话检查点（回滚菜单数据源）。 */
    val checkpoints: List<app.codeforge.model.CheckpointStep> = emptyList(),
    /** resume_back：有未跑完的轮次时可挂「继续上一轮」。 */
    val resumeBack: Int = -1,
    /** 编辑重发锚点（checkpoints.editables 里的 back；null=普通发送）。 */
    val editBack: Int? = null,
    /** 可编辑用户消息锚点表（checkpoints.editables）。 */
    val editableAnchors: List<app.codeforge.model.EditableUserMessage> = emptyList(),
)

/** 顶部横幅：区分错误与警告，两者不能长得一样。 */
data class Banner(
    val text: String,
    val isError: Boolean,
    /** 是否给一个「重试」按钮。内核连不上/异常退出时置 true —— 自动重连兜不住时得让用户有手动手段。 */
    val retryable: Boolean = false,
)

/**
 * 聊天页的状态持有者。
 *
 * 所有对内核的调用都经 [transport]，UI 不直接碰网络。
 * 事件流在这里被折叠成 [ChatUiState]，Compose 只负责把状态画出来。
 *
 * ## 与内核的绑定时机
 *
 * transport 不在构造期创建：内核由 [CoreService] 拉起，端口要等
 * `PORT=` 握手才知道。这里订阅 [CoreService.state]，Ready 时创建
 * [HttpCoreTransport] 并开始收事件；Failed 时挂横幅说明原因。
 *
 */
class ChatViewModel(app: android.app.Application) : androidx.lifecycle.AndroidViewModel(app) {

    /** 当前绑定的传输来源；决定内核 Ready/Failed 时要不要换绑。 */
    private enum class BoundKind { None, Kernel }

    private var transport: CoreTransport? = null
    private var eventsJob: Job? = null
    private var boundPort = -1
    private var boundKind = BoundKind.None

    /** 内核 REST 客户端：随内核端口创建/重建。UI 的服务操作都经它。 */
    var api: KernelApi? = null
        private set

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val seq = AtomicInteger(0)

    init {
        viewModelScope.launch {
            CoreService.state.collect { onKernelState(it) }
        }
        viewModelScope.launch {
            AppVisibility.hidden.collect { h ->
                transport?.sendVisibility(h)
            }
        }
        // 再等 10 秒仍停在 Starting：前台服务没起来、或握手卡死。
        // 主动踢一次重启，用户不必去后台手动杀进程再打开。
        viewModelScope.launch {
            delay(25_000)
            if (CoreService.state.value is KernelState.Starting) {
                CoreService.restart(getApplication())
            }
        }
    }

    private suspend fun onKernelState(kernel: KernelState) {
        when (kernel) {
            is KernelState.Ready -> {
                // 内核崩溃重启会换随机端口：端口变了要换绑新连接
                if (boundKind != BoundKind.Kernel || boundPort != kernel.port) {
                    transport?.disconnect()
                    boundKind = BoundKind.Kernel
                    boundPort = kernel.port
                    _state.update { it.copy(connected = false, banner = null) }
                    bind(HttpCoreTransport(host = "127.0.0.1", port = kernel.port))
                    // REST 客户端随端口重建，并预拉设置页要展示的两份摘要
                    initKernelApi(kernel.port)
                }
                // 内核重启会丢运行时工作区：把当前会话的空间重新指回去
                applySessionSpace(_state.value.currentSessionId)
            }

            is KernelState.Failed -> {
                _state.update {
                    it.copy(
                        running = false,
                        banner = Banner(
                            "内核异常：${kernel.reason.take(80)}",
                            isError = true,
                            retryable = true,
                        ),
                    )
                }
            }

            KernelState.Starting -> Unit
        }
    }

    /**
     * 会话独立空间：未显式选工作区的会话各拿一个小目录（wrx），内核经
     * /api/workspace 指过去 —— 附件与模型产出的文件互不串台。
     * 内核重启后 Ready 分支会重指。
     */
    private fun applySessionSpace(sessionId: String) {
        if (sessionId.isEmpty() || boundKind != BoundKind.Kernel) return
        val a = api ?: return
        viewModelScope.launch {
            a.setWorkspace(EnvKit.sessionSpace(getApplication(), sessionId).absolutePath)
        }
    }

    /** 绑定传输层：开始收事件并建立连接。内核重启换端口时也会走到这里。 */
    private fun bind(t: CoreTransport) {
        transport = t
        eventsJob?.cancel()
        eventsJob = viewModelScope.launch { t.events.collect(::onEvent) }
        // 连接前的可见性变化补发给新 transport（onOpen 用它上报首帧）
        if (t is HttpCoreTransport) {
            t.sendVisibility(AppVisibility.hidden.value)
        }
        t.connect()
    }

    /**
     * 初始化内核 REST 客户端，失败按 1/2/4 秒退避重试 4 次。
     *
     * 为什么不能只试一次：`/api/config` 与 WebSocket 是**两条独立链路**，
     * 内核刚 bind 完那一瞬间 REST 也可能还没接客。只试一次会让设置页永久
     * 停在「内核未连接」——而实际上内核是好的（通知栏都在报端口）。
     */
    private fun initKernelApi(port: Int) {
        val newApi = KernelApi("127.0.0.1", port)
        api = newApi
        viewModelScope.launch {
            repeat(4) { i ->
                when (val r = newApi.init()) {
                    is KernelApi.ApiResult.Ok -> {
                        refreshKernelSummaries()
                        return@launch
                    }

                    is KernelApi.ApiResult.Err -> {
                        if (i == 3) {
                            _state.update { it.copy(banner = Banner(r.message, isError = true)) }
                        } else {
                            delay(1_000L shl i)
                        }
                    }
                }
            }
        }
    }

    /**
     * 「后台事件」= 带 sessionId 且不属于当前会话、且属于消息流折叠类型。
     * History/SessionClaimed（切会话本身）与 ApprovalRequested（审批卡带来源
     * 标注、阻塞的可能是后台会话，必须显示）不在此列，照常走主分支。
     */
    private fun isBackgroundSessionEvent(e: CoreEvent): Boolean {
        val current = _state.value.currentSessionId
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
        return sid.isNotEmpty() && current.isNotEmpty() && sid != current
    }

    /**
     * 后台会话事件：只更新列表 busy 标记。系统通知由内核负责 ——
     * visibility（B5-2）修好后内核知道页面不可见，长任务完成会自己发通知，
     * 壳侧不重复发，避免双条。
     */
    private fun handleBackgroundEvent(e: CoreEvent) {
        when (e) {
            is CoreEvent.Busy -> _state.update { s ->
                s.copy(sessions = s.sessions.map {
                    if (it.id == e.sessionId) it.copy(busy = true) else it
                })
            }

            is CoreEvent.Idle -> _state.update { s ->
                s.copy(sessions = s.sessions.map {
                    if (it.id == e.sessionId) it.copy(busy = false) else it
                })
            }

            else -> Unit // 正文/工具/思考等输出：后台的丢弃，不进当前消息流
        }
    }

    private fun onEvent(event: CoreEvent) {
        // —— B5-1 后台会话过滤：多会话并行时，别的会话的输出不许污染当前界面 ——
        if (isBackgroundSessionEvent(event)) {
            handleBackgroundEvent(event)
            return
        }
        when (event) {
            is CoreEvent.Ready -> {
                _state.update {
                    it.copy(
                        connected = true,
                        // ready 帧自带会话与配置；老内核 payload 为空则退回主动拉
                        sessions = event.sessions.ifEmpty { it.sessions },
                        modelName = event.model.ifEmpty { it.modelName },
                    )
                }
                if (_state.value.sessions.isEmpty()) transport?.listSessions()
            }

            is CoreEvent.Link -> {
                if (event.connected) {
                    _state.update { it.copy(connected = true, banner = null) }
                    // 链路刚通：REST 之前若没初始化成功，这里补一次
                    if (_state.value.kernelConfig == null && boundPort > 0) {
                        initKernelApi(boundPort)
                    }
                } else {
                    _state.update {
                        it.copy(
                            connected = false,
                            banner = Banner(
                                "内核连接中断，正在重连（第 ${event.attempt} 次）",
                                isError = false,
                                retryable = true,
                            ),
                        )
                    }
                }
            }

            is CoreEvent.SessionClaimed -> {
                _state.update { st ->
                    // 内核先发 session、再发 sessions。若 sessions 先到（或本帧之后
                    // 不再补发），currentSessionId 此刻还是旧值，列表里就不会有这条
                    // 新会话 —— 侧栏看起来「新建了但没新增」。这里就地补一次。
                    // 占位名带时间：未命名的会话在内核里可能攒下好几条，
                    // 全叫「新对话」的话侧栏一排同名，根本分不清哪个是刚建的。
                    val newTitle = event.title.ifBlank {
                        val f = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                        "新对话 " + f.format(java.util.Date())
                    }
                    val exists = st.sessions.any { it.id == event.sessionId }
                    val patched = if (exists) {
                        st.sessions.map {
                            if (it.id == event.sessionId) it.copy(title = newTitle) else it
                        }
                    } else {
                        listOf(
                            app.codeforge.model.SessionItem(
                                id = event.sessionId,
                                title = newTitle,
                                updatedAt = System.currentTimeMillis(),
                                busy = false,
                            ),
                        ) + st.sessions
                    }
                    st.copy(
                        currentSessionId = event.sessionId,
                        title = newTitle,
                        sessions = patched,
                    )
                }
                applySessionSpace(event.sessionId)
            }

            is CoreEvent.Sessions -> _state.update { st ->
                // 内核的会话列表可能不含「刚建好还没说话」的会话，直接覆盖会让
                // 新建的对话在侧栏凭空消失。当前会话不在列表里就本地补一条。
                val cur = st.currentSessionId
                val patched = if (cur.isNotBlank() && event.items.none { it.id == cur }) {
                    listOf(
                        app.codeforge.model.SessionItem(
                            id = cur,
                            title = "新对话 " + java.text.SimpleDateFormat(
                                "HH:mm", java.util.Locale.US,
                            ).format(java.util.Date()),
                            updatedAt = System.currentTimeMillis(),
                            busy = false,
                        ),
                    ) + event.items
                } else {
                    event.items
                }
                st.copy(sessions = patched)
            }

            is CoreEvent.History -> {
                // 空历史帧防抖：新会话首条消息后内核会推空 history（claim 流程），
                // 直接替换会把乐观消息清掉 → 问候页闪回 → AnimatedContent 快速
                // A→B→A 抖动崩溃（真机 crash-1792934642977）+ 首帧闪白
                val wouldWipe = event.messages.isEmpty() &&
                    _state.value.currentSessionId == event.sessionId &&
                    _state.value.messages.isNotEmpty()
                if (!wouldWipe) {
                    _state.update {
                        it.copy(
                            currentSessionId = event.sessionId,
                            title = event.title,
                            messages = event.messages,
                            running = false,
                            approvals = emptyList(),
                            banner = null,
                            subagents = emptyList(),
                        )
                    }
                }
                applySessionSpace(event.sessionId)
            }

            is CoreEvent.Busy -> _state.update { it.copy(running = true, banner = null) }

            is CoreEvent.Idle -> _state.update { s ->
                s.copy(
                    running = false,
                    messages = s.messages.map { m ->
                        if (m.streaming) m.copy(streaming = false) else m
                    },
                )
            }

            is CoreEvent.Checkpoints -> _state.update {
                it.copy(
                    checkpoints = event.steps,
                    resumeBack = event.resumeBack,
                    editableAnchors = event.editables,
                )
            }

            is CoreEvent.StreamReset -> {
                // stream_reset 只是内核的「文本段边界」（工具执行前后各一段），
                // 不是回合边界 —— 这里若把 streaming 清掉，下一段正文/思考会开
                // 新消息，表现为「每调一次工具就多一条带复制分享行的碎块」
                // （真机反馈第 2 项）。回合真正的终点是 Idle。
                // 段间留一个不可见分隔，正文聚合在同一条消息里按空行衔接。
                _state.update { s ->
                    val msgs = s.messages.toMutableList()
                    val last = msgs.lastOrNull()
                    if (last != null && last.role == MsgRole.Assistant && last.streaming &&
                        last.text.isNotEmpty() && !last.text.endsWith("\n")
                    ) {
                        msgs[msgs.lastIndex] = last.copy(text = last.text + "\n")
                    }
                    s.copy(messages = msgs)
                }
            }

            is CoreEvent.Delta -> {
                appendAssistantText(event.text)
            }

            is CoreEvent.Thinking -> appendThinking(event.text)

            is CoreEvent.ToolStart -> upsertTool(
                ToolCall(
                    id = event.toolCallId,
                    name = event.name,
                    input = event.input,
                    state = ToolState.Running,
                ),
            )

            is CoreEvent.ToolEnd -> finishTool(event)

            is CoreEvent.ApprovalRequested -> _state.update { s ->
                s.copy(approvals = s.approvals.filterNot { it.id == event.card.id } + event.card)
            }

            is CoreEvent.ApprovalResolved -> _state.update { s ->
                s.copy(approvals = s.approvals.filterNot { it.id == event.approvalId })
            }

            is CoreEvent.NoticeEvent -> {
                // 自动重试的提示静默：内核每次退避重试都发一条，5 次就是 5 行，
                // 消息流会被「正在重试（2/5）」刷屏。最终失败时 CoreEvent.Failure
                // 会给结论和重试入口，过程没必要逐条留痕。
                if (event.kind != NoticeKind.Retry) {
                    _state.update { s ->
                        s.copy(
                            messages = s.messages + ChatMessage(
                                id = "n-${seq.incrementAndGet()}",
                                role = MsgRole.Notice,
                                text = event.text,
                                kind = event.kind,
                                time = System.currentTimeMillis(),
                            ),
                        )
                    }
                }
            }

            is CoreEvent.Todos -> _state.update { it.copy(todos = event.items) }

            is CoreEvent.ContextUpdate -> _state.update { it.copy(contextUsage = event.usage) }

            is CoreEvent.SubagentUpdate -> _state.update { s ->
                val list = s.subagents.toMutableList()
                val at = list.indexOfFirst { it.id == event.task.id }
                if (at >= 0) list[at] = event.task else list.add(event.task)
                s.copy(subagents = list)
            }

            is CoreEvent.Failure -> {
                // 限流 / 网络类失败要留一个「重试」入口：内核在 llm 层已经自动退避重试过
                // （429 无 RPM 时每次等 10 秒 ×5 次），仍然失败说明窗口没滑出来。
                // 这时只弹一条 2 秒就消失的气泡，用户看到的就是「卡了一会儿然后断了」，
                // 既不知道发生过重试，也没有继续的办法。
                val retryable = listOf(
                    "限流", "429", "超时", "timeout", "网络", "连接", "断开", "上游",
                ).any { event.message.contains(it, ignoreCase = true) }
                _state.update {
                    it.copy(
                        running = false,
                        banner = Banner(event.message, isError = true, retryable = retryable),
                    )
                }
            }

            is CoreEvent.Warning -> _state.update {
                it.copy(banner = Banner(event.message, isError = false))
            }
        }
    }

    /** 助手正文增量：并入当前流式消息，没有就新建一条。 */
    private fun appendAssistantText(text: String) {
        _state.update { s ->
            val msgs = s.messages.toMutableList()
            val last = msgs.lastOrNull()
            if (last != null && last.role == MsgRole.Assistant && last.streaming) {
                msgs[msgs.lastIndex] = last.copy(text = last.text + text)
            } else {
                msgs.add(
                    ChatMessage(
                        id = "m-${seq.incrementAndGet()}",
                        role = MsgRole.Assistant,
                        text = text,
                        streaming = true,
                        time = System.currentTimeMillis(),
                    ),
                )
            }
            s.copy(messages = msgs)
        }
    }

    /**
     * 思考过程增量：与正文一致按**增量**追加（内核 reasoning 帧是流式增量，
     * Mock 与测试模型也已统一为增量）—— 此前按「整段替换」折叠，
     * 与契约不符会让思考区只剩最后一小块。
     */
    private fun appendThinking(text: String) {
        _state.update { s ->
            val msgs = s.messages.toMutableList()
            val last = msgs.lastOrNull()
            if (last != null && last.role == MsgRole.Assistant && last.streaming) {
                msgs[msgs.lastIndex] = last.copy(thinking = (last.thinking ?: "") + text)
            } else {
                msgs.add(
                    ChatMessage(
                        id = "m-${seq.incrementAndGet()}",
                        role = MsgRole.Assistant,
                        text = "",
                        thinking = text,
                        streaming = true,
                        time = System.currentTimeMillis(),
                    ),
                )
            }
            s.copy(messages = msgs)
        }
    }

    /**
     * 工具卡片挂在「当前这条助手消息」上。
     *
     * 若最后一条是用户消息（比如工具在用户发问后立刻启动），就补一条空的助手消息
     * 来承载卡片 —— 否则卡片会无处安放，看起来像丢了。
     */
    private fun upsertTool(call: ToolCall) {
        _state.update { s ->
            val msgs = s.messages.toMutableList()
            // 挂到**最近的助手消息**上（不要求还在 streaming）。
            // 之前要求 streaming，而工具是在流结束之后才开始的 —— 条件永远不成立，
            // 于是每次工具调用都新建一条消息，工具卡全被顶到对话末尾，
            // 顺序和实际执行顺序对不上。
            var idx = msgs.indexOfLast { it.role == MsgRole.Assistant }
            if (idx < 0) {
                msgs.add(
                    ChatMessage(
                        id = "m-${seq.incrementAndGet()}",
                        role = MsgRole.Assistant,
                        text = "",
                        streaming = true,
                        time = System.currentTimeMillis(),
                    ),
                )
                idx = msgs.lastIndex
            }
            val target = msgs[idx]
            val tools = target.tools.toMutableList()
            val at = tools.indexOfFirst { it.id == call.id }
            if (at >= 0) tools[at] = call else tools.add(call)
            msgs[idx] = target.copy(tools = tools)
            s.copy(messages = msgs)
        }
    }

    private fun finishTool(event: CoreEvent.ToolEnd) {
        _state.update { s ->
            val msgs = s.messages.toMutableList()
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
                    msgs[i] = m.copy(tools = tools)
                    break
                }
            }
            s.copy(messages = msgs)
        }
    }

    // ---------- 用户动作 ----------

    /**
     * 发送消息。
     *
     * 先乐观插入一条用户气泡再交给内核 —— 网络/内核有延迟，等回包才显示
     * 会让人以为没点上。若内核返回 error，这条气泡保留（用户的问题确实
     * 问出去了），只额外挂横幅说明为什么没成。
     *
     * 运行中发送走 [steer]：不新开一轮，指令并入当前上下文（对应内核 steer 帧）。
     * 内核还没就绪时不收这条消息 —— 乐观气泡会凭空消失，比等两秒更糟。
     */
    fun send(text: String, attachments: List<String> = emptyList()) {
        val trimmed = text.trim()
        // 内核允许「纯附件无文本」；壳同样放行
        if (trimmed.isEmpty() && attachments.isEmpty()) return
        val t = transport ?: run {
            _state.update { it.copy(banner = Banner("内核启动中，稍候再发", isError = false)) }
            return
        }
        if (_state.value.running) {
            steer(trimmed)
            return
        }

        // 编辑重发（带 checkpoints.editables 的 back 锚点）：走内核原语义
        // （截断历史 + 回退文件改动 + 重跑），随后的 edit→StreamReset→history
        // 快照会重建消息流；壳侧不乐观插入 —— 截断后旧消息本来就该消失
        val back = _state.value.editBack
        if (back != null && attachments.isEmpty()) {
            clearEdit()
            t.editUserMessage(trimmed, back)
            return
        }
        clearEdit()

        _state.update { s ->
            s.copy(
                banner = null,
                messages = s.messages + ChatMessage(
                    id = "m-${seq.incrementAndGet()}",
                    role = MsgRole.User,
                    // 纯附件消息在气泡里给出可见文案，正文里仍是原始文本
                    text = trimmed.ifEmpty { attachments.joinToString(" ") { "📎 ${it.substringAfterLast('/')}" } },
                    attachments = attachments,
                    time = System.currentTimeMillis(),
                ),
            )
        }
        t.sendMessage(trimmed, attachments)
    }

    /** 运行中转向：乐观气泡照常上屏，内核确认后追加「已并入」通知行。 */
    private fun steer(text: String) {
        _state.update { s ->
            s.copy(
                messages = s.messages + ChatMessage(
                    id = "m-${seq.incrementAndGet()}",
                    role = MsgRole.User,
                    text = text,
                    time = System.currentTimeMillis(),
                ),
            )
        }
        transport?.steer(text)
    }

    /** 重新生成最后一轮：不重发用户原文，交给内核 regenerate 语义。 */
    fun regenerate() {
        if (_state.value.running) return
        _state.update { it.copy(banner = null, running = true) }
        transport?.regenerate()
    }

    fun stop() {
        transport?.cancel()
        _state.update { s ->
            s.copy(
                running = false,
                messages = s.messages.map { if (it.streaming) it.copy(streaming = false) else it },
            )
        }
    }

    fun decideApproval(id: String, allow: Boolean) {
        transport?.decideApproval(id, allow)
        _state.update { s -> s.copy(approvals = s.approvals.filterNot { it.id == id }) }
    }

    fun switchSession(id: String) {
        if (id == _state.value.currentSessionId) return
        transport?.loadSession(id)
    }

    fun createSession() {
        // 当前已经是「空会话」就不再新建 —— 否则连点几下会攒出一串空会话
        // （真机上就是这样堆出 5 条同名「新对话」）。空会话没有任何内容，
        // 复用它对用户来说和「新建」没有区别。
        val st = _state.value
        if (st.currentSessionId.isNotBlank() && st.messages.isEmpty() && !st.running) {
            _state.update {
                it.copy(banner = Banner("当前已经是新会话", isError = false))
            }
            return
        }
        val t = transport
        if (t == null) {
            _state.update { it.copy(banner = Banner("内核未连接，暂时无法新建对话", true)) }
            return
        }
        if (!t.newSession()) {
            // WS 没连上（或刚断）：帧发不出去。不提示的话用户只会觉得按钮坏了。
            _state.update { it.copy(banner = Banner("连接尚未就绪，稍等一下再试", true)) }
        }
    }

    /** 权限模式三档：本地乐观更新 + 热写到内核（/api/perm）。 */
    fun setPermissionMode(mode: PermissionMode) {
        _state.update { it.copy(permissionMode = mode) }
        AppSettings.setLastPermission(mode.name)
        api?.let { a -> viewModelScope.launch { a.setPerm(mode.name.lowercase()) } }
        toast("权限：${mode.label}")
    }

    /** 选择模型：POST /api/config {model}（内核侧切通道），本地胶囊即时跟随。 */
    fun selectModel(id: String) {
        val a = api ?: run { bannerNoKernel(); return }
        viewModelScope.launch {
            when (val r = a.updateConfig(model = id)) {
                is KernelApi.ApiResult.Ok -> _state.update { it.copy(modelName = id) }
                is KernelApi.ApiResult.Err -> _state.update { it.copy(banner = Banner(r.message, true)) }
            }
        }
    }

    /**
     * 编辑重发：回填输入卡，并按 checkpoints.editables 找该消息的 back 锚点
     * （按文本匹配，取最近一条）；发送时带锚点走内核 edit_user_message
     * （截断+文件回退+重跑的原语义），找不到（早于可编辑窗口）退回普通重发。
     */
    fun requestEdit(message: ChatMessage) {
        val anchor = _state.value.editableAnchors
            .lastOrNull { it.text.trim() == message.text.trim() }?.back
        _state.update {
            it.copy(editText = message.text, editAttachments = message.attachments, editBack = anchor)
        }
    }

    fun clearEdit() {
        _state.update { it.copy(editText = null, editAttachments = emptyList(), editBack = null) }
    }

    /** 原样重发：原文+附件按一条新用户消息再走一遍（运行中自动并入 steer）。 */
    fun resendUser(message: ChatMessage) = send(message.text, message.attachments)

    /** 历史工具卡输出回放：/api/diff 拉回 diff/output 并挂回对应卡片。 */
    fun fetchToolReplay(callId: String) {
        val a = api ?: run { bannerNoKernel(); return }
        val sid = _state.value.currentSessionId
        viewModelScope.launch {
            when (val r = a.fetchToolReplay(sid, callId)) {
                is KernelApi.ApiResult.Ok -> _state.update { s ->
                    val msgs = s.messages.toMutableList()
                    for (i in msgs.indices.reversed()) {
                        val at = msgs[i].tools.indexOfFirst { it.id == callId }
                        if (at >= 0) {
                            // List 没有按位赋值：copy 整条消息，工具数组按位替换
                            val m = msgs[i]
                            val t = m.tools[at]
                            msgs[i] = m.copy(
                                tools = m.tools.mapIndexed { j, tc ->
                                    if (j == at) {
                                        tc.copy(
                                            output = t.output ?: r.value.second,
                                            diff = t.diff ?: r.value.first,
                                        )
                                    } else {
                                        tc
                                    }
                                },
                            )
                            break
                        }
                    }
                    s.copy(messages = msgs)
                }
                is KernelApi.ApiResult.Err ->
                    _state.update { it.copy(banner = Banner(r.message, true)) }
            }
        }
    }

    /** 拉取已归档列表（侧栏展开「已归档」时调用；幂等）。 */
    fun loadArchived() {
        val a = api ?: return
        viewModelScope.launch {
            when (val r = a.listArchivedSessions()) {
                is KernelApi.ApiResult.Ok -> _state.update { it.copy(archivedSessions = r.value) }
                is KernelApi.ApiResult.Err -> _state.update { it.copy(archivedSessions = emptyList()) }
            }
        }
    }

    /** 从归档恢复：patch archived=false + 刷新归档列表。 */
    fun unarchive(id: String) {
        val a = api ?: run { bannerNoKernel(); return }
        viewModelScope.launch {
            a.patchSession(id, archived = false)
            loadArchived()
        }
    }

    /** 应用模型库条目（模型弹层选真模型时走这里，内核切通道）。 */
    fun applyModel(id: String) {
        val a = api ?: run {
            // 内核未连接：仅本地跟随（测试模型开关之外的兜底）
            _state.update { it.copy(modelName = id) }
            return
        }
        viewModelScope.launch {
            when (val r = a.applyModel(id)) {
                is KernelApi.ApiResult.Ok -> {
                    _state.update { it.copy(modelName = id) }
                    toast("已切换到 $id")
                }
                is KernelApi.ApiResult.Err -> _state.update { it.copy(banner = Banner(r.message, true)) }
            }
        }
    }

    /** 拉一次检查点（打开回滚菜单时调；内核会回 checkpoints 帧）。 */
    fun pullCheckpoints() {
        transport?.pullCheckpoints()
    }

    /** 回滚工作区文件到第 step 步之前（不动对话历史）。 */
    fun rewindTo(step: Int) {
        transport?.rewindFiles(step)
    }

    /** 继续上一轮（断点续跑）。 */
    fun continueTurn() {
        if (_state.value.running) return
        transport?.continueTurn()
    }

    /**
     * 创建对话分支：内核无 branch 端点，壳侧等价实现 —— 新会话 + 把既有
     * 对话播种为上下文（截尾 8K 字防撑爆首条消息），模型在同一语境继续。
     */
    fun branchSession() {
        val msgs = _state.value.messages.filter { it.role != app.codeforge.model.MsgRole.Notice }
        if (msgs.isEmpty()) return
        val seed = buildString {
            appendLine("（以下为此前对话的分支上下文，请在此基础上继续）")
            msgs.forEach { m ->
                val who = if (m.role == app.codeforge.model.MsgRole.User) "用户" else "助手"
                appendLine("$who：${m.text}")
            }
        }.takeLast(8_000)
        transport?.newSession()
        send("【分支延续】 " + seed)
    }

    // ---------- 每会话草稿（B4 输入区）：内存态，进程死掉即失（聊天草稿不值得落盘） ----------

    private val drafts = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun draftOf(sessionId: String): String = drafts[sessionId] ?: ""

    fun saveDraft(sessionId: String, text: String) {
        if (sessionId.isEmpty()) return
        if (text.isEmpty()) drafts.remove(sessionId) else drafts[sessionId] = text
    }

    // ---------------- 内核服务操作（设置页 / 会话管理调用） ----------------

    private fun refreshKernelSummaries() {
        val a = api ?: return
        loadKernelExtras()
        // 供应商详情也在启动时拉一次：首次安装要在这里触发 DeepSeek 预置，
        // 不能等用户点开设置页才发生（模型弹层的供应商行一直显示「未选择」）。
        refreshProviderDetails()
        viewModelScope.launch {
            when (val r = a.getConfig()) {
                is KernelApi.ApiResult.Ok -> _state.update { it.copy(kernelConfig = r.value) }
                is KernelApi.ApiResult.Err -> Unit
            }
            when (val r = a.getSubagentPolicy()) {
                is KernelApi.ApiResult.Ok -> _state.update { it.copy(subagentPolicy = r.value) }
                is KernelApi.ApiResult.Err -> Unit
            }
            // 模型库/供应商/思考规格：弹层「真数据」数据源；任一失败静默回退演示数据
            when (val r = a.listModels()) {
                is KernelApi.ApiResult.Ok -> _state.update { it.copy(models = r.value) }
                is KernelApi.ApiResult.Err -> Unit
            }
            when (val r = a.listProviders()) {
                is KernelApi.ApiResult.Ok -> _state.update { it.copy(providers = r.value) }
                is KernelApi.ApiResult.Err -> Unit
            }
            when (val r = a.getThinkingSpec()) {
                is KernelApi.ApiResult.Ok ->
                    if (r.value.steps.isNotEmpty()) _state.update { it.copy(thinkingSteps = r.value.steps) }
                is KernelApi.ApiResult.Err -> Unit
            }
            // B5-3：权限初始态以内核为准（本地默认 Ask 可能与真实策略不一致）
            when (val r = a.getPerm()) {
                is KernelApi.ApiResult.Ok -> {
                    runCatching { PermissionMode.valueOf(r.value.replaceFirstChar { it.uppercase() } ) }
                        .onSuccess { mode ->
                            _state.update { st ->
                                if (st.permissionMode != mode) st.copy(permissionMode = mode) else st
                            }
                        }
                }
                is KernelApi.ApiResult.Err -> Unit
            }
        }
    }

    fun updateGenerationConfig(maxTokens: Int?, temperature: Double?) {
        val a = api ?: run { bannerNoKernel(); return }
        viewModelScope.launch {
            when (val r = a.updateConfig(maxTokens = maxTokens, temperature = temperature)) {
                is KernelApi.ApiResult.Ok -> refreshKernelSummaries()
                is KernelApi.ApiResult.Err -> _state.update { it.copy(banner = Banner(r.message, true)) }
            }
        }
    }

    fun updateAgentConfig(maxSteps: Int?) {
        val a = api ?: run { bannerNoKernel(); return }
        viewModelScope.launch {
            when (val r = a.updateConfig(maxSteps = maxSteps)) {
                is KernelApi.ApiResult.Ok -> Unit
                is KernelApi.ApiResult.Err -> _state.update { it.copy(banner = Banner(r.message, true)) }
            }
        }
    }

    fun updateRetryConfig(attempts: Int?, mode: String?, intervalSec: Int?) {
        val a = api ?: run { bannerNoKernel(); return }
        viewModelScope.launch {
            when (val r = a.updateConfig(
                retryMaxAttempts = attempts,
                retryMode = mode,
                retryIntervalSec = intervalSec,
            )) {
                is KernelApi.ApiResult.Ok -> refreshKernelSummaries()
                is KernelApi.ApiResult.Err -> _state.update { it.copy(banner = Banner(r.message, true)) }
            }
        }
    }

    fun updateSubagentPolicy(policy: SubagentPolicy) {
        val a = api ?: run { bannerNoKernel(); return }
        viewModelScope.launch {
            when (val r = a.updateSubagentPolicy(policy)) {
                is KernelApi.ApiResult.Ok -> refreshKernelSummaries()
                is KernelApi.ApiResult.Err -> _state.update { it.copy(banner = Banner(r.message, true)) }
            }
        }
    }

    /** 设置页：拉内置插件 / MCP 插件 / 技能（Ready 后调，幂等）。 */
    fun loadKernelExtras() {
        val a = api ?: return
        viewModelScope.launch {
            when (val r = a.listBuiltinPlugins()) {
                is KernelApi.ApiResult.Ok -> _state.update { it.copy(builtinPlugins = r.value.map { Triple(it.first, it.first, it.second) }) }
                is KernelApi.ApiResult.Err -> Unit
            }
        }
        viewModelScope.launch {
            when (val r = a.listPlugins()) {
                is KernelApi.ApiResult.Ok -> _state.update { it.copy(mcpPlugins = r.value) }
                is KernelApi.ApiResult.Err -> Unit
            }
        }
        viewModelScope.launch {
            when (val r = a.listSkills()) {
                is KernelApi.ApiResult.Ok -> _state.update { it.copy(skills = r.value) }
                is KernelApi.ApiResult.Err -> Unit
            }
        }
        viewModelScope.launch {
            when (val r = a.listModels()) {
                is KernelApi.ApiResult.Ok -> {
                    _state.update {
                        it.copy(libraryModels = r.value.map { m ->
                            app.codeforge.model.LibraryModel(m.id, m.name, m.provider)
                        })
                    }
                    // 列表到齐才有得比，同步放在这里
                    syncModelToKernel()
                }
                is KernelApi.ApiResult.Err -> Unit
            }
        }
        viewModelScope.launch {
            when (val r = a.listProviders()) {
                is KernelApi.ApiResult.Ok -> _state.update { it.copy(libraryProviders = r.value) }
                is KernelApi.ApiResult.Err -> Unit
            }
        }
    }

    fun setBuiltinPlugin(id: String, enabled: Boolean) {
        val a = api ?: return
        viewModelScope.launch {
            a.setBuiltinPlugin(id, enabled)
            loadKernelExtras()
        }
    }

    fun setMcpPlugin(id: String, enabled: Boolean) {
        val a = api ?: return
        viewModelScope.launch {
            when (val r = a.setPlugin(id, enabled)) {
                is KernelApi.ApiResult.Ok -> {
                    loadKernelExtras()
                    toast(if (enabled) "已启用 $id" else "已停用 $id")
                }

                is KernelApi.ApiResult.Err ->
                    _state.update { it.copy(banner = Banner(r.message, true)) }
            }
        }
    }

    /**
     * 探测某个供应商下的可用模型。
     *
     * [provider] 传空串表示用当前生效配置探测。结果走回调而不是塞进 state ——
     * 探测是一次性动作，没必要让整个界面重组一次。
     */
    fun discoverModels(provider: String, onResult: (List<String>) -> Unit) {
        val a = api ?: run { bannerNoKernel(); onResult(emptyList()); return }
        viewModelScope.launch {
            when (val r = a.discoverModels(provider)) {
                is KernelApi.ApiResult.Ok -> onResult(r.value)
                is KernelApi.ApiResult.Err -> {
                    _state.update { it.copy(banner = Banner(r.message, true)) }
                    onResult(emptyList())
                }
            }
        }
    }

    /** 把一个探测到的模型存进模型库。 */
    fun saveModel(id: String, displayName: String, provider: String) {
        val a = api ?: run { bannerNoKernel(); return }
        viewModelScope.launch {
            // 走 /api/models/save 而不是 save_batch：批量接口只认 id/name/provider，
            // 而且实测加进去后主对话框的模型列表不刷新（等于没加）。
            when (val r = a.saveModelFull(
                id = id,
                name = displayName.ifBlank { id },
                providerId = provider,
            )) {
                is KernelApi.ApiResult.Ok -> {
                    refreshKernelSummaries()
                    toast("已添加模型 $id")
                }

                is KernelApi.ApiResult.Err ->
                    _state.update { it.copy(banner = Banner(r.message, true)) }
            }
        }
    }

    /** 从模型库删除一个模型。 */
    fun deleteModel(id: String) {
        val a = api ?: run { bannerNoKernel(); return }
        viewModelScope.launch {
            when (val r = a.deleteModel(id)) {
                is KernelApi.ApiResult.Ok -> {
                    refreshKernelSummaries()
                    toast("已删除模型 $id")
                }

                is KernelApi.ApiResult.Err ->
                    _state.update { it.copy(banner = Banner(r.message, true)) }
            }
        }
    }

    /** 添加一个 MCP 插件（stdio 或远程 HTTP），内核会增量热加载。 */
    fun addMcpPlugin(
        name: String,
        displayName: String,
        type: String,
        command: String,
        args: List<String>,
        endpoint: String,
        description: String,
    ) {
        val a = api ?: run { bannerNoKernel(); return }
        viewModelScope.launch {
            when (val r = a.addMcpPlugin(name, displayName, type, command, args, endpoint, description)) {
                is KernelApi.ApiResult.Ok -> {
                    loadKernelExtras()
                    toast("已添加 MCP：$name")
                }

                is KernelApi.ApiResult.Err ->
                    _state.update { it.copy(banner = Banner(r.message, true)) }
            }
        }
    }

    /** 删除一个 MCP 插件。 */
    fun deleteMcpPlugin(name: String) {
        val a = api ?: run { bannerNoKernel(); return }
        viewModelScope.launch {
            when (val r = a.deleteMcpPlugin(name)) {
                is KernelApi.ApiResult.Ok -> {
                    loadKernelExtras()
                    toast("已删除 MCP：$name")
                }

                is KernelApi.ApiResult.Err ->
                    _state.update { it.copy(banner = Banner(r.message, true)) }
            }
        }
    }

    fun setNotifyEnabled(enabled: Boolean) {
        api?.let { a -> viewModelScope.launch { a.setNotifyEnabled(enabled) } }
    }

    /** 手动压缩当前会话上下文；结果同时经 WS compress 帧进消息流，这里补一条横幅反馈。 */
    fun compressContext() {
        val a = api ?: run { bannerNoKernel(); return }
        val sid = _state.value.currentSessionId
        viewModelScope.launch {
            when (val r = a.compressContext(sid.ifBlank { null })) {
                is KernelApi.ApiResult.Ok -> {
                    _state.update { it.copy(banner = Banner(r.value, isError = false)) }
                    // B5-7：压缩后主动拉一次占用（内核压缩路径不总是回推 context 帧）
                    transport?.pullContext()
                }
                is KernelApi.ApiResult.Err ->
                    _state.update { it.copy(banner = Banner(r.message, isError = true)) }
            }
        }
    }

    // ---------------- 会话管理 ----------------

    fun renameSession(id: String, title: String) {
        val a = api ?: run { bannerNoKernel(); return }
        viewModelScope.launch {
            when (val r = a.patchSession(id, title = title.ifBlank { null })) {
                is KernelApi.ApiResult.Ok -> {
                    // 本地先改，再拉一次对齐（内核不主动推 sessions 帧）
                    _state.update { st ->
                        st.copy(
                            sessions = st.sessions.map { if (it.id == id) it.copy(title = title) else it },
                            title = if (st.currentSessionId == id && title.isNotBlank()) title else st.title,
                        )
                    }
                    transport?.listSessions()
                    toast("标题已保存")
                }

                is KernelApi.ApiResult.Err -> _state.update { it.copy(banner = Banner(r.message, true)) }
            }
        }
    }

    fun setSessionArchived(id: String, archived: Boolean) {
        val a = api ?: run { bannerNoKernel(); return }
        viewModelScope.launch {
            when (val r = a.patchSession(id, archived = archived)) {
                is KernelApi.ApiResult.Ok -> {
                    // 归档 = 从主列表消失（可在「已归档」里展开恢复）。
                    // 同样不能等内核推帧 —— 它不推，界面会看起来「点了没反应」。
                    if (archived) {
                        _state.update { st ->
                            val isCurrent = st.currentSessionId == id
                            st.copy(
                                sessions = st.sessions.filterNot { it.id == id },
                                messages = if (isCurrent) emptyList() else st.messages,
                                title = if (isCurrent) "CodeForge" else st.title,
                                currentSessionId = if (isCurrent) "" else st.currentSessionId,
                            )
                        }
                    }
                    transport?.listSessions()
                    toast(if (archived) "已归档，可在「已归档」里恢复" else "已取消归档")
                }

                is KernelApi.ApiResult.Err -> _state.update { it.copy(banner = Banner(r.message, true)) }
            }
        }
    }

    /**
     * 批量删除会话。
     *
     * 逐条走内核删除（内核没有批量端点）。先本地摘掉，界面即时响应；
     * 全部发完再拉一次真值对齐，避免某条失败后列表和服务端不一致。
     */
    fun deleteSessions(ids: List<String>) {
        if (ids.isEmpty()) return
        val a = api
        _state.update { st ->
            val removingCurrent = st.currentSessionId in ids
            st.copy(
                sessions = st.sessions.filterNot { it.id in ids },
                messages = if (removingCurrent) emptyList() else st.messages,
                title = if (removingCurrent) "CodeForge" else st.title,
                currentSessionId = if (removingCurrent) "" else st.currentSessionId,
            )
        }
        viewModelScope.launch {
            var failed = 0
            ids.forEach { id ->
                if (a == null) {
                    failed++
                } else if (a.deleteSession(id) is KernelApi.ApiResult.Err) {
                    failed++
                }
            }
            transport?.listSessions()
            toast(
                if (failed == 0) "已删除 ${ids.size} 个会话"
                else "已删除 ${ids.size - failed} 个，${failed} 个失败",
            )
        }
    }

    fun deleteSession(id: String) {
        val a = api ?: run { bannerNoKernel(); return }
        viewModelScope.launch {
            when (val r = a.deleteSession(id)) {
                is KernelApi.ApiResult.Ok -> {
                    _state.update { st ->
                        val isCurrent = st.currentSessionId == id
                        st.copy(
                            // 本地立即摘掉：内核在删除/归档后**不推** sessions 帧，
                            // 只清消息区会让列表里继续挂着已删的会话（真机实测「删不掉」）。
                            sessions = st.sessions.filterNot { it.id == id },
                            messages = if (isCurrent) emptyList() else st.messages,
                            title = if (isCurrent) "CodeForge" else st.title,
                            currentSessionId = if (isCurrent) "" else st.currentSessionId,
                        )
                    }
                    // 再向内核拉一次，用真值兜底对齐
                    transport?.listSessions()
                    toast("会话已删除")
                }

                is KernelApi.ApiResult.Err -> _state.update { it.copy(banner = Banner(r.message, true)) }
            }
        }
    }

    /** 保存（新增或按 id 更新）一个供应商，成功后重拉列表。 */
    fun saveProvider(
        id: String,
        name: String,
        baseUrl: String,
        protocol: String,
        keySource: String,
        keyName: String,
        keyValue: String,
        keyTouched: Boolean,
    ) {
        val a = api ?: run { bannerNoKernel(); return }
        viewModelScope.launch {
            when (val r = a.saveProvider(id, name, baseUrl, protocol, keySource, keyName, keyValue, keyTouched)) {
                is KernelApi.ApiResult.Ok -> {
                    refreshProviderDetails()
                    _state.update { it.copy(banner = Banner("供应商已保存", isError = false)) }
                }

                is KernelApi.ApiResult.Err ->
                    _state.update { it.copy(banner = Banner(r.message, isError = true)) }
            }
        }
    }

    /** 删除供应商；内核会顺带解绑该供应商下的模型。 */
    fun deleteProvider(id: String) {
        val a = api ?: run { bannerNoKernel(); return }
        viewModelScope.launch {
            when (val r = a.deleteProvider(id)) {
                is KernelApi.ApiResult.Ok -> {
                    _state.update { st ->
                        st.copy(
                            providerDetails = st.providerDetails.filterNot { it.id == id },
                            banner = Banner("已删除供应商", isError = false),
                        )
                    }
                    refreshProviderDetails()
                }

                is KernelApi.ApiResult.Err ->
                    _state.update { it.copy(banner = Banner(r.message, isError = true)) }
            }
        }
    }

    /** 单独拉一次供应商完整列表（管理页增删后对齐）。 */
    fun refreshProviderDetails() {
        val a = api ?: return
        viewModelScope.launch {
            when (val r = a.listProviderDetails()) {
                is KernelApi.ApiResult.Ok -> {
                    _state.update { it.copy(providerDetails = r.value) }
                    seedDeepSeekIfEmpty(r.value)
                }

                is KernelApi.ApiResult.Err -> Unit
            }
        }
    }

    /**
     * 全新安装时预置 DeepSeek 官方供应商：用户只要填个 KEY 就能用。
     *
     * 判定条件是「供应商和模型库都空」而不是「供应商空」—— 后者会在用户
     * 主动删掉这家供应商之后又自动加回来，等于删不掉。
     */
    private fun seedDeepSeekIfEmpty(providers: List<KernelApi.ProviderInfo>) {
        if (providers.isNotEmpty() || _state.value.libraryModels.isNotEmpty()) return
        if (seededDeepSeek) return
        // 先取 api 再置位：内核没就绪时置位会让预置永远不再重试
        val a = api ?: return
        seededDeepSeek = true
        viewModelScope.launch {
            a.saveProvider(
                id = "p-api-deepseek-com",
                name = "DeepSeek 官方",
                baseUrl = "https://api.deepseek.com/v1",
                protocol = "openai",
                keySource = "plain",
                keyName = "",
                // 密钥留空：由用户在「供应商」子页里填。模型继承供应商的 key，
                // 没填之前 apply 会失败 —— 这一点在 apply 失败提示里会说明。
                keyValue = "",
                keyTouched = false,
            )
            // 一并预置模型条目，带完整规格：1M 输入 / 384K 输出 / 支持图片、不支持视频。
            // 只建供应商不建模型的话，用户填完 KEY 还得自己去探测、勾选，多两步。
            when (val mr = a.saveModelFull(
                id = "deepseek-flash",
                name = "DeepSeek Flash",
                providerId = "p-api-deepseek-com",
                ctxIn = 1_000_000,
                ctxOut = 384_000,
                vision = true,
                video = false,
            )) {
                is KernelApi.ApiResult.Ok -> Unit
                // 之前不接返回值：模型没进库、apply 也跟着失败，界面上毫无线索
                is KernelApi.ApiResult.Err -> _state.update {
                    it.copy(banner = Banner("预置模型失败：${mr.message}", isError = true))
                }
            }
            when (val r = a.listProviderDetails()) {
                is KernelApi.ApiResult.Ok -> _state.update { it.copy(providerDetails = r.value) }
                is KernelApi.ApiResult.Err -> Unit
            }
            // 只写进模型库不 apply，内核还在用 local.yaml 里的旧模型 ——
            // 真机：UI 显示 deepseek-flash，内核实际请求 claude-sonnet-4，上游直接拒收。
            when (a.applyModel("deepseek-flash")) {
                is KernelApi.ApiResult.Ok ->
                    _state.update { it.copy(modelName = "deepseek-flash") }
                is KernelApi.ApiResult.Err -> Unit
            }
        }
    }

    /**
     * 把界面选中的模型同步给内核。
     *
     * 内核不读 AppSettings，只认 local.yaml / 模型库 —— 壳侧改 `modelName`
     * 只是改了个字符串，不发 `/api/models/apply` 的话内核照旧用旧模型。
     * 真机表现：胶囊显示 deepseek-flash，请求却打向 claude-sonnet-4，上游拒收后
     * 客户端只报「数据格式不对（解析失败）」，完全看不出是模型没切。
     */
    private var modelSynced = false

    private fun syncModelToKernel() {
        if (modelSynced) return
        val want = AppSettings.lastModel.value
        if (want.isBlank()) return
        val entry = _state.value.libraryModels.firstOrNull { it.id == want } ?: return
        modelSynced = true
        // 内核报的模型已经就是要的那个，不用再切
        if (_state.value.modelName == entry.id) return
        applyModel(entry.id)
    }

    /**
     * 测试模型模式下的会话落盘 / 恢复。
     *
     * 内核模式的历史由内核自己存（/api/sessions），这里只兜测试模型 ——
     * 它不连内核，消息只活在 ViewModel 里，杀进程就整段丢失
     * （真机复现：聊了半天重开后右侧回到空态，侧栏标题还在）。
     *
     * 只存「能重新读一遍」的字段：角色、正文、思考、时间。
     * 工具卡不恢复 —— 历史工具卡本来就要走 /api/diff 回放，壳侧没这个数据。
     */

    /**
     * 一次性状态提示。
     *
     * 走 [Banner] 通道，界面上由 ToastHost 渲染成浮动气泡（几秒自动消失）。
     * 保存、切换、归档这类「说完就过去」的反馈都应该走这里 —— 静默成功会让
     * 用户怀疑操作没生效。
     */
    private fun toast(text: String, isError: Boolean = false) {
        _state.update { it.copy(banner = Banner(text, isError)) }
    }

    /** 本次进程是否已经尝试过预置 DeepSeek（防止空列表时反复写）。 */
    private var seededDeepSeek = false

    private fun bannerNoKernel() {
        _state.update { it.copy(banner = Banner("内核未连接，此项暂不可用", isError = false)) }
    }

    fun dismissBanner() = _state.update { it.copy(banner = null) }

    /**
     * 手动重连：踢一次内核重启。
     *
     * 传输层的自愈循环只能处理「链路断了」——它处理不了「内核进程本身卡死」
     * 或「前台服务根本没起来」。那两种情况必须重启进程，所以留这个手动入口。
     */
    fun retryKernel() {
        CoreService.restart(getApplication())
        _state.update { it.copy(banner = Banner("正在重新启动内核…", isError = false)) }
    }

    override fun onCleared() {
        transport?.disconnect()
        super.onCleared()
    }
}
