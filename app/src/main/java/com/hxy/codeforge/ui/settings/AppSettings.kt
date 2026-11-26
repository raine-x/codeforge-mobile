package com.hxy.codeforge.ui.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 旋转灵敏度：决定 [com.hxy.codeforge.ui.common.rememberDevicePose] 的切换迟滞角。
 * 数值是「偏离当前档位中心多少度才切换」—— 越小越容易触发（灵敏），
 * 越大越难触发（迟钝，躺着晃也不转）。
 */
enum class RotationSensitivity(val label: String, val hysteresisDegrees: Int) {
    Low("迟钝", 80),
    Standard("标准", 60),
    High("灵敏", 45),
}

/**
 * 应用设置：Compose 状态 + SharedPreferences 持久化。
 *
 * 用 `mutableStateOf` 而不是 ViewModel：这些是跨界面的轻量偏好，
 * DeviceRotation（界面层）与设置弹层都要读写，全局单例最直接。
 * 写操作同步落盘（apply 异步提交），读在组合里自动响应变化。
 *
 * 必须先在 [com.hxy.codeforge.CodeForgeApp] 里 [init]，读默认值才有意义。
 */
object AppSettings {

    private const val PREFS_NAME = "app_settings"
    private const val KEY_ROTATE_WITH_DEVICE = "rotate_with_device"
    private const val KEY_ROTATION_SENSITIVITY = "rotation_sensitivity"
    private const val KEY_DARK_MODE = "dark_mode"
    private const val KEY_PROVIDER_SEEDED = "provider_seeded_v1"
    private const val KEY_INPUT_BOX_STYLE = "input_box_style"
    private const val KEY_BACKGROUND_IMAGE = "background_image_path"
    private const val KEY_BACKGROUND_BLUR = "background_blur"
    private const val KEY_BACKGROUND_BRIGHTNESS = "background_brightness"
    private const val KEY_TEXT_COLOR_OVERRIDE = "text_color_override"
    private const val KEY_SHOW_STATUS_BAR = "show_status_bar"
    private const val KEY_STT_CLOUD_ENABLED = "stt_cloud_enabled"
    private const val KEY_STT_CLOUD_API_KEY = "stt_cloud_api_key"
    private const val KEY_STT_CLOUD_BASE_URL = "stt_cloud_base_url"
    private const val KEY_STT_CLOUD_MODEL = "stt_cloud_model"
    private const val KEY_STT_CLOUD_CLEAN = "stt_cloud_clean"
    private const val KEY_LAST_SESSION_ID = "last_session_id"
    private const val KEY_NOTIFY_ENABLED = "notify_enabled"

    /** 外观值域：模糊 0–40 dp，亮度 20–100（%），越界一律夹取。 */
    const val BACKGROUND_BLUR_MAX = 40
    const val BACKGROUND_BRIGHTNESS_MIN = 20
    const val BACKGROUND_BRIGHTNESS_MAX = 100

    /** 云端语音识别默认服务：硅基流动（OpenAI 兼容转写接口）。 */
    const val STT_CLOUD_DEFAULT_BASE_URL = "https://api.siliconflow.cn/v1"
    const val STT_CLOUD_DEFAULT_MODEL = "FunAudioLLM/SenseVoiceSmall"

    private var prefs: SharedPreferences? = null

    /** 横持时是否旋转界面。关掉 = 完全锁定竖屏（传感器不再驱动旋转）。 */
    val rotateWithDevice = mutableStateOf(true)

    val rotationSensitivity = mutableStateOf(RotationSensitivity.Standard)

    /** 思考强度档：跨启动持久。 */
    val thinkingLevel = mutableIntStateOf(2)

    /** 上次选中的模型 id，用于启动后补发 `/api/models/apply` 对齐内核通道（避免退回历史选择）。 */
    val lastModel = mutableStateOf("")

    /** 最近一次权限档（内核未就绪时兜底显示）。 */
    val lastPermission = mutableStateOf("Ask")

    /** 深色模式：0 跟随系统 / 1 浅色 / 2 深色。 */
    val darkMode = mutableStateOf(0)

    /** 是否已执行过首次供应商预置，防止用户删除后重启又自动补回。 */
    val providerSeeded = mutableStateOf(false)

    /** 输入框外观（default / black / white / clear），仅作用于原生绘制层。 */
    val inputBoxStyle = mutableStateOf("default")

    /** 自定义背景图在应用私有目录下的绝对路径（空串表示无背景）。 */
    val backgroundImagePath = mutableStateOf("")

    val backgroundBlur = mutableIntStateOf(0)
    val backgroundBrightness = mutableIntStateOf(100)

    /** 文字颜色覆盖值（`#RRGGBB`；空串表示跟随背景亮度自动黑白）。 */
    val textColorOverride = mutableStateOf("")

    /** 是否显示系统状态栏（默认 false 隐藏，隐藏时顶部安全区 inset 自动归零）。 */
    val showStatusBar = mutableStateOf(false)

