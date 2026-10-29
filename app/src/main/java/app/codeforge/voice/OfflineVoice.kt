package app.codeforge.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 离线中文语音引擎（Vosk 兜底）+ 首启「环境准备」流水线。
 *
 * 背景：`android.speech.SpeechRecognizer` 依赖系统识别服务，国内无 GMS 的
 * ROM 大多没实现 —— 这类设备上点麦克风只会得到「无可用语音服务」。
 * 解决：APK 内置压缩的中文小模型（assets/voice-model-zh.zip，约 41 MB），
 * **首次启动**在后台解压到应用私有目录，期间界面盖一层「环境准备中」
 * 提示；解压成功写就绪标记，之后启动秒过、完全离线可用。
 *
 * 模型：vosk-model-small-cn-0.22（alphacephei 官方，流式识别）。
 * APK 里该 zip 声明为不二次压缩（build.gradle 的 noCompress），这样
 * `AssetFileDescriptor` 能拿到精确长度，「环境准备中」才能显示百分比。
 */
object OfflineVoice {

    /** 环境准备状态。设置页与首启遮罩据此渲染。 */
    sealed interface Status {
        /** 还没开始（本次进程尚未触发过准备）。 */
        data object Idle : Status

        /** 环境准备中：正在从 APK 解压模型。 */
        data class Preparing(val percent: Int) : Status

        data object Ready : Status

        data class Failed(val message: String) : Status
    }

    private const val ASSET_NAME = "voice-model-zh.zip"
    private const val MODEL_DIR_NAME = "voice-model-zh"
    private const val READY_MARKER = ".ready"

    // 环境准备与应用进程同生命周期，不随界面退出取消
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val status = mutableStateOf<Status>(Status.Idle)

    fun modelDir(context: Context): File = File(context.filesDir, MODEL_DIR_NAME)

