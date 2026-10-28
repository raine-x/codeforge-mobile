package app.codeforge.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeforge.ui.chat.Composer
import app.codeforge.ui.chat.ContextMeter
import app.codeforge.ui.chat.MessageList
import app.codeforge.ui.chat.SubagentBar
import app.codeforge.ui.chat.TodoPanel
import app.codeforge.ui.common.Glyph
import app.codeforge.ui.common.ToastHost
import app.codeforge.ui.common.GlyphIcon
import app.codeforge.ui.common.LocalOrientation
import app.codeforge.ui.common.RotateWithDevice
import app.codeforge.ui.common.FlameMark
import app.codeforge.ui.common.WindowMode
import app.codeforge.ui.common.currentWindowMode
import app.codeforge.ui.common.isLandscape
import app.codeforge.ui.common.noRippleClickable
import app.codeforge.ui.sessions.SessionActionsSheet
import app.codeforge.ui.sessions.SessionPane
import app.codeforge.ui.settings.SettingsSheet
import app.codeforge.ui.theme.Clay
import app.codeforge.ui.theme.ClayDeep
import app.codeforge.core.EnvKit
import app.codeforge.ui.theme.SerifDisplay
import app.codeforge.voice.OfflineVoice
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 应用根布局。
 *
 * ## 横屏策略：窗口锁竖屏，内容跟手旋转
 *
 * Activity 在 manifest 锁死 `portrait`，系统永不转窗口；[RotateWithDevice]
 * 用加速度传感器探测物理姿态，用户把手机横过来时把整套**竖屏布局**以弹簧
 * 动画旋转 ±90°（倒持 180°），宽高对调成铺满物理屏幕。这不是「换横版双栏」
 * —— 是同一套竖屏元素跟着手转，所有布局、动画、内边距原封不动。
 *
 * ## 空态与对话态
 *
 * 没有消息时显示 Claude 式问候页（星标 + 轮换问候语），有消息时交叉淡入
 * 消息列表 —— [AnimatedContent] 只切换中间那块区域，顶栏与输入卡保持原位，
 * 视觉上输入框像是从问候页「长」进对话页的。
 */
@Composable
fun CodeForgeApp(viewModel: ChatViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var sessionActions by remember { mutableStateOf<app.codeforge.model.SessionItem?>(null) }

    Box(Modifier.fillMaxSize()) {
        RotateWithDevice {
            // 档位判定必须放在旋转层内：LocalOrientation 由它提供，
            // 横持时内容盒宽高对调，自动切到网页端同款双栏布局
            val mode = currentWindowMode()
            when (mode) {
                WindowMode.Compact -> CompactLayout(
                    state,
                    viewModel,
                    onOpenSettings = { showSettings = true },
                    onSessionLongPress = { sessionActions = it },
                    archived = state.archivedSessions,
                    onExpandArchived = viewModel::loadArchived,
                    onRestoreArchived = { id -> viewModel.unarchive(id) },
                )
                WindowMode.Medium, WindowMode.Expanded ->
                    TwoPaneLayout(
                        state,
                        viewModel,
                        mode,
                        onOpenSettings = { showSettings = true },
                        onSessionLongPress = { sessionActions = it },
                    )
            }
        }

        // 首启「环境准备中」遮罩：模型解压期间盖住整屏，完成自动消失。
        // 放在旋转层外 —— 准备阶段还没到讲横竖屏的时候。
        EnvPreparingOverlay()

        if (showSettings) {
            SettingsSheet(vm = viewModel, onDismiss = { showSettings = false })
        }

        sessionActions?.let { session ->
            SessionActionsSheet(
                session = session,
                vm = viewModel,
                onDismiss = { sessionActions = null },
            )
        }

        // 放在所有弹层之后：设置页里点保存，提示才不会被弹层盖住。
        ToastHost(
            banner = state.banner,
            onDismiss = viewModel::dismissBanner,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 150.dp)
                .padding(horizontal = 32.dp),
        )
    }
}

// ---------------------------------------------------------------- 环境准备

