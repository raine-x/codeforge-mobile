package com.hxy.codeforge.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.hxy.codeforge.MainActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * 手机操控高风险动作的人工确认门禁。
 *
 * 相比依赖当前是否在 App 前台，本门禁同时驱动：
 * 1. 应用内不可透字弹窗（`AppRoot` 订阅 [pending]）；
 * 2. 通知栏 `IMPORTANCE_HIGH` heads-up 确认通知（用户在其他应用内操控屏幕时也能直接点击「允许/拒绝」）。
 *
 * `PendingIntent` requestCode 使用 `101` / `102` / `103`，避免与 `CoreService`（0..6）及任务条（110..112）冲突（P-056）。
 */
object PhoneConfirmationGate {

    enum class Outcome {
        APPROVED,
        REJECTED,
        TIMED_OUT,
        CANCELLED,
    }

    const val ACTION_CONFIRM_DECISION = "com.hxy.codeforge.PHONE_CONFIRM_DECISION"
    const val EXTRA_CONFIRM_ID = "confirm_id"
    const val EXTRA_CONFIRM_ALLOW = "confirm_allow"

    private const val CHANNEL_ID = "phone_confirm_high"
    private const val NOTIF_ID = 4002
    private const val NOTIF_TAG = "phone_confirm"

    private val _pending = MutableStateFlow<PendingPhoneConfirmation?>(null)
    val pending: StateFlow<PendingPhoneConfirmation?> = _pending.asStateFlow()

    private val waiters = mutableMapOf<String, CompletableDeferred<Outcome>>()
    private val lock = Any()

    suspend fun request(
        context: Context?,
        rule: PhoneConfirmRule,
        title: String,
        detail: String,
        targetApp: String = "",
        timeoutMs: Long = 60_000L,
    ): Outcome {
        val id = UUID.randomUUID().toString().take(12)
        val req = PendingPhoneConfirmation(
            id = id,
            rule = rule,
            title = title,
            detail = detail,
            targetApp = targetApp,
            createdAtMs = System.currentTimeMillis(),
        )
        val deferred = CompletableDeferred<Outcome>()
        synchronized(lock) {
            // 新请求顶掉悬而未决的旧请求时，旧请求按取消收口
            val old = _pending.value
            if (old != null) {
                waiters.remove(old.id)?.complete(Outcome.CANCELLED)
            }
            waiters[id] = deferred
            _pending.value = req
        }
        if (context != null) {
            postNotification(context, req)
        }
        val outcome = withTimeoutOrNull(timeoutMs) { deferred.await() } ?: Outcome.TIMED_OUT
        synchronized(lock) {
            waiters.remove(id)
            if (_pending.value?.id == id) {
                _pending.value = null
            }
        }
        if (context != null) {
            cancelNotification(context)
        }
        return outcome
    }

    fun resolve(id: String, allow: Boolean) {
        val outcome = if (allow) Outcome.APPROVED else Outcome.REJECTED
        synchronized(lock) {
            waiters.remove(id)?.complete(outcome)
            if (_pending.value?.id == id) {
                _pending.value = null
            }
        }
        PhoneRuntime.appContext?.let { cancelNotification(it) }
    }

    fun cancelAll(reason: String = "") {
        synchronized(lock) {
            val all = waiters.values.toList()
            waiters.clear()
            _pending.value = null
            all.forEach { it.complete(Outcome.CANCELLED) }
        }
        PhoneRuntime.appContext?.let { cancelNotification(it) }
    }

    private fun postNotification(context: Context, req: PendingPhoneConfirmation) {
        runCatching {
            val mgr = context.getSystemService(NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "手机操控危险操作确认", NotificationManager.IMPORTANCE_HIGH),
                )
            }
            val open = PendingIntent.getActivity(
                context,
                103,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            fun decidePi(allow: Boolean, code: Int): PendingIntent = PendingIntent.getBroadcast(
                context,
                code,
                Intent(context, PhoneActionReceiver::class.java)
                    .setAction(ACTION_CONFIRM_DECISION)
                    .putExtra(EXTRA_CONFIRM_ID, req.id)
                    .putExtra(EXTRA_CONFIRM_ALLOW, allow),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context).setPriority(Notification.PRIORITY_HIGH)
            }
            val body = listOf(req.title, req.detail, req.targetApp.takeIf { it.isNotBlank() }?.let { "目标应用：$it" })
                .filterNotNull()
                .filter { it.isNotBlank() }
                .joinToString("\n")
            val notif = builder
                .setContentTitle("手机操控需要确认 · ${req.rule.label}")
                .setContentText(req.title)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentIntent(open)
                .addAction(android.R.drawable.checkbox_on_background, "允许执行", decidePi(true, 101))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "拒绝", decidePi(false, 102))
                .setAutoCancel(false)
                .build()
            mgr.notify(NOTIF_TAG, NOTIF_ID, notif)
        }
    }

    private fun cancelNotification(context: Context) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_TAG, NOTIF_ID)
        }
    }
}
