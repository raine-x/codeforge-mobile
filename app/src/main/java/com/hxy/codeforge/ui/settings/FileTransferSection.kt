package com.hxy.codeforge.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hxy.codeforge.core.EnvKit
import com.hxy.codeforge.ui.ChatViewModel
import com.hxy.codeforge.ui.common.noRippleClickable
import com.hxy.codeforge.ui.theme.Clay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 文件传输：在「容器里的工作区」与「手机可见的 Download」之间搬文件。
 *
 * 导入分两个落点（默认工作区 / 当前项目）；单文件浏览与导出直接复用统一文件管理器
 * [com.hxy.codeforge.ui.files.FileManagerSheet]，不再在这里截断只列前 200 个文件；
 * 本页额外保留一键将默认工作区或当前项目整体打包导出为 zip 的快捷入口。
 */
@Composable
fun FileTransferSection(
    vm: ChatViewModel,
    onOpenFileManager: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by vm.state.collectAsState()

    var importDefaultResult by remember { mutableStateOf<String?>(null) }
    var importCurrentResult by remember { mutableStateOf<String?>(null) }
    var exportResult by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    var pendingImport by remember { mutableStateOf(TransferTarget.DEFAULT) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val target = pendingImport
        val setResult: (String) -> Unit = { msg ->
            if (target == TransferTarget.DEFAULT) importDefaultResult = msg else importCurrentResult = msg
        }
        busy = true
        setResult("导入中…")
        scope.launch(Dispatchers.IO) {
            val baseDir = when (target) {
                TransferTarget.DEFAULT -> null
                TransferTarget.CURRENT -> {
                    val p = vm.state.value.currentWorkspace.trim()
                    if (p.isBlank()) {
                        setResult("当前没有工作区，请先选择项目再导入")
                        busy = false
                        return@launch
                    }
                    java.io.File(p)
                }
            }
            val file = EnvKit.importFileFromUri(context, uri, baseDir = baseDir)
            setResult(
                if (file != null) {
                    "已导入：${file.absolutePath}"
                } else {
                    "导入失败：所选文件不可读（云端文件或权限受限时会这样），换本地文件再试"
                }
            )
            busy = false
        }
    }

    fun exportInbox() {
        busy = true
        exportResult = "导出中…"
        scope.launch(Dispatchers.IO) {
            val inbox = EnvKit.inboxSpace(context)
            if (!inbox.walkTopDown().any { it.isFile }) {
                exportResult = "工作区还是空的，没有可导出的文件"
                busy = false
                return@launch
            }
            val zipName = "codeforge-inbox-${transferStamp()}.zip"
            val out = EnvKit.exportDirectoryAsZip(context, inbox, zipName)
            exportResult = if (out != null) {
                "已导出：${out.absolutePath}"
            } else {
                "导出失败：写不进 Download/CodeForge（存储不可用或没有可写目录）"
            }
            busy = false
        }
    }

    fun exportCurrentProject() {
        val dir = state.currentWorkspace.trim()
        if (dir.isBlank()) {
            exportResult = "请先选择一个项目（当前没有工作区）"
            return
        }
        busy = true
        exportResult = "导出中…"
        scope.launch(Dispatchers.IO) {
            val target = java.io.File(dir)
            val tail = dir.trimEnd('/').substringAfterLast('/').ifBlank { "workspace" }
            val out = EnvKit.exportDirectoryAsZip(
                context,
                target,
                "$tail-${transferStamp()}.zip",
            )
            exportResult = if (out != null) {
                "已导出：${out.absolutePath}"
            } else {
                "导出失败：目录不存在、是空目录，或 Download/CodeForge 不可写"
            }
            busy = false
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "/sdcard 与 Download 已由兼容环境绑定，内核命令可直接读写；" +
                "导出会把文件复制到 Download/CodeForge/，不需要额外权限（targetSdk 28 legacy 存储）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )

        // 浏览与导出单文件/任意目录（复用统一文件管理器）
        TransferTitle("浏览与导出文件（统一文件管理器）")
        Text(
            text = "按真实目录层级浏览全部文件（支持搜索、预览、单文件导出与目录打包导出），不受文件数量限制。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        if (onOpenFileManager != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TransferPill("当前项目文件", enabled = !busy) {
                    onOpenFileManager("CURRENT")
                }
                TransferPill("默认工作区文件", enabled = !busy) {
                    onOpenFileManager("INBOX")
                }
                TransferPill("全部工作区", enabled = !busy) {
                    onOpenFileManager("WORKSPACES")
                }
            }
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )

        // 导入到默认工作区
        TransferTitle("导入文件到默认工作区")
        Text(
            text = "从系统选择器挑一个文件，复制进默认工作区（未选项目时内核会话的落点），" +
                "对话里可以直接引用。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        TransferPill(if (busy) "处理中…" else "选择文件", enabled = !busy) {
            pendingImport = TransferTarget.DEFAULT
            importer.launch("*/*")
        }
        TransferResult(importDefaultResult)

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )

        // 导入到当前工作区
        TransferTitle("导入文件到当前工作区")
        Text(
            text = if (state.currentWorkspace.isBlank()) {
                "当前没有工作区。先到侧栏选一个项目，或新建项目时选目录。"
            } else {
                "挑一个文件复制进当前项目的 imports/：${state.currentWorkspace}"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        TransferPill(
            if (busy) "处理中…" else "选择文件",
            enabled = !busy && state.currentWorkspace.isNotBlank(),
        ) {
            pendingImport = TransferTarget.CURRENT
            importer.launch("*/*")
        }
        TransferResult(importCurrentResult)

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )

        // 整目录一键打包导出
        TransferTitle("一键打包导出工作区")
        Text(
            text = "把默认工作区或当前项目整体打包成 zip 复制到 Download/CodeForge/。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TransferPill(if (busy) "处理中…" else "导出默认工作区", enabled = !busy) { exportInbox() }
            TransferPill(if (busy) "处理中…" else "导出当前项目", enabled = !busy) { exportCurrentProject() }
        }
        TransferResult(exportResult)
    }
}

private enum class TransferTarget { DEFAULT, CURRENT }

private fun transferStamp(): String =
    java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())

@Composable
private fun TransferTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun TransferPill(label: String, enabled: Boolean, onClick: () -> Unit) {
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
            color = if (enabled) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun TransferResult(message: String?) {
    if (message == null) return
    Text(
        text = message,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}
