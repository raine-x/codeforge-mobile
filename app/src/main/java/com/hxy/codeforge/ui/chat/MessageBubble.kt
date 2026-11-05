package com.hxy.codeforge.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hxy.codeforge.model.ChatMessage
import com.hxy.codeforge.model.MessageSegment
import com.hxy.codeforge.model.MsgRole
import com.hxy.codeforge.model.NoticeKind
import com.hxy.codeforge.model.ToolCall
import com.hxy.codeforge.model.ToolState
import com.hxy.codeforge.ui.common.Glyph
import com.hxy.codeforge.ui.common.GlyphIcon
import com.hxy.codeforge.ui.common.Sparkle
import com.hxy.codeforge.ui.common.ThumbIcon
import com.hxy.codeforge.ui.common.noRippleClickable
import com.hxy.codeforge.ui.theme.Clay
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 一条消息，对齐 Claude 客户端的消息形态：
 *
 *  - 用户消息：右对齐的暖灰圆角气泡（Claude 不给用户气泡上色）；
 *  - 助手消息：无气泡通栏排版，衬线正文；「思考过程」默认收起成一行，
 *    思考流式期间自动展开、正文开始后自动收起（用户手动切换过则尊重用户）；
 *  - 完整回复下方是操作行（复制/分享/朗读/赞/踩/重试）；
 *  - 最后一条完整回复下挂 AI 免责声明。
 *
 * 所有文本包在 [SelectionContainer] 里 —— 长按复制是最高频的需求。
 */
@Composable
fun MessageBubble(
    message: ChatMessage,
    showActions: Boolean,
    showDisclaimer: Boolean,
    onRetry: () -> Unit,
    onEditResend: ((ChatMessage) -> Unit)? = null,
    onResendUser: ((ChatMessage) -> Unit)? = null,
    onFetchOutput: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    when (message.role) {
        MsgRole.User -> UserBubble(message, onEditResend, onResendUser, modifier)
        MsgRole.Assistant ->
            AssistantBlock(message, showActions, showDisclaimer, onRetry, onFetchOutput, modifier)
        MsgRole.Notice -> NoticeBubble(message, modifier)
    }
}

// ---------------------------------------------------------------- 通知行

/**
 * 流程性通知行（重试/压缩/回滚/转向/提示）。
 *
 * 视觉上必须与气泡明确区分：它是「系统的旁白」，不是谁说的话 ——
 * 居中、无气泡、小字、按种类着色。重试行带一枚慢转的图标，
 * 压缩行用陶土橘强调（上下文状态变化值得被看见）。
 */
