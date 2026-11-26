package com.hxy.codeforge.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.dp
import com.hxy.codeforge.model.ApprovalCard
import com.hxy.codeforge.model.ChatMessage
import com.hxy.codeforge.model.MessageSegment
import com.hxy.codeforge.model.MsgRole
import com.hxy.codeforge.model.ToolCall
import com.hxy.codeforge.model.ToolState
import com.hxy.codeforge.ui.common.Glyph
import com.hxy.codeforge.ui.common.GlyphIcon
import com.hxy.codeforge.ui.common.noRippleClickable
import com.hxy.codeforge.ui.theme.Clay
import kotlinx.coroutines.launch

/**
 * 消息列表。
 *
 * ## 贴底跟随：这是最容易做错的地方
 *
 * 朴素做法是「有新内容就滚到底」，但那样用户上滑看历史时会被不断拽回来，
 * 根本没法读。这里的规则：
 *
 *  - **只有内容超出视口时才需要跟随**（[canScroll]）——内容不足一屏时列表
 *    根本滚不动，任何「是否到底」的判断都没有意义，一律视为在底部；
 *  - 用户**手指拖动**即停止自动跟随（[autoFollow] = false），不管当时是否在底部；
 *  - 拖动结束且回到底部 → 恢复跟随；
 *  - 流式增高时仅在跟随状态使用瞬时 `scrollToItem`，避免高频动画互相打断抖动；
 *  - 新消息到达时若未跟随则不动视口，只高亮「回到最新」按钮；出现审批卡片时强制滚到底；
 *  - 末尾放置 1dp 哨兵 item，确保长消息的底部能完整滚入视口（见 P-002）；
 *  - 将连续纯只读工具消息跨消息折叠为 [FeedRow.Explore]（遇正文立即断开）。
 */
