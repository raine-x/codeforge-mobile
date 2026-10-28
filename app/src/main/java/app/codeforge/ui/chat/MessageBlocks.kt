package app.codeforge.ui.chat

import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.webkit.WebView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import app.codeforge.ui.common.Glyph
import app.codeforge.ui.common.GlyphIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * 消息正文渲染入口。
 *
 * 解析与块切分已全部交给 `libopenrender.so`（见 [OpenRenderContent]）；
 * 本文件只保留**宿主侧的媒体渲染器**：引擎按设计只输出 URL 与语义，
 * 图片/视频/音频/SVG 的加载与播放归宿主。
 *
 * 围栏约定由引擎当 code_block 输出，渲染层按 lang 路由：
 * ```svg / ```image / ```video / ```audio；普通 markdown 与公式不再经过 WebView。
 */

// ---------------------------------------------------------------- 正文入口

/**
 * 消息正文块序列渲染。[caret] 为 true 表示仍处于流式输出：由引擎增量解析，
 * 光标只落在最后一个可载文本的块上。
 */
@Composable
fun MessageContentBlocks(text: String, modifier: Modifier = Modifier, caret: Boolean = false) {
    OpenRenderContent(
        source = text,
        streaming = caret,
        modifier = modifier,
    )
}

/** 流式尾巴的等宽占位卡：WebView/播放器块在流结束后才挂载，杜绝重建闪烁。 */
@Composable
internal fun StreamingTailBlock(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            lineHeight = 18.sp,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

// ---------------------------------------------------------------- 公共件

private val blockCardShape = RoundedCornerShape(12.dp)

/** 播放器/读取器用的真实数据源：http(s) 原样，其余解析到工作区真实文件。 */
internal fun mediaDataSource(ctx: android.content.Context, url: String): String =
    if (url.startsWith("http://") || url.startsWith("https://")) {
        url
    } else {
        attachmentFile(ctx, url).absolutePath
    }

/** 图片位图缓存：聊天列表来回滚动、内联图与块级图共用。 */
internal val bitmapCache = object : android.util.LruCache<String, android.graphics.Bitmap>(16) {
    override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = 1
}

internal val mediaHttp = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

/** 统一的媒体位图加载：http(s) 走 OkHttp，其余按工作区相对路径读文件。 */
internal suspend fun loadMediaBitmap(
    ctx: android.content.Context,
    url: String,
): android.graphics.Bitmap? {
    bitmapCache.get(url)?.let { return it }
    return withContext(Dispatchers.IO) {
        runCatching {
            val bytes: ByteArray? = if (url.startsWith("http")) {
                mediaHttp.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) null else r.body?.bytes()
                }
            } else {
                attachmentFile(ctx, url).takeIf { it.exists() }?.readBytes()
            }
            bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        }.getOrNull()?.also { bitmapCache.put(url, it) }
    }
}

private sealed interface ImgState {
    data object Loading : ImgState
    data class Ok(val bitmap: android.graphics.Bitmap) : ImgState
    data object Err : ImgState
}

// ---------------------------------------------------------------- SVG

/**
 * SVG 块：WebView 渲染（禁 JS，纯矢量绘制，透明背景）。
 * 容器高度由 viewBox/width/height 推算的宽高比决定 —— 不用 JS 量高。
 */
@Composable
fun MessageSvgBlock(svg: String, modifier: Modifier = Modifier) {
    val aspect = remember(svg) { svgAspect(svg) ?: 1.5f }
    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspect.coerceIn(0.4f, 4f))
            .clip(blockCardShape)
            .background(MaterialTheme.colorScheme.surface),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        update = { wv ->
            val html = "<html><head><meta charset=utf-8>" +
                "<style>html,body{margin:0;padding:0;background:transparent}" +
                "svg{width:100%;height:100%;display:block}</style></head><body>" +
                svg + "</body></html>"
            if (wv.tag != html) {
                wv.tag = html
                wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
    )
}

