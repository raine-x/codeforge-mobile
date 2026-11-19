package com.hxy.codeforge.ui.settings

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.hxy.codeforge.core.EnvKit
import com.hxy.codeforge.phone.PhoneAppSkill
import com.hxy.codeforge.phone.PhoneAppSkillSource
import com.hxy.codeforge.phone.PhoneArtifact
import com.hxy.codeforge.phone.PhoneArtifactType
import com.hxy.codeforge.ui.common.noRippleClickable
import com.hxy.codeforge.ui.theme.Clay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class InstalledAppEntry(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
)

/**
 * 手机操控「应用黑名单」独立选择弹窗：
 * 后台线程加载全部已安装应用，支持按名称/包名搜索与系统应用筛选，LazyColumn 流畅滚动。
 */
@Composable
internal fun BlacklistPickerDialog(
    context: Context,
    excludedPackages: Set<String>,
    onTogglePackage: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var apps by remember { mutableStateOf<List<InstalledAppEntry>>(emptyList()) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.getInstalledApplications(0)
                .map { info ->
                    val label = runCatching { pm.getApplicationLabel(info)?.toString() }.getOrNull()
                        ?.takeIf { it.isNotBlank() } ?: info.packageName
                    val isSys = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    InstalledAppEntry(info.packageName, label, isSys)
                }
                .sortedWith(
                    compareByDescending<InstalledAppEntry> { it.packageName in excludedPackages }
                        .thenBy { it.isSystem }
                        .thenBy { it.label.lowercase() },
                )
        }
        loading = false
    }

    val filtered = remember(apps, query, showSystem, excludedPackages) {
        val q = query.trim()
        apps.filter { entry ->
            val inWhitelistOrExcluded = !entry.isSystem || showSystem || entry.packageName in excludedPackages
            val matches = q.isEmpty() ||
                entry.label.contains(q, ignoreCase = true) ||
                entry.packageName.contains(q, ignoreCase = true)
            inWhitelistOrExcluded && matches
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 1f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        title = {
            Column {
                Text(
                    text = "应用黑名单（已选 ${excludedPackages.size} 个）",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "进入已勾选应用时将自动停止所有屏幕观察与操控动作。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索应用名称或包名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "显示系统组件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = showSystem,
                        onCheckedChange = { showSystem = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = Clay),
                    )
                }

                if (loading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(26.dp), color = Clay)
                    }
                } else if (filtered.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "没有匹配的应用",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(filtered, key = { it.packageName }) { app ->
                            val checked = app.packageName in excludedPackages
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (checked) Clay.copy(alpha = 0.1f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                    )
                                    .noRippleClickable { onTogglePackage(app.packageName, !checked) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { onTogglePackage(app.packageName, it) },
                                    colors = CheckboxDefaults.colors(checkedColor = Clay),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = app.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = app.packageName + if (app.isSystem) " · 系统" else "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成", color = Clay, fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

/**
 * 产物详情预览与操作弹窗（查看截图大图、导出到 Download、系统打开、分享或删除）。
 */
@Composable
internal fun ArtifactPreviewDialog(
    artifact: PhoneArtifact,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val thumb by remember(artifact.absolutePath) {
        mutableStateOf(
            if (artifact.type == PhoneArtifactType.SCREENSHOT && File(artifact.absolutePath).isFile) {
                runCatching {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                    BitmapFactory.decodeFile(artifact.absolutePath, opts)
                }.getOrNull()
            } else {
                null
            },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 1f),
        title = {
            Text(
                text = "${artifact.type.label} · ${artifact.fileName}",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                thumb?.let { bmp: Bitmap ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = artifact.fileName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
                    )
                }
                val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
                Text(
                    text = buildString {
                        appendLine("路径：${artifact.workspacePath}")
                        appendLine("大小：${EnvKit.formatFileSize(artifact.sizeBytes)}")
                        if (artifact.width > 0 && artifact.height > 0) {
                            appendLine("尺寸：${artifact.width} × ${artifact.height}")
                        }
                        if (artifact.durationMs > 0) {
                            appendLine("时长：${String.format(Locale.US, "%.1f", artifact.durationMs / 1000.0)} 秒")
                        }
                        append("生成时间：${fmt.format(Date(artifact.createdAtMs))}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ActionChip("系统打开", Modifier.weight(1f), onOpen)
                    ActionChip("分享", Modifier.weight(1f), onShare)
                    ActionChip("导出下载", Modifier.weight(1f), onExport)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        dismissButton = {
            TextButton(onClick = onDelete) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
    )
}

@Composable
private fun ActionChip(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .noRippleClickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 应用技能库管理弹窗：浏览已加载的内置/自定义/工作区 App 技能，支持检索、启停、查看/编辑与新建。
 */
@Composable
internal fun AppSkillLibraryDialog(
    skills: List<PhoneAppSkill>,
    onToggleEnabled: (id: String, enabled: Boolean) -> Unit,
    onSaveSkill: (
        id: String,
        name: String,
        description: String,
        packages: List<String>,
        appNames: List<String>,
        tags: List<String>,
        priority: Int,
        content: String,
    ) -> Unit,
    onDeleteCustomSkill: (id: String) -> Unit,
    onImportClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var editingSkill by remember { mutableStateOf<PhoneAppSkill?>(null) }
    var creatingNew by remember { mutableStateOf(false) }

    val filtered = remember(skills, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) skills
        else skills.filter {
            it.id.lowercase().contains(q) ||
                it.name.lowercase().contains(q) ||
                it.description.lowercase().contains(q) ||
                it.packages.any { p -> p.lowercase().contains(q) } ||
                it.appNames.any { n -> n.lowercase().contains(q) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 1f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "应用技能库 (${skills.size})",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(onClick = { creatingNew = true }) {
                    Text("+ 新建", color = Clay, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("按应用名、包名或关键词筛选") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (filtered.isEmpty()) {
                    Text(
                        text = "未找到匹配的应用技能，可点击右上角「+ 新建」或「导入」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 340.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(filtered, key = { it.id }) { skill ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                                    .noRippleClickable { editingSkill = skill }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            text = skill.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = skill.source.label,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Clay,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Clay.copy(alpha = 0.14f))
                                                .padding(horizontal = 5.dp, vertical = 1.dp),
                                        )
                                    }
                                    Text(
                                        text = skill.description,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (skill.packages.isNotEmpty()) {
                                        Text(
                                            text = skill.packages.joinToString(", "),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                Switch(
                                    checked = skill.enabled,
                                    onCheckedChange = { onToggleEnabled(skill.id, it) },
                                    colors = SwitchDefaults.colors(checkedTrackColor = Clay),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成", color = Clay, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onImportClick) {
                Text("导入 .md/.yaml")
            }
        },
    )

    if (creatingNew || editingSkill != null) {
        val target = editingSkill
        AppSkillEditorDialog(
            initial = target,
            onSave = { id, name, desc, packages, appNames, tags, priority, content ->
                onSaveSkill(id, name, desc, packages, appNames, tags, priority, content)
                creatingNew = false
                editingSkill = null
            },
            onDelete = if (target != null && target.source == PhoneAppSkillSource.CUSTOM) {
                {
                    onDeleteCustomSkill(target.id)
                    editingSkill = null
                }
            } else {
                null
            },
            onDismiss = {
                creatingNew = false
                editingSkill = null
            },
        )
    }
}

@Composable
private fun AppSkillEditorDialog(
    initial: PhoneAppSkill?,
    onSave: (
        id: String,
        name: String,
        description: String,
        packages: List<String>,
        appNames: List<String>,
        tags: List<String>,
        priority: Int,
        content: String,
    ) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var id by remember(initial) { mutableStateOf(initial?.id.orEmpty()) }
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var desc by remember(initial) { mutableStateOf(initial?.description.orEmpty()) }
    var packagesText by remember(initial) { mutableStateOf(initial?.packages?.joinToString(", ").orEmpty()) }
    var appNamesText by remember(initial) { mutableStateOf(initial?.appNames?.joinToString(", ").orEmpty()) }
    var tagsText by remember(initial) { mutableStateOf(initial?.tags?.joinToString(", ").orEmpty()) }
    var content by remember(initial) { mutableStateOf(initial?.content.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 1f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        title = {
            Text(
                text = when {
                    initial == null -> "新建应用技能"
                    initial.source == PhoneAppSkillSource.BUILTIN -> "查看 / 覆写「${initial.name}」"
                    else -> "编辑「${initial.name}」"
                },
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    label = { Text("技能 ID（英文/短横线）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("应用名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("一句话简介（先暴露给模型）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = packagesText,
                    onValueChange = { packagesText = it },
                    label = { Text("包名列表（逗号分隔，支持 .* 通配）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = appNamesText,
                    onValueChange = { appNamesText = it },
                    label = { Text("应用别名（逗号分隔）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = tagsText,
                    onValueChange = { tagsText = it },
                    label = { Text("标签（逗号分隔）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Markdown 操作经验正文") },
                    minLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && content.isNotBlank(),
                onClick = {
                    val splitCsv = { raw: String ->
                        raw.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
                    }
                    onSave(
                        id.ifBlank { name },
                        name,
                        desc.ifBlank { name },
                        splitCsv(packagesText),
                        splitCsv(appNamesText),
                        splitCsv(tagsText),
                        initial?.priority ?: 90,
                        content,
                    )
                },
            ) {
                Text(
                    if (initial?.source == PhoneAppSkillSource.BUILTIN) "保存为自定义覆写" else "保存",
                    color = Clay,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        },
    )
}