@Composable
private fun NoticeBubble(message: ChatMessage, modifier: Modifier) {
    val kind = message.kind ?: NoticeKind.Info
    val accent = when (kind) {
        NoticeKind.Retry -> MaterialTheme.colorScheme.onSurfaceVariant
        NoticeKind.Compress -> Clay
        NoticeKind.Rewind -> MaterialTheme.colorScheme.onSurfaceVariant
        NoticeKind.Steer -> Clay
        NoticeKind.Warn -> MaterialTheme.colorScheme.secondary
        NoticeKind.Info -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val spin = rememberInfiniteTransition(label = "notice-spin")
        val rotation by spin.animateFloat(
            0f, 360f,
            infiniteRepeatable(tween(1_400, easing = androidx.compose.animation.core.LinearEasing)),
            label = "notice-rot",
        )

        when (kind) {
            NoticeKind.Retry -> GlyphIcon(
                glyph = Glyph.Retry,
                size = 12.dp,
                color = accent,
                modifier = Modifier
                    .padding(end = 5.dp)
                    .rotate(rotation),
            )

            NoticeKind.Steer -> GlyphIcon(
                glyph = Glyph.ChatBubble,
                size = 12.dp,
                color = accent,
                modifier = Modifier.padding(end = 5.dp),
            )

            else -> Box(
                modifier = Modifier
                    .padding(end = 5.dp)
                    .size(5.dp)
                    .background(accent, CircleShape),
            )
        }

        Text(
            text = message.text,
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------- 用户

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun UserBubble(
    message: ChatMessage,
    onEditResend: ((ChatMessage) -> Unit)?,
    onResendUser: ((ChatMessage) -> Unit)?,
    modifier: Modifier,
) {
    var actionsOpen by remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<String?>(null) }
    // 「缩略」：长提问默认整段铺开会把后续对话顶到很远，折成 5 行后仍可展开。
    // 只有真的长才给这个入口 —— 短消息上多一个图标反而让人犹豫。
    var collapsed by rememberSaveable(message.id) { mutableStateOf(false) }
    val canCollapse = message.text.length > 260 || message.text.count { it == '\n' } > 6
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // 长按/点气泡切换操作栏（参考 Grok：操作直接落在气泡下方，不弹菜单）。
    // 长按仍交给 SelectionContainer 选文字，两者不冲突。
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Spacer(modifier = Modifier.weight(1f))
        Column(
            modifier = Modifier.weight(4f, fill = false),
            horizontalAlignment = Alignment.End,
        ) {
            Box(
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(20.dp),
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { actionsOpen = !actionsOpen }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (message.text.isNotEmpty()) {
                        SelectionContainer {
                            Text(
                                text = message.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = if (collapsed) 5 else Int.MAX_VALUE,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    // 附件：图片类出缩略图（点开全屏），其余出文件片
                    message.attachments.forEach { att ->
                        if (att.isImagePath()) {
                            LocalAttachmentThumb(path = att, onClick = { viewer = att })
                        } else {
                            AttachmentFileChip(att)
                        }
                    }
                }
            }

            // 操作栏：气泡正下方右对齐，点一下出现、再点收起。
            // 图标顺序固定为「缩略 / 编辑 / 复制 / 重发」，全部是纯描边的精简线框 ——
            // 早期那版铅笔带实心笔尖、重发借用刷新箭头，观感偏「可爱化」，用户要求换掉。
            AnimatedVisibility(
                visible = actionsOpen,
                enter = fadeIn(tween(120)) + expandVertically(tween(160)),
                exit = fadeOut(tween(100)) + shrinkVertically(tween(140)),
            ) {
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (canCollapse) {
                        MsgAction(
                            glyph = Glyph.Collapse,
                            rotation = if (collapsed) 180f else 0f,
                        ) { collapsed = !collapsed }
                    }
                    if (onEditResend != null) {
                        MsgAction(Glyph.Edit) {
                            actionsOpen = false
                            onEditResend(message)
                        }
                    }
                    MsgAction(Glyph.Copy) {
                        scope.launch {
                            clipboard.setClipEntry(
                                androidx.compose.ui.platform.ClipEntry(
                                    android.content.ClipData.newPlainText("text", message.text),
                                ),
                            )
                        }
                        actionsOpen = false
                    }
                    if (onResendUser != null) {
                        MsgAction(Glyph.Resend) {
                            actionsOpen = false
                            onResendUser(message)
                        }
                    }
                }
            }
        }
    }
    viewer?.let { FullscreenImageViewer(it) { viewer = null } }
}

// ---------------------------------------------------------------- 助手

@Composable
private fun AssistantBlock(
    message: ChatMessage,
    showActions: Boolean,
    showDisclaimer: Boolean,
    onRetry: () -> Unit,
    onFetchOutput: ((String) -> Unit)?,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 有 segments 就按「思考 → 执行 → 思考 → 执行 → 正文」的**真实发生顺序**渲染；
        // 没有就回退旧字段（历史消息 / 老内核事件只有 thinking+tools+text 三块）。
        // 旧渲染把所有工具卡堆在正文之前 —— 那正是用户反馈的「执行全在前面」。
        if (message.segments.isNotEmpty()) {
            SegmentedContent(message, onFetchOutput)
        } else {
            LegacyContent(message, onFetchOutput)
        }

        if (showActions) {
            ActionsRow(message, onRetry)
        }

        if (showDisclaimer) {
            Text(
                text = "CodeForge 是 AI，生成内容可能出错。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * 按 [ChatMessage.segments] 的顺序渲染正文流。
 *
 * 连续的只读工具（read_file / list_dir / search_files / web 读取）在这里收成
 * 「已探索 N 项」一块；写类工具与正文、思考一样各占自己的位置 —— 分组只在
 * 相邻只读工具之间发生，绝不跨过一段正文或思考（否则「什么时候看的」就丢了）。
 */
@Composable
private fun ColumnScope.SegmentedContent(
    message: ChatMessage,
    onFetchOutput: ((String) -> Unit)?,
) {
    val segments = message.segments
    var i = 0
    while (i < segments.size) {
        when (val seg = segments[i]) {
            is MessageSegment.Thinking -> {
                if (seg.text.isNotBlank()) {
                    ThinkingBlock(
                        text = seg.text,
                        message = message,
                        live = message.streaming && i == segments.lastIndex,
                        stateKey = "thinking-$i",
                    )
                }
                i++
            }

            is MessageSegment.Text -> {
                if (seg.text.isNotBlank()) {
                    BodyText(message, seg.text, caret = message.streaming && i == segments.lastIndex)
                }
                i++
            }

            is MessageSegment.Tool -> {
                if (isReadOnlyTool(seg.call.name)) {
                    val group = ArrayList<ToolCall>()
                    val start = i
                    while (i < segments.size) {
                        val next = segments[i]
                        if (next is MessageSegment.Tool && isReadOnlyTool(next.call.name)) {
                            group.add(next.call)
                            i++
                        } else {
                            break
                        }
                    }
                    if (group.size == 1) {
                        ToolCard(tool = group[0], onFetchOutput = replayOf(group[0], onFetchOutput))
                    } else {
                        ExploreGroup(
                            tools = group,
                            onFetchOutput = onFetchOutput,
                            stateKey = message.id + "-explore-" + (group.firstOrNull()?.id ?: start),
                        )
                    }
                } else {
                    ToolCard(tool = seg.call, onFetchOutput = replayOf(seg.call, onFetchOutput))
                    i++
                }
            }
        }
    }

    // 流刚开始、还没有任何正文/思考片段时给等待点；已经有工具卡就不必再画
    val hasNarrative = segments.any { it is MessageSegment.Text || it is MessageSegment.Thinking }
    val hasTool = segments.any { it is MessageSegment.Tool }
    if (message.streaming && !hasNarrative && !hasTool) WaitingIndicator()
}

/** 旧字段回退：thinking + tools + text 三段式（历史消息没有 segments）。 */
@Composable
private fun ColumnScope.LegacyContent(
    message: ChatMessage,
    onFetchOutput: ((String) -> Unit)?,
) {
    message.thinking?.takeIf { it.isNotBlank() }?.let {
        ThinkingBlock(
            text = it,
            message = message,
            live = message.streaming && message.text.isBlank(),
            stateKey = "legacy-thinking",
        )
    }

    var i = 0
    val tools = message.tools
    while (i < tools.size) {
        val tool = tools[i]
        if (isReadOnlyTool(tool.name)) {
            val group = ArrayList<ToolCall>()
            val start = i
            while (i < tools.size && isReadOnlyTool(tools[i].name)) {
                group.add(tools[i])
                i++
            }
            if (group.size == 1) {
                ToolCard(tool = group[0], onFetchOutput = replayOf(group[0], onFetchOutput))
            } else {
                ExploreGroup(
                    tools = group,
                    onFetchOutput = onFetchOutput,
                    stateKey = message.id + "-legacy-explore-" + (group.firstOrNull()?.id ?: start),
                )
            }
        } else {
            ToolCard(tool = tool, onFetchOutput = replayOf(tool, onFetchOutput))
            i++
        }
    }

    if (message.text.isNotBlank()) {
        BodyText(message, message.text, caret = message.streaming)
    }

    if (message.streaming && message.text.isBlank() && message.thinking.isNullOrBlank()) {
        WaitingIndicator()
    }
}

/** 历史工具卡（load 回来 output 为空）给「回放」入口：/api/diff 拉取。 */
private fun replayOf(tool: ToolCall, onFetchOutput: ((String) -> Unit)?): ((String) -> Unit)? =
    if (tool.output == null && tool.state == ToolState.Ok) onFetchOutput else null

/** 正文段：verbatim 走等宽纯文本，其余交给 OpenRender 块渲染。 */
@Composable
private fun BodyText(message: ChatMessage, text: String, caret: Boolean) {
    SelectionContainer {
        if (message.verbatim) {
            // 原样输出：用途见 ChatMessage.verbatim 的注释 —— 模型输出
            // markdown 源码本身时按纯文本展示，等宽字体暗示「这是代码态」
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            MessageContentBlocks(text = text, caret = caret)
        }
    }
}

// ---------------------------------------------------------------- 思考过程

/**
 * 思考过程折叠行。
 *
 * 自动展开/收起的规则（用户手动点过就 [pinned]，之后不再自动动）：
 *  - 模型正在思考、正文还没开始 → 展开，流式灰字直接可见；
 *  - 正文开始输出或整条流结束 → 自动收起，把屏幕还给回答；
 *  - 历史消息（非流式）→ 默认收起。
 *
 * 展开态**限高约 5 行**并随流式增长自动贴底滚动 —— 长思考不再把回答顶出屏幕。
 *
 * [live] 由调用方判定：这条思考是不是「整条消息里最后一段、且流还在跑」。
 * 不能再用 `message.streaming && message.text.isBlank()` 推断 —— 一轮里可能
 * 出现好几段思考，那样所有段都会同时判成「正在思考」。
 * [stateKey] 让同一消息里的多段思考各自记住展开状态。
 */
@Composable
private fun ThinkingBlock(
    text: String,
    message: ChatMessage,
    live: Boolean,
    stateKey: String,
) {
    var pinned by rememberSaveable(message.id, stateKey) { mutableStateOf(false) }
    var expanded by rememberSaveable(message.id, stateKey) { mutableStateOf(false) }

    LaunchedEffect(live) {
        if (!pinned) expanded = live
    }
    // 思考整体结束（流断掉）也收起 —— 思考和正文都完了不该再占着屏
    LaunchedEffect(message.streaming) {
        if (!message.streaming && !pinned) expanded = false
    }

    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(180),
        label = "thinking-chevron",
    )

    val scrollState = rememberScrollState()
    // 流式增长时贴底：maxValue 要等布局完成才更新，animateScrollTo 追最新值
    LaunchedEffect(text, live, expanded) {
        if (live && expanded) scrollState.animateScrollTo(scrollState.maxValue)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable {
                    pinned = true
                    expanded = !expanded
                }
                .padding(horizontal = 2.dp, vertical = 4.dp)
                .height(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (live) {
                Sparkle(size = 14.dp, color = Clay, animate = true)
            } else {
                Sparkle(size = 14.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = if (live) "正在思考…" else "思考过程",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp),
            )
            GlyphIcon(
                glyph = Glyph.Chevron,
                size = 13.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 2.dp)
                    .rotate(chevronRotation),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(140)) + expandVertically(tween(180)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(160)),
        ) {
            SelectionContainer {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // 限高 ≈ 5 行（bodySmall 13sp×~1.4 行高 ×5 ≈ 115dp），超高内部滚动
                    modifier = Modifier
                        .padding(start = 2.dp, top = 2.dp)
                        .heightIn(max = 118.dp)
                        .verticalScroll(scrollState),
                )
            }
        }
    }
}

/** 等待首个 token：三颗错峰呼吸的暖灰点。 */
@Composable
private fun WaitingIndicator() {
    val transition = rememberInfiniteTransition(label = "waiting")
    val dotColor = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { i ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 600, delayMillis = i * 160),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "waiting-$i",
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .drawBehind { drawCircle(dotColor.copy(alpha = alpha)) },
            )
        }
    }
}

