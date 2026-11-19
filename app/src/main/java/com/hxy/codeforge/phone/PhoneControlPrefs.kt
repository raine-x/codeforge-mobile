package com.hxy.codeforge.phone

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 手机操控配置的持久化与观察中心（锚定 `SharedPreferences("phone_controls")`）。
 *
 * 自动兼容早期测试副本遗留键名（`volume` / `wifi` / `bluetooth` / `gestures` / `observe` / `excluded_packages`），
 * 不丢失用户已勾选的应用黑名单。
 */
object PhoneControlPrefs {

    private const val PREF_NAME = "phone_controls"
    private const val KEY_MASTER = "master_enabled"
    private const val KEY_EXCLUDED = "excluded_packages"
    private const val KEY_ACTION_ANIM = "show_action_animation"
    private const val KEY_APP_SKILLS_ENABLED = "app_skills_enabled"
    private const val KEY_ALLOW_SKILL_SAVE = "allow_skill_self_learning"
    private const val KEY_DISABLED_APP_SKILLS = "disabled_app_skills"
    private const val KEY_RANDOM_CLICK_DELAY = "random_click_delay_enabled"
    private const val KEY_CLICK_DELAY_PRESET = "click_delay_preset"
    private const val KEY_AUTO_RETURN_APP = "auto_return_to_app_on_finish"
    private const val KEY_AUTO_STOP_COMPLETION = "auto_stop_on_completion"
    private const val KEY_MIGRATED_V2 = "migrated_v2"

