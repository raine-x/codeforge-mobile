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

/** 构造上行文本，附加用户挑选的页面元素布局、客户端能力说明与手机操控门控提示（不影响 UI 显示的用户气泡）。 */
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
    if (com.hxy.codeforge.phone.PhoneControlPrefs.config.value.masterEnabled &&
        !com.hxy.codeforge.phone.PhoneTaskController.isExplicitPhoneTurn() &&
        !com.hxy.codeforge.phone.PhoneTaskController.isPhoneControlLikelyNeeded(userText)
    ) {
        sb.append("\n\n（本轮注意：当前问题无需手机操控，请直接回答，不要调用 phone.* 工具或进入手机操控模式。）")
    }
    return sb.toString()
}
/**
 * 懒加载一层目录结构（[path] 为空时读取工作区根目录）。
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

fun ChatViewModel.dismissActionPanel() = _state.update { it.copy(actionPanel = null) }

/** 面板里的「直接发送」：关闭面板并把这句当作普通用户消息发出去。 */
fun ChatViewModel.sendActionPanel(text: String) {
    _state.update { it.copy(actionPanel = null) }
    send(text)
}
/**
 * 按消息位置计算 `edit_user_message` 所需的 `back`（距最后一条用户消息的偏移，0 = 最后一条）。
 *
 * 为什么不能只靠字符串相等查 `checkpoints.editables`：
 * 1. 内核存储的是带 `【CodeForge 客户端渲染能力】` 等注入后缀的原文，而 UI 气泡是 `stripInjectedText`
 *    之后的纯文本，直接比较会 100% 失配；
 * 2. 内核 `editables` 仅下发最近 3 条且只在特定帧推送，而 `RerunFrom` 实际支持任意 `back >= 0`；
 * 3. 同会话若有重复文本，按位置从末尾数才能命中用户点中的那一条。
 */
internal fun resolveEditBack(
    messages: List<ChatMessage>,
    editableAnchors: List<com.hxy.codeforge.model.EditableUserMessage>,
    target: ChatMessage,
): Int? {
    val cleanTarget = stripInjectedText(target.text).trim()
    val byId = messages.indexOfLast { it.id == target.id && it.role == MsgRole.User }
    val idx = if (byId >= 0) {
        byId
    } else {
        messages.indexOfLast {
            it.role == MsgRole.User && stripInjectedText(it.text).trim() == cleanTarget
        }
    }
    if (idx >= 0) {
        val ordinalFromEnd = (idx + 1 until messages.size).count { messages[it].role == MsgRole.User }
        // 若锚点表里有唯一匹配的剥离文本且仍在内核最近窗口内，以服务端 back 校准（可抵消运行中 steer 占位偏移）。
        val anchorMatches = editableAnchors.filter {
            stripInjectedText(it.text).trim() == cleanTarget
        }
        val maxAnchorBack = editableAnchors.maxOfOrNull { it.back } ?: -1
        if (anchorMatches.size == 1 && ordinalFromEnd <= maxAnchorBack) {
            return anchorMatches.single().back
        }
        return ordinalFromEnd
    }
    return editableAnchors.lastOrNull {
        stripInjectedText(it.text).trim() == cleanTarget
    }?.back
}

/**
 * 本地立即截断被编辑消息之后的所有消息（回复、工具、后续轮次），
 * 防止等待内核 `edit` + `history` 快照期间旧回复仍挂在界面上。
 */
internal fun truncateMessagesForEdit(
    messages: List<ChatMessage>,
    targetMessageId: String?,
    back: Int,
    newText: String,
): List<ChatMessage> {
    val idxById = if (!targetMessageId.isNullOrBlank()) {
        messages.indexOfLast { it.id == targetMessageId && it.role == MsgRole.User }
    } else {
        -1
    }
    val idx = if (idxById >= 0) {
        idxById
    } else {
        var remaining = back.coerceAtLeast(0)
        var found = -1
        for (i in messages.indices.reversed()) {
            if (messages[i].role == MsgRole.User) {
                if (remaining == 0) {
                    found = i
                    break
                }
                remaining--
            }
        }
        found
    }
    if (idx < 0) return messages
    val updated = messages[idx].copy(text = newText, streaming = false)
    return messages.take(idx) + updated
}

