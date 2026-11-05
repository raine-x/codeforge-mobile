package com.hxy.codeforge.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.hxy.codeforge.ui.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * 云端语音识别引擎（硅基流动 / OpenAI 兼容转写接口）。
 *
 * 与系统 [android.speech.SpeechRecognizer] 的接口形态一致，Composer
 * 里两台引擎可以互换；区别是云识别**没有中间结果** —— 它只能「录一整段 → 上传
 * → 回一段文本」，所以 [onPartial] 不调用，保留字段仅为接线一致。
 *
 * 流程：AudioRecord 16kHz 单声道 PCM16 → 写进 cacheDir 的临时 WAV（先占 44 字节
 * 头，录完用 RandomAccessFile 回填长度）→ multipart 上传 → 解析 `{"text"}`。
 * 上传在 IO 协程里跑，主线程只负责 start/stop 的开关（stop 靠 `record.stop()`
 * 解除采集线程阻塞的 read）。
 */
class CloudVoiceEngine(private val context: Context) {

    var onFinal: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onListeningChanged: ((Boolean) -> Unit)? = null

    /** 云识别无中间结果，此字段不会触发；保留只为与现有引擎接线对齐。 */
    var onPartial: ((String) -> Unit)? = null

    private val appContext: Context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 转写是「一次请求一大段音频」：连 15s、读 90s（长录音 + 服务端排队留足余量）
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private var record: AudioRecord? = null
    private var captureThread: Thread? = null
    private var wavFile: File? = null

    @Volatile private var listening = false

    /** cancel() 置位：采集线程收尾时据此决定「删文件不上传」。 */
    @Volatile private var cancelled = false
    @Volatile private var destroyed = false