    private val _config = MutableStateFlow(PhoneControlConfig())
    val config: StateFlow<PhoneControlConfig> = _config.asStateFlow()

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        val sp = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs = sp
        migrateLegacyIfNeeded(sp)
        _config.value = readConfig(sp)
    }

    private fun requirePrefs(context: Context? = null): SharedPreferences? {
        if (prefs == null && context != null) {
            init(context)
        }
        return prefs
    }

    private fun migrateLegacyIfNeeded(sp: SharedPreferences) {
        if (sp.getBoolean(KEY_MIGRATED_V2, false)) return
        val editor = sp.edit()
        // 兼容旧测试副本：如果用户曾显式打开任一旧开关，但还没设过新 key，沿用其语义
        if (!sp.contains(PhoneCapability.SYSTEM_SETTINGS.key)) {
            val anyLegacySys = sp.getBoolean("volume", false) ||
                sp.getBoolean("wifi", false) ||
                sp.getBoolean("bluetooth", false)
            if (anyLegacySys) editor.putBoolean(PhoneCapability.SYSTEM_SETTINGS.key, true)
        }
        if (!sp.contains(PhoneCapability.UI_ACTION.key) && sp.contains("gestures")) {
            editor.putBoolean(PhoneCapability.UI_ACTION.key, sp.getBoolean("gestures", true))
        }
        if (!sp.contains(PhoneCapability.SCREEN_OBSERVE.key) && sp.contains("observe")) {
            editor.putBoolean(PhoneCapability.SCREEN_OBSERVE.key, sp.getBoolean("observe", true))
        }
        editor.putBoolean(KEY_MIGRATED_V2, true).apply()
    }

    private fun readConfig(sp: SharedPreferences): PhoneControlConfig {
        val caps = PhoneCapability.entries.associateWith { cap ->
            sp.getBoolean(cap.key, cap.defaultEnabled)
        }
        val confirms = PhoneConfirmRule.entries.associateWith { rule ->
            sp.getBoolean(rule.key, rule.defaultEnabled)
        }
        val excluded = sp.getStringSet(KEY_EXCLUDED, emptySet()).orEmpty().toSet()
        val disabledSkills = sp.getStringSet(KEY_DISABLED_APP_SKILLS, emptySet()).orEmpty().toSet()
        return PhoneControlConfig(
            masterEnabled = sp.getBoolean(KEY_MASTER, false),
            capabilities = caps,
            excludedPackages = excluded,
            confirmations = confirms,
            showActionAnimation = sp.getBoolean(KEY_ACTION_ANIM, true),
            appSkillsEnabled = sp.getBoolean(KEY_APP_SKILLS_ENABLED, true),
            allowSkillSelfLearning = sp.getBoolean(KEY_ALLOW_SKILL_SAVE, true),
            disabledAppSkillIds = disabledSkills,
            randomClickDelayEnabled = sp.getBoolean(KEY_RANDOM_CLICK_DELAY, true),
            clickDelayPreset = PhoneClickDelayPreset.fromKey(sp.getString(KEY_CLICK_DELAY_PRESET, null)),
            autoReturnToAppOnFinish = sp.getBoolean(KEY_AUTO_RETURN_APP, true),
            autoStopOnCompletion = sp.getBoolean(KEY_AUTO_STOP_COMPLETION, true),
        )
    }

    fun setMasterEnabled(context: Context, enabled: Boolean) {
        val sp = requirePrefs(context) ?: return
        sp.edit().putBoolean(KEY_MASTER, enabled).apply()
        _config.value = readConfig(sp)
    }

    fun setCapability(context: Context, capability: PhoneCapability, enabled: Boolean) {
        val sp = requirePrefs(context) ?: return
        sp.edit().putBoolean(capability.key, enabled).apply()
        _config.value = readConfig(sp)
    }

    fun setAllCapabilities(context: Context, values: Map<PhoneCapability, Boolean>) {
        val sp = requirePrefs(context) ?: return
        val editor = sp.edit()
        values.forEach { (cap, v) -> editor.putBoolean(cap.key, v) }
        editor.apply()
        _config.value = readConfig(sp)
    }

    fun setExcludedPackages(context: Context, packages: Set<String>) {
        val sp = requirePrefs(context) ?: return
        sp.edit().putStringSet(KEY_EXCLUDED, packages.toSet()).apply()
        _config.value = readConfig(sp)
    }

    fun toggleExcludedPackage(context: Context, pkg: String, excluded: Boolean) {
        val sp = requirePrefs(context) ?: return
        val current = sp.getStringSet(KEY_EXCLUDED, emptySet()).orEmpty().toMutableSet()
        if (excluded) current.add(pkg) else current.remove(pkg)
        sp.edit().putStringSet(KEY_EXCLUDED, current).apply()
        _config.value = readConfig(sp)
    }

    fun setConfirmRule(context: Context, rule: PhoneConfirmRule, enabled: Boolean) {
        val sp = requirePrefs(context) ?: return
        sp.edit().putBoolean(rule.key, enabled).apply()
        _config.value = readConfig(sp)
    }

    fun setShowActionAnimation(context: Context, enabled: Boolean) {
        val sp = requirePrefs(context) ?: return
        sp.edit().putBoolean(KEY_ACTION_ANIM, enabled).apply()
        _config.value = readConfig(sp)
    }

    fun setAppSkillsEnabled(context: Context, enabled: Boolean) {
        val sp = requirePrefs(context) ?: return
        sp.edit().putBoolean(KEY_APP_SKILLS_ENABLED, enabled).apply()
        _config.value = readConfig(sp)
    }

    fun setAllowSkillSelfLearning(context: Context, allowed: Boolean) {
        val sp = requirePrefs(context) ?: return
        sp.edit().putBoolean(KEY_ALLOW_SKILL_SAVE, allowed).apply()
        _config.value = readConfig(sp)
    }

    fun setAppSkillEnabled(context: Context, skillId: String, enabled: Boolean) {
        val sp = requirePrefs(context) ?: return
        val current = sp.getStringSet(KEY_DISABLED_APP_SKILLS, emptySet()).orEmpty().toMutableSet()
        if (enabled) current.remove(skillId) else current.add(skillId)
        sp.edit().putStringSet(KEY_DISABLED_APP_SKILLS, current).apply()
        _config.value = readConfig(sp)
    }

    fun setRandomClickDelayEnabled(context: Context, enabled: Boolean) {
        val sp = requirePrefs(context) ?: return
        sp.edit().putBoolean(KEY_RANDOM_CLICK_DELAY, enabled).apply()
        _config.value = readConfig(sp)
    }

    fun setClickDelayPreset(context: Context, preset: PhoneClickDelayPreset) {
        val sp = requirePrefs(context) ?: return
        sp.edit().putString(KEY_CLICK_DELAY_PRESET, preset.key).apply()
        _config.value = readConfig(sp)
    }

    fun setAutoReturnToAppOnFinish(context: Context, enabled: Boolean) {
        val sp = requirePrefs(context) ?: return
        sp.edit().putBoolean(KEY_AUTO_RETURN_APP, enabled).apply()
        _config.value = readConfig(sp)
    }

    fun setAutoStopOnCompletion(context: Context, enabled: Boolean) {
        val sp = requirePrefs(context) ?: return
        sp.edit().putBoolean(KEY_AUTO_STOP_COMPLETION, enabled).apply()
        _config.value = readConfig(sp)
    }

    /**
     * 计算下一次点击/连续动作前的随机拟人延时（毫秒）。
     * 关闭随机延时时返回 0L。
     */
    fun nextRandomPreClickDelayMs(cfg: PhoneControlConfig = _config.value): Long {
        if (!cfg.randomClickDelayEnabled) return 0L
        val min = cfg.clickDelayPreset.minPreDelayMs
        val max = cfg.clickDelayPreset.maxPreDelayMs.coerceAtLeast(min + 1L)
        return kotlin.random.Random.nextLong(min, max + 1L)
    }

    /**
     * 计算单次屏幕触摸按压时长（毫秒）。
     * 开启随机延时时按预设区间（如 95–150ms）随机取值，关闭时取 105ms（比原固定 70ms 更稳，防高刷屏/电商组件丢点击）。
     */
    fun nextRandomTapHoldMs(cfg: PhoneControlConfig = _config.value): Long {
        if (!cfg.randomClickDelayEnabled) return 105L
        val min = cfg.clickDelayPreset.minTapHoldMs
        val max = cfg.clickDelayPreset.maxTapHoldMs.coerceAtLeast(min + 1L)
        return kotlin.random.Random.nextLong(min, max + 1L)
    }

    /**
     * 在控件安全边界内为点击坐标加入微小自然偏移（±maxJitterPx），绝不越出控件内缩边界。
     */
    internal fun jitterCoordinateInBounds(
        center: Float,
        minBound: Int,
        maxBound: Int,
        enabled: Boolean = _config.value.randomClickDelayEnabled,
        maxJitterPx: Float = 3f,
    ): Float {
        if (!enabled) return center
        val span = maxBound - minBound
        if (span < 20) return center
        val safeMargin = 6f
        val low = (minBound + safeMargin).coerceAtMost(center)
        val high = (maxBound - safeMargin).coerceAtLeast(center)
        val offset = (kotlin.random.Random.nextFloat() * 2f - 1f) * maxJitterPx
        return (center + offset).coerceIn(low, high)
    }

    /** 刷新内存中配置（供 Instrumentation / 外部直接写 prefs 场景调用）。 */
    fun reload(context: Context) {
        val sp = requirePrefs(context) ?: return
        _config.value = readConfig(sp)
    }

    /**
     * 启发式识别可能涉及支付/转账/扣费的高风险动作（纯函数，可直接单测）。
     */
    internal fun isLikelyPaymentAction(actionText: String, packageName: String = ""): Boolean {
        val textLower = actionText.lowercase()
        val pkgLower = packageName.lowercase()
        val paymentKeywords = listOf(
            "确认支付", "立即支付", "去支付", "付款", "转账", "确认转账", "免密支付",
            "打款", "充值", "结算", "收银台", "支付密码", "指纹支付", "刷脸支付",
            "pay now", "confirm payment", "checkout", "transfer money", "send money",
        )
        if (paymentKeywords.any { textLower.contains(it) }) return true

        val paymentPackages = listOf(
            "com.eg.android.alipaygphone",
            "com.unionpay",
            "com.paypal.android",
        )
        val payButtonVerbs = listOf("支付", "付款", "转账", "确认", "pay", "transfer", "confirm")
        if (pkgLower in paymentPackages && payButtonVerbs.any { textLower.contains(it) }) {
            return true
        }
        return false
    }
}
