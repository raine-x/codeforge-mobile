package com.hxy.codeforge.ui

import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.hxy.codeforge.core.CoreTransport
import com.hxy.codeforge.core.EnvKit
import com.hxy.codeforge.core.KernelApi
import com.hxy.codeforge.core.ModelPresets
import com.hxy.codeforge.core.NOTICE_COMPRESS_DONE
import com.hxy.codeforge.core.NOTICE_COMPRESS_START
import com.hxy.codeforge.core.PluginInstaller
import com.hxy.codeforge.core.PluginSkills
import com.hxy.codeforge.model.BuiltinPluginInfo
import com.hxy.codeforge.model.ChatMessage
import com.hxy.codeforge.model.InstallVersionRelation
import com.hxy.codeforge.model.KernelConfigSummary
import com.hxy.codeforge.model.LibraryModel
import com.hxy.codeforge.model.MsgRole
import com.hxy.codeforge.model.NoticeKind
import com.hxy.codeforge.model.PendingPluginInstall
import com.hxy.codeforge.model.PermissionMode
import com.hxy.codeforge.model.ProjectItem
import com.hxy.codeforge.model.SessionItem
import com.hxy.codeforge.model.SubagentPolicy
import com.hxy.codeforge.ui.browser.ElementInsert
import com.hxy.codeforge.ui.browser.PickedElement
import com.hxy.codeforge.ui.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