    fun start() {
        if (destroyed || listening) return
        if (AppSettings.sttCloudApiKey.value.isBlank()) {
            emitError("未配置云端语音 API Key（设置 → 语音输入）")
            return
        }
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            emitError("设备不支持 16kHz 单声道录音")
            return
        }
        val bufferSize = max(minBuf, 4096)

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (e: SecurityException) {
            emitError("缺少麦克风权限")
            return
        } catch (e: Exception) {
            emitError("麦克风初始化失败：${e.message ?: e.javaClass.simpleName}")
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            emitError("麦克风初始化失败（被占用或无权限）")
            return
        }
        // startRecording 是 void，失败不抛异常：不校验状态会带着哑麦克风进采集循环
        rec.startRecording()
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release()
            emitError("麦克风启动失败（录音状态异常）")
            return
        }

        cancelled = false
        record = rec
        onListeningChanged?.invoke(true)

        val file = File(appContext.cacheDir, "stt-${System.currentTimeMillis()}.wav")
        this.wavFile = file
        runCatching { FileOutputStream(file).use { it.write(wavHeader(0)) } }
        listening = true

        captureThread = Thread {
            captureLoop(rec, file, bufferSize)
        }.apply {
            isDaemon = true
            name = "cloud-stt-capture"
            start()
        }
    }

    /**
     * 用户收口。主线程调 `record.stop()` 解除采集线程阻塞中的 read，
     * 收尾（补 WAV 头、上传）由采集线程自己完成，这里不等它。
     */
    fun stop() {
        if (!listening) return
        listening = false
        runCatching { record?.stop() }
        onListeningChanged?.invoke(false)
    }

    /** 放弃本次录音：不上传、删临时文件。 */
    fun cancel() {
        cancelled = true
        if (listening) {
            listening = false
            runCatching { record?.stop() }
            onListeningChanged?.invoke(false)
        } else {
            // 采集线程已退出（可能在等上传）：这里只清理可能残留的临时文件
            wavFile?.delete()
            wavFile = null
        }
    }

    fun destroy() {
        destroyed = true
        cancel()
        scope.cancel()
    }

    // ---------------------------------------------------------------- 采集

    private fun captureLoop(rec: AudioRecord, file: File, bufferSize: Int) {
        val buf = ByteArray(bufferSize)
        var written = 0L
        var zeroReads = 0
        try {
            FileOutputStream(file, true).use { out ->
                val deadline = System.currentTimeMillis() + MAX_RECORD_MS
                while (listening && System.currentTimeMillis() < deadline) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n < 0) break
                    if (n == 0) {
                        // 正常阻塞读不会返回 0；连续 0 = 录音没真正跑起来，别无限自旋
                        if (++zeroReads >= 60) break
                        continue
                    }
                    zeroReads = 0
                    out.write(buf, 0, n)
                    written += n
                }
            }
        } catch (e: Exception) {
            // stop()/release() 与读取并发时 read 会抛 IllegalStateException ——
            // 这是收口的正常路径，按已录到的数据继续往下走
        }

        listening = false
        runCatching { rec.release() }
        if (record === rec) record = null
        if (wavFile === file) wavFile = null

        // 头部长度回填：录音结束才知道体积（随机写，不用整个文件重写）
        if (written > 0) runCatching { patchWavHeader(file, written) }
        main.post { onListeningChanged?.invoke(false) }

        when {
            cancelled -> file.delete()
            written < MIN_PCM_BYTES -> {
                // 不足 0.3 秒有效音频 = 没录到声音，与其让服务端返回空串，不如本地判死
                file.delete()
                emitError("没有录到声音")
            }

            else -> scope.launch { transcribe(file) }
        }
    }

    // ---------------------------------------------------------------- 上传

    private suspend fun transcribe(file: File) {
        try {
            val key = AppSettings.sttCloudApiKey.value.trim()
            val base = AppSettings.sttCloudBaseUrl.value.trim()
                .ifBlank { AppSettings.STT_CLOUD_DEFAULT_BASE_URL }
                .trimEnd('/')
            val model = AppSettings.sttCloudModel.value.trim()
                .ifBlank { AppSettings.STT_CLOUD_DEFAULT_MODEL }

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", model)
                .addFormDataPart("file", "audio.wav", file.asRequestBody("audio/wav".toMediaType()))
                .build()
            val request = Request.Builder()
                .url("$base/audio/transcriptions")
                .header("Authorization", "Bearer $key")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    emitError("云端转写失败：HTTP ${response.code} ${raw.take(160)}")
                    return
                }
                val text = runCatching { JSONObject(raw).optString("text") }.getOrDefault("")
                val cleaned = if (AppSettings.sttCloudClean.value) cleanText(text) else text.trim()
                if (cleaned.isBlank()) {
                    emitError("云端转写失败：返回内容里没有文字")
                } else {
                    main.post { onFinal?.invoke(cleaned) }
                }
            }
        } catch (e: Exception) {
            emitError("云端转写失败：${e.message ?: e.javaClass.simpleName}")
        } finally {
            file.delete()
        }
    }

    // ---------------------------------------------------------------- 工具

    private fun emitError(message: String) {
        main.post { onError?.invoke(message) }
    }

    /** 44 字节 PCM16 单声道 WAV 头；[dataSize] 是后面的音频字节数。 */
    private fun wavHeader(dataSize: Long): ByteArray {
        val header = ByteArray(44)
        fun ascii(offset: Int, s: String) {
            s.forEachIndexed { i, c -> header[offset + i] = c.code.toByte() }
        }

        fun intLe(offset: Int, v: Int) {
            header[offset] = (v and 0xFF).toByte()
            header[offset + 1] = ((v shr 8) and 0xFF).toByte()
            header[offset + 2] = ((v shr 16) and 0xFF).toByte()
            header[offset + 3] = ((v shr 24) and 0xFF).toByte()
        }

        fun shortLe(offset: Int, v: Int) {
            header[offset] = (v and 0xFF).toByte()
            header[offset + 1] = ((v shr 8) and 0xFF).toByte()
        }

        val byteRate = SAMPLE_RATE * 2
        ascii(0, "RIFF")
        intLe(4, (36 + dataSize).toInt())
        ascii(8, "WAVE")
        ascii(12, "fmt ")
        intLe(16, 16)
        shortLe(20, 1) // PCM
        shortLe(22, 1) // 单声道
        intLe(24, SAMPLE_RATE)
        intLe(28, byteRate)
        shortLe(32, 2) // block align
        shortLe(34, 16) // bits per sample
        ascii(36, "data")
        intLe(40, dataSize.toInt())
        return header
    }

    private fun patchWavHeader(file: File, dataSize: Long) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(wavHeader(dataSize))
        }
    }

    /**
     * 去掉 SenseVoice 插在正文里的情感/事件标记（🎼😡😊 之类），再压缩空白。
     * 这些符号在 `OTHER_SYMBOL` 类里，另有两段码点区间兜住 Android 上判定
     * 不一致的 emoji 区。
     */
    private fun cleanText(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            val count = Character.charCount(cp)
            val isSymbol = Character.getType(cp) == Character.OTHER_SYMBOL.toInt() ||
                cp in 0x1F000..0x1FAFF ||
                cp in 0x2600..0x27BF
            if (!isSymbol) sb.append(raw, i, i + count)
            i += count
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private companion object {
        const val SAMPLE_RATE = 16000
        const val MAX_RECORD_MS = 120_000L
        /** 0.3 秒的 PCM16 字节数：低于它视为没录到声音。 */
        const val MIN_PCM_BYTES = 9_600L
    }
}
