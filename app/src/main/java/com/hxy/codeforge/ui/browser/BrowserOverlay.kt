package com.hxy.codeforge.ui.browser

import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hxy.codeforge.ui.common.Glyph
import com.hxy.codeforge.ui.common.GlyphIcon
import com.hxy.codeforge.ui.common.noRippleClickable
import com.hxy.codeforge.ui.theme.Clay

/**
 * 全屏内置浏览器。
 *
 * 顶栏从左到右：返回 · 刷新 · 标题/地址 · **选择元素** · **全屏** · 三点菜单。
 *
 * 「选择元素」的图标是一支**指向方框的鼠标**（DevTools 的 inspect 图标）；
 * 「全屏」在**最顶栏**上，不藏进菜单 —— 全屏是高频操作，藏两层就没人用了。
 * 三点菜单里再放一份，作为发现性入口。
 */
@Composable
fun BrowserOverlay(
    target: BrowserTarget,
    onClose: () -> Unit,
    onElementAdded: (PickedElement) -> Unit,
    onOpenExternal: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val onElementAddedState = rememberUpdatedState(onElementAdded)

    var fullscreen by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var currentUrl by remember { mutableStateOf("") }
    var pageTitle by remember { mutableStateOf("") }
    var layoutInfo by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    val webViewRef = remember { arrayOfNulls<WebView>(1) }

    fun toast(msg: String) {
        notice = msg
    }

    // 临时提示自动消失 —— 不然会一直挂在屏幕底部挡内容
    LaunchedEffect(notice) {
        if (notice != null) {
            kotlinx.coroutines.delay(2600)
            notice = null
        }
    }

    fun startPick(wv: WebView?) {
        val w = wv ?: return
        picking = true
        w.evaluateJavascript("window.__cf && window.__cf.startPick && window.__cf.startPick();", null)
        toast("点页面上的元素即可加入对话")
    }

    fun stopPick(wv: WebView?) {
        picking = false
        wv?.evaluateJavascript("window.__cf && window.__cf.stopPick && window.__cf.stopPick();", null)
    }

    fun openExternal() {
        val url = currentUrl
        if (url.isBlank()) {
            toast("当前页面没有可打开的地址")
            return
        }
        onOpenExternal(url)
    }

    fun snapshot(wv: WebView?) {
        val w = wv ?: return
        val dir = java.io.File(context.cacheDir, "codeforge-screenshots")
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val path = BrowserWeb.captureToFile(w, dir, "page-$stamp.png")
        toast(if (path != null) "截图已存：$path" else "截图失败")
    }

    fun showLayout(wv: WebView?) {
        val w = wv ?: return
        w.evaluateJavascript("window.__cf && window.__cf.list ? window.__cf.list() : '[]'") { raw ->
            val decoded = runCatching {
                org.json.JSONTokener(raw ?: "[]").nextValue() as? String
            }.getOrNull() ?: raw.orEmpty()
            layoutInfo = decoded
        }
    }

    BackHandler(enabled = true) {
        val w = webViewRef[0]
        if (picking) {
            stopPick(w)
        } else if (w != null && w.canGoBack()) {
            w.goBack()
        } else {
            onClose()
        }
    }

    val panelBg = MaterialTheme.colorScheme.background.let {
        if (it.alpha < 1f) MaterialTheme.colorScheme.surface.copy(alpha = 1f) else it
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(panelBg),
    ) {
        if (!fullscreen) {
            BrowserTopBar(
                title = pageTitle,
                url = currentUrl,
                picking = picking,
                menuOpen = menuOpen,
                onMenuOpenChange = { menuOpen = it },
                onBack = {
                    val w = webViewRef[0]
                    if (w != null && w.canGoBack()) w.goBack() else onClose()
                },
                onRefresh = { webViewRef[0]?.reload() },
                onPick = {
                    if (picking) stopPick(webViewRef[0]) else startPick(webViewRef[0])
                },
                onFullscreen = { fullscreen = true },
                onExternal = { openExternal() },
                onCopyLink = {
                    val u = currentUrl
                    if (u.isNotBlank()) {
                        runCatching {
                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("url", u))
                        }
                        toast("链接已复制")
                    }
                },
                onSnapshot = { snapshot(webViewRef[0]) },
                onLayout = { showLayout(webViewRef[0]) },
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewRef[0] = this
                        BrowserWeb.configure(
                            webView = this,
                            interactive = true,
                            zoomAllowed = true,
                            listener = { url, title ->
                                currentUrl = url
                                pageTitle = title
                            },
                        )
                        PageBridge.attach(
                            webView = this,
                            allowBridge = true,
                        ) { ev ->
                            when (ev.kind) {
                                BridgeEvent.KIND_ELEMENT -> {
                                    PageBridge.parseElement(ev.payload)?.let { el ->
                                        onElementAddedState.value(el)
                                    }
                                }
                            }
                        }
                        when (target) {
                            is BrowserTarget.Html -> BrowserWeb.loadHtml(this, target.html)
                            is BrowserTarget.Url -> this.loadUrl(target.url)
                        }
                    }
                },
                onRelease = { wv ->
                    runCatching { wv.stopLoading() }
                    runCatching { wv.destroy() }
                    webViewRef[0] = null
                },
            )

            // 全屏时顶栏没了，给一个悬浮的退出按钮 —— 否则退不出全屏。
            if (fullscreen) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .size(34.dp)
                        .clip(RoundedCornerShape(17.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.86f))
                        .noRippleClickable { fullscreen = false },
                    contentAlignment = Alignment.Center,
                ) {
                    GlyphIcon(Glyph.FullscreenExit, 17.dp, Clay)
                }
            }

            if (picking) {
                Text(
                    text = "选择元素中 —— 点页面上的元素，或按返回退出",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.9f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            notice?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 18.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.92f))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .noRippleClickable { notice = null },
                )
            }
        }
    }

    layoutInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { layoutInfo = null },
            title = { Text("页面布局信息") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = info,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { layoutInfo = null }) { Text("关闭") }
            },
        )
    }
}

