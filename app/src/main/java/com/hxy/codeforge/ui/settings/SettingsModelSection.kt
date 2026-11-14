package com.hxy.codeforge.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hxy.codeforge.core.EnvKit
import com.hxy.codeforge.ui.ChatViewModel
import com.hxy.codeforge.ui.applyModel
import com.hxy.codeforge.ui.common.Glyph
import com.hxy.codeforge.ui.common.GlyphIcon
import com.hxy.codeforge.ui.common.SyncDialogStatusBar
import com.hxy.codeforge.ui.common.isLandscape
import com.hxy.codeforge.ui.common.noRippleClickable
import com.hxy.codeforge.ui.deleteModel
import com.hxy.codeforge.ui.deleteProvider
import com.hxy.codeforge.ui.deleteSession
import com.hxy.codeforge.ui.discoverModels
import com.hxy.codeforge.ui.loadArchived
import com.hxy.codeforge.ui.loadKernelExtras
import com.hxy.codeforge.ui.refreshProviderDetails
import com.hxy.codeforge.ui.saveModel
import com.hxy.codeforge.ui.saveProvider
import com.hxy.codeforge.ui.theme.Clay
import com.hxy.codeforge.ui.unarchive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt


internal fun modelSubRank(sub: String?): Int = when (sub) {
    null -> 0
    "library", "providers" -> 1
    else -> 2
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
internal fun ModelSection(
    vm: ChatViewModel,
    sub: String?,
    returnSub: String?,
    onNavigateSub: (next: String?, returnTo: String?) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()

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
        onNavigateSub("modelEdit", sub)
    }

    val providerNames = state.providerDetails.associate { it.id to it.name.ifBlank { it.id } }
    val addedIds = state.libraryModels.map { it.id }.toSet()

    AnimatedContent(
        targetState = sub,
        transitionSpec = {
            horizontalSlide(modelSubRank(targetState) >= modelSubRank(initialState))
        },
        label = "model-sub",
        modifier = Modifier.fillMaxWidth(),
    ) { currentSub ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            when (currentSub) {
            // ---------------- 主入口 ----------------
            null -> {
                SectionTitle("供应商与模型")
                SubPageEntry("模型库", "${state.libraryModels.size} 个") { onNavigateSub("library", null) }
                SubPageEntry("供应商", "${state.providerDetails.size} 个") { onNavigateSub("providers", null) }
                SubPageEntry("添加模型", "") { openModelEditor(null, "") }
                SubPageEntry("添加供应商", "") {
                    resetProviderForm()
                    onNavigateSub("providerEdit", sub)
                }
            }

            // ---------------- 模型库（按供应商分组） ----------------
            "library" -> {
                BackRow { onNavigateSub(null, null) }
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
                BackRow { onNavigateSub(null, null) }
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
                                        onNavigateSub("providerEdit", sub)
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
                BackRow { onNavigateSub(returnSub, null) }
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
                        onNavigateSub(returnSub ?: "providers", null)
                    }
                    SmallPill("取消") {
                        resetProviderForm()
                        onNavigateSub(returnSub, null)
                    }
                }
            }

            // ---------------- 添加 / 编辑模型 ----------------
            "modelEdit" -> {
                BackRow {
                    onNavigateSub(returnSub, null)
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
                            onNavigateSub(returnSub ?: "library", null)
                            editingModelId = null
                        }
                    },
                    onCancel = {
                        onNavigateSub(returnSub, null)
                        editingModelId = null
                    },
                )
            }
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


/** 从 URL 里取 host 作显示名。 */
private fun hostOf(url: String): String =
    url.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').ifEmpty { "新供应商" }

/** 按内核既有命名习惯生成 id：p-api-deepseek-com。 */
private fun providerIdOf(url: String): String {
    val host = hostOf(url).lowercase().replace('.', '-').replace(Regex("[^a-z0-9-]"), "")
    return if (host.isEmpty()) "p-custom" else "p-$host"
}
