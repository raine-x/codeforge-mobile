package app.codeforge.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.codeforge.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 内核宿主前台服务：拉起 libcodeforge.so、端口握手、保活重启。
 *
 * ## 启动握手（协议的另一半在内核 cmd/agent/main.go）
 *
 *  1. `ProcessBuilder` 启动 `${nativeLibraryDir}/libcodeforge.so`，参数
 *     `-config <filesDir>/kernel-config -workdir <filesDir>/workspace -no-open`，
 *     环境 `HOME=<filesDir>`（SQLite 库落在 ~/.codeforge）、`CODEFORGE_PORT=0`
 *     （随机端口模式）；
 *  2. 内核绑定成功后把 `PORT=xxxxx` 打到 stdout，这里逐行扫描该前缀；
 *  3. 拿到端口即 [KernelState.Ready]，ChatViewModel 据此创建 HttpCoreTransport；
 *  4. 内核意外退出按 1→2→4→8s 退避重启（Ready 满 60s 后计数清零，连续 6 次失败停手）。
 *
 * ## stdout 必须一直有人读
 *
 * PORT 行到手后 reader 线程**不能退**：管道缓冲区塞满后内核会阻塞在日志写上，
 * 表现为「握手成功、聊两句整个卡死」。所以排水线程与内核进程同寿命，
 * 顺带把全部输出镜像到 `filesDir/logs/kernel.log`（每次启动截断）——
 * 内核起不来时唯一线索就在这份文件里，Failed 的 reason 会带日志尾部。
 *
 * ## 前台服务类型是 specialUse 不是 dataSync
 *
 * targetSdk 35+ 的 dataSync 有每天 6 小时的系统配额（AGENTS P-005），
 * 内核要全天候常驻，必须走 specialUse 并在 manifest 声明用途。
 */
/**
 * 内核进程状态。独立于 companion 声明成文件级 sealed class ——
 * 埋在 companion 里的嵌套类型跨文件只能以 `CoreService.Companion.X` 引用，
 * 消费方（ChatViewModel）写起来又长又脆。
 */
sealed class KernelState {
    data object Starting : KernelState()
    data class Ready(val port: Int) : KernelState()
    data class Failed(val reason: String) : KernelState()
}

