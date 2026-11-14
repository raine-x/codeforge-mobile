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

/** 权限模式三档：本地乐观更新 + 热写到内核（/api/perm）。 */
fun ChatViewModel.setPermissionMode(mode: PermissionMode) {
    _state.update { it.copy(permissionMode = mode) }
    AppSettings.setLastPermission(mode.name)
    api?.let { a -> viewModelScope.launch { a.setPerm(mode.name.lowercase()) } }
    toast("权限：${mode.label}")
}

/** 选择模型：POST /api/config {model}（内核侧切通道），本地胶囊即时跟随。 */
fun ChatViewModel.selectModel(id: String) {
    val a = api ?: run { bannerNoKernel(); return }
    viewModelScope.launch {
        when (val r = a.updateConfig(model = id)) {
            is KernelApi.ApiResult.Ok -> _state.update { it.copy(modelName = id) }
            is KernelApi.ApiResult.Err -> _state.update { it.copy(banner = Banner(r.message, true)) }
        }
    }
}
/** 应用模型库条目（模型弹层选真模型时走这里，内核切通道）。 */
fun ChatViewModel.applyModel(id: String) {
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
                // 换模型会换上下文窗口（ctx_in）与压缩预算：不拉一次 context 帧，
                // 输入卡外圈的占比还按旧窗口画（1M 模型仍显示接近压缩线）
                transport?.pullContext()
                toast("已切换到 $id")
            }
            is KernelApi.ApiResult.Err -> _state.update { it.copy(banner = Banner(r.message, true)) }
        }
    }
}
internal fun ChatViewModel.refreshKernelSummaries() {
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

fun ChatViewModel.updateAgentConfig(maxSteps: Int?) {
    val a = api ?: run { bannerNoKernel(); return }
    viewModelScope.launch {
        when (val r = a.updateConfig(maxSteps = maxSteps)) {
            is KernelApi.ApiResult.Ok -> Unit
            is KernelApi.ApiResult.Err -> _state.update { it.copy(banner = Banner(r.message, true)) }
        }
    }
}

fun ChatViewModel.updateRetryConfig(attempts: Int?, mode: String?, intervalSec: Int?) {
    val a = api ?: run { bannerNoKernel(); return }
    viewModelScope.launch {
        when (val r = a.updateConfig(
            retryMaxAttempts = attempts,
            retryMode = mode,
            retryIntervalSec = intervalSec,
        )) {
            is KernelApi.ApiResult.Ok -> {
                refreshKernelSummaries()
                toast("重试设置已保存")
            }
            is KernelApi.ApiResult.Err -> _state.update { it.copy(banner = Banner(r.message, true)) }
        }
    }
}

fun ChatViewModel.updateSubagentPolicy(policy: SubagentPolicy) {
    val a = api ?: run { bannerNoKernel(); return }
    viewModelScope.launch {
        when (val r = a.updateSubagentPolicy(policy)) {
            is KernelApi.ApiResult.Ok -> refreshKernelSummaries()
            is KernelApi.ApiResult.Err -> _state.update { it.copy(banner = Banner(r.message, true)) }
        }
    }
}

// 内置插件 @ 补全的回落映射：现役内核的触发词匹配（pluginTriggered）只认
// 原生触发词，不认插件 id 形态——壳侧 @ 面板插 @vulnerability_research 时
// 内核毫无反应，「选了等于没选」。内核触发词数据化版本会下发 triggers 字段，
// 到时候优先走 API；映射只兜旧内核。逆向分析不用映射：它的原生触发词
// @reverse_analysis 与 id 本来就一致。
private val builtinMentionFallback = mapOf(
    "vulnerability_research" to "vuln_hunt",
)

/** 内置插件插入输入框的 token：内核下发的真实触发词优先，否则查回落映射，最后退回 id。 */
private fun builtinMentionToken(p: BuiltinPluginInfo): String {
    p.triggers.firstOrNull()?.let { return it.removePrefix("@") }
    return builtinMentionFallback[p.id] ?: p.id.removePrefix("@")
}

/** 设置页：拉内置插件 / MCP 插件 / 技能（Ready 后调，幂等）。 */
fun ChatViewModel.loadKernelExtras() {
    refreshInstalledPlugins()
    val a = api ?: return
    viewModelScope.launch {
        when (val r = a.listBuiltinPlugins()) {
            is KernelApi.ApiResult.Ok -> _state.update {
                it.copy(
                    builtinPluginInfos = r.value,
                    // 旧 Triple 形态给 Composer 的 @ 补全：第一项是插入输入框的 token
                    //（解析规则见 builtinMentionToken），第二项填内核下发的显示名。
                    builtinPlugins = r.value.map { p -> Triple(builtinMentionToken(p), p.name, p.enabled) },
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
                // 给历史库里未设 ctx_in（<= 0）的条目自动补齐预设或至少 128K 默认上下文
                backfillMissingModelContexts(a, r.value)
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
fun ChatViewModel.refreshTasks() {
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
fun ChatViewModel.startTask(command: String, label: String = "") {
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
fun ChatViewModel.killTask(id: String) {
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

fun ChatViewModel.setBuiltinPlugin(id: String, enabled: Boolean) {
    val a = api ?: return
    viewModelScope.launch {
        a.setBuiltinPlugin(id, enabled)
        loadKernelExtras()
    }
}

fun ChatViewModel.setBuiltinPluginModify(id: String, allowModify: Boolean) {
    val a = api ?: return
    viewModelScope.launch {
        when (val r = a.setBuiltinPluginModify(id, allowModify)) {
            is KernelApi.ApiResult.Ok -> toast(
                if (allowModify) "逆向修改能力已开启（注意保留原件备份）"
                else "已切回只读逆向模式",
            )
            is KernelApi.ApiResult.Err -> toast(r.message, isError = true)
        }
        loadKernelExtras()
    }
}

fun ChatViewModel.setMcpPlugin(id: String, enabled: Boolean) {
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
fun ChatViewModel.discoverModels(
    provider: String,
    onResult: (models: List<String>, error: String?, keyFromActive: Boolean) -> Unit,
) {
    val a = api ?: run {
        bannerNoKernel()
        onResult(emptyList(), "内核未连接", false)
        return
    }
    viewModelScope.launch {
        // 带上供应商自己的地址/协议/密钥：内核只有在「请求里没有可用密钥」时
        // 才会回退到**当前生效模型**的密钥（响应里的 key_from_active），
        // 带上就不回退 —— 探测的才是这个供应商。
        val p = providerInfo(provider)
        when (val r = a.discoverModels(
            provider = provider,
            baseUrl = p?.baseUrl.orEmpty(),
            protocol = p?.protocol.orEmpty(),
            keyValue = p?.keyPlain.orEmpty(),
        )) {
            is KernelApi.ApiResult.Ok -> onResult(
                r.value.models,
                if (r.value.models.isEmpty()) "未探测到模型：检查供应商密钥/地址或上游是否支持 /models" else null,
                r.value.keyFromActive,
            )

            is KernelApi.ApiResult.Err -> {
                _state.update { it.copy(banner = Banner(r.message, true)) }
                // 探测入口在设置弹层里，主界面的 banner 被弹层盖住看不到；
                // 必须把错误回传给调用点就地显示。
                onResult(emptyList(), r.message, false)
            }
        }
    }
}

/** 把一个探测到的模型存进模型库（自动补齐预设上下文与默认 128K 下限）。 */
fun ChatViewModel.saveModel(id: String, displayName: String, provider: String) {
    val a = api ?: run { bannerNoKernel(); return }
    val spec = ModelPresets.resolveOrDefault(id)
    viewModelScope.launch {
        // 走 /api/models/save 而不是 save_batch：批量接口只认 id/name/provider，
        // 而且实测加进去后主对话框的模型列表不刷新（等于没加）。
        when (val r = a.saveModelFull(
            id = id,
            name = displayName.ifBlank { id },
            providerId = provider,
            ctxIn = spec.ctxIn,
            ctxOut = spec.ctxOut,
            vision = spec.vision,
            video = spec.video,
        )) {
            is KernelApi.ApiResult.Ok -> {
                refreshKernelSummaries()
                if (_state.value.kernelConfig?.model == id) {
                    transport?.pullContext()
                }
                toast("已添加模型 $id")
            }

            is KernelApi.ApiResult.Err ->
                _state.update { it.copy(banner = Banner(r.message, true)) }
        }
    }
}

/** 从模型库删除一个模型。 */
fun ChatViewModel.deleteModel(id: String) {
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
fun ChatViewModel.saveModelFull(
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
    onDone: (ok: Boolean, message: String?) -> Unit = { _, _ -> },
) {
    val a = api ?: run { bannerNoKernel(); onDone(false, "内核未连接"); return }
    if (id.isBlank()) {
        toast("模型 id 不能为空", isError = true)
        onDone(false, "模型 id 不能为空")
        return
    }
    val preset = ModelPresets.resolveOrDefault(id)
    val resolvedCtxIn = if (ctxIn > 0) ctxIn else preset.ctxIn
    val resolvedCtxOut = if (ctxOut > 0) ctxOut else preset.ctxOut
    viewModelScope.launch {
        when (val r = a.saveModelFull(
            id = id,
            name = name.ifBlank { id },
            providerId = providerId,
            ctxIn = resolvedCtxIn,
            ctxOut = resolvedCtxOut,
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
                // 改了当前生效模型的规格 → 当场重应用，否则改了看不到效果，
                // 直到用户下一次在模型库点「设为当前」才 apply（客服项 8 的同一解法）
                val current = _state.value.kernelConfig?.model
                if (current != null && current == id) {
                    when (val ap = a.applyModel(id)) {
                        is KernelApi.ApiResult.Ok -> {
                            // 刚改的 ctx_in 要立刻反映到占比环与压缩预算
                            transport?.pullContext()
                            toast("模型已保存，已应用到当前会话")
                        }
                        is KernelApi.ApiResult.Err ->
                            toast("模型已保存，但应用失败：${ap.message}", isError = true)
                    }
                } else {
                    toast("模型已保存")
                }
                onDone(true, null)
            }

            is KernelApi.ApiResult.Err -> {
                _state.update { it.copy(banner = Banner(r.message, true)) }
                onDone(false, r.message)
            }
        }
    }
}

/**
 * 测试一条模型的连接。结果走回调而不是塞进 state —— 测试是一次性动作，
 * 且入口在设置子页里，主界面横幅会被弹层盖住。
 * [onResult] 第一个参数是成功文案（含延迟），第二个是失败原因。
 */
fun ChatViewModel.testModel(id: String, onResult: (String?, String?) -> Unit) {
    val a = api ?: run { onResult(null, "内核未连接"); return }
    viewModelScope.launch {
        // 测的是**这条模型自己的**通道：带上它的供应商 id，
        // 内核才会用该供应商的地址/协议/密钥，而不是当前生效的那条。
        val entry = _state.value.libraryModels.firstOrNull { it.id == id }
        val pid = entry?.provider?.takeIf { it.isNotBlank() }.orEmpty()
        val p = providerInfo(pid)
        when (val r = a.testModel(
            model = id,
            providerId = pid,
            baseUrl = p?.baseUrl?.takeIf { it.isNotBlank() } ?: entry?.baseUrl.orEmpty(),
            protocol = p?.protocol?.takeIf { it.isNotBlank() } ?: entry?.protocol.orEmpty(),
            keyValue = p?.keyPlain.orEmpty(),
        )) {
            is KernelApi.ApiResult.Ok -> onResult(r.value, null)
            is KernelApi.ApiResult.Err -> onResult(null, r.message)
        }
    }
}

/** 按 id 找供应商的连接信息（含内核回填的明文密钥）。 */
private fun ChatViewModel.providerInfo(providerId: String): KernelApi.ProviderInfo? {
    if (providerId.isBlank()) return null
    return _state.value.providerDetails.firstOrNull { it.id == providerId }
}
fun ChatViewModel.setNotifyEnabled(enabled: Boolean) {
    // 壳侧开关是发送方（见 notifyDone）；内核那条只做同步记录，不再发通知
    AppSettings.setNotifyEnabled(enabled)
    api?.let { a -> viewModelScope.launch { a.setNotifyEnabled(enabled) } }
}

/**
 * 给模型库里尚未声明上下文（`ctxIn <= 0`）的老条目按 [ModelPresets] 补齐至少 128K。
 *
 * 用 `inheritKey = false` 且留空 `keyValue`，配合内核 `models.go` 保留既有自定义密钥，
 * 不破坏用户原来填好的明文/环境变量密钥。
 */
internal suspend fun ChatViewModel.backfillMissingModelContexts(a: KernelApi, models: List<KernelApi.ModelEntry>) {
    val candidates = models.filter { it.ctxIn <= 0 && backfilledCtxModels.add(it.id) }
    if (candidates.isEmpty()) return
    var updated = 0
    var activeUpdated = false
    val activeId = _state.value.kernelConfig?.model
    for (m in candidates) {
        val preset = ModelPresets.resolveOrDefault(m.id)
        val resolvedOut = m.ctxOut.takeIf { it > 0 } ?: preset.ctxOut
        val res = a.saveModelFull(
            id = m.id,
            name = m.name.ifBlank { m.id },
            providerId = m.provider,
            ctxIn = preset.ctxIn,
            ctxOut = resolvedOut,
            vision = m.vision ?: preset.vision,
            video = m.video ?: preset.video,
            rpm = m.rpm,
            baseUrl = m.baseUrl,
            protocol = m.protocol,
            keySource = m.keySource,
            keyName = m.keyName,
            keyValue = "",
            inheritKey = false,
        )
        if (res is KernelApi.ApiResult.Ok) {
            updated++
            if (activeId == m.id) activeUpdated = true
        } else {
            backfilledCtxModels.remove(m.id)
        }
    }
    if (updated > 0) {
        refreshKernelSummaries()
        if (activeUpdated && !activeId.isNullOrBlank()) {
            a.applyModel(activeId)
            transport?.pullContext()
        }
    }
}
/** 保存（新增或按 id 更新）一个供应商，成功后重拉列表。 */
fun ChatViewModel.saveProvider(
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
                loadKernelExtras()
                _state.update { it.copy(banner = Banner("供应商已保存", isError = false)) }
                // 内核当前生效的模型不在模型库里 = 眼下**没有任何可用通道**。
                // 用户刚把 Key 填好了 —— 这时直接把该供应商下的模型切过去，
                // 别让 App 停在「配好了却用不了」的状态。
                if (_state.value.kernelConfig?.modelInLibrary == false) {
                    val candidate = _state.value.libraryModels.firstOrNull { it.provider == id }
                    if (candidate != null) applyModel(candidate.id)
                }
            }

            is KernelApi.ApiResult.Err ->
                _state.update { it.copy(banner = Banner(r.message, isError = true)) }
        }
    }
}

/** 删除供应商；内核会顺带解绑该供应商下的模型。 */
fun ChatViewModel.deleteProvider(id: String) {
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
fun ChatViewModel.refreshProviderDetails() {
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
internal fun ChatViewModel.seedDeepSeekIfEmpty(providers: List<KernelApi.ProviderInfo>) {
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
        when (val ar = a.applyModel("deepseek-flash")) {
            is KernelApi.ApiResult.Ok -> {
                _state.update { it.copy(modelName = "deepseek-flash") }
                // 同上：预置完也要让供应商/模型/配置三者口径一致（问题 8）
                refreshKernelSummaries()
            }
            is KernelApi.ApiResult.Err -> {
                // 预置的密钥是**留空**的（等用户去供应商页填）→ apply 必然失败。
                // 这不是故障，是「等 Key」。但必须说出来 ——
                // 静默吞掉的话，界面上只表现为「供应商未选择」，
                // 用户完全看不出卡在哪一步（真机截图复现）。
                _state.update {
                    it.copy(
                        banner = Banner(
                            "已预置 DeepSeek 官方与 DeepSeek Flash，但还差一把 API Key —— " +
                                "到「设置 → 模型服务 → 供应商」填上即可使用",
                            isError = false,
                        )
                    )
                }
            }
        }
    }
}
