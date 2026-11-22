package com.hxy.codeforge.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.hxy.codeforge.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 手机操控任务执行状态、历史记录与暂停/继续/立即停止控制器。
 *
 * - 支持在聊天条、设置页及通知栏操作中暂停、继续或立即停止正在进行的手机操控循环；
 * - 当任务进入 [PhoneTaskStatus.PAUSED] 时，下一次工具调用会阻塞等待恢复或被停止；
 * - 当任务进入 [PhoneTaskStatus.STOPPED] 时，立即拒绝后续动作并联动中断当前 Agent 回合。
 */
object PhoneTaskController {

    private const val MAX_STEPS = 200
    private const val CHANNEL_ID = "phone_task_control_v2"
    private const val NOTIF_ID = 4001
    private const val NOTIF_TAG = "phone_task"
    private const val STARTUP_HINT_TEXT = "手机操控已开始 · 下拉通知栏可随时停止"

    const val ACTION_TASK_PAUSE = "com.hxy.codeforge.PHONE_TASK_PAUSE"
    const val ACTION_TASK_RESUME = "com.hxy.codeforge.PHONE_TASK_RESUME"
    const val ACTION_TASK_STOP = "com.hxy.codeforge.PHONE_TASK_STOP"

    private val _state = MutableStateFlow(PhoneTaskState())
    val state: StateFlow<PhoneTaskState> = _state.asStateFlow()

    private val lock = Any()
    private var noticeSeq = 0L
    private var thinkingRaw = StringBuilder()
    private var lastThinkingUiUpdateMs = 0L
    private var lastThinkingNotifUpdateMs = 0L

    /** 当前回合是否显式请求了手机操控（例如带 @phone）。 */
    @Volatile
    private var explicitPhoneTurn: Boolean = false

    /** 当前回合是否已完成/停止（同回合收尾后禁止再次误入操控模式）。 */
    @Volatile
    private var turnCompleted: Boolean = false

    /** 本轮完整用户输入，用于判断后续问题是否真的需要手机操控。 */
    @Volatile
    private var currentTurnPrompt: String = ""

    /** 回合成功收尾时的轨迹落盘钩子（由 PhoneToolDispatcher 挂入）。 */
    @Volatile
    var onTurnFlushing: (() -> Unit)? = null

    /** 新回合开始或强制停机时清理未落盘的临时轨迹。 */
    @Volatile
    var onTurnReset: (() -> Unit)? = null

    @Volatile
    private var historyFile: File? = null

    fun isExplicitPhoneTurn(): Boolean = explicitPhoneTurn
    fun isTurnCompleted(): Boolean = turnCompleted
    fun currentTurnPrompt(): String = currentTurnPrompt

    /**
     * 判断文本是否显式触发 `@phone` 手机操控模式（用于提前点亮跑马灯）。
     *
     * 「为什么手机操控又进去了」「介绍手机操控架构」这类讨论性文本**不应**提前进入操控态，
     * 只有明确带 `@phone` 或祈使型「用/开启/启动手机操控」才直接点亮。
     */
    internal fun isExplicitPhoneTrigger(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (t.contains("@phone", ignoreCase = true)) return true
        val metaDiscussion = META_QUESTION_REGEX.containsMatchIn(t)
        if (metaDiscussion) return false
        return EXPLICIT_PHONE_PHRASE_REGEX.containsMatchIn(t)
    }

