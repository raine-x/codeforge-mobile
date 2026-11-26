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
import com.hxy.codeforge.ui.createSession
import com.hxy.codeforge.ui.onExternalContent
import com.hxy.codeforge.ui.settings.AppSettings
import com.hxy.codeforge.ui.switchSession
import com.hxy.codeforge.ui.theme.CodeForgeTheme

/**
 * 主入口 Activity：负责窗口初始化、通知权限、拉起 [CoreService]，
 * 以及处理通知快捷操作、会话跳转与系统分享/选词意图。
 */
class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startForegroundService(Intent(this, CoreService::class.java))
        }

    private val vm: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestedOrientation = resolveRequestedOrientation(AppSettings.rotateWithDevice.value)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // CoreService 内部幂等，每次进入前台都确保服务已拉起
        startForegroundService(Intent(this, CoreService::class.java))
        handleIntent(intent)

        setContent {
            CodeForgeTheme {
                val showStatusBar = AppSettings.showStatusBar.value
                LaunchedEffect(showStatusBar) {
                    applyStatusBarVisibility(window, showStatusBar)
                }
                val rotateWithDevice = AppSettings.rotateWithDevice.value
                LaunchedEffect(rotateWithDevice) {
                    val target = resolveRequestedOrientation(rotateWithDevice)
                    if (requestedOrientation != target) {
                        requestedOrientation = target
                    }
                }
                CodeForgeApp(vm)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 弹层关闭恢复主窗焦点时重新同步状态栏隐藏态
        if (hasFocus && !AppSettings.showStatusBar.value) {
            applyStatusBarVisibility(window, showStatusBar = false)
        }
    }

    override fun onStart() {
        super.onStart()
        startForegroundService(Intent(this, CoreService::class.java))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_NEW_CHAT) vm.createSession()
        intent?.getStringExtra(EXTRA_SESSION_ID)?.takeIf { it.isNotBlank() }?.let { vm.switchSession(it) }
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
        const val ACTION_NEW_CHAT = "com.hxy.codeforge.MAIN_NEW_CHAT"
        const val EXTRA_SESSION_ID = "com.hxy.codeforge.SESSION_ID"
    }
}

internal fun resolveRequestedOrientation(rotateWithDevice: Boolean): Int =
    if (rotateWithDevice) {
        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    } else {
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
