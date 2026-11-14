package com.hxy.codeforge.ui.common

import android.view.OrientationEventListener
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 旋转后内容坐标系的信息，由 [RotateWithDevice] 提供。
 *
 * 窗口锁竖屏后 LocalConfiguration 永远是竖屏尺寸，档位判定与 insets 都不能
 * 再用它 —— 这里给出**旋转后内容盒**的真实宽高（横持时 = 物理高×宽），
 * 以及按姿态重映射过的安全区 padding。
 */
class OrientationInfo(
    val isLandscape: Boolean,
    val widthDp: Int,
    val heightDp: Int,
    val insets: PaddingValues,
)

val LocalOrientation = staticCompositionLocalOf<OrientationInfo?> { null }

/**
 * 物理姿态：按「设备顶部指向哪」命名。
 *
 * Activity 已锁死竖屏（manifest `screenOrientation="portrait"`），系统不会再转
 * 窗口；这里的姿态来自加速度传感器，描述用户**手里**怎么拿手机。
 * 横屏时不是把界面换成横版双栏，而是把整套竖屏元素原样旋转 —— 见 [RotateWithDevice]。
 *
 * 方向对应关系经真机校准：传感器角 90°（顶部向左）→ 内容逆时针 90°。
 * 初版曾按推导写成顺时针，真机实测方向相反 —— 传感器方位角的参考系
 * 和屏幕坐标系手性不同，这类映射必须实测，不能纸上推导。
 */
enum class DevicePose(val sensorAngle: Int, val contentRotation: Float) {
    /** 正常竖握。 */
    Portrait(0, 0f),

    /** 顶部指向左（传感器角 90°）—— 内容逆时针转 90°。 */
    LandscapeLeft(90, -90f),

    /** 顶部指向右（传感器角 270°）—— 内容顺时针转 90°。 */
    LandscapeRight(270, 90f),

    /** 倒持（传感器角 180°），躺着看手机时常见。 */
    Inverted(180, 180f),
}

/**
 * 用 [OrientationEventListener] 探测物理姿态。
 *
 * 必须带迟滞：方位角在档位边界附近天然抖动，直接取最近档位会来回翻转。
 * 规则是「偏离当前档位中心超过 N° 才切换」，N 由设置里的灵敏度决定
 * （迟钝 80° / 标准 60° / 灵敏 45°）。设置里关掉「横持旋转」后恒报 Portrait。
 */
@Composable
fun rememberDevicePose(): DevicePose {
    val context = LocalContext.current
    var pose by remember { mutableStateOf(DevicePose.Portrait) }
    val enabled = com.hxy.codeforge.ui.settings.AppSettings.rotateWithDevice.value
    val hysteresis =
        com.hxy.codeforge.ui.settings.AppSettings.rotationSensitivity.value.hysteresisDegrees

    DisposableEffect(context) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(angle: Int) {
                if (angle == ORIENTATION_UNKNOWN) return
                if (!com.hxy.codeforge.ui.settings.AppSettings.rotateWithDevice.value) {
                    pose = DevicePose.Portrait
                    return
                }
                val centers = listOf(
                    DevicePose.Portrait,
                    DevicePose.LandscapeLeft,
                    DevicePose.Inverted,
                    DevicePose.LandscapeRight,
                )
                val currentCenter = pose.sensorAngle
                if (circularDistance(angle, currentCenter) > hysteresis) {
                    // 只会落进迟滞带之外的相邻档，取最近即可
                    pose = centers.minBy { circularDistance(angle, it.sensorAngle) }
                }
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }
    return if (enabled) pose else DevicePose.Portrait
}

private fun circularDistance(a: Int, b: Int): Int {
    val d = kotlin.math.abs(a - b) % 360
    return min(d, 360 - d)
}

/** 有符号的最短角差（-180..180），动画沿短弧走，避免从 -90 绕 270° 转到 180。 */
private fun shortestDelta(from: Float, to: Float): Float {
    val d = ((to - from) % 360f + 360f) % 360f
    return if (d > 180f) d - 360f else d
}

/**
 * 把物理窗口 insets 按姿态映射到内容坐标系。
 *
 * 旋转后「内容盒的左边」可能对应「物理屏幕的底边」（键盘/导航栏），
 * 直接用 safeDrawing 会把状态栏高度垫在没有状态栏的那条边上 —— 表现为
 * 横持时顶栏压住挖孔。映射关系随 [pose] 翻转（见 DevicePose 注释）。
 */
