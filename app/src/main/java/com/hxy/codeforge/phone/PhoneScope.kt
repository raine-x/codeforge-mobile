package com.hxy.codeforge.phone

import android.content.Context

/**
 * 黑名单与目标应用范围检查。
 *
 * 保留 `blocked(context, pkg)` 原有签名以兼容已有测试与旧调用方，
 * 同时提供读取集合、反查应用名以及保护本应用窗口的辅助方法。
 */
internal object PhoneScope {
    fun blocked(context: Context, pkg: String): Boolean {
        if (pkg.isBlank()) return false
        val prefs = context.getSharedPreferences("phone_controls", Context.MODE_PRIVATE)
        return pkg in prefs.getStringSet("excluded_packages", emptySet()).orEmpty()
    }

    fun blockedPackages(context: Context): Set<String> {
        val prefs = context.getSharedPreferences("phone_controls", Context.MODE_PRIVATE)
        return prefs.getStringSet("excluded_packages", emptySet()).orEmpty().toSet()
    }

    fun appLabel(context: Context, pkg: String): String {
        if (pkg.isBlank()) return ""
        return runCatching {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString()
        }.getOrDefault(pkg)
    }
}
