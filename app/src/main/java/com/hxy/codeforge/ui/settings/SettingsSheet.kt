package com.hxy.codeforge.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hxy.codeforge.core.EnvKit
import com.hxy.codeforge.ui.ChatViewModel
import com.hxy.codeforge.ui.common.Glyph
import com.hxy.codeforge.ui.common.GlyphIcon
import com.hxy.codeforge.ui.common.noRippleClickable
import com.hxy.codeforge.ui.theme.Clay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 设置弹层（抽屉底栏入口）。
 *
 * 二级导航：主列表 → 各子页（外观/模型/记忆/技能/内置插件/MCP/归档/环境/内核/语音/诊断）。
 * 子页切换用直接 `when` + `animateContentSize` —— **不能**用 AnimatedContent：
 * 它在 verticalScroll 的 Column 里拿不到有界高度，新旧页会永久叠在一起（P-023）。
 *
 * 本页只负责「写 AppSettings / 调 VM 服务方法」，所有状态仍来自 [ChatViewModel.state]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(vm: ChatViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        // null = 主列表；其余为子页 key
        var page by rememberSaveable { mutableStateOf<String?>(null) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            val pageTitle = PAGE_TITLES[page]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (pageTitle != null) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .noRippleClickable { page = null },
                        contentAlignment = Alignment.Center,
                    ) {
                        GlyphIcon(
                            glyph = Glyph.Back,
                            size = 18.dp,
                            color = MaterialTheme.colorScheme.onSurface,
                            strokeWidth = 2.6.dp,
                        )
                    }
                }
                Text(
                    text = pageTitle ?: "设置",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = if (pageTitle != null) 4.dp else 0.dp),
                )
            }

            Column(
                modifier = Modifier.animateContentSize(animationSpec = tween(220)),
            ) {
                when (page) {
                    null -> SettingsHome(onOpen = { page = it })
                    "appearance" -> AppearanceSection()
                    "model" -> ModelSection(vm)
                    "memory" -> MemorySection(vm)
                    "skills" -> SkillsSection(vm)
                    "plugins" -> BuiltinPluginsSection(vm)
                    "mcp" -> McpPluginsSection(vm)
                    "archive" -> ArchiveSection(vm)
                    "env" -> EnvSection(context)
                    "transfer" -> FileTransferSection(vm)
                    "kernel" -> KernelSettingsSection(vm)
                    "voice" -> VoiceSection(context)
                    "diag" -> DiagnosticsSection()
                }
            }
        }
    }
}

private val PAGE_TITLES = mapOf(
    "appearance" to "外观与交互",
    "model" to "模型服务",
    "memory" to "记忆",
    "skills" to "技能",
    "plugins" to "内置插件",
    "mcp" to "MCP 服务",
    "archive" to "已归档会话",
    "env" to "运行环境",
    "transfer" to "文件传输",
    "kernel" to "内核设置",
    "voice" to "语音输入",
    "diag" to "诊断",
)

/**
 * 设置主列表。
 *
 * 原来六组设置挤在一个长滚动页里，手机上要滑很久才能找到目标项，
 * 且「内核设置」那种大块会把上面的开关挤到视线外。拆成二级：
 * 这里只放入口，内容在各自子页。
 */
