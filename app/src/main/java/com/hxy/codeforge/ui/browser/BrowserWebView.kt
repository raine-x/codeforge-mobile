package com.hxy.codeforge.ui.browser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 内置浏览器的 WebView 工具层。
 *
 * 对话流里的内嵌 HTML 块和全屏浏览器共用同一套配置 —— 差别只有
 * 「要不要缩放」「要不要给用户看地址栏」这类呈现层的东西。
 */
object BrowserWeb {

    private const val TAG = "CodeForgeWeb"

    /** 伪源。用它当 baseUrl，页面里的相对路径有地方落地，且不是 file://。 */
    const val BASE_URL = "https://codeforge.local/"

    /**
     * 页面加载完成 / 地址变化的回调。
     */
    fun interface PageListener {
        fun onPage(url: String, title: String)
    }

    /**
     * 统一配置。
     *
     * @param interactive 是否允许用户交互（内嵌块里的网页照样能点按钮，
     *        这是本功能的核心 —— AI 出的题要能点开看答案）。
     * @param zoomAllowed 全屏浏览器允许双指缩放；内嵌块不允许（会跟消息流手势打架）。
     */
    fun configure(
        webView: WebView,
        interactive: Boolean = true,
        zoomAllowed: Boolean = false,
        listener: PageListener? = null,
        onConsole: ((String) -> Unit)? = null,
    ) {
        val s: WebSettings = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        // 让页面按 CSS px 布局，再由 WebView 缩放到设备像素 ——
        // 这是「同一份 HTML 在手机竖屏和平板横屏都正常」的前提。
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportZoom(zoomAllowed)
        s.builtInZoomControls = zoomAllowed
        s.displayZoomControls = false
        s.mediaPlaybackRequiresUserGesture = false
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        webView.isVerticalScrollBarEnabled = true
        webView.isHorizontalScrollBarEnabled = false
        webView.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                listener?.onPage(url.orEmpty(), view?.title.orEmpty())
                // 页面自己改过 DOM 后（AI 生成的页面常有展开/收起逻辑），
                // 重新注入一次桥，免得脚本被页面的 innerHTML 覆盖掉。
                view?.evaluateJavascript(PageBridge.SCRIPT, null)
                // 尺寸观察要在页面就绪后再起 —— 首次注入时 body 可能还不存在。
                view?.evaluateJavascript(
                    "window.__cf && window.__cf.observeSize ? window.__cf.observeSize() : 0;",
                    null,
                )
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val url = request?.url?.toString().orEmpty()
                // 外链一律交给系统浏览器，别在内置浏览器里越走越远
                if (url.isNotBlank() && request?.isForMainFrame == true) {
                    listener?.onPage(url, view?.title.orEmpty())
                }
                return false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                val line = "[${msg.messageLevel()}] ${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})"
                Log.d(TAG, line)
                onConsole?.invoke(line)
                return true
            }
        }

        // 允许 chrome://inspect 远程调试：把手机连上电脑就能用 DevTools 面板
        // 看 DOM、断点、调 CSS —— 这是「Chrome 开发者调试常用接口」里最硬的一条。
        runCatching { WebView.setWebContentsDebuggingEnabled(true) }
    }

    /**
     * 载入一段完整 HTML。
     *
     * 会补一条 viewport meta —— AI 常常忘记写，缺了它页面在手机上就是
     * 按 980px 布局再缩放，字小到看不清，还没法横竖屏自适应。
     */
    fun loadHtml(webView: WebView, html: String) {
        webView.loadDataWithBaseURL(BASE_URL, ensureViewport(html), "text/html", "utf-8", null)
    }

    /** 保证有 viewport meta；已有就保留 AI 自己写的（可能带 user-scalable=no）。 */
    fun ensureViewport(html: String): String {
        if (html.contains("name=\"viewport\"", ignoreCase = true) ||
            html.contains("name='viewport'", ignoreCase = true)
        ) {
            return html
        }
        val meta = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
        val headIdx = html.indexOf("<head", ignoreCase = true)
        if (headIdx < 0) {
            return meta + html
        }
        val gt = html.indexOf('>', headIdx)
        if (gt < 0) return meta + html
        return html.substring(0, gt + 1) + meta + html.substring(gt + 1)
    }

    /**
     * 抓当前可见区域为 PNG。
     *
     * `WebView.capturePicture()` 在硬件加速下从 API 19 起就返回 null，
     * 所以走 `View.draw(canvas)` —— 把 WebView 重绘到一张离屏 Bitmap 上。
     */
    fun capture(view: View): Bitmap? {
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return null
        return runCatching {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bmp))
            bmp
        }.getOrNull()
    }

    /** 抓 PNG 并存成文件，返回绝对路径（失败返回 null）。 */
    fun captureToFile(view: View, dir: java.io.File, name: String): String? {
        val bmp = capture(view) ?: return null
        return runCatching {
            if (!dir.exists()) dir.mkdirs()
            val f = java.io.File(dir, name)
            java.io.FileOutputStream(f).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bmp.recycle()
            f.absolutePath
        }.getOrNull()
    }
}