/**
 * 首次启动的环境准备遮罩（语音模型 + proot/Alpine 兼容环境）。
 *
 * 任一准备器处于 Preparing 态才出现：星标呼吸 + 进度条，视觉与空态问候页
 * 同族 ——「安静地等一下」，不是报错。进度条取**较慢的那个**（通常是语音
 * 模型的 44MB，环境包只有 4MB），副文案列出各自状态。失败不挡屏
 * （设置页里能看到原因并重试），成功即自动淡出。
 */
@Composable
private fun EnvPreparingOverlay() {
    val voice = OfflineVoice.status.value
    val env = EnvKit.status.value
    val voicePct = (voice as? OfflineVoice.Status.Preparing)?.percent
    val envPrep = env as? EnvKit.Status.Preparing
    val envPct = envPrep?.percent
    val visible = voicePct != null || envPct != null

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(280)),
    ) {
        // minOrNull：淡出动画期间两台准备器都已结束（双 null），min() 会抛
        // NoSuchElementException 崩首启 —— 真机首启崩溃 crash-1792943112447 即此
        val percent = listOfNotNull(voicePct, envPct).minOrNull() ?: 0
        val fill by animateFloatAsState(
            targetValue = percent / 100f,
            animationSpec = tween(240),
            label = "prep-fill",
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 48.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FlameMark(size = 52.dp, color = Clay, deepColor = ClayDeep, animated = true)
            Spacer(Modifier.height(22.dp))
            Text(
                text = "环境准备中",
                style = SerifDisplay.copy(fontSize = 22.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = buildString {
                    if (voicePct != null) append("语音模型 $voicePct%")
                    if (voicePct != null && envPct != null) append(" · ")
                    if (envPct != null) {
                        append(envPrep.step)
                        append(" $envPct%")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 18.dp),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = fill)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Clay),
                )
            }
            Text(
                text = "$percent%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- 竖屏单栏

@Composable
private fun CompactLayout(
    state: ChatUiState,
    vm: ChatViewModel,
    onOpenSettings: () -> Unit,
    onSessionLongPress: (app.codeforge.model.SessionItem) -> Unit,
    archived: List<app.codeforge.model.SessionItem> = emptyList(),
    onExpandArchived: () -> Unit = {},
    onRestoreArchived: (String) -> Unit = {},
) {
    // 附件面板开合（提升到这里才能盖遮罩实现「点外部收起」）
    var attachOpen by rememberSaveable { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // 划出侧栏即收键盘：抽屉盖着输入框时 IME 悬着既碍事又顶布局
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusMgr = androidx.compose.ui.platform.LocalFocusManager.current
    androidx.compose.runtime.LaunchedEffect(drawerState.targetValue) {
        if (drawerState.targetValue == androidx.compose.material3.DrawerValue.Open) {
            focusMgr.clearFocus()
            keyboard?.hide()
        }
    }

    // 抽屉开着时，返回键先关抽屉而不是退出 App —— 否则很容易误退出。
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    var renameDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var rewindDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var renameText by androidx.compose.runtime.remember(state.title) {
        androidx.compose.runtime.mutableStateOf(state.title)
    }
    if (rewindDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { rewindDialog = false },
            title = { Text("回滚文件到断点") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                ) {
                    if (state.checkpoints.isEmpty()) {
                        Text(
                            "暂无检查点（执行过文件改动的步骤才会产生）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.checkpoints.asReversed().forEach { cp ->
                        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                            .format(java.util.Date(cp.at * 1000))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .noRippleClickable {
                                    vm.rewindTo(cp.step)
                                    rewindDialog = false
                                }
                                .padding(10.dp),
                        ) {
                            Text(
                                "步骤 ${cp.step} · ${cp.files} 个文件 · $time",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (cp.sample.isNotBlank()) {
                                Text(
                                    cp.sample.take(60),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                        androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { rewindDialog = false }) { Text("关闭") }
            },
        )
    }
    if (renameDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renameDialog = false },
            title = { Text("重命名对话") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it.take(60) },
                    singleLine = true,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    vm.renameSession(state.currentSessionId, renameText.trim())
                    renameDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { renameDialog = false }) { Text("取消") }
            },
        )
    }


    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.width(310.dp),
            ) {
                SessionPane(
                    sessions = state.sessions,
                    currentId = state.currentSessionId,
                    onSelect = { id ->
                        vm.switchSession(id)
                        scope.launch { drawerState.close() }
                    },
                    onCreate = {
                        vm.createSession()
                        scope.launch { drawerState.close() }
                    },
                    onOpenSettings = onOpenSettings,
                    onSessionLongPress = onSessionLongPress,
                    archived = archived,
                    onExpandArchived = onExpandArchived,
                    onRestoreArchived = { id ->
                        onRestoreArchived(id)
                        scope.launch { drawerState.close() }
                    },
                    onDeleteSessions = vm::deleteSessions,
                )
            }
        },
    ) {
        // insets 用旋转层重映射过的：横持时「内容左边缘」可能是物理底边，
        // 直接用 safeDrawing 会垫错边（顶栏压挖孔就是这么来的）
        val orientation = LocalOrientation.current
        val insetMod = if (orientation != null) {
            Modifier.padding(orientation.insets)
        } else {
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .then(insetMod),
        ) {
            TopBar(
                title = state.title,
                connected = state.connected,
                onMenuClick = { scope.launch { drawerState.open() } },
                onNewChat = vm::createSession,
                onRename = { renameDialog = true },
                onShare = { shareTranscript(context, state) },
                onBranch = vm::branchSession,
                onContinueTurn = if (state.resumeBack >= 0) {
                    { vm.continueTurn() }
                } else {
                    null
                },
                onRewind = {
                    vm.pullCheckpoints()
                    rewindDialog = true
                },
                onOpenSettings = onOpenSettings,
            )
            // 需要「重试」的内核故障才用常驻横幅（按钮得点得到）；
            // 保存成功、状态变更这类一次性反馈走 ToastHost 气泡。
            if (state.banner?.retryable == true) {
                BannerBar(state.banner, onDismiss = vm::dismissBanner, onRetry = vm::retryKernel)
            }

            Box(modifier = Modifier.weight(1f)) {
                ChatBody(state, vm, listState, Modifier.fillMaxSize())
                // 附件面板展开时，点消息区即收起（面板本身在输入卡下方，盖不到这里）
                if (attachOpen) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) { detectTapGestures { attachOpen = false } },
                    )
                }
            }

            // 三条状态区：上下文表 → 任务清单 → 子智能体，有数据才占位
            ContextMeter(state.contextUsage)
            TodoPanel(state.todos)
            SubagentBar(state.subagents)

            Composer(
                running = state.running,
                permissionMode = state.permissionMode,
                modelName = state.modelName,
                sessionId = state.currentSessionId,
                draftText = vm.draftOf(state.currentSessionId),
                onDraftChange = vm::saveDraft,
                libraryModels = state.libraryModels,
                libraryProviders = state.libraryProviders,
                onApplyModel = vm::applyModel,
                onPermissionChange = vm::setPermissionMode,
                onSend = vm::send,
                onStop = vm::stop,
                onOpenSettings = onOpenSettings,
                maxLines = maxComposerLines(),
                attachPanelOpen = attachOpen,
                onAttachPanelChange = { attachOpen = it },
            )
        }
    }
}

