package com.hxy.codeforge.ui.files

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hxy.codeforge.core.EnvKit
import com.hxy.codeforge.ui.chat.isAudioPath
import com.hxy.codeforge.ui.chat.isImagePath
import com.hxy.codeforge.ui.chat.isVideoPath
import com.hxy.codeforge.ui.chat.launchMediaUri
import com.hxy.codeforge.ui.common.Glyph
import com.hxy.codeforge.ui.common.GlyphIcon
import com.hxy.codeforge.ui.common.SyncDialogStatusBar
import com.hxy.codeforge.ui.common.isLandscape
import com.hxy.codeforge.ui.common.noRippleClickable
import com.hxy.codeforge.ui.theme.Clay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date

private enum class FileRootKey(val label: String) {
    CURRENT("当前工作区"),
    INBOX("默认空间"),
    WORKSPACES("全部工作区"),
    DEVICE("手机存储"),
    CACHE("暂存与缓存"),
}

private enum class SortMode(val label: String) {
    NAME("名称"),
    TIME_DESC("最新"),
    SIZE_DESC("体积↓"),
    ;

    fun next(): SortMode = entries[(ordinal + 1) % entries.size]
}

private data class FsEntry(
    val file: File,
    val name: String,
    val isDir: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
)

private data class DirSnapshot(
    val entries: List<FsEntry>,
    val totalBytes: Long,
    val unreadable: Boolean,
)

private data class TextPreviewState(
    val file: File,
    val content: String,
    val truncated: Boolean,
)

private const val MAX_PREVIEW_BYTES = 256 * 1024L

private val TEXT_EXTENSIONS = setOf(
    "txt", "md", "markdown", "json", "yaml", "yml", "xml", "html", "htm", "css",
    "kt", "kts", "java", "go", "py", "js", "jsx", "ts", "tsx", "c", "cc", "cpp",
    "h", "hpp", "rs", "sh", "bash", "zsh", "toml", "ini", "cfg", "conf", "env",
    "log", "csv", "sql", "gradle", "properties", "svg", "gitignore",
)

private fun isPreviewableTextFile(file: File): Boolean {
    if (!file.isFile) return false
    val ext = file.extension.lowercase()
    if (ext in TEXT_EXTENSIONS) return true
    val name = file.name.lowercase()
    return name in setOf("makefile", "dockerfile", "readme", "license", "gradlew")
}

