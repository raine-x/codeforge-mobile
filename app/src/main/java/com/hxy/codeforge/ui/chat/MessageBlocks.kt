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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
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
import com.hxy.codeforge.ui.common.noRippleClickable
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

/** 图片位图缓存：按 KiB 计量（占进程堆上限 1/8，钳位 8～32 MiB），避免原图张数上限撑爆内存。 */
private val BITMAP_CACHE_KB = (Runtime.getRuntime().maxMemory() / 1024L / 8L)
    .toInt()
    .coerceIn(8 * 1024, 32 * 1024)

internal val bitmapCache = object : android.util.LruCache<String, android.graphics.Bitmap>(BITMAP_CACHE_KB) {
    override fun sizeOf(key: String, value: android.graphics.Bitmap): Int =
        (value.allocationByteCount / 1024).coerceAtLeast(1)
}

/** 系统内存压力或界面退后台时驱逐图片缓存。 */
internal fun trimMediaBitmapCache(level: Int) {
    if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
        bitmapCache.evictAll()
    }
}

private fun decodeSampledBytes(bytes: ByteArray, maxDim: Int = 2048): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) {
        sample *= 2
    }
    return BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )
}

internal val mediaHttp = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

/** 统一的媒体位图加载：http(s) 走 OkHttp，其余按工作区相对路径读文件；超大原图按 2048px 降采样。 */
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
            bytes?.let { decodeSampledBytes(it) }
        }.getOrNull()?.also { bitmapCache.put(url, it) }
    }
}

private sealed interface ImgState {
    data object Loading : ImgState
    data class Ok(val bitmap: android.graphics.Bitmap) : ImgState
    data object Err : ImgState
}

// ---------------------------------------------------------------- SVG

/** 判定路径或 URI 是否为 SVG 文件或 `data:image/svg+xml` 内联矢量图。 */
fun isSvgPathOrDataUri(url: String): Boolean {
    val t = url.trim()
    if (t.startsWith("data:image/svg+xml", ignoreCase = true)) return true
    val clean = t.substringBefore('?').substringBefore('#').lowercase()
    return clean.endsWith(".svg")
}

/** 从 data URI、http(s) 或工作区本地文件加载 SVG 源码文本；非 SVG 返回 null。 */
internal suspend fun loadMediaSvg(
    ctx: android.content.Context,
    url: String,
    root: String = "",
): String? = withContext(Dispatchers.IO) {
    runCatching {
        val trimmed = url.trim()
        val rawText: String? = when {
            trimmed.startsWith("data:image/svg+xml", ignoreCase = true) -> {
                val comma = trimmed.indexOf(',')
                if (comma < 0) null
                else {
                    val meta = trimmed.substring(0, comma).lowercase()
                    val payload = trimmed.substring(comma + 1)
                    if (meta.contains(";base64")) {
                        String(android.util.Base64.decode(payload, android.util.Base64.DEFAULT), Charsets.UTF_8)
                    } else {
                        Uri.decode(payload)
                    }
                }
            }
            trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true) -> {
                mediaHttp.newCall(Request.Builder().url(trimmed).build()).execute().use { r ->
                    if (!r.isSuccessful) null else r.body?.bytes()?.toString(Charsets.UTF_8)
                }
            }
            else -> {
                attachmentFile(ctx, trimmed, root).takeIf { it.exists() && it.isFile }?.readText(Charsets.UTF_8)
            }
        }
        rawText?.trim()?.takeIf { it.contains("<svg", ignoreCase = true) }
    }.getOrNull()
}

/**
 * 规范化 SVG 源码以确保在响应式容器中完整等比缩放：
 * - 截取 `<svg ...>...</svg>` 主体（剥除前置 `<?xml ...?>` 或 `<!DOCTYPE ...>`）；
 * - 若根 `<svg>` 标签未声明 `viewBox`，但带有数值型 `width` 与 `height`，自动补齐 `viewBox="0 0 w h"`，
 *   防止 CSS `width:100%;height:100%` 下只拉伸视口而不缩放坐标系导致图形被裁切。
 */