// ---------------------------------------------------------------- 横屏双栏

@Composable
private fun TwoPaneLayout(
    state: ChatUiState,
    vm: ChatViewModel,
    mode: WindowMode,
    onOpenSettings: () -> Unit,
    onSessionLongPress: (app.codeforge.model.SessionItem) -> Unit,
) {
    var attachOpen by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // Expanded（平板/折叠屏展开）给列表更宽一点，标题不容易被截断
    val paneWidth = if (mode == WindowMode.Expanded) 320.dp else 280.dp

    val context = androidx.compose.ui.platform.LocalContext.current
    var renameDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var rewindDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var renameText by androidx.compose.runtime.remember(state.title) {
        androidx.compose.runtime.mutableStateOf(state.title)
    }
    if (rewindDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { rewindDialog = false },
            title = { Text("回滚文件到断点") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                ) {
                    if (state.checkpoints.isEmpty()) {
                        Text(
                            "暂无检查点（执行过文件改动的步骤才会产生）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.checkpoints.asReversed().forEach { cp ->
                        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                            .format(java.util.Date(cp.at * 1000))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .noRippleClickable {
                                    vm.rewindTo(cp.step)
                                    rewindDialog = false
                                }
                                .padding(10.dp),
                        ) {
                            Text(
                                "步骤 ${cp.step} · ${cp.files} 个文件 · $time",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (cp.sample.isNotBlank()) {
                                Text(
                                    cp.sample.take(60),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                        androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { rewindDialog = false }) { Text("关闭") }
            },
        )
    }
    if (renameDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renameDialog = false },
            title = { Text("重命名对话") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it.take(60) },
                    singleLine = true,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    vm.renameSession(state.currentSessionId, renameText.trim())
                    renameDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { renameDialog = false }) { Text("取消") }
            },
        )
    }


    val orientation = LocalOrientation.current
    val insetMod = if (orientation != null) {
        Modifier.padding(orientation.insets)
    } else {
        Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
    }
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .then(insetMod),
    ) {
        SessionPane(
            sessions = state.sessions,
            currentId = state.currentSessionId,
            onSelect = vm::switchSession,
            onCreate = vm::createSession,
            onOpenSettings = onOpenSettings,
            onSessionLongPress = onSessionLongPress,
            archived = state.archivedSessions,
            onExpandArchived = vm::loadArchived,
            onRestoreArchived = vm::unarchive,
            onDeleteSessions = vm::deleteSessions,
            modifier = Modifier
                .width(paneWidth)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface),
        )

        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(modifier = Modifier.weight(1f)) {
            TopBar(
                title = state.title,
                connected = state.connected,
                onMenuClick = null,
                onNewChat = vm::createSession,
                onRename = { renameDialog = true },
                onShare = { shareTranscript(context, state) },
                onBranch = vm::branchSession,
                onContinueTurn = if (state.resumeBack >= 0) {
                    { vm.continueTurn() }
                } else {
                    null
                },
                onRewind = {
                    vm.pullCheckpoints()
                    rewindDialog = true
                },
                onOpenSettings = onOpenSettings,
            )
            // 需要「重试」的内核故障才用常驻横幅（按钮得点得到）；
            // 保存成功、状态变更这类一次性反馈走 ToastHost 气泡。
            if (state.banner?.retryable == true) {
                BannerBar(state.banner, onDismiss = vm::dismissBanner, onRetry = vm::retryKernel)
            }

            Box(modifier = Modifier.weight(1f)) {
                ChatBody(state, vm, listState, Modifier.fillMaxSize())
                // 附件面板展开时，点消息区即收起（面板本身在输入卡下方，盖不到这里）
                if (attachOpen) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) { detectTapGestures { attachOpen = false } },
                    )
                }
            }

            // 三条状态区：上下文表 → 任务清单 → 子智能体，有数据才占位
            ContextMeter(state.contextUsage)
            TodoPanel(state.todos)
            SubagentBar(state.subagents)

            Composer(
                running = state.running,
                permissionMode = state.permissionMode,
                modelName = state.modelName,
                sessionId = state.currentSessionId,
                draftText = vm.draftOf(state.currentSessionId),
                onDraftChange = vm::saveDraft,
                libraryModels = state.libraryModels,
                libraryProviders = state.libraryProviders,
                onApplyModel = vm::applyModel,
                onPermissionChange = vm::setPermissionMode,
                onSend = vm::send,
                onStop = vm::stop,
                onOpenSettings = onOpenSettings,
                maxLines = maxComposerLines(),
                attachPanelOpen = attachOpen,
                onAttachPanelChange = { attachOpen = it },
            )
        }
    }
}

