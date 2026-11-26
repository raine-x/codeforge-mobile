package com.hxy.codeforge.phone

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.hxy.codeforge.core.EnvKit
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * 权限探测与系统能力执行集合：
 * - 权限状态快照
 * - 应用管理（列表、查找、启动、切换、通过最近任务关闭）
 * - 系统设置（音量、亮度、旋转、勿扰、Wi-Fi、蓝牙、热点、定位：支持版本自适应接口 + 回读实际结果 + 动画结果提示）
 * - 文件与媒体（列举、导出到 Download、选择器导入、移动、重命名、删除、打开、分享）
 * - 日历、闹钟与计时器
 * - 联系人查询、拨号、短信起草/发送与系统分享
 * - 设备状态查询
 */
internal object PhoneSystemCapabilities {

    fun permissionSnapshot(context: Context): PhonePermissionSnapshot {
        val accessibilityEnabled = PhoneObservationService.active != null || isAccessibilityServiceEnabled(context)
        val mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val postNotif = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        val cal = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
        val contacts = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        val sms = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

        return PhonePermissionSnapshot(
            accessibilityEnabled = accessibilityEnabled,
            screenCaptureAuthorized = PhoneMediaController.hasScreenCaptureConsent(),
            microphoneGranted = mic,
            notificationListenerEnabled = PhoneNotificationService.isEnabledInSettings(context),
            postNotificationsGranted = postNotif,
            writeSettingsGranted = Settings.System.canWrite(context),
            dndPolicyAccessGranted = nm?.isNotificationPolicyAccessGranted == true,
            calendarGranted = cal,
            contactsGranted = contacts,
            smsGranted = sms,
        )
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val flat = ComponentName(context, PhoneObservationService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(flat, ignoreCase = true) }
    }

    // 应用管理

    fun listInstalledApps(
        context: Context,
        query: String = "",
        includeSystem: Boolean = false,
        limit: Int = 80,
    ): JSONObject {
        val pm = context.packageManager
        val blocked = PhoneScope.blockedPackages(context)
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchablePkgs = pm.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()

        val items = pm.getInstalledApplications(0)
            .asSequence()
            .filter { info ->
                val isSys = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isLaunchable = info.packageName in launchablePkgs
                includeSystem || !isSys || isLaunchable
            }
            .map { info ->
                val label = pm.getApplicationLabel(info)?.toString().orEmpty().ifBlank { info.packageName }
                Triple(info.packageName, label, (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
            }
            .filter { (pkg, label, _) ->
                query.isBlank() ||
                    label.contains(query, ignoreCase = true) ||
                    pkg.contains(query, ignoreCase = true)
            }
            .sortedWith(
                compareBy<Triple<String, String, Boolean>>(
                    { !(it.second.equals(query, ignoreCase = true)) },
                    { it.third },
                    { it.second.lowercase() },
                ),
            )
            .take(limit.coerceIn(1, 200))
            .toList()

        val arr = JSONArray()
        items.forEach { (pkg, label, isSys) ->
            arr.put(
                JSONObject().apply {
                    put("package", pkg)
                    put("name", label)
                    put("system", isSys)
                    put("launchable", pkg in launchablePkgs)
                    put("blocked", pkg in blocked)
                },
            )
        }
        return JSONObject().apply {
            put("count", arr.length())
            put("apps", arr)
        }
    }

    suspend fun launchOrSwitchApp(context: Context, appName: String, packageName: String): Result<JSONObject> {
        val pm = context.packageManager
        val targetPkg = if (packageName.isNotBlank()) {
            packageName.trim()
        } else {
            val found = findBestLaunchablePackage(context, appName)
                ?: return Result.failure(IllegalArgumentException("未找到匹配「$appName」的可启动应用"))
            found.first
        }

        if (PhoneScope.blocked(context, targetPkg)) {
            val label = PhoneScope.appLabel(context, targetPkg)
            return Result.failure(IllegalStateException("应用「$label ($targetPkg)」位于禁止操控黑名单内，已拒绝启动"))
        }

        val launchIntent = pm.getLaunchIntentForPackage(targetPkg)
            ?: return Result.failure(IllegalStateException("应用 $targetPkg 没有可启动的主界面入口"))
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

        val starter: Context = PhoneObservationService.active ?: context
        starter.startActivity(launchIntent)
        val label = PhoneScope.appLabel(context, targetPkg)
        PhoneObservationService.active?.showOverlayBadge("已打开 $label")

        // 短暂等待切换生效后返回当前观测到的包名
        delay(650L)
        val fg = PhoneObservationService.active?.foregroundPackage().orEmpty()
        return Result.success(
            JSONObject().apply {
                put("target_package", targetPkg)
                put("target_name", label)
                put("foreground_package", fg)
            },
        )
    }

    suspend fun closeAppViaRecents(context: Context, appName: String, packageName: String): Result<JSONObject> {
        val service = PhoneObservationService.active
            ?: return Result.failure(IllegalStateException("通过最近任务关闭应用需要先启用无障碍服务"))
        val targetLabel = when {
            appName.isNotBlank() -> appName.trim()
            packageName.isNotBlank() -> PhoneScope.appLabel(context, packageName.trim())
            else -> ""
        }
        if (packageName.isNotBlank() && PhoneScope.blocked(context, packageName.trim())) {
            return Result.failure(IllegalStateException("目标应用在黑名单内"))
        }

        service.performGlobal("recents").getOrElse { return Result.failure(it) }
        delay(650L)
        val snap = service.captureScreenSnapshot().getOrElse { return Result.failure(it) }
        val dm = context.resources.displayMetrics

        // 查找包含目标应用名称的最近任务卡片标题或直接上滑当前卡片
        val matchedNode = if (targetLabel.isNotBlank()) {
            snap.nodes.firstOrNull {
                it.text.contains(targetLabel, ignoreCase = true) ||
                    it.contentDesc.contains(targetLabel, ignoreCase = true)
            }
        } else {
            null
        }
        val swipeX = (matchedNode?.centerX ?: (dm.widthPixels / 2)).toFloat()
        val startY = (dm.heightPixels * 0.58f)
        val endY = (dm.heightPixels * 0.14f)
        val swiped = service.performSwipe(swipeX, startY, swipeX, endY, 320L, "关闭 ${targetLabel.ifBlank { "当前任务" }}")
        delay(450L)
        return if (swiped) {
            Result.success(
                JSONObject().apply {
                    put("action", "close_via_recents")
                    put("target", targetLabel.ifBlank { "current" })
                    put("note", "已打开最近任务并上滑关闭卡片")
                },
            )
        } else {
            Result.failure(IllegalStateException("最近任务上滑关闭未能完成，可尝试手动查看最近任务界面"))
        }
    }

    private fun findBestLaunchablePackage(context: Context, query: String): Pair<String, String>? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, 0)
        val candidates = resolved.mapNotNull { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            val label = ri.loadLabel(pm)?.toString().orEmpty().ifBlank { pkg }
            pkg to label
        }.distinctBy { it.first }

        return candidates.firstOrNull { it.second.equals(q, ignoreCase = true) || it.first.equals(q, ignoreCase = true) }
            ?: candidates.firstOrNull { it.second.contains(q, ignoreCase = true) }
            ?: candidates.firstOrNull { it.first.contains(q, ignoreCase = true) }
    }

