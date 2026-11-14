package com.hxy.codeforge.ui

import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.hxy.codeforge.core.CoreTransport
import com.hxy.codeforge.core.EnvKit
import com.hxy.codeforge.core.KernelApi
import com.hxy.codeforge.core.ModelPresets
import com.hxy.codeforge.core.NOTICE_COMPRESS_DONE
import com.hxy.codeforge.core.NOTICE_COMPRESS_START
import com.hxy.codeforge.core.PluginInstaller
import com.hxy.codeforge.core.PluginSkills
import com.hxy.codeforge.model.BuiltinPluginInfo
import com.hxy.codeforge.model.ChatMessage
import com.hxy.codeforge.model.InstallVersionRelation
import com.hxy.codeforge.model.KernelConfigSummary
import com.hxy.codeforge.model.LibraryModel
import com.hxy.codeforge.model.MsgRole
import com.hxy.codeforge.model.NoticeKind
import com.hxy.codeforge.model.PendingPluginInstall
import com.hxy.codeforge.model.PermissionMode
import com.hxy.codeforge.model.ProjectItem
import com.hxy.codeforge.model.SessionItem
import com.hxy.codeforge.model.SubagentPolicy
import com.hxy.codeforge.ui.browser.ElementInsert
import com.hxy.codeforge.ui.browser.PickedElement
import com.hxy.codeforge.ui.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 把一个从网页里挑中的元素加进当前对话。
 *
 * 序号自增；文本插入由 Composer 侧完成（它才持有输入框的真实内容），
 * 这里只负责登记元素并发出一次插入请求。
 */
fun ChatViewModel.addElement(el: PickedElement) {
    val tagged = el.copy(index = (++elementSeq).toInt())
    insertSeq += 1
    _state.update { s ->
        s.copy(
            attachedElements = s.attachedElements + tagged,
            // 备注一起下发：Composer 把它与「元素N」一并插进输入框
            elementInsert = ElementInsert(insertSeq, tagged.ref, tagged.userNote),
        )
    }
}

/** 从待发列表里撤掉一个元素（chip 上的删除）。 */
fun ChatViewModel.removeElement(index: Int) {
    _state.update { s ->
        s.copy(attachedElements = s.attachedElements.filterNot { it.index == index })
    }
}

/**
 * 发送时追加给内核的说明。
 *
 * ## 为什么要有
 *
 * 内核协议里没有「系统提示词」这个字段（config 只有模型、温度、步数这些），
 * 所以唯一能让模型知道「这 App 支持哪些呈现格式」的办法，就是把说明
 * 混进上行文本。**混进的是上行文本，不是气泡文本** —— 用户看到的仍然是
 * 自己写的那句话。
 *
 * ## 为什么要带「元素」的布局信息
 *
 * 用户在内置浏览器里点了某个按钮，AI 并不知道那个按钮是什么。
 * 把标签、文本、矩形、选择器一并送过去，AI 才能接着讨论「这个按钮」。
 */
internal fun ChatViewModel.buildWireText(userText: String, elements: List<PickedElement>): String {
    val sb = StringBuilder()
    sb.append(userText)
    if (elements.isNotEmpty()) {
        sb.append('\n').append('\n')
        sb.append(
            elements.joinToString("\n\n") {
                "（下面是从应用内置浏览器里挑出的页面元素，编号即对话中的引用名）\n" + it.toWireBlock()
            }
        )
    }
    sb.append('\n').append('\n').append(CLIENT_CAPABILITY_PROMPT)
    return sb.toString()
}
// ---------- 文件树（GET /api/tree，depth=1 懒加载）----------

/**
 * 读取一层目录。[path] 空 = 工作区根，否则传内核返回的绝对路径（或相对路径）。
 * 读取失败写 [ChatUiState.treeError]，与「空目录」严格区分。
 */
fun ChatViewModel.loadTree(path: String = "") {
    val a = api ?: run {
        _state.update { it.copy(treeError = "内核未连接，无法读取文件树") }
        return
    }
    viewModelScope.launch {
        when (val r = a.listTree(path, depth = 1)) {
            is KernelApi.ApiResult.Ok -> _state.update {
                it.copy(
                    treeEntries = r.value.items,
                    treePath = r.value.path,
                    treeParent = r.value.parent,
                    treeError = r.value.error,
                )
            }

            is KernelApi.ApiResult.Err -> _state.update {
                it.copy(treeEntries = emptyList(), treeError = r.message)
            }
        }
    }
}