// ---------------------------------------------------------------- 中部：问候页 ↔ 消息列表

@Composable
private fun ChatBody(
    state: ChatUiState,
    vm: ChatViewModel,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier,
) {
    // B4 消息流：编辑重发 / 原样重发 / 历史工具卡回放 —— 从 MessageList 一路透传
    // running 时不回问候页：新会话首条消息的空窗期若切回问候页，
    // AnimatedContent 快速抖动会崩（crash-1792934642977）且视觉闪白
    val showGreeting = state.messages.isEmpty() && state.approvals.isEmpty() && !state.running

    AnimatedContent(
        targetState = showGreeting,
        transitionSpec = {
            fadeIn(tween(260)) togetherWith fadeOut(tween(180))
        },
        label = "home-switch",
        modifier = modifier,
    ) { greeting ->
        if (greeting) {
            GreetingHome()
        } else {
            MessageList(
                messages = state.messages,
                approvals = state.approvals,
                onApprovalDecide = vm::decideApproval,
                onRegenerate = vm::regenerate,
                onEditResend = vm::requestEdit,
                onResendUser = vm::resendUser,
                onFetchOutput = vm::fetchToolReplay,
                listState = listState,
                running = state.running,
            )
        }
    }
}

/**
 * 空态问候页：星标 + 按时段问候，问候语每 7 秒轮换一次（淡出淡入）。
 * 对齐 Claude 空态的观感 —— 界面安静、有生命，但不催促。
 */
