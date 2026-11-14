package com.hxy.codeforge.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hxy.codeforge.model.PermissionMode
import com.hxy.codeforge.model.ProjectItem
import com.hxy.codeforge.ui.ChatViewModel
import com.hxy.codeforge.ui.archiveProject
import com.hxy.codeforge.ui.common.Glyph
import com.hxy.codeforge.ui.common.GlyphIcon
import com.hxy.codeforge.ui.common.SyncDialogStatusBar
import com.hxy.codeforge.ui.common.noRippleClickable
import com.hxy.codeforge.ui.deleteProject
import com.hxy.codeforge.ui.renameProject
import com.hxy.codeforge.ui.theme.Clay
import com.hxy.codeforge.ui.theme.SerifDisplay

/** 目录末段作为项目名默认值；根目录等极端情况退回整条路径。 */
private fun dirName(path: String): String {
    val trimmed = path.trim().trimEnd('/')
    if (trimmed.isEmpty()) return ""
    val tail = trimmed.substringAfterLast('/')
    return tail.ifBlank { trimmed }
}

/**
 * 新建项目面板（大幅弹层）。
 *
 * 三件事：项目名、工作区路径、默认权限。
 *  - 项目名的默认值取**目录末段** —— 选完文件夹改名字是最多余的一步；
 *  - 「选择文件夹」把文件树面板顶上来（整屏），选完带路径回到这里；
 *  - 权限三档直接作为内核 /api/perm 的初值（[PermissionMode] 与内核小写枚举一一对应）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewProjectPanel(
    initialWorkspace: String,
    onCreate: (name: String, workspacePath: String, permission: PermissionMode) -> Unit,
    onPickFolder: () -> Unit,
    onDismiss: () -> Unit,
    defaultPermission: PermissionMode = PermissionMode.Ask,
) {
    // key 用 initialWorkspace：从文件树选完路径回来时，名称/路径都要跟着重置
    var workspace by rememberSaveable(initialWorkspace) { mutableStateOf(initialWorkspace) }
    var name by rememberSaveable(initialWorkspace) { mutableStateOf(dirName(initialWorkspace)) }
    var permission by rememberSaveable { mutableStateOf(defaultPermission.name) }

    // 工作区和名称都是可选项：不选目录时落成独立工作区，留空名称时自动兜底（目录名或「新项目」）
    val canCreate = true

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 1f),
    ) {
        SyncDialogStatusBar()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "新建项目",
                    style = SerifDisplay.copy(fontSize = 21.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
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

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "项目名",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    singleLine = true,
                    placeholder = {
                        Text(if (workspace.isBlank()) "留空自动命名为「新项目」" else "默认取目录名")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = "工作区（项目目录）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 路径行：整行可点 = 打开文件树选目录
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .noRippleClickable(onClick = onPickFolder)
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    GlyphIcon(
                        glyph = Glyph.Folder,
                        size = 15.dp,
                        color = if (workspace.isBlank()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            Clay
                        },
                    )
                    Text(
                        // 空路径不是「还没选」，而是一个合法选择：自动建独立工作区
                        text = workspace.ifBlank { "不选择（自动创建独立工作区）" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (workspace.isBlank()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "浏览",
                        style = MaterialTheme.typography.labelSmall,
                        color = Clay,
                    )
                }

                Text(
                    text = "默认权限",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PermissionMode.entries.forEach { mode ->
                        val selected = permission == mode.name
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (selected) Clay else MaterialTheme.colorScheme.surfaceVariant,
                                )
                                .noRippleClickable { permission = mode.name }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = mode.label,
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

                Text(
                    text = "不选文件夹时，应用会在私有目录里自动建一个专属工作区。" +
                        "项目按工作区分组；切换项目不会中断其它项目里正在跑的会话。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(
                        if (canCreate) Clay else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .noRippleClickable(enabled = canCreate) {
                        if (canCreate) {
                            onCreate(
                                name.trim(),
                                workspace.trim(),
                                PermissionMode.entries.first { it.name == permission },
                            )
                        }
                    }
                    .padding(vertical = 13.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "创建项目",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (canCreate) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/**
 * 项目操作弹层：重命名 / 归档 / 删除。
 *
 * 交互沿用会话的 [SessionActionsSheet]（行式动作 + 二次确认），
 * 让「项目的归档删除复用会话的 UI」这件事在两处看起来是同一套控件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectActionsSheet(
    project: ProjectItem,
    vm: ChatViewModel,
    onDismiss: () -> Unit,
) {
    var renaming by rememberSaveable(project.workspace) { mutableStateOf(false) }
    var rename by rememberSaveable(project.workspace) { mutableStateOf(project.name) }
    var confirmDelete by rememberSaveable(project.workspace) { mutableStateOf(false) }

    val danger = MaterialTheme.colorScheme.error

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 1f),
    ) {
        SyncDialogStatusBar()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = project.name.ifBlank { dirName(project.workspace) },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            )
            Text(
                text = project.workspace,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp),
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (renaming) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = rename,
                        onValueChange = { rename = it.take(40) },
                        label = { Text("项目名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SheetPill("保存", primary = true) {
                            vm.renameProject(project.workspace, rename.trim())
                            onDismiss()
                        }
                        SheetPill("取消", primary = false) {
                            rename = project.name
                            renaming = false
                        }
                    }
                }
            } else {
                ProjectActionRow(Glyph.Copy, "重命名") { renaming = true }
            }

            // 归档 = 从侧栏消失（取消归档可在设置页归档管理里做；这里也留一份，
            // 免得用户点到归档后找不到回来的路）
            ProjectActionRow(
                Glyph.Folder,
                if (project.archived) "取消归档（恢复显示）" else "归档（从侧栏隐藏，可恢复）",
            ) {
                vm.archiveProject(project.workspace, !project.archived)
                onDismiss()
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = 6.dp),
            )

            ProjectActionRow(
                glyph = Glyph.Close,
                label = if (confirmDelete) "再点一次确认删除（不可恢复）" else "删除项目",
                tint = danger,
                highlighted = confirmDelete,
            ) {
                if (confirmDelete) {
                    vm.deleteProject(project.workspace)
                    onDismiss()
                } else {
                    confirmDelete = true
                }
            }
        }
    }
}

/** 一行操作：图标 + 文字，44dp 以上行高（与会话操作弹层一致）。 */
@Composable
private fun ProjectActionRow(
    glyph: Glyph,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (highlighted) MaterialTheme.colorScheme.errorContainer else Color.Transparent,
            )
            .noRippleClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GlyphIcon(glyph = glyph, size = 17.dp, color = tint, strokeWidth = 1.7.dp)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = tint,
        )
    }
}

/** 小胶囊按钮（重命名的保存/取消）。 */
@Composable
private fun SheetPill(label: String, primary: Boolean, onClick: () -> Unit) {
    val bg = if (primary) Clay else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (primary) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .noRippleClickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}
