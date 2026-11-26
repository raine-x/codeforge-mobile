package com.hxy.codeforge.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 接收通知栏的「确认允许/拒绝」与「任务暂停/继续/立即停止」广播。
 * 缺失布尔 extra 默认按拒绝处理（见 P-056）。
 */
class PhoneActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            PhoneConfirmationGate.ACTION_CONFIRM_DECISION -> {
                val id = intent.getStringExtra(PhoneConfirmationGate.EXTRA_CONFIRM_ID).orEmpty()
                if (id.isNotBlank()) {
                    val allow = intent.getBooleanExtra(PhoneConfirmationGate.EXTRA_CONFIRM_ALLOW, false)
                    PhoneConfirmationGate.resolve(id, allow)
                }
            }
            PhoneTaskController.ACTION_TASK_PAUSE -> PhoneTaskController.pause("已在通知栏暂停")
            PhoneTaskController.ACTION_TASK_RESUME -> PhoneTaskController.resume()
            PhoneTaskController.ACTION_TASK_STOP -> PhoneTaskController.stop("已在通知栏主动停止")
        }
    }
}