internal fun normalizeSvgForRender(rawSvg: String): String {
    val trimmed = rawSvg.trim()
    val startIdx = trimmed.indexOf("<svg", ignoreCase = true)
    if (startIdx < 0) return trimmed
    val endIdx = trimmed.lastIndexOf("</svg>", ignoreCase = true)
    val core = if (endIdx >= startIdx) {
        trimmed.substring(startIdx, endIdx + "</svg>".length)
    } else {
        trimmed.substring(startIdx)
    }
    val tagEnd = core.indexOf('>')
    if (tagEnd < 0) return core
    val openTag = core.substring(0, tagEnd + 1)

    val extraAttrs = buildString {
        // 裸 SVG 常省略 xmlns；注入默认 SVG 命名空间以保证内嵌与独立解析一致
        if (!Regex("""(?<![-\w])xmlns\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(openTag)) {
            append(""" xmlns="http://www.w3.org/2000/svg"""")
        }
        if (!Regex("""(?<![-\w])viewBox\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(openTag)) {
            val dims = parseSvgRootDimensions(openTag)
            if (dims != null) {
                val (w, h) = dims
                val wStr = if (w % 1f == 0f) w.toInt().toString() else w.toString()
                val hStr = if (h % 1f == 0f) h.toInt().toString() else h.toString()
                append(""" viewBox="0 0 $wStr $hStr"""")
            }
        }
    }
    if (extraAttrs.isEmpty()) return core
    return openTag.substring(0, 4) + extraAttrs + openTag.substring(4) + core.substring(tagEnd + 1)
}

/**
 * 从根 `<svg ...>` 开标签的 `viewBox` 或 `width`/`height`（含内联 `style`）推算宽高比；
 * 只解析根 `<svg>` 开标签，绝不误读 `stroke-width` 或内部 `<rect width="100%">` 等子元素属性。
 */
internal fun svgAspect(svg: String): Float? {
    val startIdx = svg.indexOf("<svg", ignoreCase = true)
    if (startIdx < 0) return null
    val tagEnd = svg.indexOf('>', startIdx)
    if (tagEnd < 0) return null
    val openTag = svg.substring(startIdx, tagEnd + 1)

    val vbMatch = Regex("""(?<![-\w])viewBox\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(openTag)
    val vbParts = vbMatch?.groupValues?.get(1)?.trim()?.split(Regex("""[,\s]+"""))
    if (vbParts != null && vbParts.size == 4) {
        val vbNum = Regex("""^([0-9]+(?:\.[0-9]+)?)(?:px)?$""", RegexOption.IGNORE_CASE)
        val w = vbNum.find(vbParts[2])?.groupValues?.get(1)?.toFloatOrNull()
        val h = vbNum.find(vbParts[3])?.groupValues?.get(1)?.toFloatOrNull()
        if (w != null && h != null && w > 0f && h > 0f) return w / h
    }

    val dims = parseSvgRootDimensions(openTag)
    return if (dims != null) dims.first / dims.second else null
}

private fun parseSvgDimNumber(raw: String?): Float? {
    val v = raw?.trim() ?: return null
    if (v.isEmpty() || v.endsWith("%") || v.equals("auto", ignoreCase = true)) return null
    val num = Regex("""^([0-9]+(?:\.[0-9]+)?)""").find(v)?.groupValues?.get(1)?.toFloatOrNull()
    return if (num != null && num > 0f) num else null
}

private fun parseSvgRootDimensions(openTag: String): Pair<Float, Float>? {
    // 使用负向后行避免 `stroke-width="..."` 误匹配为根 width
    val wAttr = Regex("""(?<![-\w])width\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        .find(openTag)?.groupValues?.get(1)
    val hAttr = Regex("""(?<![-\w])height\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        .find(openTag)?.groupValues?.get(1)
    val styleText = Regex("""(?<![-\w])style\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        .find(openTag)?.groupValues?.get(1)
    val wStyle = styleText?.let {
        Regex("""(?:^|;)\s*width\s*:\s*([^;]+)""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)
    }
    val hStyle = styleText?.let {
        Regex("""(?:^|;)\s*height\s*:\s*([^;]+)""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)
    }
    val w = parseSvgDimNumber(wAttr) ?: parseSvgDimNumber(wStyle) ?: return null
    val h = parseSvgDimNumber(hAttr) ?: parseSvgDimNumber(hStyle) ?: return null
    return w to h
}

internal fun buildSvgHtmlDocument(
    svg: String,
    lightCanvas: Boolean,
    transparentBg: Boolean = false,
    textColorHex: String? = null,
): String {
    val bg = if (transparentBg || !lightCanvas) "transparent" else "#FAF8F5"
    val fg = textColorHex ?: if (lightCanvas) "#1F1E1B" else "#EAE6DF"
    // 只约束顶层 `body > svg`，避免 `svg{width:100%!important}` 把架构图里嵌套的小 `<svg>` 图标拉爆
    return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=5,user-scalable=yes\">" +
        "<style>" +
        "html,body{margin:0;padding:0;width:100%;height:100%;background:$bg;color:$fg;overflow:hidden;" +
        "display:flex;align-items:center;justify-content:center;}" +
        "body>svg{width:100%;height:100%;max-width:100%;max-height:100%;display:block;}" +
        "[data-cf-send],[data-cf-prefill]{cursor:pointer;}" +
        "</style></head><body>" +
        svg +
        "</body></html>"
}

/** 检测 SVG 是否自带覆盖画布的不透明背景层（如整卡 `<rect width="100%" height="100%" fill="..."/>`）。 */
internal fun svgHasOpaqueBackdrop(svg: String): Boolean {
    val startIdx = svg.indexOf("<svg", ignoreCase = true)
    if (startIdx < 0) return false
    val tagEnd = svg.indexOf('>', startIdx)
    if (tagEnd < 0) return false
    val openTag = svg.substring(startIdx, tagEnd + 1)

    val rootStyleBg = Regex("""background(?:-color)?\s*:\s*([^;"']+)""", RegexOption.IGNORE_CASE)
        .find(openTag)?.groupValues?.get(1)?.trim()?.lowercase()
    if (rootStyleBg != null && rootStyleBg != "none" && rootStyleBg != "transparent") return true

    val rootDims = parseSvgRootDimensions(openTag) ?: run {
        val vb = Regex("""(?<![-\w])viewBox\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(openTag)?.groupValues?.get(1)?.trim()?.split(Regex("""[,\s]+"""))
        val w = vb?.getOrNull(2)?.removeSuffix("px")?.toFloatOrNull()
        val h = vb?.getOrNull(3)?.removeSuffix("px")?.toFloatOrNull()
        if (w != null && h != null && w > 0f && h > 0f) w to h else null
    }

    val rectRegex = Regex("""<rect\b([^>]{0,400})/?>""", RegexOption.IGNORE_CASE)
    for (m in rectRegex.findAll(svg).take(3)) {
        val attrs = m.groupValues[1]
        val wRaw = Regex("""(?<![-\w])width\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(attrs)?.groupValues?.get(1)?.trim()
        val hRaw = Regex("""(?<![-\w])height\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(attrs)?.groupValues?.get(1)?.trim()
        val fillAttr = Regex("""(?<![-\w])fill\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(attrs)?.groupValues?.get(1)?.trim()?.lowercase()
            ?: Regex("""fill\s*:\s*([^;"']+)""", RegexOption.IGNORE_CASE)
                .find(attrs)?.groupValues?.get(1)?.trim()?.lowercase()
        if (fillAttr == null || fillAttr == "none" || fillAttr == "transparent") continue

        val coversWidth = wRaw == "100%" || (
            rootDims != null && (parseSvgDimNumber(wRaw) ?: 0f) >= rootDims.first * 0.7f
        )
        val coversHeight = hRaw == "100%" || (
            rootDims != null && (parseSvgDimNumber(hRaw) ?: 0f) >= rootDims.second * 0.7f
        )
        if (coversWidth && coversHeight) return true
    }
    return false
}

/**
 * 深色模式下判断无底色透明 SVG 是否需要垫一块无框柔白底，
 * 防止 `#000`/`#333` 等深色线条在深灰背景上隐身；自带底色卡或纯浅色线条保持透明直接融入。
 */
internal fun needsLightBackdropInDarkMode(svg: String): Boolean {
    if (svgHasOpaqueBackdrop(svg)) return false
    val low = svg.lowercase()
    val hasDarkInk = Regex("""(?:#0[0-9a-f]{2}|#1[0-9a-f]{2}|#2[0-9a-f]{2}|#3[0-9a-f]{2}|#4[0-9a-f]{2}\b|\bblack\b)""")
        .containsMatchIn(low)
    if (hasDarkInk) return true
    val hasExplicitLightInk = Regex("""(?:#[c-f][0-9a-f]{2}|#[c-f][0-9a-f]{5}|\bwhite\b|currentcolor)""")
        .containsMatchIn(low)
    return !hasExplicitLightInk
}

/** 判定 SVG 是否包含交互声明（`data-cf-send` / `data-cf-prefill` / `window.__cf` / 脚本 / 内联点击）。 */
internal fun isInteractiveSvg(svg: String): Boolean {
    val low = svg.lowercase()
    return low.contains("data-cf-send") ||
        low.contains("data-cf-prefill") ||
        low.contains("window.__cf") ||
        low.contains("<script") ||
        low.contains("onclick=")
}

private class SvgWebHolder {
    var pendingHtml: String = ""
    var loadedHtml: String = ""
    var lastBgColor: Int? = null
}

private fun loadSvgIfLaidOut(wv: WebView, holder: SvgWebHolder) {
    val target = holder.pendingHtml
    if (target.isEmpty() || holder.loadedHtml == target) return
    if (wv.width > 0 && wv.height > 0) {
        holder.loadedHtml = target
        wv.loadDataWithBaseURL("https://codeforge.local/", target, "text/html", "utf-8", null)
    }
}

/**
 * 统一的 SVG `WebView` 宿主：
 * 1. 等视图获得非零宽高后再调用 `loadDataWithBaseURL`，避免 `LazyColumn` 首帧 0x0 布局下 `<svg>` 按 0 高度排版；
 * 2. 无论是否交互态一律挂载 `WebViewClient` + `postVisualStateCallback` 与重挂/尺寸变化刷新，防止滚动离屏再滑回后空白；
 * 3. `onRelease` 及时销毁 `WebView`，防止多轮长对话里多张 SVG 泄漏原生渲染表面。
 */
@Composable
private fun SvgWebCanvas(
    html: String,
    canvasColor: Int,
    modifier: Modifier = Modifier,
    interactive: Boolean = false,
    zoomable: Boolean = false,
) {
    val chatActions by rememberUpdatedState(LocalChatUiActions.current)
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }

    key(interactive, zoomable) {
        AndroidView(
            modifier = modifier,
            factory = { wctx ->
                val holder = SvgWebHolder()
                WebView(wctx).apply {
                    tag = holder
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    settings.javaScriptEnabled = interactive
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    if (zoomable) {
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                    }
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    setBackgroundColor(canvasColor)
                    holder.lastBgColor = canvasColor

                    if (interactive) {
                        addJavascriptInterface(
                            com.hxy.codeforge.ui.browser.PageBridge.AndroidBridge { ev ->
                                when (ev.kind) {
                                    "send" -> mainHandler.post { chatActions?.onSend?.invoke(ev.payload) }
                                    "prefill" -> mainHandler.post { chatActions?.onPrefill?.invoke(ev.payload) }
                                    "toast" -> mainHandler.post { chatActions?.onToast?.invoke(ev.payload) }
                                }
                            },
                            "__cfAndroid",
                        )
                    }

                    webViewClient = object : android.webkit.WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            if (interactive) {
                                view.evaluateJavascript(com.hxy.codeforge.ui.browser.PageBridge.SCRIPT, null)
                            }
                            view.postVisualStateCallback(
                                0L,
                                object : WebView.VisualStateCallback() {
                                    override fun onComplete(requestId: Long) {
                                        view.requestLayout()
                                        view.postInvalidateOnAnimation()
                                    }
                                },
                            )
                            view.postInvalidateOnAnimation()
                        }
                    }

                    addOnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                        val wv = v as WebView
                        val h = (wv.tag as? SvgWebHolder) ?: return@addOnLayoutChangeListener
                        val newW = right - left
                        val newH = bottom - top
                        val oldW = oldRight - oldLeft
                        val oldH = oldBottom - oldTop
                        if (newW > 0 && newH > 0) {
                            loadSvgIfLaidOut(wv, h)
                            if (oldW != newW || oldH != newH) {
                                wv.postInvalidateOnAnimation()
                            }
                        }
                    }

                    addOnAttachStateChangeListener(
                        object : android.view.View.OnAttachStateChangeListener {
                            override fun onViewAttachedToWindow(v: android.view.View) {
                                val wv = v as WebView
                                val h = wv.tag as? SvgWebHolder
                                if (h != null) loadSvgIfLaidOut(wv, h)
                                v.postInvalidateOnAnimation()
                            }

                            override fun onViewDetachedFromWindow(v: android.view.View) = Unit
                        },
                    )
                }
            },
            update = { wv ->
                val holder = (wv.tag as? SvgWebHolder) ?: SvgWebHolder().also { wv.tag = it }
                if (holder.lastBgColor != canvasColor) {
                    holder.lastBgColor = canvasColor
                    wv.setBackgroundColor(canvasColor)
                }
                holder.pendingHtml = html
                loadSvgIfLaidOut(wv, holder)
            },
            onRelease = { wv ->
                runCatching { wv.stopLoading() }
                runCatching { wv.destroy() }
            },
        )
    }
}

/**
 * SVG 块：无边框、无顶栏直接融入对话流呈现矢量图形，避免额外框线造成的视觉割裂。
 * 非交互态轻触进入全屏查看器（支持双指缩放、暖白/深色底切换、源码预览与复制）；
 * 交互态（`data-cf-send` 等）将点击保留给图元本身，仅在右上角留一枚轻量全屏角标。
 */
@Composable
fun MessageSvgBlock(
    svg: String,
    modifier: Modifier = Modifier,
    title: String = "SVG 矢量图",
) {
    val normalized = remember(svg) { normalizeSvgForRender(svg) }
    val interactive = remember(normalized) { isInteractiveSvg(normalized) }
    val aspect = remember(normalized) { (svgAspect(normalized) ?: 1.5f).coerceIn(0.4f, 4f) }
    val isLightTheme = MaterialTheme.colorScheme.onBackground.luminance() < 0.5f
    val needsDarkModeMat = remember(normalized, isLightTheme) {
        !isLightTheme && needsLightBackdropInDarkMode(normalized)
    }
    var fullscreen by remember(svg) { mutableStateOf(false) }

    val html = remember(normalized, isLightTheme, needsDarkModeMat) {
        buildSvgHtmlDocument(
            svg = normalized,
            lightCanvas = isLightTheme || needsDarkModeMat,
            transparentBg = true,
            textColorHex = if (isLightTheme || needsDarkModeMat) "#1F1E1C" else "#EAE6DF",
        )
    }

    val canvasModifier = modifier
        .fillMaxWidth()
        .padding(vertical = 4.dp)
        .aspectRatio(aspect)
        .let { mod ->
            if (needsDarkModeMat) {
                // 不对包着 WebView 的容器做 clip(RoundedCornerShape)，只垫背景 + 内边距，兼容硬件渲染层
                mod.background(Color(0xFFF7F5EF), blockCardShape).padding(6.dp)
            } else {
                mod
            }
        }

    Box(modifier = canvasModifier) {
        SvgWebCanvas(
            html = html,
            canvasColor = android.graphics.Color.TRANSPARENT,
            interactive = interactive,
            zoomable = false,
            modifier = Modifier.fillMaxSize(),
        )
        if (interactive) {
            Text(
                text = "全屏",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f))
                    .noRippleClickable { fullscreen = true }
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
        } else {
            // WebView 默认拦截触摸，非交互态用无波纹透明遮罩承接全屏手势，消除点击高亮闪烁
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .noRippleClickable { fullscreen = true },
            )
        }
    }

    if (fullscreen) {
        FullscreenSvgViewer(
            svg = normalized,
            title = title,
            initialLightCanvas = isLightTheme || needsDarkModeMat,
            onClose = { fullscreen = false },
        )
    }
}

