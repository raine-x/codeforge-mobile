package com.hxy.codeforge.phone

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

/**
 * 通知读取与动作执行服务（`NotificationListenerService`）。
 *
 * 读取通知时自动跳过黑名单应用的通知；支持打开通知正文、清除单条/全部可清除通知、
 * 以及触发通知上的快捷按钮（`Notification.Action.actionIntent`）。
 */
class PhoneNotificationService : NotificationListenerService() {

    companion object {
        @Volatile
        var active: PhoneNotificationService? = null
            private set

        fun isEnabledInSettings(context: Context): Boolean {
            if (active != null) return true
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            val expected = ComponentName(context, PhoneNotificationService::class.java).flattenToString()
            return flat.split(':').any { it.equals(expected, ignoreCase = true) }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        active = this
    }

    override fun onListenerDisconnected() {
        if (active === this) active = null
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        if (active === this) active = null
        super.onDestroy()
    }

    fun listVisibleNotifications(context: Context, packageFilter: String = "", limit: Int = 30): JSONArray {
        val arr = JSONArray()
        val items = runCatching { activeNotifications }.getOrNull().orEmpty()
        for (sbn in items) {
            if (arr.length() >= limit) break
            val pkg = sbn.packageName.orEmpty()
            if (PhoneScope.blocked(context, pkg)) continue
            if (packageFilter.isNotBlank() && !pkg.contains(packageFilter, ignoreCase = true)) continue
            arr.put(serializeSbn(context, sbn))
        }
        return arr
    }

    fun openNotification(context: Context, key: String): Result<String> {
        val sbn = findByKey(context, key)
            ?: return Result.failure(IllegalArgumentException("找不到指定通知或该通知属于黑名单应用"))
        val pi = sbn.notification?.contentIntent
            ?: return Result.failure(IllegalStateException("该通知没有可打开的点击意图"))
        return runCatching {
            pi.send()
            "已打开通知（${PhoneScope.appLabel(context, sbn.packageName)}）"
        }
    }

    fun clearNotification(context: Context, key: String): Result<String> {
        if (key.equals("all", ignoreCase = true)) {
            val all = runCatching { activeNotifications }.getOrNull().orEmpty()
            var cleared = 0
            all.forEach { sbn ->
                if (!PhoneScope.blocked(context, sbn.packageName) && sbn.isClearable) {
                    runCatching { cancelNotification(sbn.key); cleared++ }
                }
            }
            return Result.success("已请求清除 $cleared 条可清除通知")
        }
        val sbn = findByKey(context, key)
            ?: return Result.failure(IllegalArgumentException("找不到指定通知或该通知属于黑名单应用"))
        if (!sbn.isClearable) {
            return Result.failure(IllegalStateException("该通知为常驻不可清除通知"))
        }
        cancelNotification(sbn.key)
        return Result.success("已清除通知")
    }

    fun triggerNotificationAction(context: Context, key: String, actionIndex: Int): Result<String> {
        val sbn = findByKey(context, key)
            ?: return Result.failure(IllegalArgumentException("找不到指定通知或该通知属于黑名单应用"))
        val actions = sbn.notification?.actions.orEmpty()
        val action = actions.getOrNull(actionIndex)
            ?: return Result.failure(IllegalArgumentException("通知动作索引 $actionIndex 越界（共 ${actions.size} 个）"))
        val pi = action.actionIntent
            ?: return Result.failure(IllegalStateException("通知按钮没有可执行意图"))
        return runCatching {
            pi.send()
            "已触发通知按钮「${action.title}」"
        }
    }

    private fun findByKey(context: Context, key: String): StatusBarNotification? {
        val all = runCatching { activeNotifications }.getOrNull().orEmpty()
        return all.firstOrNull { sbn ->
            sbn.key == key && !PhoneScope.blocked(context, sbn.packageName)
        }
    }

    private fun serializeSbn(context: Context, sbn: StatusBarNotification): JSONObject {
        val n = sbn.notification
        val extras = n?.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras?.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val sub = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val actionsArr = JSONArray()
        n?.actions?.forEachIndexed { idx, act ->
            actionsArr.put(
                JSONObject().apply {
                    put("index", idx)
                    put("title", act.title?.toString().orEmpty())
                },
            )
        }
        return JSONObject().apply {
            put("key", sbn.key)
            put("package", sbn.packageName)
            put("app_name", PhoneScope.appLabel(context, sbn.packageName))
            put("title", title.take(120))
            put("text", text.take(280))
            put("sub_text", sub.take(80))
            put("post_time", sbn.postTime)
            put("ongoing", sbn.isOngoing)
            put("clearable", sbn.isClearable)
            put("actions", actionsArr)
        }
    }
}
