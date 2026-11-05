package com.hxy.codeforge.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hxy.codeforge.model.ActionPanelState
import com.hxy.codeforge.ui.common.Glyph
import com.hxy.codeforge.ui.common.GlyphIcon
import com.hxy.codeforge.ui.common.noRippleClickable
import com.hxy.codeforge.ui.theme.Clay

/**
 * 输入卡上方的「多页操作」选择框（@plan 计划书触发后由内核侧给出）。
 *
 * 结构对齐需求：标题行（左侧选中圈 + 标题 + 关闭）、计划书正文（默认选中，
 * 可点左侧圆圈取消）、下方输入框（补充要求，可留空）、右侧一个发送键。
 *
 * 面板本身不带业务状态：选中的正文与补充文字拼成一条 payload 交给
 * [onSend]，由 ViewModel 决定是「直接执行计划」还是并入下一轮。
 * 选中的正文默认就是 [ActionPanelState.content] —— 用户不动它，按发送
 * 就等于「直接发送计划书」。
 */
@Composable
fun ActionPanel(
    state: ActionPanelState,
    onSend: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    running: Boolean = false,
) {
    // 正文换了（下一份计划书）就回到默认选中，别把上一份的取消状态带过来
    var selected by remember(state.content) { mutableStateOf(true) }
    var extra by remember(state.content) { mutableStateOf("") }

    fun payload(): String = buildString {
        if (selected) append(state.content.trim())
        if (extra.isNotBlank()) {
            if (isNotEmpty()) append("\n\n")
            append(extra.trim())
        }
    }

    val canSend = !running && payload().isNotBlank()

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SelectionMark(selected = selected, onClick = { selected = !selected })
                Spacer(Modifier.width(10.dp))
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .noRippleClickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    GlyphIcon(
                        glyph = Glyph.Close,
                        size = 13.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.size(8.dp))
            // 计划书正文：视口封顶 180dp 自己滚动 —— 面板要浮在输入卡上方，
            // 不能让长计划书把整个底部区域顶掉
            Text(
                text = state.content,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 19.sp),
                color = if (selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .verticalScroll(rememberScrollState()),
            )

            Spacer(Modifier.size(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = extra,
                    onValueChange = { extra = it },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            RoundedCornerShape(14.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(Clay),
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    decorationBox = { inner ->
                        Box {
                            if (extra.isEmpty()) {
                                Text(
                                    text = "补充要求（可留空）",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            inner()
                        }
                    },
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            if (canSend) Clay else MaterialTheme.colorScheme.surfaceVariant,
                            CircleShape,
                        )
                        .noRippleClickable(enabled = canSend) { onSend(payload()) },
                    contentAlignment = Alignment.Center,
                ) {
                    // ArrowDown 旋转 180° 即上箭头发送键（与输入卡主按钮同款方向）
                    GlyphIcon(
                        glyph = Glyph.ArrowDown,
                        size = 15.dp,
                        color = if (canSend) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.rotate(180f),
                    )
                }
            }
        }
    }
}

/** 左侧选中圈：选中 = 陶土实心 + 白勾，未选 = 空圈。 */
@Composable
private fun SelectionMark(selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(if (selected) Clay else Color.Transparent, CircleShape)
            .border(1.5.dp, if (selected) Clay else MaterialTheme.colorScheme.outline, CircleShape)
            .noRippleClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            GlyphIcon(glyph = Glyph.Check, size = 11.dp, color = Color.White)
        }
    }
}
