package com.hxy.codeforge.ui.browser

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hxy.codeforge.ui.common.Glyph
import com.hxy.codeforge.ui.common.GlyphIcon
import com.hxy.codeforge.ui.common.noRippleClickable
import com.hxy.codeforge.ui.theme.Clay

/**
 * 对话流里的内嵌 HTML 块。
 *
 * AI 用 ```html 围栏给出整页文档（题目页、解析展开、可点开的图表），
 * 这里直接把它挂成可交互 WebView —— 按钮能点，答案能展开，不需要跳出对话。
 *
 * 高度按内容实测：页面报多少就多高，但夹在 [MIN_H] 与 [MAX_H] 之间，
 * 免得一个 3000px 长的页面把整条消息流撑爆。
 */
@Composable
fun HtmlBlockView(
    html: String,
    modifier: Modifier = Modifier,
    title: String = "网页",
    onOpenInApp: ((BrowserTarget) -> Unit)? = null,
    onOpenExternal: ((String) -> Unit)? = null,
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val controller = LocalBrowserController.current

    var heightPx by remember(html) { mutableIntStateOf(0) }
    val onHeight by rememberUpdatedState { h: Int -> heightPx = h }

    val shape = RoundedCornerShape(12.dp)
    val border = 1.dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(border, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        // 工具条：标题 + 两个打开方式
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphIcon(Glyph.CodeBrackets, 13.dp, Clay)
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .weight(1f),
            )
            ToolChip("打开") {
                val t = BrowserTarget.Html(title, html)
                if (onOpenInApp != null) onOpenInApp(t) else controller?.open(t)
            }
            ToolChip("系统浏览器") {
                if (onOpenExternal != null) {
                    onOpenExternal(html)
                } else {
                    openHtmlInSystemBrowser(context, html)
                }
            }
        }

        // 页面报的是 CSS px；要按密度换算成物理像素再夹取。
        // Dp * Float 得到的还是 Dp，所以必须走 Density.toPx()。
        val minPx = with(density) { MIN_H.toPx() }.toInt()
        val maxPx = with(density) { MAX_H.toPx() }.toInt()
        val fallbackPx = with(density) { DEFAULT_H.toPx() }.toInt()
        val contentHeight = if (heightPx > 0) {
            heightPx.coerceIn(minPx, maxPx)
        } else {
            fallbackPx
        }

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(density) { contentHeight.toDp() })
                .padding(horizontal = 2.dp),
            factory = { ctx ->
                WebView(ctx).apply {
                    BrowserWeb.configure(
                        webView = this,
                        interactive = true,
                        zoomAllowed = false,
                    )
                    addJavascriptInterface(
                        PageBridge.AndroidBridge { ev ->
                            if (ev.kind == "size") onHeight(ev.payload.toIntOrNull() ?: 0)
                        },
                        "__cfAndroid",
                    )
                }
            },
            update = { wv ->
                val tagKey = "cf-html-${html.hashCode()}"
                if (wv.tag != tagKey) {
                    wv.tag = tagKey
                    BrowserWeb.loadHtml(wv, html)
                }
            },
            onRelease = { wv ->
                runCatching { wv.stopLoading() }
                runCatching { wv.destroy() }
            },
        )
    }
}

/**
 * 把一份 HTML 交给系统浏览器打开。
 *
 * **必须走 FileProvider**：直接 `Uri.fromFile` 在 API 24+ 会抛
 * `FileUriExposedException`（StrictMode 禁止把 file:// 暴露给别的应用）。
 * 落地目录 `cache/pages/` 已在 `res/xml/file_paths.xml` 里登记。
 */
private fun openHtmlInSystemBrowser(context: android.content.Context, html: String) {
    runCatching {
        val dir = java.io.File(context.cacheDir, "pages")
        if (!dir.exists()) dir.mkdirs()
        val file = java.io.File(dir, "codeforge-page-${html.hashCode().toUInt().toString(16)}.html")
        file.writeText(html, Charsets.UTF_8)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file,
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

/**
 * 工具条上的小文字按钮。
 *
 * 只有文字，不带底色胶囊 —— 工具条本身已有浅色底，再套一层胶囊会显得套娃。
 */
@Composable
private fun ToolChip(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = Clay,
        modifier = Modifier
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .noRippleClickable(onClick = onClick),
    )
}

/** 内容太矮不好看，给个下限。 */
private val MIN_H = 140.dp

/** 上限：再高就内部滚动，不把消息流撑开。 */
private val MAX_H = 520.dp

/** 还没量到高度时的兜底。 */
private val DEFAULT_H = 260.dp
