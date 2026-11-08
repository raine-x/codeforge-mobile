package com.hxy.codeforge.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
 * 导入分两个落点（默认工作区 / 当前项目），导出分三种形态
 * （单个文件 / 默认工作区全部 / 当前项目全部），别混：
 *  - 默认工作区用 [EnvKit.inboxSpace]（未选项目时内核会话的落点）；
 *  - 当前项目用 [ChatViewModel] 状态里的 currentWorkspace —— 工作区是
 *    内核全局状态，壳侧不猜路径，只读这一份真值。
 *
 * 落点统一是 `Download/CodeForge/`：targetSdk 28 在 Android 10+ 仍是 legacy
 * 存储，本进程与内核子进程（同一 uid）都能直接写公共目录，不需要 SAF 授权。
 *
 * 所有 IO 一律在 [Dispatchers.IO]：打包是遍历整棵目录树，主线程做必卡。
 */
@Composable
fun FileTransferSection(vm: ChatViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by vm.state.collectAsState()

    // 各操作的结果各自独立展示，互不覆盖 —— 用户常常导完一个再导下一个
    var importDefaultResult by remember { mutableStateOf<String?>(null) }
    var importCurrentResult by remember { mutableStateOf<String?>(null) }
    var exportResult by remember { mutableStateOf<String?>(null) }
    var singleExportResult by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // 导出单个文件：来源工作区（默认工作区 / 当前项目）与该目录下的文件清单
    var singleSource by remember { mutableStateOf(TransferSource.DEFAULT) }
    var singleFiles by remember { mutableStateOf<List<TransferFile>>(emptyList()) }
    var singleTotal by remember { mutableStateOf(0) }
    var singleLoaded by remember { mutableStateOf(false) }

    fun loadSingleFiles() {
        val dir = when (singleSource) {
            TransferSource.DEFAULT -> EnvKit.inboxSpace(context)
            TransferSource.CURRENT -> {
                val p = vm.state.value.currentWorkspace.trim()
                if (p.isBlank()) {
                    singleFiles = emptyList()
                    singleTotal = 0
                    singleLoaded = true
                    singleExportResult = "当前没有工作区，请先选择项目"
                    return
                }
                java.io.File(p)
            }
        }
        busy = true
        scope.launch(Dispatchers.IO) {
            val all = if (dir.isDirectory) {
                dir.walkTopDown().filter { it.isFile }
                    .sortedBy { it.toRelativeString(dir) }
                    .toList()
            } else emptyList()
            singleTotal = all.size
            singleFiles = all.take(SINGLE_FILE_LIST_LIMIT).map { f ->
                TransferFile(rel = f.toRelativeString(dir), size = f.length())
            }
            singleLoaded = true
            busy = false
        }
    }

    // GetContent 给的是 content://：必须先复制到自己的目录再交给内核，
    // 系统只保证本次会话内的临时读权限（同 Composer 附件管道的理由）
    // pendingImport 决定这次导入落到默认工作区还是当前项目（导入前由按钮设好）
    var pendingImport by remember { mutableStateOf(TransferSource.DEFAULT) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val target = pendingImport
        val setResult: (String) -> Unit = { msg ->
            if (target == TransferSource.DEFAULT) importDefaultResult = msg else importCurrentResult = msg
        }
        busy = true
        setResult("导入中…")
        scope.launch(Dispatchers.IO) {
            val baseDir = when (target) {
                TransferSource.DEFAULT -> null
                TransferSource.CURRENT -> {
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
                    // EnvKit 内部 runCatching 只回 null，拿不到具体异常；
                    // 实际最常见的原因是云盘文件未下载或 uri 读权限已失效
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
            // 先判空：exportDirectoryAsZip 空目录和写失败都返回 null，
            // 不区分的话用户会看到「导出失败」，实际只是没东西可导
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
            // 空工作区 = 还没选项目（或选了「不选择」且内核未分配目录），
            // 拿不到路径就没法猜打包目标，先让用户去选
            exportResult = "请先选择一个项目（当前没有工作区）"
            return
        }
        busy = true
        exportResult = "导出中…"
        scope.launch(Dispatchers.IO) {
            val target = java.io.File(dir)
            // 末段作 zip 名：用户一眼能认出是哪个项目，重名靠时间戳区分
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

    fun exportSingleFile(rel: String) {
        val dir = when (singleSource) {
            TransferSource.DEFAULT -> EnvKit.inboxSpace(context)
            TransferSource.CURRENT -> java.io.File(vm.state.value.currentWorkspace.trim())
        }
        val src = java.io.File(dir, rel)
        busy = true
        singleExportResult = "导出中…"
        scope.launch(Dispatchers.IO) {
            val out = EnvKit.exportFileToDownloads(context, src)
            singleExportResult = if (out != null) {
                "已导出到手机：${out.absolutePath}"
            } else {
                "导出失败：文件不可读或 Download/CodeForge 不可写"
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

        // ---------------- 导入到默认工作区 ----------------
        TransferTitle("导入文件到默认工作区")
        Text(
            text = "从系统选择器挑一个文件，复制进默认工作区（未选项目时内核会话的落点），" +
                "对话里可以直接引用。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        TransferPill(if (busy) "处理中…" else "选择文件", enabled = !busy) {
            pendingImport = TransferSource.DEFAULT
            importer.launch("*/*")
        }
        TransferResult(importDefaultResult)

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )

        // ---------------- 导入到当前工作区 ----------------
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
            pendingImport = TransferSource.CURRENT
            importer.launch("*/*")
        }
        TransferResult(importCurrentResult)

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )

        // ---------------- 导出单个文件 ----------------
        TransferTitle("导出单个文件")
        Text(
            text = "先选来源工作区，再点下面的文件，把单个文件复制到手机 Download/CodeForge/。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TransferSourceChip("默认工作区", selected = singleSource == TransferSource.DEFAULT, enabled = !busy) {
                singleSource = TransferSource.DEFAULT
                singleLoaded = false
            }
            TransferSourceChip("当前项目", selected = singleSource == TransferSource.CURRENT, enabled = !busy) {
                singleSource = TransferSource.CURRENT
                singleLoaded = false
            }
            TransferPill(if (busy) "处理中…" else "刷新清单", enabled = !busy) { loadSingleFiles() }
        }
        if (singleLoaded) {
            if (singleFiles.isEmpty()) {
                Text(
                    text = "没有可导出的文件",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    singleFiles.forEach { f ->
                        TransferFileRow(
                            file = f,
                            enabled = !busy,
                            onClick = { exportSingleFile(f.rel) },
                        )
                    }
                    if (singleTotal > singleFiles.size) {
                        Text(
                            text = "共 $singleTotal 个文件，仅列出前 ${singleFiles.size} 个",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
        TransferResult(singleExportResult)

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )

        // ---------------- 导出默认工作区 ----------------
        TransferTitle("导出默认工作区")
        Text(
            text = "把默认工作区（未选项目时的文件落点）打包成 zip 复制到 Download/CodeForge/。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        TransferPill(if (busy) "处理中…" else "导出", enabled = !busy) { exportInbox() }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )

        // ---------------- 导出当前项目 ----------------
        TransferTitle("导出当前项目")
        Text(
            text = if (state.currentWorkspace.isBlank()) {
                "当前没有工作区。先到侧栏选一个项目，或新建项目时选目录。"
            } else {
                "当前项目：${state.currentWorkspace}"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        TransferPill(if (busy) "处理中…" else "导出", enabled = !busy) { exportCurrentProject() }

        TransferResult(exportResult)
    }
}

/** zip 名里的时间戳：同一目录连导两次不能互相覆盖。 */
private fun transferStamp(): String =
    java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())

/** 小节标题（本文件私有，避免与 SettingsSheet 内的同名件互相影响）。 */
@Composable
private fun TransferTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )
}

/** 行内动作胶囊；busy 时置灰不可点（打包/复制期间重复点会叠加 IO）。 */
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

/** 操作结果：路径可能很长，允许换行（截断会让用户找不到落地文件）。 */
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

// ---------------------------------------------------------------- 导出单个文件

/** 工作区来源：默认工作区（未选项目时的落点）或当前项目。 */
private enum class TransferSource { DEFAULT, CURRENT }

/** 单文件清单条目：相对路径 + 大小（点一行就导出这一个文件）。 */
private data class TransferFile(val rel: String, val size: Long)

/** 清单上限：工作区可能有上千文件，全部列出来 would 卡 UI，截断后提示总数。 */
private const val SINGLE_FILE_LIST_LIMIT = 200

/** 来源选择小标签：选中是陶土底，未选是浅灰底。 */
@Composable
private fun TransferSourceChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Clay else MaterialTheme.colorScheme.surfaceVariant)
            .noRippleClickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
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

/** 清单行：相对路径 + 大小，点按导出单个文件到手机。 */
@Composable
private fun TransferFileRow(file: TransferFile, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = "${file.rel}  ·  ${formatTransferSize(file.size)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .noRippleClickable(enabled = enabled) { onClick() }
            .padding(vertical = 5.dp),
    )
}

/** 相对路径（正斜杠，和 zip 条目口径一致）。 */
private fun java.io.File.toRelativeString(base: java.io.File): String =
    base.toPath().relativize(toPath()).toString().replace('\\', '/')

/** 文件大小：B / KB / MB。 */
private fun formatTransferSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    return "%.1f MB".format(kb / 1024.0)
}