@Composable
private fun GreetingHome() {
    val greetings = remember { buildGreetings() }
    var index by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(7_000)
            index = (index + 1) % greetings.size
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 60.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FlameMark(size = 58.dp, color = Clay, deepColor = ClayDeep, animated = true)
        Spacer(Modifier.height(26.dp))
        AnimatedContent(
            targetState = index,
            transitionSpec = {
                (fadeIn(tween(420)) + slideInVertically(tween(420)) { it / 8 })
                    .togetherWith(fadeOut(tween(280)))
            },
            label = "greeting",
        ) { i ->
            Text(
                text = greetings[i],
                style = SerifDisplay,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
    }
}

/** 按当前时段生成问候语，后缀轮换。 */
private fun buildGreetings(): List<String> {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val base = when (hour) {
        in 5..10 -> "早上好"
        in 11..12 -> "中午好"
        in 13..17 -> "下午好"
        in 18..22 -> "晚上好"
        else -> "夜深了"
    }
    return listOf(
        "一切都好吗？",
        "今天想做点什么？",
        "有什么可以帮你的？",
        "准备从哪里开始？",
    ).map { "$base，$it" }
}

// ---------------------------------------------------------------- 公共件

/**
 * 顶栏：不等长三线菜单 + 居中小标题 + 右侧新建。
 * 运行状态不再放顶栏（Claude 也不放）—— 输入卡上的停止键已经说明了一切；
 * 只有「未连接」还需要提示，因为它影响的是所有操作。
 */
@Composable
private fun TopBar(
    title: String,
    connected: Boolean,
    onMenuClick: (() -> Unit)?,
    onNewChat: () -> Unit,
    onRename: () -> Unit = {},
    onShare: () -> Unit = {},
    onBranch: () -> Unit = {},
    onContinueTurn: (() -> Unit)? = null,
    onRewind: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onMenuClick != null) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .noRippleClickable(onClick = onMenuClick),
                contentAlignment = Alignment.Center,
            ) {
                MenuGlyph()
            }
        } else {
            Spacer(Modifier.width(8.dp))
        }

        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
        )

        if (!connected) {
            Text(
                text = "未连接",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp),
            )
        } else {
            // 右上角三点菜单：新建 / 重命名 / 分享 / 分支（新建保留在这里，
            // 高频；其余从 Plus 里解放出来）
            var menuOpen by androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(false)
            }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .noRippleClickable(onClick = { menuOpen = true }),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(3.dp),
                ) {
                    repeat(3) {
                        Box(
                            modifier = Modifier
                                .size(3.5.dp)
                                .background(MaterialTheme.colorScheme.onSurface, CircleShape),
                        )
                    }
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("新建对话") },
                        onClick = { menuOpen = false; onNewChat() },
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("重命名对话") },
                        onClick = { menuOpen = false; onRename() },
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("分享整段对话") },
                        onClick = { menuOpen = false; onShare() },
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("创建对话分支") },
                        onClick = { menuOpen = false; onBranch() },
                    )
                    if (onContinueTurn != null) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("继续上一轮") },
                            onClick = { menuOpen = false; onContinueTurn() },
                        )
                    }
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("回滚文件到断点…") },
                        onClick = { menuOpen = false; onRewind() },
                    )
                    // 设置入口也放这里：平板上侧栏底部的头像按钮在部分 ROM 上
                    // 会落在系统手势区里点不到（真机实测），菜单是更可靠的一条路。
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("设置") },
                        onClick = { menuOpen = false; onOpenSettings() },
                    )
                }
            }
        }
    }
}