/**
 * 浏览器顶栏。
 *
 * 全屏按钮在最顶栏上，不藏进三点菜单 —— 高频操作不该藏两层。
 */
@Composable
private fun BrowserTopBar(
    title: String,
    url: String,
    picking: Boolean,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onPick: () -> Unit,
    onFullscreen: () -> Unit,
    onExternal: () -> Unit,
    onCopyLink: () -> Unit,
    onSnapshot: () -> Unit,
    onLayout: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarIcon(Glyph.Back, "返回", onBack)
        BarIcon(Glyph.Retry, "刷新", onRefresh)

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 6.dp),
        ) {
            Text(
                text = title.ifBlank { "网页" },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (url.isNotBlank()) {
                Text(
                    text = url,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 选择元素：鼠标指向方框
        BarIcon(
            Glyph.Inspect,
            "选择元素",
            onPick,
            active = picking,
        )
        // 全屏：在最顶栏
        BarIcon(Glyph.Fullscreen, "全屏", onFullscreen)

        Box {
            BarIcon(Glyph.MenuDots, "更多", { onMenuOpenChange(true) })
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { onMenuOpenChange(false) },
            ) {
                DropdownMenuItem(
                    text = { Text(if (picking) "停止选择元素" else "选择元素") },
                    onClick = {
                        onMenuOpenChange(false)
                        onPick()
                    },
                )
                DropdownMenuItem(
                    text = { Text("全屏") },
                    onClick = {
                        onMenuOpenChange(false)
                        onFullscreen()
                    },
                )
                DropdownMenuItem(
                    text = { Text("用系统浏览器打开") },
                    onClick = {
                        onMenuOpenChange(false)
                        onExternal()
                    },
                )
                DropdownMenuItem(
                    text = { Text("复制链接") },
                    onClick = {
                        onMenuOpenChange(false)
                        onCopyLink()
                    },
                )
                DropdownMenuItem(
                    text = { Text("页面快照（截图）") },
                    onClick = {
                        onMenuOpenChange(false)
                        onSnapshot()
                    },
                )
                DropdownMenuItem(
                    text = { Text("布局信息") },
                    onClick = {
                        onMenuOpenChange(false)
                        onLayout()
                    },
                )
            }
        }
    }
}

/** 顶栏图标按钮。 */
@Composable
private fun BarIcon(
    glyph: Glyph,
    label: String,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (active) Clay.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent,
            )
            .noRippleClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, 19.dp, if (active) Clay else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
