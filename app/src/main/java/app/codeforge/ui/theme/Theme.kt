package app.codeforge.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

// ---------------------------------------------------------------- 自定义背景

/**
 * 当前生效的「背景上的文字色」（null = 用配色方案自己的取值）。
 *
 * 由 [AppBackground] 提供：需要直接压在背景图上画字的组件可以读它，
 * 不必各自再算一遍亮度。
 */
val LocalAppTextColor = staticCompositionLocalOf<Color?> { null }

/**
 * 外观层入口：背景图 + 模糊 + 亮度 + 文字色。
 *
 * 由 B 在 AppRoot 里包住整棵界面（`AppBackground { ... }`），本文件不碰 AppRoot。
 *
 * 规则（对应用户要求）：
 *  - 背景图：从 [AppSettings.backgroundImagePath] 读本地文件，等比裁满；
 *  - 模糊：`Modifier.blur`，RenderEffect 需要 API 31+，低版本系统级忽略（不崩、不报错）；
 *  - 亮度：低于 100 叠黑、高于 100 叠白 —— 用覆盖层而不是调色板，
 *    因为图片是位图、没法像矢量那样改色；
 *  - 文字色：手动设置的优先；没设就按**背景有效亮度**自动判定，
 *    亮度低（< 0.46）切白字，其余保持主题默认（浅色主题的本就是近黑）。
 *    判定用「原图平均亮度 × 亮度百分比」，不是屏幕像素采样 —— 屏幕采样拿不到
 *    模糊后的值，且每帧重算代价太大。
 *
 * 设了背景图时，surface 角色会带一点透明度，让背景透出来；同时若背景偏暗，
 * 整套配色切到深色方案 —— 否则浅色卡面 + 白字会变成白底白字。
 */
@Composable
fun AppBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val settings = app.codeforge.ui.settings.AppSettings
    val path = settings.backgroundImagePath.value
    val blurDp = settings.backgroundBlur.intValue
    val brightness = settings.backgroundBrightness.intValue.coerceIn(0, 200)
    val overrideSpec = settings.textColorOverride.value

    val bitmap = remember(path) {
        if (path.isBlank()) null else BackgroundImageCache.load(path)
    }
    val luminance = remember(bitmap) {
        bitmap?.let { BackgroundImageCache.luminance(it) }
    }
    // 亮度百分比作用在平均亮度上：100 = 原样
    val effective = luminance?.let { (it * brightness / 100f).coerceIn(0f, 1f) }
    val darkBackground = effective != null && effective < 0.46f

    val manual = remember(overrideSpec) { parseColorSpec(overrideSpec) }
    val textColor = manual ?: if (darkBackground) Color.White else null

    Box(
        modifier = modifier
            .fillMaxSize()
            // 有图时垫黑底：图片解码失败/尺寸不匹配时不会露出奇怪的白边
            .background(if (bitmap == null) MaterialTheme.colorScheme.background else Color.Black),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (blurDp > 0) Modifier.blur(blurDp.dp) else Modifier),
            )
            if (brightness != 100) {
                val scrim = if (brightness < 100) {
                    Color.Black.copy(alpha = (100 - brightness) / 100f * 0.8f)
                } else {
                    Color.White.copy(alpha = (brightness - 100) / 100f * 0.6f)
                }
                Box(modifier = Modifier.fillMaxSize().background(scrim))
            }
        }

        CompositionLocalProvider(LocalAppTextColor provides textColor) {
            val scheme = backgroundScheme(hasImage = bitmap != null, dark = darkBackground, textColor = textColor)
            if (scheme != null) {
                MaterialTheme(colorScheme = scheme) { content() }
            } else {
                content()
            }
        }
    }
}

/**
 * 背景图下的配色方案：透明底 + 半透明卡面 + 由文字色决定的正文色。
 * 返回 null 表示「不需要改配色」（没图、也没手动指定文字色）。
 */
@Composable
private fun backgroundScheme(hasImage: Boolean, dark: Boolean, textColor: Color?): ColorScheme? {
    if (!hasImage && textColor == null) return null
    if (hasImage && dark) {
        return DarkColors.copy(
            background = Color.Transparent,
            surface = DarkColors.surface.copy(alpha = 0.92f),
            surfaceVariant = DarkColors.surfaceVariant.copy(alpha = 0.86f),
            onBackground = textColor ?: DarkColors.onBackground,
            onSurface = textColor ?: DarkColors.onSurface,
            onSurfaceVariant = textColor?.copy(alpha = 0.72f) ?: DarkColors.onSurfaceVariant,
        )
    }
    if (hasImage) {
        return LightColors.copy(
            background = Color.Transparent,
            surface = LightColors.surface.copy(alpha = 0.92f),
            surfaceVariant = LightColors.surfaceVariant.copy(alpha = 0.88f),
            onBackground = textColor ?: LightColors.onBackground,
            onSurface = textColor ?: LightColors.onSurface,
            onSurfaceVariant = textColor?.copy(alpha = 0.72f) ?: LightColors.onSurfaceVariant,
        )
    }
    // 只有手动文字色：只改文字，卡面保持原配色
    val base = MaterialTheme.colorScheme
    return base.copy(
        onBackground = textColor ?: base.onBackground,
        onSurface = textColor ?: base.onSurface,
    )
}

/** 解析 `#RRGGBB` / `#AARRGGBB` / `RRGGBB`；空串或非法值返回 null。 */
private fun parseColorSpec(spec: String): Color? {
    val t = spec.trim().removePrefix("#")
    return when (t.length) {
        6 -> t.toLongOrNull(16)?.let { Color(0xFF000000L or it) }
        8 -> t.toLongOrNull(16)?.let { Color(it) }
        else -> null
    }
}

/**
 * 背景图缓存：只留一张（设置里同时只有一个背景图）。
 *
 * 解码在主线程做，但带 `inSampleSize` 降到 1440px 以内，一张普通手机照片
 * 几毫秒到几十毫秒，且只在 path 变化时发生一次 —— 不值得为它引 Coil。
 */
private object BackgroundImageCache {
    private var cachedPath: String? = null
    private var cached: android.graphics.Bitmap? = null

    fun load(path: String): android.graphics.Bitmap? {
        if (path == cachedPath) return cached
        val decoded = runCatching {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 1440 || bounds.outHeight / sample > 1440) sample *= 2
            android.graphics.BitmapFactory.decodeFile(
                path,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }.getOrNull()
        cachedPath = path
        cached = decoded
        return decoded
    }

    /** 平均亮度 0..1：缩到 16×16 再算，不逐像素扫整张图。 */
    fun luminance(bitmap: android.graphics.Bitmap): Float {
        val small = android.graphics.Bitmap.createScaledBitmap(bitmap, 16, 16, true)
        val pixels = IntArray(16 * 16)
        small.getPixels(pixels, 0, 16, 0, 0, 16, 16)
        var sum = 0f
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            sum += (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
        }
        if (small !== bitmap) small.recycle()
        return sum / pixels.size
    }
}
