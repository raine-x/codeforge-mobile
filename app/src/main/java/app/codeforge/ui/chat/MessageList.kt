package app.codeforge.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import app.codeforge.model.ApprovalCard
import app.codeforge.model.ChatMessage
import app.codeforge.model.MsgRole
import app.codeforge.ui.common.Glyph
import app.codeforge.ui.common.GlyphIcon
import app.codeforge.ui.common.noRippleClickable
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 消息列表。
 *
 * ## 贴底跟随：这是最容易做错的地方
 *
 * 朴素做法是「有新内容就滚到底」，但那样用户上滑看历史时会被不断拽回来，
 * 根本没法读。这里的规则：
 *
 *  - 用户**手指拖动**且不在底部 → 立即停止自动跟随（[autoFollow] = false）；
 *  - 拖动结束且回到底部 → 恢复跟随；
 *  - 流式输出增长时，只在跟随状态下滚动，且用**瞬时**滚动（`scrollToItem`），
 *    因为每 20ms 一次动画会互相打断、看起来在抖；
 *  - 新消息到达时用动画滚动，有明确的「来了新内容」的观感；
 *  - 出现审批卡片时**强制**滚到底 —— 它阻塞 agent，看不到就没法继续。
 *
 * ## 为什么末尾要放一个 spacer item
 *
 * `scrollToItem(lastIndex)` 会把最后一项的**顶部**对齐视口顶部。若最后一条
 * 消息比一屏还高（长代码块、长 diff），这样滚完还是看不到它的结尾。
 * 末尾垫一个 1dp 的 item 再滚到它，才是真正的「滚到底」。
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

    var autoFollow by remember { mutableStateOf(true) }

    // 只用「用户手指在拖」来判定，不能用 isScrollInProgress ——
    // 后者把程序化滚动也算进去：流式跟随里我们自己调的 scrollToItem 会让它
    // 短暂为 true，而此时 isAtBottom 还是 false，于是 autoFollow 被误设成 false，
    // 跟随就此断掉（表现为「流式输出几行后就不跟了」）。
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(isDragged) {
        if (isDragged) {
            if (!isAtBottom) autoFollow = false
        } else {
            autoFollow = isAtBottom
        }
    }

    // 新问题置顶动画进行中时，暂停流式跟随 ——
    // 否则 streamKey 一变就用瞬时 scrollToItem 把列表拽回底部，置顶动画白做。
    var holdFollow by remember { mutableStateOf(false) }

    val lastMessage = messages.lastOrNull()
    val streamKey = lastMessage?.text?.length ?: 0
    val itemCount = messages.size + approvals.size + 1
    val density = androidx.compose.ui.platform.LocalDensity.current

    // 新消息入列：用户的新问题滚到**视口偏上**位置（约 96dp），回复在其下
    // 展开 —— 对齐 Claude；其余（回复完成等）仍滚到底
    LaunchedEffect(messages.size) {
        if (!autoFollow || messages.isEmpty()) return@LaunchedEffect
        // 刚发出的问题要顶到视口上方（约 96dp），回复在它下面展开。
        // 判据不能是「最后一条是用户消息」—— 发送时通常同时插入了助手占位消息，
        // 那样条件永远不成立，新问题会被压到上一条消息下面。
        val lastUserIdx = messages.indexOfLast { it.role == MsgRole.User }
        val justSent = lastUserIdx >= 0 && lastUserIdx >= messages.lastIndex - 1
        if (justSent) {
            holdFollow = true
            // scrollOffset 是「item 顶部距视口顶部的距离」，**正值**才是往下留白。
            // 之前写负值等于把问题滚到视口上方去了，看起来像「还在下面」。
            val topOffset = with(density) { 72.dp.toPx() }.roundToInt()
            listState.animateScrollToItem(lastUserIdx, topOffset)
            // 动画走完再恢复跟随；用 try/finally 保证被新的 size 变化取消时也能复位，
            // 否则 holdFollow 会永久停在 true，流式跟随彻底失效。
            try {
                kotlinx.coroutines.delay(600)
            } finally {
                holdFollow = false
            }
        } else {
            listState.animateScrollToItem((itemCount - 1).coerceAtLeast(0))
        }
    }

    // 流式增长：瞬时跟随，避免动画互相打断
    LaunchedEffect(streamKey) {
        if (autoFollow && !holdFollow && lastMessage?.streaming == true) {
            listState.scrollToItem((itemCount - 1).coerceAtLeast(0))
        }
    }

    // 审批卡出现：无条件滚到底，否则用户根本不知道 agent 在等他
    LaunchedEffect(approvals.size) {
        if (approvals.isNotEmpty()) {
            autoFollow = true
            listState.animateScrollToItem((itemCount - 1).coerceAtLeast(0))
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        val scope = rememberCoroutineScope()

        // 内容实际高度与视口高度：撑高逻辑要靠它判断「内容是否已超过一屏」。
        val density = androidx.compose.ui.platform.LocalDensity.current
        val viewportHeightPx = with(density) { listState.layoutInfo.viewportSize.height }
        val contentHeightPx = with(density) {
            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.let { last ->
                (last.offset + last.size).coerceAtLeast(0)
            } ?: 0
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // 右侧多留 6dp：系统边缘滑动指示器（overscroll indicator）画在
            // 屏幕最右缘，不留的话它正好压在内容上，看着像一根白条。
            contentPadding = PaddingValues(start = 16.dp, end = 22.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(18.dp),
        ) {
            itemsIndexed(
                messages,
                key = { _, m -> m.id },
                contentType = { _, m -> m.role },
            ) { index, msg ->
                // 重试只在「这条回复前面还有用户消息」时才有意义；找不到就静默隐藏该能力。
                // 走内核 regenerate 语义，不重发原文 —— 否则历史里会出现重复的用户气泡。
                val canRegenerate = remember(messages, index) {
                    var i = index - 1
                    while (i >= 0 && messages[i].role != MsgRole.User) i--
                    i >= 0
                }
                val isLast = index == messages.lastIndex
                MessageBubble(
                    message = msg,
                    // 操作栏只在 agent loop 收尾后、且只挂在最后一条助手消息上。
                    // 之前每个中间轮次（每次工具调用一条）都会挂一排，一屏能出现
                    // 三四组「复制/分享/点赞」，噪音很大。
                    showActions = msg.role == MsgRole.Assistant &&
                        !msg.streaming &&
                        isLast &&
                        !running &&
                        (msg.text.isNotBlank() || msg.tools.isNotEmpty()),
                    showDisclaimer = isLast &&
                        msg.role == MsgRole.Assistant &&
                        !msg.streaming &&
                        msg.text.isNotBlank(),
                    onRetry = { if (canRegenerate) onRegenerate() },
                    onEditResend = onEditResend,
                    onResendUser = onResendUser,
                    onFetchOutput = onFetchOutput,
                    // 不用 animateItem：键盘开合/流式增高的列表重排会被它
                    // 当成位移动画（淡出+弹簧），真机上表现为整屏闪烁
                    modifier = modifier,
                )
            }

            items(
                approvals,
                key = { "ap-${it.id}" },
                contentType = { "approval" },
            ) { card ->
                ApprovalCardView(
                    card = card,
                    onDecide = { allow -> onApprovalDecide(card.id, allow) },
                )
            }

            // 尾部撑高：让「新问题停在固定位置」在任何消息数量下都成立。
            //
            // 只有 1dp 时，内容不足一屏 → LazyColumn 没有可滚动余量 →
            // scrollToItem(lastUserIdx, offset) 根本滚不动，第二条消息只能排在
            // 第一条下面。撑到接近一屏后，任意一条消息都能被顶到视口上方，
            // 历史自然往上走。
            item(key = "__tail__") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillParentMaxHeight(0.92f),
                )
            }
        }

        // 用户翻历史时给一个「回到最新」的入口。
        // 只在「不在底部 + 有内容」时出现，避免常驻遮挡。
        AnimatedVisibility(
            visible = !isAtBottom && messages.isNotEmpty(),
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
                    .noRippleClickable {
                        autoFollow = true
                        scope.launch {
                            listState.animateScrollToItem((itemCount - 1).coerceAtLeast(0))
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                GlyphIcon(
                    glyph = Glyph.ArrowDown,
                    size = 16.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
