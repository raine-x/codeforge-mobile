package app.codeforge.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed

/**
 * 无涟漪点击。
 *
 * 圆形/胶囊按钮上默认涟漪是**方形**边界（clickable 的 indication 只看布局
 * 矩形，不看 background 的形状），视觉上就是「方形的按压阴影」——
 * 本应用的按钮全靠形状本身表达可点，按压反馈交给动画，涟漪一律去掉。
 * 通栏列表行保留默认涟漪（矩形行配矩形涟漪不违和）。
 */
fun Modifier.noRippleClickable(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    clickable(
        interactionSource = interaction,
        indication = null,
        enabled = enabled,
        onClick = onClick,
    )
}
