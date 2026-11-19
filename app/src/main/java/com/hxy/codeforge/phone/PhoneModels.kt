package com.hxy.codeforge.phone

import android.graphics.Bitmap

/**
 * 手机操控能力域开关（设置 → 手机操控 → 能力开关）。
 *
 * 与工具分发器里的校验一一对应：关闭某一能力域后，即便模型尝试调用对应工具，
 * 分发器也会立即拦截并返回明确的权限提示。
 */
enum class PhoneCapability(
    val key: String,
    val label: String,
    val summary: String,
    val defaultEnabled: Boolean = true,
) {
    SCREEN_OBSERVE("cap_screen_observe", "屏幕观察", "读取前台应用、窗口控件树与界面变化", true),
    UI_ACTION("cap_ui_action", "界面操作", "点击、双击、长按、滑动、拖拽、输入与全局导航", true),
    APP_MANAGE("cap_app_manage", "应用管理", "查询已安装应用、启动、切换与关闭应用", true),
    SYSTEM_SETTINGS("cap_system_settings", "系统设置", "查询与调节音量、亮度、旋转、勿扰、Wi-Fi、蓝牙与定位", true),
    SCREENSHOT("cap_screenshot", "屏幕截图", "截取当前屏幕或窗口并保存到工作区与产物列表", true),
    SCREEN_RECORD("cap_screen_record", "屏幕录制", "通过系统授权录制操作过程并保存视频", true),
    AUDIO_RECORD("cap_audio_record", "麦克风录音", "开始、暂停、继续、停止录音并保存音频", true),
    NOTIFICATIONS("cap_notifications", "通知管理", "读取可见通知、展开通知栏、点击动作或清除通知", true),
    FILES_MEDIA("cap_files_media", "文件与媒体", "管理、导出、重命名、打开与分享媒体和工作区文件", true),
    CALENDAR_ALARM("cap_calendar_alarm", "日程与提醒", "查询和创建日历日程、闹钟与计时器", true),
    COMMUNICATION("cap_communication", "通讯与分享", "查询联系人、打开拨号、起草/发送短信与系统分享", true),
    DEVICE_STATUS("cap_device_status", "设备状态", "读取电量、网络、存储空间、音量与屏幕锁屏状态", true),
    ;

    companion object {
        fun fromKey(key: String): PhoneCapability? = entries.firstOrNull { it.key == key }
    }
}

/**
 * 敏感操作人工确认规则（设置 → 手机操控 → 确认设置）。
 */
enum class PhoneConfirmRule(
    val key: String,
    val label: String,
    val summary: String,
    val defaultEnabled: Boolean,
) {
    SEND_MESSAGE("confirm_send_message", "发送消息与对外分享", "发送短信或调起对外分享前请求确认", true),
    DELETE_FILE("confirm_delete_file", "删除文件", "删除手机或工作区文件前请求确认", true),
    PAYMENT("confirm_payment", "支付与转账操作", "识别到支付、转账、付款或结账动作前请求确认", true),
    CALL_DIAL("confirm_call_dial", "拨打电话", "调起拨号盘或拨出号码前请求确认", true),
    CLOSE_APP("confirm_close_app", "关闭应用", "通过最近任务关闭后台应用前请求确认", false),
    SYSTEM_SETTINGS("confirm_system_settings", "修改系统设置", "更改音量、亮度、旋转、勿扰或网络开关前请求确认", false),
}

/** 随机点击延时与按压时长档位（兼顾防失效与拟人节奏）。 */
enum class PhoneClickDelayPreset(
    val key: String,
    val label: String,
    val minPreDelayMs: Long,
    val maxPreDelayMs: Long,
    val minTapHoldMs: Long,
    val maxTapHoldMs: Long,
) {
    FAST("fast", "快速 (60–160ms)", 60L, 160L, 85L, 125L),
    BALANCED("balanced", "稳健 (150–380ms)", 150L, 380L, 95L, 150L),
    CAUTIOUS("cautious", "慢速 (300–650ms)", 300L, 650L, 110L, 175L);

    companion object {
        fun fromKey(key: String?): PhoneClickDelayPreset =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: BALANCED
    }
}

/** 手机操控配置快照。 */
data class PhoneControlConfig(
    val masterEnabled: Boolean = false,
    val capabilities: Map<PhoneCapability, Boolean> = PhoneCapability.entries.associateWith { it.defaultEnabled },
    val excludedPackages: Set<String> = emptySet(),
    val confirmations: Map<PhoneConfirmRule, Boolean> = PhoneConfirmRule.entries.associateWith { it.defaultEnabled },
    val showActionAnimation: Boolean = true,
    val appSkillsEnabled: Boolean = true,
    val allowSkillSelfLearning: Boolean = true,
    val disabledAppSkillIds: Set<String> = emptySet(),
    val randomClickDelayEnabled: Boolean = true,
    val clickDelayPreset: PhoneClickDelayPreset = PhoneClickDelayPreset.BALANCED,
    val autoReturnToAppOnFinish: Boolean = true,
    val autoStopOnCompletion: Boolean = true,
) {
    fun isCapabilityEnabled(cap: PhoneCapability): Boolean = capabilities[cap] ?: cap.defaultEnabled
    fun isConfirmationRequired(rule: PhoneConfirmRule): Boolean = confirmations[rule] ?: rule.defaultEnabled
}

