package app.codeforge.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeforge.model.SessionItem
import app.codeforge.ui.common.Glyph
import app.codeforge.ui.common.GlyphIcon
import app.codeforge.ui.common.noRippleClickable
import app.codeforge.ui.theme.Clay
import app.codeforge.ui.theme.SerifTitle
import java.util.concurrent.TimeUnit

/**
 * 会话侧栏，对齐 Claude 客户端的抽屉：
 *
 *  - 顶部是衬线大标题；下面一列导航（对话高亮，其余为占位导航，
 *    等对应页面在 UI 层铺开后接真路由）；
 *  - 中部「最近对话」列表，选中项暖灰圆角高亮；
 *  - 底部固定：左侧头像（点按 = 设置）、右侧深色「新建对话」胶囊。
 *
 * 同一个组件用在两处：竖屏抽屉、横屏左栏。布局差异交给外层容器控制宽度。
 */
@Composable
fun SessionPane(
    sessions: List<SessionItem>,
    currentId: String,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    onOpenSettings: () -> Unit,
    onSessionLongPress: (SessionItem) -> Unit = {},
    archived: List<SessionItem> = emptyList(),
    onExpandArchived: () -> Unit = {},
    onRestoreArchived: (String) -> Unit = {},
    /** 批量删除（侧栏「管理」模式下选中若干条后调用）。 */
    onDeleteSessions: (List<String>) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // 批量管理模式：标题右侧「管理」进入，行首出现勾选圈，底部「新建对话」换成「删除」。
    var manageMode by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(setOf<String>()) }

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = "CodeForge",
            style = SerifTitle.copy(fontSize = 30.sp),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )

        // 导航列：只有「对话」是真的，其余是占位 —— 点击不响应也不报错
        NavRow(label = "对话", glyph = Glyph.ChatBubble, active = true, onClick = null)
        NavRow(label = "项目", glyph = Glyph.Folder, active = false, onClick = null)
        NavRow(label = "Code", glyph = Glyph.CodeBrackets, active = false, onClick = null)
        NavRow(label = "Artifacts", glyph = Glyph.Cube, active = false, onClick = null)

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(vertical = 10.dp),
        )

        // 本地搜索过滤（B4 会话侧栏）：按标题包含，忽略大小写
        var query by remember { mutableStateOf("") }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("搜索会话", style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 6.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "最近对话",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (sessions.isNotEmpty()) {
                Text(
                    text = if (manageMode) "完成" else "管理",
                    style = MaterialTheme.typography.labelSmall,
                    color = Clay,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .noRippleClickable {
                            manageMode = !manageMode
                            picked = emptySet()
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        val q = query.trim()
        val filtered = if (q.isEmpty()) sessions else sessions.filter {
            it.title.contains(q, ignoreCase = true)
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            items(filtered, key = { it.id }) { session ->
                SessionRow(
                    session = session,
                    selected = if (manageMode) session.id in picked else session.id == currentId,
                    manageMode = manageMode,
                    checked = session.id in picked,
                    onClick = {
                        if (manageMode) {
                            picked = if (session.id in picked) {
                                picked - session.id
                            } else {
                                picked + session.id
                            }
                        } else {
                            onSelect(session.id)
                        }
                    },
                    onLongPress = { onSessionLongPress(session) },
                )
            }

            // 已归档折叠组（B4 会话侧栏）：默认收起，展开时拉取；点击行 = 恢复
            item(key = "__archived__") {
                var expanded by remember { mutableStateOf(false) }
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                expanded = !expanded
                                if (expanded) onExpandArchived()
                            }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "已归档",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = if (expanded) "收起" else "展开",
                            style = MaterialTheme.typography.labelSmall,
                            color = Clay,
                        )
                    }
                    if (expanded) {
                        if (archived.isEmpty()) {
                            Text(
                                text = "（空）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                            )
                        }
                        archived.forEach { session ->
                            Row(
                                modifier = Modifier
                                    .padding(horizontal = 12.dp, vertical = 2.dp)
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onRestoreArchived(session.id) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = session.title.ifBlank { "已归档会话" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { onRestoreArchived(session.id) }) {
                                    Text("恢复", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }

        BottomBar(
            onCreate = onCreate,
            onOpenSettings = onOpenSettings,
            manageMode = manageMode,
            picked = picked,
            onDeleteSessions = onDeleteSessions,
            onExitManage = {
                picked = emptySet()
                manageMode = false
            },
        )
    }
}

// ---------------------------------------------------------------- 导航

@Composable
private fun NavRow(label: String, glyph: Glyph, active: Boolean, onClick: (() -> Unit)?) {
    val color = if (active) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (active || onClick != null) 1f else 0.55f)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlyphIcon(glyph = glyph, size = 18.dp, color = color)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            ),
            color = color,
        )
    }
}

// ---------------------------------------------------------------- 会话行

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    session: SessionItem,
    selected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    manageMode: Boolean = false,
    checked: Boolean = false,
) {
    val bg = if (selected) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.surface
    }

    Row(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongPress,
            )
            .heightIn(min = 46.dp)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 管理模式：行首的对话图标换成勾选圈，一眼能看出哪些被选中
        if (manageMode) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(
                        if (checked) Clay else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (checked) {
                    GlyphIcon(
                        glyph = Glyph.Check,
                        size = 11.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.4.dp,
                    )
                }
            }
        } else {
            GlyphIcon(
                glyph = Glyph.ChatBubble,
                size = 14.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Text(
            text = session.title.ifBlank { "未命名会话" },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (session.busy) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(Clay, CircleShape),
            )
        }
    }
}

