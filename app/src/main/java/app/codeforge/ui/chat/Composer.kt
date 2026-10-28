package app.codeforge.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import app.codeforge.model.PermissionMode
import app.codeforge.ui.common.Glyph
import app.codeforge.ui.common.GlyphIcon
import app.codeforge.ui.common.ZhSpeechInput
import app.codeforge.ui.common.noRippleClickable
import app.codeforge.ui.common.rememberZhSpeechInput
import app.codeforge.ui.common.zhSpeechAvailable
import app.codeforge.ui.settings.AppSettings
import app.codeforge.ui.theme.Clay
import app.codeforge.voice.OfflineVoice
import app.codeforge.voice.OfflineVoiceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 输入区，对齐 Claude 客户端的悬浮输入卡：
 *
 *  - 整体是一张 28dp 圆角卡片浮在底部，而不是被分割线压住的条；
 *  - 底行从左到右：「+」附件、模型胶囊（含权限档位）、主按钮；
 *  - 主按钮三态：空输入 = 语音（点按开始中文听写，再点收口）、有输入 =
 *    陶土橘发送、运行中 = 方块停止。三态同位渐变切换，手指不用挪。
 *  - 语音走系统识别服务（见 [ZhSpeechInput]）：零依赖零模型，识别的中间
 *    结果实时打进输入框，说完自动收口成可编辑文本。
 *  - 模型胶囊打开「选择模型」底部弹层（模型为界面演示，权限模式是真的）；
 *    「+」打开附件占位弹层。
 *
 * **回车不发送**：手机上回车应该是换行，发送靠右侧按钮。
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun Composer(
    running: Boolean,
    permissionMode: PermissionMode,
    onPermissionChange: (PermissionMode) -> Unit,
    onSend: (String, List<String>) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    maxLines: Int = 6,
    modelName: String = "",
    onOpenSettings: () -> Unit = {},
    // 每会话草稿（B4）：keystroke 即存、发送即清；切会话时以 key 换态恢复
    sessionId: String = "",
    draftText: String = "",
    onDraftChange: (String, String) -> Unit = { _, _ -> },
    // 模型库真数据 + 应用回调（B6-P0）：选库内模型时由内核切通道
    libraryModels: List<app.codeforge.model.LibraryModel> = emptyList(),
    libraryProviders: List<String> = emptyList(),
    onApplyModel: (String) -> Unit = {},
    // 附件面板开合由外部持有：这样 AppRoot 能在消息区盖一层「点外部收起」的
    // 透明遮罩（面板自己在 Composer 里，盖不到自己头顶的区域）。
    attachPanelOpen: Boolean = false,
    onAttachPanelChange: (Boolean) -> Unit = {},
) {
    var input by rememberSaveable(sessionId) { mutableStateOf(draftText) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    // DeepSeek 式附件面板：⊕ 展开 / ⊗ 收起；与键盘互斥（键盘弹起自动收起）。
    // 开合状态由外部持有 —— AppRoot 要在消息区盖一层遮罩，实现「点外部收起」。
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) { if (imeVisible) onAttachPanelChange(false) }
    // 待发附件（相对工作区路径 attachments/xxx）。选中即复制落盘，
    // 发送时随消息上行；删除只是从待发列表去掉（文件留在工作区，可再次引用）
    var attachments by rememberSaveable { mutableStateOf(listOf<String>()) }
    val attachScope = androidx.compose.runtime.rememberCoroutineScope()
    // 模型名：内核 ready 帧的 display_name 优先；用户在弹层里选过则尊重选择。
    var pickedModel by rememberSaveable { mutableStateOf<String?>(null) }
    val model = pickedModel ?: modelName.ifBlank { "未选择模型" }
    // 供应商与思考强度：界面态（弹层里选），设置层接 /api/config、/api/providers 后换真数据源
    // 不预设任何供应商：库里没有就显示「未选择」，避免出现一个点不进去的假条目
    var provider by rememberSaveable { mutableStateOf("") }
    // 初始值取持久化档位（选过就不再回默认「高」）
    var thinkingIndex by rememberSaveable { mutableIntStateOf(AppSettings.thinkingLevel.value) }

    val context = LocalContext.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var listening by rememberSaveable { mutableStateOf(false) }
    var voiceNotice by rememberSaveable { mutableStateOf<String?>(null) }
    // 听写开始时的文本快照：中间/最终结果都追加在它后面，识别失败则还原
    var listenBase by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingVoice by rememberSaveable { mutableStateOf(false) }
    // 本次聆听是否出过任何结果（partial/final）：聆听看门狗只盯「全程零结果」的会话
    var gotVoiceThisSession by rememberSaveable { mutableStateOf(false) }
    // 本次聆听用的是哪台引擎：看门狗收口时判定该谁背锅（系统服务 hang 直接判降级）
    var voiceEngine by rememberSaveable { mutableStateOf("") }

    // 双引擎同构：系统服务优先，没有服务的 ROM 落到离线 Vosk（首启已解压）
    fun handlePartial(p: String) {
        gotVoiceThisSession = true
        listenBase?.let { input = it + p }
    }

    // 连续听写（B4）：听完一段自动续听，直到手动停止或关开关。
    // autoRestart 在 beginListening 里被赋值为「本次选定引擎的 start 闭包」
    var autoRestart: (() -> Unit)? = null
    fun handleFinal(f: String) {
        gotVoiceThisSession = true
        listenBase?.let { input = (it + f).trim() }
        listenBase = null
        onDraftChange(sessionId, input)
        if (app.codeforge.ui.settings.AppSettings.continuousDictation.value) {
            listenBase = input
            autoRestart?.invoke()
        }
    }

    fun handleVoiceError(e: String) {
        // 失败时回到听写前的文本，别让半截中间结果留在输入框里
        listenBase?.let { input = it }
        listenBase = null
        voiceNotice = e
    }

    // 离线引擎先建（onEngineDead 回调要引用它），系统引擎后建
    val offlineEngine = remember { OfflineVoiceEngine(context) }
    DisposableEffect(offlineEngine) {
        offlineEngine.onPartial = ::handlePartial
        offlineEngine.onFinal = ::handleFinal
        offlineEngine.onError = ::handleVoiceError
        offlineEngine.onListeningChanged = { listening = it }
        onDispose { offlineEngine.destroy() }
    }

    // 离线引擎就绪即后台预加载模型：把 ~68MB 的 Model/Recognizer 构造挪出
    // 首次点按的主线程（点按后几秒内的说话会被卡顿吞掉，表现为「不出字」）
    LaunchedEffect(offlineEngine, OfflineVoice.status.value) {
        if (OfflineVoice.status.value is OfflineVoice.Status.Ready) {
            offlineEngine.prepare()
        }
    }

    fun beginListening(start: () -> Unit) {
        listenBase = input
        gotVoiceThisSession = false
        voiceNotice = null
        autoRestart = { if (!listening) start() }
        start()
    }

    val speech = rememberZhSpeechInput(
        onPartial = ::handlePartial,
        onFinal = ::handleFinal,
        onError = ::handleVoiceError,
        onListeningChanged = { listening = it },
        // 系统引擎判死（ERROR_CLIENT）：当次无缝续接离线引擎，半截中间结果不丢
        // （beginListening 会把含中间结果的 input 存为新基线）
        onEngineDead = {
            when {
                !OfflineVoice.nativeDead &&
                    OfflineVoice.status.value is OfflineVoice.Status.Ready -> {
                    voiceEngine = "offline"
                    beginListening { offlineEngine.start() }
                }

                OfflineVoice.status.value is OfflineVoice.Status.Preparing ->
                    voiceNotice = "系统语音服务不可用；离线引擎准备中，稍候几秒再试"

                OfflineVoice.nativeDead ->
                    voiceNotice = "两台语音引擎均不可用（系统服务抖动，离线原生库加载失败）"

                else -> {
                    voiceNotice = "系统语音服务不可用；离线语音未就绪，可在设置里重新准备"
                    onOpenSettings()
                }
            }
        },
    )

    // 回调体在 toggleVoice 声明后回填；remember 住 holder，
    // 否则 launcher 闭包捕获的是首次组合的局部变量，重组后回填会丢
    var handlePermissionResult by remember {
        mutableStateOf<(Boolean) -> Unit>({ })
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> handlePermissionResult(granted) }

    /**
     * 引擎路由。权限检查必须放在路由**之前**：系统引擎由 Google 服务代开
     * 麦克风，而离线 Vosk 是本进程自己开 AudioRecord —— 没授权时
     * 系统引擎能出提示、离线引擎只会报「Microphone might be already in use」
     * （真机踩过的坑，症状完全指向错误方向）。
     */
    fun toggleVoice() {
        if (listening) {
            speech.stop()
            offlineEngine.stop()
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            pendingVoice = true
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        when {
            // 系统引擎：未降级优先；离线引擎原生库已判死时即使降级也回头试它
            // —— 两台引擎只剩它，时好时坏也比直接宣布不可用强
            zhSpeechAvailable(context) &&
                (!app.codeforge.ui.common.VoiceHealth.systemDegraded || OfflineVoice.nativeDead) -> {
                voiceEngine = "system"
                beginListening { speech.start() }
            }

            !OfflineVoice.nativeDead &&
                OfflineVoice.status.value is OfflineVoice.Status.Ready -> {
                voiceEngine = "offline"
                beginListening { offlineEngine.start() }
            }

            OfflineVoice.nativeDead ->
                voiceNotice = "离线引擎不可用（原生库加载失败）；系统语音服务也不可用"

            OfflineVoice.status.value is OfflineVoice.Status.Preparing ->
                voiceNotice = "语音环境准备中，稍候几秒再试"

            else -> {
                voiceNotice = "离线语音未就绪，可在设置里重新准备"
                onOpenSettings()
            }
        }
    }

    handlePermissionResult = { granted ->
        if (granted) {
            // 授权成功直接续上刚才想做的动作（引擎还没选定，重新走一遍路由）
            if (pendingVoice) toggleVoice()
        } else {
            voiceNotice = "需要麦克风权限才能语音输入"
        }
        pendingVoice = false
    }

    // 聆听看门狗：全程零结果的会话 20 秒强制收口。系统识别服务 hang 时不产生
    // 任何错误码，唯一出路是这里；离线引擎自身的看门狗兜的是采集/识别环节。
    // 收口时顺手清 listenBase —— 迟到的引擎回调不许再改写输入框。
    LaunchedEffect(listening, gotVoiceThisSession) {
        if (!listening || gotVoiceThisSession) return@LaunchedEffect
        delay(20_000)
        if (listening && !gotVoiceThisSession) {
            speech.stop()
            offlineEngine.stop()
            listening = false
            listenBase = null
            // 20 秒零回调 = 服务没响应（hang 不产生错误码），比 ERROR_CLIENT 更死：
            // 直接判降级，下次点按直接走离线引擎，不让用户反复死等
            if (voiceEngine == "system") {
                app.codeforge.ui.common.VoiceHealth.systemDegraded = true
            }
            voiceNotice = "没有听到内容（语音服务无响应）"
        }
    }

    // 错误提示只停留 2.6 秒；「正在聆听」由 listening 态常驻显示，不走这里
    LaunchedEffect(voiceNotice) {
        if (voiceNotice != null) {
            delay(2_600)
            voiceNotice = null
        }
    }

    /** SAF URI → 当前空间的 attachments/。返回相对路径；超 20MiB 或读失败返回 null。 */
    suspend fun copyToWorkspace(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            var name = resolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            } ?: "file"
            name = name.replace(Regex("[/\\\u0000:]"), "_").take(80)
            val size = resolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && c.moveToFirst()) c.getLong(idx) else -1L
            } ?: -1L
            if (size > 20L * 1024 * 1024) {
                withContext(Dispatchers.Main) { voiceNotice = "附件超过 20MiB 上限" }
                return@withContext null
            }
            // 空间路由：走内核会话空间（内核看到的 workspace 就是它，
            // attachments 相对路径双方一致）
            val root = app.codeforge.core.EnvKit.sessionSpace(context, sessionId)
            // 时间戳前缀防重名；落点与内核 /api/upload_file 一致
            val rel = "attachments/" + System.currentTimeMillis() + "_" + name
            val out = java.io.File(root, rel)
            out.parentFile?.mkdirs()
            resolver.openInputStream(uri)?.use { input ->
                java.io.FileOutputStream(out).use { input.copyTo(it) }
            } ?: return@withContext null
            // wrx：x 位留着，放进空间的脚本/二进制才能直接执行
            out.setExecutable(true, false)
            rel
        }.getOrNull()
    }

    fun onPicked(uri: Uri?) {
        if (uri == null) return
        sheet = null // 选完即收起附件弹层
        attachScope.launch {
            copyToWorkspace(uri)?.let { rel ->
                attachments = attachments + rel
                voiceNotice = null
            }
        }
    }

    // 照片：Photo Picker（旧系统自动回退系统实现）；文件：SAF 任意类型
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> onPicked(uri) }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> onPicked(uri) }

    // 相机：TakePicture 经 FileProvider 出片到 cache，拍完走同一附件管道
    // （FileProvider URI 能查 DISPLAY_NAME/SIZE，copyToWorkspace 无需特判）
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { ok ->
        val uri = cameraUri
        cameraUri = null
        if (ok && uri != null) onPicked(uri) // onPicked 里已收弹层
    }

    fun takePhoto() {
        val dir = java.io.File(context.cacheDir, "camera")
        dir.mkdirs()
        val f = java.io.File(dir, "IMG_${System.currentTimeMillis()}.jpg")
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
        }.getOrNull()
        if (uri == null) {
            voiceNotice = "相机初始化失败"
            return
        }
        cameraUri = uri
        // 个别 ROM 没有相机应用：launch 抛 ActivityNotFoundException，别让它带崩界面
        if (runCatching { cameraLauncher.launch(uri) }.isFailure) {
            cameraUri = null
            voiceNotice = "此设备没有可用的相机应用"
        }
    }

    val canSend = (input.isNotBlank() || attachments.isNotEmpty()) && !running && !listening

    // @触发词自动补全（B4 输入区）：内置插件 plan/goal/vuln/reverse。
    // 取行尾 token 的简化实现：光标在中间时也按行尾处理（移动端输入多在末尾）
    val triggers = listOf("plan", "goal", "vuln", "reverse")
    val tailToken = input.takeLastWhile { !it.isWhitespace() }
    val suggestions = if (tailToken.startsWith("@") && tailToken.length >= 2) {
        triggers.filter { ("@" + it).startsWith(tailToken) && ("@" + it) != tailToken }
    } else {
        emptyList()
    }

    fun applyTrigger(name: String) {
        input = input.dropLast(tailToken.length) + "@$name "
        onDraftChange(sessionId, input)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        // @触发词候选行：贴在输入卡上方，点选即替换当前 @token
        if (suggestions.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                suggestions.forEach { name ->
                    Text(
                        text = "@" + name,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .noRippleClickable { applyTrigger(name) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 2.dp,
        ) {
            Column {
                // 待发附件直接摆在输入卡内部（输入框上方）：
                // 图片出缩略图、文件出类型色标 + 名字，点击 × 移除
                if (attachments.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, end = 8.dp, top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        attachments.take(4).forEach { rel ->
                            AttachmentInline(rel = rel, sessionId = sessionId, onRemove = { attachments = attachments - rel })
                        }
                        if (attachments.size > 4) {
                            Text(
                                text = "+${attachments.size - 4}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.CenterVertically),
                            )
                        }
                    }
                }
                // 占位符走 decorationBox，不要用外层 Box 叠一个 Text ——
                // 叠放时两者的基线/内边距各自计算，真机上会错开一行
                // （截图里「与 CodeForge 对话…」同时出现在输入行和按钮行）。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 54.dp, max = (maxLines * 24 + 30).dp)
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    BasicTextField(
                        value = input,
                        onValueChange = {
                            input = it
                            onDraftChange(sessionId, it)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 16.sp,
                            lineHeight = 24.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(Clay),
                        maxLines = maxLines,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        keyboardActions = KeyboardActions(),
                        decorationBox = { inner ->
                            Box {
                                if (input.isEmpty()) {
                                    Text(
                                        text = when {
                                            listening -> "正在聆听…"
                                            running -> "回复 CodeForge…"
                                            else -> "与 CodeForge 对话…"
                                        },
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontSize = 16.sp,
                                            lineHeight = 24.sp,
                                        ),
                                        color = if (listening) {
                                            Clay
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                                inner()
                            }
                        },
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircleIconButton(glyph = Glyph.Plus, label = "添加到对话") {
                        keyboard?.hide()
                        onAttachPanelChange(!attachPanelOpen)
                    }
                    Spacer(Modifier.width(8.dp))
                    ModelPill(
                        model = model,
                        thinkingLabel = ThinkingLevel.entries[thinkingIndex].label,
                        onClick = { sheet = "model" },
                    )
                    Spacer(Modifier.weight(1f))
                    MainButton(
                        running = running,
                        canSend = canSend,
                        listening = listening,
                        onSend = {
                            onSend(input, attachments)
                            input = ""
                            attachments = listOf()
                            onDraftChange(sessionId, "")
                        },
                        onStop = onStop,
                        onVoice = ::toggleVoice,
                    )
                }
            }
        }

        // 附件面板（DeepSeek 式）：最近文件横滑可选 + 拍照/相册/文件三动作
        AnimatedVisibility(
            visible = attachPanelOpen,
            enter = expandVertically(tween(180)) + fadeIn(tween(140)),
            exit = shrinkVertically(tween(160)) + fadeOut(tween(120)),
        ) {
            AttachPanel(
                selected = attachments,
                onToggle = { rel ->
                    attachments = if (rel in attachments) attachments - rel else attachments + rel
                },
                onPickImage = {
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onPickFile = { filePicker.launch("*/*") },
                onTakePhoto = ::takePhoto,
            )
        }

        // 语音状态行：聆听中常驻；错误提示短暂停留后自己消失。
        AnimatedVisibility(
            visible = listening || voiceNotice != null,
            enter = fadeIn(tween(140)) + expandVertically(tween(160)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(140)),
        ) {
            val text = if (listening) "正在聆听，再点一次麦克风结束" else voiceNotice.orEmpty()
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = if (listening) Clay else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 18.dp, top = 6.dp),
            )
        }
    }

    if (sheet == "model") {
        // 四行式选择弹层（供应商/模型/思考强度/权限），子页水平推入过渡
        ModelPickerSheet(
            currentModel = model,
            // 选模型即让内核切通道（/api/models/apply）
            onModelSelect = {
                pickedModel = it
                libraryModels.firstOrNull { m -> m.id == it || m.name == it }
                    ?.let { m ->
                        AppSettings.setLastModel(m.id)
                        onApplyModel(m.id)
                    }
                // 选完就收起：弹层是 Dialog 层级，气泡提示会被它整个盖住
                // （真机实测：切了模型但看不到「已切换到 X」）
                sheet = null
            },
            libraryModels = libraryModels,
            libraryProviders = libraryProviders,
            currentProvider = provider.ifBlank { "未选择" },
            onProviderSelect = { provider = it },
            thinkingIndex = thinkingIndex,
            onThinkingSelect = { thinkingIndex = it; AppSettings.setThinkingLevel(it) },
            permissionMode = permissionMode,
            // 选完权限也收起（同模型：不收起的话气泡提示会被弹层盖住）
            onPermissionChange = { m -> onPermissionChange(m); sheet = null },
            onDismiss = { sheet = null },
        )
    }
}

// ---------------------------------------------------------------- 主按钮

/**
 * 四态主按钮：聆听中（陶土橘声纹）/ 停止 / 发送 / 待听写（深色声纹）。
 * 聆听态优先级最高 —— 中间结果不断进输入框，按钮不能抢跑去变发送键，
 * 否则想收口时按不到。状态切换带缩放渐变。
 */
@Composable
private fun MainButton(
    running: Boolean,
    canSend: Boolean,
    listening: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onVoice: () -> Unit,
) {
    val state = when {
        listening -> "listening"
        running -> "stop"
        canSend -> "send"
        else -> "voice"
    }

    AnimatedContent(
        targetState = state,
        transitionSpec = {
            (fadeIn(tween(160)) + scaleIn(tween(160), initialScale = 0.7f))
                .togetherWith(fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.7f))
        },
        label = "main-button",
    ) { target ->
        val (bg, onClick) = when (target) {
            "listening" -> Clay to onVoice
            "stop" -> MaterialTheme.colorScheme.surfaceVariant to onStop
            "send" -> Clay to onSend
            else -> MaterialTheme.colorScheme.onBackground to onVoice
        }
        val fg = when (target) {
            "voice" -> MaterialTheme.colorScheme.background
            else -> Color.White
        }
        // 绘制块（Canvas lambda）里不能调 composable，颜色在这里先取好
        val stopColor = MaterialTheme.colorScheme.onSurface

        Box(
            modifier = Modifier
                .size(40.dp)
                .background(bg, CircleShape)
                .noRippleClickable { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            when (target) {
                "stop" -> Canvas(modifier = Modifier.size(15.dp)) {
                    drawRoundRect(
                        color = stopColor,
                        topLeft = Offset.Zero,
                        size = size,
                        cornerRadius = CornerRadius(size.width * 0.2f),
                    )
                }

                "send" -> Canvas(modifier = Modifier.size(18.dp)) {
                    val w = size.width
                    val h = size.height
                    drawLine(
                        color = fg,
                        start = Offset(w * 0.5f, h * 0.08f),
                        end = Offset(w * 0.5f, h * 0.9f),
                        strokeWidth = w * 0.14f,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                    drawLine(color = fg, start = Offset(w * 0.14f, h * 0.46f), end = Offset(w * 0.5f, h * 0.08f), strokeWidth = w * 0.14f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    drawLine(color = fg, start = Offset(w * 0.86f, h * 0.46f), end = Offset(w * 0.5f, h * 0.08f), strokeWidth = w * 0.14f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                }

                else -> VoiceWaveform(color = fg, active = target == "listening")
            }
        }
    }
}

/**
 * 声纹动画：四根错峰起伏的竖条。[active]（聆听中）时振幅加大、节奏加快，
 * 从「提示可以说话」变成「正在收音」。
 */
@Composable
private fun VoiceWaveform(color: Color, active: Boolean = false) {
    val transition = rememberInfiniteTransition(label = "waveform")
    val period = if (active) 300 else 520
    // 动画值必须收集在 Composable 层 —— Canvas 的绘制块不是 composable 上下文
    val p0 by transition.animateFloat(0.55f, 1f, infiniteRepeatable(tween(period), RepeatMode.Reverse), label = "wf-0")
    val p1 by transition.animateFloat(0.55f, 1f, infiniteRepeatable(tween(period, delayMillis = period / 4), RepeatMode.Reverse), label = "wf-1")
    val p2 by transition.animateFloat(0.55f, 1f, infiniteRepeatable(tween(period, delayMillis = period / 2), RepeatMode.Reverse), label = "wf-2")
    val p3 by transition.animateFloat(0.55f, 1f, infiniteRepeatable(tween(period, delayMillis = 3 * period / 4), RepeatMode.Reverse), label = "wf-3")
    val phases = listOf(p0, p1, p2, p3)

    Canvas(modifier = Modifier.size(width = 17.dp, height = 15.dp)) {
        val barW = size.width * 0.16f
        val gap = size.width * 0.13f
        // 聆听中把基线整体抬起来：视觉能量随状态升一档
        val lift = if (active) 1.35f else 1f
        val base = listOf(0.5f, 0.95f, 0.7f, 0.4f)
        phases.forEachIndexed { i, phase ->
            val barH = (size.height * base[i] * phase * lift).coerceAtMost(size.height)
            val x = i * (barW + gap)
            drawRoundRect(
                color = color,
                topLeft = Offset(x, (size.height - barH) / 2f),
                size = androidx.compose.ui.geometry.Size(barW, barH),
                cornerRadius = CornerRadius(barW / 2f),
            )
        }
    }
}

// ---------------------------------------------------------------- 底行控件

@Composable
private fun CircleIconButton(glyph: Glyph, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            .noRippleClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(
            glyph = glyph,
            size = 17.dp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ModelPill(
    model: String,
    thinkingLabel: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(18.dp))
            .noRippleClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = model,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    // 锁住时整颗胶囊压暗：测试模型不吃供应商配置，改也改不动
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
            )
            Text(
                text = " $thinkingLabel",
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                },
            )
        }
    }
}

// ---------------------------------------------------------------- 附件面板

/**
 * DeepSeek 式附件面板：上半是「最近文件」横滑选择（点选即入待发列表，
 * 再点取消），下半是 拍照 / 相册 / 文件 三个动作。最近文件取
 * workspace/attachments 按修改时间倒序的前 10 个。
 */
@Composable
private fun AttachPanel(
    selected: List<String>,
    onToggle: (String) -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onTakePhoto: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var recent by remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(Unit) {
        recent = withContext(Dispatchers.IO) {
            val dir = java.io.File(java.io.File(ctx.filesDir, "workspace"), "attachments")
            dir.listFiles()?.filter { it.isFile }
                ?.sortedByDescending { it.lastModified() }
                ?.take(10)
                ?.map { "attachments/" + it.name }
                .orEmpty()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        if (recent.isEmpty()) {
            Text(
                text = "暂无最近文件，从下方拍照 / 相册 / 文件添加",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            )
        } else {
            Text(
                text = "最近文件",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                recent.forEach { rel ->
                    RecentFileCell(
                        rel = rel,
                        selected = rel in selected,
                        onClick = { onToggle(rel) },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AttachCell("拍照", Glyph.Camera, Modifier.weight(1f), onClick = onTakePhoto)
            AttachCell("相册", Glyph.Photo, Modifier.weight(1f), onClick = onPickImage)
            AttachCell("文件", Glyph.FileDoc, Modifier.weight(1f), onClick = onPickFile)
        }
    }
}

/** 最近文件单元：图片出缩略图、其余出图标 + 文件名；右上角选中圈。 */
@Composable
private fun RecentFileCell(rel: String, selected: Boolean, onClick: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var bmp by remember(rel) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(rel) {
        if (rel.isImagePath()) {
            bmp = withContext(Dispatchers.IO) {
                runCatching { decodeThumb(attachmentFile(ctx, rel), 256) }.getOrNull()
            }
        }
    }
    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .then(
                if (selected) {
                    Modifier.border(2.dp, Clay, RoundedCornerShape(12.dp))
                } else {
                    Modifier.border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(12.dp),
                    )
                },
            )
            .noRippleClickable(onClick = onClick),
    ) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = rel.substringAfterLast('/'),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.size(76.dp),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                GlyphIcon(
                    glyph = Glyph.FileDoc,
                    size = 18.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = rel.substringAfterLast('/').take(14),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        // 右上角选中圈：未选灰点、选中陶土圆 + 白勾
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(20.dp)
                .background(
                    if (selected) Clay else Color(0x99888888),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                GlyphIcon(glyph = Glyph.Check, size = 11.dp, color = Color.White)
            }
        }
    }
}

@Composable
private fun AttachCell(
    label: String,
    glyph: Glyph,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit = {},
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                .noRippleClickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(
                glyph = glyph,
                size = 22.dp,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                },
                strokeWidth = 1.6.dp,
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            },
        )
    }
}


// ---------------------------------------------------------------- 内嵌附件片

/** 按扩展名归类上色：不同类型一眼可辨。 */
private fun attachCategoryColor(rel: String): Color {
    val ext = rel.substringAfterLast('.', "").lowercase()
    return when (ext) {
        in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp") -> Color(0xFF3AA6A0)
        in setOf("py", "js", "ts", "kt", "java", "c", "h", "cpp", "go", "rs", "sh", "html", "css", "sql") -> Color(0xFF4C8BE8)
        in setOf("md", "txt", "doc", "docx", "pdf") -> Color(0xFF3E9B4F)
        in setOf("mp3", "wav", "flac", "aac", "ogg") -> Color(0xFF9C6ADE)
        in setOf("mp4", "mkv", "avi", "mov", "webm") -> Color(0xFFE57A6E)
        in setOf("zip", "tar", "tgz", "gz", "7z", "rar") -> Color(0xFFE8A33D)
        else -> Color(0xFF8A877E)
    }
}

/** 输入卡内嵌附件片：图片 = 44dp 圆角缩略图；文件 = 类型色点 + 名字。 */
@Composable
private fun AttachmentInline(rel: String, sessionId: String, onRemove: () -> Unit) {
    val isImage = rel.isImagePath()
    Box {
        if (isImage) {
            val context = LocalContext.current
            val bmp = remember(rel) {
                runCatching {
                    // 会话空间是主路径，另外两处是历史遗留的落点，找到哪个用哪个
                    val path = listOf(
                        java.io.File(app.codeforge.core.EnvKit.sessionSpace(context, sessionId), rel),
                        java.io.File(app.codeforge.core.EnvKit.cellRoot(context), "workspace/" + rel),
                        java.io.File(app.codeforge.core.EnvKit.cellRoot(context), rel),
                    ).firstOrNull { it.isFile } ?: return@runCatching null
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(path.absolutePath, bounds)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 128) sample *= 2
                    android.graphics.BitmapFactory.decodeFile(
                        path.absolutePath,
                        android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
                    )
                }.getOrNull()
            }
            if (bmp != null) {
                androidx.compose.foundation.Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(attachCategoryColor(rel).copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("IMG", style = MaterialTheme.typography.labelSmall, color = attachCategoryColor(rel))
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(attachCategoryColor(rel).copy(alpha = 0.13f))
                    .padding(horizontal = 9.dp, vertical = 7.dp)
                    .heightIn(min = 32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(attachCategoryColor(rel), CircleShape),
                )
                Text(
                    text = rel.substringAfterLast('/').take(16),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
        // 移除角标：右上角小 ×
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(16.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "×",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Box(modifier = Modifier.matchParentSize().noRippleClickable(onClick = onRemove))
    }
}
