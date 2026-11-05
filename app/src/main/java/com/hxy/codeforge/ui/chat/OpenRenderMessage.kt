package com.hxy.codeforge.ui.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
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
import com.hxy.codeforge.ui.browser.LocalBrowserController
import com.hxy.codeforge.ui.browser.mergeHtmlBlocks
import com.hxy.codeforge.ui.browser.looksLikeWholeHtmlDocument
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
@Composable
fun OpenRenderContent(
    source: String,
    streaming: Boolean,
    modifier: Modifier = Modifier,
    bodyStyle: TextStyle = SerifBody,
) {
    val density = LocalDensity.current.density
    // cacheDir 给公式渲染落地 Unicode 回退字体用（Android 无系统字体）
    val cacheDir = LocalContext.current.cacheDir.absolutePath
    val doc = remember(density, cacheDir) { OpenRenderDocument(density, cacheDir = cacheDir) }
    DisposableEffect(doc) { onDispose { doc.close() } }

    // 整页 HTML 文档：**不经过 markdown 引擎**，直接挂成可交互页面。
    //
    // 为什么必须绕开引擎：CommonMark 的 HTML 块在空行处结束，之后的内容
    // 重新按 markdown 解析 —— 缩进 ≥4 空格的 HTML 行就变成 code_block，
    // 正文被从中间打断，缩进也被吃掉 4 格。碎片化之后拼不回原文。
    //
    // 判定必须在**喂引擎之前**做（见 looksLikeWholeHtmlDocument 的说明）。
    // 流式期间不做：文档还没写完，认不准，且提前挂 WebView 会反复重建。
    if (!streaming && looksLikeWholeHtmlDocument(source)) {
        HtmlBlockView(html = source, title = "网页", modifier = modifier)
        return
    }

    if (!OpenRenderNative.available) {
        Text(
            text = source,
            style = bodyStyle.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = modifier,
        )
        return
    }

    val blocks = remember(source, streaming, doc) { doc.sync(source, streaming) }
    if (doc.failed) {
        Text(
            text = source,
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

// ---------------------------------------------------------------- 块渲染

@Composable
private fun RenderBlockView(
    block: RenderBlock,
    bodyStyle: TextStyle,
    streaming: Boolean,
    caret: Boolean,
    soleBlock: Boolean,
) {
    val inner: @Composable () -> Unit = {
        when (block.type) {
            "paragraph" -> ParagraphBlock(block, bodyStyle, caret)
            "heading" -> HeadingBlock(block, bodyStyle, caret)
            "code_block" -> CodeFenceBlock(block, streaming, soleBlock)
            "list_item" -> ListItemBlock(block, bodyStyle, caret)
            "table" -> TableBlock(block.table, bodyStyle)
            "rule" -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            "footnote_def" -> FootnoteDefBlock(block, bodyStyle)
            "latex_block" -> MathBlock(block.math, block.text, degrade = caret)
            "audio" -> MessageAudioBlock(block.url)
            "link_preview" -> LinkPreviewCard(block)
            "html_raw" -> HtmlBlockView(html = block.text, title = "网页")
            else -> InlineSpans(
                spans = block.spans,
                style = bodyStyle,
                caret = caret,
            )
        }
    }

    when {
        block.alert.isNotEmpty() -> AlertFrame(block) { inner() }
        block.quote > 0 -> QuoteFrame(block.quote) { inner() }
        else -> inner()
    }
}

@Composable
private fun ParagraphBlock(
    block: RenderBlock,
    bodyStyle: TextStyle,
    caret: Boolean,
) {
    // 独占一段的媒体按块级渲染：图片可点开全屏、音频有真实播放器、裸 SVG 走矢量绘制
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

            single.type == "text" && single.text.trimStart().startsWith("<svg") -> {
                if (single.text.contains("</svg>")) {
                    MessageSvgBlock(single.text)
                } else {
                    StreamingTailBlock(single.text)
                }
                return
            }
        }
    }

    InlineSpans(
        spans = block.spans,
        style = bodyStyle,
        caret = caret,
    )
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
            if (openTail) {
                StreamingTailBlock(block.text)
            } else if (block.text.isNotBlank()) {
                HtmlBlockView(html = block.text, title = "网页")
            } else {
                StreamingTailBlock(block.text)
            }
        }

        "svg" -> if (openTail) StreamingTailBlock(block.text) else MessageSvgBlock(block.text)

        "image", "img", "图片" -> {
            val url = firstNonBlankLine(block.text)
            if (openTail || url == null) StreamingTailBlock(block.text) else MessageImageBlock(url, "")
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

        else -> FencedCode(lang, block.text)
    }
}

@Composable
private fun FencedCode(lang: String, code: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (lang.isNotEmpty()) {
            Text(
                text = lang,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = code,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                lineHeight = 19.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
    }
}

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

@Composable
private fun TableBlock(table: RenderTable?, bodyStyle: TextStyle) {
    if (table == null || (table.header.isEmpty() && table.rows.isEmpty())) return
    val cols = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
    if (cols == 0) return
    val density = LocalDensity.current.density

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val widths = columnWidths(table, cols, maxWidth, density)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(8.dp),
        ) {
            TableRow(table.header, table.align, widths, bodyStyle, header = true)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            table.rows.forEach { row ->
                TableRow(row, table.align, widths, bodyStyle, header = false)
            }
        }
    }
}

/** 表格单元格字号；列宽换算要用同一个值，否则算出来的比例和实际排版对不上。 */
private val tableCellFontSize = 13.sp

/** 每格左右各 5dp 内边距。 */
private const val TABLE_CELL_PADDING = 5f

