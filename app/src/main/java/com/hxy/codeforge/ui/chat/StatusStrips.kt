package com.hxy.codeforge.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hxy.codeforge.model.ContextUsage
import com.hxy.codeforge.model.SubagentTask
import com.hxy.codeforge.model.TodoItem
import com.hxy.codeforge.model.TodoStatus
import com.hxy.codeforge.ui.common.Glyph
import com.hxy.codeforge.ui.common.GlyphIcon
import com.hxy.codeforge.ui.theme.Clay

/**
 * 输入卡上方的三条状态区（对应内核 context / todo / subagent 帧）：
 *
 *  - [ContextMeter]：上下文占用细条，超 90% 变警示色；
 *  - [TodoPanel]：任务清单，行状态图标带动画切换；
 *  - [SubagentBar]：子智能体任务卡，运行中转圈、完成打勾。
 *
 * 三者都是「有数据才出现」，进出用 expand+fade，不占常驻空间。
 */

// ---------------------------------------------------------------- 上下文表

@Composable
fun ContextMeter(usage: ContextUsage?, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = usage != null,
        enter = fadeIn(tween(200)) + expandVertically(tween(220)),
        exit = fadeOut(tween(140)) + shrinkVertically(tween(180)),
        modifier = modifier,
    ) {
        val u = usage ?: return@AnimatedVisibility
        // 有模型真实窗口（ctx_in）时按窗口算占比；否则退回内核预算。
        // percent 可能 >100（超预算），条形填充必须夹到 1，数字保留原值。
        val fill by animateFloatAsState(
            targetValue = u.displayFraction,
            animationSpec = tween(420),
            label = "ctx-fill",
        )
        val warn = u.overBudget || u.displayFraction >= 0.9f
        val barColor = when {
            u.overBudget -> MaterialTheme.colorScheme.error
            warn -> MaterialTheme.colorScheme.secondary
            else -> Clay
        }
        val usedText = if (u.used >= 1000) "${u.used / 1000}k" else "${u.used}"

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "上下文",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = fill)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(barColor),
                )
            }
            Text(
                text = "$usedText / ${(u.displayFraction * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = if (warn) barColor else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------- 目标模式

/**
 * 目标账本条（goal 帧）。
 *
 * 显示目标原文、已用/上限轮次与最近判定。PASS 后内核会关闭账本（goal 为空），
 * 这里仍保留 last verdict 供用户回看，不把「已验证通过」的痕迹抹掉。
 */
@Composable
fun GoalStrip(goal: com.hxy.codeforge.model.GoalStatus?, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = goal != null && (goal.open || goal.round > 0),
        enter = fadeIn(tween(200)) + expandVertically(tween(220)),
        exit = fadeOut(tween(140)) + shrinkVertically(tween(180)),
        modifier = modifier,
    ) {
        val g = goal ?: return@AnimatedVisibility
        val verdict = g.verdict.uppercase()
        val color = when (verdict) {
            "PASS" -> Clay
            "FAIL" -> MaterialTheme.colorScheme.error
            "BLOCKED" -> MaterialTheme.colorScheme.secondary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        val label = when (verdict) {
            "PASS" -> "已验证通过"
            "FAIL" -> "未达成"
            "BLOCKED" -> "无法观察"
            "SKIP" -> "无可观察面"
            else -> if (g.open) "验证中" else ""
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "目标",
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = g.goal.ifBlank { "目标已关闭" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (g.findings.isNotBlank() && verdict != "PASS") {
                    Text(
                        text = g.findings.take(80),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = buildString {
                    append(label)
                    if (g.round > 0) {
                        append(" · ")
                        append(g.round)
                        append('/')
                        append(if (g.maxRounds > 0) g.maxRounds else "?")
                    }
                    if (g.exhausted) append(" · 预算用尽")
                },
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
    }
}

// ---------------------------------------------------------------- 任务清单

@Composable
fun TodoPanel(todos: List<TodoItem>, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = todos.isNotEmpty(),
        enter = fadeIn(tween(220)) + expandVertically(tween(260),
            expandFrom = Alignment.Top),
        exit = fadeOut(tween(140)) + shrinkVertically(tween(200)),
        modifier = modifier,
    ) {
        val done = todos.count { it.status == TodoStatus.Completed }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(
                    glyph = Glyph.Check,
                    size = 13.dp,
                    color = Clay,
                    strokeWidth = 2.2.dp,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text(
                    text = "任务",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                // 计数变化只淡入淡出数字，不挪位置
                AnimatedContent(
                    targetState = done,
                    transitionSpec = {
                        (fadeIn(tween(160)) + scaleIn(tween(160), initialScale = 0.8f))
                            .togetherWith(fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.8f))
                    },
                    label = "todo-count",
                ) { d ->
                    Text(
                        text = "$d/${todos.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            todos.sortedBy { it.sort }.forEach { todo -> TodoRow(todo) }
        }
    }
}

/**
 * 任务清单弹窗：顶栏「三点」菜单的入口（B 接线，`showTaskList` 为 true 时挂载）。
 *
 * 为什么从输入卡上方搬到这里：清单常驻会在输入框上方压一块与正文无关的区域，
 * 用户要的是「想看时看得到、不看时不占地方」。弹窗读的是同一份 [todos]，
 * 内核推 todo 帧时计数与勾选**实时**变化，不需要额外的刷新入口。
 */
@Composable
fun TaskListDialog(
    todos: List<TodoItem>,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        val done = todos.count { it.status == TodoStatus.Completed }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(
                    glyph = Glyph.Check,
                    size = 14.dp,
                    color = Clay,
                    strokeWidth = 2.2.dp,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    text = "任务清单",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "$done/${todos.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 2.dp),
                )
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onDismiss)
                        .padding(6.dp),
                ) {
                    GlyphIcon(
                        glyph = Glyph.Close,
                        size = 15.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        strokeWidth = 1.8.dp,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (todos.isEmpty()) {
                Text(
                    text = "当前没有任务",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    todos.sortedBy { it.sort }.forEach { TodoRow(it) }
                }
            }
        }
    }
}

@Composable
private fun TodoRow(todo: TodoItem) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 状态图标：进行中是旋转的弧（有生命感），完成打勾、取消划叉
        AnimatedContent(
            targetState = todo.status,
            transitionSpec = {
                (fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.6f))
                    .togetherWith(fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.6f))
            },
            label = "todo-status",
        ) { status ->
            when (status) {
                TodoStatus.Pending -> HollowDot(color = MaterialTheme.colorScheme.onSurfaceVariant)
                TodoStatus.InProgress -> ArcSpinner(color = Clay, size = 14.dp)
                TodoStatus.Completed -> GlyphIcon(
                    glyph = Glyph.Check,
                    size = 14.dp,
                    color = Color(0xFF3E9B4F),
                    strokeWidth = 2.4.dp,
                )

                TodoStatus.Cancelled -> GlyphIcon(
                    glyph = Glyph.Close,
                    size = 12.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    strokeWidth = 2.dp,
                )
            }
        }
        Text(
            text = todo.content,
            style = MaterialTheme.typography.bodySmall,
            color = when (todo.status) {
                TodoStatus.Completed -> MaterialTheme.colorScheme.onSurfaceVariant
                TodoStatus.Cancelled -> MaterialTheme.colorScheme.onSurfaceVariant
                TodoStatus.InProgress -> MaterialTheme.colorScheme.onSurface
                TodoStatus.Pending -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            textDecoration = if (todo.status == TodoStatus.Cancelled) {
                TextDecoration.LineThrough
            } else {
                null
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------- 子智能体

@Composable
fun SubagentBar(tasks: List<SubagentTask>, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = tasks.isNotEmpty(),
        enter = fadeIn(tween(220)) + expandVertically(tween(260)),
        exit = fadeOut(tween(140)) + shrinkVertically(tween(200)),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            tasks.forEach { task -> SubagentCard(task) }
        }
    }
}

@Composable
private fun SubagentCard(task: SubagentTask) {
    val running = task.status == "started" || task.status == "running"
    val failed = task.status == "failed"
    val accent = when {
        failed -> MaterialTheme.colorScheme.error
        running -> Clay
        else -> Color(0xFF3E9B4F)
    }
    val modeLabel = when (task.mode) {
        "explore" -> "探查"
        "implement" -> "实现"
        "verify" -> "验证"
        else -> task.mode
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 状态切换：转圈 → 勾/叉，同位渐变
        AnimatedContent(
            targetState = task.status,
            transitionSpec = {
                (fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.6f))
                    .togetherWith(fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.6f))
            },
            label = "subagent-status",
        ) { status ->
            when {
                status == "started" || status == "running" ->
                    ArcSpinner(color = accent, size = 15.dp)

                status == "failed" -> GlyphIcon(
                    glyph = Glyph.Close,
                    size = 13.dp,
                    color = accent,
                    strokeWidth = 2.2.dp,
                )

                else -> GlyphIcon(
                    glyph = Glyph.Check,
                    size = 15.dp,
                    color = accent,
                    strokeWidth = 2.4.dp,
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "子智能体 · $modeLabel",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (task.summary.isNotBlank() || task.detail.isNotBlank()) {
                Text(
                    text = task.summary.ifBlank { task.detail },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alpha(if (running) 0.9f else 0.75f),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 小图形

/** 待办空圈。 */
@Composable
private fun HollowDot(color: Color, size: androidx.compose.ui.unit.Dp = 13.dp) {
    Canvas(modifier = Modifier.size(size)) {
        drawCircle(
            color = color,
            radius = size.toPx() / 2f - 1f,
            center = Offset(size.toPx() / 2f, size.toPx() / 2f),
            style = Stroke(width = 1.8.dp.toPx()),
        )
    }
}

/** 旋转的弧：所有「正在跑」的语义共用这一个动画。 */
@Composable
fun ArcSpinner(color: Color, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "arc-spinner")
    // 动画值收集在 Composable 层，绘制块里直接读值
    val sweep by transition.animateFloat(
        initialValue = 40f,
        targetValue = 300f,
        animationSpec = infiniteRepeatable(tween(860), RepeatMode.Reverse),
        label = "arc-sweep",
    )
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1_100, easing = LinearEasing)),
        label = "arc-rotation",
    )
    Canvas(modifier = modifier.size(size)) {
        val r = this.size.minDimension / 2f - 2f
        drawArc(
            color = color,
            startAngle = rotation,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = Offset(this.size.width / 2f - r, this.size.height / 2f - r),
            size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}
