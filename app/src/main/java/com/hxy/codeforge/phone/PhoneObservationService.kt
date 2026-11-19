package com.hxy.codeforge.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import kotlin.math.abs

/**
 * 手机操控无障碍核心服务：
 * - 实时感知前台应用与窗口状态，并在任务运行期间强执行黑名单熔断；
 * - 读取压缩裁剪后的控件树快照（支持 `ref` 定位、坐标中心点与密码脱敏）；
 * - 执行点击、双击、长按、滑动、拖拽、滚动、文本输入/清空/选区/剪贴板与全局按键；
 * - 截取屏幕或单窗口（截图前短暂隐藏 `PhoneActionOverlay` 浮层）。
 */
class PhoneObservationService : AccessibilityService() {

    private data class Request(val pkg: String, val screenshot: Boolean, var entered: Boolean = false)

    private var pending: Request? = null
    private val handler = Handler(Looper.getMainLooper())
    private var capturing = false
    private var overlay: PhoneActionOverlay? = null
    private var serviceScope: CoroutineScope? = null

    @Volatile
    private var lastScreenshotElapsedMs: Long = 0L

    @Volatile
    var currentPackage: String = ""
        private set

    @Volatile
    var currentClassName: String = ""
        private set

    private val cachedRefs = HashMap<String, Rect>()

    private val retry = object : Runnable {
        override fun run() {
            val req = pending ?: return
            if (!permitted(req)) {
                finish("操作范围已改变，检查已停止")
                return
            }
            val root = rootInActiveWindow
            if (root?.packageName?.toString() == req.pkg) inspect(req, root)
            else if (req.entered) {
                finish("窗口已切换，检查已停止")
                return
            }
            if (pending === req && !capturing) handler.postDelayed(this, 300)
        }
    }
    private val timeout = Runnable { finish("窗口检查已超时") }

    companion object {
        @Volatile
        internal var active: PhoneObservationService? = null
            private set

        internal val result = MutableStateFlow(Observation("尚未检查"))
    }

    override fun onServiceConnected() {
        active = this
        overlay = PhoneActionOverlay(this).also { it.attach() }
        serviceScope?.cancel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        serviceScope = scope
        scope.launch {
            combine(PhoneTaskController.state, PhoneControlPrefs.config) { task, cfg ->
                task to cfg
            }.collect { (task, cfg) ->
                val isRunning = task.status == PhoneTaskStatus.RUNNING || task.status == PhoneTaskStatus.PAUSED
                overlay?.updateTaskVisuals(
                    showOverlay = cfg.showActionAnimation,
                    active = isRunning,
                    paused = task.status == PhoneTaskStatus.PAUSED,
                    actionText = task.currentAction,
                    thinkingText = task.thinkingPreview,
                    stepCount = task.stepCount,
                    startupNotice = task.startupNotice,
                    startupNoticeNonce = task.startupNoticeNonce,
                )
            }
        }
    }

    // ---------------------------------------------------------------- 测试夹具兼容方法

    internal fun begin(pkg: String, screenshot: Boolean) {
        finish("等待系统设置窗口")
        pending = Request(pkg, screenshot)
        handler.postDelayed(timeout, 8000)
        handler.post(retry)
    }

    internal fun finish(message: String, image: Bitmap? = null) {
        handler.removeCallbacks(retry)
        handler.removeCallbacks(timeout)
        pending = null
        capturing = false
        result.value = Observation(message, image)
    }

