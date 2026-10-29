package app.codeforge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import app.codeforge.core.CoreService
import app.codeforge.ui.ChatViewModel
import app.codeforge.ui.CodeForgeApp
import app.codeforge.ui.settings.AppSettings
import app.codeforge.ui.theme.CodeForgeTheme

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
        // 平板解锁系统旋转：内容旋转层在平板上已关掉（见 RotateWithDevice），
        // 但 manifest 里锁的是 portrait —— 不解锁的话平板横过来窗口仍是竖的，
        // 等于既没有系统横屏、也没有内容旋转，布局直接错位。
        if (resources.configuration.smallestScreenWidthDp >= 600) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
        enableEdgeToEdge()

        // 平板交给系统旋转，手机保持锁竖屏。
        //
        // 锁竖屏是为了配合「横持旋转内容」：手机横过来时旋转的是内容而不是窗口。
        // 但平板宽度本来就够，锁竖屏会让系统把应用 letterbox 成中间一条窄窗
        // （真机实测：1600px 宽的平板只用到约 800px），双栏布局根本触发不了。
        //
        // ⚠️ 必须用 getRealMetrics 拿**物理屏幕**宽度：被 letterbox 之后，
        // resources.configuration.screenWidthDp 返回的是那个窄窗口的宽度，
        // 按它判断会永远认为是手机、永远锁竖屏 —— 死循环。
        @Suppress("DEPRECATION")
        val dm = android.util.DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(dm)
        val physicalWidthDp = dm.widthPixels / dm.density
        requestedOrientation = if (physicalWidthDp >= 600) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

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
                    val controller = WindowInsetsControllerCompat(window, window.decorView)
                    if (showStatusBar) {
                        controller.show(WindowInsetsCompat.Type.statusBars())
                    } else {
                        // 允许用户从顶部上滑临时唤出状态栏，不用退出沉浸去看时间
                        controller.systemBarsBehavior =
                            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        controller.hide(WindowInsetsCompat.Type.statusBars())
                    }
                }
                CodeForgeApp(vm)
            }
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
    }

    companion object {
        /** 通知栏「新建对话」快捷动作（CoreService 的 notification 挂着这个 PendingIntent）。 */
        const val ACTION_NEW_CHAT = "app.codeforge.MAIN_NEW_CHAT"
    }
}
