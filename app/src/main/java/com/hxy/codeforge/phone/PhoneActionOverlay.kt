package com.hxy.codeforge.phone

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 基于 `TYPE_ACCESSIBILITY_OVERLAY` 的全屏操控视觉反馈层：
 * - 屏幕四周流动跑马灯（执行中暖陶土金渐变 / 暂停琥珀慢速提示）；
 * - 顶部悬浮胶囊（当前步数/动作、开始执行通知栏停止提醒、实时 AI 思考过程）；
 * - 三阶段点击过程动画（目标边界高亮 → 收拢瞄准 → 按压涟漪与抬起）及滑动轨迹。
 *
 * 截图前会短暂隐藏并暂停跑马灯重绘，避免视觉干扰被拍入图像。
 */
internal class PhoneActionOverlay(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var overlayView: OverlayCanvasView? = null
    private var attached = false
    private var lastStartupNonceShown = 0L

    fun attach() {
        mainHandler.post {
            if (attached) return@post
            runCatching {
                val view = OverlayCanvasView(context)
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    if (Build.VERSION.SDK_INT >= 28) {
                        layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
                windowManager?.addView(view, params)
                overlayView = view
                attached = true
            }
        }
    }

    fun detach() {
        mainHandler.post {
            val view = overlayView ?: return@post
            overlayView = null
            attached = false
            view.releaseAnimators()
            runCatching { windowManager?.removeViewImmediate(view) }
        }
    }

    fun setTemporarilyHidden(hidden: Boolean) {
        val apply = Runnable {
            overlayView?.setTemporarilyHiddenState(hidden)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            apply.run()
        } else {
            mainHandler.post(apply)
        }
    }

    fun updateTaskVisuals(
        showOverlay: Boolean,
        active: Boolean,
        paused: Boolean,
        actionText: String,
        thinkingText: String,
        stepCount: Int,
        startupNotice: String,
        startupNoticeNonce: Long,
    ) {
        if (!showOverlay || !active) {
            mainHandler.post {
                overlayView?.updateTaskState(
                    active = false,
                    paused = false,
                    actionText = "",
                    thinkingText = "",
                    stepCount = 0,
                    transientNotice = "",
                    transientDurationMs = 0L,
                )
            }
            return
        }
        val noticeToShow = if (startupNoticeNonce > 0L && startupNoticeNonce != lastStartupNonceShown && startupNotice.isNotBlank()) {
            lastStartupNonceShown = startupNoticeNonce
            startupNotice
        } else {
            ""
        }
        attach()
        mainHandler.post {
            overlayView?.updateTaskState(
                active = true,
                paused = paused,
                actionText = actionText,
                thinkingText = thinkingText,
                stepCount = stepCount,
                transientNotice = noticeToShow,
                transientDurationMs = if (noticeToShow.isNotBlank()) 3800L else 0L,
            )
        }
    }

    fun showTap(
        x: Float,
        y: Float,
        label: String,
        doubleTap: Boolean = false,
        longPress: Boolean = false,
        targetBounds: RectF? = null,
    ) {
        if (!PhoneControlPrefs.config.value.showActionAnimation) return
        attach()
        mainHandler.post {
            overlayView?.showTap(x, y, label, doubleTap, longPress, targetBounds)
        }
    }

    fun showSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long, label: String) {
        if (!PhoneControlPrefs.config.value.showActionAnimation) return
        attach()
        mainHandler.post {
            overlayView?.showSwipe(x1, y1, x2, y2, durationMs, label)
        }
    }

    fun showBadge(label: String, isError: Boolean = false) {
        if (!PhoneControlPrefs.config.value.showActionAnimation) return
        attach()
        mainHandler.post {
            overlayView?.showBadge(label, isError, durationMs = 2400L)
        }
    }

    fun showPreviewDemo() {
        attach()
        mainHandler.post {
            overlayView?.showPreviewDemo()
        }
    }

    private class OverlayCanvasView(context: Context) : View(context) {

        private sealed class Effect {
            abstract val durationMs: Long
            var progress: Float = 0f

            data class Tap(
                val x: Float,
                val y: Float,
                val doubleTap: Boolean,
                val longPress: Boolean,
                val label: String,
                val targetBounds: RectF?,
                override val durationMs: Long,
            ) : Effect()

            data class Swipe(
                val x1: Float,
                val y1: Float,
                val x2: Float,
                val y2: Float,
                override val durationMs: Long,
            ) : Effect()
        }

        private val density = context.resources.displayMetrics.density
        private var currentEffect: Effect? = null
        private var effectAnimator: ValueAnimator? = null

        private var taskActive = false
        private var taskPaused = false
        private var taskActionText = ""
        private var taskThinkingText = ""
        private var taskStepCount = 0
        private var previewMode = false

        private var badgeText: String = ""
        private var badgeIsError: Boolean = false
        private var badgeAlpha: Float = 0f

        private var marqueeAnimator: ValueAnimator? = null
        private var marqueeAngle = 0f
        private var marqueeShader: SweepGradient? = null
        private var shaderPausedState = false
        private val marqueeMatrix = Matrix()
        private val marqueeRect = RectF()
        private var cachedCornerRadiusPx = -1f
        private var cachedThinkingSource = ""
        private var cachedThinkingWidth = 0
        private var cachedThinkingLines: List<String> = emptyList()

        private val clayColor = Color.rgb(217, 119, 87)
        private val clayDeepColor = Color.rgb(184, 85, 47)
        private val warmGoldColor = Color.rgb(246, 193, 119)
        private val creamGlowColor = Color.rgb(255, 241, 214)
        private val coralColor = Color.rgb(232, 131, 107)
        private val amberColor = Color.rgb(212, 154, 42)
        private val amberDeepColor = Color.rgb(140, 98, 20)
        private val amberLightColor = Color.rgb(243, 202, 122)
        private val errorColor = Color.rgb(209, 67, 58)

        private val runningGradientColors = intArrayOf(
            clayDeepColor,
            clayColor,
            warmGoldColor,
            creamGlowColor,
            coralColor,
            clayColor,
            clayDeepColor,
        )
        private val pausedGradientColors = intArrayOf(
            amberDeepColor,
            amberColor,
            amberLightColor,
            creamGlowColor,
            amberColor,
            amberDeepColor,
        )

        private val hideBadgeTask = Runnable {
            badgeAlpha = 0f
            badgeText = ""
            invalidate()
        }
        private val clearEffectTask = Runnable {
            currentEffect = null
            invalidate()
        }
        private val stopPreviewTask = Runnable {
            if (previewMode) {
                previewMode = false
                if (!taskActive) {
                    stopMarqueeAnimator()
                }
                invalidate()
            }
        }

        private val marqueePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
        }

        private val targetHighlightFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = clayColor
        }

        private val targetHighlightStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            color = warmGoldColor
        }

        private val rippleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
            strokeCap = Paint.Cap.ROUND
        }

        private val touchHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = clayColor
        }

        private val touchOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.2f * density
            color = Color.WHITE
        }

        private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = clayColor
        }

        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = clayColor
        }

        private val pillBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(236, 30, 29, 27)
        }

        private val pillStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.4f * density
            color = clayColor
        }

        private val pillText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 12.5f * density
            isFakeBoldText = true
        }

        private val thinkingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(230, 245, 224, 206)
            textSize = 11.2f * density
        }

        private val tapTagBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(228, 30, 29, 27)
        }

        private val tapTagText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 11f * density
            isFakeBoldText = true
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            cachedCornerRadiusPx = -1f
            cachedThinkingWidth = 0
            if (w > 0 && h > 0) {
                rebuildMarqueeShader()
            }
        }

        override fun onDetachedFromWindow() {
            releaseAnimators()
            super.onDetachedFromWindow()
        }

        fun releaseAnimators() {
            removeCallbacks(hideBadgeTask)
            removeCallbacks(clearEffectTask)
            removeCallbacks(stopPreviewTask)
            effectAnimator?.cancel()
            effectAnimator = null
            stopMarqueeAnimator()
        }

        fun setTemporarilyHiddenState(hidden: Boolean) {
            visibility = if (hidden) INVISIBLE else VISIBLE
            if (hidden) {
                marqueeAnimator?.pause()
            } else if ((taskActive || previewMode) && marqueeAnimator != null) {
                marqueeAnimator?.resume()
            }
        }

        fun updateTaskState(
            active: Boolean,
            paused: Boolean,
            actionText: String,
            thinkingText: String,
            stepCount: Int,
            transientNotice: String,
            transientDurationMs: Long,
        ) {
            if (active && previewMode) {
                previewMode = false
                removeCallbacks(stopPreviewTask)
            }
            val pauseChanged = taskPaused != paused
            taskActive = active
            taskPaused = paused
            taskActionText = actionText.trim().take(54)
            taskThinkingText = thinkingText.trim().take(90)
            taskStepCount = stepCount

            if (transientNotice.isNotBlank()) {
                showBadge(transientNotice, isError = false, durationMs = transientDurationMs.coerceAtLeast(1500L))
            }

            if (active || previewMode) {
                if (pauseChanged || marqueeShader == null) {
                    rebuildMarqueeShader()
                }
                ensureMarqueeAnimator()
            } else {
                stopMarqueeAnimator()
            }
            invalidate()
        }

        fun showPreviewDemo() {
            removeCallbacks(stopPreviewTask)
            previewMode = true
            rebuildMarqueeShader()
            ensureMarqueeAnimator()
            val w = if (width > 0) width.toFloat() else context.resources.displayMetrics.widthPixels.toFloat()
            val h = if (height > 0) height.toFloat() else context.resources.displayMetrics.heightPixels.toFloat()
            val demoBounds = RectF(w * 0.34f, h * 0.49f, w * 0.66f, h * 0.56f)
            showTap(
                x = w * 0.5f,
                y = h * 0.525f,
                label = "「示例按钮」",
                doubleTap = false,
                longPress = false,
                targetBounds = demoBounds,
                updateTopBadge = false,
            )
            showBadge("手机操控已开始 · 下拉通知栏可随时停止", isError = false, durationMs = 2800L)
            postDelayed(stopPreviewTask, 2900L)
            invalidate()
        }

        fun showTap(
            x: Float,
            y: Float,
            label: String,
            doubleTap: Boolean,
            longPress: Boolean,
            targetBounds: RectF?,
            updateTopBadge: Boolean = true,
        ) {
            val dur = if (longPress) 1180L else if (doubleTap) 1050L else 860L
            startEffect(Effect.Tap(x, y, doubleTap, longPress, label.take(24), targetBounds, dur))
            if (updateTopBadge && label.isNotBlank()) {
                val prefix = if (longPress) "长按：" else if (doubleTap) "双击：" else "点击："
                val shown = if (label.startsWith("点击") || label.startsWith("长按") || label.startsWith("双击")) label else prefix + label
                showBadge(shown, isError = false, durationMs = 2200L)
            }
        }

        fun showSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long, label: String) {
            val dur = durationMs.coerceIn(360L, 1600L) + 320L
            startEffect(Effect.Swipe(x1, y1, x2, y2, dur))
            if (label.isNotBlank()) showBadge(label, isError = false, durationMs = 2200L)
        }

        fun showBadge(label: String, isError: Boolean, durationMs: Long) {
            removeCallbacks(hideBadgeTask)
            badgeText = label.take(56)
            badgeIsError = isError
            badgeAlpha = 1f
            invalidate()
            postDelayed(hideBadgeTask, durationMs)
        }

        private fun startEffect(effect: Effect) {
            removeCallbacks(clearEffectTask)
            effectAnimator?.cancel()
            currentEffect = effect
            effectAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = effect.durationMs
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    effect.progress = anim.animatedValue as Float
                    invalidate()
                }
                start()
            }
            postDelayed(clearEffectTask, effect.durationMs + 120L)
        }

        private fun rebuildMarqueeShader() {
            if (width <= 0 || height <= 0) return
            val paused = taskPaused && !previewMode
            shaderPausedState = paused
            val colors = if (paused) pausedGradientColors else runningGradientColors
            marqueeShader = SweepGradient(width / 2f, height / 2f, colors, null)
        }

        private fun ensureMarqueeAnimator() {
            val paused = taskPaused && !previewMode
            val targetDuration = if (paused) 6000L else 2800L
            val existing = marqueeAnimator
            if (existing != null && existing.isRunning) {
                if (existing.duration != targetDuration) {
                    existing.duration = targetDuration
                }
                if (visibility == VISIBLE && existing.isPaused) {
                    existing.resume()
                }
                return
            }
            existing?.cancel()
            marqueeAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = targetDuration
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    marqueeAngle = anim.animatedValue as Float
                    if (visibility == VISIBLE) invalidate()
                }
                start()
            }
        }

        private fun stopMarqueeAnimator() {
            marqueeAnimator?.cancel()
            marqueeAnimator = null
            marqueeAngle = 0f
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (visibility != VISIBLE) return

            if (taskActive || previewMode) {
                drawPerimeterMarquee(canvas)
            }
            when (val ef = currentEffect) {
                is Effect.Tap -> drawTap(canvas, ef)
                is Effect.Swipe -> drawSwipe(canvas, ef)
                null -> Unit
            }
            if (badgeText.isNotBlank() || taskActive || previewMode) {
                drawTopCapsule(canvas)
            }
        }

        private fun drawPerimeterMarquee(canvas: Canvas) {
            if (width <= 0 || height <= 0) return
            val paused = taskPaused && !previewMode
            if (marqueeShader == null || shaderPausedState != paused) {
                rebuildMarqueeShader()
            }
            val shader = marqueeShader ?: return
            marqueeMatrix.setRotate(marqueeAngle, width / 2f, height / 2f)
            shader.setLocalMatrix(marqueeMatrix)
            marqueePaint.shader = shader

            val corner = resolveCornerRadiusPx()
            val w = width.toFloat()
            val h = height.toFloat()

            // 三层叠加柔和光晕 + 清晰外框跑马灯，避免粗硬遮挡内容
            drawMarqueeLayer(canvas, w, h, 5f * density, 10f * density, 46, corner)
            drawMarqueeLayer(canvas, w, h, 2.8f * density, 5.5f * density, 118, corner)
            drawMarqueeLayer(canvas, w, h, 1.4f * density, 2.8f * density, 245, corner)
        }

        private fun drawMarqueeLayer(
            canvas: Canvas,
            w: Float,
            h: Float,
            inset: Float,
            strokeW: Float,
            alpha: Int,
            cornerRadius: Float,
        ) {
            marqueeRect.set(inset, inset, w - inset, h - inset)
            marqueePaint.strokeWidth = strokeW
            marqueePaint.alpha = alpha
            val r = (cornerRadius - inset).coerceAtLeast(8f * density)
            canvas.drawRoundRect(marqueeRect, r, r, marqueePaint)
        }

        private fun resolveCornerRadiusPx(): Float {
            if (cachedCornerRadiusPx >= 0f) return cachedCornerRadiusPx
            val fallback = 24f * density
            val resolved = if (Build.VERSION.SDK_INT >= 31) {
                val r = rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0
                if (r > 0) r.toFloat() else fallback
            } else {
                fallback
            }
            cachedCornerRadiusPx = resolved
            return resolved
        }

        private fun drawTap(canvas: Canvas, tap: Effect.Tap) {
            val p = tap.progress.coerceIn(0f, 1f)

            // 阶段 0：若有控件边界矩形，闪烁高亮目标区域，让点击目标一目了然
            tap.targetBounds?.let { b ->
                val bAlpha = ((1f - p) * 185).toInt().coerceIn(0, 185)
                targetHighlightFill.alpha = (bAlpha * 0.22f).toInt()
                targetHighlightStroke.alpha = bAlpha
                val br = 8f * density
                canvas.drawRoundRect(b, br, br, targetHighlightFill)
                canvas.drawRoundRect(b, br, br, targetHighlightStroke)
            }

            // 阶段 1 (p < 0.22): 收拢瞄准圈 + 指尖点压下；阶段 2 (0.22..0.75): 扩散涟漪；阶段 3 (0.75..1.0): 抬起消隐
            val coreScale = when {
                p < 0.22f -> {
                    val t = p / 0.22f
                    1.35f - 0.5f * t
                }
                p < 0.72f -> 0.85f
                else -> {
                    val t = (p - 0.72f) / 0.28f
                    0.85f + 0.45f * t
                }
            }
            val coreAlpha = if (p < 0.75f) 245 else ((1f - p) / 0.25f * 245).toInt().coerceIn(0, 245)

            if (p < 0.26f) {
                val approachT = (p / 0.26f).coerceIn(0f, 1f)
                rippleStroke.color = warmGoldColor
                rippleStroke.alpha = ((1f - approachT * 0.3f) * 210).toInt().coerceIn(0, 210)
                rippleStroke.strokeWidth = 2.2f * density
                val approachRadius = (36f - 22f * approachT) * density
                canvas.drawCircle(tap.x, tap.y, approachRadius, rippleStroke)
            }

            if (p >= 0.16f) {
                val rp = ((p - 0.16f) / 0.84f).coerceIn(0f, 1f)
                val rAlpha = ((1f - rp) * 235).toInt().coerceIn(0, 235)
                rippleStroke.color = clayColor
                rippleStroke.strokeWidth = 3f * density
                rippleStroke.alpha = rAlpha
                canvas.drawCircle(tap.x, tap.y, (12f + rp * (if (tap.longPress) 44f else 34f)) * density, rippleStroke)

                if (tap.doubleTap || tap.longPress) {
                    val rp2 = ((rp - 0.2f) / 0.8f).coerceIn(0f, 1f)
                    if (rp2 > 0f) {
                        rippleStroke.color = warmGoldColor
                        rippleStroke.alpha = ((1f - rp2) * 205).toInt().coerceIn(0, 205)
                        canvas.drawCircle(tap.x, tap.y, (8f + rp2 * 28f) * density, rippleStroke)
                    }
                }
            }

            // 长按时加环形保持进度弧
            if (tap.longPress && p in 0.16f..0.82f) {
                val holdT = ((p - 0.16f) / 0.66f).coerceIn(0f, 1f)
                val holdRadius = 18f * density
                val arcRect = RectF(tap.x - holdRadius, tap.y - holdRadius, tap.x + holdRadius, tap.y + holdRadius)
                rippleStroke.color = warmGoldColor
                rippleStroke.alpha = 230
                rippleStroke.strokeWidth = 3.2f * density
                canvas.drawArc(arcRect, -90f, 360f * holdT, false, rippleStroke)
            }

            // 指尖外光晕 + 实体核心圆点 + 白边高对比
            touchHaloPaint.alpha = (coreAlpha * 0.28f).toInt()
            canvas.drawCircle(tap.x, tap.y, 16f * density * coreScale, touchHaloPaint)

            dotFill.color = clayColor
            dotFill.alpha = coreAlpha
            canvas.drawCircle(tap.x, tap.y, 8.5f * density * coreScale, dotFill)

            touchOutlinePaint.alpha = coreAlpha
            canvas.drawCircle(tap.x, tap.y, 8.5f * density * coreScale, touchOutlinePaint)

            // 准星短线
            val cross = 13f * density
            rippleStroke.color = clayColor
            rippleStroke.strokeWidth = 2f * density
            rippleStroke.alpha = (coreAlpha * 0.78f).toInt()
            canvas.drawLine(tap.x - cross, tap.y, tap.x + cross, tap.y, rippleStroke)
            canvas.drawLine(tap.x, tap.y - cross, tap.x, tap.y + cross, rippleStroke)

            // 触摸点旁动作小签（放在不挡目标的正上方或正下方）
            if (tap.label.isNotBlank()) {
                val tag = if (tap.label.startsWith("点击") || tap.label.startsWith("长按") || tap.label.startsWith("双击")) {
                    tap.label
                } else {
                    (if (tap.longPress) "长按 " else if (tap.doubleTap) "双击 " else "点击 ") + tap.label
                }
                val tw = tapTagText.measureText(tag)
                val fm = tapTagText.fontMetrics
                val th = fm.descent - fm.ascent
                val padX = 8f * density
                val padY = 4f * density
                val bw = tw + padX * 2f
                val bh = th + padY * 2f
                val bx = (tap.x - bw / 2f).coerceIn(8f * density, (width - bw - 8f * density).coerceAtLeast(8f * density))
                val aboveY = tap.y - 26f * density - bh
                val safeTop = safeTopPx() + 56f * density
                val by = if (aboveY >= safeTop) aboveY else (tap.y + 26f * density).coerceAtMost((height - bh - 12f * density).coerceAtLeast(safeTop))
                tapTagBg.alpha = (coreAlpha * 0.9f).toInt()
                tapTagText.alpha = coreAlpha
                canvas.drawRoundRect(RectF(bx, by, bx + bw, by + bh), bh / 2f, bh / 2f, tapTagBg)
                canvas.drawText(tag, bx + padX, by + padY - fm.ascent, tapTagText)
            }
        }

        private fun drawSwipe(canvas: Canvas, sw: Effect.Swipe) {
            val p = sw.progress.coerceIn(0f, 1f)
            val alpha = (if (p < 0.78f) 240 else ((1f - p) / 0.22f * 240)).toInt().coerceIn(0, 255)
            val curX = sw.x1 + (sw.x2 - sw.x1) * p.coerceAtMost(0.85f) / 0.85f
            val curY = sw.y1 + (sw.y2 - sw.y1) * p.coerceAtMost(0.85f) / 0.85f

            linePaint.alpha = (alpha * 0.42f).toInt()
            canvas.drawLine(sw.x1, sw.y1, sw.x2, sw.y2, linePaint)

            linePaint.alpha = alpha
            canvas.drawLine(sw.x1, sw.y1, curX, curY, linePaint)

            dotFill.color = clayColor
            dotFill.alpha = alpha
            canvas.drawCircle(sw.x1, sw.y1, 5.5f * density, dotFill)
            canvas.drawCircle(curX, curY, 9f * density, dotFill)
            touchOutlinePaint.alpha = alpha
            canvas.drawCircle(curX, curY, 9f * density, touchOutlinePaint)

            // 终点箭头
            val angle = atan2((sw.y2 - sw.y1).toDouble(), (sw.x2 - sw.x1).toDouble())
            val arrowLen = 13f * density
            val a1 = angle + Math.toRadians(150.0)
            val a2 = angle - Math.toRadians(150.0)
            val path = Path().apply {
                moveTo(sw.x2, sw.y2)
                lineTo(sw.x2 + (arrowLen * cos(a1)).toFloat(), sw.y2 + (arrowLen * sin(a1)).toFloat())
                moveTo(sw.x2, sw.y2)
                lineTo(sw.x2 + (arrowLen * cos(a2)).toFloat(), sw.y2 + (arrowLen * sin(a2)).toFloat())
            }
            canvas.drawPath(path, linePaint)
        }

        private fun drawTopCapsule(canvas: Canvas) {
            if (width <= 0) return
            val effectiveActive = taskActive || previewMode
            val primaryText = when {
                badgeText.isNotBlank() -> badgeText
                previewMode -> "正在预览操控提示 · 下拉通知栏可随时停止"
                taskPaused -> "已暂停（第 $taskStepCount 步）· 下拉通知栏可停止"
                taskActionText.isNotBlank() -> {
                    if (taskStepCount > 0) "第 $taskStepCount 步 · $taskActionText" else taskActionText
                }
                else -> "正在规划手机操作…"
            }

            val thinkingSource = when {
                previewMode -> "思考：正在识别目标按钮并准备点击…"
                effectiveActive && taskThinkingText.isNotBlank() -> "思考：$taskThinkingText"
                effectiveActive && taskStepCount == 0 && badgeText.isBlank() -> "下拉通知栏点「停止操控」可随时终止"
                else -> ""
            }

            val maxCapsuleW = (width - 28f * density).coerceAtLeast(180f * density).coerceAtMost(356f * density)
            val padH = 14f * density
            val padV = 8f * density
            val dotGap = 9f * density
            val dotR = 3.8f * density
            val innerMaxTextW = (maxCapsuleW - padH * 2f - dotR * 2f - dotGap).coerceAtLeast(80f * density)

            val primaryShown = ellipsizeSingleLine(primaryText, pillText, innerMaxTextW)
            val primaryW = dotR * 2f + dotGap + pillText.measureText(primaryShown)

            val thinkingLines = if (thinkingSource.isNotBlank()) {
                getWrappedThinkingLines(thinkingSource, maxCapsuleW - padH * 2f)
            } else {
                emptyList()
            }
            val maxThinkingW = thinkingLines.maxOfOrNull { thinkingPaint.measureText(it) } ?: 0f

            val contentW = maxOf(primaryW, maxThinkingW)
            val boxW = (contentW + padH * 2f).coerceAtMost(maxCapsuleW)

            val pFm = pillText.fontMetrics
            val primaryH = pFm.descent - pFm.ascent
            val tFm = thinkingPaint.fontMetrics
            val thinkingLineH = tFm.descent - tFm.ascent
            val extraThinkingH = if (thinkingLines.isNotEmpty()) {
                4f * density + thinkingLines.size * thinkingLineH + (thinkingLines.size - 1) * (2f * density)
            } else {
                0f
            }
            val boxH = padV * 2f + primaryH + extraThinkingH

            val left = ((width - boxW) / 2f).coerceAtLeast(14f * density)
            val top = safeTopPx()
            val rect = RectF(left, top, left + boxW, top + boxH)
            val corner = if (thinkingLines.isEmpty()) boxH / 2f else 16f * density

            val alphaRatio = if (effectiveActive) 1f else badgeAlpha
            if (alphaRatio <= 0f) return

            pillBg.alpha = (236 * alphaRatio).toInt()
            pillStroke.color = when {
                badgeIsError -> errorColor
                taskPaused && !previewMode -> amberColor
                else -> clayColor
            }
            pillStroke.alpha = (255 * alphaRatio).toInt()
            pillText.alpha = (255 * alphaRatio).toInt()
            thinkingPaint.alpha = (232 * alphaRatio).toInt()

            canvas.drawRoundRect(rect, corner, corner, pillBg)
            canvas.drawRoundRect(rect, corner, corner, pillStroke)

            val primaryBaseline = top + padV - pFm.ascent
            val dotCenterY = top + padV + primaryH / 2f
            dotFill.color = when {
                badgeIsError -> errorColor
                taskPaused && !previewMode -> amberColor
                else -> warmGoldColor
            }
            dotFill.alpha = (255 * alphaRatio).toInt()
            canvas.drawCircle(left + padH + dotR, dotCenterY, dotR, dotFill)
            canvas.drawText(primaryShown, left + padH + dotR * 2f + dotGap, primaryBaseline, pillText)

            if (thinkingLines.isNotEmpty()) {
                var y = top + padV + primaryH + 4f * density - tFm.ascent
                thinkingLines.forEach { line ->
                    canvas.drawText(line, left + padH, y, thinkingPaint)
                    y += thinkingLineH + 2f * density
                }
            }
        }

        private fun getWrappedThinkingLines(source: String, maxWidth: Float): List<String> {
            val wKey = maxWidth.toInt()
            if (source == cachedThinkingSource && wKey == cachedThinkingWidth) {
                return cachedThinkingLines
            }
            val lines = ArrayList<String>(2)
            val current = StringBuilder()
            for (i in source.indices) {
                val ch = source[i]
                val candidate = current.toString() + ch
                if (thinkingPaint.measureText(candidate) <= maxWidth || current.isEmpty()) {
                    current.append(ch)
                } else {
                    if (lines.size == 1) {
                        lines.add(ellipsizeSingleLine(current.toString() + source.substring(i), thinkingPaint, maxWidth))
                        current.setLength(0)
                        break
                    } else {
                        lines.add(current.toString())
                        current.setLength(0)
                        current.append(ch)
                    }
                }
            }
            if (current.isNotEmpty() && lines.size < 2) {
                lines.add(current.toString())
            }
            cachedThinkingSource = source
            cachedThinkingWidth = wKey
            cachedThinkingLines = lines
            return lines
        }

        private fun ellipsizeSingleLine(text: String, paint: Paint, maxWidth: Float): String {
            if (paint.measureText(text) <= maxWidth) return text
            val suffix = "…"
            var end = text.length
            while (end > 1 && paint.measureText(text, 0, end) + paint.measureText(suffix) > maxWidth) {
                end--
            }
            return text.substring(0, end) + suffix
        }

        @Suppress("DEPRECATION")
        private fun safeTopPx(): Float {
            val insets = rootWindowInsets
            val top = when {
                Build.VERSION.SDK_INT >= 30 && insets != null -> {
                    insets.getInsetsIgnoringVisibility(
                        WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout(),
                    ).top
                }
                Build.VERSION.SDK_INT >= 28 && insets != null -> {
                    maxOf(insets.systemWindowInsetTop, insets.displayCutout?.safeInsetTop ?: 0)
                }
                insets != null -> insets.systemWindowInsetTop
                else -> 0
            }
            return maxOf(top.toFloat() + 8f * density, 38f * density)
        }
    }
}
