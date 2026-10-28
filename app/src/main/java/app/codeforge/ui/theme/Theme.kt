package app.codeforge.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 配色对齐 Claude 客户端的暖色系：
 * 背景是米白（不是纯白），主色是陶土橘，文字近黑偏暖。
 * 表面层次靠「背景 → 卡片 → 灰胶囊」三级暖灰拉开，不靠阴影。
 */

// 陶土橘：主操作（发送键、链接、强调点）
val Clay = Color(0xFFD97757)
val ClayDeep = Color(0xFFB8552F)

private val LightColors = lightColorScheme(
    primary = Clay,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF6E8DE),
    onPrimaryContainer = Color(0xFF8A4226),
    secondary = Color(0xFF6B685F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFECE9E0),
    onSecondaryContainer = Color(0xFF3D3B35),
    background = Color(0xFFF6F4EE),
    onBackground = Color(0xFF1F1E1C),
    surface = Color(0xFFFCFBF8),
    onSurface = Color(0xFF1F1E1C),
    surfaceVariant = Color(0xFFE9E6DC),
    onSurfaceVariant = Color(0xFF6B685F),
    outline = Color(0xFFD9D5C9),
    outlineVariant = Color(0xFFE7E4DA),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFBE9E7),
    onErrorContainer = Color(0xFF8C2F1F),
)

private val DarkColors = darkColorScheme(
    primary = Clay,
    onPrimary = Color(0xFF2A1208),
    primaryContainer = Color(0xFF4A2A1B),
    onPrimaryContainer = Color(0xFFF6D9C8),
    secondary = Color(0xFFA8A49A),
    onSecondary = Color(0xFF26251F),
    secondaryContainer = Color(0xFF3A3936),
    onSecondaryContainer = Color(0xFFE9E6DC),
    background = Color(0xFF262524),
    onBackground = Color(0xFFF5F4EF),
    surface = Color(0xFF30302E),
    onSurface = Color(0xFFF5F4EF),
    surfaceVariant = Color(0xFF3A3936),
    onSurfaceVariant = Color(0xFFA8A49A),
    outline = Color(0xFF4A4946),
    outlineVariant = Color(0xFF3B3A37),
    error = Color(0xFFE57A6E),
    onError = Color(0xFF3A0F09),
    errorContainer = Color(0xFF4A1710),
    onErrorContainer = Color(0xFFFFD9D3),
)

/**
 * 字体：正文用系统无衬线，展示性文本（问候语、助手回复、标题）用衬线。
 *
 * 不引入第三方字体 —— 中文衬线由系统 Noto Serif 承担，与 Claude 客户端
 * 的衬线观感一致；代码与工具输出用等宽。
 */
private val AppTypography = Typography().let { base ->
    base.copy(
        bodyLarge = base.bodyLarge.copy(fontSize = 15.sp, lineHeight = 23.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp),
        bodySmall = base.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
        labelSmall = base.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Medium),
    )
}

/** 等宽样式，供代码块与工具输出复用。 */
val MonoStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.5.sp,
    lineHeight = 19.sp,
)

/** 衬线展示样式：空态问候语。 */
val SerifDisplay = TextStyle(
    fontFamily = FontFamily.Serif,
    fontSize = 27.sp,
    lineHeight = 36.sp,
    fontWeight = FontWeight.Medium,
    letterSpacing = 0.2.sp,
)

/** 衬线正文：助手回复的主体文本。 */
val SerifBody = TextStyle(
    fontFamily = FontFamily.Serif,
    fontSize = 16.sp,
    lineHeight = 26.sp,
)

/** 衬线标题：抽屉大标题、Markdown 标题。 */
val SerifTitle = TextStyle(
    fontFamily = FontFamily.Serif,
    fontSize = 26.sp,
    lineHeight = 32.sp,
    fontWeight = FontWeight.Bold,
)

@Composable
fun CodeForgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // B4 深色手动切换：设置页三档（跟随系统/浅色/深色）覆盖系统取值
    val dark = when (app.codeforge.ui.settings.AppSettings.darkMode.value) {
        1 -> false
        2 -> true
        else -> darkTheme
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