@Composable
private fun SettingsHome(onOpen: (String) -> Unit) {
    val items = listOf(
        Triple("model", "模型服务", "模型库、供应商、添加与探测"),
        Triple("appearance", "外观与交互", "输入框样式、背景图、模糊/亮度、文字颜色、旋转"),
        Triple("memory", "记忆", "增删改用户记忆"),
        Triple("skills", "技能", "技能列表与触发说明"),
        Triple("plugins", "内置插件", "开关与用途说明"),
        Triple("mcp", "MCP 服务", "外部 MCP 的增删与开关"),
        Triple("archive", "已归档会话", "恢复或永久删除"),
        Triple("env", "运行环境", "Linux 兼容环境、语言软件包"),
        Triple("transfer", "文件传输", "导入到工作区、导出到手机 Download"),
        Triple("kernel", "内核设置", "重试、权限、智能体"),
        Triple("voice", "语音输入", "系统语音服务、云端转写"),
        Triple("diag", "诊断", "崩溃与环境日志、导出"),
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        items.forEach { (key, title, sub) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .noRippleClickable { onOpen(key) }
                    .padding(horizontal = 4.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(
                    text = "›",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

// ---------------------------------------------------------------- 外观

/**
 * 外观与交互。
 *
 * 这里的值只落到 [AppSettings]（持久化），真正的绘制在聊天页：
 * 输入框外观由 Composer 读 `inputBoxStyle`，背景图/模糊/亮度与文字颜色由
 * AppRoot 的根容器读。壳侧**不调**内核 `/api/appearance` —— 那是网页端
 * CSS 变量的入口，原生主题不需要绕一圈。
 */
@Composable
private fun AppearanceSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var copyError by remember { mutableStateOf<String?>(null) }

    // ---------------- 输入框外观 ----------------
    SectionTitle("输入框外观")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            "default" to "默认",
            "black" to "黑色",
            "white" to "白色",
            "clear" to "透明",
        ).forEach { (id, label) ->
            ChoiceChip(label, AppSettings.inputBoxStyle.value == id) {
                AppSettings.setInputBoxStyle(id)
            }
        }
    }
    Text(
        text = "「默认」跟随主题；透明会露出背景图。样式在聊天页的输入卡上生效。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )

    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(vertical = 14.dp),
    )

    // ---------------- 背景图 ----------------
    SectionTitle("背景图")
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val path = copyBackgroundImage(context, uri)
                if (path != null) {
                    AppSettings.setBackgroundImage(path)
                    copyError = null
                } else {
                    // 相册给的 uri 可能读不了（云端图片/权限），不能让用户以为选上了
                    copyError = "读取所选图片失败，换一张本地图片试试"
                }
            }
        }
    }
    val bgPath = AppSettings.backgroundImagePath.value
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (bgPath.isBlank()) {
                "未设置（默认纯色背景）"
            } else {
                "当前：${bgPath.substringAfterLast('/')}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        SmallPill("选择图片") {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        if (bgPath.isNotBlank()) {
            SmallPill("清除") {
                AppSettings.setBackgroundImage("")
                runCatching { java.io.File(bgPath).delete() }
            }
        }
    }
    copyError?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    // ---------------- 模糊 / 亮度 ----------------
    var blur by remember { mutableFloatStateOf(AppSettings.backgroundBlur.intValue.toFloat()) }
    Text(
        text = "背景模糊：${blur.roundToInt()} dp",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 12.dp),
    )
    Slider(
        value = blur,
        onValueChange = { blur = it },
        // 拖拽期间只改本地值，松手才落盘 —— 每一帧都写 prefs 会白刷一串磁盘 IO
        onValueChangeFinished = { AppSettings.setBackgroundBlur(blur.roundToInt()) },
        valueRange = 0f..AppSettings.BACKGROUND_BLUR_MAX.toFloat(),
        steps = AppSettings.BACKGROUND_BLUR_MAX - 1,
    )

    var brightness by remember {
        mutableFloatStateOf(AppSettings.backgroundBrightness.intValue.toFloat())
    }
    Text(
        text = "背景亮度：${brightness.roundToInt()}%",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Slider(
        value = brightness,
        onValueChange = { brightness = it },
        onValueChangeFinished = { AppSettings.setBackgroundBrightness(brightness.roundToInt()) },
        valueRange = AppSettings.BACKGROUND_BRIGHTNESS_MIN.toFloat()..AppSettings.BACKGROUND_BRIGHTNESS_MAX.toFloat(),
        steps = AppSettings.BACKGROUND_BRIGHTNESS_MAX - AppSettings.BACKGROUND_BRIGHTNESS_MIN - 1,
    )

    // ---------------- 文字颜色 ----------------
    SectionTitle("文字颜色")
    val colorOverride = AppSettings.textColorOverride.value
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("" to "自动", "#1F1E1C" to "深色", "#FAF7F0" to "浅色").forEach { (id, label) ->
            ChoiceChip(label, colorOverride == id) { AppSettings.setTextColorOverride(id) }
        }
    }
    Text(
        text = "「自动」按背景亮度决定黑/白；手动设置优先级更高。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
    var hexDraft by remember(colorOverride) {
        mutableStateOf(colorOverride)
    }
    OutlinedTextField(
        value = hexDraft,
        onValueChange = { raw ->
            val v = raw.filter { it == '#' || it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }.take(7)
            hexDraft = v
            // 合法才写回；半截输入不落盘，避免把主题刷成随机色
            if (v.matches(Regex("^#[0-9A-Fa-f]{6}$"))) AppSettings.setTextColorOverride(v)
        },
        label = { Text("自定义颜色（#RRGGBB，留空=自动）") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )

    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(vertical = 14.dp),
    )

    // ---------------- 系统状态栏 ----------------
    // 默认关闭（= 隐藏状态栏）：聊天页要的是整屏，状态栏那条会把顶栏往下压。
    // 真正的 show/hide 在 MainActivity 的 WindowInsetsController 里做，
    // 这里只落持久化值；隐藏后 safeDrawing 顶 inset 自动变 0，布局不用跟着改。
    SectionTitle("系统状态栏")
    val showStatusBar = AppSettings.showStatusBar.value
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { AppSettings.setShowStatusBar(!showStatusBar) }
            .padding(vertical = 12.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "显示系统状态栏",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "关闭后 app 内容顶到屏幕最上方",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Switch(
            checked = showStatusBar,
            onCheckedChange = { AppSettings.setShowStatusBar(it) },
            colors = SwitchDefaults.colors(checkedTrackColor = Clay),
        )
    }

    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(vertical = 14.dp),
    )

    // ---------------- 横屏旋转（保留既有设置） ----------------
    SectionTitle("横屏旋转")
    val rotateEnabled = AppSettings.rotateWithDevice.value
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { AppSettings.setRotateWithDevice(!rotateEnabled) }
            .padding(vertical = 12.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "横持时旋转界面",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "关闭后完全锁定竖屏，横过来画面不转",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Switch(
            checked = rotateEnabled,
            onCheckedChange = { AppSettings.setRotateWithDevice(it) },
            colors = SwitchDefaults.colors(checkedTrackColor = Clay),
        )
    }

    Text(
        text = "灵敏度",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 6.dp, bottom = 6.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RotationSensitivity.entries.forEach { level ->
            ChoiceChip(level.label, AppSettings.rotationSensitivity.value == level) {
                AppSettings.setRotationSensitivity(level)
            }
        }
    }
}

