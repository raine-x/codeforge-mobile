package com.hxy.codeforge.phone

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.media.AudioManager
import android.provider.Settings
import kotlin.math.roundToInt

internal object PhoneCommands {
    val capabilities = mapOf("volume" to "媒体音量", "wifi" to "Wi-Fi 设置", "bluetooth" to "蓝牙设置")
    private val volume = Regex("""(?:把)?(?:媒体)?音量(?:调到|设为|设置为)?\s*(\d{1,3})\s*[%％]""")
    private val mention = Regex("""^@phone(?:\s+|$)""", RegexOption.IGNORE_CASE)

    fun request(message: String): String? {
        val text = message.trim()
        val prefix = mention.find(text) ?: return null
        return text.substring(prefix.range.last + 1).trim()
    }

    fun run(context: Context, request: String): String {
        if (request == "下拉通知栏") {
            if (!context.getSharedPreferences("phone_controls", Context.MODE_PRIVATE).getBoolean("gestures", false))
                return "请先在手机操控设置中允许屏幕操作"
            val service = PhoneObservationService.active ?: return "请启用无障碍服务"
            return if (service.showNotifications()) "已请求打开通知栏" else "无法打开通知栏"
        }
        val click = Regex("""点击\s*(\d+)\s*[,，]\s*(\d+)""").matchEntire(request)
        if (click != null) {
            if (!context.getSharedPreferences("phone_controls", Context.MODE_PRIVATE).getBoolean("gestures", false))
                return "请先在手机操控设置中允许屏幕操作"
            val service = PhoneObservationService.active ?: return "请启用无障碍服务"
            val x = click.groupValues[1].toFloat()
            val y = click.groupValues[2].toFloat()
            return if (service.performGesture(x, y, x, y, 80)) "已发送点击" else "点击未执行"
        }
        val match = volume.matchEntire(request)
        val id = when (request.lowercase().replace(" ", "")) {
            "wifi设置", "检查wifi", "检查wi-fi", "打开wifi", "打开wi-fi", "打开网络设置", "wifi", "wi-fi" -> "wifi"
            "蓝牙设置", "检查蓝牙", "打开蓝牙", "蓝牙" -> "bluetooth"
            else -> if (match != null) "volume" else return "支持：媒体音量30%、打开 Wi-Fi、打开蓝牙。其他操作未获授权。"
        }
        val prefs = context.getSharedPreferences("phone_controls", Context.MODE_PRIVATE)
        if (!prefs.getBoolean(id, false)) return "请先在设置 → 手机操控中允许" + capabilities.getValue(id)
        return runCatching {
            if (id == "volume") {
                val percent = match!!.groupValues[1].toInt()
                if (percent !in 0..100) return "音量应为 0% 至 100%"
                val audio = context.getSystemService(AudioManager::class.java)
                if (audio.isVolumeFixed) return "设备使用固定音量"
                val stream = AudioManager.STREAM_MUSIC
                val max = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
                audio.setStreamVolume(stream, (percent * max / 100f).roundToInt(), 0)
                "当前媒体音量 " + (audio.getStreamVolume(stream) * 100f / max).roundToInt() + "%"
            } else {
                val action = if (id == "wifi") Settings.ACTION_WIFI_SETTINGS else Settings.ACTION_BLUETOOTH_SETTINGS
                val intent = Intent(action)
                val target = context.packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_SYSTEM_ONLY)
                    ?.activityInfo ?: return "设备没有可用的系统设置入口"
                if (target.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0) return "目标不在允许范围内"
                if (PhoneScope.blocked(context, target.packageName)) return "目标在应用黑名单内"
                if (request.lowercase().replace(" ", "") in setOf("打开wifi", "打开wi-fi")) {
                    val wifi = context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
                    if (wifi.isWifiEnabled) return "Wi-Fi 已开启"
                    @Suppress("DEPRECATION")
                    val accepted = runCatching { wifi.setWifiEnabled(true) }.getOrDefault(false)
                    if (accepted) return if (wifi.isWifiEnabled) "Wi-Fi 已开启" else "系统已接受开启 Wi-Fi 的请求"
                }
                if (request.startsWith("打开") && id == "bluetooth") {
                    val adapter = context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
                        ?: return "此设备没有蓝牙适配器"
                    if (adapter.isEnabled) return "蓝牙已开启"
                    val enable = Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    val activity = context.packageManager.resolveActivity(enable, android.content.pm.PackageManager.MATCH_SYSTEM_ONLY)?.activityInfo
                    if (activity != null) {
                        if (PhoneScope.blocked(context, activity.packageName)) return "目标在应用黑名单内"
                        context.startActivity(enable.setClassName(activity.packageName, activity.name))
                        return "等待系统确认开启蓝牙"
                    }
                }
                if (request.startsWith("检查")) {
                    if (!prefs.getBoolean("observe", false)) return "请在手机操控设置中启用窗口检查"
                    val service = PhoneObservationService.active ?: return "请手动启用单次窗口检查无障碍服务"
                    val capture = prefs.getBoolean("next_capture", false)
                    prefs.edit().remove("next_capture").apply()
                    service.begin(target.packageName, capture)
                }
                context.startActivity(intent.setClassName(target.packageName, target.name))
                "已打开" + capabilities.getValue(id)
            }
        }.getOrElse { "系统未执行该操作" }
    }
}
