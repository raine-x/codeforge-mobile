package app.codeforge.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow

/**
 * 扫光文字（shimmer）。
 *
 * 一段高亮从左往右扫过文字，用来表达「这一行正在干活」——
 * 比转圈更能说明「在做什么」，也不会像 spinner 那样把注意力从文案上拉走。
 *
 * 用在工具卡执行中的标题上：`执行 python3 --version` 在跑的时候整行有光流过，
 * 跑完立刻恢复成静态文字。
 */
@Composable
fun ShimmerText(
    text: String,
    style: TextStyle,
    baseColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer-progress",
    )

    // 高亮带比文字宽一些，扫过时整行都会被照亮
    val span = 260f
    val x = progress * (span * 3) - span
    val brush = Brush.linearGradient(
        colors = listOf(
            baseColor.copy(alpha = 0.55f),
            baseColor,
            baseColor.copy(alpha = 0.55f),
        ),
        start = Offset(x, 0f),
        end = Offset(x + span, 0f),
    )

    Row(modifier = modifier) {
        Text(
            text = text,
            style = style.copy(brush = brush),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
