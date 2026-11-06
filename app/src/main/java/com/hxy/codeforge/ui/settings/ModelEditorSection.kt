package com.hxy.codeforge.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.hxy.codeforge.ui.ChatViewModel
import com.hxy.codeforge.ui.common.noRippleClickable
import com.hxy.codeforge.ui.theme.Clay

/**
 * 模型编辑表单一句话说明：把「一条模型」的全部可写字段收在一屏里。
 *
 * 字段对齐内核 `ModelInfo`：`id` / `name` / `provider_id` / `ctx_in` / `ctx_out` /
 * `vision` / `video` / `rpm` / `base_url` / `protocol` / `key_source` / `key_name`，
 * 外加只读的 `key_set`（库里是否已有密钥）。编辑既有模型时由调用方从
 * `state.models` 取全量字段回填 [ModelEditorInitial]。
 *
 * 两个必须写清的语义：
 * 1. `vision` / `video` 是**三态**：未声明（None）≠ 不支持（false）。内核把
 *    「未声明」当乐观发送、撞到上游拒收再记忆；`false` 则在发送前直接拦。
 *    界面必须能表达中间态，不能用一个 Switch 了事。
 * 2. 密钥：`inherit_key=true` 时整条模型复用供应商的地址与密钥，此时
 *    key_source/key_name/key_value 一律留空不提交；关掉继承才把本条模型自己的
 *    密钥写进去。输入框的「显示/隐藏」只影响本地渲染，不改变提交值。
 */
