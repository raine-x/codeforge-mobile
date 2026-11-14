package com.hxy.codeforge.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.hxy.codeforge.core.KernelApi
import com.hxy.codeforge.model.ActionPanelState
import com.hxy.codeforge.model.ContextUsage
import com.hxy.codeforge.model.PermissionMode
import com.hxy.codeforge.ui.common.Glyph
import com.hxy.codeforge.ui.common.GlyphIcon
import com.hxy.codeforge.ui.common.ZhSpeechInput
import com.hxy.codeforge.ui.common.noRippleClickable
import com.hxy.codeforge.ui.common.rememberZhSpeechInput
import com.hxy.codeforge.ui.common.zhSpeechAvailable
import com.hxy.codeforge.ui.settings.AppSettings
import com.hxy.codeforge.ui.theme.Clay
import com.hxy.codeforge.ui.theme.LocalAppTextColor
import com.hxy.codeforge.voice.CloudVoiceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.min


// ---------------------------------------------------------------- 附件面板

/**
 * DeepSeek 式附件面板：上半是「最近文件」横滑选择（点选即入待发列表，
 * 再点取消），下半是 拍照 / 相册 / 文件 三个动作。最近文件取
 * workspace/attachments 按修改时间倒序的前 10 个。
 */
@Composable
internal fun AttachPanel(
    selected: List<String>,
    onToggle: (String) -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onTakePhoto: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenWorkspaceFiles: (() -> Unit)?,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var recent by remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(Unit) {
        recent = withContext(Dispatchers.IO) {
            val dir = java.io.File(java.io.File(ctx.filesDir, "workspace"), "attachments")
            dir.listFiles()?.filter { it.isFile }
                ?.sortedByDescending { it.lastModified() }
                ?.take(10)
                ?.map { "attachments/" + it.name }
                .orEmpty()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        // 二级入口摆在面板最上面：Skills 和技能（上拉框选完插入 @）、当前目录的文件
        PanelEntryRow(
            title = "Skills 和技能",
            subtitle = "技能与内置插件，点选即插入 @ 提及",
            glyph = Glyph.Cube,
            onClick = onOpenSkills,
        )
        if (onOpenWorkspaceFiles != null) {
            PanelEntryRow(
                title = "当前目录的文件",
                subtitle = "浏览工作区文件并引用到对话",
                glyph = Glyph.Folder,
                onClick = onOpenWorkspaceFiles,
            )
        }
        Spacer(Modifier.height(8.dp))
        if (recent.isEmpty()) {
            Text(
                text = "暂无最近文件，从下方拍照 / 相册 / 文件添加",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            )
        } else {
            Text(
                text = "最近文件",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                recent.forEach { rel ->
                    RecentFileCell(
                        rel = rel,
                        selected = rel in selected,
                        onClick = { onToggle(rel) },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AttachCell("拍照", Glyph.Camera, Modifier.weight(1f), onClick = onTakePhoto)
            AttachCell("相册", Glyph.Photo, Modifier.weight(1f), onClick = onPickImage)
            AttachCell("文件", Glyph.FileDoc, Modifier.weight(1f), onClick = onPickFile)
        }
    }
}

/** 最近文件单元：图片出缩略图、其余出图标 + 文件名；右上角选中圈。 */
@Composable
private fun RecentFileCell(rel: String, selected: Boolean, onClick: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var bmp by remember(rel) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(rel) {
        if (rel.isImagePath()) {
            bmp = withContext(Dispatchers.IO) {
                runCatching { decodeThumb(attachmentFile(ctx, rel), 256) }.getOrNull()
            }
        }
    }
    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .then(
                if (selected) {
                    Modifier.border(2.dp, Clay, RoundedCornerShape(12.dp))
                } else {
                    Modifier.border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(12.dp),
                    )
                },
            )
            .noRippleClickable(onClick = onClick),
    ) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = rel.substringAfterLast('/'),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.size(76.dp),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                GlyphIcon(
                    glyph = Glyph.FileDoc,
                    size = 18.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = rel.substringAfterLast('/').take(14),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        // 右上角选中圈：未选灰点、选中陶土圆 + 白勾
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(20.dp)
                .background(
                    if (selected) Clay else Color(0x99888888),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                GlyphIcon(glyph = Glyph.Check, size = 11.dp, color = Color.White)
            }
        }
    }
}

@Composable
private fun AttachCell(
    label: String,
    glyph: Glyph,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit = {},
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                .noRippleClickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(
                glyph = glyph,
                size = 22.dp,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                },
                strokeWidth = 1.6.dp,
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            },
        )
    }
}


// ---------------------------------------------------------------- 内嵌附件片

/** 按扩展名归类上色：不同类型一眼可辨。 */
private fun attachCategoryColor(rel: String): Color {
    val ext = rel.substringAfterLast('.', "").lowercase()
    return when (ext) {
        in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp") -> Color(0xFF3AA6A0)
        in setOf("py", "js", "ts", "kt", "java", "c", "h", "cpp", "go", "rs", "sh", "html", "css", "sql") -> Color(0xFF4C8BE8)
        in setOf("md", "txt", "doc", "docx", "pdf") -> Color(0xFF3E9B4F)
        in setOf("mp3", "wav", "flac", "aac", "ogg") -> Color(0xFF9C6ADE)
        in setOf("mp4", "mkv", "avi", "mov", "webm") -> Color(0xFFE57A6E)
        in setOf("zip", "tar", "tgz", "gz", "7z", "rar") -> Color(0xFFE8A33D)
        else -> Color(0xFF8A877E)
    }
}