    /**
     * 判断当前用户问题是否可能需要调用手机/设备操控工具（用于非 `@phone` 回合防误触发）。
     *
     * 上一轮刚用过 `@phone`，模型在历史上下文惯性下常在下一轮普通问答/写代码时
     * 顺手调 `screen_observe` 或 `device_status`，导致莫名重进手机操控模式。
     * 这里做轻量意图兜底：明确无设备操控诉求时直接在分发层拒掉，不亮跑马灯。
     */
    internal fun isPhoneControlLikelyNeeded(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (t.contains("@phone", ignoreCase = true)) return true
        // 若用户明确是在问代码/报错/机制/原因（且不含直接「打开某 App/截屏/设闹钟」等执行动作），放回普通问答
        val hasDirectExec = DIRECT_PHONE_EXEC_REGEX.containsMatchIn(t)
        if (hasDirectExec) return true
        if (META_QUESTION_REGEX.containsMatchIn(t)) return false
        return IMPLICIT_PHONE_ACTION_REGEX.containsMatchIn(t)
    }

    private val META_QUESTION_REGEX = Regex(
        "(为什么|为啥|怎么回事|什么原因|架构|原理|代码|源码|报错|崩溃|bug|readme|文档|介绍一下|解释一下)",
        RegexOption.IGNORE_CASE,
    )

    private val EXPLICIT_PHONE_PHRASE_REGEX = Regex(
        "((请|帮我|用|开启|进入|启动|执行).{0,6}(手机操控|操控手机|操作手机)|^(手机操控|操控手机)[：:,，\\s])",
    )

    private val DIRECT_PHONE_EXEC_REGEX = Regex(
        "((帮我|去|请|现在|立刻)?(打开|启动|切到|切换到|卸载|关闭).{0,10}(设置|微信|京东|淘宝|支付宝|高德|美团|b站|哔哩|抖音|浏览器|日历|时钟|相机|相册|短信|电话|应用|app|settings)" +
            "|截[个张下]?[屏图]|录[个段下]?[屏音]|take\\s+a?\\s*screenshot|open\\s+(android\\s+)?settings" +
            "|查(一下|看)?(当前)?(电量|电池|wifi|蓝牙|音量|亮度|存储|通知)|battery\\s+level" +
            "|(设|定|建|调)[个置]?.{0,14}(闹钟|日程|提醒|倒计时|音量|亮度|勿扰|蓝牙|wifi|wi-fi)" +
            "|发(一条)?(微信|短信)|打(个)?电话|拨号)",
        RegexOption.IGNORE_CASE,
    )

    private val IMPLICIT_PHONE_ACTION_REGEX = Regex(
        "(点[击按]屏幕|滑[动屏]|长按|拖拽|通知栏|桌面|通讯录|联系人|分享到|操控手机|操作手机|tap\\s+|swipe\\s+|click\\s+)",
        RegexOption.IGNORE_CASE,
    )

    fun init(context: Context) {
        val dir = File(context.applicationContext.filesDir, "phone_control").apply { mkdirs() }
        val f = File(dir, "history.json")
        historyFile = f
        val loaded = loadHistory(f)
        synchronized(lock) {
            _state.value = _state.value.copy(steps = loaded)
        }
    }

    /** 新对话发送前重置停机标记；仅记录任务摘要待工具调用时正式激活。 */
    fun prepareForTurn(userPrompt: String) {
        val full = userPrompt.trim()
        val clean = full.take(120)
        explicitPhoneTurn = false
        turnCompleted = false
        currentTurnPrompt = full
        runCatching { onTurnReset?.invoke() }
        synchronized(lock) {
            thinkingRaw.setLength(0)
            lastThinkingUiUpdateMs = 0L
            lastThinkingNotifUpdateMs = 0L
            val cur = _state.value
            _state.value = cur.copy(
                status = PhoneTaskStatus.IDLE,
                currentTask = clean,
                currentAction = "",
                thinkingPreview = "",
                startupNotice = "",
                stepCount = 0,
                updatedAtMs = System.currentTimeMillis(),
            )
        }
        cancelTaskNotification()
    }