/**
 * 把相册选的图复制到应用私有目录。
 *
 * 不保存 content:// —— Photo Picker 给的临时读权限进程死后就失效，
 * 下次启动背景会白屏。落地成一个固定文件名，旧图顺手删掉。
 */
private suspend fun copyBackgroundImage(context: Context, uri: Uri): String? =
    withContext(Dispatchers.IO) {
        runCatching {
            val dir = java.io.File(context.filesDir, "appearance").apply { mkdirs() }
            val ext = when (context.contentResolver.getType(uri)) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                else -> "jpg"
            }
            val target = java.io.File(dir, "background.$ext")
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            } ?: return@runCatching null
            // 旧背景（换过扩展名会留下另一个文件）在写入成功后再删
            dir.listFiles()?.forEach { if (it != target) it.delete() }
            target.absolutePath
        }.getOrNull()
    }

// ---------------------------------------------------------------- 模型

/**
 * 模型服务：模型库（按供应商分组）、供应商双栏（列表 + 详情）、模型编辑表单、探测面板。
 *
 * 三条刷新链路要注意：
 *  - `vm.applyModel` 只切通道，不刷新列表 → 这里补 `loadKernelExtras()`；
 *  - `vm.saveModel` / `vm.deleteModel` 内部会 `refreshKernelSummaries`，再补一次无害；
 *  - `vm.loadArchived` 与本页无关，别混。
 */
