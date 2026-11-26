package com.hxy.codeforge.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 审批通知快捷操作接收器（manifest 注册，exported=false）。
 * 通过 [LiveTransport] 将批准/拒绝决定经 WS 发回内核并撤掉对应通知；
 * extra 缺失时按拒绝处理以策安全。
 */
class ApprovalReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CoreService.ACTION_APPROVAL_DECIDE) return
        val id = intent.getStringExtra(CoreService.EXTRA_APPROVAL_ID) ?: return
        val approved = intent.getBooleanExtra(CoreService.EXTRA_APPROVAL_ALLOWED, false)

        // decideApproval 只是往 OkHttp 的 WS 入队（异步发送），广播 10 秒窗口足够
        LiveTransport.current?.decideApproval(id, approved)
        CoreService.cancelApprovalNotification(context, id)
    }
}
