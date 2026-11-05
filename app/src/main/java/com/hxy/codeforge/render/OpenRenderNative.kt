package com.hxy.codeforge.render

/**
 * `libopenrender.so` 的最小 JNI 封装。
 *
 * 与上游 `ai.openrender.NativeEngine` 等价，但包名换成 CodeForge 自己的命名空间
 * （Rust 侧对应 `app_jni.rs` 导出的 `Java_app_codeforge_render_OpenRenderNative_*`）。
 *
 * 引擎是**增量**的：每次只喂入新增文本，返回一个 JSON delta；宿主按 `block_id`
 * 应用 put/append/close/remove。这正是替换旧「每 20ms 全量重解析」渲染器的原因。
 *
 * 加载失败（16KB 页、ABI 不符、库缺失）时 [available] 为 false 且不抛异常 ——
 * 与 P-016 同款处理：原生库失败一次后本进程内不再尝试，界面走纯文本兜底。
 */
internal class OpenRenderNative(
    throttleMs: Int = 0,
    maxTailBytes: Int = 8 * 1024,
    maxDocumentBytes: Long = 16L * 1024 * 1024,
    enablePreviews: Boolean = false,
) : AutoCloseable {

    private var handle: Long =
        if (available) nativeNew(throttleMs, maxTailBytes, maxDocumentBytes, enablePreviews) else 0L

    fun feed(text: String): String? = if (handle != 0L) nativeFeed(handle, text) else null

    fun flush(): String? = if (handle != 0L) nativeFlush(handle) else null

    fun finish(): String? = if (handle != 0L) nativeFinish(handle) else null

    fun reset() {
        if (handle != 0L) nativeReset(handle)
    }

    /**
     * 开启公式 PNG 渲染（RaTeX，内嵌 KaTeX 字体，离线、无 WebView）。
     *
     * [cacheDir] 用来落地 Unicode 回退字体：RaTeX 内嵌的 KaTeX 字体只覆盖拉丁与
     * 数学符号，公式里的中文需要回退字体，而 Android 上没有可发现的系统字体
     * （见 `app_jni.rs` 的 `install_unicode_font`）。传应用 cacheDir 即可。
     */
    fun useRatex(fontSize: Float, devicePixelRatio: Float, cacheDir: String): Boolean =
        if (handle != 0L) nativeUseRatex(handle, fontSize, devicePixelRatio, cacheDir) else false

    /** 解析开关与上限；与上游 `configure()` 字段一致。 */
    fun configure(
        parserFlags: Int = PARSE_DEFAULT,
        softBreakAsSpace: Boolean = false,
        dropHtml: Boolean = false,
        sizeHints: Boolean = true,
        maxBlocks: Long = 200_000L,
    ) {
        if (handle != 0L) {
            nativeConfigure(handle, parserFlags, softBreakAsSpace, dropHtml, sizeHints, maxBlocks)
        }
    }

    override fun close() {
        if (handle != 0L) {
            nativeFree(handle)
            handle = 0L
        }
    }

    private external fun nativeNew(
        throttleMs: Int,
        maxTailBytes: Int,
        maxDocumentBytes: Long,
        enablePreviews: Boolean,
    ): Long

    private external fun nativeFree(handle: Long)

    private external fun nativeFeed(handle: Long, text: String): String?

    private external fun nativeFlush(handle: Long): String?

    private external fun nativeFinish(handle: Long): String?

    private external fun nativeReset(handle: Long)

    private external fun nativeUseRatex(
        handle: Long,
        fontSize: Float,
        devicePixelRatio: Float,
        cacheDir: String,
    ): Boolean

    private external fun nativeConfigure(
        handle: Long,
        parserFlags: Int,
        softBreakAsSpace: Boolean,
        dropHtml: Boolean,
        sizeHints: Boolean,
        maxBlocks: Long,
    )

    companion object {
        /**
         * 进程级可用性：`System.loadLibrary` 失败后再引用该类会抛
         * NoClassDefFoundError（LinkageError 家族），所以失败一次就判定本进程
         * 不可用，后续直接走纯文本兜底，绝不重试。
         */
        val available: Boolean = try {
            System.loadLibrary("openrender")
            true
        } catch (_: Throwable) {
            false
        }

        const val PARSE_TABLES = 1
        const val PARSE_STRIKETHROUGH = 2
        const val PARSE_TASKLISTS = 4
        const val PARSE_FOOTNOTES = 8
        const val PARSE_GFM = 16
        const val PARSE_MATH = 32
        const val PARSE_SMART_PUNCTUATION = 64

        /** tables | strikethrough | task lists | footnotes | GFM | math */
        const val PARSE_DEFAULT = 0x3F
    }
}