@Composable
fun ModelEditorSection(
    vm: ChatViewModel,
    providers: List<Pair<String, String>>,
    initial: ModelEditorInitial = ModelEditorInitial(),
    onSaved: (Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    // 编辑既有模型时 id 不能改：内核按 id 判重，改 id 等于新建，会把规格挂到另一条上
    val editing = initial.id.isNotBlank()

    var providerId by remember(initial.id, initial.providerId) {
        mutableStateOf(initial.providerId.ifBlank { providers.firstOrNull()?.first.orEmpty() })
    }
    var modelId by remember(initial.id) { mutableStateOf(initial.id) }
    var displayName by remember(initial.id, initial.name) { mutableStateOf(initial.name) }
    // 编辑时沿调用方给的判定：内核 /api/models/list 返回的是**补齐供应商之后**的
    // 地址与协议（见 ResolveModel），所以光看「地址非空」无法分辨是否在继承 ——
    // 由调用方比对供应商的值后在 [ModelEditorInitial.inherit] 里说明。
    var inherit by remember(initial.id) { mutableStateOf(!editing || initial.inherit) }
    var baseUrl by remember(initial.id) { mutableStateOf(initial.baseUrl) }
    var protocol by remember(initial.id) { mutableStateOf(initial.protocol.ifBlank { "openai" }) }
    var keySource by remember(initial.id) { mutableStateOf(initial.keySource.ifBlank { "plain" }) }
    var keyName by remember(initial.id) { mutableStateOf(initial.keyName) }
    var keyValue by remember(initial.id) { mutableStateOf("") }
    var keyVisible by remember(initial.id) { mutableStateOf(false) }
    var ctxIn by remember(initial.id) {
        mutableStateOf(initial.ctxIn.takeIf { it > 0 }?.toString().orEmpty())
    }
    var ctxOut by remember(initial.id) {
        mutableStateOf(initial.ctxOut.takeIf { it > 0 }?.toString().orEmpty())
    }
    var vision by remember(initial.id) { mutableStateOf(triStateOf(initial.vision)) }
    var video by remember(initial.id) { mutableStateOf(triStateOf(initial.video)) }
    var rpm by remember(initial.id) {
        mutableStateOf(initial.rpm.takeIf { it > 0 }?.toString().orEmpty())
    }
    var formError by remember(initial.id) { mutableStateOf<String?>(null) }
    var saveError by remember(initial.id) { mutableStateOf<String?>(null) }
    var saving by remember(initial.id) { mutableStateOf(false) }
    var testResult by remember(initial.id) { mutableStateOf<String?>(null) }
    var testError by remember(initial.id) { mutableStateOf<String?>(null) }
    var testing by remember(initial.id) { mutableStateOf(false) }

    fun resetForm() {
        modelId = ""; displayName = ""; inherit = true; baseUrl = ""; protocol = "openai"
        keySource = "plain"; keyName = ""; keyValue = ""; keyVisible = false
        ctxIn = ""; ctxOut = ""; vision = -1; video = -1; rpm = ""
        formError = null; testResult = null; testError = null
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (editing) "编辑模型" else "添加模型",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 6.dp),
        )

        // ---------------- 归属供应商 ----------------
        EditorLabel("归属供应商")
        if (providers.isEmpty()) {
            Text(
                text = "还没有供应商 —— 先到「供应商」里加一家，模型要继承它的地址与密钥。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                providers.forEach { (id, name) ->
                    val sel = providerId == id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (sel) Clay.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant,
                            )
                            .noRippleClickable { providerId = id }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = name.ifBlank { id },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = id,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )

        // ---------------- 显示名 / 模型 id ----------------
        OutlinedTextField(
            value = modelId,
            onValueChange = { if (!editing) modelId = it.trim() },
            label = { Text("模型 id（上游真实 model 名，如 deepseek-chat）") },
            singleLine = true,
            enabled = !editing,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text("显示名（可选，空则与 id 相同）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        // ---------------- 继承地址 / 密钥 ----------------
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "继承供应商的地址与密钥",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "关闭后可给这条模型单独指定上游地址、协议与密钥",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = inherit,
                onCheckedChange = { inherit = it },
                colors = SwitchDefaults.colors(checkedTrackColor = Clay),
            )
        }
        if (!inherit) {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL（如 https://api.deepseek.com/v1）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("openai" to "openai 兼容", "anthropic" to "anthropic").forEach { (id, label) ->
                    EditorChip(label, protocol == id) { protocol = id }
                }
            }
        }

        // ---------------- 密钥 ----------------
        // 继承供应商时这三项一律不提交（内核按 inherit_key 走供应商那份）；
        // 关掉继承才把本条模型自己的密钥写进去。
        EditorLabel("密钥来源")
        Row(
            modifier = Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("plain" to "明文密钥", "env" to "环境变量").forEach { (id, label) ->
                EditorChip(label, keySource == id) { keySource = id }
            }
        }
        if (keySource == "env") {
            OutlinedTextField(
                value = keyName,
                onValueChange = { keyName = it },
                label = { Text("环境变量名（如 DEEPSEEK_API_KEY）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        } else {
            OutlinedTextField(
                value = keyValue,
                onValueChange = { keyValue = it },
                label = {
                    Text(
                        if (editing && initial.keySet) {
                            "API Key（已配置，留空=不改）"
                        } else {
                            "API Key"
                        },
                    )
                },
                singleLine = true,
                visualTransformation = if (keyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    Text(
                        text = if (keyVisible) "隐藏" else "显示",
                        style = MaterialTheme.typography.labelMedium,
                        color = Clay,
                        modifier = Modifier
                            .noRippleClickable { keyVisible = !keyVisible }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Text(
                text = when {
                    inherit -> "当前整条继承供应商的地址与密钥 —— 这里填的值不会提交，关掉上面的开关才保存到本模型。"
                    initial.keySet -> "库里已有密钥：留空沿用旧值，填写则覆盖。"
                    else -> "密钥只写进应用私有目录里的内核配置，不会上传。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )

        // ---------------- 上下文 ----------------
        EditorLabel("上下文（tokens）")
        EditorLabel("输入上限", small = true)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CTX_IN_PRESETS.forEach { (v, label) ->
                EditorChip(label, ctxIn == v.toString()) { ctxIn = v.toString() }
            }
        }
        OutlinedTextField(
            value = ctxIn,
            onValueChange = { ctxIn = it.filter { c -> c.isDigit() }.take(8) },
            label = { Text("自定义输入上限（0 = 未声明）") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        EditorLabel("输出上限", small = true)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CTX_OUT_PRESETS.forEach { (v, label) ->
                EditorChip(label, ctxOut == v.toString()) { ctxOut = v.toString() }
            }
        }
        OutlinedTextField(
            value = ctxOut,
            onValueChange = { ctxOut = it.filter { c -> c.isDigit() }.take(8) },
            label = { Text("自定义输出上限（0 = 未声明）") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )

        // ---------------- 图片 / 视频三态 ----------------
        EditorLabel("图片能力")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(-1 to "未声明", 1 to "支持", 0 to "不支持").forEach { (v, label) ->
                EditorChip(label, vision == v) { vision = v }
            }
        }
        EditorLabel("视频能力")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(-1 to "未声明", 1 to "支持", 0 to "不支持").forEach { (v, label) ->
                EditorChip(label, video == v) { video = v }
            }
        }
        Text(
            text = "「未声明」= 乐观发送、被上游拒收后再记住；「不支持」= 发送前直接拦。两者不能混。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        // ---------------- RPM ----------------
        EditorLabel("每分钟请求上限（可选）")
        OutlinedTextField(
            value = rpm,
            onValueChange = { rpm = it.filter { c -> c.isDigit() }.take(5) },
            label = { Text("RPM（0 / 空 = 不限）") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        // ---------------- 结果区 ----------------
        if (formError != null || saveError != null) {
            Text(
                text = (saveError ?: formError).orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f))
                    .padding(8.dp),
            )
        }
        testResult?.let {
            Text(
                text = "连接测试：$it",
                style = MaterialTheme.typography.bodySmall,
                color = Clay,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        testError?.let {
            Text(
                text = "连接测试失败：$it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        // ---------------- 动作 ----------------
        Row(
            modifier = Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EditorPill(
                label = when {
                    saving -> "保存中…"
                    editing -> "保存修改"
                    else -> "保存"
                },
                primary = true,
            ) {
                if (saving) return@EditorPill
                val id = modelId.trim()
                if (id.isEmpty()) {
                    formError = "模型 id 不能为空"
                    return@EditorPill
                }
                val visionArg = when (vision) {
                    1 -> true
                    0 -> false
                    else -> null
                }
                val videoArg = when (video) {
                    1 -> true
                    0 -> false
                    else -> null
                }
                formError = null
                saveError = null
                saving = true
                vm.saveModelFull(
                    id = id,
                    name = displayName.trim().ifBlank { id },
                    providerId = providerId,
                    ctxIn = ctxIn.toIntOrNull() ?: 0,
                    ctxOut = ctxOut.toIntOrNull() ?: 0,
                    vision = visionArg,
                    video = videoArg,
                    rpm = rpm.toIntOrNull() ?: 0,
                    baseUrl = if (inherit) "" else baseUrl.trim(),
                    protocol = if (inherit) "" else protocol,
                    // key_source 永远给一个合法值（内核按 inherit_key 决定用不用）；
                    // 继承态下 key_name/key_value 一律留空，避免把供应商那份覆盖掉
                    keySource = if (keySource == "env") "env" else "plain",
                    keyName = if (!inherit && keySource == "env") keyName.trim() else "",
                    keyValue = if (!inherit && keySource == "plain") keyValue.trim() else "",
                    inheritKey = inherit,
                ) { ok, message ->
                    saving = false
                    // 失败不跳表单：让用户在表单内看到原因、改完再存
                    if (!ok) saveError = message?.ifBlank { "模型保存失败" } ?: "模型保存失败"
                    onSaved(ok)
                }
            }
            EditorPill(label = "重置", primary = false) { resetForm() }
            if (editing) {
                EditorPill(
                    label = if (testing) "测试中…" else "测试连接",
                    primary = false,
                ) {
                    if (testing) return@EditorPill
                    testing = true
                    testResult = null
                    testError = null
                    vm.testModel(initial.id) { ok, err ->
                        testing = false
                        testResult = ok
                        testError = err
                    }
                }
            } else {
                Text(
                    text = "先保存再测试",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(modifier = Modifier.padding(top = 8.dp)) {
            EditorPill(label = "取消", primary = false) { onCancel() }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * 编辑器的初始值：内核 `ModelInfo` 的可写字段，外加只读的 `key_set`。
 *
 * 新增模型时全用默认值（[id] 为空 = 新建）；编辑时由调用方从
 * `ChatUiState.models` 查同名条目回填，查不到才退回列表页给的 id/name/provider。
 */
data class ModelEditorInitial(
    val id: String = "",
    val name: String = "",
    val providerId: String = "",
    /** 是否整条继承供应商的连接信息与密钥（新增模型恒为 true）。 */
    val inherit: Boolean = true,
    val baseUrl: String = "",
    val protocol: String = "",
    val ctxIn: Int = 0,
    val ctxOut: Int = 0,
    val vision: Boolean? = null,
    val video: Boolean? = null,
    val rpm: Int = 0,
    val keySource: String = "plain",
    val keyName: String = "",
    val keySet: Boolean = false,
)

/** Boolean? 三态 → 界面内部编码：1 支持 / 0 不支持 / -1 未声明。 */
private fun triStateOf(v: Boolean?): Int = when (v) {
    true -> 1
    false -> 0
    null -> -1
}

/**
 * 上游模型发现面板：筛选、已添加标记、批量添加、错误结果。
 *
 * 数据由调用方持有（`discovered` 来自 `/api/models/discover`），本组件只负责
 * 呈现与提交 —— 探测是一次性动作，不值得进 [com.hxy.codeforge.ui.ChatUiState]。
 */
@Composable
fun ModelDiscoverPanel(
    vm: ChatViewModel,
    discovered: List<String>,
    providerId: String,
    addedIds: Set<String>,
    error: String?,
    onClose: () -> Unit,
) {
    var filter by remember(discovered) { mutableStateOf("") }
    var notice by remember(discovered) { mutableStateOf<String?>(null) }
    val shown = discovered.filter { filter.isBlank() || it.contains(filter, ignoreCase = true) }
    val missing = discovered.filterNot { it in addedIds }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "获取模型列表",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "关闭",
                style = MaterialTheme.typography.labelMedium,
                color = Clay,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .noRippleClickable { onClose() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        Text(
            text = "共 ${discovered.size} 个，已添加 ${discovered.size - missing.size} 个" +
                (notice?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
        )
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f))
                    .padding(8.dp),
            )
        }
        if (discovered.isNotEmpty()) {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                label = { Text("筛选（按 id 包含匹配）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Column(modifier = Modifier.padding(top = 6.dp)) {
                shown.forEach { id ->
                    val added = id in addedIds
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = id,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (added) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = if (added) "已添加" else "添加",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (added) MaterialTheme.colorScheme.onSurfaceVariant else Clay,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .noRippleClickable(enabled = !added) {
                                    vm.saveModel(id, id, providerId)
                                    notice = "已提交 $id"
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (shown.isEmpty()) {
                    Text(
                        text = "没有匹配的模型",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
            }
            Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EditorPill(label = "批量添加（${missing.size}）", primary = true) {
                    if (missing.isEmpty()) {
                        notice = "全部已在库中"
                    } else {
                        missing.forEach { id -> vm.saveModel(id, id, providerId) }
                        notice = "已提交 ${missing.size} 个，稍后会自动刷新"
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ---------------------------------------------------------------- 子件

private val CTX_IN_PRESETS = listOf(
    128_000 to "128K",
    200_000 to "200K",
    256_000 to "256K",
    400_000 to "400K",
    1_000_000 to "1M",
)

private val CTX_OUT_PRESETS = listOf(
    32_000 to "32K",
    64_000 to "64K",
    128_000 to "128K",
    256_000 to "256K",
    512_000 to "512K",
)

@Composable
private fun EditorLabel(text: String, small: Boolean = false) {
    Text(
        text = text,
        style = if (small) {
            MaterialTheme.typography.labelMedium
        } else {
            MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
        },
        color = if (small) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 6.dp, bottom = 5.dp),
    )
}

@Composable
private fun EditorChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Clay else MaterialTheme.colorScheme.surfaceVariant)
            .noRippleClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun EditorPill(label: String, primary: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (primary) Clay else MaterialTheme.colorScheme.surfaceVariant)
            .noRippleClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