@Composable
private fun SvgActionPill(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

/** 全屏 SVG 查看器：支持双指缩放、底色切换、源码查看与复制。 */
@Composable
internal fun FullscreenSvgViewer(
    svg: String,
    title: String = "SVG 矢量图",
    initialLightCanvas: Boolean = true,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val normalized = remember(svg) { normalizeSvgForRender(svg) }
    var lightCanvas by remember(initialLightCanvas) { mutableStateOf(initialLightCanvas) }
    var showSource by remember(svg) { mutableStateOf(false) }
    val html = remember(normalized, lightCanvas) { buildSvgHtmlDocument(normalized, lightCanvas) }

    Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF141312))
                .safeDrawingPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF22201E))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!showSource) {
                    SvgActionPill(label = if (lightCanvas) "暖白底" else "深色底") {
                        lightCanvas = !lightCanvas
                    }
                }
                SvgActionPill(label = if (showSource) "预览" else "源码") {
                    showSource = !showSource
                }
                SvgActionPill(label = "复制") {
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("svg", svg.trim()))
                    Toast.makeText(ctx, "已复制 SVG 源码", Toast.LENGTH_SHORT).show()
                }
                SvgActionPill(label = "关闭", onClick = onClose)
            }

            if (showSource) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF1A1917))
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp),
                ) {
                    SelectionContainer {
                        Text(
                            text = svg.trim(),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 19.sp,
                            ),
                            color = Color(0xFFEAE6DF),
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(if (lightCanvas) Color(0xFFFAF8F5) else Color(0xFF141312))
                        .padding(12.dp),
                ) {
                    SvgWebCanvas(
                        html = html,
                        canvasColor = if (lightCanvas) 0xFFFAF8F5.toInt() else 0xFF141312.toInt(),
                        interactive = false,
                        zoomable = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 图片

@Composable
fun MessageImageBlock(url: String, alt: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val root = LocalAttachmentRoot.current
    var state by remember(url) { mutableStateOf<ImgState>(ImgState.Loading) }
    var svgContent by remember(url) { mutableStateOf<String?>(null) }
    var fullscreen by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url, ctx, root) {
        svgContent = null
        if (isSvgPathOrDataUri(url)) {
            val loadedSvg = loadMediaSvg(ctx, url, root)
            if (loadedSvg != null) {
                svgContent = loadedSvg
                return@LaunchedEffect
            }
        }
        bitmapCache.get(url)?.let { state = ImgState.Ok(it); return@LaunchedEffect }
        state = ImgState.Loading
        val bmp = loadMediaBitmap(ctx, url, root)
        if (bmp != null) {
            state = ImgState.Ok(bmp)
        } else {
            // 兜底：部分无 .svg 后缀的徽章/图表接口实际返回 SVG XML
            val fallbackSvg = loadMediaSvg(ctx, url, root)
            if (fallbackSvg != null) {
                svgContent = fallbackSvg
            } else {
                state = ImgState.Err
            }
        }
    }

    val loadedSvg = svgContent
    if (loadedSvg != null) {
        val label = alt.ifBlank {
            url.substringAfterLast('/').substringBefore('?').takeIf { !it.startsWith("data:") }.orEmpty()
                .ifBlank { "SVG 矢量图" }
        }
        MessageSvgBlock(svg = loadedSvg, modifier = modifier, title = label)
        return
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
        l.endsWith(".webp") || l.endsWith(".gif") || l.endsWith(".svg")
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

/** 用户消息里的图片附件缩略图（120dp 方块，点击进全屏；支持位图与 .svg 矢量图）。 */
@Composable
internal fun LocalAttachmentThumb(path: String, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val root = LocalAttachmentRoot.current
    var bmp by remember(path, root) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var svgText by remember(path, root) { mutableStateOf<String?>(null) }
    LaunchedEffect(path, root) {
        if (isSvgPathOrDataUri(path)) {
            svgText = loadMediaSvg(ctx, path, root)
        } else {
            bmp = withContext(Dispatchers.IO) { decodeThumb(attachmentFile(ctx, path, root), 512) }
        }
    }
    val loadedSvg = svgText
    val b = bmp
    Box(
        modifier = Modifier
            .size(120.dp)
            .let { if (loadedSvg != null) it.background(Color(0xFFFAF8F5), RoundedCornerShape(12.dp)).padding(4.dp) else it.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface) }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loadedSvg != null -> {
                val normalized = remember(loadedSvg) { normalizeSvgForRender(loadedSvg) }
                val html = remember(normalized) { buildSvgHtmlDocument(normalized, lightCanvas = true) }
                SvgWebCanvas(
                    html = html,
                    canvasColor = 0xFFFAF8F5.toInt(),
                    interactive = false,
                    zoomable = false,
                    modifier = Modifier.fillMaxSize(),
                )
                // WebView 默认拦截触摸，需透明顶层遮罩承接点击全屏预览
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(onClick = onClick),
                )
            }
            b != null -> {
                Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = path.substringAfterLast('/'),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(120.dp),
                )
            }
            else -> {
                CircularProgressIndicator(strokeWidth = 2.dp)
            }
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

/** 全屏查看本地文件（用户附件缩略图点开时用）：支持位图与 .svg 矢量图。 */
@Composable
internal fun FullscreenImageViewer(path: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val root = LocalAttachmentRoot.current
    var bmp by remember(path, root) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var svgText by remember(path, root) { mutableStateOf<String?>(null) }
    LaunchedEffect(path, root) {
        if (isSvgPathOrDataUri(path)) {
            svgText = loadMediaSvg(ctx, path, root)
        } else if (path.isImagePath()) {
            bmp = withContext(Dispatchers.IO) { decodeThumb(attachmentFile(ctx, path, root), 2048) }
        }
    }
    Box(Modifier) {
        val loadedSvg = svgText
        val b = bmp
        if (loadedSvg != null) {
            FullscreenSvgViewer(
                svg = loadedSvg,
                title = path.substringAfterLast('/'),
                onClose = onClose,
            )
        } else if (b != null) {
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