    private fun permitted(req: Request): Boolean {
        val observeAllowed = PhoneControlPrefs.config.value.isCapabilityEnabled(PhoneCapability.SCREEN_OBSERVE) ||
            getSharedPreferences("phone_controls", Context.MODE_PRIVATE).getBoolean("observe", false)
        return !PhoneScope.blocked(this, req.pkg) &&
            observeAllowed &&
            !isDeviceLocked()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val rootPkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull().orEmpty()
        val eventPkg = event?.packageName?.toString().orEmpty()
        val activePkg = rootPkg.ifBlank {
            if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) eventPkg else ""
        }
        if (activePkg.isNotBlank()) {
            currentPackage = activePkg
            if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                currentClassName = event.className?.toString().orEmpty()
            }
            // 黑名单实时监控：一旦自主任务运行期间跳转到黑名单应用，立即停止操控
            if (PhoneTaskController.state.value.status == PhoneTaskStatus.RUNNING &&
                PhoneScope.blocked(this, activePkg)
            ) {
                val label = PhoneScope.appLabel(this, activePkg)
                PhoneTaskController.stop("检测到已进入黑名单应用「$label ($activePkg)」，已停止操控")
                showOverlayBadge("命中黑名单：$label，已停止", isError = true)
            }
        }

        val req = pending ?: return
        if (!permitted(req)) {
            finish("操作范围已改变，检查已停止")
            return
        }
        if (eventPkg != req.pkg) {
            if (req.entered || eventPkg != packageName) finish("窗口已切换，检查已停止")
            return
        }
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != req.pkg || root.windowId != event?.windowId) return
        inspect(req, root)
    }

    private fun inspect(req: Request, root: AccessibilityNodeInfo) {
        req.entered = true
        if (capturing) return
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val labels = linkedSetOf<String>()
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 160 && labels.size < 20) {
            val node = queue.removeFirst()
            if (node.packageName?.toString() != req.pkg || !node.isVisibleToUser) continue
            if (node.isPassword || node.isEditable) {
                finish("窗口含输入字段，本次检查已停止")
                return
            }
            (node.text ?: node.contentDescription)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { labels.add(it.take(80)) }
            for (i in 0 until minOf(node.childCount, 160 - queue.size)) {
                node.getChild(i)?.let(queue::addLast)
            }
        }
        if (labels.isNotEmpty()) {
            finish(labels.joinToString(" · "))
            return
        }
        if (Build.VERSION.SDK_INT < 34 || !req.screenshot) {
            finish("窗口无可读控件；单窗口截图需 Android 14 及本次授权")
            return
        }
        capturing = true
        val id = root.windowId
        lastScreenshotElapsedMs = SystemClock.elapsedRealtime()
        takeScreenshotOfWindow(
            id,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    screenshot.hardwareBuffer.use { buffer ->
                        if (pending !== req) return
                        val current = rootInActiveWindow
                        if (!permitted(req) || current?.windowId != id || current.packageName?.toString() != req.pkg) {
                            finish("窗口已改变，截图已丢弃")
                            return
                        }
                        val image = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                        finish(if (image == null) "截图无法读取" else "本次窗口截图（仅本机）", image)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    if (pending === req) finish("系统拒绝窗口截图（$errorCode）")
                }
            },
        )
    }

    // ---------------------------------------------------------------- MCP 观察与操作能力

    fun isDeviceLocked(): Boolean =
        runCatching { getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true }
            .getOrDefault(false)

    fun showOverlayBadge(text: String, isError: Boolean = false) {
        overlay?.showBadge(text, isError)
    }

    fun showPreviewDemo() {
        overlay?.showPreviewDemo()
    }

    /** 读取当前前台包名（优先 rootInActiveWindow）。 */
    fun foregroundPackage(): String =
        runCatching { rootInActiveWindow?.packageName?.toString() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: currentPackage

    /**
     * 读取并裁剪当前活动窗口控件树，分配 `n1..nN` 短引用并缓存其屏幕矩形。
     */
    suspend fun captureScreenSnapshot(maxNodes: Int = 140): Result<ScreenSnapshot> = withContext(Dispatchers.Main) {
        val root = rootInActiveWindow
            ?: return@withContext Result.failure(IllegalStateException("当前无法获取活动窗口（可能处于锁屏或过渡动画中）"))
        val pkg = root.packageName?.toString().orEmpty()
        if (PhoneScope.blocked(this@PhoneObservationService, pkg)) {
            val label = PhoneScope.appLabel(this@PhoneObservationService, pkg)
            PhoneTaskController.stop("当前前台应用「$label ($pkg)」位于黑名单中，已停止操作")
            return@withContext Result.failure(IllegalStateException("当前应用「$label」在禁止操控黑名单内"))
        }
        val dm = resources.displayMetrics
        val cap = maxNodes.coerceIn(20, 240)
        val collected = ArrayList<UiNodeSnapshot>(cap)
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0
        var truncated = false
        val newRefs = HashMap<String, Rect>()

        while (queue.isNotEmpty()) {
            val (node, depth) = queue.removeFirst()
            visited++
            if (visited > 500) {
                truncated = true
                break
            }
            if (!node.isVisibleToUser) continue
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val hasArea = rect.width() > 0 && rect.height() > 0

            val rawText = if (node.isPassword) {
                if (!node.text.isNullOrEmpty()) "[密码已隐藏]" else ""
            } else {
                node.text?.toString()?.trim().orEmpty()
            }
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val resId = node.viewIdResourceName?.substringAfter(":id/")?.take(48).orEmpty()
            val cls = node.className?.toString()?.substringAfterLast('.')?.take(32).orEmpty()

            val meaningful = hasArea && (
                rawText.isNotEmpty() ||
                    desc.isNotEmpty() ||
                    resId.isNotEmpty() ||
                    node.isClickable ||
                    node.isEditable ||
                    node.isScrollable ||
                    node.isCheckable ||
                    node.isFocused
                )

            if (meaningful) {
                if (collected.size >= cap) {
                    truncated = true
                    break
                }
                val ref = "n${collected.size + 1}"
                newRefs[ref] = Rect(rect)
                collected.add(
                    UiNodeSnapshot(
                        ref = ref,
                        packageName = node.packageName?.toString().orEmpty(),
                        className = cls,
                        text = rawText.take(120),
                        contentDesc = desc.take(120),
                        resourceId = resId,
                        left = rect.left,
                        top = rect.top,
                        right = rect.right,
                        bottom = rect.bottom,
                        clickable = node.isClickable,
                        longClickable = node.isLongClickable,
                        editable = node.isEditable,
                        scrollable = node.isScrollable,
                        checkable = node.isCheckable,
                        checked = node.isChecked,
                        selected = node.isSelected,
                        focused = node.isFocused,
                        password = node.isPassword,
                        enabled = node.isEnabled,
                        depth = depth,
                    ),
                )
            }

            val childLimit = minOf(node.childCount, 40)
            for (i in 0 until childLimit) {
                node.getChild(i)?.let { queue.addLast(it to depth + 1) }
            }
        }

        cachedRefs.clear()
        cachedRefs.putAll(newRefs)
        currentPackage = pkg
        val signature = computeSnapshotSignature(pkg, root.windowId, collected)
        Result.success(
            ScreenSnapshot(
                packageName = pkg,
                appLabel = PhoneScope.appLabel(this@PhoneObservationService, pkg),
                className = currentClassName,
                windowId = root.windowId,
                screenWidth = dm.widthPixels,
                screenHeight = dm.heightPixels,
                signature = signature,
                nodes = collected,
                truncated = truncated,
            ),
        )
    }

    /** 定位目标节点：优先按最新控件树选择器，次之用最近一次快照缓存 `ref`。 */
    data class NodeLookup(
        val node: AccessibilityNodeInfo?,
        val bounds: Rect?,
        val label: String,
        val packageName: String,
    )

    suspend fun resolveTarget(
        ref: String? = null,
        text: String? = null,
        contentDesc: String? = null,
        resourceId: String? = null,
        index: Int = 0,
    ): NodeLookup? = withContext(Dispatchers.Main) {
        val root = rootInActiveWindow ?: return@withContext null
        val pkg = root.packageName?.toString().orEmpty()
        val matchNode = if (!text.isNullOrBlank() || !contentDesc.isNullOrBlank() || !resourceId.isNullOrBlank()) {
            findMatchingNodes(root, text, contentDesc, resourceId).getOrNull(index.coerceAtLeast(0))
        } else {
            null
        }
        if (matchNode != null) {
            val rect = Rect()
            matchNode.getBoundsInScreen(rect)
            val label = (matchNode.text ?: matchNode.contentDescription)?.toString().orEmpty()
            return@withContext NodeLookup(matchNode, rect, label, matchNode.packageName?.toString() ?: pkg)
        }
        if (!ref.isNullOrBlank()) {
            val cached = cachedRefs[ref.trim()]
            if (cached != null) {
                val atRect = findNodeNearRect(root, cached)
                val label = (atRect?.text ?: atRect?.contentDescription)?.toString().orEmpty()
                return@withContext NodeLookup(atRect, cached, label, atRect?.packageName?.toString() ?: pkg)
            }
        }
        null
    }

    suspend fun performClickAt(
        x: Float,
        y: Float,
        doubleClick: Boolean = false,
        longPress: Boolean = false,
        label: String = "",
        targetBounds: Rect? = null,
    ): Boolean = withContext(Dispatchers.Main) {
        val root = rootInActiveWindow ?: return@withContext false
        val pkg = root.packageName?.toString().orEmpty()
        if (PhoneScope.blocked(this@PhoneObservationService, pkg)) return@withContext false

        val cfg = PhoneControlPrefs.config.value
        val tapX = if (targetBounds != null && !targetBounds.isEmpty) {
            PhoneControlPrefs.jitterCoordinateInBounds(x, targetBounds.left, targetBounds.right, cfg.randomClickDelayEnabled)
        } else {
            x
        }
        val tapY = if (targetBounds != null && !targetBounds.isEmpty) {
            PhoneControlPrefs.jitterCoordinateInBounds(y, targetBounds.top, targetBounds.bottom, cfg.randomClickDelayEnabled)
        } else {
            y
        }

        val preDelay = PhoneControlPrefs.nextRandomPreClickDelayMs(cfg)
        if (preDelay > 0L) {
            kotlinx.coroutines.delay(preDelay)
        }

        overlay?.showTap(
            x = tapX,
            y = tapY,
            label = label,
            doubleTap = doubleClick,
            longPress = longPress,
            targetBounds = targetBounds?.let(::RectF),
        )
        if (cfg.showActionAnimation) {
            kotlinx.coroutines.delay(110L)
        }
        if (doubleClick) {
            val hold1 = (PhoneControlPrefs.nextRandomTapHoldMs(cfg) * 0.75f).toLong().coerceIn(70L, 115L)
            val hold2 = (PhoneControlPrefs.nextRandomTapHoldMs(cfg) * 0.75f).toLong().coerceIn(70L, 115L)
            val gap = if (cfg.randomClickDelayEnabled) kotlin.random.Random.nextLong(90L, 135L) else 100L
            val first = dispatchSingleStroke(tapX, tapY, tapX, tapY, hold1)
            if (!first) return@withContext false
            kotlinx.coroutines.delay(gap)
            dispatchSingleStroke(tapX, tapY, tapX, tapY, hold2)
        } else {
            val dur = if (longPress) {
                if (cfg.randomClickDelayEnabled) kotlin.random.Random.nextLong(720L, 860L) else 760L
            } else {
                PhoneControlPrefs.nextRandomTapHoldMs(cfg)
            }
            dispatchSingleStroke(tapX, tapY, tapX, tapY, dur)
        }
    }

    suspend fun performNodeClick(
        node: AccessibilityNodeInfo,
        longPress: Boolean,
        preferGesture: Boolean = false,
    ): Boolean =
        withContext(Dispatchers.Main) {
            val pkg = node.packageName?.toString().orEmpty()
            if (PhoneScope.blocked(this@PhoneObservationService, pkg)) return@withContext false
            if (!preferGesture) {
                var target: AccessibilityNodeInfo? = node
                var depth = 0
                while (target != null && depth++ < 6) {
                    val actionable = if (longPress) target.isLongClickable else target.isClickable
                    if (actionable) {
                        val action = if (longPress) {
                            AccessibilityNodeInfo.ACTION_LONG_CLICK
                        } else {
                            AccessibilityNodeInfo.ACTION_CLICK
                        }
                        val rect = Rect()
                        target.getBoundsInScreen(rect)
                        val label = (target.text ?: target.contentDescription)?.toString().orEmpty()
                        val cfg = PhoneControlPrefs.config.value
                        val preDelay = PhoneControlPrefs.nextRandomPreClickDelayMs(cfg)
                        if (preDelay > 0L) {
                            kotlinx.coroutines.delay(preDelay)
                        }
                        overlay?.showTap(
                            x = rect.exactCenterX(),
                            y = rect.exactCenterY(),
                            label = label.ifBlank { "点击" },
                            longPress = longPress,
                            targetBounds = if (!rect.isEmpty) RectF(rect) else null,
                        )
                        if (cfg.showActionAnimation) {
                            kotlinx.coroutines.delay(110L)
                        }
                        val beforeSig = captureScreenSnapshot(60).getOrNull()?.signature.orEmpty()
                        val acted = target.performAction(action)
                        if (acted) {
                            // 许多电商/WebView/自定义组件对 ACTION_CLICK 返回 true 但实际只响应真实触摸手势；
                            // 等 120ms 核验窗口签名，若界面毫无变化且有合法屏幕坐标，则自动补发一次真实触摸按压。
                            if (!longPress && !rect.isEmpty && rect.width() > 4 && rect.height() > 4) {
                                kotlinx.coroutines.delay(120L)
                                val afterSig = captureScreenSnapshot(60).getOrNull()?.signature.orEmpty()
                                if (beforeSig.isNotEmpty() && afterSig == beforeSig) {
                                    val tapX = PhoneControlPrefs.jitterCoordinateInBounds(
                                        rect.exactCenterX(),
                                        rect.left,
                                        rect.right,
                                        cfg.randomClickDelayEnabled,
                                    )
                                    val tapY = PhoneControlPrefs.jitterCoordinateInBounds(
                                        rect.exactCenterY(),
                                        rect.top,
                                        rect.bottom,
                                        cfg.randomClickDelayEnabled,
                                    )
                                    dispatchSingleStroke(
                                        tapX,
                                        tapY,
                                        tapX,
                                        tapY,
                                        PhoneControlPrefs.nextRandomTapHoldMs(cfg),
                                    )
                                }
                            }
                            return@withContext true
                        }
                        break
                    }
                    target = target.parent
                }
            }
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.isEmpty) return@withContext false
            performClickAt(
                x = rect.exactCenterX(),
                y = rect.exactCenterY(),
                doubleClick = false,
                longPress = longPress,
                label = (node.text ?: node.contentDescription)?.toString().orEmpty(),
                targetBounds = rect,
            )
        }

    suspend fun performSwipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long = 350L,
        label: String = "滑动",
    ): Boolean = withContext(Dispatchers.Main) {
        val root = rootInActiveWindow ?: return@withContext false
        val pkg = root.packageName?.toString().orEmpty()
        if (PhoneScope.blocked(this@PhoneObservationService, pkg)) return@withContext false
        overlay?.showSwipe(x1, y1, x2, y2, durationMs, label)
        dispatchSingleStroke(x1, y1, x2, y2, durationMs.coerceIn(60L, 2500L))
    }

    suspend fun performScroll(
        direction: String,
        node: AccessibilityNodeInfo? = null,
    ): Boolean = withContext(Dispatchers.Main) {
        val root = rootInActiveWindow ?: return@withContext false
        val pkg = root.packageName?.toString().orEmpty()
        if (PhoneScope.blocked(this@PhoneObservationService, pkg)) return@withContext false

        val scrollable = findScrollableNode(node ?: root)
        val dir = direction.lowercase()
        if (scrollable != null) {
            val action = when (dir) {
                "up", "backward", "left" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            if (scrollable.performAction(action)) {
                val r = Rect()
                scrollable.getBoundsInScreen(r)
                if (!r.isEmpty) {
                    val cx = r.exactCenterX()
                    val cy = r.exactCenterY()
                    val dy = r.height() * 0.25f
                    val dx = r.width() * 0.25f
                    val (sx1, sy1, sx2, sy2) = when (dir) {
                        "up", "backward" -> listOf(cx, cy - dy, cx, cy + dy)
                        "left" -> listOf(cx - dx, cy, cx + dx, cy)
                        "right" -> listOf(cx + dx, cy, cx - dx, cy)
                        else -> listOf(cx, cy + dy, cx, cy - dy)
                    }
                    overlay?.showSwipe(sx1, sy1, sx2, sy2, 340L, "滚动 $dir")
                } else {
                    overlay?.showBadge("已滚动 ($dir)")
                }
                return@withContext true
            }
        }
        val dm = resources.displayMetrics
        val cx = dm.widthPixels * 0.5f
        val cy = dm.heightPixels * 0.5f
        val dy = dm.heightPixels * 0.28f
        val dx = dm.widthPixels * 0.32f
        val (x1, y1, x2, y2) = when (dir) {
            "up", "backward" -> listOf(cx, cy - dy, cx, cy + dy)
            "left" -> listOf(cx - dx, cy, cx + dx, cy)
            "right" -> listOf(cx + dx, cy, cx - dx, cy)
            else -> listOf(cx, cy + dy, cx, cy - dy)
        }
        performSwipe(x1, y1, x2, y2, 380L, "滚动 $dir")
    }

    suspend fun performInputAction(
        action: String,
        targetNode: AccessibilityNodeInfo?,
        text: String? = null,
        selectionStart: Int = 0,
        selectionEnd: Int = 0,
    ): Result<String> = withContext(Dispatchers.Main) {
        val root = rootInActiveWindow
            ?: return@withContext Result.failure(IllegalStateException("无活动窗口"))
        val pkg = root.packageName?.toString().orEmpty()
        if (PhoneScope.blocked(this@PhoneObservationService, pkg)) {
            return@withContext Result.failure(IllegalStateException("目标应用在黑名单内"))
        }
        val act = action.lowercase()
        val node = findEditableTarget(targetNode ?: root) ?: targetNode
        if (node == null) {
            if (act in setOf("set_text", "append", "paste") && !text.isNullOrEmpty()) {
                copyToClipboard(text)
                return@withContext Result.failure(
                    IllegalStateException("当前窗口未找到可编辑输入框；已将文本写入系统剪贴板，请先点击聚焦输入框后粘贴（或长按输入框点击「粘贴」）"),
                )
            }
            return@withContext Result.failure(IllegalStateException("当前窗口未找到可编辑输入框"))
        }

        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.isEmpty) {
            overlay?.showTap(
                x = rect.exactCenterX(),
                y = rect.exactCenterY(),
                label = "输入：$act",
                targetBounds = RectF(rect),
            )
        }
        if (node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        when (act) {
            "clear" -> {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                }
                val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                if (ok) Result.success("已清空输入框")
                else Result.failure(IllegalStateException("控件不支持直接清空"))
            }
            "set_text" -> {
                val value = text ?: ""
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                }
                if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    Result.success("已完成输入操作：set_text")
                } else if (value.isNotEmpty()) {
                    val (clipOk, pasted) = tryClipboardPaste(node, value, replaceExisting = true)
                    when {
                        pasted -> Result.success("控件不支持直接 set_text，已自动改用剪贴板粘贴填入文本")
                        clipOk -> Result.failure(
                            IllegalStateException("控件未响应 set_text 与无障碍粘贴；文本已写入系统剪贴板，请长按输入框并点击弹出的「粘贴」"),
                        )
                        else -> Result.failure(IllegalStateException("控件未能执行输入，且写入系统剪贴板失败"))
                    }
                } else {
                    Result.failure(IllegalStateException("控件未能执行 set_text 清空操作"))
                }
            }
            "append" -> {
                val existing = if (node.isPassword) "" else node.text?.toString().orEmpty()
                val appendText = text ?: ""
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        existing + appendText,
                    )
                }
                if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    Result.success("已完成追加文本：append")
                } else if (appendText.isNotEmpty()) {
                    val (clipOk, pasted) = tryClipboardPaste(node, appendText, replaceExisting = false)
                    when {
                        pasted -> Result.success("已通过剪贴板粘贴追加文本")
                        clipOk -> Result.failure(
                            IllegalStateException("控件未响应追加输入与无障碍粘贴；追加内容已写入系统剪贴板，请长按输入框并点击「粘贴」"),
                        )
                        else -> Result.failure(IllegalStateException("控件未能执行 append 操作"))
                    }
                } else {
                    Result.failure(IllegalStateException("控件未能执行 append 操作"))
                }
            }
            "select_all" -> {
                val len = node.text?.length ?: 0
                val args = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len)
                }
                if (node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)) {
                    Result.success("已全选文本")
                } else {
                    Result.failure(IllegalStateException("控件未响应全选"))
                }
            }
            "select_range" -> {
                val len = node.text?.length ?: 0
                val s = selectionStart.coerceIn(0, len)
                val e = selectionEnd.coerceIn(s, len)
                val args = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, s)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, e)
                }
                if (node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)) {
                    Result.success("已选中文本范围 [$s, $e]")
                } else {
                    Result.failure(IllegalStateException("控件未响应选区设置"))
                }
            }
            "copy" -> {
                if (node.performAction(AccessibilityNodeInfo.ACTION_COPY)) {
                    Result.success("已复制选中内容到剪贴板")
                } else {
                    Result.failure(IllegalStateException("控件未响应复制操作"))
                }
            }
            "paste" -> {
                val clipOk = if (text != null) copyToClipboard(text) else true
                val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                when {
                    pasted -> Result.success("已通过剪贴板粘贴填入")
                    clipOk && text != null -> Result.failure(
                        IllegalStateException("控件未响应无障碍 ACTION_PASTE；文本已写入系统剪贴板，请长按输入框并点击「粘贴」"),
                    )
                    else -> Result.failure(IllegalStateException("控件未响应粘贴操作"))
                }
            }
            "submit" -> {
                val ok = if (Build.VERSION.SDK_INT >= 30) {
                    node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                } else {
                    false
                }
                if (ok) Result.success("已执行输入确认 submit")
                else Result.failure(IllegalStateException("控件不支持直接触发回车提交，请改为点击页面内的搜索/确认/发送按钮"))
            }
            else -> Result.failure(IllegalArgumentException("不支持的输入动作：$action"))
        }
    }

    private fun copyToClipboard(value: String): Boolean = runCatching {
        val cm = getSystemService(ClipboardManager::class.java) ?: return false
        cm.setPrimaryClip(ClipData.newPlainText("codeforge_input", value))
        true
    }.getOrDefault(false)

    private fun tryClipboardPaste(
        node: AccessibilityNodeInfo,
        value: String,
        replaceExisting: Boolean,
    ): Pair<Boolean, Boolean> {
        val clipOk = copyToClipboard(value)
        if (!clipOk) return false to false
        if (replaceExisting) {
            val len = node.text?.length ?: 0
            if (len > 0) {
                val args = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
            }
        } else {
            val len = node.text?.length ?: 0
            if (len > 0) {
                val args = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, len)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
            }
        }
        val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        return true to pasted
    }

    suspend fun performGlobal(action: String): Result<String> = withContext(Dispatchers.Main) {
        val pkg = foregroundPackage()
        if (PhoneScope.blocked(this@PhoneObservationService, pkg) &&
            action.lowercase() !in setOf("back", "home", "recents")
        ) {
            return@withContext Result.failure(IllegalStateException("当前应用在黑名单内"))
        }
        val code = when (action.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "power_dialog" -> GLOBAL_ACTION_POWER_DIALOG
            "lock_screen" -> if (Build.VERSION.SDK_INT >= 28) {
                GLOBAL_ACTION_LOCK_SCREEN
            } else {
                return@withContext Result.failure(IllegalStateException("锁屏快捷操作需要 Android 9+"))
            }
            else -> return@withContext Result.failure(IllegalArgumentException("未知全局动作：$action"))
        }
        overlay?.showBadge("全局操作：$action")
        val ok = performGlobalAction(code)
        if (ok) Result.success("已执行全局动作：$action")
        else Result.failure(IllegalStateException("系统未接受全局动作：$action"))
    }

    /** 兼容旧调用的快捷手势方法。 */
    internal fun performGesture(x: Float, y: Float, endX: Float, endY: Float, duration: Long): Boolean {
        val root = rootInActiveWindow ?: return false
        val pkg = root.packageName?.toString() ?: return false
        if (PhoneScope.blocked(this, pkg)) return false
        val bounds = Rect()
        root.getBoundsInScreen(bounds)
        if (!bounds.contains(x.toInt(), y.toInt()) || !bounds.contains(endX.toInt(), endY.toInt())) return false
        val path = Path().apply { moveTo(x, y); lineTo(endX, endY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration.coerceIn(40, 2000)))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    internal fun showNotifications(): Boolean {
        val pkg = rootInActiveWindow?.packageName?.toString() ?: return false
        if (PhoneScope.blocked(this, pkg)) return false
        return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    }

    private class ScreenshotFailedException(val code: Int, message: String) : IllegalStateException(message)

    /**
     * 截取当前屏幕或窗口的软件 ARGB_8888 Bitmap。
     *
     * 截图前隐藏自绘操作动画层，遵守 Android 11+ 连续截图最小间隔冷却（失败时重试一次），
     * 并在截图前后均检查前台应用是否在黑名单中。
     */
    suspend fun captureScreenshotBitmap(scope: String = "screen"): Result<Bitmap> {
        val prePkg = foregroundPackage()
        if (PhoneScope.blocked(this, prePkg)) {
            val label = PhoneScope.appLabel(this, prePkg)
            PhoneTaskController.stop("前台应用「$label」在黑名单内，已拒绝截图")
            return Result.failure(IllegalStateException("前台应用「$label」在黑名单内"))
        }
        if (isDeviceLocked()) {
            return Result.failure(IllegalStateException("设备处于锁屏状态，无法截图"))
        }
        if (Build.VERSION.SDK_INT < 30) {
            val fallback = PhoneMediaController.captureProjectionFrame()
            return if (fallback != null) {
                Result.success(fallback)
            } else {
                Result.failure(IllegalStateException("Android 10 及以下截图需先授权屏幕捕获（MediaProjection）"))
            }
        }

        // Android 11+ 对连续 takeScreenshot 强制约 1 秒最小间隔，否则返回错误码 3
        val preElapsed = SystemClock.elapsedRealtime() - lastScreenshotElapsedMs
        if (lastScreenshotElapsedMs > 0L && preElapsed in 0L until 1_100L) {
            kotlinx.coroutines.delay(1_100L - preElapsed)
        }

        overlay?.setTemporarilyHidden(true)
        kotlinx.coroutines.delay(50L)
        return try {
            var result = captureAccessibilityBitmapOnce(scope)
            val err = result.exceptionOrNull()
            if (err is ScreenshotFailedException && err.code == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                kotlinx.coroutines.delay(1_150L)
                result = captureAccessibilityBitmapOnce(scope)
            }
            val postPkg = foregroundPackage()
            if (PhoneScope.blocked(this, postPkg)) {
                result.getOrNull()?.recycle()
                PhoneTaskController.stop("截图瞬间进入黑名单应用「$postPkg」，已丢弃截图并停止任务")
                Result.failure(IllegalStateException("截图过程中进入黑名单应用，已丢弃"))
            } else {
                result
            }
        } finally {
            overlay?.setTemporarilyHidden(false)
        }
    }

    @androidx.annotation.RequiresApi(30)
    private suspend fun captureAccessibilityBitmapOnce(scope: String): Result<Bitmap> {
        lastScreenshotElapsedMs = SystemClock.elapsedRealtime()
        val deferred = CompletableDeferred<Result<Bitmap>>()
        val useWindow = scope.equals("window", ignoreCase = true) && Build.VERSION.SDK_INT >= 34
        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val bmp = screenshot.hardwareBuffer.use { buffer ->
                    Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                }
                if (bmp == null) {
                    deferred.complete(Result.failure(IllegalStateException("系统截图缓冲区解析失败")))
                } else {
                    deferred.complete(Result.success(bmp))
                }
            }

            override fun onFailure(errorCode: Int) {
                deferred.complete(Result.failure(ScreenshotFailedException(errorCode, screenshotErrorMessage(errorCode))))
            }
        }
        withContext(Dispatchers.Main) {
            if (useWindow) {
                val winId = rootInActiveWindow?.windowId
                if (winId != null) {
                    takeScreenshotOfWindow(winId, mainExecutor, callback)
                } else {
                    takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
                }
            } else {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
            }
        }
        return withTimeoutOrNull(6_000L) { deferred.await() }
            ?: Result.failure(IllegalStateException("截图超时"))
    }

    @androidx.annotation.RequiresApi(30)
    private fun screenshotErrorMessage(errorCode: Int): String = when (errorCode) {
        ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "系统截图内部错误（错误码 1）"
        ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "无障碍截图权限不可用（错误码 2）"
        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "系统截图调用过于频繁，请稍后重试（错误码 3）"
        ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "目标显示器无效（错误码 4）"
        ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> "目标窗口无效或已关闭（错误码 5）"
        ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "当前页面为系统受保护窗口（FLAG_SECURE），已禁止截图（错误码 6）"
        else -> "系统拒绝无障碍截图（错误码 $errorCode）"
    }

    private suspend fun dispatchSingleStroke(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long,
    ): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            if (abs(x2 - x1) > 1f || abs(y2 - y1) > 1f) {
                lineTo(x2, y2)
            }
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(40L, 2500L)))
            .build()
        val deferred = CompletableDeferred<Boolean>()
        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    deferred.complete(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    deferred.complete(false)
                }
            },
            handler,
        )
        if (!dispatched) return false
        return withTimeoutOrNull(durationMs + 2_500L) { deferred.await() } ?: false
    }

    private fun findMatchingNodes(
        root: AccessibilityNodeInfo,
        text: String?,
        contentDesc: String?,
        resourceId: String?,
    ): List<AccessibilityNodeInfo> {
        val matches = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 500) {
            val n = queue.removeFirst()
            if (n.isVisibleToUser) {
                val nText = n.text?.toString().orEmpty()
                val nDesc = n.contentDescription?.toString().orEmpty()
                val nId = n.viewIdResourceName.orEmpty()
                val okText = text.isNullOrBlank() || nText.contains(text, ignoreCase = true)
                val okDesc = contentDesc.isNullOrBlank() || nDesc.contains(contentDesc, ignoreCase = true)
                val okId = resourceId.isNullOrBlank() ||
                    nId.equals(resourceId, ignoreCase = true) ||
                    nId.endsWith(":id/$resourceId", ignoreCase = true)
                if (okText && okDesc && okId) {
                    matches.add(n)
                }
            }
            for (i in 0 until minOf(n.childCount, 40)) {
                n.getChild(i)?.let(queue::addLast)
            }
        }
        return matches
    }

    private fun findNodeNearRect(root: AccessibilityNodeInfo, target: Rect): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var best: AccessibilityNodeInfo? = null
        var bestDist = Int.MAX_VALUE
        var visited = 0
        val r = Rect()
        while (queue.isNotEmpty() && visited++ < 400) {
            val n = queue.removeFirst()
            if (n.isVisibleToUser) {
                n.getBoundsInScreen(r)
                val d = abs(r.left - target.left) + abs(r.top - target.top) +
                    abs(r.right - target.right) + abs(r.bottom - target.bottom)
                if (d == 0) return n
                if (d < bestDist && d < 32) {
                    bestDist = d
                    best = n
                }
            }
            for (i in 0 until minOf(n.childCount, 40)) {
                n.getChild(i)?.let(queue::addLast)
            }
        }
        return best
    }

    private fun findScrollableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 250) {
            val n = queue.removeFirst()
            if (n.isVisibleToUser && n.isScrollable) return n
            for (i in 0 until minOf(n.childCount, 40)) {
                n.getChild(i)?.let(queue::addLast)
            }
        }
        return null
    }

    private fun findEditableTarget(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isEditable && root.isVisibleToUser) return root
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable && focused.isVisibleToUser) return focused
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 300) {
            val n = queue.removeFirst()
            if (n.isVisibleToUser && n.isEditable) return n
            for (i in 0 until minOf(n.childCount, 40)) {
                n.getChild(i)?.let(queue::addLast)
            }
        }
        return null
    }

    private fun computeSnapshotSignature(pkg: String, windowId: Int, nodes: List<UiNodeSnapshot>): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("$pkg|$windowId|${nodes.size}".toByteArray())
        nodes.take(60).forEach { n ->
            md.update("|${n.className}:${n.resourceId}:${n.text}:${n.contentDesc}:${n.checked}".toByteArray())
        }
        return md.digest().take(8).joinToString("") { "%02x".format(it) }
    }

    override fun onInterrupt() {
        finish("系统已中断检查")
    }

    override fun onDestroy() {
        serviceScope?.cancel()
        serviceScope = null
        overlay?.detach()
        overlay = null
        if (active === this) active = null
        finish("无障碍服务已停止")
        super.onDestroy()
    }
}
