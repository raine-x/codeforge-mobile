package app.codeforge.ui.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 旋转灵敏度：决定 [app.codeforge.ui.common.rememberDevicePose] 的切换迟滞角。
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
 * 必须先在 [app.codeforge.CodeForgeApp] 里 [init]，读默认值才有意义。
 */
object AppSettings {

    private const val PREFS_NAME = "app_settings"
    private const val KEY_ROTATE_WITH_DEVICE = "rotate_with_device"
    private const val KEY_ROTATION_SENSITIVITY = "rotation_sensitivity"
    private const val KEY_CONTINUOUS_DICTATION = "continuous_dictation"
    private const val KEY_DARK_MODE = "dark_mode"

    private var prefs: SharedPreferences? = null

    /** 横持时是否旋转界面。关掉 = 完全锁定竖屏（传感器不再驱动旋转）。 */
    val rotateWithDevice = mutableStateOf(true)

    val rotationSensitivity = mutableStateOf(RotationSensitivity.Standard)

    /**
     * 内置测试模型开关。
     * 开 = 聊天直连云端测试模型，不经过本机内核；关 = 内核优先，
     * 内核 Failed 或迟迟未就绪时自动兜底到测试模型（见 ChatViewModel）。
     *
     * 双载体：Compose State 供界面读写，StateFlow 供 ChatViewModel 订阅
     * （VM 不允许引 Compose，见 AGENTS 2.3-3）—— 两者只在 init/Setter 同步。
     */
    /** 思考强度档（模型弹层）：跨启动持久，选过就不再回默认。 */
    val thinkingLevel = mutableIntStateOf(2)
    /**
     * 用户上次选中的模型 id（模型库里的 id）。
     *
     * 内核只认 state.yaml / 模型库，壳侧光记一个字符串不会让它切通道；
     * 这个值用来在启动时补一次 `/api/models/apply`，否则重启后内核会退回
     * state.yaml 里的历史选择（真机表现：界面显示新模型、请求打向旧模型）。
     */
    val lastModel = mutableStateOf("")

    /** 最近一次权限档（内核未就绪时 UI 的兜底显示）。 */
    val lastPermission = mutableStateOf("Ask")

    /** 连续听写：听完一段自动续听，直到手动停止（B4 输入区）。 */
    val continuousDictation = mutableStateOf(false)

    /** 深色模式手动切换：0 跟随系统 / 1 浅色 / 2 深色（B4 设置页）。 */
    val darkMode = mutableStateOf(0)

    fun init(context: Context) {
        if (prefs != null) return
        // B5-5：敏感项（API Key 等）加密存储 —— Keystore 支撑的官方方案。
        // EncryptedSharedPreferences 的 API 在 1.1.0-alpha07 被标 deprecated，
        // 但官方尚无成熟替代（迁移指引进 DataStore+Tink 自实现），压警告继续用。
        // 首启把明文 prefs 的内容整体迁入加密库并删旧文件；失败（Keystore 损坏等
        // 罕见场景）回退明文，宁可弱存储也不能让设置全丢。
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
        continuousDictation.value = p.getBoolean(KEY_CONTINUOUS_DICTATION, false)
        darkMode.value = p.getInt(KEY_DARK_MODE, 0)
    }

    fun setContinuousDictation(enabled: Boolean) {
        continuousDictation.value = enabled
        prefs?.edit()?.putBoolean(KEY_CONTINUOUS_DICTATION, enabled)?.apply()
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

}