@Composable
fun MessageList(
    messages: List<ChatMessage>,
    approvals: List<ApprovalCard>,
    onApprovalDecide: (id: String, allow: Boolean) -> Unit,
    onRegenerate: () -> Unit,
    onEditResend: ((ChatMessage) -> Unit)? = null,
    onResendUser: ((ChatMessage) -> Unit)? = null,
    onFetchOutput: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    /** agent loop 是否还在跑：跑的过程中不显示操作栏。 */
    running: Boolean = false,
    /** 发送后到首段助手输出/工具开始之间的等待阶段文案。 */
    pendingStage: String = "",
    /**
     * 断点「继续这一轮」。
     *
     * 由 B 接线：`if (state.resumeBack >= 0 && !state.running) { { vm.continueTurn() } } else null`。
     * 传 null 就完全不出现这个环 —— 不用看消息状态去猜「是不是断了」。
     */
    onContinue: (() -> Unit)? = null,
) {
    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            if (info.totalItemsCount == 0) return@derivedStateOf true
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= info.totalItemsCount - 1 &&
                last.offset + last.size <= info.viewportEndOffset + 24
        }
    }

    // 内容高度是否真的超过视口。不足一屏时列表压根滚不动（两个方向都不可滚），
    // 此时「是否到底」永远算作在底部 —— 否则 autoFollow 会被误设成 false，
    // 后面的流式输出就再也不跟了（短回复时最容易复现）。
    // 用 canScrollForward/Backward 而不是 layoutInfo 的偏移量：
    // 本版本 Compose 的 LazyListLayoutInfo 没有 maxScrollOffset。
    val canScroll by remember {
        derivedStateOf { listState.canScrollForward || listState.canScrollBackward }
    }
    val atBottom = isAtBottom || !canScroll

    var autoFollow by remember { mutableStateOf(true) }

    // 不跟随时来了新内容（或流式增长）→ 在「回到最新」按钮上点亮描边，
    // 让用户知道下面还有东西，但视口不动。
    var hasNew by remember { mutableStateOf(false) }

    // 只用「用户手指在拖」来判定，不能用 isScrollInProgress ——
    // 后者把程序化滚动也算进去：流式跟随里我们自己调的 scrollToItem 会让它
    // 短暂为 true，而此时 isAtBottom 还是 false，于是 autoFollow 被误设成 false，
    // 跟随就此断掉（表现为「流式输出几行后就不跟了」）。
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(isDragged) {
        if (isDragged) {
            // 手指一动就停跟随，不看当时是否算到底：上滑过程中的中间位置
            // 可能仍落在 24dp 容差内，此时若「保持跟随」会被立刻拽回底部，
            // 表现为滑不动。
            autoFollow = false
        } else {
            autoFollow = atBottom
            // 已经回到底部，新内容提示自然作废，避免按钮在下次上滑时误亮
            if (atBottom) hasNew = false
        }
    }

    val rows = remember(messages, approvals) { buildFeedRows(messages, approvals) }
    val lastMessage = messages.lastOrNull()
    val assistantHasVisibleStreaming = lastMessage != null &&
        lastMessage.role == MsgRole.Assistant &&
        lastMessage.streaming &&
        (lastMessage.text.isNotBlank() || !lastMessage.thinking.isNullOrBlank() ||
            lastMessage.tools.isNotEmpty() || lastMessage.segments.isNotEmpty())
    val effectivePending = when {
        assistantHasVisibleStreaming -> ""
        pendingStage.isNotBlank() -> pendingStage
        running -> "正在连接模型，等待响应…"
        else -> ""
    }
    // 跟随的触发键必须覆盖正文 + 思考 + 工具输出 + 片段数：
    // 只看 text.length 时，思考/工具阶段的内容增长不会触发滚动，表现为「不动」。
    val streamKey = lastMessage?.let {
        it.text.length + (it.thinking?.length ?: 0) +
            it.tools.sumOf { t -> t.output?.length ?: 0 } +
            it.segments.size
    } ?: 0
    val itemCount = rows.size + (if (effectivePending.isNotEmpty()) 1 else 0) + 1

    // 新消息入列：跟随时滚到底；不跟随时不动视口，只点亮提示。
    // 不再把新问题顶到视口上方 —— 下面没有消息时，回复被拉到屏幕顶部
    // 本来就是不合理的布局。
    LaunchedEffect(messages.size, effectivePending.isNotEmpty()) {
        if (messages.isEmpty()) return@LaunchedEffect
        if (autoFollow) {
            hasNew = false
            listState.animateScrollToItem((itemCount - 1).coerceAtLeast(0))
        } else {
            hasNew = true
        }
    }

    // 流式增长：瞬时跟随，避免动画互相打断；内容不足一屏时无需滚（也滚不动）
    LaunchedEffect(streamKey) {
        if (autoFollow && lastMessage?.streaming == true) {
            if (canScroll) {
                listState.scrollToItem((itemCount - 1).coerceAtLeast(0))
            }
        } else if (!autoFollow && lastMessage?.streaming == true) {
            hasNew = true
        }
    }

    // 帧节奏平滑吐字与表格动态舒展可能在末包抵达后的几帧继续增高气泡，持续追踪布局底边防止末行溢出视口外
    LaunchedEffect(autoFollow, lastMessage?.streaming, itemCount) {
        if (!autoFollow || lastMessage?.streaming != true) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            Triple(info.totalItemsCount, last?.index ?: -1, (last?.offset ?: 0) + (last?.size ?: 0))
        }.collect {
            if (listState.canScrollForward) {
                listState.scrollToItem((itemCount - 1).coerceAtLeast(0))
            }
        }
    }

    // 审批卡出现：无条件滚到底，否则用户根本不知道 agent 在等他
    LaunchedEffect(approvals.size) {
        if (approvals.isNotEmpty()) {
            autoFollow = true
            hasNew = false
            listState.animateScrollToItem((itemCount - 1).coerceAtLeast(0))
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        val scope = rememberCoroutineScope()

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // 右侧多留 6dp：系统边缘滑动指示器（overscroll indicator）画在
            // 屏幕最右缘，不留的话它正好压在内容上，看着像一根白条。
            contentPadding = PaddingValues(start = 16.dp, end = 22.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(18.dp),
        ) {
            items(
                items = rows,
                key = { it.key },
                contentType = { row ->
                    when (row) {
                        is FeedRow.Msg -> row.message.role
                        is FeedRow.Approve -> "approval"
                        is FeedRow.Explore -> "explore"
                        is FeedRow.ProcessGroup -> "process-group"
                    }
                },
            ) { row ->
                when (row) {
                    is FeedRow.Msg -> {
                        val msg = row.message
                        // 重试只在「这条回复前面还有用户消息」时才有意义；找不到就静默隐藏该能力。
                        // 走内核 regenerate 语义，不重发原文 —— 否则历史里会出现重复的用户气泡。
                        val canRegenerate = remember(messages, row.index) {
                            var i = row.index - 1
                            while (i >= 0 && messages[i].role != MsgRole.User) i--
                            i >= 0
                        }
                        val isLast = row.index == messages.lastIndex
                        MessageBubble(
                            message = msg,
                            // 仅在回合收尾的最后一条助手消息下方挂载操作栏
                            showActions = msg.role == MsgRole.Assistant &&
                                !msg.streaming &&
                                isLast &&
                                !running &&
                                (msg.text.isNotBlank() || msg.tools.isNotEmpty() ||
                                    msg.segments.any { it is MessageSegment.Text }),
                            showDisclaimer = isLast &&
                                msg.role == MsgRole.Assistant &&
                                !msg.streaming &&
                                (msg.text.isNotBlank() || msg.segments.any { it is MessageSegment.Text }),
                            onRetry = { if (canRegenerate) onRegenerate() },
                            onEditResend = onEditResend,
                            onResendUser = onResendUser,
                            onFetchOutput = onFetchOutput,
                            // 禁用 animateItem，防止键盘开合或流式增高重排时触发位移动画闪烁
                            modifier = modifier,
                        )
                    }

                    is FeedRow.Approve -> ApprovalCardView(
                        card = row.card,
                        onDecide = { allow -> onApprovalDecide(row.card.id, allow) },
                    )

                    is FeedRow.Explore -> ExploreGroup(
                        tools = row.tools,
                        onFetchOutput = onFetchOutput,
                        stateKey = row.key,
                    )

                    is FeedRow.ProcessGroup -> ProcessGroupRowContent(
                        messages = row.messages,
                        onFetchOutput = onFetchOutput,
                    )
                }
            }

            if (effectivePending.isNotEmpty()) {
                item(key = "__pending__") {
                    PendingResponseRow(stage = effectivePending)
                }
            }

            // 1dp 尾部哨兵，供 scrollToItem 滚到最后一条结尾且不留大段空白（见 P-002）
            item(key = "__tail__") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp),
                )
            }
        }

        // 断点「继续这一轮」：环里是右向箭头（不是刷新），落在左下角，
        // 与右下角的「回到最新」分开，两个都出现时不会叠在一起。
        AnimatedVisibility(
            visible = onContinue != null && !running,
            enter = fadeIn(tween(160)) + scaleIn(tween(160), initialScale = 0.8f),
            exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.8f),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                    .noRippleClickable { onContinue?.invoke() },
                contentAlignment = Alignment.Center,
            ) {
                GlyphIcon(glyph = Glyph.Resume, size = 21.dp, color = Clay, strokeWidth = 1.6.dp)
            }
        }

        // 用户翻历史时给一个「回到最新」的入口。
        // 只在「不在底部 + 有内容」时出现，避免常驻遮挡。
        // hasNew 时描边+图标换主色：视口没动但下面确实来了新内容。
        val accent = MaterialTheme.colorScheme.primary
        AnimatedVisibility(
            visible = !atBottom && messages.isNotEmpty(),
            enter = fadeIn(tween(120)) + scaleIn(tween(120), initialScale = 0.8f),
            exit = fadeOut(tween(90)) + scaleOut(tween(90), targetScale = 0.8f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                    .then(
                        if (hasNew) Modifier.border(1.5.dp, accent, CircleShape) else Modifier,
                    )
                    .noRippleClickable {
                        autoFollow = true
                        hasNew = false
                        scope.launch {
                            listState.animateScrollToItem((itemCount - 1).coerceAtLeast(0))
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                GlyphIcon(
                    glyph = Glyph.ArrowDown,
                    size = 16.dp,
                    color = if (hasNew) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 提问后到 AI 吐出首字/工具调用前的占位行。
 *
 * 解决首次启动或每次提问后、模型首字尚未回来期间消息流下方完全空白的真空期体验。
 */
@Composable
private fun PendingResponseRow(stage: String) {
    val transition = rememberInfiniteTransition(label = "pending-stage")
    val dotColor = Clay
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(3) { i ->
                val alpha by transition.animateFloat(
                    initialValue = 0.25f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 550, delayMillis = i * 150),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "pending-dot-$i",
                )
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .drawBehind { drawCircle(dotColor.copy(alpha = alpha)) },
                )
            }
        }
        Text(
            text = stage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// 行模型

/**
 * 列表行。两类合并：
 *  - [Explore]：连续「纯只读工具独占一条助手消息」收一行「已探索 N 项」（单行已够读，保留）；
 *  - [ProcessGroup]：连续「纯过程」助手消息（只有思考/工具、没有正文）整组合并成
 *    一行「思考与执行 · N 项」—— 历史重放会把一轮拆成多条消息（工具一条、思考一条、
 *    正文一条），单条消息内的前缀折叠（MessageBubble.SegmentedContent）够不着它们，
 *    必须在行层级合并。其余消息原样成 [Msg]，审批卡挂在最后。
 */
private sealed interface FeedRow {
    val key: String

    data class Msg(val message: ChatMessage, val index: Int) : FeedRow {
        override val key: String get() = message.id
    }

    data class Explore(val tools: List<ToolCall>, val firstId: String) : FeedRow {
        override val key: String get() = "explore-$firstId"
    }

    data class ProcessGroup(val messages: List<ChatMessage>) : FeedRow {
        override val key: String get() = "pgrp-" + messages.first().id
    }

    data class Approve(val card: ApprovalCard) : FeedRow {
        override val key: String get() = "ap-${card.id}"
    }
}

/** 纯过程消息：助手、非流式、没有正文与附件，且带思考或工具（流式中的留给消息内折叠）。 */
private fun isProcessOnly(msg: ChatMessage): Boolean {
    if (msg.role != MsgRole.Assistant || msg.streaming ||
        msg.text.isNotBlank() || msg.attachments.isNotEmpty()
    ) {
        return false
    }
    if (msg.segments.isNotEmpty()) {
        return msg.segments.any { it is MessageSegment.Thinking || it is MessageSegment.Tool }
    }
    return !msg.thinking.isNullOrBlank() || msg.tools.isNotEmpty()
}

private fun toolsOf(msg: ChatMessage): List<ToolCall> =
    if (msg.segments.isNotEmpty()) {
        msg.segments.filterIsInstance<MessageSegment.Tool>().map { it.call }
    } else {
        msg.tools
    }

private fun hasThinkingOf(msg: ChatMessage): Boolean =
    if (msg.segments.isNotEmpty()) {
        msg.segments.any { it is MessageSegment.Thinking }
    } else {
        !msg.thinking.isNullOrBlank()
    }

private fun buildFeedRows(
    messages: List<ChatMessage>,
    approvals: List<ApprovalCard>,
): List<FeedRow> {
    val rows = ArrayList<FeedRow>(messages.size + approvals.size)
    val run = ArrayList<Pair<ChatMessage, Int>>()

    fun flushRun() {
        if (run.isEmpty()) return
        // 连续「只读工具独占消息」（≥2 条）保留旧行为：一行「已探索 N 项」
        // —— 它本身已是单行折叠，再包一层过程组等于双重点击。
        val toolsOnlyReadOnly = run.size >= 2 && run.all { (m, _) ->
            !hasThinkingOf(m) && toolsOf(m).isNotEmpty() &&
                toolsOf(m).all { isReadOnlyTool(it.name) && it.state != ToolState.Running }
        }
        if (toolsOnlyReadOnly) {
            val combined = run.flatMap { toolsOf(it.first) }
            rows.add(FeedRow.Explore(combined, combined.first().id))
        } else if (run.size == 1) {
            // 单条纯过程消息：独立渲染（段内 ≥2 段时 MessageBubble 自己会折叠）
            val (m, idx) = run.first()
            rows.add(FeedRow.Msg(m, idx))
        } else {
            rows.add(FeedRow.ProcessGroup(run.map { it.first }))
        }
        run.clear()
    }

    for ((index, msg) in messages.withIndex()) {
        if (isProcessOnly(msg)) {
            run.add(msg to index)
        } else {
            flushRun()
            rows.add(FeedRow.Msg(msg, index))
        }
    }
    flushRun()

    approvals.forEach { rows.add(FeedRow.Approve(it)) }
    return rows
}