    /** 发送 `@phone` 任务时立即进入运行态，点亮屏幕跑马灯、思考提示与可停止通知栏。 */
    fun activateForPhoneTurn(userPrompt: String) {
        val full = userPrompt.trim()
        val clean = full.take(120)
        explicitPhoneTurn = true
        turnCompleted = false
        currentTurnPrompt = full
        runCatching { onTurnReset?.invoke() }
        synchronized(lock) {
            thinkingRaw.setLength(0)
            lastThinkingUiUpdateMs = 0L
            lastThinkingNotifUpdateMs = 0L
            noticeSeq++
            val cur = _state.value
            _state.value = cur.copy(
                status = PhoneTaskStatus.RUNNING,
                currentTask = clean.ifBlank { "手机操控任务" },
                currentAction = "正在规划手机操作…",
                thinkingPreview = "",
                startupNotice = STARTUP_HINT_TEXT,
                startupNoticeNonce = noticeSeq,
                stepCount = 0,
                lastResult = "",
                lastResultError = false,
                updatedAtMs = System.currentTimeMillis(),
            )
        }
        syncNotificationIfNeeded(force = true)
    }

    /** 标记新动作开始并激活 RUNNING 状态。 */
    fun beginAction(tool: String, summary: String, targetApp: String) {
        synchronized(lock) {
            thinkingRaw.setLength(0)
            lastThinkingUiUpdateMs = 0L
            val cur = _state.value
            val enteringRun = cur.status != PhoneTaskStatus.RUNNING && cur.status != PhoneTaskStatus.PAUSED
            if (enteringRun) noticeSeq++
            val task = cur.currentTask.ifBlank { summary }
            _state.value = cur.copy(
                status = PhoneTaskStatus.RUNNING,
                currentTask = task,
                currentAction = summary,
                thinkingPreview = "",
                startupNotice = if (enteringRun) STARTUP_HINT_TEXT else cur.startupNotice,
                startupNoticeNonce = if (enteringRun) noticeSeq else cur.startupNoticeNonce,
                updatedAtMs = System.currentTimeMillis(),
            )
        }
        syncNotificationIfNeeded(force = true)
    }

    /** 追加模型思考增量并在手机操控执行期间实时投射到顶部提示与通知栏。 */
    fun appendThinkingDelta(delta: String) {
        if (delta.isEmpty()) return
        var notifyDue = false
        synchronized(lock) {
            val cur = _state.value
            if (cur.status != PhoneTaskStatus.RUNNING && cur.status != PhoneTaskStatus.PAUSED) return
            thinkingRaw.append(delta)
            if (thinkingRaw.length > 600) {
                thinkingRaw.delete(0, thinkingRaw.length - 600)
            }
            val now = System.currentTimeMillis()
            val hitBreak = delta.any { it == '\n' || it == '。' || it == '！' || it == '？' || it == '.' }
            if (cur.thinkingPreview.isNotBlank() && !hitBreak && now - lastThinkingUiUpdateMs < 120L) {
                return
            }
            val preview = extractThinkingPreview(thinkingRaw.toString())
            if (preview.isBlank()) return
            lastThinkingUiUpdateMs = now
            if (preview != cur.thinkingPreview) {
                _state.value = cur.copy(thinkingPreview = preview, updatedAtMs = now)
                if (now - lastThinkingNotifUpdateMs >= 1200L) {
                    lastThinkingNotifUpdateMs = now
                    notifyDue = true
                }
            }
        }
        if (notifyDue) {
            syncNotificationIfNeeded(force = false)
        }
    }

