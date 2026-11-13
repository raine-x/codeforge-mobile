package com.hxy.codeforge

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.hxy.codeforge.core.CoreService
import com.hxy.codeforge.ui.ChatViewModel
import com.hxy.codeforge.ui.CodeForgeApp
import com.hxy.codeforge.ui.common.applyStatusBarVisibility
import com.hxy.codeforge.ui.settings.AppSettings
import com.hxy.codeforge.ui.theme.CodeForgeTheme

/**
 * 唯一的 Activity。
 *
 * 界面全部由 Compose 渲染 —— 没有 WebView，也没有第二套布局文件。
 * 横竖屏切换由 `android:configChanges="orientation|screenSize|screenLayout"` 拦下，
 * Activity 不重建；即使重建（分屏拖拽、字体缩放），状态也在 [ChatViewModel] 里。
 *
 * 职责只有三件：建窗口、申请通知权限、把内核宿主服务拉起来。
 * 另外承接通知的两类跳转意图（B4）：
 *  - [ACTION_NEW_CHAT]：通知栏「新建对话」快捷操作 → 直接触发 vm.createSession()；
 *  - 内核系统通知点击 → 普通打开应用（会话跳转由内核通知携带的 extra 决定，B5-2 链路）。
 *
 * `enableEdgeToEdge()` 是 targetSdk 35+ 的硬要求（Android 15 起系统强制全面屏），
 * 具体避让交给 AppRoot 里的 `WindowInsets.safeDrawing`。
 */
class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝也不阻塞：通知只是状态展示，内核照常跑 */ }

    /** 通知快捷操作要用 vm：提到字段（viewModels 委托在 onCreate 前可用）。 */
    private val vm: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 手机锁竖屏由 RotateWithDevice 转内容；平板按设置里的「横持时旋转界面」开关决定
        // 交给系统旋转（UNSPECIFIED）还是锁定竖屏（PORTRAIT），避免开关在平板上失效。
        // 用物理短边 + smallestScreenWidthDp 判平板，不受当前横竖屏或 letterbox 窄窗影响。
        val isTablet = isPhysicalTablet()
        requestedOrientation = resolveRequestedOrientation(
            isTablet = isTablet,
            rotateWithDevice = AppSettings.rotateWithDevice.value,
        )

        // Android 13+ 通知要运行时申请，否则内核状态常驻通知不可见
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // 内核宿主服务：前台服务必须在前台时启动（目标 31+ 的限制），
        // 放 onStart 每次进前台都拉一次 —— 服务内部幂等，重复 start 无副作用
        startForegroundService(Intent(this, CoreService::class.java))

        handleIntent(intent)

        setContent {
            CodeForgeTheme {
                // 系统状态栏显隐跟随设置（默认隐藏）。必须在这里读 Compose State，
                // 读到 .value 才会随设置页的开关变化重跑。
                // 隐藏后 safeDrawing 的顶 inset 自动变 0，AppRoot 不需要任何改动。
                val showStatusBar = AppSettings.showStatusBar.value
                LaunchedEffect(showStatusBar) {
                    applyStatusBarVisibility(window, showStatusBar)
                }
                // 平板监听「横持时旋转界面」开关，实时同步窗口方向锁定状态
                val rotateWithDevice = AppSettings.rotateWithDevice.value
                LaunchedEffect(isTablet, rotateWithDevice) {
                    val target = resolveRequestedOrientation(
                        isTablet = isTablet,
                        rotateWithDevice = rotateWithDevice,
                    )
                    if (requestedOrientation != target) {
                        requestedOrientation = target
                    }
                }
                CodeForgeApp(vm)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun isPhysicalTablet(): Boolean {
        val dm = android.util.DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(dm)
        val shortSideDp = if (dm.density > 0f) {
            minOf(dm.widthPixels, dm.heightPixels) / dm.density
        } else {
            0f
        }
        return maxOf(resources.configuration.smallestScreenWidthDp.toFloat(), shortSideDp) >= 600f
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 弹层/系统界面关闭把焦点还给主窗口时，按用户偏好补齐状态栏隐藏态，避免临时显示后回不去
        if (hasFocus && !AppSettings.showStatusBar.value) {
            applyStatusBarVisibility(window, showStatusBar = false)
        }
    }

    override fun onStart() {
        super.onStart()
        startForegroundService(Intent(this, CoreService::class.java))
    }

    /** singleTop 复用实例时新意图也走这里（通知栏快捷操作在后台点下时）。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_NEW_CHAT) vm.createSession()
        // 完成通知点按：带回那个会话（存在才跳，空 = 只打开应用）
        intent?.getStringExtra(EXTRA_SESSION_ID)?.takeIf { it.isNotBlank() }?.let { vm.switchSession(it) }
        // 入站分享 / 选中文本：预填输入卡（不建会话不自动发送，见 ChatViewModel.onExternalContent）
        when (intent?.action) {
            Intent.ACTION_SEND -> vm.onExternalContent(
                intent.getCharSequenceExtra(Intent.EXTRA_TEXT),
                listOfNotNull(
                    androidx.core.content.IntentCompat.getParcelableExtra(
                        intent, Intent.EXTRA_STREAM, Uri::class.java,
                    ),
                ),
            )
            Intent.ACTION_SEND_MULTIPLE -> vm.onExternalContent(
                intent.getCharSequenceExtra(Intent.EXTRA_TEXT),
                androidx.core.content.IntentCompat.getParcelableArrayListExtra(
                    intent, Intent.EXTRA_STREAM, Uri::class.java,
                ).orEmpty(),
            )
            Intent.ACTION_PROCESS_TEXT -> vm.onExternalContent(
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT),
                emptyList(),
            )
        }
    }

    companion object {
        /** 通知栏「新建对话」快捷动作（CoreService 的 notification 挂着这个 PendingIntent）。 */
        const val ACTION_NEW_CHAT = "com.hxy.codeforge.MAIN_NEW_CHAT"

        /** 完成通知点按携带的会话 id（CoreService.postDoneNotification 写入）。 */
        const val EXTRA_SESSION_ID = "com.hxy.codeforge.SESSION_ID"
    }
}

/**
 * 窗口请求方向判定：
 * - 手机或关闭「横持时旋转界面」：锁定竖屏（手机开启时由 RotateWithDevice 做内容旋转）；
 * - 平板且开启「横持时旋转界面」：放开给系统（竖屏切手机单栏、横屏切平板双栏）。
 */
internal fun resolveRequestedOrientation(isTablet: Boolean, rotateWithDevice: Boolean): Int =
    if (isTablet && rotateWithDevice) {
        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    } else {
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
