package com.hxy.codeforge.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hxy.codeforge.core.KernelApi
import com.hxy.codeforge.model.InstallVersionRelation
import com.hxy.codeforge.model.InstalledPlugin
import com.hxy.codeforge.model.PendingPluginInstall
import com.hxy.codeforge.model.PluginPermissions
import com.hxy.codeforge.model.PluginSkillRef
import com.hxy.codeforge.ui.common.Glyph
import com.hxy.codeforge.ui.common.GlyphIcon
import com.hxy.codeforge.ui.common.noRippleClickable
import com.hxy.codeforge.ui.theme.Clay

/**
 * 纯文本技能（B 类）的能力边界固定说明（§1.3 硬性要求）。
 */
internal const val SKILL_BOUNDARY_NOTICE =
    "能力边界：纯文本技能只向模型注入知识、流程与检查清单，本身不能直接执行代码。" +
        "若技能正文引用了随包脚本（如 references/xxx.py），模型仍需通过 run_command 工具（受容器环境与审批策略约束）运行。"

/**
 * 已安装动态插件的详情弹层：
 * 展示元信息、权限声明、密钥填写、已加载工具列表（按 `<id>.` 前缀过滤）、随包技能同步态、README、更新与卸载。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PluginDetailSheet(
    plugin: InstalledPlugin,
    mcpStatus: KernelApi.McpPluginInfo?,
    registeredTools: List<String>,
    onToggleEnabled: (Boolean) -> Unit,
    onSaveSecrets: (Map<String, String>, onResult: (Boolean, String?) -> Unit) -> Unit,
    onRequestUpdate: () -> Unit,
    onUninstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val manifest = plugin.manifest
    val secretKeys = manifest.secretKeys
    val secretEdits = remember(plugin.id, plugin.configuredSecrets) {
        mutableStateMapOf<String, String>().apply {
            secretKeys.forEach { k ->
                put(k, plugin.configuredSecrets[k] ?: manifest.env[k].orEmpty())
            }
        }
    }
    var revealSecrets by rememberSaveable { mutableStateOf(false) }
    var savingSecrets by remember { mutableStateOf(false) }
    var secretFeedback by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var confirmUninstall by rememberSaveable(plugin.id) { mutableStateOf(false) }
    var showReadme by rememberSaveable(plugin.id) { mutableStateOf(false) }

    val pluginTools = remember(registeredTools, plugin.id) {
        val prefix = "${plugin.id}."
        registeredTools.filter { it.startsWith(prefix) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 1f),
    ) {
        com.hxy.codeforge.ui.common.SyncDialogStatusBar()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .padding(horizontal = 16.dp),
        ) {
            // 顶栏：插件标题 + 关闭按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = manifest.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        PluginTypeBadge(type = manifest.type, hasSkills = manifest.skills.isNotEmpty())
                    }
                    Text(
                        text = "${manifest.id} · v${manifest.version}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .noRippleClickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    GlyphIcon(
                        glyph = Glyph.Close,
                        size = 14.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 启用开关卡片
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        val statusLabel = when {
                            !plugin.enabled -> "未启用（已停用）"
                            manifest.isPureSkill -> "已启用 · 随工作区自动同步技能"
                            mcpStatus?.loaded == true -> "运行中 · 已加载 ${pluginTools.size} 个工具"
                            mcpStatus?.configured == true -> "已启用（等待内核加载或加载失败）"
                            else -> "已启用"
                        }
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (plugin.enabled) Clay else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (manifest.isPureSkill) {
                            Text(
                                text = "停用后将从当前工作区移除技能副本，重新启用即恢复",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Switch(
                        checked = plugin.enabled,
                        onCheckedChange = onToggleEnabled,
                        colors = SwitchDefaults.colors(checkedTrackColor = Clay),
                    )
                }

                // 基本信息
                SectionCard(title = "基本信息") {
                    if (manifest.description.isNotBlank()) {
                        Text(
                            text = manifest.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (manifest.author.isNotBlank()) {
                        MetaKeyValueRow("作者", manifest.author)
                    }
                    if (manifest.homepage.isNotBlank()) {
                        MetaKeyValueRow("主页", manifest.homepage)
                    }
                    if (manifest.endpoint.isNotBlank()) {
                        MetaKeyValueRow("端点", manifest.endpoint, monospace = true)
                    }
                    if (manifest.command.isNotBlank()) {
                        val cmdFull = buildString {
                            append(manifest.command)
                            if (manifest.args.isNotEmpty()) {
                                append(' ')
                                append(manifest.args.joinToString(" "))
                            }
                        }
                        MetaKeyValueRow("启动命令", cmdFull, monospace = true)
                    }
                    if (plugin.sourceFileName.isNotBlank()) {
                        MetaKeyValueRow("来源文件", plugin.sourceFileName)
                    }
                    if (plugin.sha256.isNotBlank()) {
                        MetaKeyValueRow("SHA-256", plugin.sha256.take(16) + "…", monospace = true)
                    }
                }

                // 权限清单（声明式）
                PermissionsSectionCard(permissions = manifest.permissions)

                // 密钥填写（permissions.secrets 或 env 中声明的密钥）
                if (secretKeys.isNotEmpty()) {
                    SectionCard(title = "密钥配置（环境变量）") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "保存后将通过热重载注入插件进程环境变量",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = if (revealSecrets) "隐藏明文" else "显示明文",
                                style = MaterialTheme.typography.labelSmall,
                                color = Clay,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .noRippleClickable { revealSecrets = !revealSecrets }
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        secretKeys.forEach { key ->
                            OutlinedTextField(
                                value = secretEdits[key].orEmpty(),
                                onValueChange = {
                                    secretEdits[key] = it
                                    secretFeedback = null
                                },
                                label = { Text(key, fontFamily = FontFamily.Monospace) },
                                singleLine = true,
                                visualTransformation = if (revealSecrets) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        secretFeedback?.let { (ok, msg) ->
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (ok) Clay else MaterialTheme.colorScheme.error,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ActionPillButton(
                                text = if (savingSecrets) "保存中…" else "保存密钥",
                                primary = true,
                                enabled = !savingSecrets,
                                onClick = {
                                    savingSecrets = true
                                    secretFeedback = null
                                    onSaveSecrets(secretEdits.toMap()) { ok, msg ->
                                        savingSecrets = false
                                        if (msg != null) {
                                            secretFeedback = ok to msg
                                        }
                                    }
                                },
                            )
                        }
                    }
                }

                // 工具列表（可执行插件）
                if (!manifest.isPureSkill) {
                    SectionCard(title = "已注册工具（${pluginTools.size}）") {
                        if (pluginTools.isEmpty()) {
                            val hint = if (!plugin.enabled) {
                                "插件当前已停用，启用后内核将热加载并注册 ${manifest.id}.* 工具。"
                            } else {
                                "暂未检测到 ${manifest.id}.* 工具。请确认网络连接、命令环境或所需密钥已配置妥当。"
                            }
                            Text(
                                text = hint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                pluginTools.forEach { toolName ->
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.surface)
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                    ) {
                                        Text(
                                            text = toolName,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 随包技能列表
                if (plugin.skillRefs.isNotEmpty()) {
                    SkillsSectionCard(
                        skills = plugin.skillRefs,
                        pluginEnabled = plugin.enabled,
                        showSyncBadge = true,
                    )
                }

                // README 说明
                if (plugin.readme.isNotBlank()) {
                    SectionCard(title = "插件说明（README）") {
                        Text(
                            text = if (showReadme) "收起说明" else "展开查看完整说明",
                            style = MaterialTheme.typography.labelSmall,
                            color = Clay,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .noRippleClickable { showReadme = !showReadme }
                                .padding(vertical = 2.dp),
                        )
                        if (showReadme) {
                            Text(
                                text = plugin.readme,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            // 底部操作区：更新 / 卸载
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionPillButton(
                    text = "导入新版本更新",
                    primary = false,
                    onClick = {
                        onDismiss()
                        onRequestUpdate()
                    },
                )
                Spacer(Modifier.weight(1f))
                if (confirmUninstall) {
                    ActionPillButton(
                        text = "取消",
                        primary = false,
                        onClick = { confirmUninstall = false },
                    )
                    ActionPillButton(
                        text = "确认卸载",
                        primary = true,
                        danger = true,
                        onClick = {
                            onDismiss()
                            onUninstall()
                        },
                    )
                } else {
                    ActionPillButton(
                        text = "卸载插件",
                        primary = false,
                        danger = true,
                        onClick = { confirmUninstall = true },
                    )
                }
            }
        }
    }
}

/**
 * 手动添加的 MCP 服务详情弹层（只有 `plugins.yaml` 条目、无 `install.json`）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ManualMcpDetailSheet(
    mcp: KernelApi.McpPluginInfo,
    registeredTools: List<String>,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmDelete by rememberSaveable(mcp.id) { mutableStateOf(false) }
    val pluginTools = remember(registeredTools, mcp.id) {
        val prefix = "${mcp.id}."
        registeredTools.filter { it.startsWith(prefix) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 1f),
    ) {
        com.hxy.codeforge.ui.common.SyncDialogStatusBar()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = mcp.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        PluginTypeBadge(type = mcp.type.ifBlank { "mcp" }, hasSkills = false)
                    }
                    Text(
                        text = "${mcp.id} · 手动添加",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = mcp.configured,
                    onCheckedChange = onToggleEnabled,
                    colors = SwitchDefaults.colors(checkedTrackColor = Clay),
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SectionCard(title = "连接配置") {
                val statusText = when {
                    mcp.loaded -> "运行中（已加载 ${pluginTools.size} 个工具）"
                    mcp.configured -> "已启用（等待加载或启动失败）"
                    else -> "未启用"
                }
                MetaKeyValueRow("状态", statusText)
                if (mcp.description.isNotBlank()) {
                    MetaKeyValueRow("描述", mcp.description)
                }
                if (mcp.endpoint.isNotBlank()) {
                    MetaKeyValueRow("端点", mcp.endpoint, monospace = true)
                }
                if (mcp.command.isNotBlank()) {
                    val cmd = buildString {
                        append(mcp.command)
                        if (mcp.args.isNotEmpty()) {
                            append(' ')
                            append(mcp.args.joinToString(" "))
                        }
                    }
                    MetaKeyValueRow("启动命令", cmd, monospace = true)
                }
                if (mcp.path.isNotBlank()) {
                    MetaKeyValueRow("路径", mcp.path, monospace = true)
                }
            }

            SectionCard(title = "已注册工具（${pluginTools.size}）") {
                if (pluginTools.isEmpty()) {
                    Text(
                        text = "当前没有已加载的 ${mcp.id}.* 工具",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        pluginTools.forEach { t ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    text = t,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (confirmDelete) {
                    ActionPillButton(
                        text = "取消",
                        primary = false,
                        onClick = { confirmDelete = false },
                    )
                    Spacer(Modifier.size(8.dp))
                    ActionPillButton(
                        text = "确认删除",
                        primary = true,
                        danger = true,
                        onClick = {
                            onDismiss()
                            onDelete()
                        },
                    )
                } else {
                    ActionPillButton(
                        text = "删除此服务",
                        primary = false,
                        danger = true,
                        onClick = { confirmDelete = true },
                    )
                }
            }
        }
    }
}

/**
 * 导入 `.cfplugin` 包后的安装前预览与确认弹层：
 * - 展示版本对比（新装 / 升级 / 重装 / 降级需勾选确认）；
 * - 展示权限声明与风险提示；
 * - 支持在安装前直接填写所需密钥；
 * - 对阶段 2 类型（`wasm` / `http`）显示「需内核支持」置灰并说明原因。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingPluginInstallSheet(
    pending: PendingPluginInstall,
    busy: Boolean,
    onConfirm: (allowDowngrade: Boolean, secrets: Map<String, String>) -> Unit,
    onCancel: () -> Unit,
) {
    val manifest = pending.manifest
    val secretKeys = manifest.secretKeys
    val secretInputs = remember(pending.stagedPath) {
        mutableStateMapOf<String, String>().apply {
            secretKeys.forEach { k -> put(k, manifest.env[k].orEmpty()) }
        }
    }
    var allowDowngrade by rememberSaveable(pending.stagedPath) { mutableStateOf(false) }
    var revealSecrets by rememberSaveable(pending.stagedPath) { mutableStateOf(false) }

    val canInstall = !busy &&
        !pending.requiresKernelSupport &&
        (pending.versionRelation != InstallVersionRelation.DOWNGRADE || allowDowngrade)

    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 1f),
    ) {
        com.hxy.codeforge.ui.common.SyncDialogStatusBar()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.86f)
                .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "导入插件包：${manifest.name}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        PluginTypeBadge(type = manifest.type, hasSkills = manifest.skills.isNotEmpty())
                    }
                    Text(
                        text = "${manifest.id} · v${manifest.version} · ${pending.sourceFileName}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 版本关系提示
                val relationText = when (pending.versionRelation) {
                    InstallVersionRelation.FRESH -> "首次安装此插件"
                    InstallVersionRelation.UPGRADE ->
                        "升级已安装插件：v${pending.existingVersion.orEmpty()} → v${manifest.version}"
                    InstallVersionRelation.REINSTALL ->
                        "当前已安装同版本 v${manifest.version}，继续将覆盖重装"
                    InstallVersionRelation.DOWNGRADE ->
                        "版本降级警告：当前已安装 v${pending.existingVersion.orEmpty()} 高于此包 v${manifest.version}"
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (pending.versionRelation == InstallVersionRelation.DOWNGRADE) {
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                            },
                        )
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = relationText,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = if (pending.versionRelation == InstallVersionRelation.DOWNGRADE) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    if (pending.versionRelation == InstallVersionRelation.DOWNGRADE) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "允许降级安装（用旧版替换已装新版）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = allowDowngrade,
                                onCheckedChange = { allowDowngrade = it },
                                colors = SwitchDefaults.colors(checkedTrackColor = Clay),
                            )
                        }
                    }
                }

                // 阶段 2 类型置灰说明
                if (pending.requiresKernelSupport) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f))
                            .padding(12.dp),
                    ) {
                        Text(
                            text = "需内核支持：${pending.kernelSupportReason}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                // 插件说明与元信息
                SectionCard(title = "清单摘要") {
                    if (manifest.description.isNotBlank()) {
                        Text(
                            text = manifest.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (manifest.author.isNotBlank()) MetaKeyValueRow("作者", manifest.author)
                    if (manifest.homepage.isNotBlank()) MetaKeyValueRow("主页", manifest.homepage)
                    if (manifest.endpoint.isNotBlank()) MetaKeyValueRow("端点", manifest.endpoint, monospace = true)
                    if (manifest.command.isNotBlank()) {
                        val cmd = (listOf(manifest.command) + manifest.args).joinToString(" ")
                        MetaKeyValueRow("命令", cmd, monospace = true)
                    }
                }

                // 权限与风险提示
                PermissionsSectionCard(permissions = manifest.permissions)

                // 随包技能与 B 类边界说明
                if (pending.skillRefs.isNotEmpty()) {
                    SkillsSectionCard(
                        skills = pending.skillRefs,
                        pluginEnabled = true,
                        showSyncBadge = false,
                    )
                }

                // 可选安装期填写密钥
                if (secretKeys.isNotEmpty() && !pending.requiresKernelSupport) {
                    SectionCard(title = "预填所需密钥（也可安装后在详情页填写）") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "声明了 ${secretKeys.size} 项环境变量密钥",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = if (revealSecrets) "隐藏" else "显示",
                                style = MaterialTheme.typography.labelSmall,
                                color = Clay,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .noRippleClickable { revealSecrets = !revealSecrets }
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        secretKeys.forEach { key ->
                            OutlinedTextField(
                                value = secretInputs[key].orEmpty(),
                                onValueChange = { secretInputs[key] = it },
                                label = { Text(key, fontFamily = FontFamily.Monospace) },
                                singleLine = true,
                                visualTransformation = if (revealSecrets) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                if (pending.readme.isNotBlank()) {
                    SectionCard(title = "随包说明（README.md）") {
                        Text(
                            text = pending.readme.take(1_200),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionPillButton(
                    text = "取消",
                    primary = false,
                    onClick = onCancel,
                )
                Spacer(Modifier.weight(1f))
                val confirmLabel = when {
                    pending.requiresKernelSupport -> "需内核支持（暂不可装）"
                    busy -> "安装中…"
                    pending.versionRelation == InstallVersionRelation.UPGRADE -> "确认升级"
                    pending.versionRelation == InstallVersionRelation.REINSTALL -> "确认重装"
                    pending.versionRelation == InstallVersionRelation.DOWNGRADE -> "确认降级安装"
                    else -> "确认安装"
                }
                ActionPillButton(
                    text = confirmLabel,
                    primary = true,
                    enabled = canInstall,
                    onClick = {
                        if (canInstall) {
                            onConfirm(allowDowngrade, secretInputs.toMap())
                        }
                    },
                )
            }
        }
    }
}

/**
 * 插件类型徽标：区分「纯文本」「可执行」「可执行+技能」「需内核支持」。
 */
