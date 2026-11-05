package com.hxy.codeforge.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hxy.codeforge.model.ToolCall
import com.hxy.codeforge.model.ToolState
import com.hxy.codeforge.ui.common.Glyph
import com.hxy.codeforge.ui.common.GlyphIcon
import com.hxy.codeforge.ui.common.ShimmerText
import com.hxy.codeforge.ui.theme.Clay

/**
 * 工具调用卡片。
 *
 * 折叠态只显示「工具名 + 参数摘要 + 状态点」，点一下展开看完整参数与输出。
 * 折叠是默认 —— 一轮对话里工具调用可能有十几条，全展开会把正文淹没。
 *
 * 触控目标：整张卡片可点，最小高度 44dp（Material 的最小可点尺寸），
 * 不用小图标当点击区，避免手指点不中。
 */
@Composable
fun ToolCard(
    tool: ToolCall,
    // 历史工具卡（load 回来 output 为空）的「回放」入口：传 tool.id 去 /api/diff 拉输出
    onFetchOutput: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(tool.id) { mutableStateOf(false) }
    // B4：diff 全屏查看器（内联只渲染前 120 行，大 diff 进全屏无截断）
    var fullscreenDiff by rememberSaveable(tool.id) { mutableStateOf(false) }

    val statusColor = when (tool.state) {
        ToolState.Running -> Clay
        ToolState.Ok -> Color(0xFF3E9B4F)
        ToolState.Failed -> MaterialTheme.colorScheme.error
    }

    // 一行式（同「思考过程」）：不画卡片框、不加边框，只留图标 + 文案 + 折叠箭头。
    // 工具是过程信息，不该比正文更抢眼；展开后才是参数与输出。
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded }
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(tool.state, statusColor)
            // 文案走 toolLabel：中文短语 + 目标（「执行 python3 --version」）。
            // 不再单独显示工具 ID —— read_file 是系统内部标识，对用户没有意义。
            // 执行中整行扫光，跑完变静态文字：一眼能看出「这条正在跑」。
            val label = toolLabel(tool.name, tool.input)
            if (tool.state == ToolState.Running) {
                ShimmerText(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .weight(1f),
                )
            } else {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .weight(1f),
                )
                GlyphIcon(
                    glyph = Glyph.Chevron,
                    size = 12.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .rotate(if (expanded) 90f else 0f),
                )
            }
            if (tool.diffAdded > 0 || tool.diffRemoved > 0) {
                DiffBadge(tool.diffAdded, tool.diffRemoved)
            }
            // 折叠态就要露出「回放」入口：历史卡默认收起，藏在展开区里等于没有
            if (onFetchOutput != null && tool.output == null) {
                Text(
                    text = "回放",
                    style = MaterialTheme.typography.labelSmall,
                    color = Clay,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Clay.copy(alpha = 0.12f))
                        .clickable { onFetchOutput(tool.id) }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(120)) + expandVertically(tween(160)),
            exit = fadeOut(tween(90)) + shrinkVertically(tween(140)),
        ) {
            Column(
                modifier = Modifier.padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                if (tool.input.isNotBlank()) {
                    MonoSection(label = "参数", body = tool.input)
                }

                tool.diff?.let { diff ->
                    DiffSection(diff, onFullscreen = { fullscreenDiff = true })
                }

                tool.output?.let { out ->
                    MonoSection(
                        label = if (tool.state == ToolState.Failed) "错误" else "输出",
                        body = out,
                        tint = if (tool.state == ToolState.Failed) MaterialTheme.colorScheme.error else null,
                    )
                }
            }
        }
    }

    if (fullscreenDiff) {
        tool.diff?.let { diff ->
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { fullscreenDiff = false },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Diff 全屏 · " + tool.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "关闭",
                            style = MaterialTheme.typography.labelLarge,
                            color = Clay,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { fullscreenDiff = false }
                                .padding(8.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        diff.lines().forEach { line ->
                            val color = when {
                                line.startsWith("+") && !line.startsWith("+++") -> Color(0xFF3E9B4F)
                                line.startsWith("-") && !line.startsWith("---") ->
                                    MaterialTheme.colorScheme.error
                                line.startsWith("@@") -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                            Text(
                                text = line.ifEmpty { " " },
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.5.sp,
                                    lineHeight = 17.sp,
                                ),
                                color = color,
                                softWrap = false,
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 探索分组

/**
 * 只读工具判定：探索阶段的「看」类工具可以折叠成一行。
 *
 * 写类工具（write_file / edit_file / delete_file / run_command）一律**不进组** ——
 * 用户对「改了哪个文件」必须一眼可见，折叠等于藏证据。
 * 带插件前缀的名字（`oss.read_file`）按最后一段匹配，与 [toolLabel] 同一套规则。
 */
fun isReadOnlyTool(name: String?): Boolean {
    val local = (name ?: return false).substringAfter('.')
    if (local.startsWith("web_search") || local.startsWith("web_fetch")) return true
    return local in READ_ONLY_TOOLS
}

private val READ_ONLY_TOOLS = setOf(
    "read_file", "read", "list_dir", "list_files", "search_files",
    "grep", "glob", "search", "fetch_url", "read_url",
)

/**
 * 「已探索 N 项」折叠块：把连续的只读工具收成一行。
 *
 * 为什么必须折叠：一轮里 read_file / list_dir / search_files 常常连着十几次，
 * 全展开会把真正的正文挤出屏幕，而它们的信息量对用户只是「它在看哪些文件」。
 *
 * [stateKey] 是展开状态的持久化键 —— 用户展开后必须保住，否则流式输出每来一帧
 * 就重排一次、刚展开的组又收回去。由调用方用「组内首条工具 id」构造。
 */
@Composable
fun ExploreGroup(
    tools: List<ToolCall>,
    onFetchOutput: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    stateKey: String = tools.firstOrNull()?.id.orEmpty(),
) {
    if (tools.isEmpty()) return
    var expanded by rememberSaveable(stateKey) { mutableStateOf(false) }
    val running = tools.count { it.state == ToolState.Running }
    val failed = tools.count { it.state == ToolState.Failed }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (running > 0) {
                ArcSpinner(color = Clay, size = 12.dp)
            } else {
                GlyphIcon(
                    glyph = Glyph.Search,
                    size = 13.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    strokeWidth = 1.6.dp,
                )
            }
            Text(
                text = buildString {
                    append(if (running > 0) "正在探索 " else "已探索 ")
                    append(tools.size)
                    append(" 项")
                    if (failed > 0 && running == 0) {
                        append(" · ")
                        append(failed)
                        append(" 项失败")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (failed > 0 && running == 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .weight(1f),
            )
            GlyphIcon(
                glyph = Glyph.Chevron,
                size = 12.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .rotate(if (expanded) 90f else 0f),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(120)) + expandVertically(tween(160)),
            exit = fadeOut(tween(90)) + shrinkVertically(tween(140)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, top = 2.dp),
            ) {
                tools.forEach { tool ->
                    val fetch = if (tool.output == null && tool.state == ToolState.Ok) onFetchOutput else null
                    ToolCard(tool = tool, onFetchOutput = fetch)
                }
            }
        }
    }
}

/** 运行中的圆点做呼吸动画，静止状态就是实心点 —— 一眼能看出「还在跑」。 */
@Composable
private fun StatusDot(state: ToolState, color: Color) {
    if (state == ToolState.Running) {
        val transition = rememberInfiniteTransition(label = "tool-dot")
        val alpha by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(700),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "tool-dot-alpha",
        )
        Box(
            modifier = Modifier
                .size(7.dp)
                .alpha(alpha)
                .background(color, CircleShape),
        )
    } else {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(color, CircleShape),
        )
    }
}

@Composable
private fun DiffBadge(added: Int, removed: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(start = 6.dp),
    ) {
        if (added > 0) {
            Text(
                text = "+$added",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = Color(0xFF3E9B4F),
            )
        }
        if (removed > 0) {
            Text(
                text = "-$removed",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun MonoSection(label: String, body: String, tint: Color? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                lineHeight = 18.sp,
            ),
            color = tint ?: MaterialTheme.colorScheme.onSurface,
            softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
    }
}

/** diff 按行着色：`+` 绿、`-` 红、`@@` 灰。横向滚动，不折行。头部带「全屏」入口。 */
@Composable
private fun DiffSection(diff: String, onFullscreen: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp),
            )
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "diff",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "全屏",
                style = MaterialTheme.typography.labelSmall,
                color = Clay,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onFullscreen)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        diff.lines().take(120).forEach { line ->
            val color = when {
                line.startsWith("+") && !line.startsWith("+++") -> Color(0xFF3E9B4F)
                line.startsWith("-") && !line.startsWith("---") -> MaterialTheme.colorScheme.error
                line.startsWith("@@") -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurface
            }
            Text(
                text = line.ifEmpty { " " },
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                    lineHeight = 17.sp,
                ),
                color = color,
                softWrap = false,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
        }
    }
}

/** 把工具参数压成一行摘要：优先取 path / command 这类最能说明意图的字段。 */
private fun summarize(input: String): String {
    val compact = input.replace(Regex("\\s+"), " ").trim()
    return if (compact.length <= 72) compact else compact.take(71) + "…"
}
