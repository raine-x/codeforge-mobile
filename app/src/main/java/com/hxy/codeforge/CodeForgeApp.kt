package com.hxy.codeforge

import android.app.Application
import com.hxy.codeforge.core.EnvKit
import com.hxy.codeforge.core.KernelConfig
import com.hxy.codeforge.ui.settings.AppSettings

/** 全局前后台可见性状态，供 ViewModel 上报内核通知策略。 */
object AppVisibility {
    val hidden = kotlinx.coroutines.flow.MutableStateFlow(false)
}

/** 进程级启动初始化：异常日志落盘、前后台可见性、偏好、环境解包与过期缓存清理。 */
class CodeForgeApp : Application() {

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            com.hxy.codeforge.ui.chat.trimMediaBitmapCache(level)
            android.util.Log.i("CodeForgeApp", "onTrimMemory level=$level: trimmed media cache")
        }
    }

    override fun onCreate() {
        super.onCreate()
        val systemHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val dir = java.io.File(filesDir, "logs").apply { mkdirs() }
                dir.resolve("crash-${System.currentTimeMillis()}.txt")
                    .writeText("thread=${t.name}\n\n${e.stackTraceToString()}")
                dir.listFiles { f -> f.name.startsWith("crash-") }
                    ?.sortedByDescending { it.name }
                    ?.drop(5)
                    ?.forEach { it.delete() }
            }
            systemHandler?.uncaughtException(t, e)
        }
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                when (event) {
                    androidx.lifecycle.Lifecycle.Event.ON_START -> AppVisibility.hidden.value = false
                    androidx.lifecycle.Lifecycle.Event.ON_STOP -> AppVisibility.hidden.value = true
                    else -> Unit
                }
            },
        )
        AppSettings.init(this)
        com.hxy.codeforge.phone.PhoneRuntime.init(this)
        EnvKit.cleanupOrphansAsync(this)
        EnvKit.ensureReady(this)
    }
}
