package com.hxy.codeforge.core

/**
 * 进程级当前 [CoreTransport] 引用。
 * 通知栏审批操作（[ApprovalReceiver]）必须经由当前活动 WebSocket 下发决定，
 * 并在 ViewModel 销毁时置 null，避免在 detached 状态下发出无效审批。
 */
object LiveTransport {
    @Volatile
    var current: CoreTransport? = null
}
