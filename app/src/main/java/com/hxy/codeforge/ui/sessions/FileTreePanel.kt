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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hxy.codeforge.model.TreeEntry
import com.hxy.codeforge.ui.common.Glyph
import com.hxy.codeforge.ui.common.GlyphIcon
import com.hxy.codeforge.ui.common.noRippleClickable
import com.hxy.codeforge.ui.theme.Clay
import com.hxy.codeforge.ui.theme.SerifDisplay

/**
 * 工作区文件树（放大到整屏 / 整个侧栏）。
 *
 * 数据来自内核 `GET /api/tree?path=&depth=1`（[TreeEntry] 列表）。
 * 选择工作区时，「选择此文件夹」取的是**当前列出的目录**；`..` 回上级。
 * 关闭/选完的缩小动画由外层 AnimatedVisibility 负责（参考 MCP 面板的放缩）。
 *
 * 为什么每次打开都从根开始：文件树是一次性选择动作，留在上次的深目录里
 * 只会让人多点几层返回；根目录瞬间可达。
 */
@Composable
fun FileTreePanel(
    entries: List<TreeEntry>,
    path: String,
    parent: String,
    error: String?,
    onLoad: (String) -> Unit,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { onLoad("") }

    // 当前所在目录：path 为空表示内核把根目录列出来了，退回 parent 兜底
    val current = path.ifBlank { parent }
    val canPick = current.isNotBlank()

    // 选中壁纸时 theme.background 为透明；文件树作为叠加层需要实底，防止透出底层内容。
    val panelBg = MaterialTheme.colorScheme.background.let {
        if (it.alpha < 1f) MaterialTheme.colorScheme.surface.copy(alpha = 1f) else it
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(panelBg)
            .noRippleClickable {},
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左上角返回：关闭文件树，回到原来的侧栏
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
                    text = "选择工作区",
                    style = SerifDisplay.copy(fontSize = 19.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = current.ifBlank { "根目录" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "刷新",
                style = MaterialTheme.typography.labelSmall,
                color = Clay,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .noRippleClickable { onLoad(path) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // 权限错误（内核返回的 error）就地显示：文件树空着又没提示会让人以为没加载
        if (!error.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        // 目录在前、文件在后；同类按名字排序（内核返回顺序不保证稳定）
        val sorted = entries.sortedWith(
            compareByDescending<TreeEntry> { it.isDir }.thenBy { it.name.lowercase() },
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 6.dp),
        ) {
            // `..` 返回上级：根目录（path 为空）时不出现；parent 为空时回到根
            if (path.isNotBlank()) {
                item(key = "__up__") {
                    TreeRow(
                        name = "..",
                        isDir = true,
                        trailing = "上级目录",
                        upArrow = true,
                        onClick = { onLoad(parent) },
                    )
                }
            }
            items(sorted, key = { it.path }) { entry ->
                TreeRow(
                    name = entry.name,
                    isDir = entry.isDir,
                    trailing = if (entry.isDir) "" else sizeLabel(entry.size),
                    onClick = { if (entry.isDir) onLoad(entry.path) },
                )
            }
            if (sorted.isEmpty() && error.isNullOrBlank()) {
                item(key = "__empty__") {
                    Text(
                        // 根目录（path 为空）就列不出东西 = 容器里没有可挂载目录。
                        // 这时必须明说「可以跳过」，否则用户会以为非选一个目录不可
                        text = if (path.isBlank()) {
                            "这里没有可挂载的目录。可以直接跳过，自动创建一个独立工作区。"
                        } else {
                            "这个目录是空的"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // 底部：选择工作区。选的是当前所在目录（不是文件）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = if (canPick) current else "请先进入一个文件夹",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            if (canPick) Clay else MaterialTheme.colorScheme.surfaceVariant,
                        )
                        .noRippleClickable(enabled = canPick) { if (canPick) onPick(current) }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = "选择工作区",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (canPick) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            // 「不选择」始终可用：容器里可能压根没有可挂载目录，
            // 不给这个出口的话，空列表面前用户就永远建不出项目
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .noRippleClickable { onPick("") }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "不选择（自动创建独立工作区）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 一行文件/目录：目录可点进入，文件只展示大小。 */
@Composable
private fun TreeRow(
    name: String,
    isDir: Boolean,
    trailing: String,
    upArrow: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .padding(horizontal = 10.dp, vertical = 1.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .noRippleClickable(onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (upArrow) {
            GlyphIcon(
                glyph = Glyph.Chevron,
                size = 13.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(180f),
            )
        } else {
            GlyphIcon(
                glyph = if (isDir) Glyph.Folder else Glyph.FileDoc,
                size = 14.dp,
                color = if (isDir) Clay else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isDir) FontWeight.Medium else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailing.isNotBlank()) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** 文件大小：只做 KB/MB 两档，精确字节数在选目录场景没有意义。 */
private fun sizeLabel(bytes: Long): String = when {
    bytes <= 0L -> ""
    bytes < 1024 -> "${bytes} B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}
