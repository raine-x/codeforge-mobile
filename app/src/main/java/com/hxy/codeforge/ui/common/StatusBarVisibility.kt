package com.hxy.codeforge.ui.common

import android.view.View
import android.view.ViewParent
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.hxy.codeforge.ui.settings.AppSettings

/**
 * 把「状态栏/通知栏显隐」偏好同步到任意 [Window]（Activity 主窗或 ModalBottomSheet/Dialog 独立窗）。
 *
 * ModalBottomSheet 内部使用独立的 Dialog 窗口，若不同步，弹层获得焦点时系统会按默认窗口属性重新拉出状态栏。
 */
fun applyStatusBarVisibility(window: Window, showStatusBar: Boolean, anchor: View = window.decorView) {
    val controller = WindowInsetsControllerCompat(window, anchor)
    if (showStatusBar) {
        controller.show(WindowInsetsCompat.Type.statusBars())
    } else {
        // 允许用户从顶部下滑临时唤出状态栏，不用退出沉浸去看时间
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.statusBars())
    }
}

/**
 * 在 Compose Dialog / ModalBottomSheet 内容内部调用，把当前设置里的状态栏显隐同步到弹层自己的 Window。
 */
@Composable
fun SyncDialogStatusBar() {
    val view = LocalView.current
    val showStatusBar = AppSettings.showStatusBar.value
    val dialogWindow = findDialogWindow(view) ?: return
    DisposableEffect(dialogWindow, showStatusBar) {
        applyStatusBarVisibility(dialogWindow, showStatusBar, view)
        onDispose { }
    }
}

private fun findDialogWindow(view: View): Window? {
    var parent: ViewParent? = view.parent
    while (parent != null) {
        if (parent is DialogWindowProvider) return parent.window
        parent = parent.parent
    }
    return null
}
