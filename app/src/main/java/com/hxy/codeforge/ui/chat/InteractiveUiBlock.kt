package com.hxy.codeforge.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hxy.codeforge.ui.theme.Clay
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

// ---------------------------------------------------------------- 数据模型与纯解析

data class InteractiveUiSpec(
    val title: String = "",
    val subtitle: String = "",
    val blocks: List<UiBlock>,
)

sealed interface UiBlock {
    data class Choices(
        val id: String,
        val prompt: String,
        val mode: String, // "single" | "multi" | "quiz"
        val autoSend: Boolean,
        val confirmLabel: String,
        val sendTemplate: String,
        val options: List<UiChoiceOption>,
    ) : UiBlock

    data class Actions(
        val items: List<UiActionItem>,
    ) : UiBlock

    data class Tabs(
        val items: List<UiTabItem>,
    ) : UiBlock

    data class Form(
        val id: String,
        val submitLabel: String,
        val submitAction: String, // "send" | "prefill"
        val fields: List<UiFormField>,
    ) : UiBlock
}

data class UiChoiceOption(
    val id: String,
    val label: String,
    val desc: String = "",
    val value: String = label,
    val correct: Boolean? = null,
    val explain: String = "",
)

data class UiActionItem(
    val label: String,
    val action: String, // "send" | "prefill"
    val payload: String,
    val style: String = "secondary", // "primary" | "secondary" | "outline"
)

data class UiTabItem(
    val label: String,
    val title: String = "",
    val body: String,
    val pros: List<String> = emptyList(),
    val cons: List<String> = emptyList(),
    val selectPayload: String = "",
    val selectLabel: String = "采用此方案",
)

sealed interface UiFormField {
    val id: String
    val label: String

    data class Toggle(
        override val id: String,
        override val label: String,
        val default: Boolean = false,
    ) : UiFormField

    data class Text(
        override val id: String,
        override val label: String,
        val placeholder: String = "",
        val default: String = "",
    ) : UiFormField

    data class NumberSlider(
        override val id: String,
        override val label: String,
        val min: Float,
        val max: Float,
        val step: Float = 1f,
        val default: Float = min,
        val unit: String = "",
    ) : UiFormField

    data class Select(
        override val id: String,
        override val label: String,
        val options: List<String>,
        val default: String = options.firstOrNull().orEmpty(),
    ) : UiFormField
}

/**
 * 解析 ` ```ui ` / ` ```widget ` 围栏 JSON 为 [InteractiveUiSpec]（纯 JVM 可测）。
 *
 * 容错：既支持 `{title, blocks: [...]} ` 组合结构，也支持把单个块直接写在顶层
 * （如 `{kind: "choices", options: [...]}`），避免模型少包一层就降级成源码。
 * 解析失败或没有任何有效块时返回 null，由调用方回落到代码块展示。
 */
internal fun parseInteractiveUiSpec(raw: String): InteractiveUiSpec? {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("{")) return null
    val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
    val blocksArr = root.optJSONArray("blocks")
    val blocks = if (blocksArr != null) {
        (0 until blocksArr.length()).mapNotNull { i ->
            blocksArr.optJSONObject(i)?.let(::parseUiBlock)
        }
    } else {
        listOfNotNull(parseUiBlock(root))
    }
    if (blocks.isEmpty()) return null
    return InteractiveUiSpec(
        title = root.optString("title").trim(),
        subtitle = root.optString("subtitle").ifBlank { root.optString("hint") }.trim(),
        blocks = blocks,
    )
}