/** 把整段对话拼成纯文本走系统分享面板。 */
private fun shareTranscript(context: android.content.Context, state: ChatUiState) {
    val text = buildString {
        appendLine("CodeForge 对话：${state.title.ifBlank { "未命名" }}")
        appendLine()
        state.messages.forEach { m ->
            when (m.role) {
                app.codeforge.model.MsgRole.User -> appendLine("我：${m.text}")
                app.codeforge.model.MsgRole.Assistant -> appendLine("CodeForge：${m.text}")
                app.codeforge.model.MsgRole.Notice -> Unit
            }
        }
    }.trim()
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, text)
    }
    runCatching { context.startActivity(android.content.Intent.createChooser(send, "分享对话")) }
}

/** 三横线菜单图标：不等长（上短、中满、下中），对齐 Claude 的菜单符号。 */
@Composable
private fun MenuGlyph() {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(modifier = Modifier.size(width = 19.dp, height = 15.dp)) {
        val w = size.width
        val gap = size.height / 3f
        val widths = listOf(0.58f, 1f, 0.8f)
        widths.forEachIndexed { i, ratio ->
            val y = gap * i + gap / 2f
            drawLine(
                color = color,
                start = Offset(0f, y),
                end = Offset(w * ratio, y),
                strokeWidth = w * 0.1f,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * 顶部横幅（浮动圆角卡）。
 *
 * error 与 warn 视觉上必须不同：error 是红的（请求本身有问题，改了才能好），
 * warn 是暖黄的（等一下重试就好，用户不需要改任何东西）。
 */
@Composable
private fun BannerBar(
    banner: Banner?,
    onDismiss: () -> Unit,
    onRetry: (() -> Unit)? = null,
) {
    AnimatedVisibility(
        visible = banner != null,
        enter = fadeIn(tween(140)) + expandVertically(tween(160)),
        exit = fadeOut(tween(100)) + shrinkVertically(tween(140)),
    ) {
        val b = banner ?: return@AnimatedVisibility
        val (bg, fg) = if (b.isError) {
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(bg)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(fg, CircleShape),
            )
            Text(
                text = b.text,
                style = MaterialTheme.typography.bodySmall,
                color = fg,
                modifier = Modifier.weight(1f),
            )
            // 自动重连兜不住时（内核进程卡死、服务没起来）给用户一个手动出口
            if (b.retryable && onRetry != null) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(fg.copy(alpha = 0.12f))
                        .noRippleClickable(onClick = onRetry)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(text = "重试", style = MaterialTheme.typography.labelSmall, color = fg)
                }
            }
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .noRippleClickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                GlyphIcon(glyph = Glyph.Close, size = 13.dp, color = fg, strokeWidth = 1.8.dp)
            }
        }
    }
}

/**
 * 输入框最大行数。
 *
 * 横屏可用高度只有 400dp 上下，键盘一弹更少。上限收到 4 行，
 * 否则输入框会把消息区挤没。判定走内容坐标系（旋转层）。
 */
@Composable
private fun maxComposerLines(): Int = if (isLandscape()) 4 else 6
