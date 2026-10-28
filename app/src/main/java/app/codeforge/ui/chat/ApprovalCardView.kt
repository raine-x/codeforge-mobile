package app.codeforge.ui.chat

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeforge.model.ApprovalCard
import app.codeforge.ui.theme.Clay
import app.codeforge.ui.theme.ClayDeep

/**
 * 人工审批卡片（HITL）。
 *
 * 这张卡片决定 agent 会不会卡住 —— 内核把工具挂起等这里回话，超时（默认 15 分钟）
 * 才自己放弃。所以它必须在消息流里**紧跟当前对话**，不能被折叠进菜单。
 *
 * 交互上的几条硬要求：
 *  - 两个按钮各自最小 48dp 高，间距足够，避免「想拒绝却点到允许」；
 *  - 「允许」是主操作放右侧，「拒绝」是次要操作放左侧；
 *  - 详情与 diff 默认折叠，因为卡片本身要能一眼看完；
 *  - 卡片不可整体点击（防止误触把展开态关掉而错过按钮）。
 */
@Composable
fun ApprovalCardView(
    card: ApprovalCard,
    onDecide: (allow: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDetail by rememberSaveable(card.id) { mutableStateOf(false) }

    val accent = ClayDeep
    val surface = MaterialTheme.colorScheme.surface

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(surface, RoundedCornerShape(16.dp))
            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "需要你确认",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = accent,
            )
            if (card.subagentId != null) {
                Text(
                    text = "· 子任务 ${card.subagentId}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }

        Text(
            text = card.action.ifBlank { card.tool },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        if (card.reason.isNotBlank()) {
            Text(
                text = card.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (card.detail.isNotBlank() || card.diff != null) {
            Text(
                text = if (showDetail) "收起详情" else "查看详情",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { showDetail = !showDetail }
                    .heightIn(min = 32.dp)
                    .padding(vertical = 6.dp),
            )
        }

        AnimatedVisibility(
            visible = showDetail,
            enter = fadeIn(tween(120)) + expandVertically(tween(160)),
            exit = fadeOut(tween(90)) + shrinkVertically(tween(140)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (card.detail.isNotBlank()) {
                    Text(
                        text = card.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                card.diff?.let { diff ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(8.dp),
                            )
                            .padding(8.dp),
                    ) {
                        diff.lines().take(80).forEach { line ->
                            val color = when {
                                line.startsWith("+") && !line.startsWith("+++") -> Color(0xFF3E9B4F)
                                line.startsWith("-") && !line.startsWith("---") -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = { onDecide(false) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Text(text = "拒绝", style = MaterialTheme.typography.bodyMedium)
            }
            Button(
                onClick = { onDecide(true) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Clay),
            ) {
                Text(text = "允许", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
