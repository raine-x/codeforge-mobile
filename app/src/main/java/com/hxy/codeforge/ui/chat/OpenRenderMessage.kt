package com.hxy.codeforge.ui.chat

import android.graphics.BitmapFactory
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hxy.codeforge.render.OpenRenderDocument
import com.hxy.codeforge.render.OpenRenderNative
import com.hxy.codeforge.render.RenderBlock
import com.hxy.codeforge.render.RenderMathImage
import com.hxy.codeforge.render.RenderSpan
import com.hxy.codeforge.render.RenderTable
import com.hxy.codeforge.ui.browser.BrowserTarget
import com.hxy.codeforge.ui.browser.HtmlBlockView
import com.hxy.codeforge.ui.browser.HtmlLoadingBlock
import com.hxy.codeforge.ui.browser.LocalBrowserController
import com.hxy.codeforge.ui.browser.buildStreamingSvgPreview
import com.hxy.codeforge.ui.browser.hasBareSvgOutsideFences
import com.hxy.codeforge.ui.browser.looksLikeHtmlDocumentLoose
import com.hxy.codeforge.ui.browser.looksLikeStreamingSvgStart
import com.hxy.codeforge.ui.browser.looksLikeSvgMarkup
import com.hxy.codeforge.ui.browser.mergeHtmlBlocks
import com.hxy.codeforge.ui.browser.looksLikeWholeHtmlDocument
import com.hxy.codeforge.ui.browser.splitProseAroundBareSvg
import com.hxy.codeforge.ui.browser.splitProseAroundHtml
import com.hxy.codeforge.ui.common.Glyph
import com.hxy.codeforge.ui.common.GlyphIcon
import com.hxy.codeforge.ui.common.noRippleClickable
import com.hxy.codeforge.ui.theme.SerifBody

/**
 * OpenRender（Rust / `libopenrender.so`）驱动的消息正文渲染。
 *
 * 引擎只解析并输出 IR（块 + 行内 span），排版全部在这里完成：这正是换引擎的
 * 核心收益 —— 流式期间不再每 20ms 全量重解析，公式不再进 WebView。
 *
 * 覆盖矩阵与旧管线的对照：
 *  - markdown：CommonMark + GFM（表格对齐/任务列表/删除线/脚注/GFM 警告块）；
 *  - 公式：`latex_block` / `latex_inline` 带 RaTeX 生成的 PNG（离线、无 WebView）；
 *  - SVG/图片/视频/音频围栏：引擎当 code_block 输出，这里按 lang 路由到原生媒体件；
 *  - 图片：markdown 图片是 image span —— 独占一段时按块级大图渲染，混排时内联缩略图；
 *  - 原样输出：不经过这里（MessageBubble 的 verbatim 分支直接画纯文本）。
 *
 * 原生库不可用（ABI 不符/加载失败）时整条消息退回纯文本，不吞内容。
 */
/**
 * 在流式期间按渲染帧节奏连续吐字，消除网络突发分片造成的“一卡一顿”；
 * 一旦流结束或文本发生非前缀替换（编辑/重生成），立即对齐目标全文，零延迟收口。
 */
@Composable
private fun rememberSmoothedStreamingSource(source: String, streaming: Boolean): String {
    if (!streaming) return source

    val latestSource by rememberUpdatedState(source)
    val latestStreaming by rememberUpdatedState(streaming)
    var displayedLen by remember { mutableIntStateOf(source.length) }
    var lastAnchor by remember { mutableStateOf(source) }

    if (source != lastAnchor) {
        if (!source.startsWith(lastAnchor)) {
            displayedLen = source.length
        }
        lastAnchor = source
    }

    LaunchedEffect(streaming) {
        while (latestStreaming) {
            withFrameNanos {
                val target = latestSource
                val targetLen = target.length
                if (displayedLen > targetLen) {
                    displayedLen = targetLen
                } else if (displayedLen < targetLen) {
                    val backlog = targetLen - displayedLen
                    // 少量积压每帧匀出 1~3 字维持丝滑连续，大量积压按比例追赶不掉队
                    val step = when {
                        backlog <= 4 -> 1
                        backlog <= 14 -> 2
                        backlog <= 40 -> (backlog + 5) / 6
                        else -> (backlog + 3) / 4
                    }
                    var nextLen = (displayedLen + step).coerceAtMost(targetLen)
                    if (nextLen < targetLen && nextLen > 0 && target[nextLen - 1].isHighSurrogate()) {
                        nextLen++
                    }
                    displayedLen = nextLen
                }
            }
        }
        displayedLen = latestSource.length
    }

    return source.substring(0, displayedLen.coerceIn(0, source.length))
}