/** 任务运行态。 */
enum class PhoneTaskStatus(val label: String) {
    IDLE("空闲"),
    RUNNING("执行中"),
    PAUSED("已暂停"),
    STOPPED("已停止"),
}

/** 单条工具执行记录。 */
data class PhoneStepRecord(
    val id: String,
    val timestampMs: Long,
    val tool: String,
    val summary: String,
    val targetApp: String,
    val success: Boolean,
    val detail: String,
    val durationMs: Long = 0L,
    val artifactPath: String = "",
)

/** 手机操控执行状态快照。 */
data class PhoneTaskState(
    val status: PhoneTaskStatus = PhoneTaskStatus.IDLE,
    val currentTask: String = "",
    val currentAction: String = "",
    val thinkingPreview: String = "",
    val startupNotice: String = "",
    val startupNoticeNonce: Long = 0L,
    val stepCount: Int = 0,
    val lastResult: String = "",
    val lastResultError: Boolean = false,
    val updatedAtMs: Long = 0L,
    val steps: List<PhoneStepRecord> = emptyList(),
)

/** 待用户确认的高风险动作。 */
data class PendingPhoneConfirmation(
    val id: String,
    val rule: PhoneConfirmRule,
    val title: String,
    val detail: String,
    val targetApp: String,
    val createdAtMs: Long,
)

enum class PhoneArtifactType(val label: String) {
    SCREENSHOT("截图"),
    RECORDING("录屏"),
    AUDIO("录音"),
}

/** 手机操控产生的媒体产物。 */
data class PhoneArtifact(
    val id: String,
    val type: PhoneArtifactType,
    val fileName: String,
    val workspacePath: String,
    val absolutePath: String,
    val createdAtMs: Long,
    val sizeBytes: Long = 0L,
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
)

/** 权限总览快照。 */
data class PhonePermissionSnapshot(
    val accessibilityEnabled: Boolean = false,
    val screenCaptureAuthorized: Boolean = false,
    val microphoneGranted: Boolean = false,
    val notificationListenerEnabled: Boolean = false,
    val postNotificationsGranted: Boolean = true,
    val writeSettingsGranted: Boolean = false,
    val dndPolicyAccessGranted: Boolean = false,
    val calendarGranted: Boolean = false,
    val contactsGranted: Boolean = false,
    val smsGranted: Boolean = false,
)

/** 控件树节点快照。 */
data class UiNodeSnapshot(
    val ref: String,
    val packageName: String,
    val className: String,
    val text: String,
    val contentDesc: String,
    val resourceId: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val selected: Boolean,
    val focused: Boolean,
    val password: Boolean,
    val enabled: Boolean,
    val depth: Int,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
}

/** 当前屏幕控件观察快照。 */
data class ScreenSnapshot(
    val packageName: String,
    val appLabel: String,
    val className: String,
    val windowId: Int,
    val screenWidth: Int,
    val screenHeight: Int,
    val signature: String,
    val nodes: List<UiNodeSnapshot>,
    val truncated: Boolean = false,
    val capturedAtMs: Long = System.currentTimeMillis(),
)

/** 可选视觉模型配置（来自内核当前模型 + 供应商密钥，仅用于辅助 screen_analyze）。 */
data class VisionModelConfig(
    val baseUrl: String,
    val protocol: String,
    val model: String,
    val apiKey: String,
)

/** 本地 MCP HTTP 服务状态。 */
sealed class PhoneMcpStatus {
    data object Stopped : PhoneMcpStatus()
    data class Running(val port: Int, val endpoint: String, val toolCount: Int) : PhoneMcpStatus()
    data class Error(val message: String) : PhoneMcpStatus()
}

/** 兼容测试夹具的观测对象。 */
internal data class Observation(val message: String, val image: Bitmap? = null)

/** 应用操控技能来源。 */
enum class PhoneAppSkillSource(val label: String) {
    BUILTIN("内置"),
    CUSTOM("自定义"),
    WORKSPACE("工作区"),
}

/** 面向特定手机 App 的预置/手写操控经验技能。 */
data class PhoneAppSkill(
    val id: String,
    val name: String,
    val description: String,
    val packages: List<String> = emptyList(),
    val appNames: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val priority: Int = 0,
    val content: String,
    val source: PhoneAppSkillSource = PhoneAppSkillSource.BUILTIN,
    val filePath: String = "",
    val enabled: Boolean = true,
)
