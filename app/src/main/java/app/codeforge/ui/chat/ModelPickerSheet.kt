package app.codeforge.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.codeforge.model.PermissionMode
import app.codeforge.ui.common.Glyph
import app.codeforge.ui.common.GlyphIcon
import app.codeforge.ui.theme.Clay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 「选择模型」弹层 —— 四行结构 + 子页水平推入动画（对齐视频参考）：
 *
 *  1. 供应商：点击 → 子页（过渡对齐视频 2 的「新页右推入、旧页左退出」）；
 *  2. 模型：列表直接嵌在弹层里，**至多露出 3 行**，向下滚动选更多；
 *  3. 思考强度：点击 → 子页放**网页端同款拉条**（暗槽 + 强度渐变填充 +
 *     刻度点 + 发光滑球 + 星尘粒子，拖动线性跟随、松手吸附最近档）；
 *  4. 权限：显示当前档位，点击 → 动画滑到三档选择（只参考动画，布局用自家行样式）。
 *
 * 供应商/思考强度目前是界面态；内核 /api/config 的思考分级规格与
 * /api/providers 供应商库在设置层接入后替换数据源。拉条按 steps 规格实现，
 * 与网页 buildSlider 同构，换规格只改 levels 来源。
 */

// ---------------------------------------------------------------- 数据

/** 思考档位。文案对齐 Claude 客户端；最高档带用量提示徽标。 */
enum class ThinkingLevel(val label: String, val badge: String? = null) {
    Low("低"),
    Medium("中等"),
    High("高"),
    XHigh("超高"),
    Max("最高", "用量达 5x 或以上"),
}


private data class ModelOption(
    val name: String,
    val badge: String? = null,
    val subtitle: String,
)


private enum class PickerPage(val title: String) {
    Main("选择模型"),
    Provider("供应商"),
    Thinking("思考强度"),
    Permission("权限模式"),
}

