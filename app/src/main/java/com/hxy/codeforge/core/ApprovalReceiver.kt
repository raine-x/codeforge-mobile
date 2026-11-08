package com.hxy.codeforge.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 审批通知「批准/拒绝」快操作的落点（manifest 注册，exported=false）。
 *
 * 只做两件事：查 [LiveTransport] 把决定经已连的 WS 发回内核
 * （帧名/字段契约见 [CoreTransport.decideApproval]），然后撤掉这条通知。
 *
 * transport 不在场时静默丢弃：onCleared 会把审批通知撤光，这里理论上收不到；
 * 真收到也不能在这里跳 App —— Android 10+ 禁止后台启动 Activity，用户点
 * 通知正文自然会把应用带回来。extra 缺失按「拒绝」处理：误拒绝可重试，
 * 误批准的写操作撤不回来。
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