// ---------------------------------------------------------------- 底栏

@Composable
private fun BottomBar(
    onCreate: () -> Unit,
    onOpenSettings: () -> Unit,
    manageMode: Boolean,
    picked: Set<String>,
    onDeleteSessions: (List<String>) -> Unit,
    onExitManage: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 头像 = 设置入口（暂用首字母圆点，等账号体系接入后换真头像）
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(Clay, CircleShape)
                .noRippleClickable(onClick = onOpenSettings),
            contentAlignment = Alignment.Center,
        ) {
            // 齿轮比首字母 "C" 更能说明这是设置入口 ——
            // 用户反馈「只有一个 C，不知道能不能点、点了是什么」
            GlyphIcon(
                glyph = Glyph.Gear,
                size = 17.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }

        Spacer(Modifier.weight(1f))

        if (manageMode) {
            // 管理态：新建换成删除。选中为空时按钮压暗且不响应，
            // 避免误点后毫无反应。
            val n = picked.size
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        if (n > 0) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    )
                    .noRippleClickable(enabled = n > 0) {
                        onDeleteSessions(picked.toList())
                        onExitManage()
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = if (n > 0) "删除 $n 个会话" else "选择要删除的会话",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (n > 0) {
                        MaterialTheme.colorScheme.onError
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.onBackground)
                    .noRippleClickable(onClick = onCreate)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                GlyphIcon(
                    glyph = Glyph.Plus,
                    size = 13.dp,
                    color = MaterialTheme.colorScheme.background,
                    strokeWidth = 2.dp,
                )
                Text(
                    text = "新建对话",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.background,
                )
            }
        }
    }
}

/**
 * 相对时间。
 *
 * 只做到「天」这一级，再久就显示日期 —— 会话列表里精确到分钟没有意义，
 * 反而占地方。（当前抽屉样式里没有展示时间的位置，留作会话行二期扩展。）
 */
private fun relativeTime(ts: Long): String {
    if (ts <= 0L) return ""
    val now = System.currentTimeMillis()
    val diff = now - ts
    if (diff < 0) return "刚刚"

    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    val hours = TimeUnit.MILLISECONDS.toHours(diff)
    val days = TimeUnit.MILLISECONDS.toDays(diff)

    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes} 分钟前"
        hours < 24 -> "${hours} 小时前"
        days < 7 -> "${days} 天前"
        else -> {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = ts }
            val m = cal.get(java.util.Calendar.MONTH) + 1
            val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
            "$m 月 $d 日"
        }
    }
}
