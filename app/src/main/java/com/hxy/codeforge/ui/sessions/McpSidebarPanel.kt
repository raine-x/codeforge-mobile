package com.hxy.codeforge.ui.sessions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hxy.codeforge.core.KernelApi
import com.hxy.codeforge.model.BuiltinPluginInfo
import com.hxy.codeforge.model.InstalledPlugin
import com.hxy.codeforge.ui.ChatViewModel
import com.hxy.codeforge.ui.common.Glyph
import com.hxy.codeforge.ui.common.GlyphIcon
import com.hxy.codeforge.ui.common.noRippleClickable
import com.hxy.codeforge.ui.theme.Clay
import com.hxy.codeforge.ui.theme.SerifDisplay

/**
 * 侧栏「插件」管理面板（铺满整个侧栏 / 屏幕）。
 *
 * 统一承载三类扩展形态（§1.3 / §2.4）：
 * 1. **已安装插件包**（`.cfplugin`，有 `install.json` 记录）：支持可执行包（`mcp` / `mcp-http`）与
 *    纯文本技能包（`skill`）同包共存，支持导入、启停、详情、密钥配置、版本升级与卸载；
 * 2. **手动添加的 MCP**（仅 `config/plugins.yaml` 条目）：保留无包快速接入表单；
 * 3. **内置功能**（C 类编译进内核的能力）：只读展示状态与触发词，指向设置页管理。
 */