// ---------- @plan 操作面板 ----------

fun ChatViewModel.dismissActionPanel() = _state.update { it.copy(actionPanel = null) }

/** 面板里的「直接发送」：关闭面板并把这句当作普通用户消息发出去。 */
fun ChatViewModel.sendActionPanel(text: String) {
    _state.update { it.copy(actionPanel = null) }
    send(text)
}
/**
 * 编辑重发：回填输入卡，并按 checkpoints.editables 找该消息的 back 锚点
 * （按文本匹配，取最近一条）；发送时带锚点走内核 edit_user_message
 * （截断+文件回退+重跑的原语义），找不到（早于可编辑窗口）退回普通重发。
 */
fun ChatViewModel.requestEdit(message: ChatMessage) {
    val anchor = _state.value.editableAnchors
        .lastOrNull { it.text.trim() == message.text.trim() }?.back
    _state.update {
        it.copy(
            editText = message.text,
            editAttachments = message.attachments,
            editBack = anchor,
            editSeq = ++editSeqCounter,
        )
    }
}

fun ChatViewModel.clearEdit() {
    _state.update { it.copy(editText = null, editAttachments = emptyList(), editBack = null) }
}

/**
 * 外部内容到达（系统分享 ACTION_SEND/SEND_MULTIPLE、选中文本 PROCESS_TEXT）：
 * 预填进输入卡。**不建会话不自动发送** —— 冷启动时 transport 未就绪，
 * createSession 会直接失败；会话交给发送时的 awaitSession 自动闸门，
 * 草稿预填在任何时点都成立。
 *
 * 文本走 editText 回填通路（editBack 留空 = 不进编辑态，发送是普通发送）；
 * 图片按 EnvKit.stageUriToOutbox 同款口径复制进暂存区，再走 editAttachments。
 * 全部失败且有文本时仍回填文本 —— 文本是主、附件是辅。
 */
fun ChatViewModel.onExternalContent(text: CharSequence?, uris: List<Uri>) {
    val body = text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    if (body == null && uris.isEmpty()) return
    viewModelScope.launch {
        val staged = uris.mapNotNull { uri ->
            EnvKit.stageUriToOutbox(getApplication(), uri) {
                _state.update { s -> s.copy(banner = Banner("附件超过 20MiB 上限", isError = true)) }
            }
        }
        val skipped = uris.size - staged.size
        if (skipped > 0) {
            _state.update { s ->
                s.copy(banner = Banner("已跳过 $skipped 个超限或无法读取的附件", isError = true))
            }
        }
        _state.update {
            it.copy(
                editText = body,
                editAttachments = staged,
                editSeq = ++editSeqCounter,
            )
        }
    }
}

/** 原样重发：原文+附件按一条新用户消息再走一遍（运行中自动并入 steer）。 */
fun ChatViewModel.resendUser(message: ChatMessage) = send(message.text, message.attachments)

/** 历史工具卡输出回放：/api/diff 拉回 diff/output 并挂回对应卡片。 */
fun ChatViewModel.fetchToolReplay(callId: String) {
    val a = api ?: run { bannerNoKernel(); return }
    val sid = _state.value.currentSessionId
    viewModelScope.launch {
        when (val r = a.fetchToolReplay(sid, callId)) {
            is KernelApi.ApiResult.Ok -> _state.update { s ->
                val msgs = s.messages.toMutableList()
                for (i in msgs.indices.reversed()) {
                    val at = msgs[i].tools.indexOfFirst { it.id == callId }
                    if (at >= 0) {
                        // List 没有按位赋值：copy 整条消息，工具数组按位替换
                        val m = msgs[i]
                        val t = m.tools[at]
                        msgs[i] = m.copy(
                            tools = m.tools.mapIndexed { j, tc ->
                                if (j == at) {
                                    tc.copy(
                                        output = t.output ?: r.value.second,
                                        diff = t.diff ?: r.value.first,
                                    )
                                } else {
                                    tc
                                }
                            },
                        )
                        break
                    }
                }
                s.copy(messages = msgs)
            }
            is KernelApi.ApiResult.Err ->
                _state.update { it.copy(banner = Banner(r.message, true)) }
        }
    }
}
fun ChatViewModel.draftOf(sessionId: String): String = drafts[sessionId] ?: ""