    fun isReady(context: Context): Boolean {
        val marker = File(modelDir(context), READY_MARKER)
        if (!marker.exists()) return false
        // 标记内容是打包时模型 zip 的长度：APK 换了模型包而标记还是旧的，
        // 会永远用旧解压产物 —— 长度对不上视为未就绪，自动重解压。
        // 旧版本标记是 "ok"（非数字），当前 zip 从未换过，视为有效。
        val stamped = marker.readText().trim().toLongOrNull() ?: return true
        return try {
            context.assets.openFd(ASSET_NAME).use { it.length } == stamped
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 环境准备入口（幂等）：Application 启动时调一次；设置页「重新准备」也走这里。
     * 已就绪立即返回；正在准备不重入；失败后允许再试。
     */
    fun ensureReady(context: Context) {
        val app = context.applicationContext
        when {
            isReady(app) -> status.value = Status.Ready
            status.value is Status.Preparing -> Unit
            else -> status.value = Status.Idle
        }
        if (status.value != Status.Idle) return
        status.value = Status.Preparing(0)
        scope.launch {
            try {
                extractModel(app)
                status.value = Status.Ready
            } catch (e: Exception) {
                status.value = Status.Failed("环境准备失败：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** 只刷新状态显示，不触发解压（设置页进入时调用）。 */
    fun refresh(context: Context) {
        if (status.value is Status.Preparing) return
        status.value = if (isReady(context)) Status.Ready else Status.Idle
    }

    /**
     * 原生库已判死（进程级）：加载失败过一次后，类初始化器不会重跑，
     * 之后再碰 vosk 类只会立刻再抛 NoClassDefFoundError —— 路由层据此
     * 本进程内彻底绕开它（进程重启自动复位）。16KB 页设备装了 4KB 对齐
     * 的旧 libvosk.so 就是这个症状（已升 0.3.75 修根因，此标记是防御层）。
     */
    @Volatile var nativeDead = false

    /** 清掉解压产物（设置页「重新准备」先走这里）。 */
    fun reset(context: Context) {
        modelDir(context).deleteRecursively()
        status.value = Status.Idle
    }

    /** 从 assets 解压模型。返回值只为可读性，异常向上抛给调用者标 Failed。 */
    private fun extractModel(context: Context) {
        val target = modelDir(context)
        val fd = context.assets.openFd(ASSET_NAME)
        val total = fd.length
        fd.createInputStream().use { raw ->
            val counting = object : FilterInputStream(raw) {
                private var read = 0L
                private var lastPct = -1
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    if (n > 0) onBytes(n)
                    return n
                }

                override fun read(): Int {
                    val n = super.read()
                    if (n >= 0) onBytes(1)
                    return n
                }

                private fun onBytes(n: Int) {
                    read += n
                    if (total > 0) {
                        val pct = (read * 100 / total).toInt()
                        // 每 1% 更新一次足够顺滑，别每 64KB 都写状态
                        if (pct != lastPct) {
                            lastPct = pct
                            status.value = Status.Preparing(pct)
                        }
                    }
                }
            }
            target.deleteRecursively()
            target.mkdirs()
            unzipSkippingRoot(counting, target)
        }
        File(target, READY_MARKER).writeText(total.toString())
    }

    /** 解压 zip 并丢弃顶层目录（压缩包内是 model-name/...，落盘去掉这层壳）。防 zip-slip。 */
    private fun unzipSkippingRoot(input: InputStream, target: File) {
        val canonicalTarget = target.canonicalPath
        ZipInputStream(input).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val parts = entry.name.split('/', limit = 2)
                // 顶层目录壳：目录条目本身跳过，只取壳下的相对路径
                if (parts.size < 2 || parts[1].isEmpty()) continue
                val out = File(target, parts[1])
                if (!out.canonicalPath.startsWith(canonicalTarget)) continue
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    java.io.FileOutputStream(out).use { zis.copyTo(it) }
                }
                zis.closeEntry()
            }
        }
    }
}

/**
 * 离线识别会话，接口形态与 `ZhSpeechInput`（系统引擎）一致，
 * Composer 里两台引擎可以互换。vosk 的 [SpeechService] 自己起线程收音，
 * 回调抛回主线程再上抛 UI。
 */
/**
 * 离线识别会话：**自采集 + Vosk Recognizer 直连**。
 *
 * ## 为什么不用官方 SpeechService
 *
 * SpeechService 内部自己开 AudioRecord —— 构造失败/状态异常走 native 路径，
 * 真机上表现为整进程 SIGSEGV（Kotlin 层 catch 不住，2026-10-25 真机崩溃）。
 * 改为壳侧自管 AudioRecord：状态可校验（UNINITIALIZED 直接优雅降级）、
 * 采样循环全在 Java 层，任何异常都能转成 onError 而不是崩进程。
 * 采集数据喂 [Recognizer.acceptWaveForm]（官方支持的另一种用法）。
 *
 * 行为与系统引擎对齐：单句收口（完整结果即停），手动停止把最后一段
 * 中间结果当最终文本交出。回调统一抛回主线程。
 */
class OfflineVoiceEngine(context: Context) {

    var onPartial: ((String) -> Unit)? = null
    var onFinal: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onListeningChanged: ((Boolean) -> Unit)? = null

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val appContext: Context = context.applicationContext
    private var model: org.vosk.Model? = null
    private var recognizer: org.vosk.Recognizer? = null
    private var record: android.media.AudioRecord? = null
    private var captureThread: Thread? = null

    @Volatile private var listening = false
    private var lastPartial = ""

    private var focusRequest: android.media.AudioFocusRequest? = null

    // Model/Recognizer 的构造与销毁互斥：预加载线程和 start/destroy 可能并发
    private val loadLock = Any()

    @Volatile private var destroyed = false

    /** 识别流水线诊断日志（filesDir/logs/voice.log，64KB 截断）：
     *  真机「能听不出字」时靠它分定位断在加载/采集/识别哪一环。 */
    private fun logv(msg: String) {
        runCatching {
            val f = java.io.File(java.io.File(appContext.filesDir, "logs"), "voice.log")
            f.parentFile?.mkdirs()
            if (!f.exists() || f.length() > 64 * 1024) f.writeText("")
            f.appendText("[${System.currentTimeMillis()}] $msg\n")
        }
    }

    fun start() {
        logv("start: 调用（nativeDead=${OfflineVoice.nativeDead}）")
        if (listening) return
        // 独占麦克风期间请求瞬时音频焦点（B5-9，AudioFocusRequest 为 API 26+ 正路）
        runCatching {
            val am = appContext.getSystemService(android.media.AudioManager::class.java) ?: return@runCatching
            val req = android.media.AudioFocusRequest.Builder(
                android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            )
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .build(),
                )
                .build()
            if (am.requestAudioFocus(req) == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                focusRequest = req
            }
        }
        try {
            val (m, r) = synchronized(loadLock) {
                val m = model ?: org.vosk.Model(OfflineVoice.modelDir(appContext).absolutePath)
                    .also { model = it; logv("model: 已加载（${OfflineVoice.modelDir(appContext).name}）") }
                val r = recognizer
                    ?: org.vosk.Recognizer(m, SAMPLE_RATE).also { recognizer = it; logv("recognizer: 已创建") }
                m to r
            }
            r.reset()
            lastPartial = ""

            // 声明式采集：状态不可用立即降级，绝不进 native
            val minBuf = android.media.AudioRecord.getMinBufferSize(
                SAMPLE_RATE.toInt(),
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) {
                fail("设备不支持 16kHz 单声道录音")
                return
            }
            val rec = android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE.toInt(),
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 4,
            )
            if (rec.state != android.media.AudioRecord.STATE_INITIALIZED) {
                rec.release()
                fail("麦克风初始化失败（被占用或无权限）")
                return
            }
            rec.startRecording()
            if (rec.recordingState != android.media.AudioRecord.RECORDSTATE_RECORDING) {
                // startRecording 是 void，失败不抛异常：不校验状态会带着哑麦克风
                // 进采集循环，表现为 read 恒 0 自旋或恒静音，UI 永久「正在聆听」
                rec.release()
                fail("麦克风启动失败（录音状态异常）")
                return
            }
            record = rec
            listening = true
            logv("capture: AudioRecord 已启动（buffer=$minBuf）")
            onListeningChanged?.invoke(true)

            captureThread = Thread {
                val shorts = ShortArray(1024)
                val bytes = ByteArray(shorts.size * 2)
                val bb = java.nio.ByteBuffer.wrap(bytes)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                var reads = 0
                var rmsPeak = 0
                var zeroReads = 0
                val startTs = System.currentTimeMillis()
                try {
                    while (listening) {
                        val n = rec.read(shorts, 0, shorts.size)
                        if (n < 0) {
                            // 裸 break 会让线程无声死亡：UI 永久「正在聆听」、AudioRecord 泄漏
                            logv("capture: read 返回 $n，采集终止")
                            main.post { if (listening) fail("麦克风读取失败（错误码 $n）") }
                            return@Thread
                        }
                        if (n == 0) {
                            // 正常阻塞读不会返回 0；连续 0 = 录音没真正跑起来，
                            // 无限自旋同样是「正在聆听」永久卡死的来源之一
                            if (++zeroReads >= 150) {
                                logv("capture: 连续 150 次 read=0，判定采集无数据")
                                main.post { if (listening) fail("麦克风没有数据（可能被其他应用占用）") }
                                return@Thread
                            }
                            continue
                        }
                        zeroReads = 0
                        reads++
                        if (reads == 1) logv("capture: 首帧音频到达（n=$n）")
                        if (reads % 40 == 0) {
                            var sum = 0L
                            for (i in 0 until n) { val v = shorts[i].toInt(); sum += (v * v) }
                            val rms = kotlin.math.sqrt(sum.toDouble() / n).toInt()
                            rmsPeak = maxOf(rmsPeak, rms)
                            logv("capture: reads=$reads rms=$rms peak=$rmsPeak（rms≈0 = 麦克风采空音）")
                        }
                        if (lastPartial.isBlank() && System.currentTimeMillis() - startTs > 20_000) {
                            // 总看门狗：一个字都没出过就不再无限挂着。rmsPeak==0 区分
                            // 「假麦克风全零」与「听到了但没识别出来」两种去向
                            val hint = if (rmsPeak == 0) {
                                "麦克风只采到静音（能量为 0）——请检查系统设置是否限制了本应用使用麦克风"
                            } else {
                                "请靠近手机正常说话再试一次"
                            }
                            logv("capture: 20s 无识别结果，收口（peak=$rmsPeak）")
                            main.post { if (listening) fail("没有识别到内容：$hint") }
                            return@Thread
                        }
                        bb.clear()
                        bb.asShortBuffer().put(shorts, 0, n)
                        val len = n * 2
                        val isFinal = r.acceptWaveForm(bytes, len)
                        if (isFinal) {
                            val text = org.json.JSONObject(r.result).optString("text")
                            logv("final: 「$text」（reads=$reads）")
                            if (text.isNotBlank()) {
                                main.post {
                                    if (listening) {
                                        stopInternal()
                                        onFinal?.invoke(text)
                                    }
                                }
                                return@Thread
                            }
                        } else {
                            val p = org.json.JSONObject(r.partialResult).optString("partial")
                            if (p.isNotBlank() && p != lastPartial) {
                                if (lastPartial.isBlank()) logv("partial: 首个中间结果「$p」")
                                lastPartial = p
                                main.post { if (listening) onPartial?.invoke(p) }
                            }
                        }
                    }
                } catch (e: InterruptedException) {
                    // 用户收口：正常路径
                } catch (e: LinkageError) {
                    // JNA/native 在 acceptWaveForm 中途抛 Error 家族同样不能裸奔（同 P-016）
                    OfflineVoice.nativeDead = true
                    logv("linkage(capture): ${e.javaClass.simpleName}: ${e.message}")
                    main.post {
                        if (listening) {
                            stopInternal()
                            onError?.invoke("离线引擎不可用（识别中原生库错误：${(e.message ?: e.javaClass.simpleName).take(90)}）")
                        }
                    }
                } catch (e: Exception) {
                    main.post {
                        if (listening) {
                            stopInternal()
                            onError?.invoke("离线识别出错：${e.message ?: e.javaClass.simpleName}")
                        }
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
        } catch (e: LinkageError) {
            // 必须 catch 整个 LinkageError 家族而不是 UnsatisfiedLinkError：
            // 类初始化器里 loadLibrary 失败，后续使用抛的是 NoClassDefFoundError
            // / ExceptionInInitializerError —— 都不是 Exception 子类，漏接即主线程裸崩
            // （真机 crash-1792931918114 即此：点语音必崩）。
            OfflineVoice.nativeDead = true
            logv("linkage: ${e.javaClass.simpleName}: ${e.message}")
            fail("离线引擎不可用（原生库加载失败：${(e.message ?: e.javaClass.simpleName).take(90)}）")
        } catch (e: SecurityException) {
            fail("缺少麦克风权限")
        } catch (e: Exception) {
            fail("离线引擎启动失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 用户手动收口：把最后一段中间结果当最终文本交出去；一个字都没有时
     *  明确报「没有听到内容」——无声结束会让用户以为点了没反应。 */
    fun stop() {
        if (!listening) return
        val partial = lastPartial
        stopInternal()
        if (partial.isNotBlank()) onFinal?.invoke(partial)
        else onError?.invoke("没有听到内容")
    }

    fun destroy() {
        destroyed = true
        stopInternal()
        synchronized(loadLock) {
            runCatching { recognizer?.close() }
            runCatching { model?.close() }
            recognizer = null
            model = null
        }
    }

    /**
     * 释放已加载的 Vosk 模型（启用云端识别时调用，省下 ~68MB 常驻内存）。
     *
     * 与 [destroy] 的区别：这里只卸载模型，不把引擎判死 —— 云端只是优先，
     * 用户关掉云端后离线仍要能兜底；下一次 start()/prepare() 会重新加载。
     * 磁盘上的模型文件保留（首启解压成本不为 0，删了再解压不划算）。
     */
    fun releaseModel() {
        stopInternal()
        synchronized(loadLock) {
            runCatching { recognizer?.close() }
            runCatching { model?.close() }
            recognizer = null
            model = null
        }
        logv("releaseModel: 已释放（云端识别启用）")
    }

    /**
     * 后台预加载 Model/Recognizer（幂等）：Composer 在离线引擎就绪后调用。
     * 首次构造要读 ~68MB 模型数据，放主线程会把点按后头几秒的说话吞掉，
     * 表现为「识别不出字」；预加载后 start() 拿到的是现成实例，点按即采。
     */
    fun prepare() {
        Thread {
            runCatching {
                synchronized(loadLock) {
                    if (destroyed) return@Thread
                    if (model == null) {
                        org.vosk.Model(OfflineVoice.modelDir(appContext).absolutePath)
                            .also { model = it }
                    }
                    if (recognizer == null && model != null) {
                        org.vosk.Recognizer(model!!, SAMPLE_RATE).also { recognizer = it }
                    }
                }
                logv("prepare: 完成")
            }.onFailure { logv("prepare: 失败 ${it.javaClass.simpleName}: ${it.message}") }
        }.apply {
            isDaemon = true
            name = "vosk-preload"
            start()
        }
    }

    private fun stopInternal() {
        listening = false
        captureThread?.interrupt()
        captureThread = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        runCatching {
            focusRequest?.let {
                appContext.getSystemService(android.media.AudioManager::class.java)
                    ?.abandonAudioFocusRequest(it)
            }
            focusRequest = null
        }
        onListeningChanged?.invoke(false)
    }

    private fun fail(message: String) {
        stopInternal()
        onError?.invoke(message)
    }

    private companion object {
        const val SAMPLE_RATE = 16000.0f
    }
}
