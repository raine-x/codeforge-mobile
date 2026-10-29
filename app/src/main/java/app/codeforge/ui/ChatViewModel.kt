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
import app.codeforge.model.BuiltinPluginInfo
import app.codeforge.model.KernelConfigSummary
import app.codeforge.model.LibraryModel
import app.codeforge.model.SkillInfo
import app.codeforge.model.SubagentPolicy
import app.codeforge.model.ApprovalCard
import app.codeforge.model.ActionPanelState
import app.codeforge.model.BackgroundTask
import app.codeforge.model.ChatMessage
import app.codeforge.model.ContextUsage
import app.codeforge.model.MessageSegment
import app.codeforge.model.MsgRole
import app.codeforge.model.NoticeKind
import app.codeforge.model.PermissionMode
import app.codeforge.model.ProjectItem
import app.codeforge.model.SessionItem
import app.codeforge.model.SubagentTask
import app.codeforge.model.TodoItem
import app.codeforge.model.ToolCall
import app.codeforge.model.ToolState
import app.codeforge.model.TreeEntry
import app.codeforge.ui.settings.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.ArrayDeque
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
    /** 后台脚本任务（GET /api/tasks 拉取；task 帧只做刷新提示），最新在前。 */
    val tasks: List<BackgroundTask> = emptyList(),
    /** 子智能体任务卡（内核 subagent 帧），按 id 增量维护。 */
    val subagents: List<SubagentTask> = emptyList(),
    /** 上下文占用（内核 context 帧），null = 还没收到过。 */
    val contextUsage: ContextUsage? = null,
    /** 目标模式账本（goal 帧），null = 当前会话没有目标模式。 */
    val goal: app.codeforge.model.GoalStatus? = null,
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
    /** 内置插件完整信息（用途/使用场景说明）；[builtinPlugins] 保留给 Composer 的 @ 补全。 */
    val builtinPluginInfos: List<BuiltinPluginInfo> = emptyList(),
    /** MCP 插件开关。 */
    val mcpPlugins: List<app.codeforge.core.KernelApi.McpPluginInfo> = emptyList(),
    /** 技能（名称 → 描述），只读展示。 */
    val skills: List<Pair<String, String>> = emptyList(),
    /** 技能完整信息（显示名/触发词/启用态）；[skills] 保留给 Composer 的 @ 补全。 */
    val skillInfos: List<SkillInfo> = emptyList(),
    /** 用户记忆（id → 内容），设置页可增删。 */
    val memories: List<Pair<String, String>> = emptyList(),
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
    /** 侧栏项目（由 [sessions] 按 workspace 分组聚合，见 aggregateProjects）。 */
    val projects: List<ProjectItem> = emptyList(),
    /** 当前项目（会话归属的工作区绝对路径；空 = 未选择工作区）。 */
    val currentWorkspace: String = "",
    /** 当前项目显示名（内核 workspace_names，空则界面回落路径末段）。 */
    val currentWorkspaceName: String = "",
    /** 正在切换工作区（侧栏转圈/禁用入口）。 */
    val workspaceSwitching: Boolean = false,
    /** 文件树（GET /api/tree 的当前层）。 */
    val treeEntries: List<TreeEntry> = emptyList(),
    /** 文件树当前目录（绝对路径）。 */
    val treePath: String = "",
    /** 文件树上一级目录（内核算好；空 = 已在根）。 */
    val treeParent: String = "",
    /** 文件树错误（路径不存在 / 无权限，与「空目录」分开）。 */
    val treeError: String? = null,
    /** @plan 回合结束后的计划书操作面板；null = 不显示。 */
    val actionPanel: ActionPanelState? = null,
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

    /**
     * 本轮是否由 @plan 触发（用户在消息里点名计划书）。
     * 只有为 true 时，本轮 idle 才会把最后一条助手正文装进 actionPanel ——
     * 普通回合弹面板是打扰。
     */
    private var planPending = false

    /** 用户消息里点名计划书的触发词；`\b` 避免把 "xx@plane.com" 之类算进来。 */
    private val planMention = Regex("""@plan\b""", RegexOption.IGNORE_CASE)

    /**
     * 待发送的一条消息。附件此时是**本地暂存绝对路径**（[EnvKit.outboxDir]），
     * 真正的落点要等会话/工作区就绪后由 `/api/upload_file` 决定。
     */
    private data class PendingSend(
        val text: String,
        val attachments: List<String>,
        val bubbleText: String,
    )

    /**
     * 发送串行队列。
     *
     * 为什么不能直接发：新建会话是异步的（new_session → session 帧 → 切工作区），
     * 用户点完「+」立刻打字发送时，currentSessionId 还是旧的；直接发会把消息
     * 送进旧会话，或带空 id 让内核再建一个会话 —— 这正是「会话错乱」的来源。
     * 排队后统一在 [deliver] 里等会话与工作区落定再上行。
     */
    private val pendingSends = ArrayDeque<PendingSend>()
    private val sendGate = Mutex()

    /** new_session 已发出、尚未收到 session 帧；发送队列等它落定。 */
    private var newSessionGate: CompletableDeferred<String>? = null

    init {
        viewModelScope.launch {
            CoreService.state.collect { onKernelState(it) }
        }
        viewModelScope.launch {
            AppVisibility.hidden.collect { h ->
                transport?.sendVisibility(h)
                // 从 APP 外回来：内核可能已在后台推进了别的会话（busy/新消息），
                // 拉一次会话列表把「运行中」与最新顺序追平。
                if (!h && transport != null) transport?.listSessions()
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
     * 会话工作区：**未绑定工作区**的会话统一用应用私有的 inbox 空间。
     *
     * 为什么不再「每个会话一个空格子」：内核在会话**创建那一刻**就把当时的
     * 工作区写进 sessions.workspace，壳侧事后没法改这条记录；而按会话 id 建
     * 目录必须先有 id —— 死循环。统一 inbox 后，「会话归属 / 附件落点 /
     * 加载时的工作区切换」三者同源。
     *
     * 已归属项目的会话必须指回项目目录，否则「项目」只是侧栏上的一个分组壳，
     * 文件工具实际还落在私有空间里。内核重启后 Ready 分支会重指。
     */
    private fun applySessionSpace(sessionId: String) {
        viewModelScope.launch { applySessionSpaceNow(sessionId) }
    }

    /**
     * [applySessionSpace] 的挂起版：发送附件前必须**等它完成**再上行，
     * 否则 `/api/upload_file` 会写进旧工作区，附件路径与新工作区对不上（问题 1）。
     */
    private suspend fun applySessionSpaceNow(sessionId: String): Boolean {
        if (sessionId.isEmpty() || boundKind != BoundKind.Kernel) return false
        val a = api ?: return false
        val st = _state.value
        val session = st.sessions.firstOrNull { it.id == sessionId }
        // 优先级：会话元信息里的 workspace（真值）→ 当前项目 → 应用私有 inbox
        val target = session?.workspace?.takeUnless { it.isBlank() }
            ?: st.currentWorkspace.takeUnless { it.isBlank() }
            ?: EnvKit.inboxSpace(getApplication()).absolutePath
        return when (val r = a.setWorkspace(target)) {
            is KernelApi.ApiResult.Ok -> true
            is KernelApi.ApiResult.Err -> {
                _state.update { it.copy(banner = Banner(r.message, isError = true)) }
                false
            }
        }
    }

    /** 绑定传输层：开始收事件并建立连接。内核重启换端口时也会走到这里。 */
    private fun bind(t: CoreTransport) {
        transport = t
        // 重连/换绑后传输层的 currentSession 是空的：把界面正在看的会话同步过去，
        // 否则第一条消息会带空 id，内核按「新会话」处理（问题 2 的典型触发路径）。
        _state.value.currentSessionId.takeIf { it.isNotBlank() }?.let { t.setCurrentSession(it) }
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
                s.applySessions(
                    s.sessions.map { if (it.id == e.sessionId) it.copy(busy = true) else it },
                )
            }

            is CoreEvent.Idle -> _state.update { s ->
                s.applySessions(
                    s.sessions.map { if (it.id == e.sessionId) it.copy(busy = false) else it },
                )
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
                _state.update { st ->
                    val sessions = event.sessions.ifEmpty { st.sessions }
                    st.copy(
                        connected = true,
                        // ready 帧自带会话与配置；老内核 payload 为空则退回主动拉
                        modelName = event.model.ifEmpty { st.modelName },
                    ).applySessions(mergeBusy(sessions, st.sessions))
                }
                if (_state.value.sessions.isEmpty()) transport?.listSessions()
                // 项目名/最近会话顺序以 REST 为准补一次（ready 只带会话，不带 workspace_names 的完整表）
                refreshWorkspaces()
            }

            is CoreEvent.Link -> {
                if (event.connected) {
                    _state.update { it.copy(connected = true, banner = null) }
                    // 链路刚通：REST 之前若没初始化成功，这里补一次
                    if (_state.value.kernelConfig == null && boundPort > 0) {
                        initKernelApi(boundPort)
                    }
                    // 断线期间内核可能已推进/截断历史；重连后用 load_session 的
                    // history 快照重建当前视图，不能拿断线前的 UI 当真相。
                    val sid = _state.value.currentSessionId
                    if (sid.isNotBlank()) transport?.loadSession(sid)
                } else {
                    _state.update {
                        it.copy(
                            connected = false,
                            // 服务端审批挂在连接上，断线即失效；先收掉卡片，
                            // 避免重连后点一个已经不存在于服务端的 approval_id。
                            approvals = emptyList(),
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
                    // 默认标题按需求统一为「新建会话」（内核自己的默认名是「新会话」，
                    // 已在解析层归一）；首条用户消息发出后会改成本地前 24 字。
                    val newTitle = event.title.ifBlank { "新建会话" }
                    val exists = st.sessions.any { it.id == event.sessionId }
                    // 会话切到了另一条：新会话是空白上下文，会话级状态必须一起清掉。
                    // 过去只改 id/title，界面还挂着上一条会话的消息与运行态 ——
                    // 用户必须切走再切回才「刷掉历史」（问题 6）。session 帧只在
                    // new_session 时下发，因此「id 变了」就是新建的确定信号。
                    val isNew = event.sessionId != st.currentSessionId
                    val patched = if (exists) {
                        st.sessions.map {
                            if (it.id == event.sessionId) it.copy(title = event.title.ifBlank { it.title }) else it
                        }
                    } else {
                        listOf(
                            SessionItem(
                                id = event.sessionId,
                                title = newTitle,
                                updatedAt = System.currentTimeMillis(),
                                busy = false,
                                // 新会话归属当前项目：内核 next 帧的 sessions 会带回真值，
                                // 这里先填上，侧栏不会把刚建的会话塞进「未选择工作区」。
                                workspace = st.currentWorkspace,
                                workspaceName = st.currentWorkspaceName,
                            ),
                        ) + st.sessions
                    }
                    st.copy(
                        currentSessionId = event.sessionId,
                        title = newTitle,
                        messages = if (isNew) emptyList() else st.messages,
                        running = if (isNew) false else st.running,
                        approvals = if (isNew) emptyList() else st.approvals,
                        todos = if (isNew) emptyList() else st.todos,
                        subagents = if (isNew) emptyList() else st.subagents,
                        contextUsage = if (isNew) null else st.contextUsage,
                        goal = if (isNew) null else st.goal,
                        checkpoints = if (isNew) emptyList() else st.checkpoints,
                        resumeBack = if (isNew) -1 else st.resumeBack,
                        editableAnchors = if (isNew) emptyList() else st.editableAnchors,
                        actionPanel = if (isNew) null else st.actionPanel,
                        editText = if (isNew) null else st.editText,
                        editAttachments = if (isNew) emptyList() else st.editAttachments,
                        editBack = if (isNew) null else st.editBack,
                    ).applySessions(patched)
                }
                if (event.sessionId != "") planPending = false
                transport?.setCurrentSession(event.sessionId)
                // 说明本次 new_session 已经落定：等它的发送队列可以继续
                newSessionGate?.complete(event.sessionId)
                newSessionGate = null
                applySessionSpace(event.sessionId)
            }

            is CoreEvent.Sessions -> _state.update { st ->
                // 内核的会话列表可能不含「刚建好还没说话」的会话，直接覆盖会让
                // 新建的对话在侧栏凭空消失。当前会话不在列表里就本地补一条。
                val cur = st.currentSessionId
                val patched = if (cur.isNotBlank() && event.items.none { it.id == cur }) {
                    listOf(
                        SessionItem(
                            id = cur,
                            title = "新建会话",
                            updatedAt = System.currentTimeMillis(),
                            busy = false,
                            workspace = st.currentWorkspace,
                            workspaceName = st.currentWorkspaceName,
                        ),
                    ) + event.items
                } else {
                    event.items
                }
                st.applySessions(mergeBusy(patched, st.sessions))
            }

            is CoreEvent.History -> {
                // 切会话（含重连后的快照重建）视作新的回合边界：@plan 标记不能跨会话残留
                planPending = false
                // 历史帧是会话确立的另一条权威路径：传输层必须与界面同 id
                transport?.setCurrentSession(event.sessionId)
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
                        ).applySessions(it.sessions)
                    }
                }
                applySessionSpace(event.sessionId)
            }

            // 当前会话的忙碌态也要写回会话列表：项目行的「转圈」是从 sessions 的
            // busy 聚合出来的，只改 running 的话侧栏看不到哪个项目在跑。
            is CoreEvent.Busy -> _state.update { st ->
                st.copy(running = true, banner = null)
                    .applySessions(st.sessions.map { if (it.id == event.sessionId) it.copy(busy = true) else it })
            }

            is CoreEvent.Idle -> {
                // @plan 回合结束：把最后一条助手正文装进操作面板。
                // 只认本轮（planPending 由 send 置位），普通回合不弹。
                val showPlan = planPending
                planPending = false
                _state.update { st ->
                    val msgs = st.messages.map { m ->
                        if (m.streaming) m.copy(streaming = false) else m
                    }
                    val panel = if (showPlan) {
                        msgs.lastOrNull { it.role == MsgRole.Assistant && it.text.isNotBlank() }
                            ?.let { ActionPanelState(content = it.text) } ?: st.actionPanel
                    } else {
                        st.actionPanel
                    }
                    st.copy(
                        running = false,
                        messages = msgs,
                        actionPanel = panel,
                    ).applySessions(st.sessions.map { if (it.id == event.sessionId) it.copy(busy = false) else it })
                }
            }

            is CoreEvent.Checkpoints -> _state.update {
                it.copy(
                    checkpoints = event.steps,
                    resumeBack = event.resumeBack,
                    editableAnchors = event.editables,
                )
            }

            is CoreEvent.StreamReset -> {
                // edit 帧：内核已把历史截断，随后会补一帧 history 权威快照重建视图。
                // 旧实现只补换行保留「段间分隔」，现在段序才是渲染依据 ——
                // 必须把在途消息的 segments 清空，否则截断前的旧段会拼在重跑内容前面
                // （表现为编辑重发后消息「渲染两次」）。text 仍保留，旧渲染不受影响。
                planPending = false
                _state.update { s ->
                    val msgs = s.messages.map { m ->
                        if (m.role == MsgRole.Assistant && m.streaming) {
                            val text = if (m.text.isNotEmpty() && !m.text.endsWith("\n")) m.text + "\n" else m.text
                            m.copy(text = text, segments = emptyList())
                        } else {
                            m
                        }
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

            is CoreEvent.ApprovalTimedOut -> {
                // 超时必须与「用户拒绝」区分：拒绝是用户意图，超时是无人处理。
                _state.update { s ->
                    s.copy(
                        approvals = s.approvals.filterNot { it.id == event.approvalId },
                        messages = s.messages + ChatMessage(
                            id = "n-${seq.incrementAndGet()}",
                            role = MsgRole.Notice,
                            text = event.message,
                            kind = NoticeKind.Warn,
                            time = System.currentTimeMillis(),
                        ),
                    )
                }
            }

            is CoreEvent.Goal -> _state.update { it.copy(goal = event.status) }

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

            // 后台任务状态变化：只刷新列表（帧里没有输出，避免大日志挤占 WS）
            is CoreEvent.TaskChanged -> refreshTasks()

            is CoreEvent.Failure -> {
                // 限流 / 网络类失败要留一个「重试」入口：内核在 llm 层已经自动退避重试过
                // （429 无 RPM 时每次等 10 秒 ×5 次），仍然失败说明窗口没滑出来。
                // 这时只弹一条 2 秒就消失的气泡，用户看到的就是「卡了一会儿然后断了」，
                // 既不知道发生过重试，也没有继续的办法。
                val retryable = listOf(
                    "限流", "429", "超时", "timeout", "网络", "连接", "断开", "上游",
                ).any { event.message.contains(it, ignoreCase = true) }
                // 失败没有 idle，本轮不会再有「计划书已生成」的时机，标记要作废
                planPending = false
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

    /** 正文增量并入末段（末段是正文就接着写，否则新起一段）。 */
    private fun appendTextSegment(segs: List<MessageSegment>, text: String): List<MessageSegment> {
        val last = segs.lastOrNull()
        return if (last is MessageSegment.Text) {
            segs.dropLast(1) + last.copy(text = last.text + text)
        } else {
            segs + MessageSegment.Text(text)
        }
    }

    /** 思考增量并入末段（末段是思考就接着写，否则新起一段 —— 段序即真实发生顺序）。 */
    private fun appendThinkingSegment(segs: List<MessageSegment>, text: String): List<MessageSegment> {
        val last = segs.lastOrNull()
        return if (last is MessageSegment.Thinking) {
            segs.dropLast(1) + last.copy(text = last.text + text)
        } else {
            segs + MessageSegment.Thinking(text)
        }
    }

    /** 工具段按 id 就地更新；没有就追加到末尾（保证「思考-工具-正文」的真实顺序）。 */
    private fun upsertToolSegment(segs: List<MessageSegment>, call: ToolCall): List<MessageSegment> {
        val at = segs.indexOfFirst { it is MessageSegment.Tool && it.call.id == call.id }
        return if (at >= 0) {
            segs.toMutableList().also { it[at] = MessageSegment.Tool(call) }
        } else {
            segs + MessageSegment.Tool(call)
        }
    }

    /** 助手正文增量：并入当前流式消息，没有就新建一条。 */
    private fun appendAssistantText(text: String) {
        _state.update { s ->
            val msgs = s.messages.toMutableList()
            val last = msgs.lastOrNull()
            if (last != null && last.role == MsgRole.Assistant && last.streaming) {
                msgs[msgs.lastIndex] = last.copy(
                    text = last.text + text,
                    segments = appendTextSegment(last.segments, text),
                )
            } else {
                msgs.add(
                    ChatMessage(
                        id = "m-${seq.incrementAndGet()}",
                        role = MsgRole.Assistant,
                        text = text,
                        streaming = true,
                        time = System.currentTimeMillis(),
                        segments = listOf(MessageSegment.Text(text)),
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
                msgs[msgs.lastIndex] = last.copy(
                    thinking = (last.thinking ?: "") + text,
                    segments = appendThinkingSegment(last.segments, text),
                )
            } else {
                msgs.add(
                    ChatMessage(
                        id = "m-${seq.incrementAndGet()}",
                        role = MsgRole.Assistant,
                        text = "",
                        thinking = text,
                        streaming = true,
                        time = System.currentTimeMillis(),
                        segments = listOf(MessageSegment.Thinking(text)),
                    ),
                )
            }
            s.copy(messages = msgs)
        }
    }

    /**
     * 工具卡片挂在「当前这条助手消息」上。
     *
     * 只从**最后一条用户消息之后**找助手消息：之前是「找最近的助手消息」，
     * 上一轮已经结束的助手消息也会被命中，新工具卡就被挂到旧轮正文后面 ——
     * 界面上所有工具都堆到正文前/后的错位就是这么来的。
     */
    private fun upsertTool(call: ToolCall) {
        _state.update { s ->
            val msgs = s.messages.toMutableList()
            var idx = currentTurnAssistantIndex(msgs)
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
            msgs[idx] = target.copy(
                tools = tools,
                segments = upsertToolSegment(target.segments, call),
            )
            s.copy(messages = msgs)
        }
    }

    /** 从末尾往前找助手消息，遇到用户消息即为上一轮边界（通知行跳过）；找不到返回 -1。 */
    private fun currentTurnAssistantIndex(msgs: List<ChatMessage>): Int {
        for (i in msgs.indices.reversed()) {
            val m = msgs[i]
            if (m.role == MsgRole.Assistant) return i
            if (m.role == MsgRole.User) return -1
        }
        return -1
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
                    // 段里的工具卡是同一份数据的另一份拷贝，必须一起改，
                    // 否则按段渲染时卡永远停在「执行中」。
                    val segs = m.segments.map { seg ->
                        if (seg is MessageSegment.Tool && seg.call.id == event.toolCallId) {
                            MessageSegment.Tool(tools[at])
                        } else {
                            seg
                        }
                    }
                    msgs[i] = m.copy(tools = tools, segments = segs)
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
        if (transport == null) {
            _state.update { it.copy(banner = Banner("内核启动中，稍候再发", isError = false)) }
            return
        }
        // @plan 之后本轮结束要弹计划书面板；普通回合不弹
        if (planMention.containsMatchIn(trimmed)) planPending = true
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
            transport?.editUserMessage(trimmed, back)
            return
        }
        clearEdit()

        val bubbleText = trimmed.ifEmpty {
            attachments.joinToString(" ") { "📎 ${it.substringAfterLast('/')}" }
        }
        enqueueSend(PendingSend(trimmed, attachments, bubbleText))
    }

    /**
     * 发送队列的串行入口：任何一条用户消息都从这里进，保证「会话就绪 → 切工作区
     * → 上传附件 → 上行」的顺序不被并发打乱。
     */
    private fun enqueueSend(req: PendingSend) {
        pendingSends.addLast(req)
        viewModelScope.launch { drainSends() }
    }

    private suspend fun drainSends() {
        sendGate.withLock {
            while (pendingSends.isNotEmpty()) {
                deliver(pendingSends.removeFirst())
            }
        }
    }

    /**
     * 投递一条消息：等会话落定 → 等工作区切换完成 → 上传附件 → 上行。
     *
     * 气泡先于上传显示（纯附件大视频上传可能要几秒，界面不能空着）；
     * 上传成功后把气泡里的本地暂存路径换成内核返回的工作区相对路径，
     * 这样历史重放、附件预览与内核读附件三者同源（问题 1）。
     */
    private suspend fun deliver(req: PendingSend) {
        val t = transport ?: return
        val sid = awaitSession(t) ?: return
        val bubbleId = "m-${seq.incrementAndGet()}"
        _state.update { s ->
            s.copy(
                banner = null,
                messages = s.messages + ChatMessage(
                    id = bubbleId,
                    role = MsgRole.User,
                    // 纯附件消息在气泡里给出可见文案，正文里仍是原始文本
                    text = req.bubbleText,
                    attachments = req.attachments,
                    time = System.currentTimeMillis(),
                ),
            )
        }
        // 首条消息后把占位标题改成消息前 24 字：内核也会在落盘时改（history.go 的
        // Save），但 sessions 帧要等一轮结束才回来 —— 本地先乐观更新，避免侧栏
        // 一排「新建会话」分不清。
        updateSessionTitleFromFirstMessage(req.bubbleText)

        // 附件从工作区根解析，必须等工作区切好再上传；纯文本也走这一步，
        // 保证内核本轮的文件根与界面显示的会话归属一致。
        applySessionSpaceNow(sid)
        val staged = uploadAttachments(req.attachments) ?: return
        if (staged != req.attachments && staged.isNotEmpty()) {
            _state.update { s ->
                s.copy(
                    messages = s.messages.map {
                        if (it.id == bubbleId) it.copy(attachments = staged) else it
                    },
                )
            }
        }
        // 本地暂存已经复制进工作区：删掉副本，避免 outbox 无限增长。
        // 只删绝对路径（相对路径是内核工作区里的文件，绝不能动）。
        req.attachments.forEach { p ->
            val f = java.io.File(p)
            if (f.isAbsolute) runCatching { f.delete() }
        }
        t.setCurrentSession(sid)
        t.sendMessage(req.text, staged)
    }

    /**
     * 发送前的会话闸门：正在新建会话就等 `session` 帧；一条都没有就先建一条。
     *
     * 等不到（内核没响应 / WS 未就绪）时宁可报错也不发 —— 发出去的消息
     * 会落进旧会话或触发内核自动新建，那正是用户报告的「会话错乱」。
     */
    private suspend fun awaitSession(t: CoreTransport): String? {
        var gate = newSessionGate
        if (gate == null && _state.value.currentSessionId.isBlank()) {
            gate = requestNewSession(t)
        }
        if (gate != null) {
            val sid = withTimeoutOrNull(8_000) { gate.await() }
            if (sid == null) {
                // 超时要把闸门撤掉，否则后续每条消息都白等 8 秒
                if (newSessionGate === gate) newSessionGate = null
                _state.update {
                    it.copy(banner = Banner("新建会话没有响应，请检查内核连接后重试", isError = true))
                }
            }
            return sid
        }
        val sid = _state.value.currentSessionId
        if (sid.isBlank()) {
            _state.update { it.copy(banner = Banner("会话尚未就绪，稍候再发", isError = true)) }
            return null
        }
        return sid
    }

    /**
     * 请求内核新建会话，并登记一个闸门给发送队列等待。
     *
     * 未选择项目且内核当前没有工作区时，先把默认私有空间指好再建会话 ——
     * 会话的 workspace 在创建那一刻就固定下来，事后补切工作区改不回来。
     */
    private suspend fun requestNewSession(t: CoreTransport): CompletableDeferred<String>? {
        val gate = CompletableDeferred<String>()
        newSessionGate = gate
        val a = api
        val st = _state.value
        if (a != null && st.currentWorkspace.isBlank() && st.kernelConfig?.workDir.isNullOrBlank()) {
            val root = EnvKit.inboxSpace(getApplication()).absolutePath
            when (val r = a.setWorkspace(root)) {
                is KernelApi.ApiResult.Ok -> _state.update {
                    it.copy(currentWorkspace = root, currentWorkspaceName = "应用私有空间")
                }

                is KernelApi.ApiResult.Err -> {
                    newSessionGate = null
                    _state.update { it.copy(banner = Banner(r.message, isError = true)) }
                    return null
                }
            }
        }
        if (!t.newSession()) {
            // WS 没连上（或刚断）：帧发不出去。不提示的话用户只会觉得按钮坏了。
            newSessionGate = null
            _state.update { it.copy(banner = Banner("连接尚未就绪，稍等一下再试", isError = true)) }
            return null
        }
        return gate
    }

    /**
     * 附件上传：本地暂存绝对路径 → 内核工作区（`/api/upload_file`）。
     *
     * 相对路径（历史消息重发 / 编辑重发带回来的）已经在工作区里，原样透传；
     * 绝对路径一律走服务端原子落盘 —— 由内核决定真正的落点，壳侧不再猜目录。
     * 返回 null = 上传失败（横幅已提示），调用方应保留气泡但不发上行帧。
     */
    private suspend fun uploadAttachments(paths: List<String>): List<String>? {
        if (paths.isEmpty()) return emptyList()
        val a = api ?: run {
            _state.update { it.copy(banner = Banner("内核未连接，附件无法发送", isError = true)) }
            return null
        }
        val staged = ArrayList<String>(paths.size)
        for (p in paths) {
            val f = java.io.File(p)
            if (!f.isAbsolute) {
                staged += p
                continue
            }
            if (!f.isFile) {
                _state.update { it.copy(banner = Banner("附件不存在：${f.name}", isError = true)) }
                return null
            }
            when (val r = a.uploadFile(f)) {
                is KernelApi.ApiResult.Ok -> staged += r.value
                is KernelApi.ApiResult.Err -> {
                    _state.update { it.copy(banner = Banner("附件上传失败：${r.message}", isError = true)) }
                    return null
                }
            }
        }
        return staged
    }

    /**
     * 只在标题仍是占位名（新建会话等）时才改：用户/内核已经起过名的会话不能覆盖。
     * 首次消息之后再发也不该再改，长度 24 与内核 truncateRunes(t, 24) 对齐。
     */
    private fun updateSessionTitleFromFirstMessage(text: String) {
        val st = _state.value
        if (!isPlaceholderSessionTitle(st.title)) return
        val newTitle = text.trim().take(24)
        if (newTitle.isEmpty()) return
        _state.update { s ->
            s.copy(
                title = newTitle,
                sessions = s.sessions.map {
                    if (it.id == st.currentSessionId && isPlaceholderSessionTitle(it.title)) {
                        it.copy(title = newTitle)
                    } else {
                        it
                    }
                },
            )
        }
    }

    private fun isPlaceholderSessionTitle(t: String): Boolean {
        val s = t.trim()
        return s.isEmpty() || s == "新会话" || s == "新建会话" || s == "新对话" ||
            s == "未命名" || s.startsWith("新对话 ")
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

    /** 撤销当前会话最近一次文件写入（内核按会话分桶；撤销结果只回传路径与剩余步数）。 */
    fun undoLastWrite(sessionId: String = _state.value.currentSessionId) {
        if (sessionId.isBlank()) {
            toast("还没有可撤销的会话")
            return
        }
        val a = api ?: run { bannerNoKernel(); return }
        viewModelScope.launch {
            when (val r = a.undo(sessionId)) {
                is KernelApi.ApiResult.Ok -> {
                    val res = r.value
                    if (res.ok) {
                        toast(
                            "已撤销 ${res.path.substringAfterLast('/')}（剩余 ${res.remaining} 步）",
                        )
                        pullCheckpoints()
                    } else {
                        toast(res.message.ifBlank { "没有可撤销的操作" }, isError = true)
                    }
                }

                is KernelApi.ApiResult.Err ->
                    _state.update { it.copy(banner = Banner(r.message, true)) }
            }
        }
    }

    /** 断点重试：保留用户原话，截掉未完成回复并按检查点回退文件。 */
    fun retryUnfinished() {
        val back = _state.value.resumeBack
        if (back < 0) {
            toast("没有未完成的轮次")
            return
        }
        if (_state.value.running) return
        _state.update { it.copy(running = true, banner = null) }
        transport?.retry(back, rollbackFiles = true)
    }

    fun createSession() {
        createSessionInternal()
    }

    /**
     * 新建会话。
     *
     * 统一走 [requestNewSession]：它会先登记闸门再发 new_session，
     * 用户紧接着发送的消息会等这条会话落定（问题 2/6 的根因都在这里）。
     * 「项目下 ＋」不走这里 —— 跨项目要先切工作区再建会话，见 [newSessionInProject]。
     */
    private fun createSessionInternal() {
        val st = _state.value
        // 当前已经是「空会话」就不再新建 —— 否则连点几下会攒出一串空会话
        // （真机上就是这样堆出 5 条同名「新对话」）。空会话没有任何内容，
        // 复用它对用户来说和「新建」没有区别。
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
        if (newSessionGate != null) return
        viewModelScope.launch { requestNewSession(t) }
    }

    /**
     * 在指定项目下新建会话：先切工作区（挂起等 REST 完成），再 new_session。
     *
     * 旧实现是 `switchProject(workspace)` + 立刻 `createSession()` —— 后者是
     * 异步 REST，new_session 帧先到内核时工作区还是上一个项目，会话就归属错了
     * （用户报告的「项目下新建会话，发消息又自动新开对话」）。
     */
    fun newSessionInProject(workspace: String) {
        val a = api ?: run { bannerNoKernel(); return }
        val t = transport ?: run {
            _state.update { it.copy(banner = Banner("内核未连接，暂时无法新建对话", true)) }
            return
        }
        if (_state.value.workspaceSwitching || newSessionGate != null) return
        if (workspace == _state.value.currentWorkspace) {
            createSessionInternal()
            return
        }
        if (_state.value.sessions.any { it.busy }) {
            toast("有会话正在运行，暂不能切换项目", isError = true)
            return
        }
        val display = _state.value.projects.firstOrNull { it.workspace == workspace }?.name
            ?: displayWorkspaceName(workspace)
        viewModelScope.launch {
            _state.update { it.copy(workspaceSwitching = true) }
            when (val r = a.setWorkspace(workspace)) {
                is KernelApi.ApiResult.Ok -> {
                    _state.update {
                        it.copy(
                            currentWorkspace = workspace,
                            currentWorkspaceName = display,
                            workspaceSwitching = false,
                            treeEntries = emptyList(),
                            treePath = "",
                            treeParent = "",
                            treeError = null,
                        )
                    }
                    requestNewSession(t)
                    t.listSessions()
                }

                is KernelApi.ApiResult.Err ->
                    _state.update { it.copy(workspaceSwitching = false, banner = Banner(r.message, true)) }
            }
        }
    }

    // ---------- 项目（工作区）----------

    /**
     * 新建项目：POST /api/workspace → POST /api/perm → WS new_session，
     * 成功后刷新项目名表与会话列表。
     *
     * [workspacePath] 是绝对路径；传空串 = 「未选择工作区」项目（内核把空串当
     * 新建项目的临时清空语义，不会落盘）。[name] 为空时默认取目录末段。
     * 同一路径已有项目时不再重复建 —— 提示「已有空的新项目」并切过去。
     */
    fun createProject(name: String, workspacePath: String, permission: PermissionMode) {
        val a = api ?: run { bannerNoKernel(); return }
        val t = transport ?: run {
            _state.update { it.copy(banner = Banner("内核未连接，暂时无法新建项目", true)) }
            return
        }
        if (_state.value.workspaceSwitching) return
        val rawPath = workspacePath.trim()
        val explicitName = name.trim()
        // 工作区是**可选**的：不选文件夹时在 `$CELL/workspace/<项目id>` 下自动建
        // 一个独立目录做默认工作区。
        //
        // 为什么用项目 id 而不是会话 id：内核在**会话创建那一刻**就把当前 root
        // 写进 `sessions.workspace`，事后改不回来；而会话 id 正是创建后才有的。
        // 另外项目会被多个会话共享，拿第一条会话的 id 当目录名语义也不对。
        // 目录名用 id 后，重命名只改 workspace_names 里的显示名，文件夹保持不变。
        val path = if (rawPath.isNotBlank()) {
            rawPath
        } else {
            val id = EnvKit.newProjectId()
            EnvKit.newProjectWorkspace(getApplication(), id).absolutePath
        }
        // 显示名：优先用户输入；没填就用目录名（自动工作区即项目 id）
        val display = explicitName.ifEmpty { displayWorkspaceName(path) }
        val existing = _state.value.projects.firstOrNull { it.workspace == path }
        if (existing != null) {
            toast("已有空的新项目：${existing.name}", isError = false)
            switchProject(path)
            return
        }
        // 新建项目也是切工作区：别的项目在跑时切过去会改掉那一轮的文件根
        if (path != _state.value.currentWorkspace && _state.value.sessions.any { it.busy }) {
            toast("有会话正在运行，暂不能新建项目", isError = true)
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(workspaceSwitching = true) }
            when (val r = a.setWorkspace(path)) {
                is KernelApi.ApiResult.Ok -> {
                    a.setPerm(permission.name.lowercase())
                    // 显示名只写 workspace_names（纯显示名）：内核明确「改 workspace
                    // 会让项目失去工作区」，所以绝不能拿显示名当归属键。
                    // 总是写一次（即使显示名就是自动生成的 id）：用户要求「在数据库
                    // 存好名称」，不依赖取列表时按路径末段兜底。
                    if (display.isNotBlank()) a.patchWorkspace(path, newName = display)
                    _state.update {
                        it.copy(
                            currentWorkspace = path,
                            currentWorkspaceName = display,
                            permissionMode = permission,
                            workspaceSwitching = false,
                            treeEntries = emptyList(),
                            treePath = "",
                            treeParent = "",
                            treeError = null,
                        )
                    }
                    AppSettings.setLastPermission(permission.name)
                    // WS 帧发不出去（连接刚断）时项目已切好但会话没建：必须告知，
                    // 否则侧栏看起来「新建项目没反应」。
                    val created = t.newSession()
                    refreshWorkspaces()
                    t.listSessions()
                    if (created) {
                        toast("已新建项目：$display")
                    } else {
                        toast("项目已切换，但新会话未创建（连接未就绪）", isError = true)
                    }
                }

                is KernelApi.ApiResult.Err ->
                    _state.update { it.copy(workspaceSwitching = false, banner = Banner(r.message, true)) }
            }
        }
    }

    /**
     * 切到另一个项目：POST /api/workspace，然后载入该项目最近会话（没有就新建）。
     *
     * [workspace] 传空串 = 「不选择文件夹」：落到应用私有的 inbox 空间。
     * 不能直接把空串发给内核 —— 那是「清空工作区」语义，之后新建的会话
     * 会带上空 workspace，附件与文件工具都没有有效根。
     *
     * **不动其他项目/会话的运行状态**：只切当前视图与全局工作区，sessions 里的
     * busy 标记原样保留（项目行的转圈靠它维持）。
     */
    fun switchProject(workspace: String) {
        val a = api ?: run { bannerNoKernel(); return }
        val t = transport ?: run {
            _state.update { it.copy(banner = Banner("内核未连接，暂时无法切换项目", true)) }
            return
        }
        if (_state.value.workspaceSwitching) return
        // 新建会话还没落定时切项目会让那条会话归属到别的项目，直接拒绝并提示
        if (newSessionGate != null) {
            toast("正在新建会话，请稍候再切换项目", isError = true)
            return
        }
        val target = workspace.ifBlank { EnvKit.inboxSpace(getApplication()).absolutePath }
        // 已在当前项目：只把列表刷新一次，不打断正在进行的会话
        if (target == _state.value.currentWorkspace) {
            t.listSessions()
            return
        }
        // 工作区是内核进程的全局状态，切它会改掉正在跑那一轮的文件根
        // （内核 /api/workspace 自己不做这个检查），所以壳侧必须挡住。
        if (_state.value.sessions.any { it.busy }) {
            toast("有会话正在运行，暂不能切换项目", isError = true)
            return
        }
        val display = _state.value.projects.firstOrNull { it.workspace == target }?.name
            ?: displayWorkspaceName(target)
        viewModelScope.launch {
            _state.update { it.copy(workspaceSwitching = true) }
            when (val r = a.setWorkspace(target)) {
                is KernelApi.ApiResult.Ok -> {
                    // 内核会话列表按 updated_at 倒序，第一条命中即该项目最近的会话
                    val latest = _state.value.sessions.firstOrNull {
                        it.workspace == target && !it.archived
                    }
                    _state.update {
                        it.copy(
                            currentWorkspace = target,
                            currentWorkspaceName = display,
                            workspaceSwitching = false,
                            treeEntries = emptyList(),
                            treePath = "",
                            treeParent = "",
                            treeError = null,
                        )
                    }
                    if (latest != null) {
                        t.loadSession(latest.id)
                    } else {
                        requestNewSession(t)
                    }
                    t.listSessions()
                }

                is KernelApi.ApiResult.Err ->
                    _state.update { it.copy(workspaceSwitching = false, banner = Banner(r.message, true)) }
            }
        }
    }

    /** 重命名项目：PATCH /api/workspaces {workspace, new_name}（只改显示名）。 */
    fun renameProject(workspace: String, newName: String) {
        val a = api ?: run { bannerNoKernel(); return }
        val display = newName.trim().ifEmpty { displayWorkspaceName(workspace) }
        viewModelScope.launch {
            when (val r = a.patchWorkspace(workspace, newName = newName.trim())) {
                is KernelApi.ApiResult.Ok -> {
                    // 内核不推 sessions 帧，本地先改（P-022），再拉一次对齐
                    _state.update { st ->
                        st.applySessions(
                            st.sessions.map { if (it.workspace == workspace) it.copy(workspaceName = display) else it },
                        ).let {
                            if (it.currentWorkspace == workspace) it.copy(currentWorkspaceName = display) else it
                        }
                    }
                    transport?.listSessions()
                    toast("项目已重命名为：$display")
                }

                is KernelApi.ApiResult.Err ->
                    _state.update { it.copy(banner = Banner(r.message, true)) }
            }
        }
    }

    /** 归档 / 恢复整个项目：PATCH /api/workspaces {workspace, archive}。 */
    fun archiveProject(workspace: String, archived: Boolean) {
        val a = api ?: run { bannerNoKernel(); return }
        viewModelScope.launch {
            when (val r = a.patchWorkspace(workspace, archive = archived)) {
                is KernelApi.ApiResult.Ok -> {
                    if (archived) {
                        // 归档 = 整组会话从主列表消失（归档页可恢复）
                        _state.update { st ->
                            val ids = st.sessions.filter { it.workspace == workspace }.map { it.id }.toSet()
                            val curHit = st.currentSessionId in ids
                            val wasProject = st.currentWorkspace == workspace
                            st.copy(
                                messages = if (curHit) emptyList() else st.messages,
                                title = if (curHit) "CodeForge" else st.title,
                                currentSessionId = if (curHit) "" else st.currentSessionId,
                                currentWorkspace = if (wasProject) "" else st.currentWorkspace,
                                currentWorkspaceName = if (wasProject) "" else st.currentWorkspaceName,
                            ).applySessions(st.sessions.filterNot { it.id in ids })
                        }
                        loadArchived()
                    }
                    transport?.listSessions()
                    refreshWorkspaces()
                    toast(if (archived) "项目已归档" else "项目已恢复")
                }

                is KernelApi.ApiResult.Err ->
                    _state.update { it.copy(banner = Banner(r.message, true)) }
            }
        }
    }

    /** 删除整个项目及其全部会话：DELETE /api/workspaces?workspace=。 */
    fun deleteProject(workspace: String) {
        val a = api ?: run { bannerNoKernel(); return }
        viewModelScope.launch {
            when (val r = a.deleteWorkspace(workspace)) {
                is KernelApi.ApiResult.Ok -> {
                    _state.update { st ->
                        val ids = st.sessions.filter { it.workspace == workspace }.map { it.id }.toSet()
                        val curHit = st.currentSessionId in ids
                        val wasProject = st.currentWorkspace == workspace
                        st.copy(
                            messages = if (curHit) emptyList() else st.messages,
                            title = if (curHit) "CodeForge" else st.title,
                            currentSessionId = if (curHit) "" else st.currentSessionId,
                            currentWorkspace = if (wasProject) "" else st.currentWorkspace,
                            currentWorkspaceName = if (wasProject) "" else st.currentWorkspaceName,
                        ).applySessions(st.sessions.filterNot { it.id in ids })
                    }
                    transport?.listSessions()
                    refreshWorkspaces()
                    toast("项目已删除")
                }

                is KernelApi.ApiResult.Err ->
                    _state.update { it.copy(banner = Banner(r.message, true)) }
            }
        }
    }

    // ---------- 文件树（GET /api/tree，depth=1 懒加载）----------

    /**
     * 读取一层目录。[path] 空 = 工作区根，否则传内核返回的绝对路径（或相对路径）。
     * 读取失败写 [ChatUiState.treeError]，与「空目录」严格区分。
     */
    fun loadTree(path: String = "") {
        val a = api ?: run {
            _state.update { it.copy(treeError = "内核未连接，无法读取文件树") }
            return
        }
        viewModelScope.launch {
            when (val r = a.listTree(path, depth = 1)) {
                is KernelApi.ApiResult.Ok -> _state.update {
                    it.copy(
                        treeEntries = r.value.items,
                        treePath = r.value.path,
                        treeParent = r.value.parent,
                        treeError = r.value.error,
                    )
                }

                is KernelApi.ApiResult.Err -> _state.update {
                    it.copy(treeEntries = emptyList(), treeError = r.message)
                }
            }
        }
    }

    // ---------- @plan 操作面板 ----------

    fun dismissActionPanel() = _state.update { it.copy(actionPanel = null) }

    /** 面板里的「直接发送」：关闭面板并把这句当作普通用户消息发出去。 */
    fun sendActionPanel(text: String) {
        _state.update { it.copy(actionPanel = null) }
        send(text)
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
                    // 供应商行必须与「当前生效模型」同一口径（问题 8）：
                    // apply 成功后重拉 config，让 kernelConfig.provider 也跟上。
                    refreshKernelSummaries()
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
        val t = transport ?: return
        val seed = buildString {
            appendLine("（以下为此前对话的分支上下文，请在此基础上继续）")
            msgs.forEach { m ->
                val who = if (m.role == app.codeforge.model.MsgRole.User) "用户" else "助手"
                appendLine("$who：${m.text}")
            }
        }.takeLast(8_000)
        val text = "【分支延续】 " + seed
        // 分支必须等新会话确立再发：直接 newSession()+send() 会让上行帧带旧会话 id
        viewModelScope.launch {
            if (newSessionGate == null) requestNewSession(t)
            enqueueSend(PendingSend(text, emptyList(), text))
        }
    }

    // ---------- 每会话草稿（B4 输入区）：内存态，进程死掉即失（聊天草稿不值得落盘） ----------

    private val drafts = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun draftOf(sessionId: String): String = drafts[sessionId] ?: ""

    fun saveDraft(sessionId: String, text: String) {
        if (sessionId.isEmpty()) return
        if (text.isEmpty()) drafts.remove(sessionId) else drafts[sessionId] = text
    }

    // ---------------- 内核服务操作（设置页 / 会话管理调用） ----------------

    /**
     * 会话列表变化后的统一收尾：重算 [ChatUiState.projects] 与当前项目。
     *
     * 所有改 sessions 的地方都走这里 —— 侧栏的项目分组、项目行的转圈、
     * 当前项目名全是 sessions 的派生物，散着写必然有一处漏同步。
     */
    private fun ChatUiState.applySessions(sessions: List<SessionItem>): ChatUiState {
        val cur = sessions.firstOrNull { it.id == currentSessionId }
        return copy(
            sessions = sessions,
            projects = aggregateProjects(sessions),
            // 当前会话不在列表里（刚建好还没推帧 / 已被删）时保持原值
            currentWorkspace = cur?.workspace ?: currentWorkspace,
            currentWorkspaceName = cur?.workspaceName ?: currentWorkspaceName,
        )
    }

    /** 按 workspace 分组聚合项目：顺序沿用会话列表（内核按 updated_at 倒序）。 */
    private fun aggregateProjects(sessions: List<SessionItem>): List<ProjectItem> =
        sessions.groupBy { it.workspace }.map { (ws, list) ->
            ProjectItem(
                workspace = ws,
                name = list.firstNotNullOfOrNull { it.workspaceName.takeIf { n -> n.isNotBlank() } }
                    ?: displayWorkspaceName(ws),
                sessionCount = list.size,
                busy = list.any { it.busy },
                archived = list.all { it.archived },
            )
        }

    /**
     * 会话列表帧不带 busy（内核 SessionMeta 只有 id/workspace/title/时间/归档），
     * 运行态只由 busy/idle 帧驱动。列表帧到达时把本地已知的在跑标记并回去，
     * 否则每拉一次列表就把项目行的转圈抹掉（从后台回来时尤其明显）。
     */
    private fun mergeBusy(incoming: List<SessionItem>, previous: List<SessionItem>): List<SessionItem> {
        val prevBusy = previous.filter { it.busy }.map { it.id }.toSet()
        if (prevBusy.isEmpty()) return incoming
        return incoming.map { if (!it.busy && it.id in prevBusy) it.copy(busy = true) else it }
    }

    /** 项目显示名兜底：路径末段；未选择工作区给固定文案。 */
    private fun displayWorkspaceName(workspace: String): String =
        if (workspace.isBlank()) {
            "未选择工作区"
        } else {
            workspace.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
                .ifBlank { workspace }
        }

    /**
     * 拉一次项目名表（GET /api/workspaces），只用来给「workspace_name 为空」的会话补显示名。
     * 内核在会话帧里就给了 workspace_name，这里覆盖的是新建项目/刚重命名还没推帧的窗口。
     */
    private fun refreshWorkspaces() {
        val a = api ?: return
        viewModelScope.launch {
            when (val r = a.listWorkspaces()) {
                is KernelApi.ApiResult.Ok -> {
                    val names = r.value.filter { it.name.isNotBlank() }
                        .associate { it.workspace to it.name }
                    if (names.isEmpty()) return@launch
                    _state.update { st ->
                        st.applySessions(
                            st.sessions.map { s ->
                                val n = s.workspaceName.ifBlank { names[s.workspace].orEmpty() }
                                if (n.isNotBlank() && n != s.workspaceName) s.copy(workspaceName = n) else s
                            },
                        )
                    }
                }

                is KernelApi.ApiResult.Err -> Unit
            }
        }
    }

    private fun refreshKernelSummaries() {
        val a = api ?: return
        loadKernelExtras()
        // 后台任务列表也在这里拉一次：内核刚就绪/重启后，UI 需要知道
        // 有没有脚本还在跑（任务归进程，重启会全部丢失）
        refreshTasks()
        // 供应商详情也在启动时拉一次：首次安装要在这里触发 DeepSeek 预置，
        // 不能等用户点开设置页才发生（模型弹层的供应商行一直显示「未选择」）。
        refreshProviderDetails()
        viewModelScope.launch {
            when (val r = a.getConfig()) {
                is KernelApi.ApiResult.Ok -> _state.update { st ->
                    val cfg = r.value
                    // 内核把上次工作区持久化在 state.yaml：冷启动时界面还没有
                    // 选中会话，currentWorkspace 是空串 —— 用它对齐「当前项目」，
                    // 附件预览根与项目名才不会落到 inbox（问题 8 的同一口径问题）。
                    st.copy(
                        kernelConfig = cfg,
                        currentWorkspace = st.currentWorkspace.ifBlank { cfg.workDir },
                        currentWorkspaceName = st.currentWorkspaceName.ifBlank {
                            if (cfg.workDir.isBlank()) "" else displayWorkspaceName(cfg.workDir)
                        },
                    )
                }

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
                is KernelApi.ApiResult.Ok -> _state.update {
                    it.copy(
                        builtinPluginInfos = r.value,
                        // 旧 Triple 形态给 Composer 的 @ 补全（第一项是 id，即 @token）；
                        // 第二项现在填内核下发的显示名，未配置时内核已回退成 id。
                        builtinPlugins = r.value.map { p -> Triple(p.id, p.name, p.enabled) },
                    )
                }
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
                is KernelApi.ApiResult.Ok -> _state.update {
                    it.copy(
                        skillInfos = r.value,
                        // Pair 的第一项必须是 slug（@ 补全 token），第二项是描述
                        skills = r.value.map { s -> s.slug to s.description },
                    )
                }
                is KernelApi.ApiResult.Err -> Unit
            }
        }
        viewModelScope.launch {
            when (val r = a.listMemories()) {
                is KernelApi.ApiResult.Ok -> _state.update { it.copy(memories = r.value) }
                is KernelApi.ApiResult.Err -> Unit
            }
        }
        viewModelScope.launch {
            when (val r = a.listModels()) {
                is KernelApi.ApiResult.Ok -> {
                    _state.update {
                        it.copy(libraryModels = r.value.map { m ->
                            // 逐字段搬过来：设置页的表单要用它们回填（上下文/RPM/
                            // 三态能力/密钥来源），只带 id/name/provider 会全部落空
                            LibraryModel(
                                id = m.id,
                                name = m.name,
                                provider = m.provider,
                                providerName = m.providerName,
                                ctxIn = m.ctxIn,
                                ctxOut = m.ctxOut,
                                vision = m.vision,
                                video = m.video,
                                rpm = m.rpm,
                                keySource = m.keySource,
                                keyName = m.keyName,
                                keySet = m.keySet,
                                baseUrl = m.baseUrl,
                                protocol = m.protocol,
                            )
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

    // ---------------------------------------------------------------- 后台脚本任务

    /**
     * 拉一次后台任务列表。
     *
     * 任务输出只保留尾部，但单个任务最多 128KiB；面板打开时按 1s 轮询即可，
     * 不做逐行 WS 推送 —— 构建日志会把连接挤爆，而 UI 只需要「最近发生了什么」。
     */
    fun refreshTasks() {
        val a = api ?: return
        viewModelScope.launch {
            when (val r = a.listTasks()) {
                is KernelApi.ApiResult.Ok -> _state.update { it.copy(tasks = r.value) }
                // 失败静默：任务面板的轮询不该反复弹横幅，下一次拖到就是
                is KernelApi.ApiResult.Err -> Unit
            }
        }
    }

    /** 手动启动一个后台脚本（命令在内核当前工作区执行）。 */
    fun startTask(command: String, label: String = "") {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return
        val a = api ?: run { bannerNoKernel(); return }
        viewModelScope.launch {
            when (val r = a.startTask(trimmed, label.trim())) {
                is KernelApi.ApiResult.Ok -> {
                    toast("后台任务已启动")
                    refreshTasks()
                }

                is KernelApi.ApiResult.Err ->
                    _state.update { it.copy(banner = Banner(r.message, isError = true)) }
            }
        }
    }

    /** 取消一个运行中的后台任务（内核杀整个进程组）。 */
    fun killTask(id: String) {
        val a = api ?: run { bannerNoKernel(); return }
        viewModelScope.launch {
            when (val r = a.killTask(id)) {
                is KernelApi.ApiResult.Ok -> {
                    toast("已取消任务")
                    refreshTasks()
                }

                is KernelApi.ApiResult.Err ->
                    _state.update { it.copy(banner = Banner(r.message, isError = true)) }
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
    fun discoverModels(provider: String, onResult: (models: List<String>, error: String?) -> Unit) {
        val a = api ?: run {
            bannerNoKernel()
            onResult(emptyList(), "内核未连接")
            return
        }
        viewModelScope.launch {
            when (val r = a.discoverModels(provider)) {
                is KernelApi.ApiResult.Ok -> onResult(
                    r.value,
                    if (r.value.isEmpty()) "未探测到模型：检查供应商密钥/地址或上游是否支持 /models" else null,
                )

                is KernelApi.ApiResult.Err -> {
                    _state.update { it.copy(banner = Banner(r.message, true)) }
                    // 探测入口在设置弹层里，主界面的 banner 被弹层盖住看不到；
                    // 必须把错误回传给调用点就地显示。
                    onResult(emptyList(), r.message)
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

    /**
     * 保存一条模型的**完整规格**（/api/models/save）。
     *
     * 密钥三件套 [keySource] / [keyName] / [keyValue] 现在会真正下发：
     * - `keySource = "plain"` 配 [keyValue] 明文；编辑时留空 = 不动库里的旧密钥；
     * - `keySource = "env"` 配 [keyName] 环境变量名；
     * - 三者全空时 `inherit_key` 取 [inheritKey]（true = 清掉条目上的覆盖，改用供应商的）。
     * 给了任一密钥字段时内核必须收到 `inherit_key=false`，否则会清掉手输密钥；
     * 这一步在 [KernelApi.saveModelFull] 内按字段自动判定，调用方不必再算。
     *
     * [vision]/[video] 传 null = 未声明，与「明确不支持」是两回事。
     */
    fun saveModelFull(
        id: String,
        name: String,
        providerId: String,
        ctxIn: Int = 0,
        ctxOut: Int = 0,
        vision: Boolean? = null,
        video: Boolean? = null,
        rpm: Int = 0,
        baseUrl: String = "",
        protocol: String = "",
        keySource: String = "",
        keyName: String = "",
        keyValue: String = "",
        inheritKey: Boolean = true,
    ) {
        val a = api ?: run { bannerNoKernel(); return }
        if (id.isBlank()) {
            toast("模型 id 不能为空", isError = true)
            return
        }
        viewModelScope.launch {
            when (val r = a.saveModelFull(
                id = id,
                name = name.ifBlank { id },
                providerId = providerId,
                ctxIn = ctxIn,
                ctxOut = ctxOut,
                vision = vision,
                video = video,
                rpm = rpm,
                baseUrl = baseUrl,
                protocol = protocol,
                keySource = keySource,
                keyName = keyName,
                keyValue = keyValue,
                inheritKey = inheritKey,
            )) {
                // 模型表跟着刷新一次：新增/改写后弹层里要立刻看到
                is KernelApi.ApiResult.Ok -> {
                    refreshKernelSummaries()
                    toast("模型已保存")
                }

                is KernelApi.ApiResult.Err ->
                    _state.update { it.copy(banner = Banner(r.message, true)) }
            }
        }
    }

    /**
     * 测试一条模型的连接。结果走回调而不是塞进 state —— 测试是一次性动作，
     * 且入口在设置子页里，主界面横幅会被弹层盖住。
     * [onResult] 第一个参数是成功文案（含延迟），第二个是失败原因。
     */
    fun testModel(id: String, onResult: (String?, String?) -> Unit) {
        val a = api ?: run { onResult(null, "内核未连接"); return }
        viewModelScope.launch {
            when (val r = a.testModel(id)) {
                is KernelApi.ApiResult.Ok -> onResult(r.value, null)
                is KernelApi.ApiResult.Err -> onResult(null, r.message)
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
        env: List<Pair<String, String>> = emptyList(),
    ) {
        val a = api ?: run { bannerNoKernel(); return }
        viewModelScope.launch {
            when (val r = a.addMcpPlugin(name, displayName, type, command, args, endpoint, description, env)) {
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

    /** 新增一条用户记忆，成功后重拉列表（内核按工作区隔离）。 */
    fun addMemory(content: String) {
        val text = content.trim()
        if (text.isEmpty()) return
        val a = api ?: run { bannerNoKernel(); return }
        viewModelScope.launch {
            when (val r = a.addMemory(text)) {
                is KernelApi.ApiResult.Ok -> loadKernelExtras()
                is KernelApi.ApiResult.Err ->
                    _state.update { it.copy(banner = Banner(r.message, true)) }
            }
        }
    }

    fun deleteMemory(id: String) {
        val a = api ?: run { bannerNoKernel(); return }
        viewModelScope.launch {
            when (val r = a.deleteMemory(id)) {
                is KernelApi.ApiResult.Ok -> loadKernelExtras()
                is KernelApi.ApiResult.Err ->
                    _state.update { it.copy(banner = Banner(r.message, true)) }
            }
        }
    }

    /** 就地编辑一条记忆（PATCH /api/memory）：成功后重拉列表把真值带回来。 */
    fun updateMemory(id: String, content: String) {
        val text = content.trim()
        if (text.isEmpty()) return
        val a = api ?: run { bannerNoKernel(); return }
        viewModelScope.launch {
            when (val r = a.updateMemory(id, text)) {
                is KernelApi.ApiResult.Ok -> {
                    loadKernelExtras()
                    toast("记忆已保存")
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
                        st.applySessions(
                            st.sessions.map { if (it.id == id) it.copy(title = title) else it },
                        ).let {
                            if (it.currentSessionId == id && title.isNotBlank()) it.copy(title = title) else it
                        }
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
                                messages = if (isCurrent) emptyList() else st.messages,
                                title = if (isCurrent) "CodeForge" else st.title,
                                currentSessionId = if (isCurrent) "" else st.currentSessionId,
                            ).applySessions(st.sessions.filterNot { it.id == id })
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
                messages = if (removingCurrent) emptyList() else st.messages,
                title = if (removingCurrent) "CodeForge" else st.title,
                currentSessionId = if (removingCurrent) "" else st.currentSessionId,
            ).applySessions(st.sessions.filterNot { it.id in ids })
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
                            messages = if (isCurrent) emptyList() else st.messages,
                            title = if (isCurrent) "CodeForge" else st.title,
                            currentSessionId = if (isCurrent) "" else st.currentSessionId,
                        ).applySessions(st.sessions.filterNot { it.id == id })
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
                    // 删除也是「已预置过」的明确证据：以后列表再空也不自动补
                    AppSettings.markProviderSeeded()
                    _state.update { st ->
                        st.copy(
                            providerDetails = st.providerDetails.filterNot { it.id == id },
                            banner = Banner("已删除供应商", isError = false),
                        )
                    }
                    refreshProviderDetails()
                    // 删除供应商时内核会把其下模型「解绑并内联连接信息」，
                    // 模型库的 provider_id/provider_name 也会变；只刷供应商列表
                    // 会留下旧分组，看起来像「供应商删了还在」。
                    loadKernelExtras()
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
        if (providers.isNotEmpty() || _state.value.libraryModels.isNotEmpty()) {
            // 已经有数据 = 这台设备不是首次安装；标记后用户以后再删空也不会被重新预置
            AppSettings.markProviderSeeded()
            return
        }
        // 持久标记优先：用户主动删掉预置供应商后重启 App 不能又被写回来
        if (AppSettings.providerSeeded.value) return
        if (seededDeepSeek) return
        // 先取 api 再置位：内核没就绪时置位会让预置永远不再重试
        val a = api ?: return
        seededDeepSeek = true
        viewModelScope.launch {
            when (val sr = a.saveProvider(
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
            )) {
                is KernelApi.ApiResult.Ok -> AppSettings.markProviderSeeded()
                is KernelApi.ApiResult.Err -> {
                    seededDeepSeek = false
                    _state.update {
                        it.copy(banner = Banner("预置供应商失败：${sr.message}", isError = true))
                    }
                    return@launch
                }
            }
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
                is KernelApi.ApiResult.Ok -> {
                    _state.update { it.copy(modelName = "deepseek-flash") }
                    // 同上：预置完也要让供应商/模型/配置三者口径一致（问题 8）
                    refreshKernelSummaries()
                }
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
