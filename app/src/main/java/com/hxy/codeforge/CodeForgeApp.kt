package com.hxy.codeforge

import android.app.Application
import com.hxy.codeforge.core.EnvKit
import com.hxy.codeforge.core.KernelConfig
import com.hxy.codeforge.ui.settings.AppSettings

/**
 * 进程级初始化。
 *
 * 启动流水线（顺序固定，后续接入内核时在此追加）：
 *  1. [AppSettings.init] —— 读出持久化设置（旋转开关/灵敏度），必须在首帧前完成；
 *  2. （待接内核）创建通知渠道 —— CoreService 启动前必须存在。
 *
 * 内置离线语音模型已移除（APK 瘦身约 52 MB）：语音改走「云端（用户填 Key）→
 * 系统 SpeechRecognizer」两档，系统没有识别服务时给出提示而不是自带模型。
 */
/** 全局前后台可见性（B5-2）：ProcessLifecycleOwner 驱动，VM 订阅转发给内核。 */
object AppVisibility {
    val hidden = kotlinx.coroutines.flow.MutableStateFlow(false)
}

class CodeForgeApp : Application() {

    /**
     * B5-9：整机内存压力回调（系统原生）。内核有自己的 memlimit 自保，
     * 壳侧记录事件即可 —— 运行时状态不主动杀（内核承载会话，杀了丢上下文）。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            android.util.Log.i("CodeForgeApp", "onTrimMemory level=$level（内核由 memlimit 自保）")
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 崩溃现场落盘（真机排障第一手资料）：filesDir/logs/crash-*.txt，
        // 保留最近 5 份；落盘后链回系统默认处理器，logcat 照常有记录
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
        // 前后台切换上报：内核据此决定长任务完成时是否发系统通知（此前永远收不到）
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
        // B5-10：附件孤儿清理 —— 发送完成后附件文件不再被引用（内容已进上下文），
        // 仿内核 GCUndoSpill 的「启动清一次」策略，留 7 天缓冲供 regenerate 重读
        java.io.File(filesDir, "workspace/attachments").let { dir ->
            if (dir.isDirectory) {
                val deadline = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
                dir.listFiles()?.forEach { f -> if (f.lastModified() < deadline) f.delete() }
            }
        }
        // 统一环境准备（AGENTS §2.6 第 4 步）：proot + Alpine rootfs 解压到 $CELL，
        // 完成后内核的 run_command 经 cfrun 在同一棵目录树上执行（见 EnvKit 契约）
        EnvKit.ensureReady(this)
    }
}
