package com.hxy.codeforge.ui

import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.hxy.codeforge.core.BuiltinSkills
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

/** 添加一个 MCP 插件（stdio 或远程 HTTP），内核会增量热加载。 */
fun ChatViewModel.addMcpPlugin(
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
                refreshConfigSummary()
                toast("已添加 MCP：$name")
            }

            is KernelApi.ApiResult.Err ->
                _state.update { it.copy(banner = Banner(r.message, true)) }
        }
    }
}

/** 删除一个 MCP 插件。 */
fun ChatViewModel.deleteMcpPlugin(name: String) {
    val a = api ?: run { bannerNoKernel(); return }
    viewModelScope.launch {
        when (val r = a.deleteMcpPlugin(name)) {
            is KernelApi.ApiResult.Ok -> {
                loadKernelExtras()
                refreshConfigSummary()
                toast("已删除 MCP：$name")
            }

            is KernelApi.ApiResult.Err ->
                _state.update { it.copy(banner = Banner(r.message, true)) }
        }
    }
}

// ---------------------------------------------------------------- 动态插件管理（.cfplugin）

/** 解析当前活跃工作区路径（与 applySessionSpaceNow 优先级一致）。 */
private fun ChatViewModel.resolveActiveWorkspace(): String {
    val st = _state.value
    val sessionWs = st.sessions.firstOrNull { it.id == st.currentSessionId }
        ?.workspace?.takeUnless { it.isBlank() }
    return sessionWs
        ?: st.currentWorkspace.takeUnless { it.isBlank() }
        ?: st.kernelConfig?.workDir?.takeUnless { it.isBlank() }
        ?: EnvKit.inboxSpace(getApplication()).absolutePath
}

/** 刷新已安装插件列表（含当前工作区的技能同步状态）。 */
fun ChatViewModel.refreshInstalledPlugins(workspaceOverride: String? = null) {
    val app = getApplication<android.app.Application>()
    val ws = workspaceOverride?.takeUnless { it.isBlank() } ?: resolveActiveWorkspace()
    viewModelScope.launch(Dispatchers.IO) {
        val list = PluginInstaller.listInstalled(app.filesDir, ws)
        _state.update { it.copy(installedPlugins = list) }
    }
}

/** 单独刷新一次内核 config（用于插件变更后追平 `kernelConfig.tools` 工具清单）。 */
internal fun ChatViewModel.refreshConfigSummary() {
    val a = api ?: return
    viewModelScope.launch {
        when (val r = a.getConfig()) {
            is KernelApi.ApiResult.Ok -> _state.update { it.copy(kernelConfig = r.value) }
            is KernelApi.ApiResult.Err -> Unit
        }
    }
}

/**
 * 把插件技能同步到当前工作区与已知本地工作区，然后通知内核重读技能列表。
 */
private suspend fun ChatViewModel.syncPluginSkillsEverywhere() {
    val app = getApplication<android.app.Application>()
    val activeWs = resolveActiveWorkspace()
    val targets = buildSet {
        if (activeWs.isNotBlank()) add(activeWs)
        add(EnvKit.inboxSpace(app).absolutePath)
        _state.value.projects.forEach { p ->
            if (p.workspace.isNotBlank()) add(p.workspace)
        }
    }
    withContext(Dispatchers.IO) {
        targets.forEach { ws -> PluginSkills.sync(app.filesDir, ws) }
    }
    refreshInstalledPlugins(activeWs)
}

/**
 * 从 SAF 选入一个 `.cfplugin` 包：暂存 + 解析校验 + 生成安装前预览 [PendingPluginInstall]。
 *
 * 校验通过后弹预览确认卡（展示版本对比、权限声明、随包技能、可预填密钥，或对阶段 2 类型置灰）；
 * 校验失败立即删暂存并提示明确原因。
 */
fun ChatViewModel.installPlugin(uri: Uri) {
    if (_state.value.pluginBusy) return
    val app = getApplication<android.app.Application>()
    _state.update { it.copy(pluginBusy = true) }
    viewModelScope.launch(Dispatchers.IO) {
        // 先清理旧暂存（若上次打开了预览还没确认又选了新包）
        _state.value.pendingPluginInstall?.stagedPath?.let { runCatching { java.io.File(it).delete() } }

        val fileName = resolveUriDisplayName(app, uri)
        val staged = when (val sr = PluginInstaller.stageUriToIncoming(app, uri, fileName)) {
            is PluginInstaller.InstallResult.Ok -> sr.value
            is PluginInstaller.InstallResult.Err -> {
                _state.update { it.copy(pluginBusy = false, banner = Banner(sr.message, isError = true)) }
                return@launch
            }
        }

        val st = _state.value
        val installed = PluginInstaller.listInstalled(app.filesDir, resolveActiveWorkspace())
        val installedIds = installed.map { it.id }.toSet()
        val manualIds = st.mcpPlugins.map { it.id }.filter { it !in installedIds }.toSet()
        val builtinSlugs = BuiltinSkills.builtinSlugs(app)
        val kernelTools = st.kernelConfig?.tools.orEmpty()

        when (val ir = PluginInstaller.inspectStagedPackage(
            stagedFile = staged,
            sourceFileName = fileName,
            installedPlugins = installed,
            kernelTools = kernelTools,
            builtinSkillSlugs = builtinSlugs,
            manualMcpIds = manualIds,
        )) {
            is PluginInstaller.InstallResult.Ok -> {
                _state.update {
                    it.copy(
                        installedPlugins = installed,
                        pendingPluginInstall = ir.value,
                        pluginBusy = false,
                    )
                }
            }
            is PluginInstaller.InstallResult.Err -> {
                staged.delete()
                _state.update {
                    it.copy(
                        pendingPluginInstall = null,
                        pluginBusy = false,
                        banner = Banner(ir.message, isError = true),
                    )
                }
            }
        }
    }
}

