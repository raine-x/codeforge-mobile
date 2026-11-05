package com.hxy.codeforge.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * 手绘图形库：火焰 Logo（轮廓自动描摹自系统 🔥 emoji，见 FlameMark 注释）与全套线性图标。
 *
 * 不引 material-icons（约 10 MB）—— 全部图标用 24 单位坐标系手绘，
 * 统一 1.7dp 圆头描边，观感与 Claude 的 outline 图标一致。
 */

/**
 * 火焰标志：轮廓自动描摹自系统 🔥 emoji 的真实剪影（2026-10-25 定稿，
 * 描摹管线 = 位图 alpha → 像素裂缝跟踪 → RDP → Catmull-Rom，1.10× 横向适配，
 * 详见改动历史），内部三笔舌焰 + 三颗火星，替换早期均匀放射的星芒。
 *
 * [animated] 复刻定稿的燃烧动效：三焰绕各自根部异相摇曳 + 明暗闪烁，
 * 整体呼吸（1↔1.03）+ ±1.2° 微摆，火星上飘淡出；轮廓几乎静止只做极缓胀缩。
 * [color] 陶土橘主色（轮廓 / 中焰 / 右焰 / 两颗火星），[deepColor] 左焰深色。
 *
 * 全部几何定义在 200 单位坐标系里，与启动图标 res/drawable/ic_launcher_foreground.xml 同源，
 * 两处路径务必同步修改。
 */
