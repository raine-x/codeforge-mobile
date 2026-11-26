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
 * 物理姿态，按设备顶部朝向命名（用于内容旋转模式，见 [RotateWithDevice]）。
 * 传感器角与屏幕坐标系手性相反，因此 90° 对应内容逆时针旋转 90°。
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
    // WindowInsets.get*() 返回像素，必须按屏幕密度转为 dp（见 P-013）
    return with(d) {
        PaddingValues(l.toDp(), t.toDp(), r.toDp(), b.toDp())
    }
}

/**
 * 竖屏锁死下的内容旋转容器（仅手机端生效，平板走系统原生横竖屏）。
 * 已知边界（见 P-009）：系统输入法与底部弹层属于独立窗口，不随内容旋转。
 */
@Composable
fun RotateWithDevice(content: @Composable () -> Unit) {
    // 平板直接走系统横竖屏；不提供 LocalOrientation 使 Responsive 回退到真实窗口尺寸
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
