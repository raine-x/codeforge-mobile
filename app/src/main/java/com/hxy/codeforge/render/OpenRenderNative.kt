package com.hxy.codeforge.render

/**
 * `libopenrender.so` 的最小 JNI 封装：增量喂入文本并输出 IR delta。
 * 原生库加载失败时 [available] 置 false，界面退回纯文本兜底（P-016）。
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

    /** 开启公式 PNG 渲染（RaTeX）；[cacheDir] 用于缓存 Unicode 回退字体。 */
    fun useRatex(fontSize: Float, devicePixelRatio: Float, cacheDir: String): Boolean =
        if (handle != 0L) nativeUseRatex(handle, fontSize, devicePixelRatio, cacheDir) else false

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
         * System.loadLibrary 失败后类初始化状态会被标记为失败，后续引用会抛 NoClassDefFoundError；
         * 因此只尝试加载一次并做进程级降级。
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
