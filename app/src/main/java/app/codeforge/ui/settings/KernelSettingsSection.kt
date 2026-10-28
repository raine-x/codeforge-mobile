package app.codeforge.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.codeforge.model.SubagentPolicy
import app.codeforge.ui.common.Glyph
import app.codeforge.ui.common.GlyphIcon
import app.codeforge.ui.common.noRippleClickable
import app.codeforge.ui.ChatViewModel
import app.codeforge.ui.theme.Clay

/**
 * 内核设置区：生成参数 / 重试机制 / 上下文压缩 / 子智能体策略 / 通知 / 工作区展示。
 *
 * 数据全部来自 [ChatViewModel.state] 的 kernelConfig / subagentPolicy / contextUsage
 * （内核 Ready 后 KernelApi 拉取）；写操作经 VM 走 REST，成功后摘要自动回灌。
 * 控件全部复用既有形态：OutlinedTextField、Switch 行、CheckRow 胶囊——
 * 供应商/模型的选择在对话界面「选择模型」弹层里，此处不重复。
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
    var maxTokens by rememberSaveable(cfg.maxTokens) { mutableStateOf(cfg.maxTokens.takeIf { it > 0 }?.toString() ?: "") }
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

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 14.dp))

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
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
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
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
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

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 14.dp))

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

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 14.dp))

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

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 14.dp))

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
                            app.codeforge.model.PermissionMode.entries.firstOrNull { it.name.equals(id, true) }
                                ?: return@noRippleClickable,
                        )
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 14.dp))

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

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 14.dp))

    // ---------------- 内置插件 ----------------
    run {
        SettingsLabel("内置插件")
        val names = mapOf(
            "skill_creator" to "技能创建器", "multi_agent" to "多智能体", "plan" to "计划模式",
            "goal_mode" to "目标模式", "vulnerability_research" to "漏洞挖掘", "reverse_analysis" to "逆向分析",
        )
        if (state.builtinPlugins.isEmpty()) {
            Text(
                text = "未读到列表（内核未连接或旧版内核不支持该端点）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        state.builtinPlugins.forEach { (id, _, enabled) ->
            SwitchRow(
                title = names[id] ?: id,
                subtitle = "@触发 或自动增强能力，关闭即停用",
                checked = enabled,
                onChange = { vm.setBuiltinPlugin(id, it) },
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 14.dp))
    }

    // ---------------- MCP 插件 ----------------
    run {
        SettingsLabel("MCP 插件")

        var adding by rememberSaveable { mutableStateOf(false) }
        var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }
        var fName by rememberSaveable { mutableStateOf("") }
        var fDisplay by rememberSaveable { mutableStateOf("") }
        var fHttp by rememberSaveable { mutableStateOf(false) }
        var fCommand by rememberSaveable { mutableStateOf("") }
        var fArgs by rememberSaveable { mutableStateOf("") }
        var fEndpoint by rememberSaveable { mutableStateOf("") }
        var fDesc by rememberSaveable { mutableStateOf("") }

        Text(
            text = "外部 MCP 服务：stdio 填启动命令，远程填 Streamable HTTP 端点。启用后其工具会以「插件名.工具名」出现在对话里。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )

        if (state.mcpPlugins.isEmpty()) {
            Text(
                text = "（暂无 MCP 插件，点下方添加）",
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
        }

        if (!adding) {
            Box(
                modifier = Modifier
                    .padding(top = 8.dp)
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
                    listOf(false to "stdio", true to "HTTP").forEach { (http, label) ->
                        val sel = fHttp == http
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (sel) Clay else MaterialTheme.colorScheme.surfaceVariant)
                                .noRippleClickable { fHttp = http }
                                .padding(horizontal = 16.dp, vertical = 7.dp),
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

                OutlinedTextField(
                    value = fDesc,
                    onValueChange = { fDesc = it },
                    label = { Text("描述（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(Clay)
                            .noRippleClickable {
                                vm.addMcpPlugin(
                                    name = fName.trim(),
                                    displayName = fDisplay.trim(),
                                    type = if (fHttp) "mcp-http" else "mcp",
                                    command = fCommand.trim(),
                                    args = fArgs.trim().split(Regex("\\s+")).filter { it.isNotBlank() },
                                    endpoint = fEndpoint.trim(),
                                    description = fDesc.trim(),
                                )
                                adding = false
                                fName = ""; fDisplay = ""; fCommand = ""; fArgs = ""; fEndpoint = ""; fDesc = ""
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
                            .noRippleClickable { adding = false }
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

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 14.dp))
    }

    // ---------------- 技能 ----------------
    run {
        SettingsLabel("技能（${state.skills.size}）")
        if (state.skills.isEmpty()) {
            Text(
                text = "暂无技能（在工作区创建 SKILL.md 后由内核发现）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        state.skills.forEach { (name, desc) ->
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                Text(name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                if (desc.isNotBlank()) {
                    Text(
                        desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 14.dp))
    }

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