// ---------------------------------------------------------------- 弹层骨架

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    currentModel: String,
    onModelSelect: (String) -> Unit,
    currentProvider: String,
    onProviderSelect: (String) -> Unit,
    thinkingIndex: Int,
    onThinkingSelect: (Int) -> Unit,
    permissionMode: PermissionMode,
    onPermissionChange: (PermissionMode) -> Unit,
    onDismiss: () -> Unit,
    /** 模型库真数据（/api/models/list）；空时回退演示列表。 */
    libraryModels: List<app.codeforge.model.LibraryModel> = emptyList(),
    /** 供应商真数据；空时回退演示列表。 */
    libraryProviders: List<String> = emptyList(),
) {
    var page by rememberSaveable { mutableStateOf(PickerPage.Main) }

    // 子页打开时返回键先回主页，而不是关掉整个弹层
    BackHandler(enabled = page != PickerPage.Main) {
        page = PickerPage.Main
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            SheetHeader(
                page = page,
                onClose = onDismiss,
                onBack = { page = PickerPage.Main },
            )

            // 子页切换 = 水平推入：新页从右滑入、旧页向左退出（视频 2 的过渡）。
            // 方向由页序号决定：返回主页时整体反向。
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    if (forward) {
                        (slideInHorizontally(tween(260, easing = FastOutSlowInEasing)) { it } +
                            fadeIn(tween(200)))
                            .togetherWith(
                                slideOutHorizontally(tween(260, easing = FastOutSlowInEasing)) { -it / 3 } +
                                    fadeOut(tween(160)),
                            )
                    } else {
                        (slideInHorizontally(tween(260, easing = FastOutSlowInEasing)) { -it / 3 } +
                            fadeIn(tween(200)))
                            .togetherWith(
                                slideOutHorizontally(tween(260, easing = FastOutSlowInEasing)) { it } +
                                    fadeOut(tween(160)),
                            )
                    }
                },
                label = "picker-page",
                modifier = Modifier.fillMaxWidth(),
            ) { p ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    when (p) {
                        PickerPage.Main -> MainPage(
                            currentModel = currentModel,
                            currentProvider = currentProvider,
                            thinking = ThinkingLevel.entries[thinkingIndex],
                            permissionMode = permissionMode,
                            onModelSelect = onModelSelect,
                            libraryModels = libraryModels,
                            onGoProvider = { page = PickerPage.Provider },
                            onGoThinking = { page = PickerPage.Thinking },
                            onGoPermission = { page = PickerPage.Permission },
                        )

                        PickerPage.Provider -> {
                            // 供应商 = 设置里的真库 + 模型库里出现但不在库中的
                            val fromModels = libraryModels
                                .map { it.provider }
                                .filter { it.isNotBlank() }
                                .distinct()
                            val merged = (libraryProviders + fromModels.filter { it !in libraryProviders })
                                .distinct()
                            ProviderPage(
                                currentProvider = currentProvider,
                                onProviderSelect = onProviderSelect,
                                providers = merged,
                                hasOrphans = libraryModels.any { it.provider.isBlank() },
                            )
                        }

                        PickerPage.Thinking -> ThinkingPage(
                            selectedIndex = thinkingIndex,
                            onSelect = onThinkingSelect,
                        )

                        PickerPage.Permission -> PermissionPage(
                            permissionMode = permissionMode,
                            onPermissionChange = onPermissionChange,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetHeader(page: PickerPage, onClose: () -> Unit, onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .height(36.dp),
        contentAlignment = Alignment.Center,
    ) {
        // 主页左上是关闭（X），子页左上是返回（←）—— 对齐视频里的头部结构
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(36.dp)
                .clip(CircleShape)
                .clickable { if (page == PickerPage.Main) onClose() else onBack() },
            contentAlignment = Alignment.Center,
        ) {
            if (page == PickerPage.Main) {
                GlyphIcon(
                    glyph = Glyph.Close,
                    size = 17.dp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else {
                // ArrowDown 顺时针转 90° 即左向箭头
                GlyphIcon(
                    glyph = Glyph.ArrowDown,
                    size = 17.dp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.rotate(90f),
                )
            }
        }
        Text(
            text = page.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ---------------------------------------------------------------- 主页

@Composable
private fun MainPage(
    currentModel: String,
    currentProvider: String,
    thinking: ThinkingLevel,
    permissionMode: PermissionMode,
    onModelSelect: (String) -> Unit,
    libraryModels: List<app.codeforge.model.LibraryModel> = emptyList(),
    onGoProvider: () -> Unit,
    onGoThinking: () -> Unit,
    onGoPermission: () -> Unit,
) {
    // 第 1 行：供应商
    PickerRow(
        title = "供应商",
        value = currentProvider.ifBlank { "未选择" },
        onClick = onGoProvider,
    )

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

    // 第 2 行：模型列表直接嵌进来 —— 视口压到 3 行高，往下滚动选更多。
    // 尾部常驻「测试模型」行：点选即直连模式（不使用供应商）；选中任一真模型
    // 自动退出测试模式（Composer 侧 onModelSelect 负责关开关）
    Text(
        text = "模型",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 62.dp * 3)
            .verticalScroll(rememberScrollState()),
    ) {
        // 只列模型库里的真模型。不再回退 Fable/Opus/Sonnet 那组写死的演示条目 ——
        // 那些点下去切不到任何真实通道，副标题（"应对你最艰巨的挑战"等）也是假的。
        libraryModels.forEach { m ->
            ModelRow(
                option = ModelOption(name = m.name.ifBlank { m.id }, badge = null, subtitle = ""),
                selected = m.id == currentModel || m.name == currentModel,
                onClick = { onModelSelect(m.id) },
            )
        }
        if (libraryModels.isEmpty()) {
            Text(
                text = "模型库为空。到「设置 → 模型服务」添加供应商后探测模型。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

    // 第 3 行：思考强度
    PickerRow(
        title = "思考强度",
        value = thinking.label,
        onClick = onGoThinking,
    )

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

    // 第 4 行：权限
    PickerRow(
        title = "权限",
        value = permissionMode.label,
        onClick = onGoPermission,
    )
}

@Composable
private fun ModelRow(option: ModelOption, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 9.dp)
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = option.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                option.badge?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                text = option.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (selected) {
            GlyphIcon(
                glyph = Glyph.Check,
                size = 18.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun PickerRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        GlyphIcon(
            glyph = Glyph.Chevron,
            size = 13.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------- 供应商子页

@Composable
private fun ProviderPage(
    currentProvider: String,
    onProviderSelect: (String) -> Unit,
    providers: List<String> = emptyList(),
    hasOrphans: Boolean = false,
) {
    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
        // 只列真实存在的供应商：设置里配的（/api/providers/list）+ 模型库里出现过的。
        // 不再回退「Anthropic 官方 / OpenAI 兼容 / 自定义中转」那组预设 ——
        // 那些是写死的演示数据，点下去切不到任何真实通道。
        providers.forEach { provider ->
            CheckRow(
                title = provider,
                subtitle = null,
                badge = null,
                selected = provider == currentProvider,
                onClick = { onProviderSelect(provider) },
            )
        }
        // 模型库里存在 provider 为空的模型时，单独给一个归类
        if (hasOrphans) {
            CheckRow(
                title = "无供应商",
                subtitle = null,
                badge = null,
                selected = currentProvider == "无供应商",
                onClick = { onProviderSelect("无供应商") },
            )
        }
        if (providers.isEmpty() && !hasOrphans) {
            Text(
                text = "还没有配置供应商。到「设置 → 模型服务 → 供应商」添加，或直接选下面的模型。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 14.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- 思考强度子页

@Composable
private fun ThinkingPage(selectedIndex: Int, onSelect: (Int) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        ThinkingSlider(
            levels = ThinkingLevel.entries.toList(),
            selectedIndex = selectedIndex,
            onSelect = onSelect,
        )
        // 档位气泡已实时显示档名，档位文字行就在滑条下方 —— 再放一句
        // 「投入程度越高…」的说明纯属重复，去掉（用户反馈：提示多余）
    }
}

/**
 * 网页端思考强度拉条的 Compose 等效实现（源：web/dist/ui.js buildSlider + app.css）。
 *
 * 逐项对应：
 *  - 暗色圆角槽 20dp（.fs-track 的 #26262b→#303036 渐变）；
 *  - 填充渐变 = forge 暖皮：hsl(12 78% 46%) → hsl(45-33r 85% 60%)，随强度变红；
 *  - 刻度点均布整条轨道（含两端），命中档变白放大 1.35 倍（.fs-dot.active）；
 *  - 24dp 白球拇指保持槽内（中心范围 [半径, 宽-半径]，对应网页的 calc 补偿式），
 *    光晕随强度增大，最高档呼吸脉动（.fancy-slider.max）；
 *  - 星尘粒子群：16 光点缓漂 + 5 流星拖尾，透明度随强度提升（--fxo）；
 *  - 拖动线性跟随不吸附，松手吸附最近档；点击轨道/刻度直接跳档；
 *  - 拇指上方档位气泡实时显示当前档名。
 */
@Composable
private fun ThinkingSlider(
    levels: List<ThinkingLevel>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val n = levels.size
    val ratio = remember { Animatable(if (n <= 1) 0f else selectedIndex.toFloat() / (n - 1)) }
    val scope = rememberCoroutineScope()
    val dragging = remember { mutableStateOf(false) }

    // 外部选中态变化（含松手吸附）回灌滑块位置
    LaunchedEffect(selectedIndex) {
        if (n > 1) ratio.animateTo(selectedIndex.toFloat() / (n - 1), tween(240))
    }

    fun snapTo(r: Float) {
        scope.launch { ratio.snapTo(r.coerceIn(0f, 1f)) }
    }

    // 最高档呼吸光晕（.fancy-slider.max 的 thumb-pulse）
    val breathing by rememberInfiniteTransition(label = "thumb-pulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_200), RepeatMode.Reverse),
        label = "pulse",
    )
    // 星尘共享时钟：各粒子速度不同 → 自然错峰漂移。
    // 必须用线性缓动 —— 周期性动画带上 FastOutSlowIn 会在每个循环里
    // 「加速-减速-瞬跳回起点」，固定挡位下看就是速度不均 + 明显的循环痕迹
    val dustPhase by rememberInfiniteTransition(label = "dust").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3_200, easing = LinearEasing)),
        label = "phase",
    )

    // 粒子种子：位置/速度/尺寸全随机错开（对应 buildSlider 里随机的 --x/--d/--dl）。
    // speed 只取整数：相位回绕时粒子位移恰为 100 的整数倍，取模后无缝衔接，
    // 循环重启不露痕迹（非整数速度每次回绕都会瞬移一次）
    val dust = remember {
        val rnd = java.util.Random(20261025L)
        List(16) {
            DustP(
                x = 4f + rnd.nextFloat() * 92f,
                y = 3f + rnd.nextFloat() * 12f,
                size = 2f + rnd.nextFloat() * 2f,
                speed = if (rnd.nextBoolean()) 1f else 2f,
                streak = false,
            )
        } + List(5) {
            DustP(
                x = 10f + rnd.nextFloat() * 88f,
                y = 4f + rnd.nextFloat() * 11f,
                size = 1.6f,
                speed = if (rnd.nextBoolean()) 2f else 3f,
                streak = true,
            )
        }
    }

    val currentRatio = ratio.value
    val glow = intensityColor(currentRatio)
    val fillStart = Color.hsl(12f, 0.78f, 0.46f)
    val nearestIndex = { r: Float ->
        if (n <= 1) 0 else (r * (n - 1)).roundToInt().coerceIn(0, n - 1)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
    ) {
        val density = LocalDensity.current
        val wPx = constraints.maxWidth.toFloat()
        val thumbR = with(density) { 12.dp.toPx() }
        fun thumbCenter(r: Float): Float = thumbR + (wPx - 2 * thumbR) * r

        // 档位气泡：跟随拇指，拖动中即预览最近档名（对应 .fs-tip）
        val tipLabel = levels[nearestIndex(currentRatio)].label
        val tipW = with(density) { 64.dp.toPx() }
        Text(
            text = tipLabel,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .offset {
                    IntOffset(
                        (thumbCenter(currentRatio) - tipW / 2).roundToInt()
                            .coerceIn(0, (wPx - tipW).roundToInt()),
                        0,
                    )
                }
                .background(Color(0xE6222226), RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 5.dp),
        )

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .align(Alignment.BottomCenter)
                .pointerInput(n) {
                    // 点击轨道 / 刻度点：直接跳档
                    detectTapGestures { pos ->
                        onSelect(nearestIndex((pos.x / size.width).coerceIn(0f, 1f)))
                    }
                }
                .pointerInput(n) {
                    // 拖动：线性跟随不吸附；松手吸附最近档（对应网页 dragging 逻辑）
                    detectDragGestures(
                        onDragStart = { pos ->
                            dragging.value = true
                            snapTo(pos.x / size.width)
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            snapTo(ratio.value + dragAmount.x / size.width)
                        },
                        onDragEnd = {
                            dragging.value = false
                            onSelect(nearestIndex(ratio.value))
                        },
                        onDragCancel = { dragging.value = false },
                    )
                },
        ) {
            val cy = size.height - 14.dp.toPx()
            val trackH = 20.dp.toPx()

            // 槽底：暗色渐变（.fs-track）
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(Color(0xFF26262B), Color(0xFF303036))),
                topLeft = Offset(0f, cy - trackH / 2f),
                size = Size(size.width, trackH),
                cornerRadius = CornerRadius(trackH / 2f),
            )

            val centerX = thumbCenter(currentRatio)
            val fillW = centerX.coerceAtLeast(0f)

            // 强度填充 + 星尘（裁剪在填充内，对应 .fs-fill 的 overflow hidden）
            clipRect(0f, cy - trackH / 2f, fillW, cy + trackH / 2f) {
                drawRoundRect(
                    brush = Brush.horizontalGradient(listOf(fillStart, glow)),
                    topLeft = Offset(0f, cy - trackH / 2f),
                    size = Size(fillW, trackH),
                    cornerRadius = CornerRadius(trackH / 2f),
                )
                // 星尘透明度随强度提升（--fxo = .45 + .55r）
                val fxo = 0.45f + currentRatio * 0.55f
                dust.forEach { p ->
                    // x 沿填充向左漂移，越界回绕
                    val px = ((p.x - dustPhase * 100f * p.speed) % 100f + 100f) % 100f
                    val x = px / 100f * fillW
                    val y = cy - trackH / 2f + p.y.dp.toPx()
                    if (p.streak) {
                        drawLine(
                            color = Color.White.copy(alpha = 0.9f * fxo),
                            start = Offset(x, y),
                            end = Offset(x + p.size.dp.toPx() * 10f, y),
                            strokeWidth = 1.6f,
                            cap = StrokeCap.Round,
                        )
                    } else {
                        drawCircle(
                            color = Color(0xFFFFDDA6).copy(alpha = 0.85f * fxo),
                            radius = p.size.dp.toPx() / 2f,
                            center = Offset(x, y),
                        )
                    }
                }
            }

            // 刻度点：均布整条轨道（含两端），命中档变白放大（.fs-dot.active）
            repeat(n) { i ->
                val dp = if (n <= 1) 0f else i.toFloat() / (n - 1)
                val active = abs(dp - currentRatio) * 100f < 1.5f
                drawCircle(
                    color = Color.White.copy(alpha = if (active) 1f else 0.45f),
                    radius = (if (active) 4.05f else 3f).dp.toPx() / 2f,
                    center = Offset(dp * size.width, cy),
                )
            }

            // 拇指：白球 + 强度光晕（半径随强度增大；最高档呼吸脉动）
            val glowBase = (6f + currentRatio * 14f).dp.toPx()
            val glowSpread = (2f + currentRatio * 3f).dp.toPx()
            val pulse = if (currentRatio >= 0.995f) 1f + breathing * 0.5f else 1f
            drawCircle(
                color = glow.copy(alpha = 0.35f),
                radius = thumbR + (glowBase * 0.4f + glowSpread) * pulse,
                center = Offset(centerX, cy),
            )
            drawCircle(
                color = glow.copy(alpha = 0.55f),
                radius = thumbR + glowSpread * 0.6f * pulse,
                center = Offset(centerX, cy),
            )
            drawCircle(
                color = Color.White,
                radius = thumbR,
                center = Offset(centerX, cy),
            )
        }
    }

    // 档位文字行：均布滑条下方（网页无此行，属等效补充 —— 拖动时气泡已有档名）
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        levels.forEachIndexed { i, level ->
            Text(
                text = level.label,
                style = MaterialTheme.typography.labelSmall,
                color = if (i == selectedIndex) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = if (i == selectedIndex) FontWeight.Medium else FontWeight.Normal,
            )
        }
    }
}

/** 网页强度→色相映射（forge 暖皮）：hue 45 → 12，与陶土主题同族。 */
private fun intensityColor(ratio: Float): Color =
    Color.hsl(45f - ratio * 33f, 0.85f, 0.60f)

private data class DustP(
    val x: Float,
    val y: Float,
    val size: Float,
    val speed: Float,
    val streak: Boolean,
)

// ---------------------------------------------------------------- 权限子页

@Composable
private fun PermissionPage(
    permissionMode: PermissionMode,
    onPermissionChange: (PermissionMode) -> Unit,
) {
    val notes = mapOf(
        PermissionMode.ReadOnly to "不写文件、不跑命令，只能读",
        PermissionMode.Ask to "写入与命令都需要你确认",
        PermissionMode.Auto to "自动执行，不再询问",
    )
    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
        PermissionMode.entries.forEach { mode ->
            CheckRow(
                title = mode.label,
                subtitle = notes[mode],
                badge = null,
                selected = mode == permissionMode,
                onClick = { onPermissionChange(mode) },
            )
        }
        Spacer(Modifier.height(6.dp))
    }
}

// ---------------------------------------------------------------- 公共件

@Composable
private fun CheckRow(
    title: String,
    subtitle: String?,
    badge: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                badge?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = Clay,
                        modifier = Modifier
                            .background(Clay.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (selected) {
            GlyphIcon(
                glyph = Glyph.Check,
                size = 18.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