/** 从 viewBox / width+height 推宽高比；都没有返回 null（调用方取默认）。 */
private fun svgAspect(svg: String): Float? {
    // 引号用 \x22 表示：raw string 里直接写引号会与三引号结束符撞车
    val vb = Regex("""viewBox\s*=\s*\x22([^\x22]+)\x22""").find(svg)?.groupValues?.get(1)?.trim()?.split(Regex("""[,\s]+"""))
    if (vb != null && vb.size == 4) {
        val w = vb[2].toFloatOrNull()
        val h = vb[3].toFloatOrNull()
        if (w != null && h != null && w > 0 && h > 0) return w / h
    }
    val w = Regex("""width\s*=\s*"([\d.]+)""").find(svg)?.groupValues?.get(1)?.toFloatOrNull()
    val h = Regex("""height\s*=\s*"([\d.]+)""").find(svg)?.groupValues?.get(1)?.toFloatOrNull()
    return if (w != null && h != null && w > 0 && h > 0) w / h else null
}

// ---------------------------------------------------------------- 图片

@Composable
fun MessageImageBlock(url: String, alt: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var state by remember(url) { mutableStateOf<ImgState>(ImgState.Loading) }
    var fullscreen by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url, ctx) {
        bitmapCache.get(url)?.let { state = ImgState.Ok(it); return@LaunchedEffect }
        state = ImgState.Loading
        val bmp = loadMediaBitmap(ctx, url)
        if (bmp != null) {
            state = ImgState.Ok(bmp)
        } else {
            state = ImgState.Err
        }
    }

    when (val s = state) {
        is ImgState.Ok -> Image(
            bitmap = s.bitmap.asImageBitmap(),
            contentDescription = alt.ifBlank { "消息图片" },
            contentScale = ContentScale.FillWidth,
            modifier = modifier
                .fillMaxWidth()
                .clip(blockCardShape)
                .clickable { fullscreen = true },
        )

        is ImgState.Err -> MediaErrorRow("图片加载失败：$url")
        ImgState.Loading -> Box(
            modifier = modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(blockCardShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator(strokeWidth = 2.5.dp) }
    }

    if (fullscreen && state is ImgState.Ok) {
        FullscreenBitmapViewer((state as ImgState.Ok).bitmap) { fullscreen = false }
    }
}

// ---------------------------------------------------------------- 视频

@Composable
fun MessageVideoBlock(url: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val view = remember { android.widget.VideoView(ctx) }
    DisposableEffect(url) { onDispose { view.stopPlayback() } }

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .height(240.dp)
            .clip(blockCardShape)
            .background(Color.Black),
        factory = { view },
        update = { vv ->
            val source = mediaDataSource(ctx, url)
            if (vv.tag != source) {
                vv.tag = source
                vv.setMediaController(android.widget.MediaController(ctx))
                val videoUri = if (source.startsWith("http")) {
                    android.net.Uri.parse(source)
                } else {
                    android.net.Uri.fromFile(java.io.File(source))
                }
                vv.setVideoURI(videoUri)
                vv.start()
            }
        },
    )
}

// ---------------------------------------------------------------- 音频