/**
 * 编辑重发：回填输入卡，并按位置/剥离后锚点计算 `back` 走内核 `edit_user_message`
 * （从被编辑消息截断后续对话 + 回退文件改动 + 重跑）。
 */
fun ChatViewModel.requestEdit(message: ChatMessage) {
    val state = _state.value
    val anchor = resolveEditBack(state.messages, state.editableAnchors, message)
    if (anchor == null) {
        toast("未定位到可回溯的用户消息", isError = true)
        return
    }
    _state.update {
        it.copy(
            editText = message.text,
            editAttachments = message.attachments,
            editBack = anchor,
            editMessageId = message.id,
            editSeq = ++editSeqCounter,
        )
    }
}

fun ChatViewModel.clearEdit() {
    _state.update {
        it.copy(
            editText = null,
            editAttachments = emptyList(),
            editBack = null,
            editMessageId = null,
        )
    }
}

/**
 * 接收系统分享或文本选中动作，将正文与附件暂存回填至输入卡（不主动新建会话或发送）。
 */
fun ChatViewModel.onExternalContent(text: CharSequence?, uris: List<Uri>) {
    val body = text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    if (body == null && uris.isEmpty()) return
    viewModelScope.launch {
        val staged = uris.mapNotNull { uri ->
            EnvKit.stageUriToOutbox(getApplication(), uri)
        }
        val skipped = uris.size - staged.size
        if (skipped > 0) {
            _state.update { s ->
                s.copy(banner = Banner("已跳过 $skipped 个无法读取的附件", isError = true))
            }
        }
        _state.update {
            it.copy(
                editText = body,
                editAttachments = staged,
                editBack = null,
                editMessageId = null,
                editSeq = ++editSeqCounter,
            )
        }
    }
}

/** 从文件管理器引用路径到输入框，不覆盖用户已写文本或已有附件。 */
fun ChatViewModel.quoteFileToInput(displayPath: String) {
    val clean = displayPath.trim()
    if (clean.isEmpty()) return
    _state.update { it.copy(elementInsert = ElementInsert(seq = ++insertSeq, ref = clean)) }
}

/** 从文件管理器把文件装入输入卡附件槽；已在当前工作区内则直接带相对路径，避免大图/大视频二次复制。 */
fun ChatViewModel.attachFileToInput(file: java.io.File, activeWorkspaceRoot: String) {
    if (!file.isFile) {
        toast("文件不存在：${file.name}", isError = true)
        return
    }
    viewModelScope.launch {
        val target = withContext(Dispatchers.IO) {
            val rootDir = activeWorkspaceRoot.takeIf { it.isNotBlank() }?.let { java.io.File(it) }
            val fileCanon = runCatching { file.canonicalFile }.getOrNull()
            val rootCanon = rootDir?.let { runCatching { it.canonicalFile }.getOrNull() }
            val prefix = rootCanon?.path?.trimEnd(java.io.File.separatorChar)?.plus(java.io.File.separator)
            if (fileCanon != null && prefix != null && fileCanon.path.startsWith(prefix)) {
                rootCanon.toPath().relativize(fileCanon.toPath()).toString().replace('\\', '/')
            } else {
                EnvKit.stageFileToOutbox(getApplication(), file)
            }
        }
        if (target == null) {
            toast("读取文件失败：${file.name}", isError = true)
            return@launch
        }
        _state.update {
            it.copy(
                editText = null,
                editAttachments = listOf(target),
                editBack = null,
                editMessageId = null,
                editSeq = ++editSeqCounter,
            )
        }
    }
}

/** 原样重发：原文+附件按一条新用户消息再走一遍（运行中自动并入 steer），先退出未发出的编辑态。 */
fun ChatViewModel.resendUser(message: ChatMessage) {
    clearEdit()
    send(message.text, message.attachments)
}

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