/**
 * 统一文件管理器。
 *
 * 把原本散落在各处的四个文件系统区域收口到一张面板里：
 * 当前项目工作区、应用私有默认空间、全部工作区列表、手机外部存储（Download/根目录）
 * 以及 outbox/camera/logs 暂存缓存；并支持无 20MiB 限制导入、导出到 Download、
 * 新建文件夹、重命名、安全删除、文本直览、媒体外部打开，以及直接引用路径/装入对话附件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerSheet(
    activeWorkspaceRoot: String,
    activeWorkspaceLabel: String,
    initialRoot: String = FileRootKey.CURRENT.name,
    onQuoteToChat: (String) -> Unit,
    onAttachToChat: (File) -> Unit,
    onNotify: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val portraitFullScreen = !isLandscape()

    val rootDirs = remember(activeWorkspaceRoot) {
        val inbox = EnvKit.inboxSpace(context)
        val current = activeWorkspaceRoot.takeIf { it.isNotBlank() }?.let { File(it) } ?: inbox
        mapOf(
            FileRootKey.CURRENT to current,
            FileRootKey.INBOX to inbox,
            FileRootKey.WORKSPACES to EnvKit.workspaceRoot(context),
            FileRootKey.DEVICE to EnvKit.deviceStorageRoot(context),
            FileRootKey.CACHE to EnvKit.outboxDir(context),
        )
    }

    var selectedRoot by rememberSaveable(initialRoot) {
        mutableStateOf(
            runCatching { FileRootKey.valueOf(initialRoot) }.getOrDefault(FileRootKey.CURRENT),
        )
    }
    val rootDir = rootDirs[selectedRoot]
    var currentPath by rememberSaveable(selectedRoot, rootDir?.absolutePath) {
        mutableStateOf(rootDir?.absolutePath.orEmpty())
    }
    var query by rememberSaveable { mutableStateOf("") }
    var sortMode by rememberSaveable { mutableStateOf(SortMode.NAME) }
    var reloadSeq by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }

    var snapshot by remember { mutableStateOf(DirSnapshot(emptyList(), 0L, unreadable = false)) }

    var actionTarget by remember { mutableStateOf<FsEntry?>(null) }
    var renameTarget by remember { mutableStateOf<FsEntry?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<FsEntry?>(null) }
    var showNewFolder by remember { mutableStateOf(false) }
    var newFolderInput by remember { mutableStateOf("") }
    var previewState by remember { mutableStateOf<TextPreviewState?>(null) }
    var showClearCacheConfirm by remember { mutableStateOf(false) }

    val currentDir = remember(currentPath, rootDir) {
        val f = currentPath.takeIf { it.isNotBlank() }?.let { File(it) }
        when {
            f != null && f.isDirectory -> f
            rootDir != null -> rootDir
            else -> null
        }
    }

    val atRoot = remember(currentDir, rootDir) {
        if (currentDir == null || rootDir == null) true
        else runCatching { currentDir.canonicalFile == rootDir.canonicalFile }.getOrDefault(true)
    }

    fun navigateUp() {
        val cur = currentDir ?: return
        val root = rootDir ?: return
        val curCanon = runCatching { cur.canonicalFile }.getOrNull() ?: return
        val rootCanon = runCatching { root.canonicalFile }.getOrNull() ?: return
        if (curCanon == rootCanon) return
        val parent = curCanon.parentFile ?: return
        val rootPrefix = rootCanon.path.trimEnd(File.separatorChar) + File.separator
        if (parent == rootCanon || parent.path.startsWith(rootPrefix)) {
            currentPath = parent.absolutePath
        } else {
            currentPath = rootCanon.absolutePath
        }
    }

    LaunchedEffect(currentDir?.absolutePath, reloadSeq) {
        val target = currentDir
        if (target == null) {
            snapshot = DirSnapshot(emptyList(), 0L, unreadable = true)
            return@LaunchedEffect
        }
        snapshot = withContext(Dispatchers.IO) { readDirSnapshot(target) }
    }

    val displayedEntries = remember(snapshot.entries, query, sortMode) {
        val q = query.trim()
        val filtered = if (q.isEmpty()) {
            snapshot.entries
        } else {
            snapshot.entries.filter { it.name.contains(q, ignoreCase = true) }
        }
        filtered.sortedWith(
            compareBy<FsEntry> { !it.isDir }.then(
                when (sortMode) {
                    SortMode.NAME -> compareBy { it.name.lowercase() }
                    SortMode.TIME_DESC -> compareByDescending<FsEntry> { it.lastModified }.thenBy { it.name.lowercase() }
                    SortMode.SIZE_DESC -> compareByDescending<FsEntry> { it.sizeBytes }.thenBy { it.name.lowercase() }
                },
            ),
        )
    }

    val importFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val destDir = currentDir ?: return@rememberLauncherForActivityResult
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val imported = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    EnvKit.importFileFromUri(context, uri, baseDir = destDir, subdir = "")
                }
            }
            busy = false
            reloadSeq++
            if (imported.size == uris.size) {
                onNotify("已导入 ${imported.size} 个文件到 ${destDir.name}", false)
            } else {
                onNotify("已导入 ${imported.size}/${uris.size} 个文件", imported.isEmpty())
            }
        }
    }

    val importMediaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris ->
        val destDir = currentDir ?: return@rememberLauncherForActivityResult
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val imported = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    EnvKit.importFileFromUri(context, uri, baseDir = destDir, subdir = "")
                }
            }
            busy = false
            reloadSeq++
            if (imported.size == uris.size) {
                onNotify("已导入 ${imported.size} 个媒体到 ${destDir.name}", false)
            } else {
                onNotify("已导入 ${imported.size}/${uris.size} 个媒体", imported.isEmpty())
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = if (portraitFullScreen) Dp.Unspecified else 720.dp,
        shape = if (portraitFullScreen) {
            RoundedCornerShape(0.dp)
        } else {
            RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        },
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 1f),
        dragHandle = if (portraitFullScreen) null else ({ BottomSheetDefaults.DragHandle() }),
        contentWindowInsets = if (portraitFullScreen) {
            { WindowInsets.safeDrawing }
        } else {
            { BottomSheetDefaults.windowInsets }
        },
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = atRoot && previewState == null),
    ) {
        SyncDialogStatusBar()
        BackHandler(enabled = previewState != null || !atRoot) {
            if (previewState != null) {
                previewState = null
            } else {
                navigateUp()
            }
        }

        Column(
            modifier = if (portraitFullScreen) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 680.dp)
            },
        ) {
            // 顶部栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!atRoot) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(17.dp))
                            .noRippleClickable { navigateUp() },
                        contentAlignment = Alignment.Center,
                    ) {
                        GlyphIcon(
                            glyph = Glyph.Back,
                            size = 18.dp,
                            color = MaterialTheme.colorScheme.onSurface,
                            strokeWidth = 2.4.dp,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "文件管理",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (selectedRoot == FileRootKey.CURRENT && activeWorkspaceLabel.isNotBlank()) {
                        Text(
                            text = activeWorkspaceLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(17.dp))
                        .noRippleClickable { reloadSeq++ },
                    contentAlignment = Alignment.Center,
                ) {
                    GlyphIcon(
                        glyph = Glyph.Retry,
                        size = 16.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(17.dp))
                        .noRippleClickable { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) {
                    GlyphIcon(
                        glyph = Glyph.Close,
                        size = 18.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        strokeWidth = 2.2.dp,
                    )
                }
            }

            // 存储根切换条
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FileRootKey.entries.forEach { key ->
                    val available = rootDirs[key] != null
                    val selected = selectedRoot == key
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = when {
                            selected -> Clay
                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (available) 0.55f else 0.25f)
                        },
                        modifier = Modifier.noRippleClickable(enabled = available) {
                            selectedRoot = key
                            currentPath = rootDirs[key]?.absolutePath.orEmpty()
                            query = ""
                        },
                    ) {
                        Text(
                            text = key.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = when {
                                selected -> Color.White
                                available -> MaterialTheme.colorScheme.onSurface
                                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        )
                    }
                }
            }

            // 路径面包屑 + 统计
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = relativeBreadcrumb(rootDir, currentDir),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${displayedEntries.size} 项 · ${EnvKit.formatFileSize(snapshot.totalBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 搜索框 + 排序切换
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlyphIcon(
                        glyph = Glyph.Search,
                        size = 15.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text(
                                text = "搜索当前目录文件名…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(Clay),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (query.isNotEmpty()) {
                        GlyphIcon(
                            glyph = Glyph.Close,
                            size = 14.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.noRippleClickable { query = "" },
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.noRippleClickable { sortMode = sortMode.next() },
                ) {
                    Text(
                        text = "排序:${sortMode.label}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                    )
                }
            }

            // 工具操作条
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (selectedRoot == FileRootKey.CACHE) {
                    ToolChip("一键清空暂存与缓存", enabled = !busy, accent = true) {
                        showClearCacheConfirm = true
                    }
                } else {
                    ToolChip(if (busy) "处理中…" else "导入文件", enabled = !busy && currentDir != null, accent = true) {
                        importFilesLauncher.launch(arrayOf("*/*"))
                    }
                    ToolChip("相册/视频", enabled = !busy && currentDir != null) {
                        importMediaLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                        )
                    }
                    ToolChip("新建文件夹", enabled = !busy && currentDir != null) {
                        newFolderInput = ""
                        showNewFolder = true
                    }
                    ToolChip("打包导出", enabled = !busy && currentDir != null && selectedRoot != FileRootKey.DEVICE) {
                        val target = currentDir ?: return@ToolChip
                        busy = true
                        scope.launch {
                            val zipName = "${target.name.ifBlank { "workspace" }}-${System.currentTimeMillis() / 1000}.zip"
                            val out = withContext(Dispatchers.IO) {
                                EnvKit.exportDirectoryAsZip(context, target, zipName)
                            }
                            busy = false
                            if (out != null) {
                                onNotify("已导出到 Download/CodeForge/${out.name}", false)
                            } else {
                                onNotify("当前目录为空或无法写入 Download", true)
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

            // 目录列表
            when {
                currentDir == null -> EmptyHint("未检测到可用存储目录")
                snapshot.unreadable -> EmptyHint("当前目录不可读（若浏览手机存储，请在系统设置开启存储权限）")
                displayedEntries.isEmpty() -> EmptyHint(if (query.isBlank()) "目录为空" else "没有匹配“$query”的项目")
                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (!atRoot) {
                        item(key = "__parent__") {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .noRippleClickable { navigateUp() },
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    GlyphIcon(Glyph.Back, 16.dp, MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        text = "返回上一级",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    items(displayedEntries, key = { it.file.absolutePath }) { entry ->
                        FileRow(
                            entry = entry,
                            onClick = {
                                if (entry.isDir) {
                                    currentPath = entry.file.absolutePath
                                    query = ""
                                } else {
                                    actionTarget = entry
                                }
                            },
                            onMore = { actionTarget = entry },
                        )
                    }
                }
            }
        }
    }

    // 单项操作菜单
    actionTarget?.let { entry ->
        val relForChat = remember(entry.file.absolutePath, activeWorkspaceRoot) {
            formatPathForChat(entry.file, activeWorkspaceRoot)
        }
        val canPreviewText = !entry.isDir && isPreviewableTextFile(entry.file)
        val allowDelete = rootDir != null && !runCatching { entry.file.canonicalFile == rootDir.canonicalFile }.getOrDefault(true)
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 1f),
            title = {
                Column {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (entry.isDir) "文件夹" else "${EnvKit.formatFileSize(entry.sizeBytes)} · ${formatTime(entry.lastModified)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (entry.isDir) {
                        DialogActionRow("打开目录", Glyph.Folder) {
                            actionTarget = null
                            currentPath = entry.file.absolutePath
                            query = ""
                        }
                        if (selectedRoot != FileRootKey.DEVICE) {
                            DialogActionRow("打包导出目录到 Download", Glyph.Share) {
                                actionTarget = null
                                busy = true
                                scope.launch {
                                    val zipName = "${entry.name}-${System.currentTimeMillis() / 1000}.zip"
                                    val out = withContext(Dispatchers.IO) {
                                        EnvKit.exportDirectoryAsZip(context, entry.file, zipName)
                                    }
                                    busy = false
                                    if (out != null) {
                                        onNotify("已导出到 Download/CodeForge/${out.name}", false)
                                    } else {
                                        onNotify("目录为空或导出失败", true)
                                    }
                                }
                            }
                        }
                    } else {
                        if (canPreviewText) {
                            DialogActionRow("查看文本内容", Glyph.CodeBrackets) {
                                val file = entry.file
                                actionTarget = null
                                scope.launch {
                                    val loaded = withContext(Dispatchers.IO) { readTextPreview(file) }
                                    if (loaded != null) {
                                        previewState = loaded
                                    } else {
                                        onNotify("无法按文本读取该文件", true)
                                    }
                                }
                            }
                        }
                        DialogActionRow("用系统应用打开 / 播放", Glyph.Share) {
                            actionTarget = null
                            launchMediaUri(context, entry.file.absolutePath, activeWorkspaceRoot)
                        }
                        DialogActionRow("作为附件发送", Glyph.Plus) {
                            actionTarget = null
                            onAttachToChat(entry.file)
                            onDismiss()
                        }
                        DialogActionRow("引用路径到对话", Glyph.ChatBubble) {
                            actionTarget = null
                            onQuoteToChat(relForChat)
                            onDismiss()
                        }
                        DialogActionRow("导出到 Download/CodeForge", Glyph.ArrowDown) {
                            actionTarget = null
                            busy = true
                            scope.launch {
                                val out = withContext(Dispatchers.IO) {
                                    EnvKit.exportFileToDownloads(context, entry.file)
                                }
                                busy = false
                                if (out != null) {
                                    onNotify("已导出：Download/CodeForge/${out.name}", false)
                                } else {
                                    onNotify("导出失败，请检查公共下载目录权限", true)
                                }
                            }
                        }
                    }
                    DialogActionRow("复制路径", Glyph.Copy) {
                        actionTarget = null
                        copyToClipboard(context, "path", relForChat)
                        onNotify("已复制路径：$relForChat", false)
                    }
                    if (allowDelete) {
                        DialogActionRow("重命名", Glyph.Edit) {
                            actionTarget = null
                            renameTarget = entry
                            renameInput = entry.name
                        }
                        DialogActionRow("删除", Glyph.Close, danger = true) {
                            actionTarget = null
                            deleteTarget = entry
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { actionTarget = null }) { Text("关闭") }
            },
        )
    }

    // 文本预览
    previewState?.let { preview ->
        AlertDialog(
            onDismissRequest = { previewState = null },
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 1f),
            title = {
                Column {
                    Text(
                        text = preview.file.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (preview.truncated) {
                        Text(
                            text = "文件超过 256 KB，仅预览前段内容",
                            style = MaterialTheme.typography.labelSmall,
                            color = Clay,
                        )
                    }
                }
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 380.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                ) {
                    Text(
                        text = preview.content,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    copyToClipboard(context, preview.file.name, preview.content)
                    onNotify("已复制文本内容", false)
                }) { Text("复制全文") }
            },
            confirmButton = {
                TextButton(onClick = { previewState = null }) { Text("关闭") }
            },
        )
    }

    // 新建文件夹
    if (showNewFolder) {
        AlertDialog(
            onDismissRequest = { showNewFolder = false },
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 1f),
            title = { Text("新建文件夹") },
            text = {
                OutlinedTextField(
                    value = newFolderInput,
                    onValueChange = { newFolderInput = it },
                    singleLine = true,
                    placeholder = { Text("输入文件夹名称") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            dismissButton = {
                TextButton(onClick = { showNewFolder = false }) { Text("取消") }
            },
            confirmButton = {
                TextButton(onClick = {
                    val parent = currentDir
                    val name = newFolderInput
                    if (parent == null || !EnvKit.isSafeEntryName(name)) {
                        onNotify("文件夹名称不合法", true)
                        return@TextButton
                    }
                    showNewFolder = false
                    scope.launch {
                        val created = withContext(Dispatchers.IO) { EnvKit.createSubdir(parent, name) }
                        reloadSeq++
                        if (created != null) {
                            onNotify("已创建文件夹：${created.name}", false)
                        } else {
                            onNotify("创建失败，可能已存在同名文件", true)
                        }
                    }
                }) { Text("创建") }
            },
        )
    }

    // 重命名
    renameTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 1f),
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            },
            confirmButton = {
                TextButton(onClick = {
                    val newName = renameInput
                    if (!EnvKit.isSafeEntryName(newName)) {
                        onNotify("名称不合法（不能包含 / \\ : 等符号）", true)
                        return@TextButton
                    }
                    renameTarget = null
                    scope.launch {
                        val renamed = withContext(Dispatchers.IO) { EnvKit.renameEntry(entry.file, newName) }
                        reloadSeq++
                        if (renamed != null) {
                            onNotify("已重命名为 ${renamed.name}", false)
                        } else {
                            onNotify("重命名失败，目标可能已存在", true)
                        }
                    }
                }) { Text("确定") }
            },
        )
    }

    // 删除确认
    deleteTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 1f),
            title = { Text("确认删除") },
            text = {
                Text(
                    if (entry.isDir) {
                        "将永久删除文件夹“${entry.name}”及其全部内容，且不可恢复。"
                    } else {
                        "将永久删除文件“${entry.name}”（${EnvKit.formatFileSize(entry.sizeBytes)}），且不可恢复。"
                    },
                )
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
            confirmButton = {
                TextButton(onClick = {
                    val root = rootDir
                    deleteTarget = null
                    if (root == null) return@TextButton
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            EnvKit.deleteRecursivelySafe(entry.file, root)
                        }
                        reloadSeq++
                        if (ok) {
                            onNotify("已删除 ${entry.name}", false)
                        } else {
                            onNotify("删除失败或没有权限", true)
                        }
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
        )
    }

    // 清空暂存与缓存确认
    if (showClearCacheConfirm) {
        AlertDialog(
            onDismissRequest = { showClearCacheConfirm = false },
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 1f),
            title = { Text("清空暂存与缓存") },
            text = { Text("将清理本地待发暂存（outbox）、相机临时照片与过期日志；不会删除各项目工作区里的文件。") },
            dismissButton = {
                TextButton(onClick = { showClearCacheConfirm = false }) { Text("取消") }
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearCacheConfirm = false
                    busy = true
                    scope.launch {
                        val count = withContext(Dispatchers.IO) {
                            EnvKit.clearDirectoryContents(EnvKit.outboxDir(context)) +
                                EnvKit.clearDirectoryContents(File(context.cacheDir, "camera")) +
                                EnvKit.clearDirectoryContents(File(context.cacheDir, "logs"))
                        }
                        busy = false
                        reloadSeq++
                        onNotify("已清理 $count 项暂存与缓存文件", false)
                    }
                }) { Text("清理", color = Clay) }
            },
        )
    }
}