    // 系统设置

    fun handleSystemSetting(
        context: Context,
        setting: String,
        action: String,
        value: String?,
        streamName: String?,
    ): Result<JSONObject> {
        val key = setting.lowercase().trim()
        val act = action.lowercase().trim().ifBlank { "get" }
        val result = when (key) {
            "volume" -> handleVolume(context, act, value, streamName)
            "brightness" -> handleBrightness(context, act, value)
            "rotation" -> handleRotation(context, act, value)
            "dnd" -> handleDnd(context, act, value)
            "wifi", "wi-fi" -> handleWifi(context, act, value)
            "bluetooth" -> handleBluetooth(context, act, value)
            "hotspot" -> handleHotspot(context, act)
            "location" -> handleLocation(context, act)
            else -> return Result.failure(IllegalArgumentException("未知系统设置项：$setting（支持 volume/brightness/rotation/dnd/wifi/bluetooth/hotspot/location）"))
        }
        result.onSuccess { json ->
            val summary = json.optString("summary")
            if (act == "set" && summary.isNotBlank()) {
                PhoneObservationService.active?.showOverlayBadge(summary)
            }
        }
        return result
    }

    private fun handleVolume(
        context: Context,
        act: String,
        value: String?,
        streamName: String?,
    ): Result<JSONObject> {
        val audio = context.getSystemService(AudioManager::class.java)
            ?: return Result.failure(IllegalStateException("无法访问 AudioManager"))
        val (stream, label) = when (streamName?.lowercase()?.trim()) {
            "ring" -> AudioManager.STREAM_RING to "铃声音量"
            "alarm" -> AudioManager.STREAM_ALARM to "闹钟音量"
            "notification" -> AudioManager.STREAM_NOTIFICATION to "通知音量"
            "call", "voice" -> AudioManager.STREAM_VOICE_CALL to "通话音量"
            else -> AudioManager.STREAM_MUSIC to "媒体音量"
        }
        val max = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
        if (act == "open_panel") {
            audio.adjustStreamVolume(stream, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
            val pct = (audio.getStreamVolume(stream) * 100f / max).roundToInt()
            return Result.success(
                JSONObject().put("setting", "volume").put("stream", label).put("percent", pct).put("summary", "已显示音量条 ($pct%)"),
            )
        }
        if (act == "set") {
            if (audio.isVolumeFixed) return Result.failure(IllegalStateException("设备使用固定音量"))
            val want = value?.trim()?.removeSuffix("%")?.toIntOrNull()
                ?: return Result.failure(IllegalArgumentException("请提供 0~100 的音量百分比"))
            val clamped = want.coerceIn(0, 100)
            audio.setStreamVolume(stream, (clamped * max / 100f).roundToInt(), 0)
        }
        val actualRaw = audio.getStreamVolume(stream)
        val actualPct = (actualRaw * 100f / max).roundToInt()
        val summary = "$label 当前 $actualPct%"
        return Result.success(
            JSONObject().apply {
                put("setting", "volume")
                put("stream", label)
                put("level", actualRaw)
                put("max", max)
                put("percent", actualPct)
                put("summary", summary)
            },
        )
    }

    private fun handleBrightness(context: Context, act: String, value: String?): Result<JSONObject> {
        val cr = context.contentResolver
        if (act == "open_panel") {
            return openSystemSettingsPanel(context, Settings.ACTION_DISPLAY_SETTINGS, "显示与亮度设置")
        }
        if (act == "set") {
            if (!Settings.System.canWrite(context)) {
                openSystemSettingsPanel(context, Settings.ACTION_MANAGE_WRITE_SETTINGS, "修改系统设置授权")
                return Result.failure(IllegalStateException("尚未授予「修改系统设置」权限，已为你打开授权页"))
            }
            val raw = value?.trim()?.lowercase().orEmpty()
            if (raw == "auto") {
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            } else {
                val pct = raw.removeSuffix("%").toIntOrNull()
                    ?: return Result.failure(IllegalArgumentException("亮度值应为 0~100 或 auto"))
                val target = (pct.coerceIn(1, 100) * 255 / 100f).roundToInt().coerceIn(1, 255)
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, target)
            }
        }
        val mode = runCatching { Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE) }.getOrDefault(0)
        val rawVal = runCatching { Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128)
        val pct = (rawVal * 100f / 255f).roundToInt().coerceIn(0, 100)
        val auto = mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        val summary = if (auto) "自动亮度已开启（当前 $pct%）" else "屏幕亮度 $pct%"
        return Result.success(
            JSONObject().apply {
                put("setting", "brightness")
                put("auto", auto)
                put("percent", pct)
                put("raw", rawVal)
                put("can_write_settings", Settings.System.canWrite(context))
                put("summary", summary)
            },
        )
    }

    private fun handleRotation(context: Context, act: String, value: String?): Result<JSONObject> {
        val cr = context.contentResolver
        if (act == "open_panel") {
            return openSystemSettingsPanel(context, Settings.ACTION_DISPLAY_SETTINGS, "显示设置")
        }
        if (act == "set") {
            if (!Settings.System.canWrite(context)) {
                openSystemSettingsPanel(context, Settings.ACTION_MANAGE_WRITE_SETTINGS, "修改系统设置授权")
                return Result.failure(IllegalStateException("调节自动旋转需要「修改系统设置」权限，已打开授权页"))
            }
            val enable = parseBooleanLike(value)
                ?: return Result.failure(IllegalArgumentException("请提供 on/off 或 true/false"))
            Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, if (enable) 1 else 0)
        }
        val autoRotate = runCatching { Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION) }.getOrDefault(0) == 1
        val summary = if (autoRotate) "自动旋转已开启" else "自动旋转已关闭（锁定方向）"
        return Result.success(
            JSONObject().apply {
                put("setting", "rotation")
                put("auto_rotate", autoRotate)
                put("summary", summary)
            },
        )
    }

    private fun handleDnd(context: Context, act: String, value: String?): Result<JSONObject> {
        val nm = context.getSystemService(NotificationManager::class.java)
            ?: return Result.failure(IllegalStateException("无法访问 NotificationManager"))
        if (act == "open_panel") {
            return openSystemSettingsPanel(context, Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, "勿扰权限设置")
        }
        if (act == "set") {
            if (!nm.isNotificationPolicyAccessGranted) {
                openSystemSettingsPanel(context, Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, "勿扰权限设置")
                return Result.failure(IllegalStateException("尚未授予「勿扰权限」，已打开设置页"))
            }
            val enable = parseBooleanLike(value)
                ?: return Result.failure(IllegalArgumentException("请提供 on/off 或 true/false"))
            val filter = if (enable) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
            nm.setInterruptionFilter(filter)
        }
        val current = nm.currentInterruptionFilter
        val enabled = current != NotificationManager.INTERRUPTION_FILTER_ALL
        val summary = if (enabled) "勿扰模式已开启" else "勿扰模式已关闭"
        return Result.success(
            JSONObject().apply {
                put("setting", "dnd")
                put("enabled", enabled)
                put("interruption_filter", current)
                put("summary", summary)
            },
        )
    }

    @Suppress("DEPRECATION")
    private fun handleWifi(context: Context, act: String, value: String?): Result<JSONObject> {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val currentlyEnabled = wifi?.isWifiEnabled == true
        if (act == "open_panel") {
            val action = if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
            return openSystemSettingsPanel(context, action, "Wi-Fi 面板")
        }
        if (act == "set") {
            val want = parseBooleanLike(value)
                ?: return Result.failure(IllegalArgumentException("请提供 on/off 或 true/false"))
            if (Build.VERSION.SDK_INT < 29 && wifi != null) {
                val ok = runCatching { wifi.setWifiEnabled(want) }.getOrDefault(false)
                val now = wifi.isWifiEnabled
                val summary = if (now) "Wi-Fi 已开启" else if (ok) "已请求切换 Wi-Fi" else "Wi-Fi 状态未变更"
                return Result.success(
                    JSONObject().put("setting", "wifi").put("enabled", now).put("summary", summary),
                )
            } else {
                // Android 10+ 限制第三方 App 直接开关 Wi-Fi，弹出原生 Wi-Fi 控制面板供下一步点击或确认
                val action = if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
                openSystemSettingsPanel(context, action, "Wi-Fi 面板")
                return Result.success(
                    JSONObject().apply {
                        put("setting", "wifi")
                        put("enabled", currentlyEnabled)
                        put("panel_opened", true)
                        put("summary", "Android ${Build.VERSION.RELEASE} 需在系统面板操作，已弹出 Wi-Fi 面板（当前${if (currentlyEnabled) "已开" else "已关"}）")
                    },
                )
            }
        }
        return Result.success(
            JSONObject().apply {
                put("setting", "wifi")
                put("enabled", currentlyEnabled)
                put("summary", if (currentlyEnabled) "Wi-Fi 已开启" else "Wi-Fi 已关闭")
            },
        )
    }

    @Suppress("MissingPermission")
    private fun handleBluetooth(context: Context, act: String, value: String?): Result<JSONObject> {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return Result.failure(IllegalStateException("此设备没有蓝牙适配器"))
        val enabled = runCatching { adapter.isEnabled }.getOrDefault(false)
        if (act == "open_panel") {
            return openSystemSettingsPanel(context, Settings.ACTION_BLUETOOTH_SETTINGS, "蓝牙设置")
        }
        if (act == "set") {
            val want = parseBooleanLike(value)
                ?: return Result.failure(IllegalArgumentException("请提供 on/off 或 true/false"))
            if (want && !enabled) {
                val reqIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val starter: Context = PhoneObservationService.active ?: context
                runCatching { starter.startActivity(reqIntent) }
                    .onFailure { openSystemSettingsPanel(context, Settings.ACTION_BLUETOOTH_SETTINGS, "蓝牙设置") }
                return Result.success(
                    JSONObject().apply {
                        put("setting", "bluetooth")
                        put("enabled", enabled)
                        put("panel_opened", true)
                        put("summary", "已弹出开启蓝牙系统确认框")
                    },
                )
            } else if (!want && enabled) {
                openSystemSettingsPanel(context, Settings.ACTION_BLUETOOTH_SETTINGS, "蓝牙设置")
                return Result.success(
                    JSONObject().apply {
                        put("setting", "bluetooth")
                        put("enabled", enabled)
                        put("panel_opened", true)
                        put("summary", "关闭蓝牙需在系统设置确认，已打开蓝牙设置页")
                    },
                )
            }
        }
        return Result.success(
            JSONObject().apply {
                put("setting", "bluetooth")
                put("enabled", enabled)
                put("summary", if (enabled) "蓝牙已开启" else "蓝牙已关闭")
            },
        )
    }

    private fun handleHotspot(context: Context, act: String): Result<JSONObject> {
        if (act == "set" || act == "open_panel") {
            val tether = Intent("android.settings.TETHER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val starter: Context = PhoneObservationService.active ?: context
            val opened = runCatching { starter.startActivity(tether); true }.getOrDefault(false)
            if (!opened) {
                openSystemSettingsPanel(context, Settings.ACTION_WIRELESS_SETTINGS, "网络与热点设置")
            }
            return Result.success(
                JSONObject().apply {
                    put("setting", "hotspot")
                    put("panel_opened", true)
                    put("summary", "已打开个人热点与网络共享设置")
                },
            )
        }
        return Result.success(
            JSONObject().apply {
                put("setting", "hotspot")
                put("summary", "个人热点开关由系统设置页管理，可传 action=open_panel 打开")
            },
        )
    }

    private fun handleLocation(context: Context, act: String): Result<JSONObject> {
        val lm = context.getSystemService(LocationManager::class.java)
        val enabled = if (Build.VERSION.SDK_INT >= 28) {
            lm?.isLocationEnabled == true
        } else {
            @Suppress("DEPRECATION")
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE, 0) != 0
        }
        if (act == "set" || act == "open_panel") {
            openSystemSettingsPanel(context, Settings.ACTION_LOCATION_SOURCE_SETTINGS, "定位服务设置")
            return Result.success(
                JSONObject().apply {
                    put("setting", "location")
                    put("enabled", enabled)
                    put("panel_opened", true)
                    put("summary", "已打开定位服务设置（当前${if (enabled) "已开启" else "已关闭"}）")
                },
            )
        }
        return Result.success(
            JSONObject().apply {
                put("setting", "location")
                put("enabled", enabled)
                put("summary", if (enabled) "定位服务已开启" else "定位服务已关闭")
            },
        )
    }

    private fun openSystemSettingsPanel(context: Context, action: String, title: String): Result<JSONObject> {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val target = context.packageManager.resolveActivity(intent, PackageManager.MATCH_SYSTEM_ONLY)?.activityInfo
            ?: context.packageManager.resolveActivity(intent, 0)?.activityInfo
            ?: return Result.failure(IllegalStateException("设备未提供 $title 入口"))
        if (PhoneScope.blocked(context, target.packageName)) {
            return Result.failure(IllegalStateException("系统设置应用在黑名单内"))
        }
        val starter: Context = PhoneObservationService.active ?: context
        starter.startActivity(intent)
        return Result.success(
            JSONObject().apply {
                put("panel_opened", true)
                put("package", target.packageName)
                put("summary", "已打开$title")
            },
        )
    }

    // 文件与媒体

    suspend fun handleFileMedia(
        context: Context,
        action: String,
        path: String,
        destPath: String,
        newName: String,
        mimeType: String,
    ): Result<JSONObject> {
        val act = action.lowercase().trim()
        val wsRoot = PhoneRuntime.workspaceRoot(context)
        fun resolveFile(raw: String): File {
            val f = File(raw)
            return if (f.isAbsolute) f else File(wsRoot, raw)
        }
        return when (act) {
            "list" -> {
                val targetDir = if (path.isBlank()) wsRoot else resolveFile(path)
                if (!targetDir.isDirectory) {
                    return Result.failure(IllegalArgumentException("目录不存在：${targetDir.absolutePath}"))
                }
                val entries = targetDir.listFiles().orEmpty()
                    .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
                    .take(100)
                val arr = JSONArray()
                entries.forEach { f ->
                    arr.put(
                        JSONObject().apply {
                            put("name", f.name)
                            put("is_dir", f.isDirectory)
                            put("size_bytes", if (f.isFile) f.length() else 0L)
                            put("workspace_path", PhoneRuntime.toWorkspaceRelativePath(context, f))
                            put("absolute_path", f.absolutePath)
                        },
                    )
                }
                Result.success(JSONObject().put("dir", targetDir.absolutePath).put("items", arr))
            }
            "save_to_downloads" -> {
                val src = resolveFile(path)
                val exported = EnvKit.exportFileToDownloads(context, src)
                    ?: return Result.failure(IllegalStateException("导出失败：源文件不存在或公共下载目录不可写"))
                Result.success(
                    JSONObject().put("exported_path", exported.absolutePath).put("summary", "已保存到 Download/CodeForge/${exported.name}"),
                )
            }
            "import_to_workspace" -> {
                val src = resolveFile(path)
                if (!src.isFile) return Result.failure(IllegalArgumentException("源文件不存在：$path"))
                val dir = File(wsRoot, "imports").apply { mkdirs() }
                val dst = EnvKit.uniqueChildFile(dir, src.name, "imported")
                src.inputStream().use { input -> dst.outputStream().use { output -> input.copyTo(output) } }
                Result.success(
                    JSONObject()
                        .put("workspace_path", PhoneRuntime.toWorkspaceRelativePath(context, dst))
                        .put("absolute_path", dst.absolutePath),
                )
            }
            "pick" -> {
                val uri = PhonePermissionBridge.requestPickDocument(context, mimeType.ifBlank { "*/*" })
                    ?: return Result.failure(IllegalStateException("用户取消了文件选择"))
                val imported = EnvKit.importFileFromUri(context, uri, wsRoot, "imports")
                    ?: return Result.failure(IllegalStateException("无法读取所选文件"))
                Result.success(
                    JSONObject()
                        .put("workspace_path", PhoneRuntime.toWorkspaceRelativePath(context, imported))
                        .put("absolute_path", imported.absolutePath)
                        .put("size_bytes", imported.length()),
                )
            }
            "move" -> {
                val src = resolveFile(path)
                if (!src.exists()) return Result.failure(IllegalArgumentException("源文件不存在：$path"))
                val dst = resolveFile(destPath).let { d -> if (d.isDirectory) File(d, src.name) else d }
                dst.parentFile?.mkdirs()
                val moved = src.renameTo(dst) || runCatching {
                    src.copyRecursively(dst, overwrite = false)
                    src.deleteRecursively()
                }.getOrDefault(false)
                if (!moved) return Result.failure(IllegalStateException("移动失败"))
                Result.success(
                    JSONObject()
                        .put("workspace_path", PhoneRuntime.toWorkspaceRelativePath(context, dst))
                        .put("absolute_path", dst.absolutePath),
                )
            }
            "rename" -> {
                val src = resolveFile(path)
                val renamed = EnvKit.renameEntry(src, newName)
                    ?: return Result.failure(IllegalStateException("重命名失败（名称非法或目标已存在）"))
                Result.success(
                    JSONObject()
                        .put("workspace_path", PhoneRuntime.toWorkspaceRelativePath(context, renamed))
                        .put("absolute_path", renamed.absolutePath),
                )
            }
            "delete" -> {
                val target = resolveFile(path)
                if (!target.exists()) return Result.failure(IllegalArgumentException("目标不存在：$path"))
                val allowedRoots = listOfNotNull(
                    wsRoot,
                    PhoneRuntime.artifactDir(context, ""),
                    EnvKit.deviceStorageRoot(context),
                )
                val deleted = allowedRoots.any { root -> EnvKit.deleteRecursivelySafe(target, root) } ||
                    (target.isFile && target.delete())
                if (!deleted) return Result.failure(IllegalStateException("删除失败或路径受保护"))
                Result.success(JSONObject().put("deleted", target.absolutePath))
            }
            "open" -> {
                val file = resolveFile(path)
                if (!file.isFile) return Result.failure(IllegalArgumentException("文件不存在：$path"))
                val uri = PhoneArtifactStore.fileUri(context, file)
                val mime = mimeType.ifBlank { PhoneArtifactStore.mimeForFile(file) }
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                val starter: Context = PhoneObservationService.active ?: context
                starter.startActivity(intent)
                Result.success(JSONObject().put("opened", file.name))
            }
            "share" -> {
                val file = resolveFile(path)
                if (!file.isFile) return Result.failure(IllegalArgumentException("文件不存在：$path"))
                val uri = PhoneArtifactStore.fileUri(context, file)
                val mime = mimeType.ifBlank { PhoneArtifactStore.mimeForFile(file) }
                val intent = Intent(Intent.ACTION_SEND)
                    .setType(mime)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val chooser = Intent.createChooser(intent, "分享文件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val starter: Context = PhoneObservationService.active ?: context
                starter.startActivity(chooser)
                Result.success(JSONObject().put("shared", file.name))
            }
            else -> Result.failure(IllegalArgumentException("不支持的 file_media 动作：$action"))
        }
    }

    // 日历、闹钟与计时器

    suspend fun handleCalendarReminder(
        context: Context,
        action: String,
        title: String,
        description: String,
        startMs: Long?,
        endMs: Long?,
        hour: Int?,
        minute: Int?,
        seconds: Int?,
    ): Result<JSONObject> {
        return when (action.lowercase().trim()) {
            "list_events" -> {
                val perms = PhonePermissionBridge.requestPermissions(
                    context,
                    arrayOf(Manifest.permission.READ_CALENDAR),
                )
                if (perms[Manifest.permission.READ_CALENDAR] != true) {
                    return Result.failure(IllegalStateException("未授予读取日历权限（READ_CALENDAR）"))
                }
                val now = System.currentTimeMillis()
                val begin = startMs ?: now
                val finish = endMs ?: (begin + 7L * 24 * 3600 * 1000)
                val uriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
                ContentUris.appendId(uriBuilder, begin)
                ContentUris.appendId(uriBuilder, finish)
                val arr = JSONArray()
                context.contentResolver.query(
                    uriBuilder.build(),
                    arrayOf(
                        CalendarContract.Instances.EVENT_ID,
                        CalendarContract.Instances.TITLE,
                        CalendarContract.Instances.BEGIN,
                        CalendarContract.Instances.END,
                        CalendarContract.Instances.EVENT_LOCATION,
                    ),
                    null,
                    null,
                    "${CalendarContract.Instances.BEGIN} ASC",
                )?.use { cursor ->
                    while (cursor.moveToNext() && arr.length() < 40) {
                        arr.put(
                            JSONObject().apply {
                                put("event_id", cursor.getLong(0))
                                put("title", cursor.getString(1).orEmpty())
                                put("begin_ms", cursor.getLong(2))
                                put("end_ms", cursor.getLong(3))
                                put("location", cursor.getString(4).orEmpty())
                            },
                        )
                    }
                }
                Result.success(JSONObject().put("count", arr.length()).put("events", arr))
            }
            "create_event" -> {
                val eventTitle = title.ifBlank { "CodeForge 提醒" }
                val begin = startMs ?: (System.currentTimeMillis() + 3600_000L)
                val finish = endMs ?: (begin + 1800_000L)
                val perms = PhonePermissionBridge.requestPermissions(
                    context,
                    arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
                )
                if (perms[Manifest.permission.WRITE_CALENDAR] == true) {
                    val calId = findWritableCalendarId(context)
                    if (calId != null) {
                        val values = ContentValues().apply {
                            put(CalendarContract.Events.CALENDAR_ID, calId)
                            put(CalendarContract.Events.TITLE, eventTitle)
                            put(CalendarContract.Events.DESCRIPTION, description)
                            put(CalendarContract.Events.DTSTART, begin)
                            put(CalendarContract.Events.DTEND, finish)
                            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                        }
                        val inserted = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                        val eventId = inserted?.lastPathSegment?.toLongOrNull()
                        if (eventId != null) {
                            val rem = ContentValues().apply {
                                put(CalendarContract.Reminders.EVENT_ID, eventId)
                                put(CalendarContract.Reminders.MINUTES, 10)
                                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                            }
                            runCatching { context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, rem) }
                            return Result.success(
                                JSONObject().apply {
                                    put("created", true)
                                    put("event_id", eventId)
                                    put("title", eventTitle)
                                    put("begin_ms", begin)
                                },
                            )
                        }
                    }
                }
                // 回退为系统日历添加页面
                val intent = Intent(Intent.ACTION_INSERT)
                    .setData(CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.Events.TITLE, eventTitle)
                    .putExtra(CalendarContract.Events.DESCRIPTION, description)
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, finish)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val starter: Context = PhoneObservationService.active ?: context
                starter.startActivity(intent)
                Result.success(
                    JSONObject().put("panel_opened", true).put("title", eventTitle).put("begin_ms", begin),
                )
            }
            "set_alarm" -> {
                val h = hour ?: return Result.failure(IllegalArgumentException("设置闹钟需要 hour (0..23) 与 minute (0..59)"))
                val m = minute ?: 0
                val intent = Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, h.coerceIn(0, 23))
                    .putExtra(AlarmClock.EXTRA_MINUTES, m.coerceIn(0, 59))
                    .putExtra(AlarmClock.EXTRA_MESSAGE, title.ifBlank { "提醒" })
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val starter: Context = PhoneObservationService.active ?: context
                starter.startActivity(intent)
                Result.success(JSONObject().put("alarm_set", true).put("hour", h).put("minute", m).put("message", title))
            }
            "set_timer" -> {
                val sec = seconds?.coerceAtLeast(1)
                    ?: return Result.failure(IllegalArgumentException("设置计时器需要正整数 seconds"))
                val intent = Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, sec)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, title.ifBlank { "倒计时" })
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val starter: Context = PhoneObservationService.active ?: context
                starter.startActivity(intent)
                Result.success(JSONObject().put("timer_set", true).put("seconds", sec).put("message", title))
            }
            "show_alarms" -> {
                val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val starter: Context = PhoneObservationService.active ?: context
                starter.startActivity(intent)
                Result.success(JSONObject().put("panel_opened", true))
            }
            else -> Result.failure(IllegalArgumentException("不支持的 calendar_reminder 动作：$action"))
        }
    }

    private fun findWritableCalendarId(context: Context): Long? {
        val uri = CalendarContract.Calendars.CONTENT_URI
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        )
        var fallback: Long? = null
        context.contentResolver.query(uri, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val primary = c.getInt(1)
                val level = c.getInt(2)
                if (level >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) {
                    if (primary == 1) return id
                    if (fallback == null) fallback = id
                }
            }
        }
        return fallback
    }

    // 通讯与分享

    suspend fun handleCommunication(
        context: Context,
        action: String,
        query: String,
        phone: String,
        body: String,
        subject: String,
        filePath: String,
    ): Result<JSONObject> {
        return when (action.lowercase().trim()) {
            "find_contacts" -> {
                val perms = PhonePermissionBridge.requestPermissions(
                    context,
                    arrayOf(Manifest.permission.READ_CONTACTS),
                )
                if (perms[Manifest.permission.READ_CONTACTS] != true) {
                    return Result.failure(IllegalStateException("未授予读取联系人权限（READ_CONTACTS）"))
                }
                val arr = JSONArray()
                val seen = HashSet<String>()
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ),
                    null,
                    null,
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
                )?.use { c ->
                    while (c.moveToNext() && arr.length() < 30) {
                        val name = c.getString(0).orEmpty()
                        val num = c.getString(1).orEmpty()
                        if (query.isBlank() ||
                            name.contains(query, ignoreCase = true) ||
                            num.contains(query, ignoreCase = true)
                        ) {
                            val key = "$name|$num"
                            if (seen.add(key)) {
                                arr.put(JSONObject().put("name", name).put("phone", num))
                            }
                        }
                    }
                }
                Result.success(JSONObject().put("count", arr.length()).put("contacts", arr))
            }
            "open_dialer" -> {
                val num = phone.trim()
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$num"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val starter: Context = PhoneObservationService.active ?: context
                starter.startActivity(intent)
                Result.success(JSONObject().put("dialer_opened", true).put("phone", num))
            }
            "draft_sms" -> {
                val num = phone.trim()
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$num"))
                    .putExtra("sms_body", body)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val starter: Context = PhoneObservationService.active ?: context
                starter.startActivity(intent)
                Result.success(JSONObject().put("sms_draft_opened", true).put("phone", num))
            }
            "send_sms" -> {
                val num = phone.trim()
                if (num.isBlank() || body.isBlank()) {
                    return Result.failure(IllegalArgumentException("发送短信需要 phone 与 body"))
                }
                val perms = PhonePermissionBridge.requestPermissions(
                    context,
                    arrayOf(Manifest.permission.SEND_SMS),
                )
                if (perms[Manifest.permission.SEND_SMS] != true) {
                    return Result.failure(IllegalStateException("未授予发送短信权限（SEND_SMS），可改用 draft_sms 打开系统短信草稿"))
                }
                @Suppress("DEPRECATION")
                val mgr = if (Build.VERSION.SDK_INT >= 31) {
                    context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
                } else {
                    SmsManager.getDefault()
                }
                val parts = mgr.divideMessage(body)
                if (parts.size > 1) {
                    mgr.sendMultipartTextMessage(num, null, parts, null, null)
                } else {
                    mgr.sendTextMessage(num, null, body, null, null)
                }
                Result.success(JSONObject().put("sent", true).put("phone", num))
            }
            "share_text" -> {
                val intent = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, subject)
                    .putExtra(Intent.EXTRA_TEXT, body)
                val chooser = Intent.createChooser(intent, subject.ifBlank { "分享内容" })
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val starter: Context = PhoneObservationService.active ?: context
                starter.startActivity(chooser)
                Result.success(JSONObject().put("share_chooser_opened", true))
            }
            "share_file" -> handleFileMedia(context, "share", filePath, "", "", "")
            else -> Result.failure(IllegalArgumentException("不支持的 communication 动作：$action"))
        }
    }

    // 设备状态

    fun deviceStatus(context: Context): JSONObject {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.coerceAtLeast(1) ?: 100
        val batteryPct = if (level >= 0) (level * 100f / scale).roundToInt() else -1
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val networkType = when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }

        val dataStat = StatFs(Environment.getDataDirectory().path)
        val availMb = dataStat.availableBytes / (1024 * 1024)
        val totalMb = dataStat.totalBytes / (1024 * 1024)

        val audio = context.getSystemService(AudioManager::class.java)
        val musicMax = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 1
        val musicPct = if (audio != null) (audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / musicMax).roundToInt() else -1

        val service = PhoneObservationService.active
        val perms = permissionSnapshot(context)

        return JSONObject().apply {
            put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("android_version", Build.VERSION.RELEASE)
            put("sdk_int", Build.VERSION.SDK_INT)
            put("battery_percent", batteryPct)
            put("charging", charging)
            put("network_type", networkType)
            put("storage_available_mb", availMb)
            put("storage_total_mb", totalMb)
            put("music_volume_percent", musicPct)
            put("ringer_mode", when (audio?.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "silent"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                else -> "normal"
            })
            put("device_locked", service?.isDeviceLocked() ?: false)
            put("foreground_package", service?.foregroundPackage().orEmpty())
            put(
                "permissions",
                JSONObject().apply {
                    put("accessibility", perms.accessibilityEnabled)
                    put("screen_capture", perms.screenCaptureAuthorized)
                    put("microphone", perms.microphoneGranted)
                    put("notification_listener", perms.notificationListenerEnabled)
                    put("write_settings", perms.writeSettingsGranted)
                    put("dnd_policy", perms.dndPolicyAccessGranted)
                    put("calendar", perms.calendarGranted)
                    put("contacts", perms.contactsGranted)
                    put("sms", perms.smsGranted)
                },
            )
        }
    }

    private fun parseBooleanLike(raw: String?): Boolean? = when (raw?.trim()?.lowercase()) {
        "true", "1", "on", "yes", "enable", "enabled", "开", "开启", "打开" -> true
        "false", "0", "off", "no", "disable", "disabled", "关", "关闭" -> false
        else -> null
    }
}