@Composable
fun McpSidebarPanel(
    vm: ChatViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        vm.refreshInstalledPlugins()
    }

    val installedPlugins = state.installedPlugins
    val installedIds = remember(installedPlugins) { installedPlugins.map { it.id }.toSet() }
    val mcpById = remember(state.mcpPlugins) { state.mcpPlugins.associateBy { it.id } }
    val manualPlugins = remember(state.mcpPlugins, installedIds) {
        state.mcpPlugins.filter { it.id !in installedIds }
    }
    val builtinPlugins = state.builtinPluginInfos
    val registeredTools = state.kernelConfig?.tools.orEmpty()

    var addingManual by rememberSaveable { mutableStateOf(false) }
    var pendingDeleteManual by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedInstalledId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedManualId by rememberSaveable { mutableStateOf<String?>(null) }

    var fName by rememberSaveable { mutableStateOf("") }
    var fDisplay by rememberSaveable { mutableStateOf("") }
    var fHttp by rememberSaveable { mutableStateOf(false) }
    var fCommand by rememberSaveable { mutableStateOf("") }
    var fArgs by rememberSaveable { mutableStateOf("") }
    var fEndpoint by rememberSaveable { mutableStateOf("") }
    var fDesc by rememberSaveable { mutableStateOf("") }

    // SAF 选取 .cfplugin（本质是 zip，兼容各厂商文件管理器的 MIME 识别）
    val packagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            vm.installPlugin(uri)
        }
    }

    val launchImport: () -> Unit = {
        packagePicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
    }

    val activeInstalledCount = installedPlugins.count { it.enabled }
    val activeManualCount = manualPlugins.count { it.configured }

    // 选中壁纸时 theme.background 会被设为 Transparent；插件面板是全屏叠加层，必须保持不透明实底，避免透出底层侧栏/聊天内容。
    val panelBg = MaterialTheme.colorScheme.background.let {
        if (it.alpha < 1f) MaterialTheme.colorScheme.surface.copy(alpha = 1f) else it
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(panelBg)
            // 吞掉空白处点击，避免穿透到底层侧栏/聊天页触发误点
            .noRippleClickable {},
    ) {
        // 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .noRippleClickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                GlyphIcon(
                    glyph = Glyph.Back,
                    size = 18.dp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
            ) {
                Text(
                    text = "插件",
                    style = SerifDisplay.copy(fontSize = 19.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "已安装 $activeInstalledCount/${installedPlugins.size} · 手动 MCP $activeManualCount/${manualPlugins.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "__hint__") {
                Text(
                    text = "支持导入 .cfplugin 插件包（可执行工具 + 纯文本技能），或直接手动接入 MCP 服务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 顶部双操作按钮：+ 导入插件包 / + 手动添加 MCP
            item(key = "__top_actions__") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(Clay)
                            .noRippleClickable(enabled = !state.pluginBusy, onClick = launchImport)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = if (state.pluginBusy) "处理中…" else "+ 导入插件包",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .noRippleClickable { addingManual = !addingManual }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = if (addingManual) "收起手动表单" else "+ 手动添加 MCP",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            // 手动添加 MCP 展开表单
            if (addingManual) {
                item(key = "__manual_form__") {
                    ManualAddMcpForm(
                        fName = fName,
                        onNameChange = { fName = it },
                        fDisplay = fDisplay,
                        onDisplayChange = { fDisplay = it },
                        fHttp = fHttp,
                        onHttpChange = { fHttp = it },
                        fCommand = fCommand,
                        onCommandChange = { fCommand = it },
                        fArgs = fArgs,
                        onArgsChange = { fArgs = it },
                        fEndpoint = fEndpoint,
                        onEndpointChange = { fEndpoint = it },
                        fDesc = fDesc,
                        onDescChange = { fDesc = it },
                        onSubmit = {
                            vm.addMcpPlugin(
                                name = fName.trim(),
                                displayName = fDisplay.trim(),
                                type = if (fHttp) "mcp-http" else "mcp",
                                command = fCommand.trim(),
                                args = fArgs.trim().split(' ').filter { it.isNotBlank() },
                                endpoint = fEndpoint.trim(),
                                description = fDesc.trim(),
                            )
                            addingManual = false
                            fName = ""
                            fDisplay = ""
                            fCommand = ""
                            fArgs = ""
                            fEndpoint = ""
                            fDesc = ""
                        },
                        onCancel = { addingManual = false },
                    )
                }
            }

            // ---------------- 分组 1：已安装插件包 ----------------
            item(key = "__header_installed__") {
                GroupSectionHeader(
                    title = "已安装插件包（${installedPlugins.size}）",
                    subtitle = "包含可执行工具包与纯文本技能包，点行查看权限、密钥与工具详情",
                )
            }

            if (installedPlugins.isEmpty()) {
                item(key = "__empty_installed__") {
                    Text(
                        text = "尚未导入插件包。点上方「+ 导入插件包」选择 .cfplugin 文件即可安装。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            } else {
                items(installedPlugins, key = { "inst-${it.id}" }) { plugin ->
                    InstalledPluginRow(
                        plugin = plugin,
                        mcpStatus = mcpById[plugin.id],
                        onClick = { selectedInstalledId = plugin.id },
                        onToggle = { vm.setPluginEnabled(plugin.id, it) },
                    )
                }
            }

            // ---------------- 分组 2：手动添加的 MCP ----------------
            item(key = "__header_manual__") {
                GroupSectionHeader(
                    title = "手动添加（${manualPlugins.size}）",
                    subtitle = "直接写入内核配置的标准 MCP 服务",
                )
            }

            if (manualPlugins.isEmpty()) {
                item(key = "__empty_manual__") {
                    Text(
                        text = "没有手动添加的 MCP 服务。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            } else {
                items(manualPlugins, key = { "man-${it.id}" }) { plugin ->
                    ManualPluginRow(
                        plugin = plugin,
                        pendingDelete = pendingDeleteManual == plugin.id,
                        onClick = { selectedManualId = plugin.id },
                        onToggle = { vm.setMcpPlugin(plugin.id, it) },
                        onDelete = {
                            if (pendingDeleteManual == plugin.id) {
                                vm.deleteMcpPlugin(plugin.id)
                                pendingDeleteManual = null
                            } else {
                                pendingDeleteManual = plugin.id
                            }
                        },
                    )
                }
            }

            // ---------------- 分组 3：内置功能（C 类，只读） ----------------
            if (builtinPlugins.isNotEmpty()) {
                item(key = "__header_builtin__") {
                    GroupSectionHeader(
                        title = "内置功能（${builtinPlugins.size} · 只读）",
                        subtitle = "编译进内核的原生模式，可在「设置 → 内置插件」中启停",
                    )
                }
                items(builtinPlugins, key = { "builtin-${it.id}" }) { bp ->
                    BuiltinPluginReadOnlyRow(bp)
                }
            }
        }
    }

    // 弹层 1：已安装插件详情页
    val detailPlugin = selectedInstalledId?.let { id ->
        installedPlugins.firstOrNull { it.id == id }
    }
    if (detailPlugin != null) {
        PluginDetailSheet(
            plugin = detailPlugin,
            mcpStatus = mcpById[detailPlugin.id],
            registeredTools = registeredTools,
            onToggleEnabled = { vm.setPluginEnabled(detailPlugin.id, it) },
            onSaveSecrets = { secrets, onDone ->
                vm.savePluginSecrets(detailPlugin.id, secrets, onDone)
            },
            onRequestUpdate = launchImport,
            onUninstall = { vm.uninstallPlugin(detailPlugin.id) },
            onDismiss = { selectedInstalledId = null },
        )
    }

    // 弹层 2：手动添加 MCP 详情页
    val detailManual = selectedManualId?.let { id ->
        manualPlugins.firstOrNull { it.id == id }
    }
    if (detailManual != null) {
        ManualMcpDetailSheet(
            mcp = detailManual,
            registeredTools = registeredTools,
            onToggleEnabled = { vm.setMcpPlugin(detailManual.id, it) },
            onDelete = { vm.deleteMcpPlugin(detailManual.id) },
            onDismiss = { selectedManualId = null },
        )
    }

    // 弹层 3：导入 .cfplugin 安装前预览确认页
    val pendingInstall = state.pendingPluginInstall
    if (pendingInstall != null) {
        PendingPluginInstallSheet(
            pending = pendingInstall,
            busy = state.pluginBusy,
            onConfirm = { allowDowngrade, secrets ->
                vm.confirmPluginInstall(allowDowngrade = allowDowngrade, secrets = secrets)
            },
            onCancel = { vm.cancelPendingPluginInstall() },
        )
    }
}

@Composable
private fun GroupSectionHeader(
    title: String,
    subtitle: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 已安装插件包行：
 * 显示名 + 类型徽标（可执行 / 纯文本 / 需内核支持）+ `id · v版本` + 运行/铺放状态 + 详情入口 + 启停开关。
 */
@Composable
private fun InstalledPluginRow(
    plugin: InstalledPlugin,
    mcpStatus: KernelApi.McpPluginInfo?,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val manifest = plugin.manifest
    val statusText = when {
        !plugin.enabled -> "未启用"
        manifest.isPureSkill -> {
            val syncedCount = plugin.skillRefs.count { it.syncedInWorkspace }
            if (syncedCount > 0) {
                "已启用 · 已同步 $syncedCount/${plugin.skillRefs.size} 个技能"
            } else {
                "已启用 · ${plugin.skillRefs.size} 个纯文本技能"
            }
        }
        mcpStatus?.loaded == true -> "运行中"
        mcpStatus?.configured == true -> "已启用（等待加载）"
        else -> "已启用"
    }
    val statusActive = plugin.enabled && (manifest.isPureSkill || mcpStatus?.loaded == true)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .noRippleClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = manifest.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                PluginTypeBadge(type = manifest.type, hasSkills = manifest.skills.isNotEmpty())
            }
            Text(
                text = "${manifest.id} · v${manifest.version} · $statusText",
                style = MaterialTheme.typography.labelSmall,
                color = if (statusActive) Clay else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (manifest.description.isNotBlank()) {
                Text(
                    text = manifest.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = "详情",
            style = MaterialTheme.typography.labelSmall,
            color = Clay,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .noRippleClickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        Switch(
            checked = plugin.enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(checkedTrackColor = Clay),
        )
    }
}

/**
 * 手动添加的 MCP 服务行：名称 + 类型徽标 + 状态 + 删除确认 + 启停开关。
 */
@Composable
private fun ManualPluginRow(
    plugin: KernelApi.McpPluginInfo,
    pendingDelete: Boolean,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val status = when {
        plugin.loaded -> "运行中"
        plugin.configured -> "已启用（等待加载）"
        else -> "未启用"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .noRippleClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = plugin.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                PluginTypeBadge(type = plugin.type.ifBlank { "mcp" }, hasSkills = false)
            }
            Text(
                text = "${plugin.id} · $status",
                style = MaterialTheme.typography.labelSmall,
                color = if (plugin.loaded) Clay else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = if (pendingDelete) "确认删除" else "删除",
            style = MaterialTheme.typography.labelSmall,
            color = if (pendingDelete) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .noRippleClickable(onClick = onDelete)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        Switch(
            checked = plugin.configured,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(checkedTrackColor = Clay),
        )
    }
}

/**
 * 内置功能（C 类）只读展示行。
 */
@Composable
private fun BuiltinPluginReadOnlyRow(bp: BuiltinPluginInfo) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = bp.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (bp.triggers.isNotEmpty()) {
                    Text(
                        text = bp.triggers.first(),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = Clay,
                    )
                }
            }
            if (bp.purpose.isNotBlank()) {
                Text(
                    text = bp.purpose,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = when {
                !bp.enabled -> "已关闭"
                bp.allowModify == true -> "已开启 · 含修改"
                else -> "已开启"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (bp.enabled) Clay else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ManualAddMcpForm(
    fName: String,
    onNameChange: (String) -> Unit,
    fDisplay: String,
    onDisplayChange: (String) -> Unit,
    fHttp: Boolean,
    onHttpChange: (Boolean) -> Unit,
    fCommand: String,
    onCommandChange: (String) -> Unit,
    fArgs: String,
    onArgsChange: (String) -> Unit,
    fEndpoint: String,
    onEndpointChange: (String) -> Unit,
    fDesc: String,
    onDescChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "手动接入 MCP 服务",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        OutlinedTextField(
            value = fName,
            onValueChange = onNameChange,
            label = { Text("名称（唯一，用作工具前缀）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = fDisplay,
            onValueChange = onDisplayChange,
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
                        .background(
                            if (sel) Clay else MaterialTheme.colorScheme.surfaceVariant,
                        )
                        .noRippleClickable { onHttpChange(http) }
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
                onValueChange = onEndpointChange,
                label = { Text("端点 URL（https://…）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            OutlinedTextField(
                value = fCommand,
                onValueChange = onCommandChange,
                label = { Text("启动命令（如 npx）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = fArgs,
                onValueChange = onArgsChange,
                label = { Text("参数（空格分隔）") },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        OutlinedTextField(
            value = fDesc,
            onValueChange = onDescChange,
            label = { Text("描述（可选）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val canAdd = fName.trim().isNotEmpty() &&
                (if (fHttp) fEndpoint.trim().isNotEmpty() else fCommand.trim().isNotEmpty())
            ActionPillButton(
                text = "添加",
                primary = true,
                enabled = canAdd,
                onClick = { if (canAdd) onSubmit() },
            )
            ActionPillButton(
                text = "取消",
                primary = false,
                onClick = onCancel,
            )
        }
    }
}