fun ChatViewModel.saveDraft(sessionId: String, text: String) {
    if (sessionId.isEmpty()) return
    if (text.isEmpty()) drafts.remove(sessionId) else drafts[sessionId] = text
}
/** 新增一条用户记忆，成功后重拉列表（内核按工作区隔离）。 */
fun ChatViewModel.addMemory(content: String) {
    val text = content.trim()
    if (text.isEmpty()) return
    val a = api ?: run { bannerNoKernel(); return }
    viewModelScope.launch {
        when (val r = a.addMemory(text)) {
            is KernelApi.ApiResult.Ok -> loadKernelExtras()
            is KernelApi.ApiResult.Err ->
                _state.update { it.copy(banner = Banner(r.message, true)) }
        }
    }
}

fun ChatViewModel.deleteMemory(id: String) {
    val a = api ?: run { bannerNoKernel(); return }
    viewModelScope.launch {
        when (val r = a.deleteMemory(id)) {
            is KernelApi.ApiResult.Ok -> loadKernelExtras()
            is KernelApi.ApiResult.Err ->
                _state.update { it.copy(banner = Banner(r.message, true)) }
        }
    }
}

/** 就地编辑一条记忆（PATCH /api/memory）：成功后重拉列表把真值带回来。 */
fun ChatViewModel.updateMemory(id: String, content: String) {
    val text = content.trim()
    if (text.isEmpty()) return
    val a = api ?: run { bannerNoKernel(); return }
    viewModelScope.launch {
        when (val r = a.updateMemory(id, text)) {
            is KernelApi.ApiResult.Ok -> {
                loadKernelExtras()
                toast("记忆已保存")
            }

            is KernelApi.ApiResult.Err ->
                _state.update { it.copy(banner = Banner(r.message, true)) }
        }
    }
}
/**
 * 手动压缩当前会话上下文。
 *
 * 内核压缩要调用一次模型生成摘要，几秒到几十秒不等 —— 期间必须给反馈，
 * 否则用户以为点了没反应。若用户刚无工作区新建对话、`session` 帧尚在路上，
 * 先等 [newSessionGate] 落定，避免把空 id 发给内核落空。
 */
fun ChatViewModel.compressContext(onResult: ((ok: Boolean, message: String) -> Unit)? = null) {
    val a = api ?: run {
        bannerNoKernel()
        onResult?.invoke(false, "内核未连接")
        return
    }
    viewModelScope.launch {
        if (_state.value.currentSessionId.isBlank()) {
            val gate = newSessionGate
            if (gate != null) {
                withTimeoutOrNull(8_000) { gate.await() }
            }
        }
        val sid = _state.value.currentSessionId
        if (sid.isBlank() && _state.value.messages.isEmpty()) {
            val msg = "当前没有活动会话可压缩"
            toast(msg, isError = true)
            onResult?.invoke(false, msg)
            return@launch
        }
        compressStartShown = true
        lastCompressFrameAt = 0L
        appendNotice(NOTICE_COMPRESS_START, NoticeKind.Compress)
        val requestAt = System.currentTimeMillis()
        when (val r = a.compressContext(sid.ifBlank { null })) {
            is KernelApi.ApiResult.Ok -> {
                // 压缩后主动拉一次占用（内核压缩路径不总是回推 context 帧）
                transport?.pullContext()
                onResult?.invoke(true, r.value.ifBlank { NOTICE_COMPRESS_DONE })
                // 完成提示优先用内核 compress 帧；3 秒内没等到才本地补，
                // 避免与帧重复（帧先到还是响应先到，两种顺序都会出现）
                launch {
                    delay(3_000)
                    if (lastCompressFrameAt < requestAt) {
                        // API 的文案是「已压缩：before → after tokens（摘要 +n）」
                        val detail = r.value.removePrefix("已压缩：").trim()
                        val msg = if (detail.isNotBlank()) "$NOTICE_COMPRESS_DONE · $detail" else NOTICE_COMPRESS_DONE
                        appendNotice(msg, NoticeKind.Compress)
                        compressStartShown = false
                    }
                }
            }
            is KernelApi.ApiResult.Err -> {
                compressStartShown = false
                appendNotice("压缩失败：${r.message}", NoticeKind.Warn)
                onResult?.invoke(false, r.message)
            }
        }
    }
}