/**
 * 按**实际渲染宽度**分配列宽。
 *
 * 早先按「字符个数」加权，且公式一律算 6 个字 —— 公式越宽越吃亏：
 * `1+nx^{2n}` 出图 60dp 却只折算成 6 个字，而一串 16 个中文能折算成 16，
 * 列宽比例整个失真，表头会被挤到换行（「分母」和公式被拆成两行）。
 *
 * 现在按 dp 算：公式与图片用出图像素宽除以屏幕密度，中文按一个字宽、
 * 西文按半个字宽。分列宽时先给每列留**保底**（公式图折不烂，只能占这么宽），
 * 余量再按「期望宽 - 保底宽」分配；全都不够时按保底比例挤。
 */
private fun columnWidths(
    table: RenderTable,
    cols: Int,
    available: Dp,
    density: Float,
): List<Dp> {
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

    // 卡片内边距 8dp×2，每格再扣左右各 5dp，剩下的才是文字可用宽
    val avail = (available.value - 16f - TABLE_CELL_PADDING * 2 * cols).coerceAtLeast(1f)
    val sumDesired = desired.sum()
    val sumFloor = floor.sum()

    val shares = FloatArray(cols)
    when {
        sumDesired <= avail -> {
            // 内容装得下：按比例放大把整行填满，避免右侧留白
            val k = avail / sumDesired.coerceAtLeast(1f)
            for (c in 0 until cols) shares[c] = desired[c] * k
        }

        sumFloor > avail -> {
            // 连保底都放不下：按保底比例挤，宁可折行也不让公式图溢出
            for (c in 0 until cols) {
                shares[c] = if (sumFloor > 0f) avail * floor[c] / sumFloor else avail / cols
            }
        }

        else -> {
            // 灌水分配：每列先拿到保底（公式图折不烂），够用的列拿满自己的
            // 期望宽就停手，剩下的空间只在仍缺空间的列之间均分。
            // 关键在于「按期望宽分」而不是「按期望宽减保底分」——
            // 后者会把本该给宽公式列的余量摊给长文本列，表头照样被挤到换行。
            var remaining = avail
            var active = (0 until cols).toMutableList()
            while (active.isNotEmpty() && remaining > 0f) {
                val share = remaining / active.size
                val full = active.filter { desired[it] <= share }
                if (full.isEmpty()) {
                    for (c in active) shares[c] = share
                    remaining = 0f
                    active.clear()
                } else {
                    for (c in full) {
                        shares[c] = desired[c]
                        remaining -= desired[c]
                    }
                    active.removeAll(full.toSet())
                }
            }
        }
    }
    return shares.map { (it + TABLE_CELL_PADDING * 2).dp }
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
    bodyStyle: TextStyle,
    header: Boolean,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        widths.forEachIndexed { c, width ->
            val cell = cells.getOrNull(c).orEmpty()
            InlineSpans(
                spans = cell,
                style = bodyStyle.copy(
                    fontSize = tableCellFontSize,
                    lineHeight = 19.sp,
                    fontWeight = if (header) FontWeight.Medium else FontWeight.Normal,
                ),
                caret = false,
                textAlign = when (align.getOrNull(c)) {
                    "center" -> TextAlign.Center
                    "right" -> TextAlign.End
                    else -> TextAlign.Start
                },
                modifier = Modifier
                    .width(width)
                    .padding(horizontal = TABLE_CELL_PADDING.dp, vertical = 3.dp),
            )
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

// ---------------------------------------------------------------- 行内 span

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

    val rendered = remember(spans, caret, linkColor, codeBg, codeFg, caretColor, density) {
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
                        codeBg = codeBg,
                        codeFg = codeFg,
                    )

                    "code" -> withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            background = codeBg,
                            color = codeFg,
                        ),
                    ) {
                        append(span.text)
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

    Text(
        text = rendered.first,
        inlineContent = rendered.second,
        style = style,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = textAlign,
        modifier = modifier,
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
    codeBg: Color,
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
            background = if (isMark) codeBg else Color.Unspecified,
            color = if (isCode) codeFg else Color.Unspecified,
        )
    }

    fun flush(buf: StringBuilder) {
        if (buf.isEmpty()) return
        val text = buf.toString()
        buf.clear()
        withStyle(styleNow()) {
            if (url.isNotBlank()) withLink(link(url, linkColor)) { append(text) } else append(text)
        }
    }

    val buffer = StringBuilder()
    var i = 0
    while (i < raw.length) {
        val c = raw[i]

        // 裸 URL 自动成链：旧渲染器行为，OpenRender 本身不做 autolink
        if (raw.startsWith("http://", i) || raw.startsWith("https://", i)) {
            flush(buffer)
            var end = i
            while (end < raw.length && !raw[end].isWhitespace() &&
                raw[end] != ')' && raw[end] != '）'
            ) end++
            val urlText = raw.substring(i, end)
            withStyle(styleNow()) {
                withLink(link(urlText, linkColor)) { append(urlText) }
            }
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
                withStyle(
                    styleNow().copy(
                        baselineShift = if (c == '~') BaselineShift.Subscript else BaselineShift.Superscript,
                        fontSize = 11.sp,
                    ),
                ) { append(inner) }
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

// ---------------------------------------------------------------- 公式

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

// ---------------------------------------------------------------- 小工具

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

/** 视频扩展名判定（忽略 query/fragment）；引擎没有 video 块，宿主据此升级媒体件。 */
private fun isVideoPath(url: String): Boolean {
    val clean = url.substringBefore('?').substringBefore('#').lowercase()
    return clean.endsWith(".mp4") || clean.endsWith(".webm") || clean.endsWith(".mkv") ||
        clean.endsWith(".mov") || clean.endsWith(".m4v") || clean.endsWith(".3gp")
}
