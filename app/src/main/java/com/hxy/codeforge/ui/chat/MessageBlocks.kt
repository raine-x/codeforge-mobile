package com.hxy.codeforge.ui.chat

import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.hxy.codeforge.ui.common.Glyph
import com.hxy.codeforge.ui.common.GlyphIcon
import com.hxy.codeforge.ui.theme.Clay
import android.widget.Toast
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
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

/**
 * 当前消息所属工作区的根（绝对路径），由 AppRoot 在消息区外提供。
 *
 * 附件在消息里存的是**工作区相对路径**（`attachments/xxx`，内核 history 的原样），
 * 而工作区可能是项目目录、也可能是应用私有 inbox —— 不能用固定的
 * `filesDir/workspace` 去猜（问题 1：发送后缩略图一直转圈就是猜错了根）。
 */
val LocalAttachmentRoot = androidx.compose.runtime.staticCompositionLocalOf { "" }

/** 播放器/读取器用的真实数据源：http(s) 原样，其余解析到工作区真实文件。 */
internal fun mediaDataSource(
    ctx: android.content.Context,
    url: String,
    root: String = "",
): String =
    if (url.startsWith("http://") || url.startsWith("https://")) {
        url
    } else {
        attachmentFile(ctx, url, root).absolutePath
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
    root: String = "",
): android.graphics.Bitmap? {
    bitmapCache.get(url)?.let { return it }
    return withContext(Dispatchers.IO) {
        runCatching {
            val bytes: ByteArray? = if (url.startsWith("http")) {
                mediaHttp.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) null else r.body?.bytes()
                }
            } else {
                attachmentFile(ctx, url, root).takeIf { it.exists() }?.readBytes()
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
    val root = LocalAttachmentRoot.current
    var state by remember(url) { mutableStateOf<ImgState>(ImgState.Loading) }
    var fullscreen by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url, ctx, root) {
        bitmapCache.get(url)?.let { state = ImgState.Ok(it); return@LaunchedEffect }
        state = ImgState.Loading
        val bmp = loadMediaBitmap(ctx, url, root)
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

/**
 * 视频块：VideoView 装进设满约束的 FrameLayout，控制条由 Compose 自绘。
 *
 * 旧实现（裸 VideoView 交给 factory + 系统 MediaController）有四个坑：
 * ① factory 返回的 View 按 WRAP_CONTENT 测量，VideoView.onMeasure 会按视频
 *    内在分辨率定尺寸，外层 height(240.dp) 被无视，后续内容被顶出消息区；
 * ② MediaController 是挂在 WindowManager 上的独立 Dialog 窗口，不参与
 *    Compose 层级裁剪，控制条浮在消息区与输入卡之上；
 * ③ 没挂 onError：加载失败只有一块纯黑；
 * ④ 没挂 onCompletion：播完停在末帧，点播放没有反应（无法重播）。
 */
@Composable
fun MessageVideoBlock(url: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val root = LocalAttachmentRoot.current
    var prepared by remember(url) { mutableStateOf(false) }
    var playing by remember(url) { mutableStateOf(false) }
    var finished by remember(url) { mutableStateOf(false) }
    var error by remember(url) { mutableStateOf(false) }
    var position by remember(url) { mutableIntStateOf(0) }
    var duration by remember(url) { mutableIntStateOf(0) }

    // factory 只在 AndroidView 首次挂载时执行一次，闭包捕获的状态委托在 url
    // 变化（remember(url) 换新状态对象）后全部过期 —— 所以 View 引用放 holder，
    // 监听器挪到 update 里每次重组重挂，永远拿到最新委托。
    val holder = remember { object { var view: VideoView? = null } }

    // 离开组合时兜底停播（与 onRelease 幂等）。key 不带 url：换源走 update 的
    // setVideoURI（内部自会重置旧播放），在这里 stopPlayback 反而把新源杀掉。
    DisposableEffect(Unit) { onDispose { holder.view?.stopPlayback() } }

    // 播放状态一律问 VideoView 自己，不信 Compose 侧快照 ——
    // 外部暂停（系统打断、窗口失焦）只反映在 View 上
    LaunchedEffect(playing, prepared) {
        while (prepared) {
            val vv = holder.view
            if (vv != null) {
                runCatching {
                    val pos = vv.currentPosition
                    if (pos >= 0) position = pos
                    val now = vv.isPlaying
                    if (now != playing && !finished) playing = now
                }
            }
            delay(200)
        }
    }

    fun toggle() {
        val vv = holder.view ?: return
        when {
            vv.isPlaying -> { vv.pause(); playing = false }
            finished -> { vv.seekTo(0); finished = false; vv.start(); playing = true }
            prepared -> { vv.start(); playing = true }
        }
    }

    if (error) {
        // 与图片块同款：失败态整块换错误行，不留黑屏
        MediaErrorRow("视频加载失败：$url")
    } else {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(blockCardShape)
                .background(Color.Black),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { vctx ->
                    FrameLayout(vctx).apply {
                        // VideoView 内是 SurfaceView，画面渲染在独立 surface 上，
                        // Compose 的 clip 管不到 —— 由容器自己裁剪子层兜底夹住溢出
                        clipChildren = true
                        clipToPadding = true
                        val video = VideoView(vctx).apply {
                            // 显式 MATCH_PARENT + 居中：不再按视频内在分辨率测量；
                            // VideoView 自己会按宽高比收缩出黑边（不拉伸变形）
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                Gravity.CENTER,
                            )
                        }
                        addView(video)
                        holder.view = video
                    }
                },
                update = { frame ->
                    val video = frame.getChildAt(0) as VideoView
                    holder.view = video

                    video.setOnPreparedListener { mp ->
                        prepared = true
                        error = false
                        duration = mp.duration.coerceAtLeast(1)
                        position = 0
                        // 不自动播放，等用户点控制条（要回自动播放：mp.start() + playing = true）
                    }
                    video.setOnCompletionListener {
                        playing = false
                        finished = true
                        position = duration
                    }
                    video.setOnErrorListener { _, _, _ ->
                        error = true
                        playing = false
                        prepared = false
                        true // 吞掉错误，不上抛系统默认错误弹层
                    }

                    val source = mediaDataSource(ctx, url, root)
                    if (video.tag != source) {
                        video.tag = source
                        prepared = false
                        playing = false
                        finished = false
                        error = false
                        position = 0
                        duration = 0
                        val videoUri = if (source.startsWith("http")) {
                            Uri.parse(source)
                        } else {
                            Uri.fromFile(java.io.File(source))
                        }
                        video.setVideoURI(videoUri)
                    }
                },
                onRelease = { frame ->
                    (frame.getChildAt(0) as? VideoView)?.stopPlayback()
                    holder.view = null
                },
            )

            if (!prepared) {
                // 改成点播不自动播之后，prepared 前是纯黑画面——给个加载反馈
                Box(Modifier.align(Alignment.Center)) {
                    CircularProgressIndicator(strokeWidth = 2.5.dp)
                }
            }

            // 自绘控制条：全在 Compose 层级，随消息列表裁剪，不再浮出窗口
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .clickable { toggle() },
                    contentAlignment = Alignment.Center,
                ) {
                    AudioPlayGlyph(playing = playing, color = MaterialTheme.colorScheme.onPrimary)
                }
                Slider(
                    value = if (duration > 0) position.toFloat() / duration else 0f,
                    onValueChange = { ratio ->
                        val vv = holder.view ?: return@Slider
                        if (prepared) {
                            val target = (ratio * duration).roundToInt()
                            vv.seekTo(target)
                            position = target
                            if (finished && ratio < 1f) finished = false
                        }
                    },
                    enabled = prepared,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${formatMs(position)} / ${formatMs(duration)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 音频

@Composable
fun MessageAudioBlock(url: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val root = LocalAttachmentRoot.current
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
                    player.setDataSource(mediaDataSource(ctx, url, root))
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

/**
 * 附件路径 → 工作区真实文件。
 *
 * 绝对路径原样返回（本地暂存 / 内核回传的绝对路径）；相对路径按 [root]
 * 解析 —— [root] 是当前会话的工作区根，由 [LocalAttachmentRoot] 提供。
 * 只有 root 缺失时才退回老的 `filesDir/workspace`（旧数据兜底）。
 */
internal fun attachmentFile(
    ctx: android.content.Context,
    rel: String,
    root: String = "",
): java.io.File {
    val clean = rel.removePrefix("file://")
    val f = java.io.File(clean)
    if (f.isAbsolute) return f
    if (root.isNotBlank()) return java.io.File(root, clean)
    return java.io.File(java.io.File(ctx.filesDir, "workspace"), clean)
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
    val root = LocalAttachmentRoot.current
    var bmp by remember(path, root) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(path, root) {
        bmp = withContext(Dispatchers.IO) { decodeThumb(attachmentFile(ctx, path, root), 512) }
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
    val root = LocalAttachmentRoot.current
    var bmp by remember(path, root) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(path, root) {
        if (path.isImagePath()) {
            bmp = withContext(Dispatchers.IO) { decodeThumb(attachmentFile(ctx, path, root), 2048) }
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

// ---------------------------------------------------------------- 音视频标签式跳转卡片

/**
 * 匹配格式：`[ ▶视频/音频 (可选 url) ]`
 * 支持变体：
 *  - `[ ▶视频 https://example.com/demo.mp4 ]`
 *  - `[ ▶音频 https://example.com/demo.mp3 ]`
 *  - `[ ▶视频 ]`
 *  - `[▶视频: url]` / `[▶音频(url)]`
 *  - 中文全角方括号 `【 ▶视频 ... 】`
 *
 * 末尾的 `(?!\()` 是**必须**的：模型很爱把这个标记写成 Markdown 链接 ——
 * `[ ▶视频 描述文字 ](https://.../a.mp4)`。没有这条断言时，正则会吃掉
 * 「描述文字」当地址（`[^\s...]+` 允许中文与空格外的任意字符），
 * 方括号后面真正的链接反被丢弃 —— 卡片点开是「未指定跳转链接」。
 * 加了断言后这种形态**不被认成标签**，整段按普通 Markdown 链接渲染，
 * 点开仍然是浏览器打开视频，行为正确。
 */
val MEDIA_JUMP_TAG_REGEX = Regex(
    """[\[【]\s*▶\s*(视频|音频)(?:[\s:：]|[(（])*\s*(?:[(（]\s*)?([^\s()（）\]】]+)?(?:\s*[)）])?\s*[\]】](?!\()"""
)

/**
 * 解析音视频标签跳转卡片。匹配成功返回 Pair(类型, url)，否则 null。
 * url 若未填写则返回空字符串。
 */
fun parseMediaJumpTag(raw: String): Pair<String, String>? {
    val m = MEDIA_JUMP_TAG_REGEX.matchEntire(raw.trim()) ?: MEDIA_JUMP_TAG_REGEX.find(raw.trim()) ?: return null
    val type = m.groupValues[1]
    var url = m.groupValues.getOrNull(2)?.trim().orEmpty()
    url = url.removePrefix("(").removePrefix("（").removeSuffix(")").removeSuffix("）").trim()
    return type to url
}

/** 视频扩展名判定（忽略 query/fragment） */
internal fun isVideoPath(url: String): Boolean {
    val clean = url.substringBefore('?').substringBefore('#').lowercase()
    return clean.endsWith(".mp4") || clean.endsWith(".webm") || clean.endsWith(".mkv") ||
        clean.endsWith(".mov") || clean.endsWith(".m4v") || clean.endsWith(".3gp")
}

/** 音频扩展名判定（忽略 query/fragment） */
internal fun isAudioPath(url: String): Boolean {
    val clean = url.substringBefore('?').substringBefore('#').lowercase()
    return clean.endsWith(".mp3") || clean.endsWith(".wav") || clean.endsWith(".ogg") ||
        clean.endsWith(".m4a") || clean.endsWith(".aac") || clean.endsWith(".flac")
}

/**
 * 调用系统外部播放器/应用打开媒体 URI（支持网络链接与工作区本地相对路径）。
 */
fun launchMediaUri(context: android.content.Context, url: String, root: String) {
    if (url.isBlank()) {
        Toast.makeText(context, "未指定跳转链接", Toast.LENGTH_SHORT).show()
        return
    }
    val uri = if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
        Uri.parse(url)
    } else {
        val file = attachmentFile(context, url, root)
        if (!file.exists()) {
            Toast.makeText(context, "本地媒体文件不存在：${file.name}", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (_: Exception) {
            Uri.fromFile(file)
        }
    }

    val isVideo = isVideoPath(url)
    val isAudio = isAudioPath(url)
    val mimeType = when {
        isVideo -> "video/*"
        isAudio -> "audio/*"
        else -> null
    }

    val intent = Intent(Intent.ACTION_VIEW).apply {
        if (mimeType != null) {
            setDataAndType(uri, mimeType)
        } else {
            data = uri
        }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        // 如果系统没有匹配特定 MIME 的专门播放器，退回到普通 ACTION_VIEW（由系统或浏览器处理）
        try {
            val fallbackIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(fallbackIntent)
        } catch (e: Exception) {
            Toast.makeText(context, "无法打开媒体：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * 标签式音视频跳转卡片组件。
 *
 * 针对 `[ ▶视频/音频 (可选 url) ]` 格式，呈现为具有精致视觉反馈的胶囊卡片，
 * 点击直接调用外部播放器或浏览器打开。
 */
@Composable
fun MediaJumpCard(
    type: String,
    url: String,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val root = LocalAttachmentRoot.current
    val isVideo = type.contains("视频")
    val hasUrl = url.isNotBlank()
    val displayName = if (hasUrl) {
        url.substringAfterLast('/').substringBefore('?').ifBlank { url }
    } else {
        if (isVideo) "点击跳转外部视频播放器" else "点击跳转外部音频播放器"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
            .border(1.dp, Clay.copy(alpha = 0.28f), RoundedCornerShape(12.dp))
            .clickable {
                launchMediaUri(ctx, url, root)
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 左侧播放微徽章
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Clay.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            if (isVideo) {
                Canvas(modifier = Modifier.size(13.dp)) {
                    val p = androidx.compose.ui.graphics.Path().apply {
                        moveTo(size.width * 0.22f, size.height * 0.15f)
                        lineTo(size.width * 0.88f, size.height * 0.5f)
                        lineTo(size.width * 0.22f, size.height * 0.85f)
                        close()
                    }
                    drawPath(p, color = Clay)
                }
            } else {
                GlyphIcon(Glyph.Speaker, 16.dp, Clay)
            }
        }

        // 中间文本描述
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = if (isVideo) "▶ 视频" else "▶ 音频",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = Clay,
                )
                if (hasUrl) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text = if (url.startsWith("http")) "网络" else "本地",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Text(
                text = displayName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // 右侧打开引导微胶囊
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Clay.copy(alpha = 0.12f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(
                text = "打开",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = Clay,
            )
            Text(
                text = "↗",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = Clay,
            )
        }
    }
}