    /** 云端语音识别配置（OpenAI 兼容 `/audio/transcriptions`，与聊天模型供应商隔离存储，见 P-060）。 */
    val sttCloudEnabled = mutableStateOf(false)
    val sttCloudApiKey = mutableStateOf("")
    val sttCloudBaseUrl = mutableStateOf(STT_CLOUD_DEFAULT_BASE_URL)
    val sttCloudModel = mutableStateOf(STT_CLOUD_DEFAULT_MODEL)

    /** 是否剥离转写正文中的情感/事件标记。 */
    val sttCloudClean = mutableStateOf(true)

    /** 上次打开的会话 id，供冷启动恢复。 */
    val lastSessionId = mutableStateOf("")

    /** 任务完成系统通知开关。 */
    val notifyEnabled = mutableStateOf(true)

    fun init(context: Context) {
        if (prefs != null) return
        // 使用基于 Keystore 的 EncryptedSharedPreferences 加密敏感配置；
        // 首启将旧明文配置整体迁入后删除旧文件，Keystore 异常时回退明文避免丢配置。
        @Suppress("DEPRECATION") val p = runCatching {
            val enc = androidx.security.crypto.EncryptedSharedPreferences.create(
                context,
                PREFS_NAME + "_enc",
                androidx.security.crypto.MasterKey.Builder(context)
                    .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                    .build(),
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            // 明文→加密一次性迁移：enc 里已有的键优先（新值），缺的从明文补
            val legacy = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (legacy.all.isNotEmpty()) {
                val encEditor = enc.edit()
                for ((k, v) in legacy.all) {
                    if (!enc.contains(k)) {
                        when (v) {
                            is Boolean -> encEditor.putBoolean(k, v)
                            is String -> encEditor.putString(k, v)
                            is Int -> encEditor.putInt(k, v)
                            is Long -> encEditor.putLong(k, v)
                            is Float -> encEditor.putFloat(k, v)
                        }
                    }
                }
                encEditor.apply()
                legacy.edit().clear().apply()
            }
            enc
        }.getOrElse {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
        prefs = p
        rotateWithDevice.value = p.getBoolean(KEY_ROTATE_WITH_DEVICE, true)
        rotationSensitivity.value = runCatching {
            RotationSensitivity.valueOf(
                p.getString(KEY_ROTATION_SENSITIVITY, null) ?: RotationSensitivity.Standard.name,
            )
        }.getOrDefault(RotationSensitivity.Standard)
        thinkingLevel.intValue = p.getInt("thinking_level", 2)
        lastPermission.value = p.getString("last_permission", "Ask") ?: "Ask"
        lastModel.value = p.getString("last_model", null) ?: ""
        darkMode.value = p.getInt(KEY_DARK_MODE, 0)
        providerSeeded.value = p.getBoolean(KEY_PROVIDER_SEEDED, false)
        inputBoxStyle.value = p.getString(KEY_INPUT_BOX_STYLE, "default") ?: "default"
        backgroundImagePath.value = p.getString(KEY_BACKGROUND_IMAGE, null) ?: ""
        backgroundBlur.intValue = p.getInt(KEY_BACKGROUND_BLUR, 0)
            .coerceIn(0, BACKGROUND_BLUR_MAX)
        backgroundBrightness.intValue = p.getInt(KEY_BACKGROUND_BRIGHTNESS, 100)
            .coerceIn(BACKGROUND_BRIGHTNESS_MIN, BACKGROUND_BRIGHTNESS_MAX)
        textColorOverride.value = p.getString(KEY_TEXT_COLOR_OVERRIDE, null) ?: ""
        showStatusBar.value = p.getBoolean(KEY_SHOW_STATUS_BAR, false)
        sttCloudEnabled.value = p.getBoolean(KEY_STT_CLOUD_ENABLED, false)
        sttCloudApiKey.value = p.getString(KEY_STT_CLOUD_API_KEY, null) ?: ""
        // 空串视为未配置：走默认地址/模型，别让清空输入框把语音链路弄成不可用
        sttCloudBaseUrl.value = p.getString(KEY_STT_CLOUD_BASE_URL, null)
            ?.takeIf { it.isNotBlank() } ?: STT_CLOUD_DEFAULT_BASE_URL
        sttCloudModel.value = p.getString(KEY_STT_CLOUD_MODEL, null)
            ?.takeIf { it.isNotBlank() } ?: STT_CLOUD_DEFAULT_MODEL
        sttCloudClean.value = p.getBoolean(KEY_STT_CLOUD_CLEAN, true)
        lastSessionId.value = p.getString(KEY_LAST_SESSION_ID, null) ?: ""
        notifyEnabled.value = p.getBoolean(KEY_NOTIFY_ENABLED, true)
    }

    /** 标记首次供应商预置已完成；之后任何空列表都不再自动补 DeepSeek。 */
    fun markProviderSeeded() {
        if (providerSeeded.value) return
        providerSeeded.value = true
        prefs?.edit()?.putBoolean(KEY_PROVIDER_SEEDED, true)?.apply()
    }

    fun setDarkMode(mode: Int) {
        darkMode.value = mode
        prefs?.edit()?.putInt(KEY_DARK_MODE, mode)?.apply()
    }


    fun setRotateWithDevice(enabled: Boolean) {
        rotateWithDevice.value = enabled
        prefs?.edit()?.putBoolean(KEY_ROTATE_WITH_DEVICE, enabled)?.apply()
    }

    fun setRotationSensitivity(value: RotationSensitivity) {
        rotationSensitivity.value = value
        prefs?.edit()?.putString(KEY_ROTATION_SENSITIVITY, value.name)?.apply()
    }

    /** 思考强度档持久化（模型弹层选过即记，重启不回默认）。 */
    fun setThinkingLevel(level: Int) {
        thinkingLevel.intValue = level
        prefs?.edit()?.putInt("thinking_level", level)?.apply()
    }

    /** 最近权限档持久化（内核未就绪时的 UI 兜底）。 */
    fun setLastModel(id: String) {
        lastModel.value = id
        prefs?.edit()?.putString("last_model", id)?.apply()
    }

    fun setLastPermission(mode: String) {
        lastPermission.value = mode
        prefs?.edit()?.putString("last_permission", mode)?.apply()
    }

    /** 外观：输入框外观档（default / black / white / clear）。 */
    fun setInputBoxStyle(style: String) {
        val v = style.lowercase().takeIf { it in setOf("default", "black", "white", "clear") }
            ?: "default"
        inputBoxStyle.value = v
        prefs?.edit()?.putString(KEY_INPUT_BOX_STYLE, v)?.apply()
    }

    /** 外观：自定义背景图路径（空串 = 清除背景）。 */
    fun setBackgroundImage(path: String) {
        backgroundImagePath.value = path
        prefs?.edit()?.putString(KEY_BACKGROUND_IMAGE, path)?.apply()
    }

    fun setBackgroundBlur(value: Int) {
        val v = value.coerceIn(0, BACKGROUND_BLUR_MAX)
        backgroundBlur.intValue = v
        prefs?.edit()?.putInt(KEY_BACKGROUND_BLUR, v)?.apply()
    }

    fun setBackgroundBrightness(value: Int) {
        val v = value.coerceIn(BACKGROUND_BRIGHTNESS_MIN, BACKGROUND_BRIGHTNESS_MAX)
        backgroundBrightness.intValue = v
        prefs?.edit()?.putInt(KEY_BACKGROUND_BRIGHTNESS, v)?.apply()
    }

    /** 外观：文字颜色覆盖（`#RRGGBB`，空串 = 自动）。 */
    fun setTextColorOverride(value: String) {
        val v = value.trim()
        textColorOverride.value = v
        prefs?.edit()?.putString(KEY_TEXT_COLOR_OVERRIDE, v)?.apply()
    }

    /** 系统状态栏显隐（MainActivity 的 LaunchedEffect 读它来 show/hide）。 */
    fun setShowStatusBar(enabled: Boolean) {
        showStatusBar.value = enabled
        prefs?.edit()?.putBoolean(KEY_SHOW_STATUS_BAR, enabled)?.apply()
    }

    /** 语音：是否启用云端识别（与聊天供应商无关，见 [sttCloudEnabled]）。 */
    fun setSttCloudEnabled(enabled: Boolean) {
        sttCloudEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_STT_CLOUD_ENABLED, enabled)?.apply()
    }

    fun setSttCloudApiKey(key: String) {
        val v = key.trim()
        sttCloudApiKey.value = v
        prefs?.edit()?.putString(KEY_STT_CLOUD_API_KEY, v)?.apply()
    }

    /** 语音：服务地址（空串落盘后 init 会回默认，运行时也用默认兜底）。 */
    fun setSttCloudBaseUrl(url: String) {
        val v = url.trim()
        sttCloudBaseUrl.value = v
        prefs?.edit()?.putString(KEY_STT_CLOUD_BASE_URL, v)?.apply()
    }

    fun setSttCloudModel(model: String) {
        val v = model.trim()
        sttCloudModel.value = v
        prefs?.edit()?.putString(KEY_STT_CLOUD_MODEL, v)?.apply()
    }

    fun setSttCloudClean(enabled: Boolean) {
        sttCloudClean.value = enabled
        prefs?.edit()?.putBoolean(KEY_STT_CLOUD_CLEAN, enabled)?.apply()
    }

    /** 记录上次打开的会话 id（冷启动恢复用，见 [lastSessionId]）。 */
    fun setLastSessionId(id: String) {
        if (lastSessionId.value == id) return
        lastSessionId.value = id
        prefs?.edit()?.putString(KEY_LAST_SESSION_ID, id)?.apply()
    }

    /** 任务完成通知开关（壳侧发送，见 [notifyEnabled]）。 */
    fun setNotifyEnabled(enabled: Boolean) {
        notifyEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_NOTIFY_ENABLED, enabled)?.apply()
    }

}