@Composable
fun FlameMark(
    size: Dp,
    color: Color,
    deepColor: Color,
    modifier: Modifier = Modifier,
    animated: Boolean = true,
) {
    val outlinePath = remember { flamePath(FLAME_OUTLINE_D) }
    val centerPath = remember {
        Path().apply { moveTo(100f, 162f); cubicTo(90f, 126f, 110f, 100f, 99f, 58f) }
    }
    val leftPath = remember {
        Path().apply { moveTo(90f, 152f); cubicTo(78f, 138f, 70f, 116f, 72f, 92f) }
    }
    val rightPath = remember {
        Path().apply { moveTo(110f, 150f); cubicTo(122f, 134f, 129f, 118f, 132f, 98f) }
    }

    val t = rememberInfiniteTransition(label = "flame")
    // 整体：±1.2° 微摆 + 呼吸 1↔1.03，周期长、幅度小，提供「炉火活着」的底频
    val sway by t.animateFloat(
        -1.2f, 1.2f,
        infiniteRepeatable(tween(5200, easing = LinearEasing), RepeatMode.Reverse),
        label = "flame-sway",
    )
    val breathe by t.animateFloat(
        1f, 1.03f,
        infiniteRepeatable(tween(3400, easing = LinearEasing), RepeatMode.Reverse),
        label = "flame-breathe",
    )
    // 三焰绕各自根部摇曳：周期与相位互错（StartOffset），根部不动、顶端摆幅最大
    val rotC by t.animateFloat(
        -1.6f, 1.6f,
        infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Reverse),
        label = "flame-rot-c",
    )
    val rotL by t.animateFloat(
        -2f, 2f,
        infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Reverse, initialStartOffset = StartOffset(650)),
        label = "flame-rot-l",
    )
    val rotR by t.animateFloat(
        -1.8f, 1.8f,
        infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Reverse, initialStartOffset = StartOffset(1250)),
        label = "flame-rot-r",
    )
    // 明暗闪烁：周期与摇曳不同步，像火光跳动
    val flickC by t.animateFloat(
        0.93f, 1f,
        infiniteRepeatable(tween(1638, easing = LinearEasing), RepeatMode.Reverse, initialStartOffset = StartOffset(400)),
        label = "flame-flick-c",
    )
    val flickL by t.animateFloat(
        0.93f, 1f,
        infiniteRepeatable(tween(2016, easing = LinearEasing), RepeatMode.Reverse, initialStartOffset = StartOffset(1050)),
        label = "flame-flick-l",
    )
    val flickR by t.animateFloat(
        0.93f, 1f,
        infiniteRepeatable(tween(1386, easing = LinearEasing), RepeatMode.Reverse, initialStartOffset = StartOffset(1650)),
        label = "flame-flick-r",
    )
    // 轮廓极缓胀缩：几乎静止，稳住「一团火」的剪影
    val osX by t.animateFloat(
        1f, 1.012f,
        infiniteRepeatable(tween(3400, easing = LinearEasing), RepeatMode.Reverse),
        label = "flame-os-x",
    )
    val osY by t.animateFloat(
        1f, 1.007f,
        infiniteRepeatable(tween(3400, easing = LinearEasing), RepeatMode.Reverse),
        label = "flame-os-y",
    )
    // 火星上飘进度（0→1 循环，相位错开；上浮 10 单位、前 30% 淡入后淡出）
    val em1 by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2600, easing = LinearEasing), initialStartOffset = StartOffset(150)),
        label = "flame-em1",
    )
    val em2 by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2600, easing = LinearEasing), initialStartOffset = StartOffset(400)),
        label = "flame-em2",
    )
    val em3 by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2600, easing = LinearEasing), initialStartOffset = StartOffset(550)),
        label = "flame-em3",
    )

    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 200f
        withTransform({
            scale(s, s, pivot = Offset.Zero)
            rotate(if (animated) sway else 0f, pivot = Offset(100f, 100f))
            val b = if (animated) breathe else 1f
            scale(b, b, pivot = Offset(100f, 100f))
        }) {
            // 轮廓：极缓胀缩（锚在底部，火苗从底部起伏）
            val os = if (animated) 1f else 0f
            withTransform({
                scale(1f + (osX - 1f) * os, 1f + (osY - 1f) * os, pivot = Offset(100f, 170f))
            }) {
                drawPath(outlinePath, color, style = Stroke(3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            // 三焰：绕各自根部摇曳 + 明暗闪烁
            fun strokeFlame(p: Path, base: Offset, angle: Float, alpha: Float, w: Float, c: Color) {
                rotate(angle, pivot = base) {
                    drawPath(p, c.copy(alpha = alpha), style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
            strokeFlame(centerPath, Offset(100f, 162f), if (animated) rotC else 0f, if (animated) flickC else 1f, 13f, color)
            strokeFlame(leftPath, Offset(90f, 152f), if (animated) rotL else 0f, if (animated) flickL else 1f, 9f, deepColor)
            strokeFlame(rightPath, Offset(110f, 150f), if (animated) rotR else 0f, if (animated) flickR else 1f, 8f, color)
            // 火星：上飘 + 淡入淡出
            fun ember(cx: Float, cy: Float, r: Float, c: Color, driftX: Float, p: Float) {
                if (!animated) {
                    drawCircle(c, r, Offset(cx, cy))
                    return
                }
                val aIn = (p / 0.3f).coerceAtMost(1f)
                val alpha = ((1 - p) / 0.7f).coerceIn(0f, 1f) * aIn
                if (alpha <= 0.01f) return
                drawCircle(c.copy(alpha = alpha), r, Offset(cx + driftX * p, cy - 10f * p))
            }
            ember(109.9f, 27f, 3.2f, color, 3f, if (animated) em1 else 0f)
            ember(117f, 103f, 2.5f, deepColor, 0f, if (animated) em2 else 0f)
            ember(48.8f, 60f, 2.2f, color, -3f, if (animated) em3 else 0f)
        }
    }
}

/**
 * 轮廓 d 值：从系统 🔥 emoji 位图自动描摹（2026-10-25）。
 * 机器输出，仅含绝对 M/C/Z 指令；与 ic_launcher_foreground.xml 同源，两处同步修改。
 */
private const val FLAME_OUTLINE_D =
    "M43.8,117.5C43.7,121.1 44.0,124.3 44.5,127.6C45.1,130.9 45.9,134.0 47.1,137.1C48.3,140.2 50.0,143.5 51.8,146.3" +
        "C53.6,149.2 55.7,151.9 57.9,154.2C60.1,156.6 62.3,158.5 65.1,160.3C67.8,162.2 71.0,163.9 74.3,165.5" +
        "C77.7,167.0 81.5,168.5 85.2,169.5C88.9,170.5 92.9,171.2 96.5,171.6C100.1,172.0 103.8,172.0 106.9,172.0" +
        "C110.0,172.0 112.4,171.7 115.1,171.4C117.8,171.0 120.2,170.5 123.0,169.6C125.8,168.8 129.0,167.7 131.7,166.4" +
        "C134.5,165.1 137.1,163.7 139.5,162.0C141.8,160.2 143.9,158.2 145.7,156.1C147.5,154.1 149.1,151.8 150.4,149.8" +
        "C151.6,147.8 152.4,146.3 153.2,144.1C154.0,142.0 154.6,139.8 155.1,136.9C155.6,134.0 156.2,130.5 156.2,126.8" +
        "C156.3,123.0 156.0,118.8 155.4,114.6C154.8,110.4 153.7,105.7 152.5,101.5C151.3,97.3 150.0,93.4 148.2,89.6" +
        "C146.5,85.7 144.6,82.2 142.2,78.4C139.8,74.5 136.6,70.2 133.8,66.5C131.1,62.8 128.3,59.5 125.4,56.3" +
        "C122.6,53.2 119.7,50.3 116.7,47.5C113.8,44.8 110.2,41.5 107.9,39.6C105.6,37.6 104.1,36.5 102.9,36.0" +
        "C101.7,35.5 101.6,35.4 100.6,36.3C99.5,37.2 98.3,38.8 96.7,41.5C95.1,44.3 92.8,49.3 90.9,52.9" +
        "C89.0,56.5 86.8,60.7 85.3,63.2C83.8,65.8 82.8,67.3 81.9,68.2C81.0,69.1 80.8,69.2 80.1,68.9" +
        "C79.3,68.6 78.5,67.4 77.4,66.3C76.3,65.3 74.7,63.6 73.4,62.6C72.1,61.6 70.7,60.8 69.6,60.4" +
        "C68.5,60.0 67.7,60.0 66.8,60.2C65.9,60.4 65.5,60.3 64.4,61.4C63.3,62.6 62.0,64.2 60.2,67.0" +
        "C58.5,69.9 55.8,74.4 53.8,78.6C51.8,82.8 49.6,87.9 48.2,92.4C46.7,96.9 45.7,101.4 44.9,105.6" +
        "C44.2,109.8 43.9,113.8 43.8,117.5Z"

/**
 * 极简 d 解析器：只支持描摹输出的绝对 M/C/Z（数字以逗号或空格分隔）。
 * 不引 PATH 解析依赖 —— 数据由脚本生成、格式固定，25 行胜过一个新依赖。
 */
private fun flamePath(d: String): Path {
    val tokens = ArrayList<Any>(d.length / 2 + 8)
    var i = 0
    while (i < d.length) {
        val c = d[i]
        when {
            c.isLetter() -> { tokens.add(c); i++ }
            c == ',' || c == ' ' -> i++
            else -> {
                var j = i + 1
                while (j < d.length && (d[j].isDigit() || d[j] == '.' || d[j] == '-')) j++
                tokens.add(d.substring(i, j).toFloat())
                i = j
            }
        }
    }
    fun num(k: Int) = tokens[k] as Float
    return Path().apply {
        var k = 0
        while (k < tokens.size) {
            val t = tokens[k]
            if (t is Char) {
                when (t) {
                    'M' -> { moveTo(num(k + 1), num(k + 2)); k += 3 }
                    'C' -> {
                        k++
                        while (k + 5 < tokens.size && tokens[k] is Float) {
                            cubicTo(num(k), num(k + 1), num(k + 2), num(k + 3), num(k + 4), num(k + 5))
                            k += 6
                        }
                    }
                    'Z' -> { close(); k++ }
                    else -> k++
                }
            } else k++
        }
    }
}

/** 线性图标种类。全部定义在 24×24 坐标系里，绘制时按 [Glyph] 尺寸缩放。 */
enum class Glyph {
    Plus, Copy, Retry, Share, Speaker, ThumbUp, Chevron, Close, ArrowDown, Camera, Photo, FileDoc, Mic, Check, Back,
    ChatBubble, Folder, CodeBrackets, Cube, Gear, Edit,
    /** 重发：纸飞机轮廓（发送语义，不复用刷新箭头 —— 后者读作「重试」）。 */
    Resend,
    /** 继续这一轮：整圆 + 环内右向箭头。刻意不做缺口圆弧，避免与 [Retry] 混淆。 */
    Resume,
    /** 折叠/缩略：两枚对向内收的折角，旋转 180° 即「展开」。 */
    Collapse,
    /** 搜索：放大镜，探索分组头用。 */
    Search,
    /**
     * 选择元素：鼠标箭头指向一个框 —— DevTools 的 inspect 图标。
     *
     * 语义是「指着页面里的东西看」，所以画成「框 + 指针」而不是放大镜：
     * 后者已经被 [Search] 占了。
     */
    Inspect,
    /** 全屏：四角向外展开的角。 */
    Fullscreen,
    /** 退出全屏：四角向内收拢（[Fullscreen] 的反向）。 */
    FullscreenExit,
    /** 三点菜单：竖排三粒。 */
    MenuDots,
    /** 折叠侧栏：三条横杠。 */
    Sidebar,
}

/** 按 Claude outline 风格绘制一枚图标（圆头描边，线宽随尺寸微调）。 */
@Composable
fun GlyphIcon(
    glyph: Glyph,
    size: Dp,
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 1.7.dp,
) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        val stroke = Stroke(
            width = strokeWidth.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        when (glyph) {
            Glyph.Plus -> {
                drawLine(color, Offset(12 * s, 5 * s), Offset(12 * s, 19 * s), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(5 * s, 12 * s), Offset(19 * s, 12 * s), stroke.width, StrokeCap.Round)
            }

            Glyph.Edit -> {
                // 精简线性铅笔：只留笔杆轮廓与笔尖分隔线。
                // 早期版本在笔尖画了一块实心三角，小尺寸下显得像表情贴纸 ——
                // 用户明确要求「去可爱化」，这里一律只用描边。
                val body = Path().apply {
                    moveTo(4.8f * s, 19.2f * s)
                    lineTo(6.3f * s, 14.4f * s)
                    lineTo(14.6f * s, 6.1f * s)
                    lineTo(17.9f * s, 9.4f * s)
                    lineTo(9.6f * s, 17.7f * s)
                    close()
                }
                drawPath(body, color, style = stroke)
                drawLine(
                    color, Offset(6.3f * s, 14.4f * s), Offset(9.6f * s, 17.7f * s),
                    stroke.width, StrokeCap.Round,
                )
            }

            Glyph.Copy -> {
                // 两张纸错开叠放：后纸完整画、前纸完整画，交叠处靠线宽区分层次。
                // 不用「缺角」画法 —— 24 单位下缺角反而像撕坏的纸。
                drawRoundRectPath(
                    color, stroke,
                    topLeft = Offset(9f, 3.5f), wh = Offset(11.5f, 12f), corner = 2.5f, scale = s,
                )
                drawRoundRectPath(
                    color, stroke,
                    topLeft = Offset(3.5f, 8.5f), wh = Offset(11.5f, 12f), corner = 2.5f, scale = s,
                )
            }

            Glyph.Retry -> {
                drawArc(
                    color = color,
                    startAngle = -60f,
                    sweepAngle = 300f,
                    useCenter = false,
                    topLeft = Offset(4.5f * s, 4.5f * s),
                    size = androidx.compose.ui.geometry.Size(15f * s, 15f * s),
                    style = stroke,
                )
                val head = Path().apply {
                    moveTo(19.5f * s, 3.5f * s)
                    lineTo(19.5f * s, 8.5f * s)
                    lineTo(14.5f * s, 8.5f * s)
                }
                drawPath(head, color, style = stroke)
            }

            Glyph.Share -> {
                // 托盘
                val tray = Path().apply {
                    moveTo(7 * s, 10 * s)
                    lineTo(5 * s, 10 * s)
                    lineTo(5 * s, 19.5f * s)
                    lineTo(19 * s, 19.5f * s)
                    lineTo(19 * s, 10 * s)
                    lineTo(17 * s, 10 * s)
                }
                drawPath(tray, color, style = stroke)
                // 上箭头
                drawLine(color, Offset(12 * s, 14.5f * s), Offset(12 * s, 3.8f * s), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(8.8f * s, 7f * s), Offset(12 * s, 3.8f * s), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(15.2f * s, 7f * s), Offset(12 * s, 3.8f * s), stroke.width, StrokeCap.Round)
            }

            Glyph.Speaker -> {
                val horn = Path().apply {
                    moveTo(4 * s, 9.5f * s)
                    lineTo(7.5f * s, 9.5f * s)
                    lineTo(12 * s, 5.5f * s)
                    lineTo(12 * s, 18.5f * s)
                    lineTo(7.5f * s, 14.5f * s)
                    lineTo(4 * s, 14.5f * s)
                    close()
                }
                drawPath(horn, color, style = stroke)
                drawArc(
                    color = color,
                    startAngle = -50f,
                    sweepAngle = 100f,
                    useCenter = false,
                    topLeft = Offset(13.5f * s, 8.5f * s),
                    size = androidx.compose.ui.geometry.Size(5.5f * s, 7f * s),
                    style = stroke,
                )
                drawArc(
                    color = color,
                    startAngle = -50f,
                    sweepAngle = 100f,
                    useCenter = false,
                    topLeft = Offset(16f * s, 6.5f * s),
                    size = androidx.compose.ui.geometry.Size(8f * s, 11f * s),
                    style = stroke,
                )
            }

            Glyph.ThumbUp -> {
                val thumb = Path().apply {
                    // 简化自 material thumb_up 的外轮廓，坐标同在 24 系
                    moveTo(7f * s, 10f * s)
                    lineTo(7f * s, 20f * s)
                    moveTo(7f * s, 10f * s)
                    lineTo(10.6f * s, 4.2f * s)
                    quadraticTo(11.6f * s, 2.8f * s, 12.4f * s, 4.3f * s)
                    lineTo(12.4f * s, 8f * s)
                    lineTo(18.6f * s, 8f * s)
                    quadraticTo(20.6f * s, 8f * s, 20.1f * s, 10f * s)
                    lineTo(18.4f * s, 18.2f * s)
                    quadraticTo(18f * s, 20f * s, 16f * s, 20f * s)
                    lineTo(7f * s, 20f * s)
                }
                drawPath(thumb, color, style = stroke)
            }

            Glyph.Chevron -> {
                val p = Path().apply {
                    moveTo(9f * s, 5.5f * s)
                    lineTo(15.5f * s, 12f * s)
                    lineTo(9f * s, 18.5f * s)
                }
                drawPath(p, color, style = stroke)
            }

            Glyph.Gear -> {
                // 齿轮：中心圆 + 8 根齿。不画标准渐开线齿形 —— 24 坐标系里
                // 简化为「圆 + 短辐条」，小尺寸下观感与 Claude 设置图标一致。
                drawCircle(color, radius = 4.6f * s, center = Offset(12 * s, 12 * s), style = stroke)
                drawCircle(color, radius = 1.6f * s, center = Offset(12 * s, 12 * s), style = stroke)
                repeat(8) { i ->
                    val a = Math.PI * 2 * i / 8 + Math.PI / 8
                    val dx = kotlin.math.cos(a).toFloat()
                    val dy = kotlin.math.sin(a).toFloat()
                    drawLine(
                        color,
                        Offset(12 * s + dx * 6.2f * s, 12 * s + dy * 6.2f * s),
                        Offset(12 * s + dx * 9.6f * s, 12 * s + dy * 9.6f * s),
                        stroke.width,
                        StrokeCap.Round,
                    )
                }
            }

            Glyph.Close -> {
                drawLine(color, Offset(6 * s, 6 * s), Offset(18 * s, 18 * s), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(18 * s, 6 * s), Offset(6 * s, 18 * s), stroke.width, StrokeCap.Round)
            }

            Glyph.Back -> {
                // 返回箭头：一横 + 箭头尖。比 `<` 字符稳重，也不像 Chevron 那样
                // 只是「折叠/展开」的含义。默认笔画偏粗，小尺寸下也立得住。
                val p = Path().apply {
                    moveTo(19f * s, 12f * s)
                    lineTo(5.5f * s, 12f * s)
                    moveTo(11.5f * s, 6f * s)
                    lineTo(5.5f * s, 12f * s)
                    lineTo(11.5f * s, 18f * s)
                }
                drawPath(p, color, style = stroke)
            }

            Glyph.ArrowDown -> {
                drawLine(color, Offset(12 * s, 4.5f * s), Offset(12 * s, 19f * s), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(6.5f * s, 13.5f * s), Offset(12 * s, 19f * s), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(17.5f * s, 13.5f * s), Offset(12 * s, 19f * s), stroke.width, StrokeCap.Round)
            }

            Glyph.Camera -> {
                drawRoundRectPath(
                    color, stroke,
                    topLeft = Offset(3.5f, 7f), wh = Offset(17f, 13f), corner = 3f, scale = s,
                )
                drawLine(color, Offset(8.5f * s, 7f * s), Offset(10 * s, 4.5f * s), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(15.5f * s, 7f * s), Offset(14 * s, 4.5f * s), stroke.width, StrokeCap.Round)
                drawCircle(color, radius = 3.6f * s, center = Offset(12 * s, 13.5f * s), style = stroke)
            }

            Glyph.Photo -> {
                drawRoundRectPath(
                    color, stroke,
                    topLeft = Offset(3.5f, 4.5f), wh = Offset(17f, 15f), corner = 3f, scale = s,
                )
                drawCircle(color, radius = 1.8f * s, center = Offset(8.5f * s, 9.5f * s), style = stroke)
                val mountain = Path().apply {
                    moveTo(5f * s, 17f * s)
                    lineTo(10f * s, 12f * s)
                    lineTo(14f * s, 16f * s)
                    lineTo(16.5f * s, 13.5f * s)
                    lineTo(20.5f * s, 17.5f * s)
                }
                drawPath(mountain, color, style = stroke)
            }

            Glyph.FileDoc -> {
                val doc = Path().apply {
                    moveTo(6f * s, 3.5f * s)
                    lineTo(14f * s, 3.5f * s)
                    lineTo(18f * s, 7.5f * s)
                    lineTo(18f * s, 20.5f * s)
                    lineTo(6f * s, 20.5f * s)
                    close()
                }
                drawPath(doc, color, style = stroke)
                val fold = Path().apply {
                    moveTo(14f * s, 3.5f * s)
                    lineTo(14f * s, 7.5f * s)
                    lineTo(18f * s, 7.5f * s)
                }
                drawPath(fold, color, style = stroke)
            }

            Glyph.Check -> {
                val p = Path().apply {
                    moveTo(5f * s, 12.5f * s)
                    lineTo(10f * s, 17.5f * s)
                    lineTo(19f * s, 6.5f * s)
                }
                drawPath(p, color, style = stroke)
            }

            Glyph.ChatBubble -> {
                drawRoundRectPath(
                    color, stroke,
                    topLeft = Offset(3.5f, 4.5f), wh = Offset(17f, 13f), corner = 4f, scale = s,
                )
                val tail = Path().apply {
                    moveTo(8.5f * s, 17.5f * s)
                    lineTo(7f * s, 21f * s)
                    lineTo(12f * s, 17.5f * s)
                }
                drawPath(tail, color, style = stroke)
            }

            Glyph.Folder -> {
                val folder = Path().apply {
                    moveTo(3.5f * s, 6.5f * s)
                    quadraticTo(3.5f * s, 4.5f * s, 5.5f * s, 4.5f * s)
                    lineTo(9f * s, 4.5f * s)
                    lineTo(11.5f * s, 7f * s)
                    lineTo(18.5f * s, 7f * s)
                    quadraticTo(20.5f * s, 7f * s, 20.5f * s, 9f * s)
                    lineTo(20.5f * s, 17.5f * s)
                    quadraticTo(20.5f * s, 19.5f * s, 18.5f * s, 19.5f * s)
                    lineTo(5.5f * s, 19.5f * s)
                    quadraticTo(3.5f * s, 19.5f * s, 3.5f * s, 17.5f * s)
                    close()
                }
                drawPath(folder, color, style = stroke)
            }

            Glyph.CodeBrackets -> {
                val left = Path().apply {
                    moveTo(9f * s, 7f * s)
                    lineTo(4.5f * s, 12f * s)
                    lineTo(9f * s, 17f * s)
                }
                val right = Path().apply {
                    moveTo(15f * s, 7f * s)
                    lineTo(19.5f * s, 12f * s)
                    lineTo(15f * s, 17f * s)
                }
                drawPath(left, color, style = stroke)
                drawPath(right, color, style = stroke)
            }

            Glyph.Cube -> {
                drawRoundRectPath(
                    color, stroke,
                    topLeft = Offset(4.5f, 4.5f), wh = Offset(15f, 15f), corner = 3f, scale = s,
                )
                drawLine(color, Offset(4.5f * s, 9.5f * s), Offset(19.5f * s, 9.5f * s), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(9.5f * s, 9.5f * s), Offset(9.5f * s, 19.5f * s), stroke.width, StrokeCap.Round)
            }

            Glyph.Mic -> {                drawRoundRectPath(
                    color, stroke,
                    topLeft = Offset(9.5f, 3.5f), wh = Offset(5f, 10f), corner = 2.5f, scale = s,
                )
                val cradle = Path().apply {
                    moveTo(5.5f * s, 11.5f * s)
                    quadraticTo(5.5f * s, 17.5f * s, 12f * s, 17.5f * s)
                    quadraticTo(18.5f * s, 17.5f * s, 18.5f * s, 11.5f * s)
                }
                drawPath(cradle, color, style = stroke)
                drawLine(color, Offset(12 * s, 17.5f * s), Offset(12 * s, 20.5f * s), stroke.width, StrokeCap.Round)
            }

            Glyph.Resend -> {
                // 纸飞机：两条斜边收束到右尖，中线是折痕。纯描边、无填充。
                val plane = Path().apply {
                    moveTo(3.5f * s, 10.8f * s)
                    lineTo(20.5f * s, 3.8f * s)
                    lineTo(14f * s, 20.5f * s)
                    lineTo(11f * s, 13.3f * s)
                    close()
                }
                drawPath(plane, color, style = stroke)
                drawLine(color, Offset(11f * s, 13.3f * s), Offset(20.5f * s, 3.8f * s), stroke.width, StrokeCap.Round)
            }

            Glyph.Resume -> {
                // 整圆 + 环内右向箭头 = 「继续」。
                // 不画缺口圆弧 + 顶部钩（那是 Retry 的形状），否则用户会以为点在重试。
                drawCircle(color, radius = 8.6f * s, center = Offset(12 * s, 12 * s), style = stroke)
                val arrow = Path().apply {
                    moveTo(9.6f * s, 7.6f * s)
                    lineTo(14.2f * s, 12f * s)
                    lineTo(9.6f * s, 16.4f * s)
                }
                drawPath(arrow, color, style = stroke)
            }

            Glyph.Collapse -> {
                // 两枚对向内收的折角；调用方旋转 180° 即得「展开」
                val upper = Path().apply {
                    moveTo(8f * s, 6.5f * s)
                    lineTo(12f * s, 10.5f * s)
                    lineTo(16f * s, 6.5f * s)
                }
                val lower = Path().apply {
                    moveTo(8f * s, 17.5f * s)
                    lineTo(12f * s, 13.5f * s)
                    lineTo(16f * s, 17.5f * s)
                }
                drawPath(upper, color, style = stroke)
                drawPath(lower, color, style = stroke)
            }

            Glyph.Inspect -> {
                // 被指向的框：细矩形，虚线感靠圆角与断续描边表达
                drawRoundRectPath(
                    color, stroke,
                    topLeft = Offset(3f, 4.5f), wh = Offset(18f, 15f), corner = 2.5f, scale = s,
                )
                // 鼠标指针：标准箭头轮廓，落在框的左下角
                val cursor = Path().apply {
                    moveTo(8.4f * s, 8f * s)
                    lineTo(8.4f * s, 15.8f * s)
                    lineTo(10.7f * s, 13.7f * s)
                    lineTo(12.2f * s, 16.9f * s)
                    lineTo(13.9f * s, 15.9f * s)
                    lineTo(12.4f * s, 12.8f * s)
                    lineTo(15.3f * s, 12.6f * s)
                    close()
                }
                drawPath(cursor, color, style = stroke)
            }

            Glyph.Fullscreen -> {
                // 四角向外：每角一个 L 形括号
                val arm = 5f * s
                val corners = listOf(
                    floatArrayOf(4.5f, 4.5f, 1f, 1f),
                    floatArrayOf(19.5f, 4.5f, -1f, 1f),
                    floatArrayOf(4.5f, 19.5f, 1f, -1f),
                    floatArrayOf(19.5f, 19.5f, -1f, -1f),
                )
                corners.forEach { c ->
                    val x = c[0] * s
                    val y = c[1] * s
                    val dx = c[2]
                    val dy = c[3]
                    drawLine(color, Offset(x, y), Offset(x + dx * arm, y), stroke.width, StrokeCap.Round)
                    drawLine(color, Offset(x, y), Offset(x, y + dy * arm), stroke.width, StrokeCap.Round)
                }
            }

            Glyph.FullscreenExit -> {
                // 四角向内：L 形括号开口朝中心
                val arm = 5f * s
                val corners = listOf(
                    floatArrayOf(4.5f, 4.5f, 1f, 1f),
                    floatArrayOf(19.5f, 4.5f, -1f, 1f),
                    floatArrayOf(4.5f, 19.5f, 1f, -1f),
                    floatArrayOf(19.5f, 19.5f, -1f, -1f),
                )
                corners.forEach { c ->
                    val x = c[0] * s
                    val y = c[1] * s
                    val dx = c[2]
                    val dy = c[3]
                    drawLine(color, Offset(x, y + dy * arm), Offset(x, y), stroke.width, StrokeCap.Round)
                    drawLine(color, Offset(x + dx * arm, y), Offset(x, y), stroke.width, StrokeCap.Round)
                }
            }

            Glyph.MenuDots -> {
                // 三粒实心圆点，竖排
                listOf(6.5f, 12f, 17.5f).forEach { y ->
                    drawCircle(color, radius = 1.5f * s, center = Offset(12f * s, y * s))
                }
            }

            Glyph.Sidebar -> {
                // 三条横杠：折叠侧栏的通用记号
                listOf(6.5f, 12f, 17.5f).forEach { y ->
                    drawLine(
                        color,
                        Offset(4.5f * s, y * s),
                        Offset(19.5f * s, y * s),
                        stroke.width,
                        StrokeCap.Round,
                    )
                }
            }

            Glyph.Search -> {
                drawCircle(color, radius = 5.6f * s, center = Offset(10.6f * s, 10.6f * s), style = stroke)
                drawLine(color, Offset(15f * s, 15f * s), Offset(20f * s, 20f * s), stroke.width, StrokeCap.Round)
            }
        }
    }
}

/** 赞/踩共用一枚图形，[flip] 时中心翻转得到踩。 */
@Composable
fun ThumbIcon(size: Dp, color: Color, flip: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        val stroke = Stroke(width = 1.7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        scale(1f, if (flip) -1f else 1f, pivot = center) {
            val thumb = Path().apply {
                moveTo(7f * s, 10f * s)
                lineTo(7f * s, 20f * s)
                moveTo(7f * s, 10f * s)
                lineTo(10.6f * s, 4.2f * s)
                quadraticTo(11.6f * s, 2.8f * s, 12.4f * s, 4.3f * s)
                lineTo(12.4f * s, 8f * s)
                lineTo(18.6f * s, 8f * s)
                quadraticTo(20.6f * s, 8f * s, 20.1f * s, 10f * s)
                lineTo(18.4f * s, 18.2f * s)
                quadraticTo(18f * s, 20f * s, 16f * s, 20f * s)
                lineTo(7f * s, 20f * s)
            }
            drawPath(thumb, color, style = stroke)
        }
    }
}

/** 思考行的火花小星（4 短 4 长交替，比 Logo 更碎）。 */
@Composable
fun Sparkle(size: Dp, color: Color, modifier: Modifier = Modifier, animate: Boolean = false) {
    if (animate) {
        val transition = rememberInfiniteTransition(label = "sparkle")
        val pulse by transition.animateFloat(
            initialValue = 0.55f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
            label = "sparkle-pulse",
        )
        Canvas(modifier.size(size).scale(pulse)) { drawSpark(color) }
    } else {
        Canvas(modifier.size(size)) { drawSpark(color) }
    }
}

private fun DrawScope.drawSpark(color: Color) {
    val r = size.minDimension / 2f
    val center = Offset(size.width / 2f, size.height / 2f)
    repeat(8) { i ->
        val angle = Math.PI * 2 * i / 8
        val dx = cos(angle).toFloat()
        val dy = sin(angle).toFloat()
        val inner = r * if (i % 2 == 0) 0.15f else 0.42f
        drawLine(
            color = color,
            start = Offset(center.x + dx * inner, center.y + dy * inner),
            end = Offset(center.x + dx * r * 0.92f, center.y + dy * r * 0.92f),
            strokeWidth = r * 0.22f,
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawRoundRectPath(
    color: Color,
    stroke: Stroke,
    topLeft: Offset,
    wh: Offset,
    corner: Float,
    scale: Float,
) {
    drawRoundRect(
        color = color,
        topLeft = Offset(topLeft.x * scale, topLeft.y * scale),
        size = androidx.compose.ui.geometry.Size(wh.x * scale, wh.y * scale),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner * scale),
        style = stroke,
    )
}
