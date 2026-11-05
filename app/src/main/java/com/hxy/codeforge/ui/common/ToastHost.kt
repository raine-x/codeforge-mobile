package com.hxy.codeforge.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hxy.codeforge.ui.Banner
import kotlinx.coroutines.delay

/**
 * 底部气泡提示。
 *
 * ## 为什么自绘而不用系统 Toast
 *
 * 系统 `Toast` 在 Android 16 上贴屏幕最底边，会被手势条切掉一半；
 * `setGravity(BOTTOM, 0, offset)` 抬高的请求也被忽略（真机两种都试过，
 * 截图里那条白边就是被切掉一半的 toast）。
 * 自绘能精确落在输入卡上方、样式与主题一致，也不会拦截触摸。
 *
 * 层级上它是根 Box 的最后一个子节点，能盖住普通内容；ModalBottomSheet 属于
 * Dialog 层级盖不住 —— 但涉及弹层的操作（选模型、选权限）都已经在选中后
 * 自动收起弹层，不会撞上。
 *
 * 停留时长：状态类 2 秒，错误类 3.2 秒。
 */
@Composable
fun ToastHost(
    banner: Banner?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shown = banner?.takeIf { !it.retryable }

    LaunchedEffect(shown) {
        if (shown != null) {
            delay(if (shown.isError) 3200L else 2000L)
            onDismiss()
        }
    }

    AnimatedVisibility(
        visible = shown != null,
        enter = fadeIn(tween(150)) + scaleIn(tween(180), initialScale = 0.92f),
        exit = fadeOut(tween(130)) + scaleOut(tween(150), targetScale = 0.95f),
        modifier = modifier,
    ) {
        val b = shown ?: return@AnimatedVisibility
        Row(
            modifier = Modifier
                .widthIn(max = 380.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(
                    if (b.isError) {
                        MaterialTheme.colorScheme.error.copy(alpha = 0.94f)
                    } else {
                        // 深色半透明，与系统 toast 观感一致
                        Color(0xF01F1F1F)
                    },
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = b.text,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