private fun parseUiBlock(o: JSONObject): UiBlock? {
    val kind = o.optString("kind").ifBlank { o.optString("type") }.lowercase().trim()
    return when (kind) {
        "choices", "choice", "quiz", "options" -> {
            val arr = o.optJSONArray("options") ?: o.optJSONArray("items") ?: return null
            val opts = (0 until arr.length()).mapNotNull { i ->
                val item = arr.opt(i) ?: return@mapNotNull null
                when (item) {
                    is String -> item.trim().takeIf { it.isNotEmpty() }?.let {
                        UiChoiceOption(id = "opt_$i", label = it, value = it)
                    }
                    is JSONObject -> {
                        val label = item.optString("label").ifBlank { item.optString("text") }.trim()
                        if (label.isEmpty()) return@mapNotNull null
                        val correct = if (item.has("correct") && !item.isNull("correct")) {
                            item.optBoolean("correct")
                        } else {
                            null
                        }
                        UiChoiceOption(
                            id = item.optString("id").ifBlank { "opt_$i" },
                            label = label,
                            desc = item.optString("desc").ifBlank { item.optString("description") }.trim(),
                            value = item.optString("value").ifBlank { label }.trim(),
                            correct = correct,
                            explain = item.optString("explain").ifBlank { item.optString("explanation") }.trim(),
                        )
                    }
                    else -> null
                }
            }
            if (opts.isEmpty()) return null
            val rawMode = o.optString("mode").lowercase().trim()
            val mode = when {
                kind == "quiz" || rawMode == "quiz" -> "quiz"
                rawMode == "multi" || rawMode == "multiple" -> "multi"
                else -> "single"
            }
            UiBlock.Choices(
                id = o.optString("id").ifBlank { "choices" },
                prompt = o.optString("prompt").ifBlank { o.optString("question") }.trim(),
                mode = mode,
                autoSend = o.optBoolean("auto_send", mode == "single" && !o.has("confirm_label")),
                confirmLabel = o.optString("confirm_label").ifBlank { "确认发送" }.trim(),
                sendTemplate = o.optString("send_template").trim(),
                options = opts,
            )
        }

        "actions", "buttons" -> {
            val arr = o.optJSONArray("items") ?: o.optJSONArray("actions") ?: return null
            val items = (0 until arr.length()).mapNotNull { i ->
                val item = arr.optJSONObject(i) ?: return@mapNotNull null
                val label = item.optString("label").ifBlank { item.optString("text") }.trim()
                val payload = item.optString("payload").ifBlank { item.optString("value") }.ifBlank { label }.trim()
                if (label.isEmpty() || payload.isEmpty()) return@mapNotNull null
                val action = item.optString("action").lowercase().trim()
                    .let { if (it == "prefill") "prefill" else "send" }
                val style = item.optString("style").lowercase().trim()
                    .let { if (it in setOf("primary", "secondary", "outline")) it else "secondary" }
                UiActionItem(label = label, action = action, payload = payload, style = style)
            }
            if (items.isEmpty()) null else UiBlock.Actions(items)
        }

        "tabs", "compare" -> {
            val arr = o.optJSONArray("items") ?: o.optJSONArray("tabs") ?: return null
            val items = (0 until arr.length()).mapNotNull { i ->
                val item = arr.optJSONObject(i) ?: return@mapNotNull null
                val label = item.optString("label").ifBlank { item.optString("name") }.trim()
                val body = item.optString("body").ifBlank { item.optString("summary") }.trim()
                if (label.isEmpty()) return@mapNotNull null
                UiTabItem(
                    label = label,
                    title = item.optString("title").trim(),
                    body = body,
                    pros = jsonStrings(item.optJSONArray("pros")),
                    cons = jsonStrings(item.optJSONArray("cons")),
                    selectPayload = item.optString("select_payload").ifBlank { item.optString("payload") }.trim(),
                    selectLabel = item.optString("select_label").ifBlank { "采用此方案" }.trim(),
                )
            }
            if (items.isEmpty()) null else UiBlock.Tabs(items)
        }

        "form" -> {
            val arr = o.optJSONArray("fields") ?: return null
            val fields = (0 until arr.length()).mapNotNull { i ->
                val f = arr.optJSONObject(i) ?: return@mapNotNull null
                val fType = f.optString("type").ifBlank { f.optString("kind") }.lowercase().trim()
                val fLabel = f.optString("label").trim()
                val fId = f.optString("id").ifBlank { fLabel.ifBlank { "field_$i" } }
                if (fLabel.isEmpty()) return@mapNotNull null
                when (fType) {
                    "toggle", "switch", "boolean" -> UiFormField.Toggle(
                        id = fId,
                        label = fLabel,
                        default = f.optBoolean("default", false),
                    )
                    "slider", "number", "range" -> {
                        val min = f.optDouble("min", 0.0).toFloat()
                        val max = f.optDouble("max", 100.0).toFloat().coerceAtLeast(min + 1f)
                        val step = f.optDouble("step", 1.0).toFloat().coerceAtLeast(0.1f)
                        val def = f.optDouble("default", min.toDouble()).toFloat().coerceIn(min, max)
                        UiFormField.NumberSlider(
                            id = fId,
                            label = fLabel,
                            min = min,
                            max = max,
                            step = step,
                            default = def,
                            unit = f.optString("unit").trim(),
                        )
                    }
                    "select", "enum", "chips" -> {
                        val opts = jsonStrings(f.optJSONArray("options"))
                        if (opts.isEmpty()) return@mapNotNull null
                        val def = f.optString("default").trim().takeIf { it in opts } ?: opts.first()
                        UiFormField.Select(id = fId, label = fLabel, options = opts, default = def)
                    }
                    else -> UiFormField.Text(
                        id = fId,
                        label = fLabel,
                        placeholder = f.optString("placeholder").trim(),
                        default = f.optString("default").trim(),
                    )
                }
            }
            if (fields.isEmpty()) return null
            val submitAction = o.optString("submit_action").lowercase().trim()
                .let { if (it == "prefill") "prefill" else "send" }
            UiBlock.Form(
                id = o.optString("id").ifBlank { "form" },
                submitLabel = o.optString("submit_label").ifBlank { "提交" }.trim(),
                submitAction = submitAction,
                fields = fields,
            )
        }

        else -> null
    }
}

