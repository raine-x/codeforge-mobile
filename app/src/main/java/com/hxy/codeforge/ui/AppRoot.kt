package com.hxy.codeforge.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hxy.codeforge.ui.chat.BackgroundTasksSheet
import com.hxy.codeforge.ui.chat.Composer
import com.hxy.codeforge.ui.chat.GoalStrip
import com.hxy.codeforge.ui.chat.LocalAttachmentRoot
import com.hxy.codeforge.ui.chat.MessageList
import com.hxy.codeforge.ui.chat.SubagentBar
import com.hxy.codeforge.ui.browser.BrowserController
import com.hxy.codeforge.ui.browser.BrowserOverlay
import com.hxy.codeforge.ui.browser.LocalBrowserController
import com.hxy.codeforge.ui.common.Glyph
import com.hxy.codeforge.ui.common.ToastHost
import com.hxy.codeforge.ui.common.GlyphIcon
import com.hxy.codeforge.ui.common.LocalOrientation
import com.hxy.codeforge.ui.common.RotateWithDevice
import com.hxy.codeforge.ui.common.FlameMark
import com.hxy.codeforge.ui.common.WindowMode
import com.hxy.codeforge.ui.common.currentWindowMode
import com.hxy.codeforge.ui.common.isLandscape
import com.hxy.codeforge.ui.common.noRippleClickable
import com.hxy.codeforge.ui.sessions.FileTreePanel
import com.hxy.codeforge.ui.sessions.McpSidebarPanel
import com.hxy.codeforge.ui.sessions.NewProjectPanel
import com.hxy.codeforge.ui.sessions.ProjectActionsSheet
import com.hxy.codeforge.ui.sessions.SessionActionsSheet
import com.hxy.codeforge.ui.sessions.SessionPane
import com.hxy.codeforge.ui.settings.SettingsSheet
import com.hxy.codeforge.ui.theme.Clay
import com.hxy.codeforge.ui.theme.ClayDeep
import com.hxy.codeforge.core.EnvKit
import com.hxy.codeforge.ui.theme.SerifDisplay
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
    var sessionActions by remember { mutableStateOf<com.hxy.codeforge.model.SessionItem?>(null) }
    var projectActions by remember { mutableStateOf<com.hxy.codeforge.model.ProjectItem?>(null) }
    var showTodos by rememberSaveable { mutableStateOf(false) }
    // 后台脚本任务面板：从顶栏三点菜单进入（任务归内核进程，切会话不丢）
    var showTasks by rememberSaveable { mutableStateOf(false) }
    // 侧栏浮层："" = 会话主侧栏，mcp = MCP 面板，tree = 文件树（均铺满侧栏）
    var sidebarPage by rememberSaveable { mutableStateOf("") }
    // 文件树是从「新建项目」里打开的：选完路径要回到新建面板
    var treeForProject by rememberSaveable { mutableStateOf(false) }
    var showNewProject by rememberSaveable { mutableStateOf(false) }
    var pickedWorkspace by rememberSaveable { mutableStateOf("") }
    // 本地一次性提示：「已有空的新项目」这类判断发生在 UI 侧，不往协议里加东西
    var notice by remember { mutableStateOf<Banner?>(null) }

    // 内置浏览器控制器：消息流里的 HTML 块与链接卡通过它唤起全屏浏览器。
    // 用 CompositionLocal 往下传，是因为渲染树中间隔了好几层 Composable。
    val browserController = remember { BrowserController() }

    // 附件/媒体块的相对路径根：与 ChatViewModel.applySessionSpace 的优先级逐字一致
    //（会话元信息 workspace → 当前项目 → 应用私有 inbox）。消息里存的是内核 history
    // 原样的相对路径，只有用**同一棵树**去解析，缩略图与播放器才找得到文件（问题 1）。
    val appCtx = androidx.compose.ui.platform.LocalContext.current
    val attachmentRoot = remember(state.currentSessionId, state.sessions, state.currentWorkspace) {
        state.sessions.firstOrNull { it.id == state.currentSessionId }
            ?.workspace?.takeIf { it.isNotBlank() }
            ?: state.currentWorkspace.takeIf { it.isNotBlank() }
            ?: EnvKit.inboxSpace(appCtx).absolutePath
    }

    val openNewProject: () -> Unit = {
        // 「已有空的新项目」：刚建好的项目还没发过任何消息时再点新建，只提示不重开面板。
        // 项目列表是按会话聚合的（没有会话就没有项目行），所以判定看
        // 「当前项目有会话 + 当前对话还是空的」，而不是 sessionCount == 0。
        val emptyCurrentProject = state.currentWorkspace.isNotBlank() &&
            state.messages.isEmpty() &&
            !state.running &&
            state.sessions.any { it.workspace == state.currentWorkspace && !it.archived }
        if (emptyCurrentProject) {
            notice = Banner("已有空的新项目", isError = false)
        } else {
            pickedWorkspace = ""
            showNewProject = true
        }
    }

    // 输入卡「＋ → 当前目录的文件」：打开工作区文件树（浏览态，不做建项目回填）
    val openWorkspaceFiles: () -> Unit = {
        treeForProject = false
        sidebarPage = "tree"
        viewModel.loadTree("")
    }

    // 用系统浏览器打开链接（内置浏览器的「系统浏览器」入口与链接卡的默认点按共用）
    val openInSystemBrowser: (String) -> Unit = { url ->
        runCatching {
            val intent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(url),
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            appCtx.startActivity(intent)
        }.onFailure {
            notice = Banner("没有可用的浏览器", isError = true)
        }
    }

    CompositionLocalProvider(
        LocalAttachmentRoot provides attachmentRoot,
        LocalBrowserController provides browserController,
    ) {
    com.hxy.codeforge.ui.theme.AppBackground(Modifier.fillMaxSize()) {
    Box(Modifier.fillMaxSize()) {
        RotateWithDevice {
            // 档位判定必须放在旋转层内：LocalOrientation 由它提供，
            // 横持时内容盒宽高对调，自动切到网页端同款双栏布局
            val mode = currentWindowMode()
            Box(Modifier.fillMaxSize()) {
                when (mode) {
                    WindowMode.Compact -> CompactLayout(
                        state,
                        viewModel,
                        onOpenSettings = { showSettings = true },
                        onSessionLongPress = { sessionActions = it },
                        onNewProject = openNewProject,
                        onOpenMcp = { sidebarPage = "mcp" },
                        onProjectLongPress = { projectActions = it },
                        onOpenTodos = {
                            viewModel.refreshTodos()
                            showTodos = true
                        },
                        onOpenTasks = { showTasks = true },
                        onOpenWorkspaceFiles = openWorkspaceFiles,
                    )
                    WindowMode.Medium, WindowMode.Expanded ->
                        TwoPaneLayout(
                            state,
                            viewModel,
                            mode,
                            onOpenSettings = { showSettings = true },
                            onSessionLongPress = { sessionActions = it },
                            onNewProject = openNewProject,
                            onOpenMcp = { sidebarPage = "mcp" },
                            onProjectLongPress = { projectActions = it },
                            onOpenTodos = {
                            viewModel.refreshTodos()
                            showTodos = true
                        },
                            onOpenTasks = { showTasks = true },
                            onOpenWorkspaceFiles = openWorkspaceFiles,
                        )
                }

                // 侧栏浮层放在旋转层里：横持时面板跟内容一起转（同 P-009 的边界）。
                // 不用 fadeIn/fadeOut：整层做 alpha 渐变会在进退场帧里把底层侧栏与聊天内容透出来叠字。
                AnimatedVisibility(
                    visible = sidebarPage == "mcp",
                    enter = slideInHorizontally(tween(200)) { -it },
                    exit = slideOutHorizontally(tween(180)) { -it },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    McpSidebarPanel(vm = viewModel, onClose = { sidebarPage = "" })
                }
                AnimatedVisibility(
                    visible = sidebarPage == "tree",
                    enter = slideInHorizontally(tween(200)) { -it },
                    exit = slideOutHorizontally(tween(180)) { -it },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    FileTreePanel(
                        entries = state.treeEntries,
                        path = state.treePath,
                        parent = state.treeParent,
                        error = state.treeError,
                        onLoad = { path -> viewModel.loadTree(path) },
                        onPick = { path ->
                            pickedWorkspace = path
                            sidebarPage = ""
                            if (treeForProject) {
                                // 「新建项目」里选目录：带着路径回到新建面板
                                treeForProject = false
                                showNewProject = true
                            } else {
                                // 浏览态（输入卡「当前目录的文件」）选中的目录直接切工作区
                                viewModel.switchProject(path)
                            }
                        },
                        onClose = {
                            sidebarPage = ""
                            treeForProject = false
                        },
                    )
                }
            }
        }

        // 浮层打开时返回键先收浮层（抽屉自己的 BackHandler 在 CompactLayout 里）
        BackHandler(enabled = sidebarPage.isNotEmpty()) {
            sidebarPage = ""
            treeForProject = false
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

        projectActions?.let { project ->
            ProjectActionsSheet(
                project = project,
                vm = viewModel,
                onDismiss = { projectActions = null },
            )
        }

        if (showNewProject) {
            NewProjectPanel(
                initialWorkspace = pickedWorkspace,
                defaultPermission = state.permissionMode,
                onCreate = { name, workspace, permission ->
                    viewModel.createProject(name, workspace, permission)
                    showNewProject = false
                    pickedWorkspace = ""
                },
                onPickFolder = {
                    // 新建面板先让位，文件树选完带着路径回来（面板会带着新路径重开）
                    showNewProject = false
                    treeForProject = true
                    sidebarPage = "tree"
                    viewModel.loadTree("")
                },
                onDismiss = { showNewProject = false },
            )
        }

        // 任务清单：从右上角三点菜单进来，打开时主动拉一次并随 todo 帧实时更新
        if (showTodos) {
            com.hxy.codeforge.ui.chat.TaskListDialog(
                todos = state.todos,
                onDismiss = { showTodos = false },
            )
        }

        // 后台脚本任务：打开期间面板自己按 1s 轮询 /api/tasks
        if (showTasks) {
            BackgroundTasksSheet(
                tasks = state.tasks,
                onRefresh = viewModel::refreshTasks,
                onStart = viewModel::startTask,
                onKill = viewModel::killTask,
                onDismiss = { showTasks = false },
                // 弹层盖住 ToastHost，错误必须在本层显示（P-024）。
                // 只传 error 横幅：成功提示走列表本身，避免把「已启动」染成红色。
                banner = state.banner?.takeIf { it.isError }?.text,
            )
        }

        // 放在所有弹层之后：设置页里点保存，提示才不会被弹层盖住。
        // 本地提示（如「已有空的新项目」）优先于 ViewModel 的 banner。
        ToastHost(
            banner = notice ?: state.banner,
            onDismiss = {
                notice = null
                viewModel.dismissBanner()
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 150.dp)
                .padding(horizontal = 32.dp),
        )

        // 内置浏览器：整屏盖住所有内容，放在最外层。
        // 选中元素 → 登记到对话（元素序号由 ViewModel 分配）。
        browserController.target?.let { target ->
            BrowserOverlay(
                target = target,
                onClose = { browserController.close() },
                onElementAdded = { el -> viewModel.addElement(el) },
                onOpenExternal = { url -> openInSystemBrowser(url) },
            )
        }
    }
    }
    }
}

