package com.hxy.codeforge.ui.common

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.speech.RecognizerIntent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/**
 * 中文语音输入，直接包系统的 [SpeechRecognizer]。
 *
 * 为什么不用 Vosk / Whisper 等离线库：中文模型最小也是几十 MB，
 * 而系统识别服务（国内 ROM 自带、Google 服务亦带）零依赖、零模型、
 * 零 APK 增量 —— 这是「语音输入必须轻量」的最优解。
 * 设备上没有识别服务时优雅降级成一句提示，不影响其他功能。
 *
 * 识别过程中 [onPartial] 会持续上抛中间结果（用于把输入框「点亮」），
 * 一句话说完 [onFinal] 上抛最终文本；错误统一转成中文短语。
 */
class ZhSpeechInput(private val context: Context) : RecognitionListener {

    private val sr: SpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)

    var onPartial: ((String) -> Unit)? = null
    var onFinal: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onListeningChanged: ((Boolean) -> Unit)? = null

    /**
     * 系统引擎判死（ERROR_CLIENT，真机实测重试必再挂）：宿主应**当次**无缝
     * 切到另一台引擎（云端）续听，而不是提示用户再点一次。
     */
    var onEngineDead: (() -> Unit)? = null

    /** 引擎已经收口（结果/错误）后到达的迟到回调一律丢弃，防止覆盖后续输入。 */
    private var listening = false

    init {
        sr.setRecognitionListener(this)
    }

    /** 独占麦克风期间请求瞬时音频焦点（B5-9）：让位后台音乐而不是混音。 */
    private var focusRequest: android.media.AudioFocusRequest? = null
    private fun requestFocus() {
        if (focusRequest != null) return
        val am = context.getSystemService(android.media.AudioManager::class.java) ?: return
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

    private fun abandonFocus() {
        focusRequest?.let {
            context.getSystemService(android.media.AudioManager::class.java)
                ?.abandonAudioFocusRequest(it)
        }
        focusRequest = null
    }

    fun start() {
        if (listening) return
        listening = true
        requestFocus()
        onListeningChanged?.invoke(true)
        sr.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            },
        )
    }

    /** 用户主动收口：让引擎把已听到的话整理成最终结果。 */
    fun stop() {
        if (!listening) return
        sr.stopListening()
    }

    fun destroy() {
        abandonFocus()
        listening = false
        // 服务进程死亡后 destroy 可能直接抛 RemoteException，别让它带崩界面
        runCatching { sr.destroy() }
    }

    override fun onPartialResults(bundle: Bundle) {
        if (!listening) return
        val text = bundle
            .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            .orEmpty()
        if (text.isNotEmpty()) {
            VoiceHealth.noteSuccess()
            onPartial?.invoke(text)
        }
    }

    override fun onResults(bundle: Bundle) {
        if (!listening) return
        listening = false
        onListeningChanged?.invoke(false)
        val text = bundle
            .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            .orEmpty()
        if (text.isNotEmpty()) {
            VoiceHealth.noteSuccess()
            onFinal?.invoke(text)
        } else {
            // 空结果原来被静默吞掉：聆听态自然结束、无字无提示，像没发生过
            VoiceHealth.noteFailure()
            onError?.invoke("没有听到内容")
        }
    }

    override fun onError(error: Int) {
        if (!listening) return
        if (error == SpeechRecognizer.ERROR_CLIENT) {
            // ROM 识别服务进程抖动（真机实测错误码 5）：判死 + 当次直切云端引擎。
            // 不重试 —— 真机复测重试 100% 再挂；也不提示「再点一次」—— 用户无感续听
            listening = false
            onListeningChanged?.invoke(false)
            VoiceHealth.systemDegraded = true
            onEngineDead?.invoke()
            return
        }
        val message = describe(error)
        listening = false
        onListeningChanged?.invoke(false)
        VoiceHealth.noteFailure()
        onError?.invoke(message)
    }

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        -> "没听清，请再试一次"

        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        -> "网络不可用，语音识别需要联网"

        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少麦克风权限"

        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音服务忙，稍后再试"

        else -> "语音识别失败（$error）"
    }

    // 其余回调与界面无关，留空实现即可。
    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}

/**
 * 在组合里持有 [ZhSpeechInput]，离开组合自动销毁引擎。
 * 回调经 rememberUpdatedState 中转 —— 闭包可以每次重组都换，引擎实例不重建。
 */
@Composable
fun rememberZhSpeechInput(
    onPartial: (String) -> Unit,
    onFinal: (String) -> Unit,
    onError: (String) -> Unit,
    onListeningChanged: (Boolean) -> Unit,
    onEngineDead: () -> Unit = {},
): ZhSpeechInput {
    val context = LocalContext.current
    val input = remember { ZhSpeechInput(context) }

    val partial by rememberUpdatedState(onPartial)
    val final by rememberUpdatedState(onFinal)
    val error by rememberUpdatedState(onError)
    val listeningChanged by rememberUpdatedState(onListeningChanged)
    val engineDead by rememberUpdatedState(onEngineDead)

    DisposableEffect(input) {
        input.onPartial = { partial(it) }
        input.onFinal = { final(it) }
        input.onError = { error(it) }
        input.onListeningChanged = { listeningChanged(it) }
        input.onEngineDead = { engineDead() }
        onDispose { input.destroy() }
    }
    return input
}

/** 当前设备有没有可用的识别服务。没有就别走语音分支，直接提示。 */
fun zhSpeechAvailable(context: Context): Boolean =
    SpeechRecognizer.isRecognitionAvailable(context)

/**
 * 系统语音引擎健康状态（进程级）：ROM 的识别服务时好时坏（同一设备曾先后报
 * 「无服务」与 ERROR_CLIENT），失败一次后本次进程内不再走这台引擎，
 * 用户无感。进程重启自动复位。
 */
object VoiceHealth {
    @Volatile var systemDegraded = false

    /** 系统引擎连续失败计数（报错/空结果都算，出过字即清零）。 */
    @Volatile var failStreak = 0

    /**
     * 「在但坏」的识别服务（反复没听清/超时/网络错误）不会触发 ERROR_CLIENT，
     * 光靠单次错误降不了级 —— 连续两次失败自动判降级，路由不再走这台引擎。
     */
    fun noteFailure() {
        if (++failStreak >= 2) systemDegraded = true
    }

    fun noteSuccess() {
        failStreak = 0
    }
}
