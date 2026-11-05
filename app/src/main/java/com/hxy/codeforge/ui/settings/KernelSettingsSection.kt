package com.hxy.codeforge.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hxy.codeforge.model.SubagentPolicy
import com.hxy.codeforge.ui.ChatViewModel
import com.hxy.codeforge.ui.common.Glyph
import com.hxy.codeforge.ui.common.GlyphIcon
import com.hxy.codeforge.ui.common.noRippleClickable
import com.hxy.codeforge.ui.theme.Clay

/**
 * 内核设置区：生成参数 / 重试机制 / 上下文压缩 / 子智能体策略 / 权限 / 步数 / 通知 / 工作区。
 *
 * 数据全部来自 [ChatViewModel.state] 的 kernelConfig / subagentPolicy / contextUsage
 * （内核 Ready 后 KernelApi 拉取）；写操作经 VM 走 REST，成功后摘要自动回灌。
 *
 * 技能 / 记忆 / 内置插件 / MCP 已拆成独立子页（见本文件下方四个 public 组合函数），
 * 这里不再混排 —— 原来六组挤在一页，手机上要滑很久才能找到目标项。
 */
@Composable
fun KernelSettingsSection(vm: ChatViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val cfg = state.kernelConfig
    val usage = state.contextUsage

    Text(
        text = "内核设置",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )

    if (cfg == null) {
        // 三种「没配置」的原因完全不同，共用一句话会让人无从下手：
        //  - 链路没通：内核进程没起来或 WS 没连上，等自愈循环或点「重试」；
        //  - 链路通了但 /api/config 没读到：REST 那条腿在退避重试，稍等即可。
        val hint = when {
            !state.connected -> "内核未连接 —— 等待内核就绪，或点上方横幅的「重试」"
            else -> "已连上内核，但配置还没读到 —— 正在自动重试，稍候"
        }
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        return
    }

    Text(
        text = "生效模型：${cfg.displayName}（provider=${cfg.provider}，Key ${if (cfg.apiKeySet) "已配置" else "未配置"}）",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
    )

    // ---------------- 生成参数 ----------------
    SettingsLabel("生成参数")
    var maxTokens by rememberSaveable(cfg.maxTokens) {
        mutableStateOf(cfg.maxTokens.takeIf { it > 0 }?.toString() ?: "")
    }
    var temperature by rememberSaveable(cfg.temperature) {
        mutableStateOf(if (cfg.temperature > 0) cfg.temperature.toString() else "")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = maxTokens,
            onValueChange = { maxTokens = it.filter { c -> c.isDigit() }.take(6) },
            label = { Text("最大 tokens") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = temperature,
            onValueChange = { temperature = it.filter { c -> c.isDigit() || c == '.' }.take(5) },
            label = { Text("温度") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
    }
    SavePill("保存生成参数") {
        vm.updateGenerationConfig(
            maxTokens = maxTokens.toIntOrNull(),
            temperature = temperature.toDoubleOrNull(),
        )
    }

    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(vertical = 14.dp),
    )

    // ---------------- 重试机制 ----------------
    SettingsLabel("重试机制")
    Text(
        text = "上游限流/抖动时自动重试；间隔模式 backoff = 1×→2×→3×→6× 封顶。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
    )
    var attempts by rememberSaveable(cfg.retryMaxAttempts) {
        mutableStateOf(cfg.retryMaxAttempts.takeIf { it > 0 }?.toString() ?: "")
    }
    var intervalSec by rememberSaveable(cfg.retryIntervalSec) {
        mutableStateOf(cfg.retryIntervalSec.takeIf { it > 0 }?.toString() ?: "")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = attempts,
            onValueChange = { attempts = it.filter { c -> c.isDigit() }.take(2) },
            label = { Text("总尝试次数") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = intervalSec,
            onValueChange = { intervalSec = it.filter { c -> c.isDigit() }.take(3) },
            label = { Text("基础间隔(秒)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
    }
    var retryMode by rememberSaveable(cfg.retryMode) { mutableStateOf(cfg.retryMode) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 10.dp),
    ) {
        listOf("backoff" to "递增间隔", "fixed" to "固定间隔").forEach { (id, label) ->
            val selected = retryMode == id
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (selected) Clay else MaterialTheme.colorScheme.surfaceVariant)
                    .noRippleClickable { retryMode = id }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
    SavePill("保存重试设置") {
        vm.updateRetryConfig(
            attempts = attempts.toIntOrNull(),
            mode = retryMode,
            intervalSec = intervalSec.toIntOrNull(),
        )
    }

    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(vertical = 14.dp),
    )

    // ---------------- 上下文压缩 ----------------
    SettingsLabel("上下文")
    if (usage != null) {
        Text(
            text = "当前：${usage.used} / ${usage.budget} tokens（${usage.percent.toInt()}%" +
                if (usage.overBudget) "，超预算）" else "）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
    } else {
        Text(
            text = "占用随每轮对话更新；超过阈值内核会自动摘要压缩。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
    }
    SavePill("立即压缩当前会话", icon = true) { vm.compressContext() }

    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(vertical = 14.dp),
    )

    // ---------------- 子智能体策略 ----------------
    val policy = state.subagentPolicy
    SettingsLabel("子智能体")
    if (policy == null) {
        Text(
            text = "策略读取中或内核不支持。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp),
        )
    } else {
        SubagentPolicyEditor(policy) { vm.updateSubagentPolicy(it) }
    }

    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(vertical = 14.dp),
    )

    // ---------------- 权限三档 ----------------
    SettingsLabel("权限")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            "readonly" to "只读",
            "ask" to "每次询问",
            "auto" to "自动允许",
        ).forEach { (id, label) ->
            val selected = cfg.permMode.equals(id, ignoreCase = true)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (selected) Clay else MaterialTheme.colorScheme.surfaceVariant)
                    .noRippleClickable {
                        vm.setPermissionMode(
                            com.hxy.codeforge.model.PermissionMode.entries.firstOrNull { it.name.equals(id, true) }
                                ?: return@noRippleClickable,
                        )
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }

    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(vertical = 14.dp),
    )

    // ---------------- 智能体（步数上限） ----------------
    SettingsLabel("智能体")
    var maxSteps by rememberSaveable(cfg.maxSteps) {
        mutableStateOf(cfg.maxSteps.takeIf { it > 0 }?.toString() ?: "")
    }
    OutlinedTextField(
        value = maxSteps,
        onValueChange = { maxSteps = it.filter { c -> c.isDigit() }.take(4) },
        label = { Text("单轮步数上限（max_steps）") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    SavePill("保存智能体设置") {
        vm.updateAgentConfig(maxSteps = maxSteps.toIntOrNull())
    }

    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(vertical = 14.dp),
    )

    // ---------------- 通知 & 工作区 ----------------
    SettingsLabel("通知与工作区")
    var notifyOn by rememberSaveable { mutableStateOf(true) }
    SwitchRow(
        title = "任务完成通知",
        subtitle = "由内核在长任务结束时发系统通知",
        checked = notifyOn,
        onChange = { notifyOn = it; vm.setNotifyEnabled(it) },
    )
    Text(
        text = "工作区：${cfg.workDir.ifBlank { "未选择（等待界面选择）" }}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

// ---------------------------------------------------------------- 记忆

/**
 * 记忆管理（独立子页）。
 *
 * 就地编辑走 `PATCH /api/memory`：内核按数字 id 更新内容，id 不是数字时
 * （字符串回退形态）会把 0 发下去，必然失败 —— 失败横幅会说明原因。
 */
@Composable
fun MemorySection(vm: ChatViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()

    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    var memoryInput by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.loadKernelExtras() }

    SettingsLabel("记忆（${state.memories.size}）")
    Text(
        text = "内核按工作区隔离记忆；对话中让模型保存的条目也会出现在这里。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
    if (state.memories.isEmpty()) {
        Text(
            text = "暂无记忆（对话中让模型保存，或在这里手动新增）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
    state.memories.forEach { (id, content) ->
        if (editingId == id) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(400) },
                    label = { Text("编辑记忆内容") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 4,
                )
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SavePillSmall("保存") {
                        if (draft.isNotBlank()) {
                            vm.updateMemory(id, draft.trim())
                            editingId = null
                        }
                    }
                    SavePillSmall("取消", primary = false) { editingId = null }
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 3,
                )
                Text(
                    text = "编辑",
                    style = MaterialTheme.typography.labelSmall,
                    color = Clay,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .noRippleClickable {
                            editingId = id
                            draft = content
                        }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
                GlyphIcon(
                    glyph = Glyph.Close,
                    size = 14.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.noRippleClickable { vm.deleteMemory(id) },
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = memoryInput,
            onValueChange = { memoryInput = it.take(400) },
            placeholder = { Text("新增一条记忆…") },
            modifier = Modifier.weight(1f),
            maxLines = 3,
        )
        Text(
            text = "添加",
            style = MaterialTheme.typography.labelMedium,
            color = Clay,
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .noRippleClickable {
                    vm.addMemory(memoryInput)
                    memoryInput = ""
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

// ---------------------------------------------------------------- 技能

/**
 * 技能独立页。
 *
 * 数据来自 `state.skillInfos`（内核 `/api/skills` 的 `name` / `display_name` /
 * `description` / `triggers`）。触发词逐个渲染成小胶囊 —— 内核按这些词命中技能，
 * 只写一句固定说明的话，用户没法知道哪些词真的能触发。
 */
@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
fun SkillsSection(vm: ChatViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadKernelExtras() }

    SettingsLabel("技能（${state.skillInfos.size}）")
    Text(
        text = "技能由工作区的 SKILL.md 发现；输入框里键入 @ 可补全技能与内置插件。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    if (state.skillInfos.isEmpty()) {
        Text(
            text = "暂无技能（在工作区创建 SKILL.md 后由内核发现）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
    state.skillInfos.forEach { skill ->
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            Text(
                text = skill.name.ifBlank { skill.slug },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // 显示名与 slug 不同时把 slug 也亮出来：@ 补全里用的是 slug
            if (skill.slug.isNotBlank() && skill.slug != skill.name) {
                Text(
                    text = skill.slug,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            if (skill.description.isNotBlank()) {
                Text(
                    text = skill.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (skill.triggers.isNotEmpty()) {
                Text(
                    text = "触发",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 3.dp),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    skill.triggers.forEach { t -> TagChip(t) }
                }
            } else if (skill.name.isNotBlank()) {
                Text(
                    text = "触发：在输入框键入 @${skill.name}，或在对话中提及该技能",
                    style = MaterialTheme.typography.labelSmall,
                    color = Clay,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

// ---------------------------------------------------------------- 内置插件

/**
 * 内置插件页。
 *
 * 数据来自 `state.builtinPluginInfos`（内核 `/api/builtin-plugins` 的
 * `id` / `name` / `purpose` / `when_to_use` / `enabled`）。用途与使用场景必须展示：
 * 只有名字和开关的话，用户不知道打开它到底会改变什么行为。
 * 开关沿用 `vm.setBuiltinPlugin`（成功后 VM 自己重拉列表）。
 */
@Composable
fun BuiltinPluginsSection(vm: ChatViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadKernelExtras() }

    // 内核没给中文名时用这份兜底（id → 中文名）；内核给了非 id 的 name 优先用它的
    val names = mapOf(
        "skill_creator" to "技能创建器",
        "multi_agent" to "多智能体",
        "plan" to "计划模式",
        "goal_mode" to "目标模式",
        "vulnerability_research" to "漏洞挖掘",
        "reverse_analysis" to "逆向分析",
    )

    SettingsLabel("内置插件")
    Text(
        text = "开启后可在对话中用 @id 触发，或由内核在相关任务里自动启用。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
    if (state.builtinPluginInfos.isEmpty()) {
        Text(
            text = "未读到列表（内核未连接或旧版内核不支持该端点）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
    state.builtinPluginInfos.forEach { plugin ->
        val title = plugin.name.takeIf { it.isNotBlank() && it != plugin.id }
            ?: names[plugin.id]
            ?: plugin.id
        SwitchRow(
            title = title,
            subtitle = builtinSubtitle(plugin.purpose, plugin.whenToUse),
            checked = plugin.enabled,
            onChange = { vm.setBuiltinPlugin(plugin.id, it) },
        )
    }
}

/** 内置插件的副标题：用途 + 何时使用；内核没给说明时不编造。 */
private fun builtinSubtitle(purpose: String, whenToUse: String): String {
    val parts = mutableListOf<String>()
    if (purpose.isNotBlank()) parts.add(purpose)
    if (whenToUse.isNotBlank()) parts.add("何时用：$whenToUse")
    return if (parts.isEmpty()) "内核未提供用途说明" else parts.joinToString("；")
}

// ---------------------------------------------------------------- MCP

/**
 * MCP 服务页（完整表单）。
 *
 * 类型两档：`mcp`（stdio，填启动命令 + 参数）与 `mcp-http`（远程端点）。
 * 环境变量按「键值对」逐行编辑，可增删 —— 单个多行文本框要求用户自己敲
 * `KEY=VALUE`，漏个等号整条就悄悄丢了；提交时聚合成 `Map` 传给
 * `vm.addMcpPlugin(..., env = env)`。
 */
@Composable
fun McpPluginsSection(vm: ChatViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadKernelExtras() }

    var adding by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }
    var fName by rememberSaveable { mutableStateOf("") }
    var fDisplay by rememberSaveable { mutableStateOf("") }
    var fHttp by rememberSaveable { mutableStateOf(false) }
    var fCommand by rememberSaveable { mutableStateOf("") }
    var fArgs by rememberSaveable { mutableStateOf("") }
    var fEndpoint by rememberSaveable { mutableStateOf("") }
    var fDesc by rememberSaveable { mutableStateOf("") }
    // 环境变量用两条等长列表存键与值：rememberSaveable 能直接存 List<String>，
    // 摆成 Pair 列表就得自己写 Saver，得不偿失。
    var envKeys by rememberSaveable { mutableStateOf(listOf("")) }
    var envValues by rememberSaveable { mutableStateOf(listOf("")) }
    var formError by rememberSaveable { mutableStateOf<String?>(null) }

    SettingsLabel("MCP 服务")
    Text(
        text = "外部 MCP 服务：stdio 填启动命令，远程填 Streamable HTTP 端点。启用后其工具会以「插件名.工具名」出现在对话里。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )

    if (state.mcpPlugins.isEmpty()) {
        Text(
            text = "（暂无 MCP 服务，点下方添加）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }

    state.mcpPlugins.forEach { p ->
        val id = p.id
        val name = p.name
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name.ifBlank { id },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = id,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (p.configured && !p.loaded) {
                    // 配置开了但没跑起来：多半是启动命令不可用（npx 缺失 /
                    // 端点不可达）。不提示的话用户只会以为开关坏了。
                    Text(
                        text = "已启用但未运行 —— 检查启动命令或端点是否可用",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            // 删除：两次确认，避免误删已配好的服务
            Text(
                text = if (pendingDelete == id) "确认删除" else "删除",
                style = MaterialTheme.typography.labelSmall,
                color = if (pendingDelete == id) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .noRippleClickable {
                        if (pendingDelete == id) {
                            vm.deleteMcpPlugin(id)
                            pendingDelete = null
                        } else {
                            pendingDelete = id
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
            // 开关读「配置态」而不是「运行态」：
            // 内核 enabled = configured && loaded，插件加载失败时 loaded=false，
            // 若开关跟着它走，用户点「开」后立刻弹回，看着就是「打不开」。
            // 运行态另用一行小字标注。
            Switch(
                checked = p.configured,
                onCheckedChange = { vm.setMcpPlugin(id, it) },
                colors = SwitchDefaults.colors(checkedTrackColor = Clay),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }

    if (!adding) {
        Box(
            modifier = Modifier
                .padding(top = 10.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .noRippleClickable { adding = true }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(
                text = "+ 添加 MCP 服务",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    } else {
        Column(
            modifier = Modifier.padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = fName,
                onValueChange = { fName = it },
                label = { Text("名称（唯一，用作工具前缀）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = fDisplay,
                onValueChange = { fDisplay = it },
                label = { Text("显示名（可选）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    false to "stdio（mcp）",
                    true to "HTTP（mcp-http）",
                ).forEach { (http, label) ->
                    val sel = fHttp == http
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (sel) Clay else MaterialTheme.colorScheme.surfaceVariant)
                            .noRippleClickable { fHttp = http }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (sel) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }

            if (fHttp) {
                OutlinedTextField(
                    value = fEndpoint,
                    onValueChange = { fEndpoint = it },
                    label = { Text("端点 URL（https://…）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedTextField(
                    value = fCommand,
                    onValueChange = { fCommand = it },
                    label = { Text("启动命令（如 npx）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = fArgs,
                    onValueChange = { fArgs = it },
                    label = { Text("参数（空格分隔，如 -y @modelcontextprotocol/server-filesystem /sdcard）") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // 环境变量：一行一对键值，可增删；键为空的行提交时忽略
            Text(
                text = "环境变量（可选，KEY 与 VALUE 都填才算一条）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            envKeys.indices.forEach { i ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedTextField(
                        value = envKeys.getOrElse(i) { "" },
                        onValueChange = { v ->
                            envKeys = envKeys.toMutableList().also { it[i] = v }
                        },
                        label = { Text("KEY") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = envValues.getOrElse(i) { "" },
                        onValueChange = { v ->
                            envValues = envValues.toMutableList().also { it[i] = v }
                        },
                        label = { Text("VALUE") },
                        singleLine = true,
                        modifier = Modifier.weight(1.3f),
                    )
                    Text(
                        text = "删",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .noRippleClickable {
                                val k = envKeys.toMutableList().also { it.removeAt(i) }
                                val v = envValues.toMutableList().also { it.removeAt(i) }
                                // 至少留一行空壳，否则整块表单会消失
                                envKeys = k.ifEmpty { listOf("") }
                                envValues = v.ifEmpty { listOf("") }
                            }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .noRippleClickable {
                        envKeys = envKeys + ""
                        envValues = envValues + ""
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "+ 添加变量",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            OutlinedTextField(
                value = fDesc,
                onValueChange = { fDesc = it },
                label = { Text("描述（可选）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            formError?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(Clay)
                        .noRippleClickable {
                            if (fName.isBlank()) {
                                formError = "名称不能为空（内核用作工具前缀）"
                                return@noRippleClickable
                            }
                            if (fHttp && fEndpoint.isBlank()) {
                                formError = "HTTP 类型需要填端点 URL"
                                return@noRippleClickable
                            }
                            if (!fHttp && fCommand.isBlank()) {
                                formError = "stdio 类型需要填启动命令"
                                return@noRippleClickable
                            }
                            formError = null
                            val envMap = envKeys.indices
                                .mapNotNull { i ->
                                    val key = envKeys[i].trim()
                                    if (key.isEmpty()) {
                                        null
                                    } else {
                                        key to envValues.getOrElse(i) { "" }.trim()
                                    }
                                }
                                .toMap()
                            vm.addMcpPlugin(
                                name = fName.trim(),
                                displayName = fDisplay.trim(),
                                type = if (fHttp) "mcp-http" else "mcp",
                                command = fCommand.trim(),
                                args = fArgs.trim().split(Regex("\\s+")).filter { it.isNotBlank() },
                                endpoint = fEndpoint.trim(),
                                description = fDesc.trim(),
                                env = envMap.toList(),
                            )
                            adding = false
                            fName = ""; fDisplay = ""; fCommand = ""; fArgs = ""
                            fEndpoint = ""; fDesc = ""
                            envKeys = listOf("")
                            envValues = listOf("")
                        }
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                ) {
                    Text(
                        text = "添加",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .noRippleClickable {
                            adding = false
                            formError = null
                        }
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                ) {
                    Text(
                        text = "取消",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 子件

@Composable
private fun SettingsLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

/** 保存/动作胶囊：复用设置页既有陶土胶囊形态。 */
@Composable
private fun SavePill(label: String, icon: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Clay)
            .noRippleClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon) {
            GlyphIcon(
                glyph = Glyph.Check,
                size = 13.dp,
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.2.dp,
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/** 行内的次级动作（保存/取消）：不占整行，也不抢主按钮的陶土橘。 */
@Composable
private fun SavePillSmall(label: String, primary: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (primary) Clay else MaterialTheme.colorScheme.surfaceVariant)
            .noRippleClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (primary) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Clay),
        )
    }
}

/** 触发词小胶囊：纯展示，不加点击手势（点它没有任何语义）。 */
@Composable
private fun TagChip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SubagentPolicyEditor(policy: SubagentPolicy, onApply: (SubagentPolicy) -> Unit) {
    var enabled by rememberSaveable(policy.enabled) { mutableStateOf(policy.enabled) }
    var maxConcurrent by rememberSaveable(policy.maxConcurrent) { mutableStateOf(policy.maxConcurrent.toString()) }
    var maxSteps by rememberSaveable(policy.maxSteps) { mutableStateOf(policy.maxSteps.toString()) }
    var allowWrite by rememberSaveable(policy.allowWrite) { mutableStateOf(policy.allowWrite) }
    var allowDelete by rememberSaveable(policy.allowDelete) { mutableStateOf(policy.allowDelete) }
    var allowMemory by rememberSaveable(policy.allowMemory) { mutableStateOf(policy.allowMemory) }

    SwitchRow("启用子智能体", "允许主智能体并行委派探查/实现/验证任务", enabled) { enabled = it }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
        OutlinedTextField(
            value = maxConcurrent,
            onValueChange = { maxConcurrent = it.filter { c -> c.isDigit() }.take(2) },
            label = { Text("并发上限") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = maxSteps,
            onValueChange = { maxSteps = it.filter { c -> c.isDigit() }.take(5) },
            label = { Text("步数上限") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
    }
    SwitchRow("允许写文件", "子任务可直接落盘（否则只读）", allowWrite) { allowWrite = it }
    SwitchRow("允许删文件", "", allowDelete) { allowDelete = it }
    SwitchRow("允许写记忆", "子任务可保存用户记忆", allowMemory) { allowMemory = it }
    SavePill("保存子智能体策略") {
        onApply(
            SubagentPolicy(
                enabled = enabled,
                maxConcurrent = maxConcurrent.toIntOrNull() ?: policy.maxConcurrent,
                maxSteps = maxSteps.toIntOrNull() ?: policy.maxSteps,
                allowWrite = allowWrite,
                allowDelete = allowDelete,
                allowMemory = allowMemory,
            ),
        )
    }
}
