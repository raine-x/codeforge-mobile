package com.hxy.codeforge.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 *  - 流式输出增长时，只在跟随状态下滚动，且用**瞬时**滚动（`scrollToItem`），
 *    因为每 20ms 一次动画会互相打断、看起来在抖；
 *  - 新消息到达时：跟随时用动画滚到底；不跟随时**不动视口**，只点亮「回到最新」
 *    按钮（主色描边）提示有新内容；
 *  - 出现审批卡片时**强制**滚到底 —— 它阻塞 agent，看不到就没法继续。
 *
 * ## 为什么末尾要放一个 spacer item
 *
 * `scrollToItem(lastIndex)` 会把最后一项的**顶部**对齐视口顶部。若最后一条
 * 消息比一屏还高（长代码块、长 diff），这样滚完还是看不到它的结尾。
 * 末尾垫一个 1dp 的哨兵 item 再滚到它，才是真正的「滚到底」；它**只是哨兵**，
 * 不撑高内容，所以回复不会被拉到屏幕顶部去。
 *
 * ## 探索分组为什么在列表层做
 *
 * 内核事件里「一次工具调用 = 一条助手消息」的情况仍然存在（segments 为空）。
 * 只把连续只读工具收成一行必须在**跨消息**的顺序上判断，所以这里先把
 * `messages` 摊成 [FeedRow]：连续「只有只读工具、没有正文/思考」的消息合并成一个
 * [FeedRow.Explore]。合并只在相邻消息之间发生，遇到任何正文消息立刻断开 ——
 * 用户永远知道「这些工具是在回答哪句话时跑的」。
 *
 * 空状态不在这里渲染：外层（AppRoot）用 AnimatedContent 在「问候页 ↔ 列表」
 * 之间做交叉淡入，这里只负责有内容的情况。
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
    // 跟随的触发键必须覆盖正文 + 思考 + 工具输出 + 片段数：
    // 只看 text.length 时，思考/工具阶段的内容增长不会触发滚动，表现为「不动」。
    val streamKey = lastMessage?.let {
        it.text.length + (it.thinking?.length ?: 0) +
            it.tools.sumOf { t -> t.output?.length ?: 0 } +
            it.segments.size
    } ?: 0
    val itemCount = rows.size + 1

    // 新消息入列：跟随时滚到底；不跟随时不动视口，只点亮提示。
    // 不再把新问题顶到视口上方 —— 下面没有消息时，回复被拉到屏幕顶部
    // 本来就是不合理的布局。
    LaunchedEffect(messages.size) {
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
                            // 操作栏只在 agent loop 收尾后、且只挂在最后一条助手消息上。
                            // 之前每个中间轮次（每次工具调用一条）都会挂一排，一屏能出现
                            // 三四组「复制/分享/点赞」，噪音很大。
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
                            // 不用 animateItem：键盘开合/流式增高的列表重排会被它
                            // 当成位移动画（淡出+弹簧），真机上表现为整屏闪烁
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
                }
            }

            // 尾部哨兵：1dp，只用于让 scrollToItem 能滚到「最后一条的结尾」。
            // 之前这里撑到 0.92 屏高，导致内容不足一屏时也能被拉出近一屏空白，
            // 回复因此能被拉到屏幕顶部 —— 用户明确不认可这种布局。
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

// ---------------------------------------------------------------- 行模型

/**
 * 列表行。把「连续只读工具独占一条助手消息」的片段合并成 [Explore]，
 * 其余消息原样成 [Msg]，审批卡挂在最后。
 */
private sealed interface FeedRow {
    val key: String

    data class Msg(val message: ChatMessage, val index: Int) : FeedRow {
        override val key: String get() = message.id
    }

    data class Explore(val tools: List<ToolCall>, val firstId: String) : FeedRow {
        override val key: String get() = "explore-$firstId"
    }

    data class Approve(val card: ApprovalCard) : FeedRow {
        override val key: String get() = "ap-${card.id}"
    }
}

/**
 * 摊平消息列表。
 *
 * 只有「纯只读工具消息」参与合并：
 *  - 没有正文、没有思考（有正文就说明这一轮已经把结论说出来了，分组必须断开）；
 *  - 工具全为只读且**没有在跑**（运行中的要露出实时状态，折叠就看丢了）；
 *  - 至少两条（单条工具直接显示独立卡片，比「已探索 1 项」更好读）。
 */
private fun buildFeedRows(
    messages: List<ChatMessage>,
    approvals: List<ApprovalCard>,
): List<FeedRow> {
    val rows = ArrayList<FeedRow>(messages.size + approvals.size)
    val pendingMsgs = ArrayList<Pair<ChatMessage, Int>>()
    val pendingTools = ArrayList<ToolCall>()

    fun flushExplore() {
        if (pendingMsgs.isEmpty()) return
        if (pendingMsgs.size >= 2) {
            rows.add(FeedRow.Explore(pendingTools.toList(), pendingTools.first().id))
        } else {
            // 只有一条：独立卡片比「已探索 1 项」更好读，折回去
            val (msg, idx) = pendingMsgs.first()
            rows.add(FeedRow.Msg(msg, idx))
        }
        pendingMsgs.clear()
        pendingTools.clear()
    }

    for ((index, msg) in messages.withIndex()) {
        val segmentTools = msg.segments.filterIsInstance<MessageSegment.Tool>().map { it.call }
        val tools = if (msg.segments.isNotEmpty()) segmentTools else msg.tools
        val segmentsAreToolsOnly =
            msg.segments.isEmpty() || msg.segments.all { it is MessageSegment.Tool }
        val mergeable = msg.role == MsgRole.Assistant &&
            !msg.streaming &&
            msg.text.isBlank() &&
            msg.thinking.isNullOrBlank() &&
            segmentsAreToolsOnly &&
            tools.isNotEmpty() &&
            tools.all { isReadOnlyTool(it.name) } &&
            tools.none { it.state == ToolState.Running }

        if (mergeable) {
            pendingMsgs.add(msg to index)
            pendingTools.addAll(tools)
        } else {
            flushExplore()
            rows.add(FeedRow.Msg(msg, index))
        }
    }
    flushExplore()

    approvals.forEach { rows.add(FeedRow.Approve(it)) }
    return rows
}