fun ChatViewModel.switchSession(id: String) {
    if (id == _state.value.currentSessionId) return
    transport?.loadSession(id)
}
/** 撤销当前会话最近一次文件写入（内核按会话分桶；撤销结果只回传路径与剩余步数）。 */
fun ChatViewModel.undoLastWrite(sessionId: String = _state.value.currentSessionId) {
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
fun ChatViewModel.retryUnfinished() {
    val back = _state.value.resumeBack
    if (back < 0) {
        toast("没有未完成的轮次")
        return
    }
    if (_state.value.running) return
    _state.update { it.copy(running = true, banner = null) }
    transport?.retry(back, rollbackFiles = true)
}

fun ChatViewModel.createSession() {
    createSessionInternal()
}

/**
 * 新建会话。统一先登记闸门再发送 new_session，确保排队消息等待会话落定。
 * 指定项目新建需先切工作区，见 [newSessionInProject]。
 */
private fun ChatViewModel.createSessionInternal() {
    val st = _state.value
    // 复用当前空会话，避免连点生成多条空白「新对话」
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
 * 在指定项目下新建会话：先挂起完成工作区切换，再发 new_session，确保会话归属正确。
 */
fun ChatViewModel.newSessionInProject(workspace: String) {
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
        installBuiltinSkills(workspace)
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

// 项目（工作区）

/** 目录路径规范化（符号链接如 /sdcard ↔ /storage/emulated/0 对齐后比较）。 */
internal fun canonicalPathOrRaw(path: String): String {
    val trimmed = path.trim()
    if (trimmed.isEmpty()) return ""
    val f = java.io.File(trimmed)
    val canon = runCatching { f.canonicalPath }.getOrNull()
    val raw = (canon ?: trimmed)
    return if (raw.length > 1) raw.trimEnd('/', '\\') else raw
}

internal fun sameWorkspacePath(a: String, b: String): Boolean {
    val ca = canonicalPathOrRaw(a)
    val cb = canonicalPathOrRaw(b)
    return ca.isNotEmpty() && ca == cb
}

/** 是否为「不选文件夹」自动创建的 `workspace/p-xxx` 独立项目目录。 */
internal fun isAutoGeneratedProjectDir(workspacePath: String, workspaceRootPath: String): Boolean {
    val ws = canonicalPathOrRaw(workspacePath)
    val root = canonicalPathOrRaw(workspaceRootPath)
    if (ws.isEmpty() || root.isEmpty()) return false
    val f = java.io.File(ws)
    val parent = f.parentFile?.path ?: return false
    return canonicalPathOrRaw(parent) == root && f.name.startsWith("p-")
}

/**
 * 新建项目：POST /api/workspace → POST /api/perm → WS new_session，
 * 成功后刷新项目名表与会话列表。
 *
 * [workspacePath] 为空时才在 `$CELL/workspace/p-*` 下创建独立空工作区（且若当前已处于
 * 一个空白的自动项目里，直接复用，避免连点生成一串空 `p-*` 目录）；
 * 若显式传入文件夹路径而侧栏尚未收录，则创建并切进该项目；若该文件夹已是已有项目
 * （含已归档），直接恢复/切换过去，绝不再拦截成「已有空的新项目」。
 */
fun ChatViewModel.createProject(name: String, workspacePath: String, permission: PermissionMode) {
    val a = api ?: run { bannerNoKernel(); return }
    val t = transport ?: run {
        _state.update { it.copy(banner = Banner("内核未连接，暂时无法新建项目", true)) }
        return
    }
    if (_state.value.workspaceSwitching) return
    val rawPath = workspacePath.trim()
    val explicitName = name.trim()
    val st = _state.value
    val wsRoot = EnvKit.workspaceRoot(getApplication()).absolutePath

    // 只有用户选「不选择目录（自动建独立项目）」且当前已经在空白自动项目时才复用，
    // 防止用户选一个明确文件夹想打开时被误判阻断。
    if (rawPath.isBlank() &&
        st.messages.isEmpty() &&
        !st.running &&
        isAutoGeneratedProjectDir(st.currentWorkspace, wsRoot) &&
        st.sessions.any { sameWorkspacePath(it.workspace, st.currentWorkspace) && !it.archived }
    ) {
        toast("已有空的新项目：${st.currentWorkspaceName.ifBlank { "新项目" }}", isError = false)
        return
    }

    val path = if (rawPath.isNotBlank()) {
        val dir = java.io.File(rawPath)
        if (!dir.exists()) runCatching { dir.mkdirs() }
        canonicalPathOrRaw(dir.absolutePath)
    } else {
        val id = EnvKit.newProjectId()
        canonicalPathOrRaw(EnvKit.newProjectWorkspace(getApplication(), id).absolutePath)
    }
    // 显示名：优先用户输入；选了目录用目录名，自动独立工作区用可读名称（避免暴露内部项目 id）
    val display = explicitName.ifEmpty {
        if (rawPath.isNotBlank()) displayWorkspaceName(path) else "新项目"
    }
    val existing = st.projects.firstOrNull { sameWorkspacePath(it.workspace, path) }
    if (existing != null) {
        viewModelScope.launch {
            if (existing.archived) {
                a.patchWorkspace(existing.workspace, archive = false)
                refreshWorkspaces()
            }
            toast("已切换到项目：${existing.name}", isError = false)
            switchProject(existing.workspace)
        }
        return
    }
    // 新建项目也是切工作区：别的项目在跑时切过去会改掉那一轮的文件根
    if (!sameWorkspacePath(path, _state.value.currentWorkspace) && _state.value.sessions.any { it.busy }) {
        toast("有会话正在运行，暂不能新建项目", isError = true)
        return
    }
    viewModelScope.launch {
        _state.update { it.copy(workspaceSwitching = true) }
        installBuiltinSkills(path)
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
 * 在侧栏打开任意目录作为项目：已有对应项目时直接恢复/切过去，否则以目录名新建项目并切入。
 */
fun ChatViewModel.openDirectoryAsProject(dir: java.io.File) {
    if (!dir.isDirectory) {
        toast("目录不存在：${dir.name}", isError = true)
        return
    }
    createProject(
        name = dir.name,
        workspacePath = dir.absolutePath,
        permission = _state.value.permissionMode,
    )
}

/**
 * 切换当前项目（[workspace] 空串自动映射到应用私有 inbox，不可直接传空清空内核工作区）。
 * 保留既有会话列表的 busy 运行态，仅切换视图与活跃工作区。
 */
fun ChatViewModel.switchProject(workspace: String) {
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
        installBuiltinSkills(target)
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
fun ChatViewModel.renameProject(workspace: String, newName: String) {
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
fun ChatViewModel.archiveProject(workspace: String, archived: Boolean) {
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
fun ChatViewModel.deleteProject(workspace: String) {
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
/** 拉取已归档列表（侧栏展开「已归档」时调用；幂等）。 */
fun ChatViewModel.loadArchived() {
    val a = api ?: return
    viewModelScope.launch {
        when (val r = a.listArchivedSessions()) {
            is KernelApi.ApiResult.Ok -> _state.update { it.copy(archivedSessions = r.value) }
            is KernelApi.ApiResult.Err -> _state.update { it.copy(archivedSessions = emptyList()) }
        }
    }
}

/** 从归档恢复：patch archived=false + 刷新归档列表。 */
fun ChatViewModel.unarchive(id: String) {
    val a = api ?: run { bannerNoKernel(); return }
    viewModelScope.launch {
        a.patchSession(id, archived = false)
        loadArchived()
    }
}
/** 拉一次检查点（打开回滚菜单时调；内核会回 checkpoints 帧）。 */
fun ChatViewModel.pullCheckpoints() {
    transport?.pullCheckpoints()
}

/** 回滚工作区文件到第 step 步之前（不动对话历史）。 */
fun ChatViewModel.rewindTo(step: Int) {
    transport?.rewindFiles(step)
}

/** 继续上一轮（断点续跑）。 */
fun ChatViewModel.continueTurn() {
    if (_state.value.running) return
    transport?.continueTurn()
}

/**
 * 创建对话分支：内核无 branch 端点，壳侧等价实现 —— 新会话 + 把既有
 * 对话播种为上下文（截尾 8K 字防撑爆首条消息），模型在同一语境继续。
 */
fun ChatViewModel.branchSession() {
    val msgs = _state.value.messages.filter { it.role != com.hxy.codeforge.model.MsgRole.Notice }
    if (msgs.isEmpty()) return
    val t = transport ?: return
    val seed = buildString {
        appendLine("（以下为此前对话的分支上下文，请在此基础上继续）")
        msgs.forEach { m ->
            val who = if (m.role == com.hxy.codeforge.model.MsgRole.User) "用户" else "助手"
            appendLine("$who：${m.text}")
        }
    }.takeLast(8_000)
    val text = "【分支延续】 " + seed
    // 分支必须等新会话确立再发：直接 newSession()+send() 会让上行帧带旧会话 id
    viewModelScope.launch {
        if (newSessionGate == null) requestNewSession(t)
        enqueueSend(ChatViewModel.PendingSend(text, emptyList(), text))
    }
}
/** 会话列表变化后的统一收尾：同步派生项目分组及当前工作区状态。 */
internal fun ChatUiState.applySessions(sessions: List<SessionItem>): ChatUiState {
    val cur = sessions.firstOrNull { it.id == currentSessionId }
    return copy(
        sessions = sessions,
        projects = aggregateProjects(sessions),
        // 后台 AI 静默生成会话小标题后会重推 sessions 帧：同步更新顶栏标题；
        // 当前会话不在列表里（刚建好还没推帧 / 已被删）时保持原值。
        title = cur?.title?.takeIf { it.isNotBlank() } ?: title,
        currentWorkspace = cur?.workspace ?: currentWorkspace,
        currentWorkspaceName = cur?.workspaceName ?: currentWorkspaceName,
    )
}

/** 按 workspace 分组聚合项目：顺序沿用会话列表（内核按 updated_at 倒序）。 */
internal fun aggregateProjects(sessions: List<SessionItem>): List<ProjectItem> =
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
internal fun mergeBusy(incoming: List<SessionItem>, previous: List<SessionItem>): List<SessionItem> {
    val prevBusy = previous.filter { it.busy }.map { it.id }.toSet()
    if (prevBusy.isEmpty()) return incoming
    return incoming.map { if (!it.busy && it.id in prevBusy) it.copy(busy = true) else it }
}

/** 项目显示名兜底：路径末段；未选择工作区给固定文案。 */
internal fun displayWorkspaceName(workspace: String): String =
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
internal fun ChatViewModel.refreshWorkspaces() {
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
fun ChatViewModel.renameSession(id: String, title: String) {
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

fun ChatViewModel.setSessionArchived(id: String, archived: Boolean) {
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
fun ChatViewModel.deleteSessions(ids: List<String>) {
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

fun ChatViewModel.deleteSession(id: String) {
    val a = api ?: run { bannerNoKernel(); return }
    viewModelScope.launch {
        when (val r = a.deleteSession(id)) {
            is KernelApi.ApiResult.Ok -> {
                _state.update { st ->
                    val isCurrent = st.currentSessionId == id
                    st.copy(
                        // 本地立即移除（删除/归档后内核不主动推送 sessions，见 P-022）
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