@Composable
private fun mappedInsets(pose: DevicePose): PaddingValues {
    val d = LocalDensity.current
    val physicalTop = maxOf(
        WindowInsets.statusBars.getTop(d),
        WindowInsets.displayCutout.getTop(d),
    )
    val physicalBottom = maxOf(
        WindowInsets.navigationBars.getBottom(d),
        WindowInsets.ime.getBottom(d),
        WindowInsets.displayCutout.getBottom(d),
    )
    val physicalLeft = maxOf(
        WindowInsets.navigationBars.getLeft(d, LayoutDirection.Ltr),
        WindowInsets.displayCutout.getLeft(d, LayoutDirection.Ltr),
    )
    val physicalRight = maxOf(
        WindowInsets.navigationBars.getRight(d, LayoutDirection.Rtl),
        WindowInsets.displayCutout.getRight(d, LayoutDirection.Rtl),
    )
    val (l, t, r, b) = when (pose) {
        DevicePose.Portrait -> listOf(physicalLeft, physicalTop, physicalRight, physicalBottom)
        DevicePose.LandscapeLeft -> listOf(physicalBottom, physicalLeft, physicalTop, physicalRight)
        DevicePose.LandscapeRight -> listOf(physicalTop, physicalRight, physicalBottom, physicalLeft)
        DevicePose.Inverted -> listOf(physicalRight, physicalBottom, physicalLeft, physicalTop)
    }
    // WindowInsets.get*() 返回的是**像素**，必须除以密度转 dp —— 此前直接 .dp
    // 等于把 inset 按屏幕密度又放大 N 倍：状态栏悬空 ~3 倍高、键盘一弹
    // 整屏内容被推出视口（真机录屏里「输入框被弹飞」就是它）。见 AGENTS P-013。
    return with(d) {
        PaddingValues(l.toDp(), t.toDp(), r.toDp(), b.toDp())
    }
}

/**
 * 横屏适配的核心：**窗口永远竖屏，转的是内容**。
 *
 * 检测到用户把手机横过来时，把整棵竖屏布局以弹簧动画旋转到对应角度，
 * 并把内容的宽高对调成「物理横屏下正好铺满屏幕」。布局档位跟着内容盒的
 * 真实尺寸走（[LocalOrientation]）—— 横持时自动切到网页端同款「侧栏 + 聊天」
 * 双栏，而不是把单栏硬拉宽。
 *
 * 尺寸对调按目标姿态立即生效、角度用弹簧追赶：45° 附近布局换边在视觉上
 * 正好被大角度旋转掩盖，看不到跳变。
 *
 * 已知边界（AGENTS P-009）：系统输入法窗口不由应用控制，锁竖屏时它仍按
 * 窗口坐标绘制，横持时键盘不会跟着转；底部弹层同理在独立窗口里。
 */
@Composable
fun RotateWithDevice(content: @Composable () -> Unit) {
    // 平板直接走系统横竖屏，不做内容旋转。
    //
    // 内容旋转是为「手机竖屏锁死 + 用户横着拿」设计的：系统不转，我们自己转。
    // 平板本来就能横过来用（窗口会真的变宽），再转 90° 等于把系统已经排好的
    // 布局又拧一下 —— 侧栏跑到上方、状态栏文字侧躺，纯属自找麻烦。
    //
    // 不提供 LocalOrientation 即可：Responsive 侧在它为 null 时回退到真实窗口尺寸。
    val isTablet = androidx.compose.ui.platform.LocalConfiguration.current
        .smallestScreenWidthDp >= 600
    if (isTablet) {
        content()
        return
    }

    val pose = rememberDevicePose()
    val angle = remember { Animatable(0f) }

    LaunchedEffect(pose) {
        val target = angle.value + shortestDelta(angle.value, pose.contentRotation)
        angle.animateTo(
            targetValue = target,
            animationSpec = spring(
                dampingRatio = 0.85f,
                stiffness = Spring.StiffnessMediumLow,
            ),
        )
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // 只在 layer 层读动画值：每帧重绘不触发重组与重新测量
            .graphicsLayer { rotationZ = angle.value },
    ) {
        val swapped = pose == DevicePose.LandscapeLeft || pose == DevicePose.LandscapeRight
        val density = LocalDensity.current
        val wDp = with(density) { constraints.maxWidth.toDp().value.roundToInt() }
        val hDp = with(density) { constraints.maxHeight.toDp().value.roundToInt() }
        val info = OrientationInfo(
            isLandscape = swapped,
            widthDp = if (swapped) hDp else wDp,
            heightDp = if (swapped) wDp else hDp,
            insets = mappedInsets(pose),
        )
        CompositionLocalProvider(LocalOrientation provides info) {
            val contentW = if (swapped) maxHeight else maxWidth
            val contentH = if (swapped) maxWidth else maxHeight
            // 必须居中：graphicsLayer 绕内容自身中心旋转，内容中心不对齐窗口中心
            // 时，转 90° 后整个画面会偏出一块
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .requiredSize(contentW, contentH),
            ) {
                content()
            }
        }
    }
}
