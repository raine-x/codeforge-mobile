package com.hxy.codeforge.phone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 屏幕视觉辅助模块：
 * 1. 按 80% 默认精度缩放/压缩截图，并计算 64-bit dHash 指纹用于页面变化比对（纯算法，可单测）；
 * 2. 可选叠加辅助像素坐标网格图（网格数字始终对齐原始物理屏幕坐标系）；
 * 3. 调用当前已配置的视觉模型（OpenAI / Anthropic 兼容协议）做画面识别与目标定位，
 *    未配置视觉模型或网络不可用时优雅回退为尺寸/指纹/控件摘要。
 */
internal object PhoneVisionAnalyzer {

    const val DEFAULT_SCREENSHOT_SCALE = 0.80f
    const val DEFAULT_SCREENSHOT_JPEG_QUALITY = 80

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    /**
     * 计算缩放后的截图宽高（默认 80% 线性精度，限制在 0.25~1.0 之间）。
     */
    internal fun computeScaledDimensions(
        width: Int,
        height: Int,
        scale: Float = DEFAULT_SCREENSHOT_SCALE,
    ): Pair<Int, Int> {
        val safeW = width.coerceAtLeast(1)
        val safeH = height.coerceAtLeast(1)
        val clamped = scale.coerceIn(0.25f, 1.0f)
        val targetW = (safeW * clamped).roundToInt().coerceIn(1, safeW)
        val targetH = (safeH * clamped).roundToInt().coerceIn(1, safeH)
        return targetW to targetH
    }

    /**
     * 对 9x8 亮度矩阵计算 64-bit difference hash（纯函数，可直接 JVM 单测）。
     */
    internal fun computeDHashFromLuma(luma9x8: IntArray): String {
        require(luma9x8.size >= 72) { "luma9x8 must contain at least 72 samples" }
        var bits = 0L
        var idx = 0
        for (y in 0 until 8) {
            val row = y * 9
            for (x in 0 until 8) {
                if (luma9x8[row + x] > luma9x8[row + x + 1]) {
                    bits = bits or (1L shl idx)
                }
                idx++
            }
        }
        return java.lang.Long.toHexString(bits).padStart(16, '0')
    }

    internal fun hammingDistance(aHex: String, bHex: String): Int {
        if (aHex.length != 16 || bHex.length != 16) return 64
        val a = aHex.toULongOrNull(16) ?: return 64
        val b = bHex.toULongOrNull(16) ?: return 64
        return (a xor b).countOneBits()
    }

    fun computeDHash(bitmap: Bitmap): String {
        val scaled = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        val pixels = IntArray(72)
        scaled.getPixels(pixels, 0, 9, 0, 0, 9, 8)
        if (scaled !== bitmap) scaled.recycle()
        val luma = IntArray(72) { i ->
            val c = pixels[i]
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            (r * 299 + g * 587 + b * 114) / 1000
        }
        return computeDHashFromLuma(luma)
    }