    internal fun extractThinkingPreview(raw: String, maxChars: Int = 68): String {
        val cleaned = raw
            .replace("```", " ")
            .replace(Regex("""[#*>`]+"""), " ")
            .replace('\r', '\n')
        val lines = cleaned.split('\n')
            .map { it.replace(Regex("""\s+"""), " ").trim() }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) return ""
        val joined = if (lines.last().length >= 16 || lines.size == 1) {
            lines.last()
        } else {
            (lines.getOrNull(lines.lastIndex - 1).orEmpty() + " " + lines.last()).trim()
        }
        return if (joined.length <= maxChars) joined else "…" + joined.takeLast(maxChars - 1)
    }

    /** 工具动作结束：写入步骤记录与最新结果回执。 */
    fun finishAction(
        tool: String,
        summary: String,
        targetApp: String,
        success: Boolean,
        detail: String,
        durationMs: Long,
        artifactPath: String = "",
    ) {
        val record = PhoneStepRecord(
            id = UUID.randomUUID().toString().take(10),
            timestampMs = System.currentTimeMillis(),
            tool = tool,
            summary = summary,
            targetApp = targetApp,
            success = success,
            detail = detail.take(500),
            durationMs = durationMs,
            artifactPath = artifactPath,
        )
        synchronized(lock) {
            val cur = _state.value
            val nextSteps = (listOf(record) + cur.steps).take(MAX_STEPS)
            val nextStatus = if (cur.status == PhoneTaskStatus.STOPPED) {
                PhoneTaskStatus.STOPPED
            } else if (cur.status == PhoneTaskStatus.PAUSED) {
                PhoneTaskStatus.PAUSED
            } else {
                PhoneTaskStatus.RUNNING
            }
            _state.value = cur.copy(
                status = nextStatus,
                stepCount = cur.stepCount + 1,
                currentAction = summary,
                lastResult = detail.take(200),
                lastResultError = !success,
                updatedAtMs = record.timestampMs,
                steps = nextSteps,
            )
            saveHistory(nextSteps)
        }
        syncNotificationIfNeeded(force = false)
    }

    fun reportStep(taskTitle: String?, stepSummary: String) {
        synchronized(lock) {
            val cur = _state.value
            _state.value = cur.copy(
                status = if (cur.status == PhoneTaskStatus.IDLE) PhoneTaskStatus.RUNNING else cur.status,
                currentTask = taskTitle?.takeIf { it.isNotBlank() } ?: cur.currentTask,
                currentAction = stepSummary.ifBlank { cur.currentAction },
                updatedAtMs = System.currentTimeMillis(),
            )
        }
        syncNotificationIfNeeded(force = false)
    }

    /** 当单轮对话完成（Agent Idle）时收口。 */
    fun markTurnCompleted() {
        runCatching { onTurnFlushing?.invoke() }
        explicitPhoneTurn = false
        turnCompleted = true
        val cfg = PhoneControlPrefs.config.value
        var shouldReturnToApp = false
        synchronized(lock) {
            thinkingRaw.setLength(0)
            val cur = _state.value
            val wasActive = cur.status == PhoneTaskStatus.RUNNING ||
                cur.status == PhoneTaskStatus.PAUSED ||
                (cur.stepCount > 0 && cur.startupNotice.isNotEmpty())
            if (wasActive && cfg.autoReturnToAppOnFinish) {
                shouldReturnToApp = true
            }
            if (cfg.autoStopOnCompletion && (cur.status == PhoneTaskStatus.RUNNING || cur.status == PhoneTaskStatus.PAUSED)) {
                _state.value = cur.copy(
                    status = PhoneTaskStatus.IDLE,
                    currentAction = "",
                    thinkingPreview = "",
                    startupNotice = "",
                    updatedAtMs = System.currentTimeMillis(),
                )
            }
        }
        if (cfg.autoStopOnCompletion) {
            cancelTaskNotification()
        }
        if (shouldReturnToApp) {
            bringCodeForgeToFront()
        }
    }

    /** 由 Agent 主动宣告任务完成或停止操控（不打断 Agent 当前最终回复生成）。 */
    fun completeByAgent(summary: String = "手机操控任务已完成", returnToAppOverride: Boolean? = null) {
        runCatching { onTurnFlushing?.invoke() }
        explicitPhoneTurn = false
        turnCompleted = true
        val cfg = PhoneControlPrefs.config.value
        synchronized(lock) {
            thinkingRaw.setLength(0)
            val cur = _state.value
            _state.value = cur.copy(
                status = PhoneTaskStatus.IDLE,
                currentAction = "",
                thinkingPreview = "",
                startupNotice = "",
                lastResult = summary.ifBlank { "手机操控任务已完成" },
                lastResultError = false,
                updatedAtMs = System.currentTimeMillis(),
            )
        }
        cancelTaskNotification()
        PhoneObservationService.active?.showOverlayBadge("手机操控已完成", isError = false)
        val shouldReturn = returnToAppOverride ?: cfg.autoReturnToAppOnFinish
        if (shouldReturn) {
            bringCodeForgeToFront()
        }
    }

    /** 将 CodeForge 主界面拉回前台（当手机操控在外部 App 结束，需要向用户展示结果时调用）。 */
    fun bringCodeForgeToFront(context: Context? = PhoneRuntime.appContext): Boolean {
        val ctx = context ?: return false
        val fg = PhoneObservationService.active?.foregroundPackage().orEmpty()
        if (fg == ctx.packageName) return true
        return runCatching {
            val intent = Intent(ctx, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                )
            }
            ctx.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    fun pause(reason: String = "已由用户暂停") {
        synchronized(lock) {
            val cur = _state.value
            _state.value = cur.copy(
                status = PhoneTaskStatus.PAUSED,
                lastResult = reason,
                lastResultError = false,
                updatedAtMs = System.currentTimeMillis(),
            )
        }
        syncNotificationIfNeeded(force = true)
    }

    fun resume() {
        synchronized(lock) {
            val cur = _state.value
            if (cur.status == PhoneTaskStatus.PAUSED || cur.status == PhoneTaskStatus.STOPPED) {
                _state.value = cur.copy(
                    status = PhoneTaskStatus.RUNNING,
                    lastResult = "任务已继续",
                    lastResultError = false,
                    updatedAtMs = System.currentTimeMillis(),
                )
            }
        }
        syncNotificationIfNeeded(force = true)
    }

    fun stop(reason: String = "用户已立即停止手机操控", stopAgentTurn: Boolean = true) {
        explicitPhoneTurn = false
        turnCompleted = true
        runCatching { onTurnReset?.invoke() }
        synchronized(lock) {
            thinkingRaw.setLength(0)
            val cur = _state.value
            _state.value = cur.copy(
                status = PhoneTaskStatus.STOPPED,
                currentAction = "",
                thinkingPreview = "",
                startupNotice = "",
                lastResult = reason,
                lastResultError = true,
                updatedAtMs = System.currentTimeMillis(),
            )
        }
        PhoneConfirmationGate.cancelAll(reason)
        PhoneMediaController.stopAllOnAbort()
        cancelTaskNotification()
        PhoneObservationService.active?.showOverlayBadge("已停止手机操控", isError = true)
        if (stopAgentTurn) {
            runCatching { PhoneRuntime.onStopAgentTurn?.invoke() }
        }
    }

    fun clearHistory() {
        synchronized(lock) {
            _state.value = _state.value.copy(
                steps = emptyList(),
                stepCount = 0,
                lastResult = "",
                lastResultError = false,
            )
            saveHistory(emptyList())
        }
    }

    /**
     * 在执行工具前检查是否处于 PAUSED / STOPPED。
     * 返回 null 表示允许继续，非空字符串表示拒绝原因。
     */
    suspend fun awaitRunnableOrReason(maxWaitMs: Long = 55_000L): String? {
        val started = System.currentTimeMillis()
        while (true) {
            when (_state.value.status) {
                PhoneTaskStatus.STOPPED -> return _state.value.lastResult.ifBlank { "手机操控任务已被停止" }
                PhoneTaskStatus.IDLE, PhoneTaskStatus.RUNNING -> return null
                PhoneTaskStatus.PAUSED -> {
                    if (System.currentTimeMillis() - started >= maxWaitMs) {
                        return "手机操控任务处于暂停状态，等待恢复超时"
                    }
                    delay(250L)
                }
            }
        }
    }

    private fun syncNotificationIfNeeded(force: Boolean = false) {
        val ctx = PhoneRuntime.appContext ?: return
        val st = _state.value
        if (st.status != PhoneTaskStatus.RUNNING && st.status != PhoneTaskStatus.PAUSED) {
            cancelTaskNotification()
            return
        }
        runCatching {
            val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(CHANNEL_ID, "手机操控常驻控制", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(null, null)
                    enableVibration(false)
                    description = "手机操控进行时常驻显示，可从通知栏直接暂停或停止"
                }
                mgr.createNotificationChannel(ch)
            }
            val openApp = PendingIntent.getActivity(
                ctx,
                110,
                Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val toggleAction = if (st.status == PhoneTaskStatus.PAUSED) {
                PendingIntent.getBroadcast(
                    ctx,
                    111,
                    Intent(ctx, PhoneActionReceiver::class.java).setAction(ACTION_TASK_RESUME),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            } else {
                PendingIntent.getBroadcast(
                    ctx,
                    111,
                    Intent(ctx, PhoneActionReceiver::class.java).setAction(ACTION_TASK_PAUSE),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }
            val stopAction = PendingIntent.getBroadcast(
                ctx,
                112,
                Intent(ctx, PhoneActionReceiver::class.java).setAction(ACTION_TASK_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(ctx, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(ctx)
            }
            val title = if (st.status == PhoneTaskStatus.PAUSED) {
                "手机操控已暂停（第 ${st.stepCount} 步）"
            } else {
                "手机操控执行中（第 ${st.stepCount} 步）"
            }
            val body = buildString {
                if (st.currentAction.isNotBlank()) append(st.currentAction)
                if (st.thinkingPreview.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append("思考：")
                    append(st.thinkingPreview)
                }
                if (isEmpty()) append(st.currentTask.ifBlank { "正在执行手机操控" })
            }
            val notif = builder
                .setContentTitle(title)
                .setContentText(body)
                .setSubText("点击下方「停止操控」可随时终止")
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(openApp)
                .setOnlyAlertOnce(!force)
                // 把「停止操控」放在首位，确保折叠通知也能直接点停
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止操控", stopAction)
                .addAction(
                    android.R.drawable.ic_media_pause,
                    if (st.status == PhoneTaskStatus.PAUSED) "继续" else "暂停",
                    toggleAction,
                )
                .setOngoing(true)
                .build()
            mgr.notify(NOTIF_TAG, NOTIF_ID, notif)
        }
    }

    fun cancelTaskNotification() {
        val ctx = PhoneRuntime.appContext ?: return
        runCatching {
            ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_TAG, NOTIF_ID)
        }
    }

    private fun loadHistory(file: File): List<PhoneStepRecord> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                PhoneStepRecord(
                    id = o.optString("id"),
                    timestampMs = o.optLong("timestamp_ms", 0L),
                    tool = o.optString("tool"),
                    summary = o.optString("summary"),
                    targetApp = o.optString("target_app"),
                    success = o.optBoolean("success", true),
                    detail = o.optString("detail"),
                    durationMs = o.optLong("duration_ms", 0L),
                    artifactPath = o.optString("artifact_path"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveHistory(steps: List<PhoneStepRecord>) {
        val file = historyFile ?: return
        runCatching {
            val arr = JSONArray()
            steps.forEach { s ->
                arr.put(
                    JSONObject().apply {
                        put("id", s.id)
                        put("timestamp_ms", s.timestampMs)
                        put("tool", s.tool)
                        put("summary", s.summary)
                        put("target_app", s.targetApp)
                        put("success", s.success)
                        put("detail", s.detail)
                        put("duration_ms", s.durationMs)
                        put("artifact_path", s.artifactPath)
                    },
                )
            }
            file.writeText(arr.toString())
        }
    }
}