@Composable
internal fun PluginTypeBadge(type: String, hasSkills: Boolean) {
    val (label, isUnsupported) = when (type) {
        "skill" -> "纯文本技能" to false
        "mcp" -> (if (hasSkills) "MCP + 技能" else "可执行 · stdio") to false
        "mcp-http" -> (if (hasSkills) "HTTP + 技能" else "可执行 · HTTP") to false
        "wasm", "http" -> "需内核支持 ($type)" to true
        else -> type.ifBlank { "可执行" } to false
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isUnsupported) {
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                } else {
                    Clay.copy(alpha = 0.14f)
                },
            )
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = if (isUnsupported) MaterialTheme.colorScheme.error else Clay,
        )
    }
}

@Composable
private fun PermissionsSectionCard(permissions: PluginPermissions) {
    SectionCard(title = "权限声明与风险提示") {
        val netText = if (permissions.network.isEmpty()) "未声明外部域名" else permissions.network.joinToString(", ")
        MetaKeyValueRow("网络域名", netText, monospace = permissions.network.isNotEmpty())
        MetaKeyValueRow("文件范围", permissions.filesystem.ifBlank { "none" }, monospace = true)
        if (permissions.secrets.isNotEmpty()) {
            MetaKeyValueRow("所需密钥", permissions.secrets.joinToString(", "), monospace = true)
        }
        MetaKeyValueRow("敏感操作审批", if (permissions.requiresApproval) "声明需要审批" else "未声明")
        if (permissions.allowedActions.isNotEmpty()) {
            MetaKeyValueRow("允许动作", permissions.allowedActions.joinToString(", "), monospace = true)
        }
        Text(
            text = "风险提示：阶段 1 权限清单为包作者声明式信息（用于安装前审阅），请仅安装来源可信的插件包。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SkillsSectionCard(
    skills: List<PluginSkillRef>,
    pluginEnabled: Boolean,
    showSyncBadge: Boolean,
) {
    SectionCard(title = "随包纯文本技能（${skills.size}）") {
        skills.forEach { s ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = s.displayName.ifBlank { s.slug },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "@${s.slug}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = Clay,
                    )
                    if (showSyncBadge) {
                        val syncedText = when {
                            !pluginEnabled -> "已停用"
                            s.syncedInWorkspace -> "当前工作区已铺"
                            else -> "待同步"
                        }
                        Text(
                            text = syncedText,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = if (s.syncedInWorkspace && pluginEnabled) {
                                Clay
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                if (s.description.isNotBlank()) {
                    Text(
                        text = s.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (s.triggers.isNotEmpty()) {
                    Text(
                        text = "触发词：${s.triggers.joinToString("、")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            text = SKILL_BOUNDARY_NOTICE,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        content()
    }
}

@Composable
private fun MetaKeyValueRow(
    label: String,
    value: String,
    monospace: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun ActionPillButton(
    text: String,
    primary: Boolean,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val bg = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant
        danger && primary -> MaterialTheme.colorScheme.error
        danger -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
        primary -> Clay
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        danger && primary -> MaterialTheme.colorScheme.onError
        danger -> MaterialTheme.colorScheme.error
        primary -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .noRippleClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = fg,
        )
    }
}
