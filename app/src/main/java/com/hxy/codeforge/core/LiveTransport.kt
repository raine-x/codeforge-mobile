package com.hxy.codeforge.core

/**
 * 进程级当前 transport 引用：通知栏审批快操作（[ApprovalReceiver]）把决定
 * 发回内核的唯一入口。
 *
 * 为什么不直接找 ChatViewModel：transport 的生命周期跟着 Activity 级 ViewModel
 * 走（viewModelScope 取消即 disconnect），拿不到实例就发不了决定；而审批决定
 * 必须走**已连接的那条 WS** —— 内核的审批 pending 挂在连接上，另开新连接去
 * 决议不保证被受理。应用退到后台时进程被前台服务保活、ViewModel 仍然活着，
 * holder 里就是那条活连接；ViewModel 真被销毁（onCleared）会置 null 并把
 * 审批通知撤光，不会留下按了没反应的死按钮。
 */
object LiveTransport {
    @Volatile
    var current: CoreTransport? = null
}