/** 取消待确认的插件安装预览并清理暂存包。 */
fun ChatViewModel.cancelPendingPluginInstall() {
    val pending = _state.value.pendingPluginInstall
    _state.update { it.copy(pendingPluginInstall = null) }
    if (pending != null) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { java.io.File(pending.stagedPath).delete() }
        }
    }
}

/**
 * 确认安装 [ChatUiState.pendingPluginInstall]：
 * 1. 落盘到 `filesDir/plugins/<id>/<version>/` + `install.json`；
 * 2. 可执行插件向内核注册（升级/重装先 `DELETE` 再 `POST`）；若内核拒绝则回滚磁盘目录；
 * 3. 同步随包技能到工作区并刷新工具与技能列表。
 */
fun ChatViewModel.confirmPluginInstall(
    allowDowngrade: Boolean = false,
    secrets: Map<String, String> = emptyMap(),
    onDone: (ok: Boolean, error: String?) -> Unit = { _, _ -> },
) {
    val pending = _state.value.pendingPluginInstall ?: return
    if (_state.value.pluginBusy) return
    val manifest = pending.manifest
    val a = api
    if (!manifest.isPureSkill && a == null) {
        val msg = "内核未连接，暂时无法安装可执行插件"
        toast(msg, isError = true)
        onDone(false, msg)
        return
    }

    val app = getApplication<android.app.Application>()
    _state.update { it.copy(pluginBusy = true) }
    viewModelScope.launch {
        val commitRes = withContext(Dispatchers.IO) {
            PluginInstaller.commitStagedInstall(
                filesDir = app.filesDir,
                pending = pending,
                allowDowngrade = allowDowngrade,
                secrets = secrets,
            )
        }
        val installed = when (commitRes) {
            is PluginInstaller.InstallResult.Ok -> commitRes.value
            is PluginInstaller.InstallResult.Err -> {
                _state.update { it.copy(pluginBusy = false, banner = Banner(commitRes.message, isError = true)) }
                onDone(false, commitRes.message)
                return@launch
            }
        }

        if (!manifest.isPureSkill && a != null) {
            // 升级或重装先卸载旧工具，防残留旧 id.* 工具
            if (pending.versionRelation != InstallVersionRelation.FRESH ||
                _state.value.mcpPlugins.any { it.id == manifest.id }
            ) {
                a.deleteMcpPlugin(manifest.id)
            }
            val regRes = a.addMcpPlugin(
                name = manifest.id,
                displayName = manifest.name,
                type = manifest.type,
                command = manifest.command,
                args = manifest.args,
                endpoint = manifest.endpoint,
                description = manifest.description,
                env = installed.mergedEnv(),
            )
            if (regRes is KernelApi.ApiResult.Err) {
                // 注册失败且是新安装时回滚磁盘目录，不留半装状态
                if (pending.versionRelation == InstallVersionRelation.FRESH) {
                    withContext(Dispatchers.IO) {
                        PluginInstaller.uninstall(app.filesDir, manifest.id)
                    }
                }
                _state.update { it.copy(pluginBusy = false, banner = Banner(regRes.message, isError = true)) }
                onDone(false, regRes.message)
                return@launch
            }
            if (!installed.enabled) {
                a.setPlugin(manifest.id, false)
            }
        }

        withContext(Dispatchers.IO) {
            runCatching { java.io.File(pending.stagedPath).delete() }
        }
        syncPluginSkillsEverywhere()
        _state.update { it.copy(pendingPluginInstall = null, pluginBusy = false) }
        loadKernelExtras()
        refreshConfigSummary()
        val actionVerb = when (pending.versionRelation) {
            InstallVersionRelation.FRESH -> "已安装"
            InstallVersionRelation.UPGRADE -> "已升级"
            InstallVersionRelation.REINSTALL -> "已重新安装"
            InstallVersionRelation.DOWNGRADE -> "已降级安装"
        }
        toast("$actionVerb：${manifest.name} v${manifest.version}")
        onDone(true, null)
    }
}

/**
 * 启停已安装的插件（可执行包 + 纯文本技能包统一入口）。
 *
 * - 可执行包：写 `install.json.enabled` + 调内核 `PATCH /api/plugins` + 同步随包技能；
 * - 纯文本技能包：写 `install.json.enabled` + 同步工作区（停用即移除工作区副本，启用即重铺）。
 */
