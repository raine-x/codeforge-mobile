package app.codeforge.ui.sessions

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeforge.model.ProjectItem
import app.codeforge.model.SessionItem
import app.codeforge.ui.common.Glyph
import app.codeforge.ui.common.GlyphIcon
import app.codeforge.ui.common.noRippleClickable
import app.codeforge.ui.theme.Clay
import app.codeforge.ui.theme.SerifTitle

/**
 * 会话侧栏，对齐 Claude 客户端的抽屉：
 *
 *  - 顶部「新建项目」入口 + 导航（对话 / MCP）；
 *  - 中部按**工作区（项目）分组**：项目行带折叠箭头、项目名、＋新建会话、⋯菜单，
 *    项目下缩进列出属于它的会话；不属于任何项目的旧会话归到「其他对话」；
 *  - 底部固定：设置入口 + 新建对话胶囊。
 *
 * 归档会话不在这里出现 —— 用户要求归档只留在设置页的归档管理里，
 * 侧栏挂一个「已归档」折叠组反而让主列表看起来少了东西却又能点回来。
 *
 * 同一个组件用在两处：竖屏抽屉、横屏左栏。布局差异交给外层容器控制宽度。
 */
@Composable
fun SessionPane(
    projects: List<ProjectItem>,
    sessions: List<SessionItem>,
    currentId: String,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    onNewProject: () -> Unit,
    onOpenSettings: () -> Unit,
    currentWorkspace: String = "",
    currentWorkspaceName: String = "",
    workspaceSwitching: Boolean = false,
    onOpenMcp: () -> Unit = {},
    onSelectProject: (ProjectItem) -> Unit = {},
    onProjectLongPress: (ProjectItem) -> Unit = {},
    onNewSessionInProject: (String) -> Unit = {},
    onSessionLongPress: (SessionItem) -> Unit = {},
    /** 批量删除（侧栏「管理」模式下选中若干条后调用）。 */
    onDeleteSessions: (List<String>) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // 批量管理模式：行首出现勾选圈，底部「新建对话」换成「删除」。
    var manageMode by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    // 折叠态：key = workspace。默认展开（新建好的项目要能立刻看到会话）。
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    var query by remember { mutableStateOf("") }

    val q = query.trim()
    fun matches(text: String): Boolean = q.isEmpty() || text.contains(q, ignoreCase = true)

    val shownProjects = projects.filterNot { it.archived }
    // 归档会话一律不进侧栏（用户要求归档只留在设置页的归档管理里）
    val visible = sessions.filterNot { it.archived }
    val projectSpaces = shownProjects.map { it.workspace }.toSet()
    // 不属于任何项目的会话（旧的「独立空间」会话、内核没带上 workspace 的历史）
    val loose = visible.filter { it.workspace !in projectSpaces }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 14.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "CodeForge",
                style = SerifTitle.copy(fontSize = 26.sp),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            // 顶部入口：原来这里是占位的导航，现在改成一个动作 —— 新建项目
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .noRippleClickable(onClick = onNewProject)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                GlyphIcon(
                    glyph = Glyph.Plus,
                    size = 11.dp,
                    color = MaterialTheme.colorScheme.onSurface,
                    strokeWidth = 2.dp,
                )
                Text(
                    text = "新建项目",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        // 导航：只有「对话」是当前页；MCP 铺满侧栏（点击后由外层切换到 MCP 面板）
        NavRow(label = "对话", glyph = Glyph.ChatBubble, active = true, onClick = null)
        NavRow(label = "MCP", glyph = Glyph.Cube, active = false, onClick = onOpenMcp)

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )

        // 本地搜索过滤：按标题包含，忽略大小写
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
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "项目",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            // 当前工作区名 + 切换中。工作区切换是异步的，不显示状态会像「点不动」
            if (workspaceSwitching) {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    strokeWidth = 1.4.dp,
                    color = Clay,
                )
            }
            Text(
                text = currentWorkspaceName.ifBlank { "未选择工作区" },
                style = MaterialTheme.typography.labelSmall,
                color = if (currentWorkspaceName.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    Clay
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 96.dp),
            )
            if (visible.isNotEmpty()) {
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
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            shownProjects.forEach { project ->
                val inProject = visible.filter { it.workspace == project.workspace && matches(it.title) }
                val count = visible.count { it.workspace == project.workspace }
                // 搜索态：项目名没命中且其下没有命中会话就不占位
                if (q.isNotEmpty() && !matches(project.name) && inProject.isEmpty()) {
                    return@forEach
                }
                item(key = "project-${project.workspace}") {
                    ProjectRow(
                        project = project,
                        sessionCount = count,
                        current = project.workspace == currentWorkspace,
                        expanded = !(collapsed[project.workspace] ?: false),
                        switching = workspaceSwitching,
                        onToggle = {
                            collapsed[project.workspace] = !(collapsed[project.workspace] ?: false)
                        },
                        onSelect = { onSelectProject(project) },
                        onNewSession = { onNewSessionInProject(project.workspace) },
                        onMenu = { onProjectLongPress(project) },
                    )
                }
                // 搜索时强制展开，否则结果被折叠状态藏起来
                if (q.isNotEmpty() || !(collapsed[project.workspace] ?: false)) {
                    items(inProject, key = { "session-${it.id}" }) { session ->
                        SessionRow(
                            session = session,
                            selected = if (manageMode) session.id in picked else session.id == currentId,
                            manageMode = manageMode,
                            checked = session.id in picked,
                            indented = true,
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
                }
            }

            // 没有项目归属的会话：单独一组，避免它们从侧栏消失
            val looseShown = loose.filter { matches(it.title) }
            if (looseShown.isNotEmpty()) {
                item(key = "__loose_header__") {
                    Text(
                        text = "其他对话",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                items(looseShown, key = { "loose-${it.id}" }) { session ->
                    SessionRow(
                        session = session,
                        selected = if (manageMode) session.id in picked else session.id == currentId,
                        manageMode = manageMode,
                        checked = session.id in picked,
                        indented = false,
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
            }

            if (shownProjects.isEmpty() && looseShown.isEmpty() && q.isEmpty()) {
                item(key = "__empty__") {
                    Text(
                        text = "还没有项目。点右上角「新建项目」选一个工作区开始。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
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
            .then(
                if (onClick != null) {
                    Modifier.noRippleClickable(onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlyphIcon(glyph = glyph, size = 17.dp, color = color)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            ),
            color = color,
        )
    }
}

// ---------------------------------------------------------------- 项目行

/** 项目名兜底：内核没给 name 时用目录末段，至少比空白可读。 */
private fun projectLabel(project: ProjectItem): String =
    project.name.ifBlank { project.workspace.trimEnd('/').substringAfterLast('/') }

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ProjectRow(
    project: ProjectItem,
    sessionCount: Int,
    current: Boolean,
    expanded: Boolean,
    switching: Boolean,
    onToggle: () -> Unit,
    onSelect: () -> Unit,
    onNewSession: () -> Unit,
    onMenu: () -> Unit,
) {
    Row(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (current) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
            )
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onSelect,
                onLongClick = onMenu,
            )
            .heightIn(min = 44.dp)
            .padding(start = 2.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 折叠箭头：Chevron 在 24 坐标系里默认朝右，展开时转 90° 朝下
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .noRippleClickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(
                glyph = Glyph.Chevron,
                size = 12.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(if (expanded) 90f else 0f),
            )
        }
        GlyphIcon(
            glyph = Glyph.Folder,
            size = 14.dp,
            color = if (current) Clay else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = projectLabel(project),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (current) FontWeight.Medium else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // 运行中的项目转圈：多项目并行时，一眼看出哪个还在跑
        if (project.busy || (switching && current)) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.6.dp,
                color = Clay,
            )
            Spacer(Modifier.width(4.dp))
        } else if (sessionCount > 0) {
            Text(
                text = "$sessionCount",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
        }
        // ＋：在该项目下新建会话
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .noRippleClickable(onClick = onNewSession),
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(
                glyph = Glyph.Plus,
                size = 11.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                strokeWidth = 2.dp,
            )
        }
        // ⋯：重命名 / 归档 / 删除（复用会话的操作弹层样式）
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .noRippleClickable(onClick = onMenu),
            contentAlignment = Alignment.Center,
        ) {
            MoreDots(color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 三点「更多」图标：纵向三点，比横向更省宽度。 */
@Composable
private fun MoreDots(color: Color) {
    Canvas(modifier = Modifier.size(16.dp)) {
        val r = size.minDimension * 0.085f
        val gap = size.minDimension * 0.27f
        val cx = size.width / 2f
        listOf(-gap, 0f, gap).forEach { dy ->
            drawCircle(color, radius = r, center = Offset(cx, size.height / 2f + dy))
        }
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
    indented: Boolean = false,
) {
    val bg = if (selected) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.surface
    }

    Row(
        modifier = Modifier
            .padding(
                start = if (indented) 34.dp else 12.dp,
                end = 12.dp,
                top = 1.dp,
                bottom = 1.dp,
            )
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongPress,
            )
            .heightIn(min = 40.dp)
            .padding(horizontal = 12.dp, vertical = 9.dp),
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
                size = 13.dp,
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
        // 头像 = 设置入口（暂用图标圆点，等账号体系接入后换真头像）
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
