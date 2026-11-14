package com.hxy.codeforge.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
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
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.hxy.codeforge.core.KernelApi
import com.hxy.codeforge.model.ActionPanelState
import com.hxy.codeforge.model.ContextUsage
import com.hxy.codeforge.model.PermissionMode
import com.hxy.codeforge.ui.common.Glyph
import com.hxy.codeforge.ui.common.GlyphIcon
import com.hxy.codeforge.ui.common.ZhSpeechInput
import com.hxy.codeforge.ui.common.noRippleClickable
import com.hxy.codeforge.ui.common.rememberZhSpeechInput
import com.hxy.codeforge.ui.common.zhSpeechAvailable
import com.hxy.codeforge.ui.settings.AppSettings
import com.hxy.codeforge.ui.theme.Clay
import com.hxy.codeforge.ui.theme.LocalAppTextColor
import com.hxy.codeforge.voice.CloudVoiceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.min

/**
 * 输入区，对齐 Claude 客户端的悬浮输入卡：
 *
 *  - 整体是一张 28dp 圆角卡片浮在底部，而不是被分割线压住的条；
 *  - 底行从左到右：「+」附件、权限盾牌（三档下拉）、模型胶囊、主按钮；
 *  - 主按钮点按/长按双语义，覆盖语音 · 打断 · 转向 · 发送：
 *    空输入空闲 = 语音（点按开始中文听写，再点收口）；有输入 = 陶土橘发送；
 *    运行中且有输入 = 发送（steer 并入当前轮）；运行中且空输入 = 点按开始
 *    语音、**长按 1 秒打断**（按钮外圈进度环实时反馈，满圈触发 + 触觉）。
 *    四个职责同位不互斥，状态切换带缩放渐变。
 *  - 语音走系统识别服务（见 [ZhSpeechInput]）：零依赖零模型，识别的中间
 *    结果实时打进输入框，说完自动收口成可编辑文本。
 *  - 模型胶囊打开「选择模型」底部弹层（模型为界面演示，权限模式是真的）；
 *    「+」打开附件面板，面板顶部另有「Skills 和技能」（上拉框点选即插入 @）与
 *    「当前目录的文件」两个二级入口。
 *  - 输入里的 @ 提及由技能 / 内置插件 / 已加载 MCP 动态补全，并染成主色；
 *    上下文占用改成绕输入卡外圈的一条细线（不再占一行高度）。
 *  - 编辑重发时顶部压一条提示条，说明「发送将丢弃其后的对话与文件改动」。
 *
 * **回车不发送**：手机上回车应该是换行，发送靠右侧按钮。
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun Composer(
    running: Boolean,
    // 内核已把转向指令排队（steer_queued）：占位符从「教手势」切换为「提醒排队中」
    steerQueued: Boolean = false,
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
    libraryModels: List<com.hxy.codeforge.model.LibraryModel> = emptyList(),
    libraryProviders: List<String> = emptyList(),
    /**
     * 供应商完整信息（id + 显示名）。**身份以 id 为准**，显示名只是外观 ——
     * 内核 config 给的是 protocol（anthropic|openai），不是供应商 id。
     */
    providerDetails: List<KernelApi.ProviderInfo> = emptyList(),
    /**
     * 内核当前**生效的模型 id**（config.model）。
     *
     * 当前供应商要从模型库按它反查 —— 用显示名或协议族都查不准。
     */
    kernelModelId: String = "",
    /** 内核当前生效的模型是否还在模型库里（内核 `model_in_library`）。 */
    modelInLibrary: Boolean = true,
    onApplyModel: (String) -> Unit = {},
    // 内核 /api/config 的 provider 字段 —— **协议族**（anthropic | openai），
    // 不是供应商 id。留着是为了将来展示「当前用的哪种协议」，
    // **绝不能拿它当供应商显示或参与匹配**（见 AGENTS P-045）。
    activeProvider: String = "",
    // 附件面板开合由外部持有：这样 AppRoot 能在消息区盖一层「点外部收起」的
    // 透明遮罩（面板自己在 Composer 里，盖不到自己头顶的区域）。
    attachPanelOpen: Boolean = false,
    onAttachPanelChange: (Boolean) -> Unit = {},
    // @ 动态补全与「+ → Skills 和技能」的数据源（技能、内置插件、MCP 插件；
    // 空列表时只剩附件与文件入口，不再回退写死的 plan/goal/vuln/reverse）
    skills: List<Pair<String, String>> = emptyList(),
    builtinPlugins: List<Triple<String, String, Boolean>> = emptyList(),
    mcpPlugins: List<KernelApi.McpPluginInfo> = emptyList(),
    // @plan 等触发后内核算出的计划书面板（null = 不显示）
    actionPanel: ActionPanelState? = null,
    onDismissActionPanel: () -> Unit = {},
    onSendActionPanel: (String) -> Unit = {},
    // 上下文占比：非 null 时在输入卡外圈画一条细线（替代旧的上下文条）
    contextUsage: ContextUsage? = null,
    // 右上角三点菜单里的任务清单入口（B 接线；未接线时该参数不参与布局）
    onOpenTaskList: (() -> Unit)? = null,
    // 「+ → 当前目录的文件」：由 B 打开工作区文件树；为 null 时该入口不显示
    onOpenWorkspaceFiles: (() -> Unit)? = null,
    // 编辑重发：editBack 非空即处于编辑态，提示条与取消/发送按钮出现
    editBack: Int? = null,
    onCancelEdit: () -> Unit = {},
    // 编辑回填：内核侧 editables 取回的原消息正文与附件（null = 没有在编辑）
    editText: String? = null,
    editAttachments: List<String> = emptyList(),
    // 回填请求的序号：触发 key 用它而不用 editText 的值 —— 同一内容两次
    // （编辑重发同一消息、分享同一链接）也要触发；纯图片分享 text 为 null
    editSeq: Long = 0,
    // 内置浏览器里挑出来、已「添加到对话」的页面元素：以 chip 摆在输入框上方。
    // 文本引用（元素1）由本组件插入 —— 它才持有输入框的真实内容。
    elementChips: List<com.hxy.codeforge.ui.browser.PickedElement> = emptyList(),
    // 一次性插入请求；seq 变化时把 ref 插到输入内容最前面
    elementInsert: com.hxy.codeforge.ui.browser.ElementInsert? = null,
    onRemoveElement: (Int) -> Unit = {},
) {
    var input by rememberSaveable(sessionId) { mutableStateOf(draftText) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    // DeepSeek 式附件面板：⊕ 展开 / ⊗ 收起；与键盘互斥（键盘弹起自动收起）。
    // 开合状态由外部持有 —— AppRoot 要在消息区盖一层遮罩，实现「点外部收起」。
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) { if (imeVisible) onAttachPanelChange(false) }
    // 待发附件（暂存区绝对路径，见 stageAttachment）。选中即复制落盘，
    // 发送时随消息上行；删除只是从待发列表去掉（文件留在暂存区，可再次引用）
    var attachments by rememberSaveable { mutableStateOf(listOf<String>()) }
    // 编辑回填 / 入站分享预填：把正文与附件拉回输入卡。key 用 editSeq 不用
    // editText 的值 —— 同一内容两次（编辑重发同一消息、分享同一链接）也要触发。
    // 正文与附件各自判断：纯图片分享 editText 为 null，只回填附件、不动文字。
    // editBack 变化但正文没变（重试同一轮）不该覆盖用户正在敲的字 —— 那条
    // 路径 vm 侧不会递增 seq，天然不触发。
    LaunchedEffect(editSeq) {
        if (editText != null) {
            input = editText
            // 与正常打字同口径存一次草稿：切会话/退后台回来还能看到在编辑的内容
            onDraftChange(sessionId, editText)
        }
        if (editAttachments.isNotEmpty()) {
            attachments = editAttachments
        }
    }
    val attachScope = androidx.compose.runtime.rememberCoroutineScope()
    // 内置浏览器挑出元素后的文本插入：把「元素N」放到输入内容最前面。
    // 空输入时就是「元素N」本身；有输入时是「元素N + 原有内容」。
    // 用 seq（不是 ref）做 key —— 连续两次可能都是「元素1」。
    //
    // 用户在气泡里同时输入的备注（note）跟着一起进输入框：
    // 「元素N 备注文字」——用户可以一边看着元素一边说怎么改，不用退出浏览器。
    LaunchedEffect(elementInsert?.seq) {
        val ins = elementInsert ?: return@LaunchedEffect
        if (ins.ref.isBlank()) return@LaunchedEffect
        val head = if (ins.note.isBlank()) ins.ref else "${ins.ref} ${ins.note}"
        input = if (input.isBlank()) head else "$head $input"
        onDraftChange(sessionId, input)
    }
    // 模型名：内核 ready 帧的 display_name 优先；用户在弹层里选过则尊重选择。
    var pickedModel by rememberSaveable { mutableStateOf<String?>(null) }
    // 模型名：用户在弹层里选过的优先；否则用内核生效模型的显示名。
    //
    // 但**内核生效模型若不在模型库里**（模型库被清空 / 条目被删 / 从没配过），
    // 那不是用户选过的模型 —— 内核 `config/default.yaml` 里的默认模型
    // （claude-sonnet-4-20250514）不该被当成「当前模型」显示。
    // 内核专门给了 `model_in_library` 这个信号就是为这一种情况。
    val model = pickedModel
        ?: if (modelInLibrary) modelName.ifBlank { "未选择模型" } else "未选择模型"
    // 供应商不是「界面态」：它的真相是**当前生效模型所属的供应商**。
    //
    // **不能用内核 config.provider** —— 那个字段是**协议族**（anthropic | openai，
    // 内核 config/default.yaml 里的默认值就是 "anthropic"），和供应商库里那些
    // 带显示名的记录是两个命名空间，拿来当供应商显示就会变成
    // 「anthropic」与「DeepSeek 官方」并存。详见 AGENTS P-045。
    //
    // 弹层里的选择只用于浏览定位，不参与真实能力判定 ——
    // 早先把本地字符串当真相，勾过库外供应商就会显示「未选择」，而模型其实能对话。
    var providerOverride by rememberSaveable { mutableStateOf("") }
    // 当前模型一旦换成库内模型，供应商应跟着新模型走，而不是残留旧选择
    val effectiveModel = remember(pickedModel, modelName) { pickedModel ?: modelName }
    val activeEntry = remember(libraryModels, effectiveModel) {
        libraryModels.firstOrNull { it.id == effectiveModel || it.name == effectiveModel }
    }
    // 当前生效的供应商：按**内核的生效模型 id**去模型库找条目 ——
    // 那才是真正在用的通道。模型库里没有（库空/模型被删）就没有当前供应商。
    val activeProviderId = remember(libraryModels, effectiveModel, kernelModelId) {
        resolveActiveProviderId(libraryModels, kernelModelId, pickedModel.orEmpty())
    }
    val effectiveProviderId = providerOverride.ifBlank { activeProviderId }
    val provider = remember(providerDetails, libraryModels, effectiveProviderId) {
        if (effectiveProviderId.isBlank()) {
            ""
        } else {
            val fromDetails = providerDetails.firstOrNull { it.id == effectiveProviderId }
            if (fromDetails != null) {
                fromDetails.name.ifBlank { fromDetails.id }
            } else {
                val m = libraryModels.firstOrNull { it.provider == effectiveProviderId }
                if (m != null) m.providerName.ifBlank { m.provider } else effectiveProviderId
            }
        }
    }
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
    // 云端「录完 → 上传 → 回文本」期间的空档：录音已停、结果还没回来。
    // 单独一个状态而不是复用 listening，是因为这期间麦克风已关、不能再显示「正在聆听」
    var transcribing by rememberSaveable { mutableStateOf(false) }

    // 双引擎同构：云端优先，其次系统 SpeechRecognizer
    fun handlePartial(p: String) {
        gotVoiceThisSession = true
        listenBase?.let { input = it + p }
    }

    // 连续听写（B4）：听完一段自动续听，直到手动停止或关开关。
    // autoRestart 在 beginListening 里被赋值为「本次选定引擎的 start 闭包」
    var autoRestart: (() -> Unit)? = null
    fun handleFinal(f: String) {
        gotVoiceThisSession = true
        // 云端「正在转写…」占位提示由这里收掉；系统/离线本来就没有，清空也无害
        voiceNotice = null
        listenBase?.let { input = (it + f).trim() }
        listenBase = null
        onDraftChange(sessionId, input)
        // 连续听写只给系统/离线：云端每段都要上传，自动续录会让请求排队，
        // 上一段结果回来时麦克风又被下一段占住，体验反而更差
        if (com.hxy.codeforge.ui.settings.AppSettings.continuousDictation.value && voiceEngine != "cloud") {
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

    // 云端引擎（硅基流动）：只在开关+Key 齐备时参与路由，见 toggleVoice
    val cloudEngine = remember { CloudVoiceEngine(context) }
    DisposableEffect(cloudEngine) {
        // 云端没有中间结果，onPartial 不接；转写期间由 transcribing 顶住输入区
        cloudEngine.onFinal = {
            transcribing = false
            handleFinal(it)
        }
        cloudEngine.onError = {
            transcribing = false
            handleVoiceError(it)
        }
        cloudEngine.onListeningChanged = { v ->
            listening = v
            // 录音结束（用户点停或 120 秒上限自动停）到上传返回之间是「转写中」：
            // 这期间麦克风已关，input 区必须挡住发送，避免把半截文字发出去。
            // 用户点停的路径 toggleVoice 也会置位，重复赋值无害。
            if (!v && voiceEngine == "cloud") transcribing = true
        }
        onDispose { cloudEngine.destroy() }
    }

    // 云端是否真正可用：启用开关 + Key 齐备。
    val cloudActive = AppSettings.sttCloudEnabled.value && AppSettings.sttCloudApiKey.value.isNotBlank()

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
        // 系统引擎判死（ERROR_CLIENT）：内置离线引擎已移除，没有第二台兜底了。
        // 云端若已配好就直接切过去，否则给出可操作的提示。
        onEngineDead = {
            if (cloudActive) {
                voiceEngine = "cloud"
                beginListening { cloudEngine.start() }
                autoRestart = null
            } else {
                voiceNotice = "系统语音服务不可用；可在设置里启用云端语音识别"
                onOpenSettings()
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
     * 引擎路由。权限检查必须放在路由**之前**：系统引擎与云端引擎都由
     * 服务方代开麦克风，没授权时先要权限，别让引擎自己报「麦克风被占用」。
     */
    fun toggleVoice() {
        if (listening) {
            // 按当前引擎收口：云端停录音后还要等上传，单独进「转写中」空档
            when (voiceEngine) {
                "cloud" -> {
                    cloudEngine.stop()
                    transcribing = true
                    voiceNotice = "正在转写…"
                }

                else -> speech.stop()
            }
            return
        }
        // 上一段还在上传：再开一轮会让请求排队，用户只会觉得「点了没反应」
        if (transcribing) return
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            pendingVoice = true
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        when {
            // 云端优先：仅在用户显式启用且填了 Key 时走（语音配置独立于聊天供应商）
            cloudActive -> {
                voiceEngine = "cloud"
                beginListening { cloudEngine.start() }
                // 云端是「录完再传」：连续听写会让请求排队，故本次不设续听回调
                autoRestart = null
            }

            // 系统引擎：未降级优先
            zhSpeechAvailable(context) &&
                !com.hxy.codeforge.ui.common.VoiceHealth.systemDegraded -> {
                voiceEngine = "system"
                beginListening { speech.start() }
            }

            else -> {
                voiceNotice = "没有可用的语音识别服务；可在设置里启用云端语音识别"
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
        // 云端整段录完才上传，全程没有中间结果：「零结果 20 秒」会误杀长录音，
        // 它的录音上限由引擎自身的 120 秒兜底
        if (voiceEngine == "cloud") return@LaunchedEffect
        delay(20_000)
        if (listening && !gotVoiceThisSession) {
            speech.stop()
            listening = false
            listenBase = null
            // 20 秒零回调 = 服务没响应（hang 不产生错误码），比 ERROR_CLIENT 更死：
            // 直接判降级，下次点按不再走这台引擎，不让用户反复死等
            if (voiceEngine == "system") {
                com.hxy.codeforge.ui.common.VoiceHealth.systemDegraded = true
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

    /**
     * 选择的 content URI → 应用私有暂存区。核心逻辑在
     * [com.hxy.codeforge.core.EnvKit.stageUriToOutbox]（入站分享共用同一份口径），
     * 这里只补本组件的 20MiB 提示。为什么落暂存区不落工作区，见 EnvKit 注释。
     */
    suspend fun stageAttachment(uri: Uri): String? =
        com.hxy.codeforge.core.EnvKit.stageUriToOutbox(context, uri) {
            voiceNotice = "附件超过 20MiB 上限"
        }

    fun onPicked(uri: Uri?) {
        if (uri == null) return
        sheet = null // 选完即收起附件弹层
        attachScope.launch {
            stageAttachment(uri)?.let { path ->
                attachments = attachments + path
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
    // （FileProvider URI 能查 DISPLAY_NAME/SIZE，stageAttachment 无需特判）
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

    // 运行中不再排除：有内容时主按钮变发送（运行中发送 = steer 并入当前轮，
    // 占位符「回复 CodeForge…」即此语义）—— 打断不再吞掉发送入口
    val canSend = (input.isNotBlank() || attachments.isNotEmpty()) && !listening &&
        // 云端转写期间不许发：回来的文字还要接进输入框
        !transcribing

    // 外观（黑/白/透明 + 背景亮度反色 + 手动文字色）与上下文细线的进度
    val palette = rememberComposerColors()
    val ringProgress by animateFloatAsState(
        // displayFraction：有模型真实窗口（ctx_in）时按窗口算，否则退回内核预算 ——
        // 1M 的模型不该在 12 万时就画出 90% 的环
        targetValue = contextUsage?.displayFraction ?: 0f,
        animationSpec = tween(600),
        label = "context-ring",
    )
    // 输入文字样式与 @ 高亮都得用同一份 —— 高亮层只换颜色不改排版
    val inputTextStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        color = palette.text,
    )
    val mentionTransform = remember(palette.mention) { mentionHighlight(palette.mention) }

    // @ 提及候选：技能（@slug）+ 内置插件（@触发词，ViewModel 已把 id 解析成
    // 内核认识的真实触发 token）+ 已加载的 MCP 插件（@名称）。
    // 不再写死 plan/goal/vuln/reverse —— 那四个只是内置插件里最常见的几个，
    // 拿它当全量候选会让「装了的技能补不出来」看起来像补全坏了。
    val mentionCandidates = remember(skills, builtinPlugins, mcpPlugins) {
        buildList {
            skills.forEach { (slug, desc) ->
                val token = slug.removePrefix("@")
                if (token.isNotBlank()) add(MentionCandidate(token, token, desc, "技能"))
            }
            builtinPlugins.forEach { (id, name, enabled) ->
                val token = id.removePrefix("@")
                if (token.isNotBlank()) {
                    add(
                        MentionCandidate(
                            token = token,
                            // 第二项是内核下发的显示名，token 是解析后的真实触发词，两者可以不同
                            title = name.ifBlank { token },
                            description = if (enabled) "内置插件" else "内置插件 · 已关闭",
                            kind = "插件",
                        ),
                    )
                }
            }
            // MCP 只补「已加载」的：未加载的插件工具还没注册，@ 进去内核也调不到
            mcpPlugins.filter { it.running }.forEach { p ->
                val token = p.id.removePrefix("@")
                if (token.isNotBlank()) {
                    add(MentionCandidate(token, p.name.ifBlank { token }, "MCP · 已启用", "MCP"))
                }
            }
        }
    }
    // 取行尾 token 的简化实现：光标在中间时也按行尾处理（移动端输入多在末尾）
    val tailToken = input.takeLastWhile { !it.isWhitespace() }
    val mentionQuery = if (tailToken.startsWith("@")) tailToken.drop(1) else null
    val mentionMatches = if (mentionQuery == null) {
        emptyList()
    } else {
        mentionCandidates.filter { c ->
            c.token != mentionQuery &&
                (c.token.startsWith(mentionQuery, ignoreCase = true) ||
                    c.title.contains(mentionQuery, ignoreCase = true))
        }
        // 不再截断成 4 条：候选来自技能 / 内置插件 / MCP 三个来源，
        // 装多了几十条很正常，硬截断等于「后面那些根本补不出来」。
        // 上限留一个兜底，防止极端情况下渲染几百行；列表本身可滚。
        .take(50)
    }

    fun insertMention(token: String) {
        val prefix = if (tailToken.startsWith("@")) input.dropLast(tailToken.length) else input
        // 从「Skills 和技能」点进来时不一定正好停在 @ 上：前面补一个空格，
        // 否则会粘成「你好@plan」
        val sep = if (prefix.isEmpty() || prefix.last().isWhitespace()) "" else " "
        input = prefix + sep + "@" + token + " "
        onDraftChange(sessionId, input)
    }

    // 编辑提示条与主按钮共用：发送即清空草稿，附件一起带走
    fun doSend() {
        // 运行中发送 = steer，而 steer 帧没有附件字段 —— 带附件时拒绝发送，
        // 附件留在输入框不丢（旧实现会静默把附件丢掉）。等本轮结束再发即可。
        if (running && attachments.isNotEmpty()) {
            voiceNotice = "运行中暂不支持带附件发送，请等本轮结束后再发"
            return
        }
        onSend(input, attachments)
        input = ""
        attachments = listOf()
        onDraftChange(sessionId, "")
    }

    // 「+ → 当前目录的文件」：先收起附件面板，再交给 B 打开工作区文件树
    val openWorkspaceFiles: (() -> Unit)? = if (onOpenWorkspaceFiles == null) {
        null
    } else {
        { onAttachPanelChange(false); onOpenWorkspaceFiles.invoke() }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        // 编辑重发提示条：编辑态下压在最上面，明确告知「发送会截断后面的轮次」
        AnimatedVisibility(
            visible = editBack != null,
            enter = expandVertically(tween(160)) + fadeIn(tween(140)),
            exit = shrinkVertically(tween(140)) + fadeOut(tween(120)),
        ) {
            EditPromptBar(canSend = canSend, onCancel = onCancelEdit, onSend = ::doSend)
        }

        // 计划书面板：输入卡整体上移，面板在它上方展开（不盖住正在输入的文字）
        AnimatedVisibility(
            visible = actionPanel != null,
            enter = expandVertically(tween(200)) + fadeIn(tween(160)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
        ) {
            actionPanel?.let { panel ->
                ActionPanel(
                    state = panel,
                    running = running,
                    onSend = onSendActionPanel,
                    onDismiss = onDismissActionPanel,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }

        // @ 提及候选：每行带描述（技能说明 / 插件用途），点选替换当前 @token
        //
        // 用 LazyColumn 而非 Column：候选来自技能 / 内置插件 / MCP 三个来源，
        // 装得多时能有几十条，普通 Column 会把整张输入卡顶出屏幕 ——
        // 装不下就得能滚。高度上限 240.dp（约 6 行），超出内部滚动。
        if (mentionMatches.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
            ) {
                items(mentionMatches.size, key = { mentionMatches[it].token + "#" + it }) { i ->
                    val c = mentionMatches[i]
                    MentionSuggestionRow(candidate = c, onClick = { insertMention(c.token) })
                }
            }
        }

        Surface(
            // 上下文细线画在卡片自己身上（drawWithContent 在 Surface 内容之后绘制），
            // 不用外面再包一层 Box —— 包一层会让圆角、阴影各算一遍
            modifier = Modifier
                .fillMaxWidth()
                .contextRing(usage = contextUsage, progress = ringProgress),
            shape = RoundedCornerShape(28.dp),
            color = palette.container,
            border = BorderStroke(1.dp, palette.border),
            // 透明底不画阴影：阴影会透出方形底边，看着像一块灰板
            shadowElevation = if (palette.container.alpha >= 0.5f) 2.dp else 0.dp,
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
                // 从内置浏览器挑出来的页面元素：编号 + 元素摘要，× 撤掉。
                // 元素本身的信息在发送时随消息带给内核（见 ChatViewModel.buildWireText），
                // 这里只负责让用户看得见「挂了哪几个」。
                if (elementChips.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, end = 8.dp, top = if (attachments.isEmpty()) 10.dp else 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        elementChips.take(4).forEach { el ->
                            ElementChipInline(
                                ref = el.ref,
                                summary = el.summary,
                                onRemove = { onRemoveElement(el.index) },
                            )
                        }
                        if (elementChips.size > 4) {
                            Text(
                                text = "+${elementChips.size - 4}",
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
                        textStyle = inputTextStyle,
                        cursorBrush = SolidColor(Clay),
                        maxLines = maxLines,
                        // @token 染主色：只看颜色的视觉变换，不碰文字/偏移/组字区
                        visualTransformation = mentionTransform,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        keyboardActions = KeyboardActions(),
                        decorationBox = { inner ->
                            Box {
                                if (input.isEmpty()) {
                                    Text(
                                        text = when {
                                            transcribing -> "正在转写…"
                                            listening -> "正在聆听…"
                                            // 运行中空输入：主按钮此时是「点按说话 · 长按打断」，
                                            // 手势是隐形的 —— 占位符是唯一能教会它的地方；
                                            // 已排队的转向则提醒「在等并入」，别让用户以为没发出去
                                            running -> if (steerQueued) {
                                                "转向已排队 · 当前步骤结束后并入"
                                            } else {
                                                "点按说话 · 长按打断当前任务"
                                            }
                                            else -> "与 CodeForge 对话…"
                                        },
                                        style = inputTextStyle,
                                        color = if (listening || transcribing) {
                                            Clay
                                        } else {
                                            palette.secondary
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
                    CircleIconButton(
                        glyph = Glyph.Plus,
                        label = "添加到对话",
                        contentColor = palette.text,
                        backgroundColor = palette.chrome,
                    ) {
                        keyboard?.hide()
                        onAttachPanelChange(!attachPanelOpen)
                    }
                    Spacer(Modifier.width(6.dp))
                    // 权限盾牌：三档下拉就地切换（onPermissionChange → 内核 /api/perm）
                    PermissionShield(
                        mode = permissionMode,
                        tint = palette.text,
                        background = palette.chrome,
                        onSelect = onPermissionChange,
                    )
                    Spacer(Modifier.width(6.dp))
                    ModelPill(
                        model = model,
                        thinkingLabel = ThinkingLevel.entries[thinkingIndex].label,
                        titleColor = palette.text,
                        subColor = palette.secondary,
                        onClick = { sheet = "model" },
                    )
                    Spacer(Modifier.weight(1f))
                    MainButton(
                        running = running,
                        canSend = canSend,
                        hasContent = input.isNotBlank() || attachments.isNotEmpty(),
                        listening = listening,
                        transcribing = transcribing,
                        voiceBg = palette.text,
                        voiceFg = palette.solid,
                        onSend = ::doSend,
                        onStop = onStop,
                        onVoice = ::toggleVoice,
                    )
                }
            }
        }

        // 附件面板（DeepSeek 式）：最近文件横滑可选 + 拍照/相册/文件三动作
        AnimatedVisibility(
            // 键盘一弹就瞬间消失，不能跑收起动画：外层 inset 已按完整键盘高度把
            // 输入卡顶上去，此时再 shrinkVertically 会先缩掉面板高度、卡片回落，
            // 视觉上是「先弹高再回落」的一次抽搐。键盘收起时仍走原来的收缩淡出。
            visible = attachPanelOpen && !imeVisible,
            enter = expandVertically(tween(180)) + fadeIn(tween(140)),
            exit = if (imeVisible) {
                ExitTransition.None
            } else {
                shrinkVertically(tween(160)) + fadeOut(tween(120))
            },
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
                // 二级入口：先收起附件面板再开弹层，否则面板会被弹层压住看不到收起
                onOpenSkills = {
                    onAttachPanelChange(false)
                    sheet = "skills"
                },
                onOpenWorkspaceFiles = openWorkspaceFiles,
            )
        }

        // 语音状态行：聆听中 / 云端转写中常驻；错误提示短暂停留后自己消失。
        AnimatedVisibility(
            visible = listening || transcribing || voiceNotice != null,
            enter = fadeIn(tween(140)) + expandVertically(tween(160)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(140)),
        ) {
            val text = when {
                transcribing -> "正在转写…"
                listening -> "正在聆听，再点一次麦克风结束"
                else -> voiceNotice.orEmpty()
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = if (listening || transcribing) Clay else MaterialTheme.colorScheme.error,
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
                // 换模型后供应商必须跟随新模型：清掉浏览定位用的覆盖值
                providerOverride = ""
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
            providerDetails = providerDetails,
            kernelModelId = kernelModelId,
            modelInLibrary = modelInLibrary,
            // 传派生值本身：这里再拼「未选择」会把「未知」伪装成一个供应商
            currentProvider = provider,
            // 高亮定位用 id —— 列表里比的是 id，不是显示名
            currentProviderId = effectiveProviderId,
            onProviderSelect = { providerOverride = it },
            thinkingIndex = thinkingIndex,
            onThinkingSelect = { thinkingIndex = it; AppSettings.setThinkingLevel(it) },
            permissionMode = permissionMode,
            // 选完权限也收起（同模型：不收起的话气泡提示会被弹层盖住）
            onPermissionChange = { m -> onPermissionChange(m); sheet = null },
            onDismiss = { sheet = null },
        )
    }

    if (sheet == "skills") {
        // 复用模型弹层的上拉框样式：技能 + 内置插件，点选即插入 @ 提及
        SkillsSheet(
            skills = skills,
            builtinPlugins = builtinPlugins,
            onPick = { token ->
                insertMention(token)
                sheet = null
            },
            onDismiss = { sheet = null },
        )
    }
}

// ---------------------------------------------------------------- 主按钮

/**
 * 主按钮：点按/长按双语义，覆盖语音 · 打断 · 转向 · 发送。
 *
 * 状态判定顺序即优先级：
 *  - listening：收音中，点击 = 收口 —— 中间结果不断进输入框，按钮不能抢跑去变发送键；
 *  - running 且输入为空：**tap = 开始语音，长按 1 秒 = 打断**（外圈进度环实时反馈，
 *    满圈触发并带长按触觉）—— 运行中最常用的两个动作同位并存；
 *  - 有内容且不在转写：发送。运行中发送 = steer 并入当前轮（占位符「回复 CodeForge…」
 *    即此语义），空闲 = 新问题；图标同为上箭头；
 *  - transcribing：云端转写空档，点击忽略（回来的文字还要接进输入框）；
 *  - 其余（空闲+空）：待听写。
 *
 * 状态切换带缩放渐变。长按进度环画在按钮外缘（Clay 色，-90° 起顺时针）。
 */
@Composable
private fun MainButton(
    running: Boolean,
    canSend: Boolean,
    hasContent: Boolean,
    listening: Boolean,
    transcribing: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onVoice: () -> Unit,
    // 黑/白/透明底上，深色声纹键会跟底色糊在一起 —— 由调用方把反色传进来
    voiceBg: Color = MaterialTheme.colorScheme.onBackground,
    voiceFg: Color = MaterialTheme.colorScheme.background,
) {
    val state = when {
        listening -> "listening"
        running && !hasContent -> "stopVoice"
        canSend -> "send"
        transcribing -> "transcribing"
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
        if (target == "stopVoice") {
            StopVoiceButton(onTapVoice = onVoice, onStop = onStop, voiceBg = voiceBg, voiceFg = voiceFg)
        } else {
            val noop: () -> Unit = {}
            val (bg, onClick) = when (target) {
                "listening" -> Clay to onVoice
                "stop" -> MaterialTheme.colorScheme.surfaceVariant to onStop
                "send" -> Clay to onSend
                "transcribing" -> voiceBg to noop
                else -> voiceBg to onVoice
            }
            val fg = when (target) {
                "voice", "transcribing" -> voiceFg
                else -> Color.White
            }
            val desc = when (target) {
                "listening" -> "收口，结束听写"
                "stop" -> "停止当前任务"
                "send" -> "发送"
                "transcribing" -> "正在转写"
                else -> "语音输入"
            }
            // 绘制块（Canvas lambda）里不能调 composable，颜色在这里先取好
            val stopColor = MaterialTheme.colorScheme.onSurface

            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(bg, CircleShape)
                    .semantics { contentDescription = desc }
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
}

/**
 * 运行中且输入为空的主按钮：tap = 开始语音，长按 1 秒 = 打断。
 *
 * 长按进度画在按钮外缘一圈（Clay 色弧，从顶部顺时针），既提示「正在积累打断」，
 * 也不会像变色那样让人误以为按钮换了功能。松手早于 1 秒 = 一次点按（开始语音）；
 * 满圈触发打断并给长按触觉反馈 —— 打断是破坏性动作，触觉确认不能省。
 */
@Composable
private fun StopVoiceButton(
    onTapVoice: () -> Unit,
    onStop: () -> Unit,
    voiceBg: Color,
    voiceFg: Color,
) {
    val haptic = LocalHapticFeedback.current
    var progress by remember { mutableStateOf(0f) }
    var fired by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .size(40.dp)
            .background(voiceBg, CircleShape)
            .semantics { contentDescription = "语音输入；长按一秒打断当前任务" }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        fired = false
                        // 进度循环放子协程：release 时 cancel，提前松手不会误触发打断。
                        // onPress 的 receiver（PressGestureScope）不是 CoroutineScope，
                        // launch 必须包进 coroutineScope，与 tryAwaitRelease 并行。
                        coroutineScope {
                            val loop = launch {
                                val start = System.nanoTime()
                                while (isActive) {
                                    withFrameNanos { now ->
                                        progress = ((now - start) / 1_000_000_000f).coerceIn(0f, 1f)
                                    }
                                    if (progress >= 1f) break
                                }
                                if (progress >= 1f && !fired) {
                                    fired = true
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onStop()
                                }
                            }
                            tryAwaitRelease()
                            loop.cancel()
                            progress = 0f
                        }
                        // 没按满一圈 = 一次点按 → 开始语音
                        if (!fired) onTapVoice()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (progress > 0f) {
            Canvas(modifier = Modifier.size(40.dp)) {
                drawArc(
                    color = Clay,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 3.dp.toPx(),
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    ),
                )
            }
        }
        VoiceWaveform(color = voiceFg, active = false)
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
private fun CircleIconButton(
    glyph: Glyph,
    label: String,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(backgroundColor, CircleShape)
            .noRippleClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(
            glyph = glyph,
            size = 17.dp,
            color = contentColor,
        )
    }
}

@Composable
private fun ModelPill(
    model: String,
    thinkingLabel: String,
    enabled: Boolean = true,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    subColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    titleColor
                } else {
                    // 锁住时整颗胶囊压暗：测试模型不吃供应商配置，改也改不动
                    titleColor.copy(alpha = 0.38f)
                },
            )
            Text(
                text = " $thinkingLabel",
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) {
                    subColor
                } else {
                    subColor.copy(alpha = 0.38f)
                },
            )
        }
    }
}

// ---------------------------------------------------------------- 编辑提示条
/**
 * 编辑重发提示条。编辑态下内核会截断这条消息之后的轮次并回退文件改动，
 * 这件事不写出来用户不知道 —— 点了发送才发现后面的对话没了。
 */
@Composable
private fun EditPromptBar(canSend: Boolean, onCancel: () -> Unit, onSend: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "正在编辑这条消息",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "发送将丢弃其后的对话与文件改动",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "取消",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .noRippleClickable(onClick = onCancel)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Text(
                text = "发送",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = if (canSend) Clay else Clay.copy(alpha = 0.4f),
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .noRippleClickable(enabled = canSend, onClick = onSend)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- @ 提及高亮

/**
 * @ 提及染色。
 *
 * 用 [VisualTransformation] 而不是「输入文字透明 + 镜像 Text 叠层」：镜像层要让
 * 输入文字透明才看得见它自己的颜色，而中文输入法的组字区（composing region）
 * 也吃同一个文字色 —— 拼音打到一半整行看不见，等于没法用。视觉变换只给
 * @token 换颜色，文字、换行、光标、选区、组字全部按原样走。
 */
private fun mentionHighlight(mentionColor: Color): VisualTransformation =
    VisualTransformation { text ->
        val raw = text.text
        val annotated = buildAnnotatedString {
            append(raw)
            var i = 0
            while (i < raw.length) {
                val at = raw.indexOf('@', i)
                if (at < 0) break
                var end = at + 1
                while (end < raw.length && !raw[end].isWhitespace()) end++
                // @ 前面必须是行首或空白：别把邮箱、装饰符当成提及
                val boundary = at == 0 || raw[at - 1].isWhitespace()
                if (boundary && end > at + 1) {
                    addStyle(SpanStyle(color = mentionColor, fontWeight = FontWeight.Medium), at, end)
                }
                i = end
            }
        }
        TransformedText(annotated, OffsetMapping.Identity)
    }

/** 一条 @ 候选：token 是插入的字符（不含 @），title/description 是展示用。 */
private data class MentionCandidate(
    val token: String,
    val title: String,
    val description: String,
    val kind: String,
)

@Composable
private fun MentionSuggestionRow(candidate: MentionCandidate, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .noRippleClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "@" + candidate.token,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = Clay,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 140.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = listOf(candidate.title, candidate.description)
                .filter { it.isNotBlank() && it != candidate.token }
                .distinct()
                .joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = candidate.kind,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

// ---------------------------------------------------------------- 权限盾牌

/** 盾牌图标：轮廓常显，自主模式填充实心（一眼看出「不用问」）。 */
@Composable
private fun ShieldGlyph(color: Color, filled: Boolean) {
    Canvas(modifier = Modifier.size(15.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.5f, h * 0.05f)
            lineTo(w * 0.92f, h * 0.24f)
            lineTo(w * 0.92f, h * 0.54f)
            cubicTo(w * 0.92f, h * 0.82f, w * 0.72f, h * 0.94f, w * 0.5f, h * 0.97f)
            cubicTo(w * 0.28f, h * 0.94f, w * 0.08f, h * 0.82f, w * 0.08f, h * 0.54f)
            lineTo(w * 0.08f, h * 0.24f)
            close()
        }
        if (filled) {
            drawPath(path, color)
        } else {
            drawPath(path, color, style = Stroke(width = w * 0.11f))
        }
    }
}

private fun permissionHint(mode: PermissionMode): String = when (mode) {
    PermissionMode.ReadOnly -> "只看不改"
    PermissionMode.Ask -> "改动前先问"
    PermissionMode.Auto -> "自主执行"
}

/**
 * 权限盾牌 + 三档下拉。盾牌本身常驻在输入卡底行左侧，点开即切档；
 * 选择结果走 [onSelect] → `ChatViewModel.setPermissionMode` → 内核 `/api/perm`。
 */
@Composable
private fun PermissionShield(
    mode: PermissionMode,
    tint: Color,
    background: Color,
    onSelect: (PermissionMode) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(background, CircleShape)
                .noRippleClickable { open = true },
            contentAlignment = Alignment.Center,
        ) {
            ShieldGlyph(
                // 只读档压暗：这一档最安全，视觉上也该最「轻」
                color = if (mode == PermissionMode.ReadOnly) tint.copy(alpha = 0.55f) else tint,
                filled = mode == PermissionMode.Auto,
            )
        }
        // 显式给本项目暖色主题色：默认菜单容器走 M3 的 surfaceContainer 色板，
        // 与本项目 surface/outlineVariant 不是一个体系，展开时会突然变灰一块
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 1f),
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            PermissionMode.entries.forEach { m ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(m.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = permissionHint(m),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    trailingIcon = {
                        if (m == mode) {
                            GlyphIcon(glyph = Glyph.Check, size = 14.dp, color = Clay)
                        }
                    },
                    colors = MenuDefaults.itemColors(
                        textColor = MaterialTheme.colorScheme.onSurface,
                        trailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    onClick = {
                        open = false
                        onSelect(m)
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 外观与上下文细线

/** 输入卡配色：容器 / 底行按钮底色 / 不透明等价色 / 描边 / 正文 / 次要文字 / @ 高亮。 */
private data class ComposerColors(
    val container: Color,
    val chrome: Color,
    val solid: Color,
    val border: Color,
    val text: Color,
    val secondary: Color,
    val mention: Color,
)

/**
 * 外观设置读的是 `AppSettings` 的字段（D 负责存）。
 *
 * 文字色优先级：手填颜色 > [LocalAppTextColor]（背景图暗时由主题层判出的白字）
 * > 主题 onSurface。背景图亮度不在这里算 —— 主题层已经按「原图平均亮度 ×
 * 亮度百分比」判过一轮，Composer 再算一遍只会在边界上给出不一致的结论。
 */

/** 手填文字色：接受 #RRGGBB / #AARRGGBB（不带 # 也认），解析失败即视为未设置。 */
private fun parseColorPref(raw: String): Color? {
    val hex = raw.trim().removePrefix("#")
    if (hex.length != 6 && hex.length != 8) return null
    return runCatching {
        val v = hex.toLong(16)
        Color(if (hex.length == 8) v else 0xFF000000L or v)
    }.getOrNull()
}

/**
 * 解析输入卡外观：
 *  - 黑/白两档直接给定容器与反色文字（用户手填的颜色仍然优先）；
 *  - 透明档只留描边，文字跟随主题层判出的颜色；
 *  - 默认档跟随主题配色，有背景图时卡面留一点透明让背景透出来。
 */
@Composable
private fun rememberComposerColors(): ComposerColors {
    val style = AppSettings.inputBoxStyle.value
    val hasImage = AppSettings.backgroundImagePath.value.isNotBlank()
    val overrideColor = parseColorPref(AppSettings.textColorOverride.value)
    // 主题层在背景图偏暗时会把这里置成白字（或直接给出用户手填的颜色）
    val localText = LocalAppTextColor.current

    val themeSurface = MaterialTheme.colorScheme.surface
    val themeVariant = MaterialTheme.colorScheme.surfaceVariant
    val themeText = MaterialTheme.colorScheme.onSurface
    val themeBorder = MaterialTheme.colorScheme.outlineVariant

    val text = overrideColor ?: localText ?: themeText

    return when (style) {
        "black" -> {
            val ink = overrideColor ?: Color(0xFFECEBE7)
            ComposerColors(
                container = Color(0xFF131315),
                chrome = Color(0xFF26262A),
                solid = Color(0xFF131315),
                border = Color(0xFF33333A),
                text = ink,
                secondary = ink.copy(alpha = 0.6f),
                mention = Clay,
            )
        }

        "white" -> {
            val ink = overrideColor ?: Color(0xFF1F1F1D)
            ComposerColors(
                container = Color(0xFFFFFFFF),
                chrome = Color(0xFFF1F0EC),
                solid = Color(0xFFFFFFFF),
                border = Color(0xFFD8D5CC),
                text = ink,
                secondary = ink.copy(alpha = 0.6f),
                mention = Clay,
            )
        }

        "clear" -> ComposerColors(
            container = Color.Transparent,
            chrome = themeVariant.copy(alpha = 0.55f),
            solid = themeSurface,
            border = themeBorder,
            text = text,
            secondary = text.copy(alpha = 0.6f),
            mention = Clay,
        )

        else -> ComposerColors(
            // 有背景图时留一点透：完全遮住的话用户设了背景也看不到
            container = themeSurface.copy(alpha = if (hasImage) 0.92f else 1f),
            chrome = themeVariant,
            solid = themeSurface,
            border = themeBorder,
            text = text,
            secondary = text.copy(alpha = 0.6f),
            mention = Clay,
        )
    }
}

/**
 * 上下文占比细线：沿输入卡外圈画一圈，画到 percent 为止（底轨很浅）。
 * 取代原先插在输入卡上方、额外占一行高度的上下文条。
 */
private fun Modifier.contextRing(usage: ContextUsage?, progress: Float): Modifier =
    if (usage == null) {
        this
    } else {
        this.drawWithContent {
            drawContent()
            val stroke = 1.6.dp.toPx()
            val inset = stroke / 2f
            val cardW = size.width - stroke
            val cardH = size.height - stroke
            if (cardW <= 0f || cardH <= 0f) return@drawWithContent
            val radius = (28.dp.toPx() - inset).coerceAtLeast(0f)
            val topLeftPx = Offset(inset, inset)
            val sizePx = Size(cardW, cardH)
            val color = if (usage.overBudget || progress >= 0.9f) Color(0xFFD25757) else Clay
            drawRoundRect(
                color = color.copy(alpha = 0.14f),
                topLeft = topLeftPx,
                size = sizePx,
                cornerRadius = CornerRadius(radius, radius),
                style = Stroke(width = stroke),
            )
            drawRingProgress(topLeftPx, sizePx, radius, progress.coerceIn(0f, 1f), color, stroke)
        }
    }

/**
 * 圆角矩形周长进度。不用 Path/PathMeasure：PathMeasure.getSegment 在部分
 * Compose 版本上是实验 API，而这里只需要画四条直边 + 四段四分之一圆。
 */
private fun DrawScope.drawRingProgress(
    topLeft: Offset,
    box: Size,
    radius: Float,
    fraction: Float,
    color: Color,
    strokeWidth: Float,
) {
    if (fraction <= 0f) return
    val r = radius.coerceAtMost(min(box.width, box.height) / 2f)
    val edgeH = (box.width - 2 * r).coerceAtLeast(0f)
    val edgeV = (box.height - 2 * r).coerceAtLeast(0f)
    val arcLen = (PI * r).toFloat() / 2f
    val total = 2 * edgeH + 2 * edgeV + 4 * arcLen
    if (total <= 0f) return

    var quota = fraction * total
    val x0 = topLeft.x
    val y0 = topLeft.y
    val right = x0 + box.width
    val bottom = y0 + box.height
    val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)

    fun line(from: Offset, to: Offset, length: Float) {
        if (quota <= 0f || length <= 0f) return
        val take = min(quota, length)
        val t = take / length
        drawLine(
            color = color,
            start = from,
            end = Offset(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        quota -= take
    }

    fun arc(cornerTopLeft: Offset, startAngle: Float) {
        if (quota <= 0f || arcLen <= 0f) return
        val sweep = (quota / arcLen).coerceAtMost(1f) * 90f
        drawArc(
            color = color,
            startAngle = startAngle,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = cornerTopLeft,
            size = Size(2 * r, 2 * r),
            style = stroke,
        )
        quota -= min(quota, arcLen)
    }

    line(Offset(x0 + r, y0), Offset(right - r, y0), edgeH)
    arc(Offset(right - 2 * r, y0), -90f)
    line(Offset(right, y0 + r), Offset(right, bottom - r), edgeV)
    arc(Offset(right - 2 * r, bottom - 2 * r), 0f)
    line(Offset(right - r, bottom), Offset(x0 + r, bottom), edgeH)
    arc(Offset(x0, bottom - 2 * r), 90f)
    line(Offset(x0, bottom - r), Offset(x0, y0 + r), edgeV)
    arc(Offset(x0, y0), 180f)
}