@Composable
fun OpenRenderContent(
    source: String,
    streaming: Boolean,
    modifier: Modifier = Modifier,
    bodyStyle: TextStyle = SerifBody,
    smoothStreaming: Boolean = true,
) {
    val activeSource = if (smoothStreaming) rememberSmoothedStreamingSource(source, streaming) else source
    val density = LocalDensity.current.density
    // cacheDir 给公式渲染落地 Unicode 回退字体用（Android 无系统字体）
    val cacheDir = LocalContext.current.cacheDir.absolutePath
    val doc = remember(density, cacheDir) { OpenRenderDocument(density, cacheDir = cacheDir) }
    DisposableEffect(doc) { onDispose { doc.close() } }

    // 整页 HTML 文档：**不经过 markdown 引擎**，直接挂成可交互页面。
    //
    // 为什么必须先于引擎识别切分：CommonMark 的 HTML 块在空行处结束，之后的内容
    // 重新按 markdown 解析 —— 缩进 ≥4 空格的 HTML 行就变成 code_block，
    // 正文被从中间打断，缩进也被吃掉 4 格。碎片化之后拼不回原文。
    //
    // 判定必须在**喂引擎之前**做（见 looksLikeWholeHtmlDocument 的说明）。
    // 模型常输出「解说 + 文档 + 解说」：切出内层文档挂 WebView，解说走正文。
    if (!streaming && looksLikeWholeHtmlDocument(activeSource)) {
        val split = splitProseAroundHtml(activeSource)
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (split.before.isNotBlank()) {
                OpenRenderContent(
                    source = split.before,
                    streaming = false,
                    bodyStyle = bodyStyle,
                    smoothStreaming = false,
                )
            }
            HtmlBlockView(html = split.html ?: activeSource, title = "网页")
            if (split.after.isNotBlank()) {
                OpenRenderContent(
                    source = split.after,
                    streaming = false,
                    bodyStyle = bodyStyle,
                    smoothStreaming = false,
                )
            }
        }
        return
    }

    // 流式未闭合：认出在生成网页就先顶一个「正在生成」大框，源码不展示。
    // 高度固定，竖屏上不随增量跳动；写完后整块替换为 HtmlBlockView。
    if (streaming && looksLikeHtmlDocumentLoose(activeSource)) {
        val split = splitProseAroundHtml(activeSource)
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (split.before.isNotBlank()) {
                OpenRenderContent(
                    source = split.before,
                    streaming = true,
                    bodyStyle = bodyStyle,
                    smoothStreaming = false,
                )
            }
            HtmlLoadingBlock(title = "网页")
            if (split.after.isNotBlank()) {
                OpenRenderContent(
                    source = split.after,
                    streaming = true,
                    bodyStyle = bodyStyle,
                    smoothStreaming = false,
                )
            }
        }
        return
    }

    // 裸 <svg>...</svg>（含解说 + 裸 SVG + 解说，或带空行/4 格缩进的多行 SVG）：
    // 在喂给 markdown 引擎前切出，避免空行后缩进变 code_block 或混排段落被剥去标签。
    if (hasBareSvgOutsideFences(activeSource)) {
        val segments = remember(activeSource) { splitProseAroundBareSvg(activeSource) }
        if (segments.any { it.isSvg }) {
            Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                segments.forEachIndexed { idx, seg ->
                    val isLast = idx == segments.lastIndex
                    if (seg.isSvg) {
                        key("bare-svg-$idx") {
                            val resolvedSvg = if (seg.closed) {
                                seg.text
                            } else {
                                remember(seg.text) { buildStreamingSvgPreview(seg.text) }
                            }
                            if (resolvedSvg != null) {
                                MessageSvgBlock(svg = resolvedSvg, streaming = !seg.closed)
                            } else {
                                // 仅在 <svg ...> 起始标签本身未闭合的瞬时展示加载占位
                                SvgLoadingBlock()
                            }
                        }
                    } else if (seg.text.isNotBlank()) {
                        key("bare-prose-$idx") {
                            OpenRenderContent(
                                source = seg.text,
                                streaming = streaming && isLast,
                                bodyStyle = bodyStyle,
                                smoothStreaming = false,
                            )
                        }
                    }
                }
            }
            return
        }
    }

    if (!OpenRenderNative.available) {
        Text(
            text = activeSource,
            style = bodyStyle.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = modifier,
        )
        return
    }

    val blocks = remember(activeSource, streaming, doc) { doc.sync(activeSource, streaming) }
    if (doc.failed) {
        Text(
            text = activeSource,
            style = bodyStyle.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = modifier,
        )
        return
    }

    // 裸 HTML（AI 直接输出整页文档）要先合并成一块再渲染。
    // 流式期间不做：文档还没写完，提前渲染只会得到半张页面。
    val renderBlocks = remember(blocks, streaming) {
        if (streaming) blocks else mergeHtmlBlocks(blocks)
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        renderBlocks.forEachIndexed { index, block ->
            key(block.id) {
                RenderBlockView(
                    block = block,
                    bodyStyle = bodyStyle,
                    streaming = streaming,
                    caret = streaming && index == renderBlocks.lastIndex &&
                        (block.hasInlineText() || block.type == "latex_block"),
                    soleBlock = renderBlocks.size == 1,
                )
            }
        }
    }
}

// 块渲染

@Composable
private fun RenderBlockView(
    block: RenderBlock,
    bodyStyle: TextStyle,
    streaming: Boolean,
    caret: Boolean,
    soleBlock: Boolean,
) {
    // 仅针对流式期新出现的内容块做轻微淡入，滚动浏览历史消息不触发多余动画
    val animateBlockAppear = remember { streaming && block.type in setOf("paragraph", "heading", "list_item") }
    var shown by remember { mutableStateOf(!animateBlockAppear) }
    if (animateBlockAppear) {
        LaunchedEffect(Unit) { shown = true }
    }
    val blockAlpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0.35f,
        animationSpec = tween(120, easing = FastOutSlowInEasing),
        label = "stream-block-alpha",
    )

    val inner: @Composable () -> Unit = {
        when (block.type) {
            "paragraph" -> ParagraphBlock(block, bodyStyle, caret)
            "heading" -> HeadingBlock(block, bodyStyle, caret)
            "code_block" -> CodeFenceBlock(block, streaming, soleBlock)
            "list_item" -> ListItemBlock(block, bodyStyle, caret)
            "table" -> TableBlock(block.table, bodyStyle, streaming = streaming)
            "rule" -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            "footnote_def" -> FootnoteDefBlock(block, bodyStyle)
            "latex_block" -> MathBlock(block.math, block.text, degrade = caret)
            "audio" -> MessageAudioBlock(block.url)
            "link_preview" -> LinkPreviewCard(block)
            "html_raw" -> HtmlBlockView(html = block.text, title = "网页")
            "svg_raw" -> MessageSvgBlock(svg = block.text)
            else -> InlineSpans(
                spans = block.spans,
                style = bodyStyle,
                caret = caret,
            )
        }
    }

    val containerModifier = if (animateBlockAppear) Modifier.alpha(blockAlpha) else Modifier
    Box(modifier = containerModifier) {
        when {
            block.alert.isNotEmpty() -> AlertFrame(block) { inner() }
            block.quote > 0 -> QuoteFrame(block.quote) { inner() }
            else -> inner()
        }
    }
}

