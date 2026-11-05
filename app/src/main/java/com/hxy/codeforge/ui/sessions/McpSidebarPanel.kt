package com.hxy.codeforge.ui.sessions

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hxy.codeforge.ui.ChatViewModel
import com.hxy.codeforge.ui.common.Glyph
import com.hxy.codeforge.ui.common.GlyphIcon
import com.hxy.codeforge.ui.common.noRippleClickable
import com.hxy.codeforge.ui.theme.Clay
import com.hxy.codeforge.ui.theme.SerifDisplay

/**
 * 侧栏上的 MCP 面板（铺满整个侧栏 / 屏幕）。
 *
 * 原来侧栏里的「Code」是个占位导航；用户要求改成「MCP」：
 * 点进来就看到全部 MCP 服务（内核 /api/plugins），可启停、可添加，
 * 左上角返回键回到会话侧栏。
 *
 * 启停读的是 `configured`（配置态），不是 `loaded`（运行态）——
 * 内核加载失败时 loaded 恒为 false，开关跟着它走会点完立刻弹回。
 */
@Composable
fun McpSidebarPanel(
    vm: ChatViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val plugins = state.mcpPlugins

    var adding by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }

    var fName by rememberSaveable { mutableStateOf("") }
    var fDisplay by rememberSaveable { mutableStateOf("") }
    var fHttp by rememberSaveable { mutableStateOf(false) }
    var fCommand by rememberSaveable { mutableStateOf("") }
    var fArgs by rememberSaveable { mutableStateOf("") }
    var fEndpoint by rememberSaveable { mutableStateOf("") }
    var fDesc by rememberSaveable { mutableStateOf("") }

    val enabledCount = plugins.count { it.configured }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左上角返回：回会话侧栏
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
                    text = "MCP 工具",
                    style = SerifDisplay.copy(fontSize = 19.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "$enabledCount/${plugins.size} 个服务已启用",
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
                    text = "MCP 服务把外部工具接给模型。启用后内核热加载，稍等片刻生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (plugins.isEmpty()) {
                item(key = "__empty__") {
                    Text(
                        text = "还没有 MCP 服务。点下面「添加 MCP 服务」接入一个。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(plugins, key = { it.id }) { plugin ->
                PluginRow(
                    name = plugin.name,
                    configured = plugin.configured,
                    loaded = plugin.loaded,
                    pendingDelete = pendingDelete == plugin.id,
                    onToggle = { vm.setMcpPlugin(plugin.id, it) },
                    onDelete = {
                        if (pendingDelete == plugin.id) {
                            vm.deleteMcpPlugin(plugin.id)
                            pendingDelete = null
                        } else {
                            pendingDelete = plugin.id
                        }
                    },
                )
            }

            item(key = "__add__") {
                if (!adding) {
                    Box(
                        modifier = Modifier
                            .padding(top = 4.dp)
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
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                        .background(
                                            if (sel) Clay else MaterialTheme.colorScheme.surfaceVariant,
                                        )
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
                                label = { Text("参数（空格分隔）") },
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
                            val canAdd = fName.trim().isNotEmpty() &&
                                (if (fHttp) fEndpoint.trim().isNotEmpty() else fCommand.trim().isNotEmpty())
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(
                                        if (canAdd) Clay else MaterialTheme.colorScheme.surfaceVariant,
                                    )
                                    .noRippleClickable(enabled = canAdd) {
                                        if (canAdd) {
                                            vm.addMcpPlugin(
                                                name = fName.trim(),
                                                displayName = fDisplay.trim(),
                                                type = if (fHttp) "mcp-http" else "mcp",
                                                command = fCommand.trim(),
                                                args = fArgs.trim()
                                                    .split(' ')
                                                    .filter { it.isNotBlank() },
                                                endpoint = fEndpoint.trim(),
                                                description = fDesc.trim(),
                                            )
                                            adding = false
                                            fName = ""
                                            fDisplay = ""
                                            fCommand = ""
                                            fArgs = ""
                                            fEndpoint = ""
                                            fDesc = ""
                                        }
                                    }
                                    .padding(horizontal = 18.dp, vertical = 9.dp),
                            ) {
                                Text(
                                    text = "添加",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (canAdd) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
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
            }
        }
    }
}

/** 一个 MCP 服务：名称 + 运行状态 + 删除 + 启停开关。 */
@Composable
private fun PluginRow(
    name: String,
    configured: Boolean,
    loaded: Boolean,
    pendingDelete: Boolean,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val status = when {
        loaded -> "运行中"
        configured -> "已启用（等待加载）"
        else -> "未启用"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = status,
                style = MaterialTheme.typography.labelSmall,
                color = if (loaded) Clay else MaterialTheme.colorScheme.onSurfaceVariant,
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
            checked = configured,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(checkedTrackColor = Clay),
        )
    }
}
