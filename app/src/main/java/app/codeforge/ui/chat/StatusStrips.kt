package app.codeforge.ui.chat

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import app.codeforge.model.ContextUsage
import app.codeforge.model.SubagentTask
import app.codeforge.model.TodoItem
import app.codeforge.model.TodoStatus
import app.codeforge.ui.common.Glyph
import app.codeforge.ui.common.GlyphIcon
import app.codeforge.ui.theme.Clay

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
        // percent 可能 >100（超预算），条形填充必须夹到 1，数字保留原值
        val fill by animateFloatAsState(
            targetValue = (u.percent / 100f).coerceIn(0f, 1f),
            animationSpec = tween(420),
            label = "ctx-fill",
        )
        val warn = u.overBudget || u.percent >= 90f
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
                text = "$usedText / ${u.percent.toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = if (warn) barColor else MaterialTheme.colorScheme.onSurfaceVariant,
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
