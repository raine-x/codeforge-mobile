package com.hxy.codeforge.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hxy.codeforge.core.EnvKit
import com.hxy.codeforge.core.StorageManager
import com.hxy.codeforge.phone.PhoneArtifactStore
import com.hxy.codeforge.ui.ChatViewModel
import com.hxy.codeforge.ui.common.noRippleClickable
import com.hxy.codeforge.ui.theme.Clay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 存储空间管理（三级分桶管控）：
 * - Tier A（可全清缓存）：outbox、日志、图片/WebView 缓存、插件临时包、Alpine `apk` 包缓存；
 * - Tier B（受配额管控）：托管附件、大文件 imports、手机操控截图/录屏/录音产物；
 * - Tier C（受保护核心）：容器 rootfs、活跃项目目录、已安装插件（仅统计，绝不静默删；
 *   孤立无会话引用的 `workspace/p-*` 目录单独列出，需人工点删确认）。
 */
@Composable
fun StorageSection(vm: ChatViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by vm.state.collectAsState()

    var report by remember { mutableStateOf<StorageManager.StorageReport?>(null) }
    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var pendingOrphan by remember { mutableStateOf<StorageManager.OrphanWorkspace?>(null) }

    val activeWorkspaces = remember(state.sessions, state.projects, state.currentWorkspace) {
        buildSet {
            state.sessions.forEach { if (it.workspace.isNotBlank()) add(it.workspace) }
            state.projects.forEach { if (it.workspace.isNotBlank()) add(it.workspace) }
            if (state.currentWorkspace.isNotBlank()) add(state.currentWorkspace)
        }
    }

    fun rescan() {
        busy = true
        scope.launch {
            val scanned = withContext(Dispatchers.IO) {
                StorageManager.scanStorageUsage(context.filesDir, context.cacheDir, activeWorkspaces)
            }
            report = scanned
            busy = false
        }
    }

    LaunchedEffect(activeWorkspaces) {
        rescan()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "应用私有存储占用",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = report?.let { EnvKit.formatFileSize(it.totalBytes) } ?: "正在扫描…",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            StorageActionPill(label = if (busy) "扫描中…" else "刷新", enabled = !busy) {
                rescan()
            }
        }

        report?.let { r ->
            StorageRatioBar(report = r, modifier = Modifier.padding(top = 12.dp, bottom = 10.dp))

            StorageLegendRow(
                color = Clay,
                title = "可清理缓存（暂存 / 日志 / apk 包缓存）",
                stats = StorageManager.BucketStats(
                    bytes = r.reclaimableCacheBytes,
                    fileCount = r.tempAndCache.fileCount + r.containerApkCache.fileCount,
                ),
            )
            StorageLegendRow(
                color = Color(0xFF4F86C6),
                title = "附件与操控产物（attachments / imports / 录屏截图）",
                stats = StorageManager.BucketStats(
                    bytes = r.mediaAndArtifactsBytes,
                    fileCount = r.attachmentsAndImports.fileCount + r.phoneArtifacts.fileCount,
                ),
            )
            StorageLegendRow(
                color = Color(0xFF5D9B72),
                title = "运行环境（Alpine rootfs 与命令行工具）",
                stats = r.runtimeEnv,
            )
            StorageLegendRow(
                color = Color(0xFF9C8E80),
                title = "受保护项目源码与已安装插件",
                stats = r.projectsAndPlugins,
            )

            statusMessage?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.labelSmall,
                    color = Clay,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = 14.dp),
            )

            // Tier A 一键清理
            StorageCleanCard(
                title = "清理暂存与容器缓存",
                desc = "清空 outbox 暂存、旧日志、应用缓存、未完成插件暂存及 Alpine apk 下载缓存（不影响已安装语言包和项目源码）。",
                sizeText = EnvKit.formatFileSize(r.reclaimableCacheBytes),
                actionLabel = "立即清理",
                enabled = !busy && r.reclaimableCacheBytes > 0L,
            ) {
                busy = true
                scope.launch {
                    val res = withContext(Dispatchers.IO) {
                        StorageManager.cleanTempAndContainerCaches(context.filesDir, context.cacheDir)
                    }
                    statusMessage = "已清理 ${res.deletedFiles} 个缓存文件，释放 ${EnvKit.formatFileSize(res.freedBytes)}"
                    rescan()
                }
            }

            // Tier B 清理附件与产物
            StorageCleanCard(
                title = "清理手机操控产物与历史附件",
                desc = "清空各托管工作区 attachments/、imports/ 与 phone_artifacts/ 下的截图、录屏、录音文件（保留其余代码与文档）。",
                sizeText = EnvKit.formatFileSize(r.mediaAndArtifactsBytes),
                actionLabel = "清空产物与附件",
                enabled = !busy && r.mediaAndArtifactsBytes > 0L,
                modifier = Modifier.padding(top = 10.dp),
            ) {
                busy = true
                scope.launch {
                    val res = withContext(Dispatchers.IO) {
                        val cleaned = StorageManager.cleanPhoneArtifactsAndAttachments(context.filesDir)
                        PhoneArtifactStore.pruneMissing()
                        cleaned
                    }
                    statusMessage = "已移除 ${res.deletedFiles} 个附件/产物，释放 ${EnvKit.formatFileSize(res.freedBytes)}"
                    rescan()
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = 14.dp),
            )

            Text(
                text = "孤立项目目录（${r.orphanWorkspaces.size}）",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "仅列出系统分配但当前已无会话关联的 p-* 目录，绝不会自动删除，可逐项核对清理。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp, bottom = 8.dp),
            )
            if (r.orphanWorkspaces.isEmpty()) {
                Text(
                    text = "没有检测到孤立项目目录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    r.orphanWorkspaces.forEach { orphan ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .border(0.8.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = orphan.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "${EnvKit.formatFileSize(orphan.bytes)} · ${orphan.fileCount} 个文件",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            StorageActionPill(label = "删除", enabled = !busy) {
                                pendingOrphan = orphan
                            }
                        }
                    }
                }
            }
        }
    }

    pendingOrphan?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingOrphan = null },
            title = { Text("确认删除孤立目录？") },
            text = {
                Text(
                    "将永久删除「${target.name}」内的 ${target.fileCount} 个文件（共 ${EnvKit.formatFileSize(target.bytes)}），删除后不可恢复。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingOrphan = null
                        busy = true
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                StorageManager.deleteOrphanWorkspace(context.filesDir, File(target.path))
                            }
                            statusMessage = if (ok) {
                                "已删除孤立目录 ${target.name}，释放 ${EnvKit.formatFileSize(target.bytes)}"
                            } else {
                                "删除失败：目录不受托管或已被移除"
                            }
                            rescan()
                        }
                    },
                ) {
                    Text("确认删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingOrphan = null }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun StorageRatioBar(report: StorageManager.StorageReport, modifier: Modifier = Modifier) {
    val total = report.totalBytes.coerceAtLeast(1L).toFloat()
    val segments = listOf(
        Clay to (report.reclaimableCacheBytes / total),
        Color(0xFF4F86C6) to (report.mediaAndArtifactsBytes / total),
        Color(0xFF5D9B72) to (report.runtimeEnv.bytes / total),
        Color(0xFF9C8E80) to (report.projectsAndPlugins.bytes / total),
    ).filter { it.second > 0.005f }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        segments.forEach { (color, fraction) ->
            Box(
                modifier = Modifier
                    .weight(fraction)
                    .fillMaxHeight()
                    .background(color),
            )
        }
    }
}

@Composable
private fun StorageLegendRow(color: Color, title: String, stats: StorageManager.BucketStats) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(start = 8.dp)
                .weight(1f),
        )
        Text(
            text = "${EnvKit.formatFileSize(stats.bytes)}（${stats.fileCount}）",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun StorageCleanCard(
    title: String,
    desc: String,
    sizeText: String,
    actionLabel: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = sizeText,
                style = MaterialTheme.typography.labelMedium,
                color = Clay,
            )
        }
        Text(
            text = desc,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            StorageActionPill(label = actionLabel, enabled = enabled, onClick = onClick)
        }
    }
}

@Composable
private fun StorageActionPill(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) Clay else MaterialTheme.colorScheme.surfaceVariant)
            .noRippleClickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