private fun jsonStrings(arr: JSONArray?): List<String> {
    if (arr == null) return emptyList()
    return buildList {
        for (i in 0 until arr.length()) {
            val s = arr.optString(i).trim()
            if (s.isNotEmpty()) add(s)
        }
    }
}

// ---------------------------------------------------------------- Compose 渲染

private val cardShape = RoundedCornerShape(14.dp)
private val innerShape = RoundedCornerShape(10.dp)

@Composable
internal fun InteractiveUiLoadingCard(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .border(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), cardShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(Clay),
        )
        Text(
            text = "正在生成交互卡片…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun InteractiveUiCard(
    spec: InteractiveUiSpec,
    cardKey: String,
    modifier: Modifier = Modifier,
) {
    val actions = LocalChatUiActions.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .border(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f), cardShape)
            .background(MaterialTheme.colorScheme.surface)
            .animateContentSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (spec.title.isNotEmpty() || spec.subtitle.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (spec.title.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Clay),
                        )
                        Text(
                            text = spec.title,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                if (spec.subtitle.isNotEmpty()) {
                    Text(
                        text = spec.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        spec.blocks.forEachIndexed { idx, block ->
            val blockKey = "$cardKey:$idx"
            when (block) {
                is UiBlock.Choices -> ChoicesBlockView(block, blockKey, actions)
                is UiBlock.Actions -> ActionsBlockView(block, actions)
                is UiBlock.Tabs -> TabsBlockView(block, blockKey, actions)
                is UiBlock.Form -> FormBlockView(block, blockKey, actions)
            }
        }
    }
}

@Composable
private fun ChoicesBlockView(
    block: UiBlock.Choices,
    blockKey: String,
    actions: ChatUiActions?,
) {
    val selected = remember(blockKey) {
        actions?.getState(blockKey) { mutableStateListOf<String>() } ?: mutableStateListOf()
    }
    var submitted by remember(blockKey) { mutableStateOf(false) }

    fun formatReply(ids: List<String>): String {
        val values = block.options.filter { it.id in ids }.map { it.value }
        val joined = values.joinToString("、")
        return when {
            block.sendTemplate.contains("{value}") -> block.sendTemplate.replace("{value}", joined)
            block.prompt.isNotEmpty() -> "${block.prompt}：$joined"
            else -> joined
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (block.prompt.isNotEmpty()) {
            Text(
                text = block.prompt,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        block.options.forEach { opt ->
            val isSelected = opt.id in selected
            val isQuiz = block.mode == "quiz"
            val borderColor = when {
                isQuiz && isSelected && opt.correct == true -> Color(0xFF2E7D32)
                isQuiz && isSelected && opt.correct == false -> MaterialTheme.colorScheme.error
                isSelected -> Clay
                else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
            }
            val bgColor = when {
                isQuiz && isSelected && opt.correct == true -> Color(0xFF2E7D32).copy(alpha = 0.08f)
                isQuiz && isSelected && opt.correct == false -> MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
                isSelected -> Clay.copy(alpha = 0.10f)
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(innerShape)
                    .border(if (isSelected) 1.2.dp else 0.8.dp, borderColor, innerShape)
                    .background(bgColor)
                    .clickable {
                        when (block.mode) {
                            "multi" -> {
                                if (isSelected) selected.remove(opt.id) else selected.add(opt.id)
                            }
                            "quiz" -> {
                                selected.clear()
                                selected.add(opt.id)
                            }
                            else -> {
                                selected.clear()
                                selected.add(opt.id)
                                if (block.autoSend && !submitted) {
                                    submitted = true
                                    actions?.onSend?.invoke(formatReply(listOf(opt.id)))
                                }
                            }
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(if (block.mode == "multi") RoundedCornerShape(4.dp) else CircleShape)
                            .border(1.5.dp, borderColor, if (block.mode == "multi") RoundedCornerShape(4.dp) else CircleShape)
                            .background(if (isSelected) borderColor else Color.Transparent),
                    )
                    Text(
                        text = opt.label,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (block.mode == "single" && block.autoSend) {
                        Text(
                            text = if (submitted && isSelected) "已发送" else "↗",
                            style = MaterialTheme.typography.labelSmall,
                            color = Clay,
                        )
                    } else if (isQuiz && isSelected && opt.correct != null) {
                        Text(
                            text = if (opt.correct) "✓ 正确" else "✗ 再想想",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = borderColor,
                        )
                    }
                }
                if (opt.desc.isNotEmpty()) {
                    Text(
                        text = opt.desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 24.dp),
                    )
                }
                if (isQuiz && isSelected && opt.explain.isNotEmpty()) {
                    Text(
                        text = opt.explain,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 24.dp, top = 2.dp),
                    )
                }
            }
        }

        if (block.mode == "multi" || (block.mode == "single" && !block.autoSend)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(
                    onClick = {
                        if (selected.isNotEmpty()) {
                            submitted = true
                            actions?.onSend?.invoke(formatReply(selected.toList()))
                        }
                    },
                    enabled = selected.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = Clay),
                ) {
                    Text(if (submitted) "再次发送" else block.confirmLabel)
                }
            }
        } else if (block.mode == "quiz" && selected.isNotEmpty()) {
            val chosen = block.options.firstOrNull { it.id == selected.first() }
            if (chosen != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    OutlinedButton(onClick = {
                        actions?.onSend?.invoke("关于「${chosen.label}」，请帮我进一步展开讲解")
                    }) {
                        Text("追问解析 ↗", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionsBlockView(
    block: UiBlock.Actions,
    actions: ChatUiActions?,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        block.items.forEach { item ->
            val suffix = if (item.action == "send") " ↗" else ""
            val onClick = {
                if (item.action == "prefill") {
                    actions?.onPrefill?.invoke(item.payload)
                } else {
                    actions?.onSend?.invoke(item.payload)
                }
                Unit
            }
            when (item.style) {
                "primary" -> Button(
                    onClick = onClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Clay),
                ) {
                    Text("${item.label}$suffix", style = MaterialTheme.typography.labelLarge)
                }
                "outline" -> OutlinedButton(onClick = onClick) {
                    Text("${item.label}$suffix", style = MaterialTheme.typography.labelLarge)
                }
                else -> Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                        .clickable(onClick = onClick)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = "${item.label}$suffix",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun TabsBlockView(
    block: UiBlock.Tabs,
    blockKey: String,
    actions: ChatUiActions?,
) {
    var activeIdx by remember(blockKey) { mutableIntStateOf(0) }
    val safeIdx = activeIdx.coerceIn(0, block.items.lastIndex)
    val tab = block.items[safeIdx]

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            block.items.forEachIndexed { idx, item ->
                val selected = idx == safeIdx
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (selected) Clay else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        )
                        .clickable { activeIdx = idx }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(innerShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (tab.title.isNotEmpty()) {
                Text(
                    text = tab.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (tab.body.isNotEmpty()) {
                Text(
                    text = tab.body,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            tab.pros.forEach { pro ->
                Text(
                    text = "+ $pro",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF2E7D32),
                )
            }
            tab.cons.forEach { con ->
                Text(
                    text = "- $con",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (tab.selectPayload.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(
                        onClick = { actions?.onSend?.invoke(tab.selectPayload) },
                        colors = ButtonDefaults.buttonColors(containerColor = Clay),
                    ) {
                        Text("${tab.selectLabel} ↗", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormBlockView(
    block: UiBlock.Form,
    blockKey: String,
    actions: ChatUiActions?,
) {
    val values = remember(blockKey) {
        actions?.getState(blockKey) {
            mutableStateMapOf<String, Any>().apply {
                block.fields.forEach { f ->
                    when (f) {
                        is UiFormField.Toggle -> put(f.id, f.default)
                        is UiFormField.Text -> put(f.id, f.default)
                        is UiFormField.NumberSlider -> put(f.id, f.default)
                        is UiFormField.Select -> put(f.id, f.default)
                    }
                }
            }
        } ?: mutableStateMapOf()
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        block.fields.forEach { field ->
            when (field) {
                is UiFormField.Toggle -> {
                    val checked = values[field.id] as? Boolean ?: field.default
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(innerShape)
                            .clickable { values[field.id] = !checked }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = field.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = checked,
                            onCheckedChange = { values[field.id] = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = Clay),
                        )
                    }
                }

                is UiFormField.Text -> {
                    val txt = values[field.id] as? String ?: field.default
                    OutlinedTextField(
                        value = txt,
                        onValueChange = { values[field.id] = it },
                        label = { Text(field.label) },
                        placeholder = if (field.placeholder.isNotEmpty()) {
                            { Text(field.placeholder) }
                        } else {
                            null
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                is UiFormField.NumberSlider -> {
                    val v = (values[field.id] as? Number)?.toFloat() ?: field.default
                    val display = if (field.step >= 1f) v.roundToInt().toString() else "%.1f".format(v)
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = field.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "$display${field.unit}",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = Clay,
                            )
                        }
                        Slider(
                            value = v.coerceIn(field.min, field.max),
                            onValueChange = { raw ->
                                val snapped = ((raw - field.min) / field.step).roundToInt() * field.step + field.min
                                values[field.id] = snapped.coerceIn(field.min, field.max)
                            },
                            valueRange = field.min..field.max,
                            colors = SliderDefaults.colors(thumbColor = Clay, activeTrackColor = Clay),
                            modifier = Modifier.height(32.dp),
                        )
                    }
                }

                is UiFormField.Select -> {
                    val cur = values[field.id] as? String ?: field.default
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = field.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            field.options.forEach { opt ->
                                val selected = opt == cur
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .border(
                                            1.dp,
                                            if (selected) Clay else MaterialTheme.colorScheme.outlineVariant,
                                            RoundedCornerShape(8.dp),
                                        )
                                        .background(if (selected) Clay.copy(alpha = 0.14f) else Color.Transparent)
                                        .clickable { values[field.id] = opt }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                ) {
                                    Text(
                                        text = opt,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (selected) Clay else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(
                onClick = {
                    val summary = block.fields.joinToString("\n") { f ->
                        val vStr = when (f) {
                            is UiFormField.Toggle -> if (values[f.id] as? Boolean ?: f.default) "是" else "否"
                            is UiFormField.Text -> (values[f.id] as? String ?: f.default).ifBlank { "(未填)" }
                            is UiFormField.NumberSlider -> {
                                val v = (values[f.id] as? Number)?.toFloat() ?: f.default
                                val n = if (f.step >= 1f) v.roundToInt().toString() else "%.1f".format(v)
                                "$n${f.unit}"
                            }
                            is UiFormField.Select -> values[f.id] as? String ?: f.default
                        }
                        "- ${f.label}：$vStr"
                    }
                    if (block.submitAction == "prefill") {
                        actions?.onPrefill?.invoke(summary)
                    } else {
                        actions?.onSend?.invoke(summary)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Clay),
            ) {
                val suffix = if (block.submitAction == "send") " ↗" else ""
                Text("${block.submitLabel}$suffix")
            }
        }
    }
}