@Composable
fun MessageAudioBlock(url: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var prepared by remember(url) { mutableStateOf(false) }
    var playing by remember(url) { mutableStateOf(false) }
    var finished by remember(url) { mutableStateOf(false) }
    var position by remember(url) { mutableIntStateOf(0) }
    var duration by remember(url) { mutableIntStateOf(0) }
    val player = remember(url) { MediaPlayer() }

    DisposableEffect(url) { onDispose { player.release() } }

    LaunchedEffect(playing, prepared) {
        while (playing && prepared) {
            runCatching {
                position = player.currentPosition
                duration = player.duration
                if (duration > 0 && position >= duration - 60) {
                    playing = false
                    finished = true
                    position = duration
                }
            }
            delay(200)
        }
    }

    fun toggle() {
        when {
            !prepared -> {
                runCatching {
                    player.setDataSource(mediaDataSource(ctx, url))
                    player.setOnPreparedListener {
                        prepared = true
                        duration = it.duration
                        it.start()
                    }
                    player.setOnCompletionListener {
                        playing = false
                        finished = true
                        position = duration
                    }
                    player.prepareAsync()
                    playing = true
                }.onFailure { prepared = false }
            }
            playing -> { player.pause(); playing = false }
            else -> {
                if (finished) { player.seekTo(0); finished = false }
                player.start()
                playing = true
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(blockCardShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
                .pointerInput(Unit) { detectTapGestures { toggle() } },
            contentAlignment = Alignment.Center,
        ) {
            AudioPlayGlyph(playing = playing, color = MaterialTheme.colorScheme.onPrimary)
        }
        Column(modifier = Modifier.weight(1f)) {
            Slider(
                value = if (duration > 0) position.toFloat() / duration else 0f,
                onValueChange = {
                    if (prepared) {
                        player.seekTo((it * duration).roundToInt())
                        position = (it * duration).roundToInt()
                        if (finished && it < 1f) finished = false
                    }
                },
                enabled = prepared,
            )
            Text(
                text = "${formatMs(position)} / ${formatMs(duration)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 播放/暂停三角块：Glyph 库里没有播放符号，直接按主按钮同款画法手绘。 */
@Composable
private fun AudioPlayGlyph(playing: Boolean, color: Color) {
    Canvas(modifier = Modifier.size(16.dp)) {
        if (playing) {
            val barW = size.width * 0.22f
            listOf(0.15f, 0.63f).forEach { x ->
                drawRoundRect(
                    color = color,
                    topLeft = Offset(size.width * x, size.height * 0.15f),
                    size = androidx.compose.ui.geometry.Size(barW, size.height * 0.7f),
                    cornerRadius = CornerRadius(barW / 3f),
                )
            }
        } else {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(size.width * 0.22f, size.height * 0.12f)
                lineTo(size.width * 0.88f, size.height * 0.5f)
                lineTo(size.width * 0.22f, size.height * 0.88f)
                close()
            }
            drawPath(path, color)
        }
    }
}

private fun formatMs(ms: Int): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

// ---------------------------------------------------------------- 失败态

@Composable
private fun MediaErrorRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier
            .fillMaxWidth()
            .clip(blockCardShape)
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

// ---------------------------------------------------------------- 附件消息（用户侧）

/** 图片类附件（按扩展名判断，决定渲染缩略图还是文件片）。 */
fun String.isImagePath(): Boolean {
    val l = lowercase()
    return l.endsWith(".png") || l.endsWith(".jpg") || l.endsWith(".jpeg") ||
        l.endsWith(".webp") || l.endsWith(".gif")
}

/** 附件相对路径 → 工作区真实文件（CoreService 以 filesDir/workspace 为 -workdir）。 */
internal fun attachmentFile(ctx: android.content.Context, rel: String): java.io.File {
    val clean = rel.removePrefix("file://")
    return if (java.io.File(clean).isAbsolute) java.io.File(clean) else java.io.File(
        java.io.File(ctx.filesDir, "workspace"),
        clean,
    )
}

/** 缩略图解码：按目标尺寸取样，避免整图进内存。 */
internal fun decodeThumb(file: java.io.File, target: Int): android.graphics.Bitmap? {
    if (!file.exists()) return null
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= target || bounds.outHeight / (sample * 2) >= target) sample *= 2
    return BitmapFactory.decodeFile(file.absolutePath, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
}

/** 用户消息里的图片附件缩略图（120dp 方块，点击进全屏）。 */
@Composable
internal fun LocalAttachmentThumb(path: String, onClick: () -> Unit) {
    val ctx = LocalContext.current
    var bmp by remember(path) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(path) {
        bmp = withContext(Dispatchers.IO) { decodeThumb(attachmentFile(ctx, path), 512) }
    }
    Box(
        modifier = Modifier
            .size(120.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = path.substringAfterLast('/'),
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(120.dp),
            )
        } else {
            CircularProgressIndicator(strokeWidth = 2.dp)
        }
    }
}

/** 非图片附件：文件名 + 图标的文件片。 */
@Composable
internal fun AttachmentFileChip(path: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GlyphIcon(
            glyph = Glyph.FileDoc,
            size = 16.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = path.substringAfterLast('/'),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

/** 全屏位图查看器：点任意处关闭（缩放手势交给系统图库级实现，本期从简）。 */
@Composable
internal fun FullscreenBitmapViewer(bitmap: android.graphics.Bitmap, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "全屏查看",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 全屏查看本地文件（用户附件缩略图点开时用）：读取后复用位图查看器。 */
@Composable
internal fun FullscreenImageViewer(path: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var bmp by remember(path) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(path) {
        if (path.isImagePath()) {
            bmp = withContext(Dispatchers.IO) { decodeThumb(attachmentFile(ctx, path), 2048) }
        }
    }
    Box(Modifier) {
        val b = bmp
        if (b != null) {
            FullscreenBitmapViewer(b, onClose)
        } else {
            // 非图片或解码失败：文件片 + 关闭
            Dialog(onDismissRequest = onClose) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = path.substringAfterLast('/'),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "该附件类型暂不支持全屏预览",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
