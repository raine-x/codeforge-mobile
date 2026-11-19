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

    @Volatile
    private var historyFile: File? = null

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
        val clean = userPrompt.trim().take(120)
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
        val clean = userPrompt.trim().take(120)
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