class CoreService : LifecycleService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        ensureChannel()
        // SPECIAL_USE 类型要求 targetSdk>=34：target 28（侧载版）在 Android 14+ 上
        // 传它会抛 IllegalArgumentException —— 按 target 分支，老 target 走无类型
        val fgType = if (Build.VERSION.SDK_INT >= 34 &&
            applicationInfo.targetSdkVersion >= 34
        ) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification("内核启动中…"), fgType)
        if (intent?.action == ACTION_STOP) {
            lifecycleScope.launch(Dispatchers.IO) {
                KernelProcess.destroy()
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESTART) {
            // 只杀当前进程：监督循环退避后自动按新配置重启（local.yaml 已被调用方改好）
            lifecycleScope.launch(Dispatchers.IO) { KernelProcess.destroy() }
            return START_STICKY
        }
        if (bootstrapped.compareAndSet(false, true)) {
            observeState()
            lifecycleScope.launch(Dispatchers.IO) { superviseLoop() }
        }
        return START_STICKY
    }

    /**
     * 用户从最近任务划掉 App（B5-6）：内核不能变野进程，随服务收掉。
     * Process.destroy 即 SIGTERM，内核信号处理会做会话落盘与审计 flush。
     */
    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        KernelProcess.destroy()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // 服务被系统回收：内核进程不能变野进程，跟着收掉。
        // 直接同步调 destroy（lifecycleScope 在 onDestroy 时已取消，协程不会执行）
        KernelProcess.destroy()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 内核监督

    /**
     * 监督循环：启动 → 等 PORT 握手 → 等退出 → 退避重启。
     * [bootstrapped] 保证随 Service 生命周期只跑一次（onStartCommand 可能多次到达）。
     */
    private suspend fun superviseLoop() {
        var backoff = 1_000L
        var restarts = 0
        var readyAtMs = 0L
        while (true) {
            val port = KernelProcess.startAndAwaitHandshake(this)
            if (port != null) {
                _state.value = KernelState.Ready(port)
                readyAtMs = System.currentTimeMillis()
                backoff = 1_000L
                restarts = 0
            } else {
                _state.value = KernelState.Failed(
                    "内核进程未就绪${KernelProcess.logTail().take(120)}",
                )
            }

            KernelProcess.exited.first { it } // 挂起直到内核退出（或被 destroy 置位）

            if (readyAtMs > 0 && System.currentTimeMillis() - readyAtMs > 60_000) {
                restarts = 0 // 起来过一分钟以上，不算「启动即崩」
            }
            restarts++
            if (restarts > 5) {
                _state.value = KernelState.Failed("内核反复退出（已试 $restarts 次），已停止重试")
                return
            }
            _state.value = KernelState.Starting
            withContext(Dispatchers.IO) { Thread.sleep(backoff) }
            backoff = (backoff * 2).coerceAtMost(8_000L)
        }
    }

    // ---------------------------------------------------------------- 状态与通知

    private fun observeState() {
        lifecycleScope.launch {
            _state.collect { st ->
                val text = when (st) {
                    is KernelState.Starting -> "内核启动中…"
                    is KernelState.Ready -> "内核运行中 · 127.0.0.1:${st.port}"
                    is KernelState.Failed -> "内核异常：${st.reason.take(60)}"
                }
                getSystemService(NotificationManager::class.java)
                    ?.notify(NOTIF_ID, buildNotification(text))
            }
        }
    }

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        // 点击通知打开应用（跳回会话）；通知条上加「新建对话 / 停止内核」两个快捷操作
        val openApp = android.app.PendingIntent.getActivity(
            this, 0,
            Intent(this, app.codeforge.MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val newChat = android.app.PendingIntent.getActivity(
            this, 1,
            Intent(this, app.codeforge.MainActivity::class.java)
                .setAction(app.codeforge.MainActivity.ACTION_NEW_CHAT),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = android.app.PendingIntent.getService(
            this, 2,
            Intent(this, CoreService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        return builder
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_menu_add, "新建对话", newChat)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stop)
            .setOngoing(true)
            .build()
    }

    // ---------------------------------------------------------------- 进程容器

    /**
     * 内核进程的启动/排水/退出信号。独立成 object 是因为 onDestroy 与
     * superviseLoop 都要碰它，且生命周期长于单个 Process 实例。
     */
    private object KernelProcess {
        var process: Process? = null
        var exited = MutableStateFlow(true)
        private var logFile: File? = null

        /** 启动内核并等 PORT= 握手。成功返回端口；起不来/20 秒没握手返回 null。 */
        fun startAndAwaitHandshake(service: CoreService): Int? {
            try {
                val exe = File(service.applicationInfo.nativeLibraryDir, "libcodeforge.so")
                if (!exe.canExecute()) {
                    appendLog("可执行文件不存在或不可执行：$exe（E1：nativeLibraryDir exec 被拦？）")
                    exited.value = true
                    return null
                }
                val configDir = File(service.filesDir, "kernel-config").apply { mkdirs() }
                val workspace = File(service.filesDir, "workspace").apply { mkdirs() }
                val log = File(service.filesDir, "logs").apply { mkdirs() }
                    .resolve("kernel.log")
                    .apply { writeText("") } // 截断：只保留最近一次启动的完整现场
                logFile = log

                // 先取 DNS：apply 块里的 receiver 是 ProcessBuilder，
                // 直接调外部成员函数会被 Kotlin 判成 receiver 不匹配。
                val dnsServer = systemDns(service)

                val proc = ProcessBuilder(
                    exe.absolutePath,
                    "-config", configDir.absolutePath,
                    "-workdir", workspace.absolutePath,
                    "-no-open",
                )
                    .apply {
                        // 统一环境契约（EnvKit）：HOME/TMPDIR/PATH 三处同源，
                        // 内核 spawn 的任何命令与 proot/rootfs 看到同一棵目录树
                        EnvKit.kernelEnv(service).forEach { (k, v) ->
                            environment()[k] = v
                        }
                        environment()["CODEFORGE_PORT"] = "0"
                        // Android 没有 /etc/resolv.conf，纯 Go 解析器会回退到
                        // 127.0.0.1:53 并失败（lookup api.deepseek.com on [::1]:53）。
                        // 现代 Android 的 DNS 只在 ConnectivityManager 里拿得到，
                        // 这里取当前网络的 DNS 注入，内核侧 netdns 包据此装 resolver。
                        dnsServer?.let { environment()["CODEFORGE_DNS"] = it }
                        // IsTermux 的唯一判据是 PREFIX —— 清掉，防止宿主环境误带
                        environment().remove("PREFIX")
                        redirectErrorStream(true)
                        directory(workspace)
                    }
                    .start()
                process = proc
                exited.value = false

                val handshake = MutableStateFlow<Int?>(null)
                // 排水线程：与内核同寿命。握手完成也不退，否则 stdout 塞满后内核卡死
                Thread {
                    val reader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
                    try {
                        while (true) {
                            val line = reader.readLine() ?: break
                            appendLog(line)
                            if (handshake.value == null && line.startsWith("PORT=")) {
                                handshake.value = line.removePrefix("PORT=").trim().toIntOrNull()
                            }
                        }
                    } catch (_: Exception) {
                        // 进程被 destroy 时流会断，这里无需处理
                    } finally {
                        exited.value = true
                    }
                }.start()

                val deadline = System.currentTimeMillis() + 20_000
                while (System.currentTimeMillis() < deadline) {
                    if (exited.value) return null
                    handshake.value?.let { return it }
                    Thread.sleep(100)
                }
                proc.destroy() // 握手超时：收掉这个哑进程
                exited.value = true
                return null
            } catch (e: Exception) {
                appendLog("启动异常：$e")
                exited.value = true
                return null
            }
        }

        fun destroy() {
            process?.destroy()
            process = null
        }

        fun logTail(): String {
            val f = logFile ?: return ""
            if (!f.exists()) return ""
            return f.readLines().takeLast(3).joinToString(" / ")
        }

        private fun appendLog(text: String) {
            runCatching { logFile?.appendText(text + "\n") }
        }
    }

    companion object {
        private const val CHANNEL_ID = "codeforge.core"
        private const val NOTIF_ID = 1001
        const val ACTION_STOP = "app.codeforge.core.STOP"
        const val ACTION_RESTART = "app.codeforge.core.RESTART"

        /** 设置页保存 LLM 配置后调用：杀掉内核进程，监督循环按新 local.yaml 拉起。 */
        fun restart(context: android.content.Context) {
            val intent = android.content.Intent(context, CoreService::class.java)
                .setAction(ACTION_RESTART)
            // 用 startForegroundService 而不是 startService：调用方可能是
            // 「25 秒还没 Ready」的超时兜底，那时 Service 很可能根本没起来，
            // 后台 startService 在 Android 8+ 会抛 IllegalStateException。
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        private val _state = MutableStateFlow<KernelState>(KernelState.Starting)
        val state: StateFlow<KernelState> = _state.asStateFlow()

        private val bootstrapped = AtomicBoolean(false)
    }

}

/**
 * 当前活动网络的 DNS 服务器地址。
 *
 * 只取第一个 IPv4：IPv6 的 `hostAddress` 带 `%wlan0` 作用域后缀，
 * Go 的 net.JoinHostPort 拼出来不是合法地址；DNS 走 IPv4 已经够用。
 *
 * 放在顶层而不是 CoreService 内部：调用点在 `ProcessBuilder.apply {}` 里，
 * 那里的 receiver 是 ProcessBuilder，访问外部类成员会报
 * 「Outer class ... cannot be used as receiver」。
 */
private fun systemDns(service: android.app.Service): String? = runCatching {
    val cm = service.getSystemService(android.net.ConnectivityManager::class.java) ?: return null
    val net = cm.activeNetwork ?: return null
    val props = cm.getLinkProperties(net) ?: return null
    props.dnsServers.firstOrNull { it is java.net.Inet4Address }?.hostAddress
}.getOrNull()