fun ChatViewModel.setPluginEnabled(id: String, enabled: Boolean) {
    val app = getApplication<android.app.Application>()
    val target = _state.value.installedPlugins.firstOrNull { it.id == id }
    if (target == null) {
        // 手动添加的 MCP 条目回退走原有方法
        setMcpPlugin(id, enabled)
        return
    }
    viewModelScope.launch {
        val a = api
        if (!target.manifest.isPureSkill) {
            if (a == null) {
                bannerNoKernel()
                return@launch
            }
            when (val r = a.setPlugin(id, enabled)) {
                is KernelApi.ApiResult.Ok -> Unit
                is KernelApi.ApiResult.Err -> {
                    _state.update { it.copy(banner = Banner(r.message, isError = true)) }
                    return@launch
                }
            }
        }
        withContext(Dispatchers.IO) {
            PluginInstaller.setPluginEnabled(app.filesDir, id, enabled)
        }
        syncPluginSkillsEverywhere()
        loadKernelExtras()
        refreshConfigSummary()
        toast(if (enabled) "已启用 ${target.manifest.name}" else "已停用 ${target.manifest.name}")
    }
}

/**
 * 卸载已安装的插件：
 * 1. 可执行插件先调 `DELETE /api/plugins?name=` 卸载工具；
 * 2. 删 `filesDir/plugins/<id>`；
 * 3. 同步器清理带 `plugin.marker` 的工作区技能目录（绝不碰用户自建或内置技能）；
 * 4. 刷新插件、技能与工具列表。
 */
fun ChatViewModel.uninstallPlugin(id: String) {
    val app = getApplication<android.app.Application>()
    val target = _state.value.installedPlugins.firstOrNull { it.id == id }
    if (target == null) {
        deleteMcpPlugin(id)
        return
    }
    viewModelScope.launch {
        val a = api
        if (!target.manifest.isPureSkill && a != null) {
            when (val r = a.deleteMcpPlugin(id)) {
                is KernelApi.ApiResult.Ok -> Unit
                is KernelApi.ApiResult.Err -> {
                    // 若内核里本就不存在该条目（比如曾被手动删过），仍允许继续卸载本地包
                    if (_state.value.mcpPlugins.any { it.id == id }) {
                        _state.update { it.copy(banner = Banner(r.message, isError = true)) }
                        return@launch
                    }
                }
            }
        }
        withContext(Dispatchers.IO) {
            PluginInstaller.uninstall(app.filesDir, id)
        }
        syncPluginSkillsEverywhere()
        loadKernelExtras()
        refreshConfigSummary()
        toast("已卸载插件：${target.manifest.name}")
    }
}

/**
 * 保存插件密钥（`permissions.secrets`）：
 * 写回 `install.json`，并对可执行插件执行 `deleteMcpPlugin` + `addMcpPlugin(env=...)` 热重载生效。
 */
fun ChatViewModel.savePluginSecrets(
    id: String,
    secrets: Map<String, String>,
    onDone: (ok: Boolean, message: String?) -> Unit = { _, _ -> },
) {
    val app = getApplication<android.app.Application>()
    val target = _state.value.installedPlugins.firstOrNull { it.id == id } ?: run {
        onDone(false, "找不到插件：$id")
        return
    }
    val a = api
    if (!target.manifest.isPureSkill && a == null) {
        bannerNoKernel()
        onDone(false, "内核未连接")
        return
    }
    viewModelScope.launch {
        val updatedRes = withContext(Dispatchers.IO) {
            PluginInstaller.updatePluginSecrets(app.filesDir, id, secrets)
        }
        val updated = when (updatedRes) {
            is PluginInstaller.InstallResult.Ok -> updatedRes.value
            is PluginInstaller.InstallResult.Err -> {
                onDone(false, updatedRes.message)
                return@launch
            }
        }
        if (!updated.manifest.isPureSkill && a != null) {
            val m = updated.manifest
            a.deleteMcpPlugin(m.id)
            when (val ar = a.addMcpPlugin(
                name = m.id,
                displayName = m.name,
                type = m.type,
                command = m.command,
                args = m.args,
                endpoint = m.endpoint,
                description = m.description,
                env = updated.mergedEnv(),
            )) {
                is KernelApi.ApiResult.Ok -> {
                    if (!updated.enabled) {
                        a.setPlugin(m.id, false)
                    }
                }
                is KernelApi.ApiResult.Err -> {
                    _state.update { it.copy(banner = Banner(ar.message, isError = true)) }
                    onDone(false, ar.message)
                    return@launch
                }
            }
        }
        refreshInstalledPlugins()
        loadKernelExtras()
        refreshConfigSummary()
        onDone(true, "密钥已保存并生效")
    }
}

private fun resolveUriDisplayName(context: android.content.Context, uri: Uri): String {
    val fromCursor = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
    return fromCursor?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: "plugin.cfplugin"
}