@Composable
private fun ParagraphBlock(
    block: RenderBlock,
    bodyStyle: TextStyle,
    caret: Boolean,
) {
    // 独占一段的媒体按块级渲染：图片可点开全屏、音频有真实播放器、裸 SVG 走矢量绘制、音视频跳转卡片
    val single = block.spans.singleOrNull()
    if (single != null) {
        when {
            single.type == "image" && single.src.isNotBlank() -> {
                // OpenRender 暂无 video 块：markdown 图片语法指向视频时由宿主升级成播放器
                if (isVideoPath(single.src)) {
                    MessageVideoBlock(single.src)
                } else {
                    MessageImageBlock(single.src, single.text)
                }
                return
            }

            single.type == "audio" && single.urlOrSrc().isNotBlank() -> {
                MessageAudioBlock(single.urlOrSrc())
                return
            }

            single.type == "text" -> {
                val jump = parseMediaJumpTag(single.text)
                if (jump != null) {
                    MediaJumpCard(type = jump.first, url = jump.second)
                    return
                }
                val resolvedSingleSvg = when {
                    looksLikeSvgMarkup(single.text) -> single.text
                    looksLikeStreamingSvgStart(single.text) ->
                        remember(single.text) { buildStreamingSvgPreview(single.text) }
                    else -> null
                }
                if (resolvedSingleSvg != null || looksLikeStreamingSvgStart(single.text)) {
                    if (resolvedSingleSvg != null) {
                        MessageSvgBlock(
                            svg = resolvedSingleSvg,
                            streaming = !looksLikeSvgMarkup(single.text),
                        )
                    } else {
                        SvgLoadingBlock()
                    }
                    return
                }
            }
        }
    }

    val fullText = block.spans.joinToString("") { it.text }
    val resolvedFullSvg = when {
        looksLikeSvgMarkup(fullText) -> fullText
        caret && looksLikeStreamingSvgStart(fullText) ->
            remember(fullText) { buildStreamingSvgPreview(fullText) }
        else -> null
    }
    if (resolvedFullSvg != null) {
        MessageSvgBlock(svg = resolvedFullSvg, streaming = !looksLikeSvgMarkup(fullText))
        return
    }
    if (caret && looksLikeStreamingSvgStart(fullText)) {
        SvgLoadingBlock()
        return
    }
    val fullJump = parseMediaJumpTag(fullText)
    if (fullJump != null) {
        MediaJumpCard(type = fullJump.first, url = fullJump.second)
        return
    }

    if (MEDIA_JUMP_TAG_REGEX.containsMatchIn(fullText)) {
        SegmentedParagraphBlock(fullText = fullText, bodyStyle = bodyStyle, caret = caret)
        return
    }

    InlineSpans(
        spans = block.spans,
        style = bodyStyle,
        caret = caret,
    )
}

/**
 * 段落中包含音视频跳转标签时的切分渲染：正文与卡片交替排列。
 */
