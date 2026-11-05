package com.hxy.codeforge.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
 * 三条路径的来源各不相同，别混：
 *  - 导入用 [EnvKit.inboxSpace]（默认工作区）的 imports/ —— 会话创建时若没选
 *    目录，内核工作区就落在这里，导入的文件能被对话直接引用；
 *  - 「导出默认工作区」同样打包 inboxSpace，回答「没选项目时文件去哪了」；
 *  - 「导出当前项目」用 [ChatViewModel] 状态里的 currentWorkspace —— 工作区是
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

    // 三条操作的结果各自独立展示，互不覆盖 —— 用户常常导完一个再导下一个
    var importResult by remember { mutableStateOf<String?>(null) }
    var exportResult by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // GetContent 给的是 content://：必须先复制到自己的目录再交给内核，
    // 系统只保证本次会话内的临时读权限（同 Composer 附件管道的理由）
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        importResult = "导入中…"
        scope.launch(Dispatchers.IO) {
            val file = EnvKit.importFileFromUri(context, uri)
            importResult = if (file != null) {
                "已导入：${file.absolutePath}"
            } else {
                // EnvKit 内部 runCatching 只回 null，拿不到具体异常；
                // 实际最常见的原因是云盘文件未下载或 uri 读权限已失效
                "导入失败：所选文件不可读（云端文件或权限受限时会这样），换本地文件再试"
            }
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

        // ---------------- 导入 ----------------
        TransferTitle("导入文件到默认工作区")
        Text(
            text = "从系统选择器挑一个文件，复制进默认工作区（未选项目时内核会话的落点），" +
                "对话里可以直接引用。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        TransferPill(if (busy) "处理中…" else "选择文件", enabled = !busy) {
            importer.launch("*/*")
        }
        TransferResult(importResult)

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