// ---------------------------------------------------------------- 环境准备

/**
 * 首次启动的环境准备遮罩（proot/Alpine 兼容环境）。
 *
 * 内置语音模型移除后只剩这一台准备器（4MB 环境包），原先「两台取较慢的」
 * 的逻辑不再需要。失败不挡屏（设置页里能看到原因并重试），成功即自动淡出。
 */
@Composable
private fun EnvPreparingOverlay() {
    val env = EnvKit.status.value
    val envPrep = env as? EnvKit.Status.Preparing
    val envPct = envPrep?.percent
    val visible = envPct != null

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(280)),
    ) {
        val percent = envPct ?: 0
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
                text = "${envPrep?.step ?: ""} $percent%",
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
    onSessionLongPress: (com.hxy.codeforge.model.SessionItem) -> Unit,
    onNewProject: () -> Unit,
    onOpenMcp: () -> Unit,
    onProjectLongPress: (com.hxy.codeforge.model.ProjectItem) -> Unit,
    onOpenTodos: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenWorkspaceFiles: () -> Unit,
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
                    // 未完成轮次：保留用户原话、截掉未完成回复并回退文件（内核 retry 语义）
                    if (state.resumeBack >= 0) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                .noRippleClickable {
                                    vm.retryUnfinished()
                                    rewindDialog = false
                                }
                                .padding(10.dp),
                        ) {
                            Text(
                                "重试未完成的轮次（保留提问、回退文件）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
                    }
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
                drawerContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 1f),
                modifier = Modifier.width(310.dp),
            ) {
                SessionPane(
                    projects = state.projects,
                    sessions = state.sessions,
                    currentId = state.currentSessionId,
                    currentWorkspace = state.currentWorkspace,
                    currentWorkspaceName = state.currentWorkspaceName,
                    workspaceSwitching = state.workspaceSwitching,
                    onSelect = { id ->
                        vm.switchSession(id)
                        scope.launch { drawerState.close() }
                    },
                    onCreate = {
                        vm.createSession()
                        scope.launch { drawerState.close() }
                    },
                    onNewProject = {
                        // 新建项目是大幅弹层（Dialog 层级），抽屉先收起来
                        scope.launch { drawerState.close() }
                        onNewProject()
                    },
                    onOpenSettings = onOpenSettings,
                    onOpenMcp = {
                        scope.launch { drawerState.close() }
                        onOpenMcp()
                    },
                    onSelectProject = { project ->
                        vm.switchProject(project.workspace)
                        scope.launch { drawerState.close() }
                    },
                    onProjectLongPress = onProjectLongPress,
                    onNewSessionInProject = { workspace ->
                        // 切项目与 new_session 必须是**一个原子序列**：先 POST 工作区、
                        // 等成功后再建会话，否则新会话会落进上一个项目（问题 2）。
                        vm.newSessionInProject(workspace)
                        scope.launch { drawerState.close() }
                    },
                    onSessionLongPress = onSessionLongPress,
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
                projectName = state.currentWorkspaceName,
                switching = state.workspaceSwitching,
                running = state.running,
                todoLabel = todoMenuLabel(state.todos),
                onOpenTodos = onOpenTodos,
                taskLabel = taskMenuLabel(state.tasks),
                onOpenTasks = onOpenTasks,
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

            // 状态区：目标账本 → 子智能体，有数据才占位。
            // 上下文条按用户要求删除（占比改由输入卡侧的细线表达）；
            // 任务清单折叠进右上角三点菜单，这里不再常驻。
            GoalStrip(state.goal)
            SubagentBar(state.subagents)

            Composer(
                running = state.running,
                steerQueued = state.steerQueued,
                permissionMode = state.permissionMode,
                modelName = state.modelName,
                // 供应商的真相在内核 config（问题 8）：弹层不再自己维护一个空字符串
                // 内核 config.provider 是**协议族**（anthropic|openai），不是供应商 id ——
                // 当前供应商由 Composer 按内核生效模型从模型库反查，别用这个字段。
                activeProvider = state.kernelConfig?.provider.orEmpty(),
                kernelModelId = state.kernelConfig?.model.orEmpty(),
                modelInLibrary = state.kernelConfig?.modelInLibrary ?: true,
                sessionId = state.currentSessionId,
                draftText = vm.draftOf(state.currentSessionId),
                onDraftChange = vm::saveDraft,
                libraryModels = state.libraryModels,
                libraryProviders = state.libraryProviders,
                providerDetails = state.providerDetails,
                onApplyModel = vm::applyModel,
                onPermissionChange = vm::setPermissionMode,
                onSend = vm::send,
                onStop = vm::stop,
                onOpenSettings = onOpenSettings,
                maxLines = maxComposerLines(),
                attachPanelOpen = attachOpen,
                onAttachPanelChange = { attachOpen = it },
                skills = state.skills,
                builtinPlugins = state.builtinPlugins,
                mcpPlugins = state.mcpPlugins,
                actionPanel = state.actionPanel,
                onDismissActionPanel = vm::dismissActionPanel,
                onSendActionPanel = vm::sendActionPanel,
                contextUsage = state.contextUsage,
                onOpenTaskList = onOpenTodos,
                onOpenWorkspaceFiles = onOpenWorkspaceFiles,
                editBack = state.editBack,
                editText = state.editText,
                editAttachments = state.editAttachments,
                editSeq = state.editSeq,
                onCancelEdit = vm::clearEdit,
                elementChips = state.attachedElements,
                elementInsert = state.elementInsert,
                onRemoveElement = { idx -> vm.removeElement(idx) },
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
    onSessionLongPress: (com.hxy.codeforge.model.SessionItem) -> Unit,
    onNewProject: () -> Unit,
    onOpenMcp: () -> Unit,
    onProjectLongPress: (com.hxy.codeforge.model.ProjectItem) -> Unit,
    onOpenTodos: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenWorkspaceFiles: () -> Unit,
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
                    // 未完成轮次：保留用户原话、截掉未完成回复并回退文件（内核 retry 语义）
                    if (state.resumeBack >= 0) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                .noRippleClickable {
                                    vm.retryUnfinished()
                                    rewindDialog = false
                                }
                                .padding(10.dp),
                        ) {
                            Text(
                                "重试未完成的轮次（保留提问、回退文件）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
                    }
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
        // 横屏/双栏下左边那栏可以收起来。竖屏是抽屉，不需要这个。
        //
        // 收起时侧栏**完全消失**（不留窄轨）：窄轨看着像「还有一截没显示完」，
        // 而展开/收起的开关已经移到主界面顶栏（见 TopBar 的 onToggleSidebar），
        // 窄轨既挡视线又和顶栏按钮重复。
        var sidebarCollapsed by rememberSaveable { mutableStateOf(false) }
        // 宽度用动画过渡：硬切会让整块内容瞬移，眼睛跟不上（用户反馈「没有过渡动画」）
        val sidebarWidth by animateDpAsState(
            targetValue = if (sidebarCollapsed) 0.dp else paneWidth,
            animationSpec = tween(durationMillis = 220),
            label = "sidebarWidth",
        )
        if (sidebarWidth > 0.dp) {
            SessionPane(
                projects = state.projects,
                sessions = state.sessions,
                currentId = state.currentSessionId,
                currentWorkspace = state.currentWorkspace,
                currentWorkspaceName = state.currentWorkspaceName,
                workspaceSwitching = state.workspaceSwitching,
                onSelect = vm::switchSession,
                onCreate = vm::createSession,
                onNewProject = onNewProject,
                onOpenSettings = onOpenSettings,
                onOpenMcp = onOpenMcp,
            onSelectProject = { project -> vm.switchProject(project.workspace) },
            onProjectLongPress = onProjectLongPress,
            onNewSessionInProject = { workspace ->
                // 同上：切工作区与建会话串行，避免会话归属到上一个项目
                vm.newSessionInProject(workspace)
            },
            onSessionLongPress = onSessionLongPress,
            onDeleteSessions = vm::deleteSessions,
                modifier = Modifier
                    .width(sidebarWidth)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surface)
                    .clipToBounds(),
            )
        }

        if (sidebarWidth > 0.dp) {
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        Column(modifier = Modifier.weight(1f)) {
            TopBar(
                title = state.title,
                connected = state.connected,
                onMenuClick = null,
                // 三横线在主界面顶栏：收起侧栏后主区占满，它跟着主区走才找得到
                onToggleSidebar = { sidebarCollapsed = !sidebarCollapsed },
                sidebarExpanded = !sidebarCollapsed,
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
                projectName = state.currentWorkspaceName,
                switching = state.workspaceSwitching,
                running = state.running,
                todoLabel = todoMenuLabel(state.todos),
                onOpenTodos = onOpenTodos,
                taskLabel = taskMenuLabel(state.tasks),
                onOpenTasks = onOpenTasks,
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

            // 状态区：目标账本 → 子智能体，有数据才占位。
            // 上下文条删除、任务清单进右上角菜单 —— 与竖屏一致。
            GoalStrip(state.goal)
            SubagentBar(state.subagents)

            Composer(
                running = state.running,
                steerQueued = state.steerQueued,
                permissionMode = state.permissionMode,
                modelName = state.modelName,
                // 供应商的真相在内核 config（问题 8）：弹层不再自己维护一个空字符串
                // 内核 config.provider 是**协议族**（anthropic|openai），不是供应商 id ——
                // 当前供应商由 Composer 按内核生效模型从模型库反查，别用这个字段。
                activeProvider = state.kernelConfig?.provider.orEmpty(),
                kernelModelId = state.kernelConfig?.model.orEmpty(),
                modelInLibrary = state.kernelConfig?.modelInLibrary ?: true,
                sessionId = state.currentSessionId,
                draftText = vm.draftOf(state.currentSessionId),
                onDraftChange = vm::saveDraft,
                libraryModels = state.libraryModels,
                libraryProviders = state.libraryProviders,
                providerDetails = state.providerDetails,
                onApplyModel = vm::applyModel,
                onPermissionChange = vm::setPermissionMode,
                onSend = vm::send,
                onStop = vm::stop,
                onOpenSettings = onOpenSettings,
                maxLines = maxComposerLines(),
                attachPanelOpen = attachOpen,
                onAttachPanelChange = { attachOpen = it },
                skills = state.skills,
                builtinPlugins = state.builtinPlugins,
                mcpPlugins = state.mcpPlugins,
                actionPanel = state.actionPanel,
                onDismissActionPanel = vm::dismissActionPanel,
                onSendActionPanel = vm::sendActionPanel,
                contextUsage = state.contextUsage,
                onOpenTaskList = onOpenTodos,
                onOpenWorkspaceFiles = onOpenWorkspaceFiles,
                editBack = state.editBack,
                editText = state.editText,
                editAttachments = state.editAttachments,
                editSeq = state.editSeq,
                onCancelEdit = vm::clearEdit,
                elementChips = state.attachedElements,
                elementInsert = state.elementInsert,
                onRemoveElement = { idx -> vm.removeElement(idx) },
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
                onContinue = if (state.resumeBack >= 0 && !state.running) {
                    { vm.continueTurn() }
                } else {
                    null
                },
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
 *
 * 标题下面补一行当前项目名与运行状态：用户要求「工作区切换/项目运行」在顶栏可见 ——
 * 多项目并行时，只靠输入卡的停止键看不出是哪个项目在跑。
 * 只有「未连接」还需要在最右提示，因为它影响的是所有操作。
 */
@Composable
private fun TopBar(
    title: String,
    connected: Boolean,
    onMenuClick: (() -> Unit)?,
    /** 折叠/展开侧栏（非空才显示三横线）。竖屏抽屉模式传 null。 */
    onToggleSidebar: (() -> Unit)? = null,
    /** 侧栏当前是否展开（只影响按钮配色）。 */
    sidebarExpanded: Boolean = true,
    onNewChat: () -> Unit,
    onRename: () -> Unit = {},
    onShare: () -> Unit = {},
    onBranch: () -> Unit = {},
    onContinueTurn: (() -> Unit)? = null,
    onRewind: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    projectName: String = "",
    switching: Boolean = false,
    running: Boolean = false,
    todoLabel: String = "任务清单",
    onOpenTodos: () -> Unit = {},
    taskLabel: String = "后台任务",
    onOpenTasks: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onToggleSidebar != null) {
            // 侧栏折叠开关：三条横杠。收起时侧栏完全消失，这个按钮跟着主区走，
            // 否则用户收起后就没法再把侧栏叫回来了。
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .noRippleClickable(onClick = onToggleSidebar),
                contentAlignment = Alignment.Center,
            ) {
                GlyphIcon(
                    Glyph.Sidebar,
                    19.dp,
                    if (sidebarExpanded) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        } else if (onMenuClick != null) {
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

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            if (projectName.isNotBlank() || switching || running) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    if (switching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(9.dp),
                            strokeWidth = 1.2.dp,
                            color = Clay,
                        )
                    } else if (running) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .background(Clay, CircleShape),
                        )
                    }
                    Text(
                        text = projectName.ifBlank { if (switching) "切换工作区…" else "" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

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
                    // 显式用主题表面色：Material3 默认取 surfaceContainer，而本项目
                    // 的暖色主题没有为这套 token 配色，弹出菜单会与页面明显两个色系（问题 7）。
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                    ),
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
                    // 任务清单：原来常驻在输入卡上方，用户要求折叠进这个菜单。
                    // 标题带实时计数（state.todos），一眼看出还剩几项。
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(todoLabel) },
                        onClick = { menuOpen = false; onOpenTodos() },
                    )
                    // 后台脚本任务：AI（run_background）与手动启动都在这里查看/取消
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(taskLabel) },
                        onClick = { menuOpen = false; onOpenTasks() },
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

/** 三点菜单里「任务清单」的标题：带实时进度，不必展开就知道还剩几项。 */
private fun todoMenuLabel(todos: List<com.hxy.codeforge.model.TodoItem>): String {
    if (todos.isEmpty()) return "任务清单"
    val done = todos.count { it.status == com.hxy.codeforge.model.TodoStatus.Completed }
    return "任务清单 $done/${todos.size}"
}

/** 三点菜单里「后台任务」的标题：只报运行中的数量（已结束的不值得占标题）。 */
private fun taskMenuLabel(tasks: List<com.hxy.codeforge.model.BackgroundTask>): String {
    val running = tasks.count { it.status == "running" }
    return if (running > 0) "后台任务（$running 运行中）" else "后台任务"
}

/** 把整段对话拼成纯文本走系统分享面板。 */
private fun shareTranscript(context: android.content.Context, state: ChatUiState) {
    val text = buildString {
        appendLine("CodeForge 对话：${state.title.ifBlank { "未命名" }}")
        appendLine()
        state.messages.forEach { m ->
            when (m.role) {
                com.hxy.codeforge.model.MsgRole.User -> appendLine("我：${m.text}")
                com.hxy.codeforge.model.MsgRole.Assistant -> appendLine("CodeForge：${m.text}")
                com.hxy.codeforge.model.MsgRole.Notice -> Unit
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