/** 输入卡内嵌附件片：图片 = 44dp 圆角缩略图；文件 = 类型色点 + 名字。 */
@Composable
internal fun AttachmentInline(rel: String, sessionId: String, onRemove: () -> Unit) {
    val isImage = rel.isImagePath()
    Box {
        if (isImage) {
            val context = LocalContext.current
            // 编辑回填取回的是相对路径，只有知道当前工作区根才解得开
            val root = LocalAttachmentRoot.current
            val bmp = remember(rel, sessionId, root) {
                runCatching {
                    // 暂存区落的是绝对路径（首选）；其余几处是历史/编辑回填的相对路径，
                    // 依次按当前工作区根、会话空间、工作区挂载点、应用根探测，找到哪个用哪个
                    val path = listOfNotNull(
                        java.io.File(rel).takeIf { it.isFile },
                        if (root.isNotBlank()) java.io.File(root, rel).takeIf { it.isFile } else null,
                        java.io.File(com.hxy.codeforge.core.EnvKit.sessionSpace(context, sessionId), rel)
                            .takeIf { it.isFile },
                        java.io.File(com.hxy.codeforge.core.EnvKit.cellRoot(context), "workspace/" + rel)
                            .takeIf { it.isFile },
                        java.io.File(com.hxy.codeforge.core.EnvKit.cellRoot(context), rel)
                            .takeIf { it.isFile },
                    ).firstOrNull() ?: return@runCatching null
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(path.absolutePath, bounds)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 128) sample *= 2
                    android.graphics.BitmapFactory.decodeFile(
                        path.absolutePath,
                        android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
                    )
                }.getOrNull()
            }
            if (bmp != null) {
                androidx.compose.foundation.Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(attachCategoryColor(rel).copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("IMG", style = MaterialTheme.typography.labelSmall, color = attachCategoryColor(rel))
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(attachCategoryColor(rel).copy(alpha = 0.13f))
                    .padding(horizontal = 9.dp, vertical = 7.dp)
                    .heightIn(min = 32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(attachCategoryColor(rel), CircleShape),
                )
                Text(
                    text = rel.substringAfterLast('/').take(16),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
        // 移除角标：右上角小 ×
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(16.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "×",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Box(modifier = Modifier.matchParentSize().noRippleClickable(onClick = onRemove))
    }
}

/**
 * 内置浏览器挑出来的页面元素 chip。
 *
 * 显示「元素N · 标签 · 文本摘要」。× 撤掉这一项（发送时就不会带它）。
 */
@Composable
internal fun ElementChipInline(ref: String, summary: String, onRemove: () -> Unit) {
    val accent = com.hxy.codeforge.ui.theme.Clay
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(accent.copy(alpha = 0.13f))
                .padding(horizontal = 9.dp, vertical = 7.dp)
                .heightIn(min = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(accent, CircleShape),
            )
            Text(
                text = ref,
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                maxLines = 1,
                modifier = Modifier.padding(start = 6.dp),
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        // 移除角标：右上角小 ×
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(16.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "×",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Box(modifier = Modifier.matchParentSize().noRippleClickable(onClick = onRemove))
    }
}


// ---------------------------------------------------------------- Skills 和技能

/** 附件面板里的一个二级入口行（标题 + 说明 + 圆底图标）。 */
@Composable
private fun PanelEntryRow(title: String, subtitle: String, glyph: Glyph, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .noRippleClickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(MaterialTheme.colorScheme.surface, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(glyph = glyph, size = 15.dp, color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 「Skills 和技能」上拉框：样式与模型弹层一致（同圆角、同头部、同行样式），
 * 分两组列出技能与内置插件，点任意一行插入该条目的 @ 提及。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SkillsSheet(
    skills: List<Pair<String, String>>,
    builtinPlugins: List<Triple<String, String, Boolean>>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
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
                .padding(bottom = 24.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .height(36.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) {
                    GlyphIcon(
                        glyph = Glyph.Close,
                        size = 17.dp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    text = "Skills 和技能",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                SheetSectionLabel("技能")
                skills.forEach { (slug, desc) ->
                    SheetEntryRow(
                        title = slug.removePrefix("@"),
                        subtitle = desc,
                        kind = "技能",
                        onClick = { onPick(slug.removePrefix("@")) },
                    )
                }
                if (skills.isEmpty()) SheetEmptyRow("内核未返回技能列表")

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SheetSectionLabel("内置插件")
                builtinPlugins.forEach { (id, name, enabled) ->
                    SheetEntryRow(
                        title = name.ifBlank { id.removePrefix("@") },
                        subtitle = "@" + id.removePrefix("@") + if (enabled) "" else " · 已关闭",
                        kind = "插件",
                        onClick = { onPick(id.removePrefix("@")) },
                    )
                }
                if (builtinPlugins.isEmpty()) SheetEmptyRow("内核未返回内置插件")
            }
        }
    }
}

@Composable
private fun SheetSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
private fun SheetEntryRow(title: String, subtitle: String, kind: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = kind,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun SheetEmptyRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
}