@Composable
private fun SegmentedParagraphBlock(
    fullText: String,
    bodyStyle: TextStyle,
    caret: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        var lastIndex = 0
        MEDIA_JUMP_TAG_REGEX.findAll(fullText).forEach { match ->
            val prefix = fullText.substring(lastIndex, match.range.first).trim()
            if (prefix.isNotBlank()) {
                Text(
                    text = prefix,
                    style = bodyStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            val type = match.groupValues[1]
            val url = match.groupValues.getOrNull(2)?.trim().orEmpty()
            MediaJumpCard(type = type, url = url)
            lastIndex = match.range.last + 1
        }
        val suffix = fullText.substring(lastIndex).trim()
        if (suffix.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = suffix,
                    style = bodyStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (caret) {
                    Text(text = "▍", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun HeadingBlock(block: RenderBlock, bodyStyle: TextStyle, caret: Boolean) {
    val style = when (block.level) {
        1 -> bodyStyle.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
        2 -> bodyStyle.copy(fontSize = 17.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold)
        else -> bodyStyle.copy(fontWeight = FontWeight.Medium)
    }
    InlineSpans(spans = block.spans, style = style, caret = caret)
}

/**
 * 围栏块。引擎把 ```svg / ```image / ```video / ```audio 也当 code_block 输出，
 * 这里按 lang 路由到原生媒体渲染器 —— 引擎不画像素，宿主决定媒体怎么放。
 *
 * 流式未闭合时先按等宽文本展示（WebView/播放器每来一个增量就重建会整块闪烁）。
 */
@Composable
private fun CodeFenceBlock(block: RenderBlock, streaming: Boolean, soleBlock: Boolean) {
    val lang = block.lang.trim().lowercase()
    val openTail = streaming && !block.closed
    when (lang) {
        "html", "htm" -> {
            // AI 出的整页文档（题目页、解析展开、可点开的图表）：
            // 直接挂成可交互 WebView，按钮能点、答案能展开，不用来回切 App。
            // 若 ```html 围栏里装的其实是纯 <svg>...</svg>，优先走矢量图增量绘制。
            val svgCandidate = when {
                openTail && !block.text.contains("<html", ignoreCase = true) && looksLikeStreamingSvgStart(block.text) ->
                    remember(block.text) { buildStreamingSvgPreview(block.text) }
                !openTail && looksLikeSvgMarkup(block.text) -> block.text
                else -> null
            }
            if (svgCandidate != null) {
                MessageSvgBlock(svg = svgCandidate, streaming = openTail)
            } else if (openTail) {
                if (!block.text.contains("<html", ignoreCase = true) && looksLikeStreamingSvgStart(block.text)) {
                    SvgLoadingBlock()
                } else {
                    HtmlLoadingBlock(title = "网页")
                }
            } else if (block.text.isNotBlank()) {
                HtmlBlockView(html = block.text, title = "网页")
            } else {
                StreamingTailBlock(block.text)
            }
        }

        "svg" -> {
            val liveSvg = when {
                openTail -> remember(block.text) { buildStreamingSvgPreview(block.text) }
                block.text.contains("<svg", ignoreCase = true) -> block.text
                else -> null
            }
            if (liveSvg != null) {
                MessageSvgBlock(svg = liveSvg, streaming = openTail)
            } else if (openTail) {
                SvgLoadingBlock()
            } else {
                val url = firstNonBlankLine(block.text)
                if (url != null && isSvgPathOrDataUri(url)) {
                    MessageImageBlock(url, "")
                } else {
                    FencedCode(lang, block.text)
                }
            }
        }

        "ui", "widget" -> {
            if (openTail) {
                InteractiveUiLoadingCard()
            } else {
                val spec = remember(block.text) { parseInteractiveUiSpec(block.text) }
                if (spec != null) {
                    InteractiveUiCard(
                        spec = spec,
                        cardKey = "ui-${block.text.hashCode()}",
                    )
                } else {
                    FencedCode(lang, block.text)
                }
            }
        }

        "image", "img", "图片" -> {
            if (!openTail && looksLikeSvgMarkup(block.text)) {
                MessageSvgBlock(block.text)
            } else {
                val url = firstNonBlankLine(block.text)
                if (openTail || url == null) StreamingTailBlock(block.text) else MessageImageBlock(url, "")
            }
        }

        "video", "视频" -> {
            val url = firstNonBlankLine(block.text)
            if (openTail || url == null) StreamingTailBlock(block.text) else MessageVideoBlock(url)
        }

        "audio", "音频" -> {
            val url = firstNonBlankLine(block.text)
            if (openTail || url == null) StreamingTailBlock(block.text) else MessageAudioBlock(url)
        }

        "markdown", "md" -> {
            // 只有整条回复就是这一个围栏时才解开（模型常这么包）；混排里的
            // markdown 围栏是真的代码样例，必须按代码显示。
            if (!soleBlock) {
                FencedCode(lang, block.text)
            } else if (openTail) {
                StreamingTailBlock(block.text)
            } else if (block.text.isNotBlank()) {
                OpenRenderContent(source = block.text, streaming = false)
            }
        }

        else -> {
            val isSvgFence = lang in setOf("xml", "") &&
                (looksLikeSvgMarkup(block.text) || (openTail && looksLikeStreamingSvgStart(block.text)))
            if (isSvgFence) {
                val liveSvg = if (openTail) {
                    remember(block.text) { buildStreamingSvgPreview(block.text) }
                } else {
                    block.text
                }
                if (liveSvg != null) {
                    MessageSvgBlock(svg = liveSvg, streaming = openTail)
                } else {
                    SvgLoadingBlock()
                }
            } else {
                FencedCode(lang, block.text)
            }
        }
    }
}

@Composable
private fun FencedCode(lang: String, code: String) {
    val isPlainOutput = lang in plainOutputLanguages
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (lang.isNotEmpty()) {
            Text(
                text = lang,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val codePanel = if (isPlainOutput) Modifier else Modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
        Box(modifier = Modifier.fillMaxWidth().then(codePanel)) {
            Text(
                text = code.removeSuffix("\n"),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 19.sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                softWrap = false,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
        }
    }
}

private val plainOutputLanguages = setOf("", "text", "plain", "plaintext", "txt", "output", "log", "console")

@Composable
private fun ListItemBlock(block: RenderBlock, bodyStyle: TextStyle, caret: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (block.depth.coerceAtLeast(0) * 16).dp),
        verticalAlignment = Alignment.Top,
    ) {
        when {
            block.checked != null -> TaskCheck(block.checked)
            else -> Text(
                text = if (block.ordered == true) "${block.number}." else "•",
                style = bodyStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        InlineSpans(
            spans = block.spans,
            style = bodyStyle,
            caret = caret,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TaskCheck(checked: Boolean) {
    Box(
        modifier = Modifier
            .padding(top = 2.dp, end = 8.dp)
            .size(16.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (checked) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            GlyphIcon(glyph = Glyph.Check, size = 11.dp, color = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

private val TableSettleEasing = CubicBezierEasing(0.18f, 0.9f, 0.2f, 1f)

@Composable
private fun TableRowEntrance(streaming: Boolean, content: @Composable () -> Unit) {
    val animateRow = remember { streaming }
    var visible by remember { mutableStateOf(!animateRow) }
    if (animateRow) {
        LaunchedEffect(Unit) { visible = true }
    }
    val rowAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0.15f,
        animationSpec = tween(160, easing = FastOutSlowInEasing),
        label = "table-row-alpha",
    )
    Box(modifier = if (animateRow) Modifier.alpha(rowAlpha) else Modifier) {
        content()
    }
}

@Composable
private fun TableBlock(table: RenderTable?, bodyStyle: TextStyle, streaming: Boolean = false) {
    if (table == null || (table.header.isEmpty() && table.rows.isEmpty())) return
    val cols = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
    if (cols == 0) return
    val density = LocalDensity.current.density
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
    val rowDividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f)
    val colDividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.36f)
    val headerBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.54f)
    val evenRowBg = MaterialTheme.colorScheme.surface.copy(alpha = 0.32f)
    val oddRowBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
    val shape = RoundedCornerShape(12.dp)

    // 流式首次呈现时从 1.045x 柔和收缩落位至标准 1.0x 尺寸（顶中锚点防止上挤前文），历史消息直接静止呈现
    val animateEntrance = remember { streaming }
    var settled by remember { mutableStateOf(!animateEntrance) }
    if (animateEntrance) {
        LaunchedEffect(Unit) { settled = true }
    }
    val settleScale by animateFloatAsState(
        targetValue = if (settled) 1f else 1.045f,
        animationSpec = tween(320, easing = TableSettleEasing),
        label = "table-settle-scale",
    )
    val settleAlpha by animateFloatAsState(
        targetValue = if (settled) 1f else 0f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "table-settle-alpha",
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (animateEntrance) {
                    Modifier.graphicsLayer {
                        scaleX = settleScale
                        scaleY = settleScale
                        alpha = settleAlpha
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        val layout = computeTableLayout(table, cols, maxWidth.value, density)
        val widths = key(cols) {
            layout.widthsDp.mapIndexed { idx, targetW ->
                val animatedW by animateFloatAsState(
                    targetValue = targetW,
                    animationSpec = spring(
                        dampingRatio = 0.88f,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                    label = "table-col-$idx",
                )
                (if (streaming) animatedW else targetW).dp
            }
        }
        val tableContent: @Composable (Modifier) -> Unit = { colMod ->
            Column(
                modifier = colMod
                    .clip(shape)
                    .border(0.8.dp, borderColor, shape)
                    .animateContentSize(
                        animationSpec = spring(
                            dampingRatio = 0.9f,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    ),
            ) {
                if (table.header.isNotEmpty()) {
                    TableRow(
                        cells = table.header,
                        align = table.align,
                        widths = widths,
                        scrollable = layout.horizontalScroll,
                        bodyStyle = bodyStyle,
                        colDividerColor = colDividerColor,
                        rowBackground = headerBg,
                        header = true,
                    )
                }
                table.rows.forEachIndexed { index, row ->
                    key("row-$index") {
                        TableRowEntrance(streaming = streaming) {
                            Column {
                                if (index > 0 || table.header.isNotEmpty()) {
                                    HorizontalDivider(
                                        thickness = if (index == 0 && table.header.isNotEmpty()) 0.8.dp else 0.6.dp,
                                        color = if (index == 0 && table.header.isNotEmpty()) borderColor else rowDividerColor,
                                    )
                                }
                                TableRow(
                                    cells = row,
                                    align = table.align,
                                    widths = widths,
                                    scrollable = layout.horizontalScroll,
                                    bodyStyle = bodyStyle,
                                    colDividerColor = colDividerColor,
                                    rowBackground = if (index % 2 == 1) oddRowBg else evenRowBg,
                                    header = false,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (layout.horizontalScroll) {
            val totalWidth = widths.sumOf { it.value.toDouble() }.dp
            Box(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                tableContent(Modifier.width(totalWidth))
            }
        } else {
            tableContent(Modifier.fillMaxWidth())
        }
    }
}

/** 表格单元格字号；列宽换算要用同一个值，否则算出来的比例和实际排版对不上。 */
private val tableCellFontSize = 13.sp

/** 每格左右各 8dp 内边距。 */
private const val TABLE_CELL_PADDING = 8f

/** 多列表进入横向滚动前，正文列尽量保留的最小可读内容宽（dp）。 */
private const val TABLE_MIN_READABLE_COL_DP = 76f

/** 横向滚动表格的单列内容宽上限（dp），使长介绍列既舒展又不会无限拉宽单行。 */
private const val TABLE_MAX_SCROLL_COL_DP = 220f

internal data class TableLayoutSpec(
    val widthsDp: List<Float>,
    val horizontalScroll: Boolean,
)

/**
 * 计算表格列宽：
 * - 2–3 列或内容紧凑表格按 `sqrt(desired)` 加权分配铺满屏宽，避免短列占空过宽、长描述列被压扁；
 * - 4 列以上且整体内容显著超出屏宽（或有列被压到可读阈值下）时自动启用横向滚动与舒适列宽。
 */
internal fun computeTableLayout(
    table: RenderTable,
    cols: Int,
    availableDp: Float,
    density: Float,
): TableLayoutSpec {
    val desired = FloatArray(cols)
    val floor = FloatArray(cols)
    for (c in 0 until cols) {
        table.header.getOrNull(c)?.let { cell ->
            desired[c] = maxOf(desired[c], spansWidth(cell, density))
            floor[c] = maxOf(floor[c], unbreakableWidth(cell, density))
        }
        for (row in table.rows) {
            row.getOrNull(c)?.let { cell ->
                desired[c] = maxOf(desired[c], spansWidth(cell, density))
                floor[c] = maxOf(floor[c], unbreakableWidth(cell, density))
            }
        }
    }

    val avail = (availableDp - TABLE_CELL_PADDING * 2 * cols).coerceAtLeast(1f)
    val fitShares = fitColumnContentWidths(desired, floor, cols, avail)

    val crushed = cols >= 4 && (
        desired.sum() > avail * 1.45f ||
            (0 until cols).any { c ->
                desired[c] > TABLE_MIN_READABLE_COL_DP && fitShares[c] < TABLE_MIN_READABLE_COL_DP
            }
        )
    if (!crushed) {
        return TableLayoutSpec(
            widthsDp = fitShares.map { it + TABLE_CELL_PADDING * 2 },
            horizontalScroll = false,
        )
    }

    val scrollShares = FloatArray(cols) { c ->
        val minTarget = minOf(desired[c], TABLE_MIN_READABLE_COL_DP)
        maxOf(floor[c], desired[c].coerceAtMost(TABLE_MAX_SCROLL_COL_DP), minTarget)
            .coerceAtLeast(18f)
    }
    val sumScroll = scrollShares.sum()
    val finalShares = if (sumScroll < avail) {
        val k = avail / sumScroll.coerceAtLeast(1f)
        FloatArray(cols) { c -> scrollShares[c] * k }
    } else {
        scrollShares
    }
    return TableLayoutSpec(
        widthsDp = finalShares.map { it + TABLE_CELL_PADDING * 2 },
        horizontalScroll = true,
    )
}

/**
 * 按实际渲染宽度在固定可视宽内分配表格列宽：
 * 先满足公式/图片不可拆行的保底宽度，再按 `sqrt(desired)` 加权水淹分配，
 * 避免短列白白吃掉单行冗余宽度而把长描述列压成竖条。
 */
private fun fitColumnContentWidths(
    desired: FloatArray,
    floor: FloatArray,
    cols: Int,
    avail: Float,
): FloatArray {
    val sumDesired = desired.sum()
    val sumFloor = floor.sum()
    val shares = FloatArray(cols)
    when {
        sumDesired <= avail -> {
            val k = avail / sumDesired.coerceAtLeast(1f)
            for (c in 0 until cols) shares[c] = desired[c] * k
        }

        sumFloor > avail -> {
            for (c in 0 until cols) {
                shares[c] = if (sumFloor > 0f) avail * floor[c] / sumFloor else avail / cols
            }
        }

        else -> {
            var remaining = avail
            for (c in 0 until cols) {
                shares[c] = floor[c]
                remaining -= floor[c]
            }
            val active = (0 until cols).filterTo(mutableListOf()) { desired[it] > shares[it] }
            while (active.isNotEmpty() && remaining > 0.5f) {
                val weightSum = active.sumOf {
                    kotlin.math.sqrt(desired[it].coerceAtLeast(1f).toDouble())
                }.toFloat().coerceAtLeast(1f)
                var cappedAny = false
                val iter = active.iterator()
                while (iter.hasNext()) {
                    val c = iter.next()
                    val w = kotlin.math.sqrt(desired[c].coerceAtLeast(1f).toDouble()).toFloat()
                    val add = remaining * (w / weightSum)
                    if (shares[c] + add >= desired[c]) {
                        remaining -= (desired[c] - shares[c])
                        shares[c] = desired[c]
                        iter.remove()
                        cappedAny = true
                    }
                }
                if (!cappedAny) {
                    val finalWeightSum = active.sumOf {
                        kotlin.math.sqrt(desired[it].coerceAtLeast(1f).toDouble())
                    }.toFloat().coerceAtLeast(1f)
                    for (c in active) {
                        val w = kotlin.math.sqrt(desired[c].coerceAtLeast(1f).toDouble()).toFloat()
                        shares[c] += remaining * (w / finalWeightSum)
                    }
                    remaining = 0f
                }
            }
        }
    }
    return shares
}

/** 单元格内容的自然宽度（dp）：公式/图片用出图像素宽，其余按字宽估算。 */
private fun spansWidth(spans: List<RenderSpan>, density: Float): Float {
    var w = 0f
    for (span in spans) {
        w += when (span.type) {
            "latex_inline" -> span.math?.takeIf { it.width > 0 }?.let { it.width / density }
                ?: textWidth(span.text)

            "image" -> span.width.takeIf { it > 0 }?.let { it / density }
                ?: textWidth(span.text)

            else -> textWidth(span.text)
        }
    }
    return w
}

/** 折不了行的内容宽度（dp）：公式图与图片，列窄到这份宽度以下就会溢出。 */
private fun unbreakableWidth(spans: List<RenderSpan>, density: Float): Float {
    var w = 0f
    for (span in spans) {
        w += when (span.type) {
            "latex_inline" -> span.math?.takeIf { it.width > 0 }?.let { it.width / density } ?: 0f
            "image" -> span.width.takeIf { it > 0 }?.let { it / density } ?: 0f
            else -> 0f
        }
    }
    return w
}

/** 文本宽度估算（dp）：中文与全角标点一个字宽，西文半个字宽，空格更窄。 */
private fun textWidth(text: String): Float {
    var units = 0f
    for (ch in text) {
        units += when {
            ch == ' ' -> 0.28f
            ch.code > 0x2E80 -> 1.0f
            else -> 0.52f
        }
    }
    return units * tableCellFontSize.value
}

@Composable
private fun TableRow(
    cells: List<List<RenderSpan>>,
    align: List<String>,
    widths: List<Dp>,
    scrollable: Boolean,
    bodyStyle: TextStyle,
    colDividerColor: Color,
    rowBackground: Color,
    header: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(rowBackground),
    ) {
        widths.forEachIndexed { c, width ->
            val cell = cells.getOrNull(c).orEmpty()
            val separator = if (c == 0) Modifier else Modifier.drawWithContent {
                drawContent()
                val stroke = 0.6.dp.toPx()
                val x = if (layoutDirection == LayoutDirection.Ltr) stroke / 2 else size.width - stroke / 2
                drawLine(colDividerColor, Offset(x, 0f), Offset(x, size.height), stroke)
            }
            val cellWidthMod = if (scrollable) Modifier.width(width) else Modifier.weight(width.value)
            Box(
                modifier = cellWidthMod.fillMaxHeight().then(separator),
                contentAlignment = Alignment.CenterStart,
            ) {
                InlineSpans(
                    spans = cell,
                    style = bodyStyle.copy(
                        fontSize = if (header) 12.5.sp else tableCellFontSize,
                        lineHeight = if (header) 18.sp else 19.5.sp,
                        fontFamily = if (header) FontFamily.SansSerif else bodyStyle.fontFamily,
                        fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    caret = false,
                    textAlign = when (align.getOrNull(c)) {
                        "center" -> TextAlign.Center
                        "right" -> TextAlign.End
                        else -> TextAlign.Start
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = TABLE_CELL_PADDING.dp,
                            vertical = if (header) 8.dp else 7.dp,
                        ),
                )
            }
        }
    }
}

@Composable
private fun FootnoteDefBlock(block: RenderBlock, bodyStyle: TextStyle) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = "[${block.name}]",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 6.dp),
        )
        InlineSpans(
            spans = block.spans,
            style = bodyStyle.copy(fontSize = 13.sp),
            caret = false,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LinkPreviewCard(block: RenderBlock) {
    val uri = LocalUriHandler.current
    val controller = LocalBrowserController.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { runCatching { uri.openUri(block.url) } }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = block.title.ifBlank { block.url },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (block.description.isNotBlank()) {
            Text(
                text = block.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = block.url,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        // 两个打开方式：卡片整体点按仍是「系统浏览器」（旧行为不变），
        // 内置浏览器能选元素、能截图、能看布局，所以单独给一个入口。
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LinkChip("系统浏览器") { runCatching { uri.openUri(block.url) } }
            LinkChip("内置浏览器") {
                controller?.open(BrowserTarget.Url(block.title, block.url))
            }
        }
    }
}

/** 链接卡上的小按钮。 */
@Composable
private fun LinkChip(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface)
            .noRippleClickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun QuoteFrame(quote: Int, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(start = ((quote - 1).coerceAtLeast(0) * 14).dp),
    ) {
        Box(
            modifier = Modifier
                .padding(end = 10.dp)
                .width(3.dp)
                .fillMaxHeight()
                .heightIn(min = 20.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
        )
        Column(modifier = Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun AlertFrame(block: RenderBlock, content: @Composable () -> Unit) {
    val label = when (block.alert.lowercase()) {
        "note" -> "提示"
        "tip" -> "技巧"
        "important" -> "重要"
        "warning" -> "警告"
        "caution" -> "注意"
        else -> block.alert
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        Box(
            modifier = Modifier
                .padding(end = 10.dp)
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(bottom = 2.dp),
            )
            content()
        }
    }
}

// 行内 span

private const val INLINE_HIGHLIGHT = "inline-highlight"
private const val CODE_HIGHLIGHT = "code"
private const val MARK_HIGHLIGHT = "mark"
private val inlineCodeSize = 13.sp

@Composable
private fun InlineSpans(
    spans: List<RenderSpan>,
    style: TextStyle,
    caret: Boolean,
    textAlign: TextAlign? = null,
    modifier: Modifier = Modifier,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val codeFg = MaterialTheme.colorScheme.onSurfaceVariant
    val caretColor = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current.density

    val rendered = remember(spans, caret, linkColor, codeFg, caretColor, density) {
        val inline = linkedMapOf<String, InlineTextContent>()
        var seq = 0
        val annotated = buildAnnotatedString {
            spans.forEach { span ->
                when (span.type) {
                    "text" -> appendRichText(
                        raw = span.text,
                        bold = span.bold,
                        italic = span.italic,
                        strike = span.strike,
                        url = span.url,
                        linkColor = linkColor,
                        codeFg = codeFg,
                    )

                    "code" -> {
                        val start = length
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = inlineCodeSize, color = codeFg)) {
                            append(span.text)
                        }
                        addStringAnnotation(INLINE_HIGHLIGHT, CODE_HIGHLIGHT, start, length)
                    }

                    "softbreak", "hardbreak" -> append('\n')

                    "image" -> {
                        val id = "img${seq++}"
                        appendInlineContent(id, span.text.ifBlank { "图片" })
                        inline[id] = InlineTextContent(
                            placeholder = Placeholder(
                                width = span.inlineWidthSp(density),
                                height = span.inlineHeightSp(density),
                                placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                            ),
                        ) {
                            InlineMedia(src = span.src, alt = span.text)
                        }
                    }

                    "audio" -> {
                        val label = span.text.ifBlank { span.src.substringAfterLast('/') }
                        if (span.urlOrSrc().isNotBlank()) {
                            withLink(link(span.urlOrSrc(), linkColor)) { append("♪ $label") }
                        } else {
                            append("♪ $label")
                        }
                    }

                    "latex_inline" -> {
                        val math = span.math
                        val id = "math${seq++}"
                        // 流式尾巴上的公式先按源码展示：RaTeX 每来一个增量就重渲染
                        // PNG 会闪、也会把主线程压满；流结束后再换正式渲染。
                        if (math != null && !caret) {
                            appendInlineContent(id, span.text)
                            inline[id] = InlineTextContent(
                                placeholder = Placeholder(
                                    width = math.inlineWidthSp(density),
                                    height = math.inlineHeightSp(density),
                                    placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                                ),
                            ) {
                                MathImageSpan(math, span.text)
                            }
                        } else {
                            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = codeFg)) {
                                append("\$" + span.text + "\$")
                            }
                        }
                    }

                    "footnote_ref" -> withStyle(
                        SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 10.sp),
                    ) {
                        append("[${span.name}]")
                    }

                    else -> append(span.text)
                }
            }
            if (caret) {
                withStyle(SpanStyle(color = caretColor)) { append("▍") }
            }
        }
        annotated to inline
    }

    val highlights = remember(rendered.first) {
        rendered.first.getStringAnnotations(INLINE_HIGHLIGHT, 0, rendered.first.length)
            .filter { it.start < it.end }
    }
    var textLayout by remember(rendered.first) { mutableStateOf<TextLayoutResult?>(null) }
    val highlightModifier = if (highlights.isEmpty()) Modifier else {
        val measurer = rememberTextMeasurer()
        val metricStyle = style.copy(lineHeight = TextUnit.Unspecified)
        val proseMetrics = measurer.measure("Hg", style = metricStyle)
        val codeMetrics = measurer.measure(
            "Hg",
            style = metricStyle.copy(fontFamily = FontFamily.Monospace, fontSize = inlineCodeSize),
        )
        Modifier.drawBehind {
            val layout = textLayout ?: return@drawBehind
            highlights.forEach { range ->
                val metrics = if (range.item == CODE_HIGHLIGHT) codeMetrics else proseMetrics
                val firstLine = layout.getLineForOffset(range.start)
                val lastLine = layout.getLineForOffset(range.end - 1)
                for (line in firstLine..lastLine) {
                    val start = maxOf(range.start, layout.getLineStart(line))
                    val end = minOf(range.end, layout.getLineEnd(line, visibleEnd = true))
                    if (start >= end) continue
                    val top = layout.getLineBaseline(line) - metrics.firstBaseline
                    // 选区保留换行及双向文本的位置，纵向只画字体高度。
                    clipRect(top = top, bottom = top + metrics.size.height) {
                        drawPath(layout.getPathForRange(start, end), codeBg)
                    }
                }
            }
        }
    }

    Text(
        text = rendered.first,
        inlineContent = rendered.second,
        style = style,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = textAlign,
        modifier = modifier.then(highlightModifier),
        onTextLayout = { if (highlights.isNotEmpty()) textLayout = it },
    )
}

/**
 * 行内 HTML 白名单（旧渲染器支持的那一组）。
 *
 * OpenRender 的设计是「HTML 是数据不是标记」，默认按字面文本输出。模型仍会
 * 输出 `<b>`/`<br>` 这类白名单标签，所以在这一层做一次剥壳 + 样式转换，
 * 与旧行为保持一致；未知标签直接剥掉、保留正文。
 */
private fun AnnotatedString.Builder.appendRichText(
    raw: String,
    bold: Boolean,
    italic: Boolean,
    strike: Boolean,
    url: String,
    linkColor: Color,
    codeFg: Color,
) {
    var isBold = bold
    var isItalic = italic
    var isStrike = strike
    var isUnderline = false
    var isSub = false
    var isSup = false
    var isMark = false
    var isCode = false

    fun markHighlight(start: Int) {
        if (isMark) addStringAnnotation(INLINE_HIGHLIGHT, MARK_HIGHLIGHT, start, length)
    }

    fun styleNow(): SpanStyle {
        val deco = buildList {
            if (isStrike) add(TextDecoration.LineThrough)
            if (isUnderline) add(TextDecoration.Underline)
        }.takeIf { it.isNotEmpty() }?.let { TextDecoration.combine(it) }
        return SpanStyle(
            fontWeight = if (isBold) FontWeight.Bold else null,
            fontStyle = if (isItalic) FontStyle.Italic else null,
            textDecoration = deco,
            baselineShift = when {
                isSup -> BaselineShift.Superscript
                isSub -> BaselineShift.Subscript
                else -> null
            },
            fontFamily = if (isCode) FontFamily.Monospace else null,
            color = if (isCode) codeFg else Color.Unspecified,
        )
    }

    fun flush(buf: StringBuilder) {
        if (buf.isEmpty()) return
        val text = buf.toString()
        buf.clear()
        val start = length
        withStyle(styleNow()) {
            if (url.isNotBlank()) withLink(link(url, linkColor)) { append(text) } else append(text)
        }
        markHighlight(start)
    }

    val buffer = StringBuilder()
    var i = 0
    while (i < raw.length) {
        val c = raw[i]

        // 裸 URL 自动成链（剥离末尾中文或英文句末标点，避免吞入标点符号）
        if (raw.startsWith("http://", i) || raw.startsWith("https://", i)) {
            flush(buffer)
            var end = i
            while (end < raw.length && !raw[end].isWhitespace() &&
                raw[end] != ')' && raw[end] != '）'
            ) end++
            while (end > i && raw[end - 1] in ",.;:!?，。；：！？、】」》\"'`") {
                end--
            }
            val urlText = raw.substring(i, end)
            val start = length
            withStyle(styleNow()) {
                withLink(link(urlText, linkColor)) { append(urlText) }
            }
            markHighlight(start)
            i = end
            continue
        }

        // 非标准但模型常用的下标 ~x~ / 上标 ^x^（~~ 删除线由引擎转成 strike span）
        if ((c == '~' || c == '^') && i + 1 < raw.length && raw[i + 1] != c) {
            val closer = raw.indexOf(c, i + 1)
            val newline = raw.indexOf('\n', i + 1)
            if (closer > i + 1 && (newline < 0 || closer < newline)) {
                flush(buffer)
                val inner = raw.substring(i + 1, closer)
                val start = length
                withStyle(
                    styleNow().copy(
                        baselineShift = if (c == '~') BaselineShift.Subscript else BaselineShift.Superscript,
                        fontSize = 11.sp,
                    ),
                ) { append(inner) }
                markHighlight(start)
                i = closer + 1
                continue
            }
        }

        if (c == '<' && i + 1 < raw.length && (raw[i + 1].isLetter() || raw[i + 1] == '/')) {
            val end = raw.indexOf('>', i + 1)
            if (end > i) {
                val tag = raw.substring(i + 1, end).trim().lowercase()
                val closing = tag.startsWith("/")
                val name = tag.removePrefix("/").substringBefore(' ').removeSuffix("/")
                if (name in htmlTags) {
                    flush(buffer)
                    when (name) {
                        "br" -> append('\n')
                        "b", "strong" -> isBold = !closing
                        "i", "em" -> isItalic = !closing
                        "s", "del", "strike" -> isStrike = !closing
                        "u", "ins" -> isUnderline = !closing
                        "sub" -> isSub = !closing
                        "sup" -> isSup = !closing
                        "mark" -> isMark = !closing
                        "code" -> isCode = !closing
                    }
                    i = end + 1
                    continue
                }
                // 未知标签剥壳留字
                flush(buffer)
                i = end + 1
                continue
            }
        }
        buffer.append(c)
        i++
    }
    flush(buffer)
}

private val htmlTags = setOf(
    "br", "b", "strong", "i", "em", "s", "del", "strike",
    "u", "ins", "sub", "sup", "mark", "code",
)

private fun link(url: String, color: Color): LinkAnnotation.Url =
    LinkAnnotation.Url(
        url = url,
        styles = TextLinkStyles(
            style = SpanStyle(color = color, textDecoration = TextDecoration.Underline),
        ),
    )

@Composable
private fun InlineMedia(src: String, alt: String) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val root = LocalAttachmentRoot.current
    var bitmap by remember(src, root) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(src, ctx, root) { bitmap = loadMediaBitmap(ctx, src, root) }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = alt,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = alt.ifBlank { "图" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// 公式

@Composable
private fun MathBlock(math: RenderMathImage?, tex: String, degrade: Boolean) {
    if (degrade || math == null || math.mime != "image/png") {
        if (tex.isBlank()) return
        Text(
            text = "\$\$" + tex + "\$\$",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
        return
    }
    val density = LocalDensity.current.density
    val bitmap = remember(math) { decodeMath(math) }
    val height = (math.height / density).dp
    if (bitmap != null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = tex,
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(
                    MaterialTheme.colorScheme.onSurface,
                    BlendMode.SrcIn,
                ),
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            )
        }
    } else {
        Text(
            text = "\$\$" + tex + "\$\$",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MathImageSpan(math: RenderMathImage, alt: String) {
    val bitmap = remember(math) { decodeMath(math) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = alt,
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(
                MaterialTheme.colorScheme.onSurface,
                BlendMode.SrcIn,
            ),
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Text(
            text = alt,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun decodeMath(math: RenderMathImage): android.graphics.Bitmap? =
    runCatching {
        BitmapFactory.decodeByteArray(math.data, 0, math.data.size)
    }.getOrNull()

private fun RenderMathImage.inlineWidthSp(density: Float): androidx.compose.ui.unit.TextUnit {
    val px = if (width > 0) width else (height.coerceAtLeast(1) * 2)
    return (px / density.coerceAtLeast(0.1f)).sp
}

private fun RenderMathImage.inlineHeightSp(density: Float): androidx.compose.ui.unit.TextUnit {
    val px = if (height > 0) height else 20
    return (px / density.coerceAtLeast(0.1f)).sp
}

// 小工具

private fun RenderBlock.hasInlineText(): Boolean =
    type == "paragraph" || type == "heading" || type == "list_item" || type == "footnote_def"

private fun RenderSpan.inlineWidthSp(density: Float): androidx.compose.ui.unit.TextUnit {
    val h = if (height > 0) height else 20
    val w = if (width > 0) width else h
    return (w / density.coerceAtLeast(0.1f)).sp
}

private fun RenderSpan.inlineHeightSp(density: Float): androidx.compose.ui.unit.TextUnit {
    val h = if (height > 0) height else 20
    return (h / density.coerceAtLeast(0.1f)).sp
}

private fun RenderSpan.urlOrSrc(): String = src.ifBlank { url }

private fun firstNonBlankLine(s: String): String? =
    s.lines().firstOrNull { it.isNotBlank() }?.trim()