    /**
     * 生成带坐标标注网格的临时分析副本（不修改原图），标注值始终使用原始物理屏幕像素坐标 [coordWidth x coordHeight]。
     */
    fun withCoordinateGrid(
        source: Bitmap,
        coordWidth: Int = source.width,
        coordHeight: Int = source.height,
    ): Bitmap {
        val copy = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(copy)
        val w = copy.width
        val h = copy.height
        val realW = coordWidth.coerceAtLeast(1)
        val realH = coordHeight.coerceAtLeast(1)
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(120, 217, 119, 87)
            strokeWidth = 2f
        }
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(175, 24, 24, 24)
            style = Paint.Style.FILL
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = max(16f, w / 44f)
            isFakeBoldText = true
        }
        val cols = 4
        val rows = 6
        for (i in 1 until cols) {
            val x = w * i / cols.toFloat()
            val label = "x=${(realW * i / cols.toFloat()).roundToInt()}"
            canvas.drawLine(x, 0f, x, h.toFloat(), linePaint)
            canvas.drawRect(x + 4f, 6f, x + textPaint.measureText(label) + 14f, textPaint.textSize + 14f, bgPaint)
            canvas.drawText(label, x + 8f, textPaint.textSize + 8f, textPaint)
        }
        for (j in 1 until rows) {
            val y = h * j / rows.toFloat()
            val label = "y=${(realH * j / rows.toFloat()).roundToInt()}"
            canvas.drawLine(0f, y, w.toFloat(), y, linePaint)
            canvas.drawRect(6f, y + 4f, textPaint.measureText(label) + 18f, y + textPaint.textSize + 14f, bgPaint)
            canvas.drawText(label, 10f, y + textPaint.textSize + 8f, textPaint)
        }
        return copy
    }

    suspend fun analyzeBitmap(
        bitmap: Bitmap,
        question: String,
        drawGrid: Boolean,
        uiSummary: String,
        config: VisionModelConfig?,
        screenWidth: Int = bitmap.width,
        screenHeight: Int = bitmap.height,
    ): Result<String> = withContext(Dispatchers.IO) {
        if (config == null || config.baseUrl.isBlank() || config.model.isBlank() || config.apiKey.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("当前未配置可用于视觉识别的完整模型端点与密钥，请直接查看截图文件或结合控件树操作"),
            )
        }
        val realW = screenWidth.coerceAtLeast(1)
        val realH = screenHeight.coerceAtLeast(1)
        val prepared = if (drawGrid) withCoordinateGrid(bitmap, realW, realH) else bitmap
        val jpegBytes = try {
            encodeScaledJpeg(prepared, maxDimension = 1280, quality = DEFAULT_SCREENSHOT_JPEG_QUALITY)
        } finally {
            if (prepared !== bitmap) prepared.recycle()
        }
        val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val prompt = buildString {
            appendLine("请结合这张 Android 手机屏幕截图（图像分辨率 ${bitmap.width}x${bitmap.height}，真实触控屏幕分辨率 ${realW}x${realH} 像素）回答问题。")
            appendLine("若需要指出点击或交互目标，请严格按照网格标注与真实屏幕坐标系 [${realW}x${realH}] 给出像素坐标 [x, y]，以及可见文字与页面状态。")
            if (question.isNotBlank()) {
                appendLine("任务问题：$question")
            }
            if (uiSummary.isNotBlank()) {
                appendLine("参考控件摘要：$uiSummary")
            }
        }
        runCatching {
            when (config.protocol.lowercase()) {
                "anthropic" -> callAnthropicVision(config, b64, prompt)
                else -> callOpenAiVision(config, b64, prompt)
            }
        }
    }

    private fun encodeScaledJpeg(bitmap: Bitmap, maxDimension: Int, quality: Int): ByteArray {
        val w = bitmap.width
        val h = bitmap.height
        val scale = if (max(w, h) > maxDimension) maxDimension.toFloat() / max(w, h) else 1f
        val target = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (w * scale).toInt().coerceAtLeast(1),
                (h * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        return try {
            ByteArrayOutputStream().use { baos ->
                target.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                baos.toByteArray()
            }
        } finally {
            if (target !== bitmap) target.recycle()
        }
    }

    private fun callOpenAiVision(cfg: VisionModelConfig, b64: String, prompt: String): String {
        val url = cfg.baseUrl.trimEnd('/') + "/chat/completions"
        val bodyJson = JSONObject().apply {
            put("model", cfg.model)
            put("max_tokens", 800)
            put(
                "messages",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put(
                            "content",
                            JSONArray()
                                .put(JSONObject().put("type", "text").put("text", prompt))
                                .put(
                                    JSONObject().put("type", "image_url").put(
                                        "image_url",
                                        JSONObject().put("url", "data:image/jpeg;base64,$b64"),
                                    ),
                                ),
                        )
                    },
                ),
            )
        }
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .header("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${text.take(240)}")
            val obj = JSONObject(text)
            val choices = obj.optJSONArray("choices") ?: error("上游未返回 choices")
            val msg = choices.optJSONObject(0)?.optJSONObject("message") ?: error("未返回消息")
            return msg.optString("content").ifBlank { text.take(400) }
        }
    }

    private fun callAnthropicVision(cfg: VisionModelConfig, b64: String, prompt: String): String {
        val url = cfg.baseUrl.trimEnd('/') + "/messages"
        val bodyJson = JSONObject().apply {
            put("model", cfg.model)
            put("max_tokens", 800)
            put(
                "messages",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put(
                            "content",
                            JSONArray()
                                .put(
                                    JSONObject().put("type", "image").put(
                                        "source",
                                        JSONObject()
                                            .put("type", "base64")
                                            .put("media_type", "image/jpeg")
                                            .put("data", b64),
                                    ),
                                )
                                .put(JSONObject().put("type", "text").put("text", prompt)),
                        )
                    },
                ),
            )
        }
        val req = Request.Builder()
            .url(url)
            .header("x-api-key", cfg.apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${text.take(240)}")
            val arr = JSONObject(text).optJSONArray("content") ?: error("上游未返回 content")
            return buildString {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optString("type") == "text") {
                        append(o.optString("text"))
                    }
                }
            }.ifBlank { text.take(400) }
        }
    }
}