@Composable
private fun ToolChip(
    label: String,
    enabled: Boolean = true,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (accent && enabled) {
            Clay.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.55f else 0.25f)
        },
        border = if (accent && enabled) BorderStroke(0.8.dp, Clay.copy(alpha = 0.4f)) else null,
        modifier = Modifier.noRippleClickable(enabled = enabled, onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                accent -> Clay
                else -> MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun FileRow(
    entry: FsEntry,
    onClick: () -> Unit,
    onMore: () -> Unit,
) {
    val glyph = remember(entry.isDir, entry.name) { glyphForEntry(entry) }
    val tint = if (entry.isDir) Clay else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
        modifier = Modifier
            .fillMaxWidth()
            .border(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .noRippleClickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GlyphIcon(glyph = glyph, size = 18.dp, color = tint)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (entry.isDir) FontWeight.Medium else FontWeight.Normal,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = if (entry.isDir) {
                    "文件夹 · ${formatTime(entry.lastModified)}"
                } else {
                    "${EnvKit.formatFileSize(entry.sizeBytes)} · ${formatTime(entry.lastModified)}"
                }
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .noRippleClickable(onClick = onMore),
                contentAlignment = Alignment.Center,
            ) {
                GlyphIcon(
                    glyph = Glyph.MenuDots,
                    size = 15.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DialogActionRow(
    label: String,
    glyph: Glyph,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .noRippleClickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlyphIcon(glyph = glyph, size = 16.dp, color = color)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun glyphForEntry(entry: FsEntry): Glyph = when {
    entry.isDir -> Glyph.Folder
    entry.name.isImagePath() || isVideoPath(entry.name) -> Glyph.Photo
    isAudioPath(entry.name) -> Glyph.Speaker
    isPreviewableTextFile(entry.file) -> Glyph.CodeBrackets
    else -> Glyph.FileDoc
}

private fun readDirSnapshot(dir: File): DirSnapshot {
    if (!dir.isDirectory || !dir.canRead()) {
        return DirSnapshot(emptyList(), 0L, unreadable = true)
    }
    val raw = dir.listFiles() ?: return DirSnapshot(emptyList(), 0L, unreadable = true)
    var total = 0L
    val list = raw.map { f ->
        val size = if (f.isFile) f.length().also { total += it } else 0L
        FsEntry(
            file = f,
            name = f.name,
            isDir = f.isDirectory,
            sizeBytes = size,
            lastModified = f.lastModified(),
        )
    }
    return DirSnapshot(list, total, unreadable = false)
}

private fun readTextPreview(file: File): TextPreviewState? {
    if (!file.isFile || !file.canRead()) return null
    return runCatching {
        val len = file.length()
        val limit = MAX_PREVIEW_BYTES.toInt()
        val bytes = file.inputStream().use { input ->
            val buf = ByteArray(limit)
            var offset = 0
            while (offset < limit) {
                val n = input.read(buf, offset, limit - offset)
                if (n <= 0) break
                offset += n
            }
            buf.copyOf(offset)
        }
        TextPreviewState(
            file = file,
            content = bytes.toString(Charsets.UTF_8),
            truncated = len > MAX_PREVIEW_BYTES,
        )
    }.getOrNull()
}

private fun relativeBreadcrumb(rootDir: File?, currentDir: File?): String {
    if (rootDir == null || currentDir == null) return "/"
    val rootCanon = runCatching { rootDir.canonicalFile }.getOrNull() ?: return currentDir.absolutePath
    val curCanon = runCatching { currentDir.canonicalFile }.getOrNull() ?: return currentDir.absolutePath
    if (rootCanon == curCanon) return rootCanon.absolutePath
    val rel = runCatching { rootCanon.toPath().relativize(curCanon.toPath()).toString().replace('\\', '/') }
        .getOrNull()
        .orEmpty()
    return if (rel.isBlank()) rootCanon.absolutePath else "${rootCanon.name}/$rel"
}

private fun formatPathForChat(file: File, activeWorkspaceRoot: String): String {
    if (activeWorkspaceRoot.isNotBlank()) {
        val rootCanon = runCatching { File(activeWorkspaceRoot).canonicalFile }.getOrNull()
        val fileCanon = runCatching { file.canonicalFile }.getOrNull()
        if (rootCanon != null && fileCanon != null) {
            val prefix = rootCanon.path.trimEnd(File.separatorChar) + File.separator
            if (fileCanon.path.startsWith(prefix)) {
                return rootCanon.toPath().relativize(fileCanon.toPath()).toString().replace('\\', '/')
            }
        }
    }
    return file.absolutePath
}

private fun formatTime(ms: Long): String =
    if (ms <= 0L) "" else DateFormat.format("MM-dd HH:mm", Date(ms)).toString()

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}