@Composable
private fun ModelSection(vm: ChatViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()

    // null / "library" / "providers" / "providerEdit" / "modelEdit"
    var sub by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedProvider by rememberSaveable { mutableStateOf("") }

    // 模型编辑初始值（不能用 rememberSaveable 存 data class，拆成三个字符串）
    var editingModelId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingModelName by rememberSaveable { mutableStateOf("") }
    var editingModelProvider by rememberSaveable { mutableStateOf("") }

    // 供应商表单
    var fId by rememberSaveable { mutableStateOf("") }
    var fName by rememberSaveable { mutableStateOf("") }
    var fUrl by rememberSaveable { mutableStateOf("") }
    var fProtocol by rememberSaveable { mutableStateOf("openai") }
    var fKey by rememberSaveable { mutableStateOf("") }
    var fKeyTouched by rememberSaveable { mutableStateOf(false) }
    var pendingProviderDelete by remember { mutableStateOf<String?>(null) }
    var pendingModelDelete by remember { mutableStateOf<String?>(null) }

    // 探测
    var discovering by remember { mutableStateOf<String?>(null) }
    var discovered by remember { mutableStateOf<List<String>>(emptyList()) }
    var discoverProvider by remember { mutableStateOf("") }
    var discoverError by remember { mutableStateOf<String?>(null) }
    // 内核回退到了「当前生效模型」的密钥（该供应商自己没有独立密钥）。
    // 不显示的话，「探测用的到底是谁的密钥」就是黑箱 —— 用户看到的只是探测失败。
    var discoverKeyFromActive by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refreshProviderDetails() }
    LaunchedEffect(Unit) { vm.loadKernelExtras() }
    // 双栏默认选中第一家：详情区空着会让人以为「供应商点不进去」
    LaunchedEffect(state.providerDetails) {
        if (selectedProvider.isBlank()) {
            selectedProvider = state.providerDetails.firstOrNull()?.id.orEmpty()
        }
    }

    fun resetProviderForm() {
        fId = ""; fName = ""; fUrl = ""; fProtocol = "openai"
        fKey = ""; fKeyTouched = false
    }

    fun openModelEditor(model: com.hxy.codeforge.model.LibraryModel?, providerId: String) {
        editingModelId = model?.id ?: ""
        editingModelName = model?.name.orEmpty()
        editingModelProvider = model?.provider ?: providerId
        sub = "modelEdit"
    }

    val providerNames = state.providerDetails.associate { it.id to it.name.ifBlank { it.id } }
    val addedIds = state.libraryModels.map { it.id }.toSet()

    Column(modifier = Modifier.fillMaxWidth()) {
        when (sub) {
            // ---------------- 主入口 ----------------
            null -> {
                SectionTitle("供应商与模型")
                SubPageEntry("模型库", "${state.libraryModels.size} 个") { sub = "library" }
                SubPageEntry("供应商", "${state.providerDetails.size} 个") { sub = "providers" }
                SubPageEntry("添加模型", "") { openModelEditor(null, "") }
                SubPageEntry("添加供应商", "") {
                    resetProviderForm()
                    sub = "providerEdit"
                }
            }

            // ---------------- 模型库（按供应商分组） ----------------
            "library" -> {
                BackRow { sub = null }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "模型库（${state.libraryModels.size}）",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    SmallPill("+ 添加模型") { openModelEditor(null, "") }
                }
                if (state.libraryModels.isEmpty()) {
                    Text(
                        text = "（空。到「供应商」里点「获取模型列表」，或直接添加模型）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }
                // 按 provider_id 分组；未归属的排最后
                val groups = state.libraryModels.groupBy { it.provider }
                    .toList()
                    .sortedBy { (provider, _) -> if (provider.isBlank()) 1 else 0 }
                groups.forEach { (provider, models) ->
                    Text(
                        text = providerNames[provider] ?: if (provider.isBlank()) "未归属供应商" else provider,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = Clay,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                    models.forEach { m ->
                        ModelRow(
                            model = m,
                            isCurrent = state.modelName == m.id,
                            pendingDelete = pendingModelDelete == m.id,
                            onSetCurrent = {
                                // lastModel 是「用户意图」，applyModel 只切内核通道；
                                // 两边都写，重启后 syncModelToKernel 才有得比
                                AppSettings.setLastModel(m.id)
                                vm.applyModel(m.id)
                                vm.loadKernelExtras()
                            },
                            onEdit = { openModelEditor(m, provider) },
                            onDelete = {
                                if (pendingModelDelete == m.id) {
                                    vm.deleteModel(m.id)
                                    pendingModelDelete = null
                                } else {
                                    pendingModelDelete = m.id
                                }
                            },
                        )
                    }
                }
            }

            // ---------------- 供应商（左列表 + 右详情） ----------------
            "providers" -> {
                BackRow { sub = null }
                SectionTitle("供应商")
                if (state.providerDetails.isEmpty()) {
                    Text(
                        text = "（暂无供应商，点下方添加）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        // 左列：供应商名（窄）
                        Column(modifier = Modifier.weight(1f)) {
                            state.providerDetails.forEach { p ->
                                val sel = selectedProvider == p.id
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (sel) {
                                                Clay.copy(alpha = 0.14f)
                                            } else {
                                                androidx.compose.ui.graphics.Color.Transparent
                                            },
                                        )
                                        .noRippleClickable { selectedProvider = p.id }
                                        .padding(horizontal = 6.dp, vertical = 10.dp),
                                ) {
                                    Text(
                                        text = p.name.ifBlank { p.id },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                    )
                                    Text(
                                        text = p.protocol,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        // 右列：详情（连接信息 + 其下模型）
                        Column(modifier = Modifier.weight(1.5f)) {
                            val p = state.providerDetails.firstOrNull { it.id == selectedProvider }
                            if (p == null) {
                                Text(
                                    text = "点左侧选择供应商",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 10.dp),
                                )
                            } else {
                                Text(
                                    text = p.name.ifBlank { p.id },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "id：${p.id}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = "${p.protocol} · ${p.baseUrl.ifBlank { "（未填地址）" }}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = "密钥：${p.keySource}${if (p.keyName.isNotBlank()) " / ${p.keyName}" else ""}" +
                                        if (p.disabled) " · 已停用" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(
                                    modifier = Modifier.padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    SmallPill("编辑") {
                                        fId = p.id
                                        fName = p.name
                                        fUrl = p.baseUrl
                                        fProtocol = p.protocol
                                        fKey = ""
                                        fKeyTouched = false
                                        sub = "providerEdit"
                                    }
                                    SmallPill(if (discovering == p.id) "探测中…" else "获取模型列表") {
                                        if (discovering == null) {
                                            discovering = p.id
                                            discoverError = null
                                            discoverKeyFromActive = false
                                            vm.discoverModels(p.id) { list, err, keyFromActive ->
                                                discovering = null
                                                discoverProvider = p.id
                                                discoverError = err
                                                discoverKeyFromActive = keyFromActive
                                                discovered = list
                                            }
                                        }
                                    }
                                    SmallPill(
                                        if (pendingProviderDelete == p.id) "确认删除" else "删除",
                                        danger = pendingProviderDelete == p.id,
                                    ) {
                                        if (pendingProviderDelete == p.id) {
                                            vm.deleteProvider(p.id)
                                            pendingProviderDelete = null
                                            if (selectedProvider == p.id) selectedProvider = ""
                                        } else {
                                            pendingProviderDelete = p.id
                                        }
                                    }
                                }

                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                )
                                val models = state.libraryModels.filter { it.provider == p.id }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "该供应商的模型（${models.size}）",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f),
                                    )
                                    SmallPill("+ 添加") { openModelEditor(null, p.id) }
                                }
                                if (models.isEmpty()) {
                                    Text(
                                        text = "（还没有模型）",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 4.dp),
                                    )
                                }
                                models.forEach { m ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = m.name.ifBlank { m.id },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                            )
                                            Text(
                                                text = m.id,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                            )
                                        }
                                        Text(
                                            text = "编辑",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Clay,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(10.dp))
                                                .noRippleClickable { openModelEditor(m, p.id) }
                                                .padding(horizontal = 6.dp, vertical = 4.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 探测结果面板（有结果或错误才出现）
                if (discovered.isNotEmpty() || discoverError != null) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                    // 内核回退到「当前生效模型」的密钥时必须说清楚 ——
                    // 否则用户看到的是「探测失败」，完全不知道是拿错了密钥。
                    if (discoverKeyFromActive) {
                        Text(
                            text = "⚠ 该供应商没有自己的密钥，这次探测用的是**当前生效模型**的密钥。" +
                                "请先给该供应商配置密钥，否则探测结果与该供应商的真实配置无关。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                        )
                    }
                    ModelDiscoverPanel(
                        vm = vm,
                        discovered = discovered,
                        providerId = discoverProvider,
                        addedIds = addedIds,
                        error = discoverError,
                        onClose = {
                            discovered = emptyList()
                            discoverError = null
                            discoverKeyFromActive = false
                        },
                    )
                }
            }

            // ---------------- 添加 / 编辑供应商 ----------------
            "providerEdit" -> {
                BackRow { sub = "providers" }
                Text(
                    text = if (fId.isEmpty()) "添加供应商" else "编辑供应商",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = fName,
                    onValueChange = { fName = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = fUrl,
                    onValueChange = { fUrl = it },
                    label = { Text("Base URL，如 https://api.deepseek.com/v1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Row(
                    modifier = Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf("openai", "anthropic").forEach { proto ->
                        ChoiceChip(proto, fProtocol == proto) { fProtocol = proto }
                    }
                }
                OutlinedTextField(
                    value = fKey,
                    onValueChange = { fKey = it; fKeyTouched = true },
                    label = { Text(if (fId.isEmpty()) "API Key" else "API Key（留空=不改）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Text(
                    text = "密钥走内核 key_touched 语义：没碰输入框就沿用旧值，空框 ≠ 清空。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(
                    modifier = Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SmallPill("保存", primary = true) {
                        vm.saveProvider(
                            id = fId.ifEmpty { providerIdOf(fUrl) },
                            name = fName.trim().ifEmpty { hostOf(fUrl) },
                            baseUrl = fUrl.trim(),
                            protocol = fProtocol,
                            keySource = "plain",
                            keyName = "",
                            keyValue = fKey,
                            keyTouched = fKeyTouched,
                        )
                        resetProviderForm()
                        sub = "providers"
                    }
                    SmallPill("取消") {
                        resetProviderForm()
                        sub = "providers"
                    }
                }
            }

            // ---------------- 添加 / 编辑模型 ----------------
            "modelEdit" -> {
                BackRow {
                    sub = "library"
                    editingModelId = null
                }
                // 编辑既有模型：从 /api/models/list 的完整条目回填全部规格。
                // 列表行（libraryModels）只有 id/name/provider，地址、上下文、
                // 三态能力、密钥来源都在 state.models 里，查不到才退回占位值。
                val spec = state.models.firstOrNull { it.id == editingModelId }
                val specProvider = state.providerDetails.firstOrNull {
                    it.id == (spec?.provider?.takeIf { p -> p.isNotBlank() } ?: editingModelProvider)
                }
                // 内核列表给的是「补齐供应商之后」的地址与协议（config.ResolveModel），
                // 只有与供应商本尊不一致时才说明这条模型自己覆盖过；一致就当继承，
                // 否则开关会永远显示「不继承」，一保存就把供应商地址钉进模型。
                val specInherits = spec == null || specProvider == null ||
                    (
                        spec.baseUrl.trim() == specProvider.baseUrl.trim() &&
                            spec.protocol.trim().equals(specProvider.protocol.trim(), ignoreCase = true)
                        )
                ModelEditorSection(
                    vm = vm,
                    providers = state.providerDetails.map { it.id to it.name.ifBlank { it.id } },
                    initial = ModelEditorInitial(
                        id = editingModelId.orEmpty(),
                        name = spec?.name?.ifBlank { editingModelName } ?: editingModelName,
                        providerId = spec?.provider?.takeIf { it.isNotBlank() } ?: editingModelProvider,
                        inherit = specInherits,
                        baseUrl = spec?.baseUrl.orEmpty(),
                        protocol = spec?.protocol.orEmpty(),
                        ctxIn = spec?.ctxIn ?: 0,
                        ctxOut = spec?.ctxOut ?: 0,
                        vision = spec?.vision,
                        video = spec?.video,
                        rpm = spec?.rpm ?: 0,
                        keySource = spec?.keySource.orEmpty(),
                        keyName = spec?.keyName.orEmpty(),
                        keySet = spec?.keySet ?: false,
                    ),
                    onSaved = { ok ->
                        if (ok) {
                            // 保存成功后模型表 + 供应商详情都重拉一次：新增模型会改动两边
                            vm.loadKernelExtras()
                            vm.refreshProviderDetails()
                            sub = "library"
                            editingModelId = null
                        }
                    },
                    onCancel = {
                        sub = "library"
                        editingModelId = null
                    },
                )
            }
        }
    }
}

/** 模型库/详情里的一行：名称、当前标记、设为当前 / 编辑 / 删除。 */
@Composable
private fun ModelRow(
    model: com.hxy.codeforge.model.LibraryModel,
    isCurrent: Boolean,
    pendingDelete: Boolean,
    onSetCurrent: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = model.name.ifBlank { model.id },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                if (isCurrent) {
                    Text(
                        text = "当前",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Clay)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            Text(
                text = model.id,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (!isCurrent) {
            Text(
                text = "设为当前",
                style = MaterialTheme.typography.labelSmall,
                color = Clay,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .noRippleClickable(onClick = onSetCurrent)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            )
        }
        Text(
            text = "编辑",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .noRippleClickable(onClick = onEdit)
                .padding(horizontal = 8.dp, vertical = 5.dp),
        )
        Text(
            text = if (pendingDelete) "确认删除" else "删除",
            style = MaterialTheme.typography.labelSmall,
            color = if (pendingDelete) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .noRippleClickable(onClick = onDelete)
                .padding(horizontal = 8.dp, vertical = 5.dp),
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

// ---------------------------------------------------------------- 归档

/**
 * 已归档会话管理页。
 *
 * 归档后的会话不再出现在侧栏；这里给恢复与永久删除两个出口。
 * 内核按「归档满 10 天」自动清理 —— 这是内核行为，壳侧只展示说明，不做定时任务。
 */
@Composable
private fun ArchiveSection(vm: ChatViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.loadArchived() }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "已归档会话（${state.archivedSessions.size}）",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        SmallPill("刷新") { vm.loadArchived() }
    }
    Text(
        text = "归档满 10 天由内核自动永久删除（内核行为，壳侧不做定时任务）。" +
            "这里的「永久删除」立即移除，不可恢复。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )
    if (state.archivedSessions.isEmpty()) {
        Text(
            text = "（没有已归档的会话）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
    state.archivedSessions.forEach { s ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = s.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                if (s.updatedAt > 0) {
                    Text(
                        text = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                            .format(java.util.Date(s.updatedAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = "恢复",
                style = MaterialTheme.typography.labelSmall,
                color = Clay,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .noRippleClickable {
                        vm.unarchive(s.id)
                        // 恢复后归档列表要自己重拉（内核不推帧，同 P-022）
                        vm.loadArchived()
                    }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            )
            Text(
                text = if (pendingDelete == s.id) "确认删除" else "永久删除",
                style = MaterialTheme.typography.labelSmall,
                color = if (pendingDelete == s.id) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .noRippleClickable {
                        if (pendingDelete == s.id) {
                            vm.deleteSession(s.id)
                            pendingDelete = null
                            // deleteSession 只刷新主列表，归档列表要补一次
                            vm.loadArchived()
                        } else {
                            pendingDelete = s.id
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

// ---------------------------------------------------------------- 运行环境

@Composable
private fun EnvSection(context: Context) {
    SectionTitle("兼容环境")
    LaunchedEffect(Unit) { EnvKit.refresh(context) }
    val envStatus = EnvKit.status.value
    val envText = when (envStatus) {
        is EnvKit.Status.Preparing -> "准备中 ${envStatus.percent}%"
        EnvKit.Status.Ready -> "已就绪"
        is EnvKit.Status.Failed -> envStatus.message
        EnvKit.Status.Idle -> "未就绪"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = envText,
            style = MaterialTheme.typography.bodySmall,
            color = if (envStatus is EnvKit.Status.Failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
        if (envStatus !is EnvKit.Status.Preparing) {
            Text(
                text = if (envStatus is EnvKit.Status.Ready) "重新准备" else "立即准备",
                style = MaterialTheme.typography.labelMedium,
                color = Clay,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .noRippleClickable {
                        // 重装走 IO 线程（reset 主线程删 3800+ 文件会 ANR）
                        EnvKit.reprepare(context)
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }

    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(vertical = 16.dp),
    )

    // 环境软件包（apk 安装语言运行时）
    EnvPackagesSection()
}

// ---------------------------------------------------------------- 语音

@Composable
private fun VoiceSection(context: Context) {
    SectionTitle("语音输入")

    val systemAvailable = com.hxy.codeforge.ui.common.zhSpeechAvailable(context)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(
                    if (systemAvailable) Clay else MaterialTheme.colorScheme.secondary,
                    CircleShape,
                ),
        )
        Text(
            text = if (systemAvailable) "系统语音服务：可用" else "系统语音服务：不可用",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 8.dp),
        )
    }

    Text(
        text = "没有系统服务的设备无法使用语音输入。可在下方启用云端语音识别（需自备 API Key），" +
            "由云端完成转写。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 10.dp),
    )

    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(vertical = 14.dp),
    )

    // ---------------- 云端识别（硅基流动） ----------------
    // 语音识别单独一套云 API 配置：不与聊天模型供应商混用 —— 两套 Key、两套计费，
    // 共用一份配置会让「换个聊天模型」顺手把语音链路改坏，故两边互不污染。
    SectionTitle("云端识别（硅基流动）")

    val cloudEnabled = AppSettings.sttCloudEnabled.value
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { AppSettings.setSttCloudEnabled(!cloudEnabled) }
            .padding(vertical = 10.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "启用云端语音识别",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "开启且填好 Key 后，语音优先走云端转写",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Switch(
            checked = cloudEnabled,
            onCheckedChange = { AppSettings.setSttCloudEnabled(it) },
            colors = SwitchDefaults.colors(checkedTrackColor = Clay),
        )
    }

    // 文本类：本地草稿 rememberSaveable 防重输入丢字，改动即写 AppSettings
    // （与外观页一致，不做「保存」按钮）
    var keyDraft by rememberSaveable { mutableStateOf(AppSettings.sttCloudApiKey.value) }
    OutlinedTextField(
        value = keyDraft,
        onValueChange = {
            keyDraft = it
            AppSettings.setSttCloudApiKey(it)
        },
        label = { Text("API Key") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    )
    Text(
        text = "仅存本机加密存储；音频会发送到上面的服务地址，由云端完成转写。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )

    var baseDraft by rememberSaveable { mutableStateOf(AppSettings.sttCloudBaseUrl.value) }
    OutlinedTextField(
        value = baseDraft,
        onValueChange = {
            baseDraft = it
            AppSettings.setSttCloudBaseUrl(it)
        },
        label = { Text("服务地址（默认硅基流动）") },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    )
    Text(
        text = "默认 ${AppSettings.STT_CLOUD_DEFAULT_BASE_URL}（OpenAI 兼容 /audio/transcriptions）。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )

    var modelDraft by rememberSaveable { mutableStateOf(AppSettings.sttCloudModel.value) }
    OutlinedTextField(
        value = modelDraft,
        onValueChange = {
            modelDraft = it
            AppSettings.setSttCloudModel(it)
        },
        label = { Text("模型（默认 ${AppSettings.STT_CLOUD_DEFAULT_MODEL}）") },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    )

    val cloudClean = AppSettings.sttCloudClean.value
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { AppSettings.setSttCloudClean(!cloudClean) }
            .padding(vertical = 10.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "过滤情感标记（🎼😼 等）",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "去掉 SenseVoice 插进正文的符号，只留文字",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Switch(
            checked = cloudClean,
            onCheckedChange = { AppSettings.setSttCloudClean(it) },
            colors = SwitchDefaults.colors(checkedTrackColor = Clay),
        )
    }

    Text(
        text = "优先级：云端（已启用且填了 Key）→ 系统语音服务。两者都不可用时无法语音输入。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )

    Spacer(Modifier.height(4.dp))
}

// ---------------------------------------------------------------- 诊断

@Composable
private fun DiagnosticsSection() {
    SectionTitle("诊断")
    DiagnosticsRow()
}

/**
 * 最小诊断区：崩溃现场（crash-*.txt）与环境流水线日志（env.log）。
 * 真机排障的第一手资料就在这几个文件里 —— 崩溃落盘是 2026-10-25 加的，
 * 这里给一个不依赖 adb 的查看/复制出口。
 */
@Composable
private fun DiagnosticsRow() {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }
    var exportResult by remember { mutableStateOf<String?>(null) }
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val scope = rememberCoroutineScope()

    fun latestLogs(): String {
        val dir = java.io.File(context.filesDir, "logs")
        val crashes = dir.listFiles { f -> f.name.startsWith("crash-") }
            ?.sortedByDescending { it.name }?.take(2)
            ?.map { "===== ${it.name} =====\n" + it.readText().take(2000) }
            ?.joinToString("\n\n").orEmpty()
        val env = java.io.File(dir, "env.log").takeIf { it.exists() }?.readText()?.takeLast(1500).orEmpty()
        return buildString {
            if (crashes.isNotBlank()) append("【崩溃记录】\n$crashes\n\n")
            if (env.isNotBlank()) append("【环境日志】\n$env\n\n")
            if (isEmpty()) append("暂无日志（无崩溃记录、环境未跑过流水线）")
        }
    }

    /**
     * 全量日志导出到系统下载文件夹：崩溃文件不截断，env/kernel 各留尾 100KB。
     * 29+ 走 MediaStore.Downloads（免权限）；26-28 直写公共目录（需存储权限，
     * 未授权给指引不弹窗 —— 老系统占比极低，不值得为此加一套权限流程）。
     * 读文件在 IO 线程（kernel.log 可能上 MB，主线程读必卡）。
     */
    fun exportLogs(): String {
        val dir = java.io.File(context.filesDir, "logs")
        val sb = StringBuilder()
        dir.listFiles { f -> f.name.startsWith("crash-") }
            ?.sortedByDescending { it.name }
            ?.forEach { sb.append("===== ${it.name} =====\n").append(it.readText()).append("\n\n") }
        java.io.File(dir, "env.log").takeIf { it.exists() }?.let {
            sb.append("===== env.log（尾 100KB）=====\n").append(it.readText().takeLast(100 * 1024)).append("\n\n")
        }
        java.io.File(dir, "kernel.log").takeIf { it.exists() }?.let {
            sb.append("===== kernel.log（尾 100KB）=====\n").append(it.readText().takeLast(100 * 1024)).append("\n\n")
        }
        if (sb.isEmpty()) return "没有可导出的日志"
        val name = "CodeForge-logs-" +
            java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date()) +
            ".txt"
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val cv = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                }
                val uri = context.contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    cv,
                ) ?: return "写入下载文件夹失败"
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(sb.toString().toByteArray())
                } ?: return "写入下载文件夹失败"
                "已导出：Download/$name"
            } else {
                if (androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    return "此系统版本导出需要存储权限，请在系统设置中授予后重试"
                }
                val d = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS,
                )
                d.mkdirs()
                java.io.File(d, name).writeText(sb.toString())
                "已导出：Download/$name"
            }
        } catch (e: Exception) {
            "导出失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * 把日志写成文件、挂到系统分享面板。
     *
     * 与「导出」的区别：导出是落到 Download 让用户自己找，发送是直接拉系统分享
     * （微信 / 邮件 / 网盘都在里面）。日志先写 cache/logs/，经 FileProvider 授权读取，
     * 分享结束后系统回收 cache，不占用户空间。
     */
    fun shareLogs() {
        val text = StringBuilder()
        val dir = java.io.File(context.filesDir, "logs")
        dir.listFiles { f -> f.name.startsWith("crash-") }
            ?.sortedByDescending { it.name }
            ?.forEach { text.append("===== ${it.name} =====\n").append(it.readText()).append("\n\n") }
        listOf("env.log", "kernel.log").forEach { n ->
            java.io.File(dir, n).takeIf { it.exists() }?.let {
                text.append("===== $n（尾 100KB）=====\n")
                    .append(it.readText().takeLast(100 * 1024)).append("\n\n")
            }
        }
        if (text.isEmpty()) {
            exportResult = "没有可发送的日志"
            return
        }
        val name = "CodeForge-logs-" +
            java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date()) + ".txt"
        runCatching {
            val cacheDir = java.io.File(context.cacheDir, "logs").apply { mkdirs() }
            val f = java.io.File(cacheDir, name)
            f.writeText(text.toString())
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                f,
            )
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "CodeForge 诊断日志")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                android.content.Intent.createChooser(send, "发送日志"),
            )
        }.onFailure {
            exportResult = "发送失败：${it.message ?: it.javaClass.simpleName}"
        }
    }

    // 导出结果短暂展示后自清
    LaunchedEffect(exportResult) {
        if (exportResult != null) {
            kotlinx.coroutines.delay(3_200)
            exportResult = null
        }
    }

    Column(modifier = Modifier.padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "崩溃与环境日志",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "导出",
                style = MaterialTheme.typography.labelMedium,
                color = Clay,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .noRippleClickable {
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            val r = exportLogs()
                            exportResult = r
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Text(
                text = "发送",
                style = MaterialTheme.typography.labelMedium,
                color = Clay,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .noRippleClickable {
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) { shareLogs() }
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Text(
                text = if (expanded) "收起" else "查看",
                style = MaterialTheme.typography.labelMedium,
                color = Clay,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .noRippleClickable {
                        expanded = !expanded
                        if (expanded) logText = latestLogs()
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            if (expanded) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "复制",
                    style = MaterialTheme.typography.labelMedium,
                    color = Clay,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .noRippleClickable {
                            scope.launch {
                                val data = android.content.ClipData.newPlainText("logs", logText)
                                clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(data))
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        if (exportResult != null) {
            Text(
                text = exportResult.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = Clay,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (expanded) {
            Text(
                text = logText,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                    .padding(10.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- 通用子件

/** 子页里的小节标题（本文件内若干区块共用；私有不外泄，避免与其他并行文件同名冲突）。 */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
    )
}

/** 胶囊选择片：选中填陶土橘，未选中暖灰。 */
@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Clay else MaterialTheme.colorScheme.surfaceVariant)
            .noRippleClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** 行内动作胶囊；primary = 陶土橘实心，danger = 红字。 */
@Composable
private fun SmallPill(
    label: String,
    primary: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val bg = if (primary) Clay else MaterialTheme.colorScheme.surfaceVariant
    val fg = when {
        primary -> MaterialTheme.colorScheme.onPrimary
        danger -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .noRippleClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

/** 子页顶部的返回行。 */
@Composable
private fun BackRow(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .noRippleClickable(onClick = onBack)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(Glyph.Back, 16.dp, Clay, strokeWidth = 2.4.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            text = "返回",
            style = MaterialTheme.typography.bodyMedium,
            color = Clay,
        )
    }
}

/** 主列表里的子页入口：标题 + 右侧计数 + 箭头。 */
@Composable
private fun SubPageEntry(title: String, count: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .noRippleClickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (count.isNotEmpty()) {
            Text(
                text = count,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "  ›",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 从 URL 里取 host 作显示名。 */
private fun hostOf(url: String): String =
    url.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').ifEmpty { "新供应商" }

/** 按内核既有命名习惯生成 id：p-api-deepseek-com。 */
private fun providerIdOf(url: String): String {
    val host = hostOf(url).lowercase().replace('.', '-').replace(Regex("[^a-z0-9-]"), "")
    return if (host.isEmpty()) "p-custom" else "p-$host"
}