// ---------------------------------------------------------------- 操作行

/**
 * 回复下方的操作行（对齐 Claude：复制/分享/朗读/赞/踩/重试）。
 *
 * 现阶段真正接了行为的是「复制」与「重试」；分享、朗读、赞、踩只做
 * 按压与选中反馈 —— UI 层先占位，等内核接入后再接 TTS 与反馈上报。
 */
@Composable
private fun ActionsRow(message: ChatMessage, onRetry: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var copied by rememberSaveable(message.id) { mutableStateOf(false) }
    var liked by rememberSaveable(message.id) { mutableStateOf(false) }
    var disliked by rememberSaveable(message.id) { mutableStateOf(false) }
    // 朗读：系统 TTS（中文），speaking 期间图标高亮、再点停止；
    // 初始化结果要收下——失败时点朗读给明确提示而不是无声无息
    var ttsReady by remember { mutableStateOf(false) }
    val tts = remember {
        runCatching {
            android.speech.tts.TextToSpeech(ctx) { st ->
                ttsReady = st == android.speech.tts.TextToSpeech.SUCCESS
            }
        }.getOrNull()
    }
    var speaking by remember(message.id) { mutableStateOf(false) }
    var ttsNotice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ttsNotice) {
        if (ttsNotice != null) {
            delay(2_600)
            ttsNotice = null
        }
    }
    DisposableEffect(Unit) { onDispose { runCatching { tts?.shutdown() } } }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1_400)
            copied = false
        }
    }

    val tint = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier.padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ActionIcon(glyph = if (copied) Glyph.Check else Glyph.Copy, tint = if (copied) Clay else tint, label = "复制") {
            // 新剪贴板 API 是 suspend 的，丢进协程；旧 API 已标废弃
            scope.launch {
                val data = android.content.ClipData.newPlainText("text", message.text)
                clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(data))
            }
            copied = true
        }
        ActionIcon(glyph = Glyph.Share, tint = tint, label = "分享") {
            // 系统分享面板（B4：分享不再是占位）
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, message.text)
            }
            runCatching { ctx.startActivity(android.content.Intent.createChooser(send, "分享回复")) }
        }
        ActionIcon(glyph = Glyph.Speaker, tint = if (speaking) Clay else tint, label = "朗读") {
            if (tts == null || !ttsReady) {
                ttsNotice = "此设备没有可用的语音合成（TTS）引擎"
                return@ActionIcon
            }
            val engine = tts
            if (speaking) {
                runCatching { engine.stop() }
                speaking = false
            } else {
                runCatching {
                    engine.language = java.util.Locale.SIMPLIFIED_CHINESE
                    // TextToSpeech 没有单参 onCompletion：完成/出错回调走
                    // UtteranceProgressListener（TTS binder 线程回调，快照写跨线程安全）
                    engine.setOnUtteranceProgressListener(
                        object : android.speech.tts.UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {}
                            override fun onDone(utteranceId: String?) { speaking = false }
                            override fun onError(utteranceId: String?) { speaking = false }
                        },
                    )
                    engine.speak(
                        message.text,
                        android.speech.tts.TextToSpeech.QUEUE_FLUSH,
                        null,
                        "speak-" + message.id,
                    )
                    speaking = true
                }
            }
        }
        ActionIcon(thumbsUp = true, active = liked, label = "赞") { liked = !liked; if (liked) disliked = false }
        ActionIcon(thumbsUp = false, active = disliked, label = "踩") { disliked = !disliked; if (disliked) liked = false }
        ActionIcon(glyph = Glyph.Retry, tint = tint, label = "重试", onClick = onRetry)
    }
    if (ttsNotice != null) {
        Text(
            text = ttsNotice.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun ActionIcon(
    glyph: Glyph? = null,
    thumbsUp: Boolean = false,
    active: Boolean = false,
    tint: Color = Color.Unspecified,
    label: String,
    onClick: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(if (active) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .noRippleClickable(enabled = onClick != null, onClick = { onClick?.invoke() }),
        contentAlignment = Alignment.Center,
    ) {
        val color = when {
            active -> MaterialTheme.colorScheme.primary
            tint != Color.Unspecified -> tint
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        if (glyph != null) {
            GlyphIcon(glyph = glyph, size = 17.dp, color = color)
        } else {
            ThumbIcon(size = 17.dp, color = color, flip = !thumbsUp)
        }
    }
}

/**
 * 消息下方的小圆角操作按钮（缩略 / 编辑 / 复制 / 重发）。
 *
 * 只放图标不放文字：气泡下方空间窄，四个图标一眼能认出来，
 * 加文字反而会把操作栏撑得比气泡还宽。图标统一 1.6dp 描边、无填充。
 */
@Composable
private fun MsgAction(glyph: Glyph, rotation: Float = 0f, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        GlyphIcon(
            glyph = glyph,
            size = 15.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            strokeWidth = 1.6.dp,
            modifier = Modifier.rotate(rotation),
        )
    }
}
