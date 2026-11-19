package com.hxy.codeforge.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 手机操控工具统一分发器（MCP `tools/call` → 策略检查 → 能力执行 → 动画/记录收口）。
 *
 * 每一条工具调用都经过同一套防线：
 * 1. 总开关校验（`masterEnabled`）；
 * 2. 任务控制状态（暂停等待 / 立即停止拦截）；
 * 3. 对应能力域开关校验；
 * 4. 实时前台黑名单检查（进入黑名单应用即停止并拒绝返回）；
 * 5. 敏感动作人工确认门禁（发送消息、删除文件、支付/转账、拨号、关闭应用、修改系统设置）。
 */
object PhoneToolDispatcher {

    private const val MAX_OUTPUT_CHARS = 22_000

    private val server: PhoneMcpServer by lazy {
        PhoneMcpServer { toolName, args -> dispatch(toolName, args) }
    }

    fun startServer(): String {
        val endpoint = server.start()
        PhoneRuntime.mcpStatus.value = PhoneMcpStatus.Running(
            port = server.port,
            endpoint = endpoint,
            toolCount = PhoneToolRegistry.tools.size,
        )
        return endpoint
    }

    fun stopServer() {
        server.stop()
        PhoneRuntime.mcpStatus.value = PhoneMcpStatus.Stopped
    }

    fun currentEndpoint(): String = server.endpoint

    fun isServerRunning(): Boolean = server.isRunning

    /**
     * 核心工具路由。在真机执行全量策略检查与能力分发。
     */
    suspend fun dispatch(toolName: String, args: JSONObject): PhoneMcpServer.ToolCallOutcome {
        val ctx = PhoneRuntime.appContext
            ?: return fail("宿主上下文尚未初始化")
        val cfg = PhoneControlPrefs.config.value
        val def = PhoneToolRegistry.find(toolName)
            ?: return fail("未知手机操控工具：$toolName")

        // `task_control` 不受总开关外层 pause 阻塞，确保模型随时可查/停状态
        if (toolName == "task_control") {
            return executeTaskControl(args)
        }

        if (!cfg.masterEnabled) {
            return fail("手机操控总开关未启用，请先在「设置 → 手机操控」中打开总开关")
        }

        val blockedReason = PhoneTaskController.awaitRunnableOrReason()
        if (blockedReason != null) {
            return fail(blockedReason)
        }

        def.requiredCapability?.let { cap ->
            if (!cfg.isCapabilityEnabled(cap)) {
                return fail("能力「${cap.label}」未启用，请在「设置 → 手机操控 → 能力开关」中打开")
            }
        }

        val startedMs = System.currentTimeMillis()
        val summary = describeToolAction(toolName, args)
        val fgPkg = PhoneObservationService.active?.foregroundPackage().orEmpty()
        val fgLabel = if (fgPkg.isNotBlank()) PhoneScope.appLabel(ctx, fgPkg) else ""

        // 在触摸或读屏前强制检查当前前台应用黑名单
        if (toolName in SCREEN_FRONT_TOOLS && fgPkg.isNotBlank() && PhoneScope.blocked(ctx, fgPkg)) {
            val msg = "当前前台应用「$fgLabel ($fgPkg)」在禁止操控黑名单内，已停止操作"
            PhoneTaskController.stop(msg)
            PhoneObservationService.active?.showOverlayBadge("命中黑名单：$fgLabel", isError = true)
            return fail(msg)
        }

        PhoneTaskController.beginAction(toolName, summary, fgLabel.ifBlank { fgPkg })

        var artifactPath = ""
        val result: Result<JSONObject> = runCatching {
            when (toolName) {
                "screen_observe" -> executeScreenObserve(ctx, args)
                "screen_wait_change" -> executeScreenWaitChange(ctx, args)
                "ui_click" -> executeUiClick(ctx, cfg, args)
                "ui_gesture" -> executeUiGesture(ctx, args)
                "ui_input" -> executeUiInput(ctx, cfg, args)
                "ui_batch" -> executeUiBatch(ctx, cfg, args)
                "ui_global_action" -> executeGlobalAction(args)
                "app_manage" -> executeAppManage(ctx, cfg, args)
                "system_setting" -> executeSystemSetting(ctx, cfg, args)
                "screenshot" -> executeScreenshot(ctx, args)
                "screen_analyze" -> executeScreenAnalyze(ctx, args)
                "screen_record" -> executeScreenRecord(ctx, args)
                "audio_record" -> executeAudioRecord(ctx, args)
                "notifications" -> executeNotifications(ctx, args)
                "file_media" -> executeFileMedia(ctx, cfg, args)
                "calendar_reminder" -> executeCalendarReminder(ctx, args)
                "communication" -> executeCommunication(ctx, cfg, args)
                "device_status" -> Result.success(PhoneSystemCapabilities.deviceStatus(ctx))
                "app_skill" -> executeAppSkill(args)
                else -> Result.failure(IllegalArgumentException("未实现工具：$toolName"))
            }
        }.getOrElse { Result.failure(it) }

        val duration = (System.currentTimeMillis() - startedMs).coerceAtLeast(1L)
        return result.fold(
            onSuccess = { payload ->
                artifactPath = payload.optString("workspace_path")
                val detail = payload.optString("summary")
                    .ifBlank { payload.optString("message") }
                    .ifBlank { "执行成功" }
                PhoneTaskController.finishAction(
                    tool = toolName,
                    summary = summary,
                    targetApp = fgLabel.ifBlank { fgPkg },
                    success = true,
                    detail = detail,
                    durationMs = duration,
                    artifactPath = artifactPath,
                )
                ok(payload)
            },
            onFailure = { err ->
                val msg = err.message ?: err.javaClass.simpleName
                PhoneTaskController.finishAction(
                    tool = toolName,
                    summary = summary,
                    targetApp = fgLabel.ifBlank { fgPkg },
                    success = false,
                    detail = msg,
                    durationMs = duration,
                    artifactPath = "",
                )
                fail(msg)
            },
        )
    }

    private val SCREEN_FRONT_TOOLS = setOf(
        "screen_observe",
        "screen_wait_change",
        "ui_click",
        "ui_gesture",
        "ui_input",
        "ui_batch",
        "screenshot",
        "screen_analyze",
    )

    // ---------------------------------------------------------------- 具体工具实现

    private fun executeTaskControl(args: JSONObject): PhoneMcpServer.ToolCallOutcome {
        val action = args.optString("action").lowercase().trim()
        val returnOverride = if (args.has("return_to_app") && !args.isNull("return_to_app")) {
            args.optBoolean("return_to_app")
        } else {
            null
        }
        when (action) {
            "report_step" -> {
                PhoneTaskController.reportStep(
                    args.optString("task_title").takeIf { it.isNotBlank() },
                    args.optString("step_summary"),
                )
            }
            "pause" -> PhoneTaskController.pause("由 Agent 请求暂停")
            "resume" -> PhoneTaskController.resume()
            "stop" -> {
                val summary = args.optString("step_summary").ifBlank { "由 Agent 停止手机操控任务" }
                PhoneTaskController.stop(summary, stopAgentTurn = false)
                if (returnOverride ?: PhoneControlPrefs.config.value.autoReturnToAppOnFinish) {
                    PhoneTaskController.bringCodeForgeToFront()
                }
            }
            "complete" -> {
                val summary = args.optString("step_summary").ifBlank { "手机操控任务已完成" }
                PhoneTaskController.completeByAgent(summary, returnOverride)
            }
            "status" -> Unit
            else -> return fail("不支持的 task_control 动作：$action")
        }
        val st = PhoneTaskController.state.value
        val out = JSONObject().apply {
            put("status", st.status.name.lowercase())
            put("status_label", st.status.label)
            put("current_task", st.currentTask)
            put("current_action", st.currentAction)
            put("step_count", st.stepCount)
            put("last_result", st.lastResult)
        }
        return ok(out)
    }

    private suspend fun executeScreenObserve(ctx: Context, args: JSONObject): Result<JSONObject> {
        val service = PhoneObservationService.active
            ?: return Result.failure(IllegalStateException("无障碍服务未开启，请先在「设置 → 手机操控」中授权并启用「CodeForge 手机操控」无障碍服务"))
        val maxNodes = args.optInt("max_nodes", 120)
        val snap = service.captureScreenSnapshot(maxNodes).getOrElse { return Result.failure(it) }
        val json = snapshotToJson(snap)

        if (args.optBoolean("include_screenshot", false)) {
            if (!PhoneControlPrefs.config.value.isCapabilityEnabled(PhoneCapability.SCREENSHOT)) {
                json.put("screenshot_error", "截图能力开关已关闭")
            } else {
                val shot = captureAndSaveScreenshot(
                    ctx = ctx,
                    service = service,
                    scope = "screen",
                    analyze = args.optBoolean("analyze", false),
                    question = args.optString("question"),
                    drawGrid = true,
                    snap = snap,
                )
                shot.onSuccess { json.put("screenshot", it) }
                    .onFailure { json.put("screenshot_error", it.message) }
            }
        }
        attachMatchedSkills(json, snap.packageName, snap.appLabel)
        json.put("summary", "已读取「${snap.appLabel}」控件树（${snap.nodes.size} 个节点）")
        return Result.success(json)
    }

    private suspend fun executeScreenWaitChange(ctx: Context, args: JSONObject): Result<JSONObject> {
        val service = PhoneObservationService.active
            ?: return Result.failure(IllegalStateException("无障碍服务未开启"))
        val timeoutMs = args.optLong("timeout_ms", 5_000L).coerceIn(400L, 25_000L)
        val intervalMs = args.optLong("interval_ms", 350L).coerceIn(150L, 2_000L)
        val expectText = args.optString("expect_text").trim()
        val expectPkg = args.optString("expect_package").trim()

        val initial = service.captureScreenSnapshot(100).getOrNull()
        val baselineSig = initial?.signature.orEmpty()
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            delay(intervalMs)
            if (PhoneTaskController.state.value.status == PhoneTaskStatus.STOPPED) {
                return Result.failure(IllegalStateException("任务已被停止"))
            }
            val fg = service.foregroundPackage()
            if (PhoneScope.blocked(ctx, fg)) {
                val label = PhoneScope.appLabel(ctx, fg)
                PhoneTaskController.stop("等待期间进入黑名单应用「$label」")
                return Result.failure(IllegalStateException("等待期间进入黑名单应用「$label」"))
            }
            val current = service.captureScreenSnapshot(100).getOrNull() ?: continue
            val matchedPkg = expectPkg.isNotEmpty() && current.packageName.contains(expectPkg, ignoreCase = true)
            val matchedText = expectText.isNotEmpty() && current.nodes.any {
                it.text.contains(expectText, ignoreCase = true) ||
                    it.contentDesc.contains(expectText, ignoreCase = true)
            }
            val changed = expectPkg.isEmpty() && expectText.isEmpty() && current.signature != baselineSig

            if (matchedPkg || matchedText || changed) {
                val res = snapshotToJson(current)
                res.put("changed", true)
                attachMatchedSkills(res, current.packageName, current.appLabel)
                res.put("summary", "屏幕已变化至「${current.appLabel}」(${current.nodes.size} 个控件)")
                return Result.success(res)
            }
        }
        val finalSnap = service.captureScreenSnapshot(100).getOrNull()
        val out = if (finalSnap != null) snapshotToJson(finalSnap) else JSONObject()
        out.put("changed", false)
        out.put("timeout", true)
        out.put("summary", "在 ${timeoutMs}ms 内未检测到预期页面变化")
        return Result.success(out)
    }

    private suspend fun executeUiClick(
        ctx: Context,
        cfg: PhoneControlConfig,
        args: JSONObject,
    ): Result<JSONObject> {
        val service = PhoneObservationService.active
            ?: return Result.failure(IllegalStateException("无障碍服务未开启"))
        val action = args.optString("action", "click").lowercase().trim()
        val ref = args.optString("ref").takeIf { it.isNotBlank() }
        val text = args.optString("text").takeIf { it.isNotBlank() }
        val desc = args.optString("content_desc").takeIf { it.isNotBlank() }
        val resId = args.optString("resource_id").takeIf { it.isNotBlank() }
        val index = args.optInt("index", 0)
        val hasCoords = args.has("x") && args.has("y") && !args.isNull("x") && !args.isNull("y")

        val lookup = if (ref != null || text != null || desc != null || resId != null) {
            service.resolveTarget(ref, text, desc, resId, index)
                ?: if (!hasCoords) {
                    return Result.failure(IllegalStateException("未在当前屏幕找到匹配的控件，请重新调用 screen_observe 或改用像素坐标 x/y"))
                } else {
                    null
                }
        } else {
            null
        }

        val targetPkg = lookup?.packageName?.takeIf { it.isNotBlank() } ?: service.foregroundPackage()
        if (PhoneScope.blocked(ctx, targetPkg)) {
            return Result.failure(IllegalStateException("目标控件属于黑名单应用「$targetPkg」"))
        }

        val targetLabel = listOfNotNull(lookup?.label, text, desc, ref).firstOrNull { it.isNotBlank() }
            ?: if (hasCoords) "(${args.optDouble("x").toInt()}, ${args.optDouble("y").toInt()})" else "目标控件"

        // 支付与高风险确认门禁
        if (cfg.isConfirmationRequired(PhoneConfirmRule.PAYMENT) &&
            PhoneControlPrefs.isLikelyPaymentAction(targetLabel, targetPkg)
        ) {
            val outcome = PhoneConfirmationGate.request(
                context = ctx,
                rule = PhoneConfirmRule.PAYMENT,
                title = "即将点击疑似支付/转账控件「$targetLabel」",
                detail = "请确认是否允许继续执行此点击动作",
                targetApp = PhoneScope.appLabel(ctx, targetPkg),
            )
            if (outcome != PhoneConfirmationGate.Outcome.APPROVED) {
                return Result.failure(IllegalStateException("支付/转账确认未通过（$outcome）"))
            }
        }

        val success = when {
            hasCoords && lookup == null -> {
                val x = args.optDouble("x").toFloat()
                val y = args.optDouble("y").toFloat()
                service.performClickAt(
                    x = x,
                    y = y,
                    doubleClick = action == "double_click",
                    longPress = action == "long_press",
                    label = targetLabel,
                )
            }
            action == "double_click" -> {
                val rect = lookup?.bounds
                    ?: return Result.failure(IllegalStateException("无法解析目标控件坐标"))
                service.performClickAt(
                    x = rect.exactCenterX(),
                    y = rect.exactCenterY(),
                    doubleClick = true,
                    label = targetLabel,
                    targetBounds = rect,
                )
            }
            lookup?.node != null -> {
                service.performNodeClick(
                    node = lookup.node,
                    longPress = action == "long_press",
                    preferGesture = args.optBoolean("prefer_gesture", false),
                )
            }
            lookup?.bounds != null -> {
                val r = lookup.bounds
                service.performClickAt(
                    x = r.exactCenterX(),
                    y = r.exactCenterY(),
                    longPress = action == "long_press",
                    label = targetLabel,
                    targetBounds = r,
                )
            }
            else -> return Result.failure(IllegalArgumentException("请提供控件选择器（ref/text/content_desc/resource_id）或坐标 x, y"))
        }

        return if (success) {
            val out = JSONObject().apply {
                put("action", action)
                put("target", targetLabel)
                put("package", targetPkg)
                put("summary", "已$action：$targetLabel")
            }
            if (args.optBoolean("observe_after", false)) {
                attachPostActionObservation(service, out)
            }
            Result.success(out)
        } else {
            Result.failure(IllegalStateException("手势或点击未生效（$targetLabel）"))
        }
    }

    private suspend fun executeUiGesture(ctx: Context, args: JSONObject): Result<JSONObject> {
        val service = PhoneObservationService.active
            ?: return Result.failure(IllegalStateException("无障碍服务未开启"))
        val action = args.optString("action", "swipe").lowercase().trim()
        val direction = args.optString("direction").lowercase().trim()
        val ref = args.optString("ref").takeIf { it.isNotBlank() }

        if (action == "scroll") {
            val lookup = if (ref != null) service.resolveTarget(ref = ref) else null
            val ok = service.performScroll(direction.ifBlank { "down" }, lookup?.node)
            return if (ok) {
                val out = JSONObject()
                    .put("action", "scroll")
                    .put("direction", direction.ifBlank { "down" })
                    .put("summary", "已执行滚动")
                if (args.optBoolean("observe_after", false)) {
                    attachPostActionObservation(service, out)
                }
                Result.success(out)
            } else {
                Result.failure(IllegalStateException("滚动未生效"))
            }
        }

        val dm = ctx.resources.displayMetrics
        val defaultDur = if (action == "drag") 850L else 350L
        val dur = args.optLong("duration_ms", defaultDur).coerceIn(80L, 2500L)

        val (x1, y1, x2, y2) = if (args.has("start_x") && args.has("start_y") && args.has("end_x") && args.has("end_y")) {
            listOf(
                args.optDouble("start_x").toFloat(),
                args.optDouble("start_y").toFloat(),
                args.optDouble("end_x").toFloat(),
                args.optDouble("end_y").toFloat(),
            )
        } else {
            val lookup = if (ref != null) service.resolveTarget(ref = ref) else null
            val cx = lookup?.bounds?.exactCenterX() ?: (dm.widthPixels * 0.5f)
            val cy = lookup?.bounds?.exactCenterY() ?: (dm.heightPixels * 0.5f)
            val dy = dm.heightPixels * 0.28f
            val dx = dm.widthPixels * 0.34f
            when (direction) {
                "up" -> listOf(cx, cy + dy, cx, cy - dy)
                "down" -> listOf(cx, cy - dy, cx, cy + dy)
                "left" -> listOf(cx + dx, cy, cx - dx, cy)
                "right" -> listOf(cx - dx, cy, cx + dx, cy)
                else -> return Result.failure(IllegalArgumentException("请提供 direction 或 start_x/start_y/end_x/end_y"))
            }
        }

        val ok = service.performSwipe(x1, y1, x2, y2, dur, if (action == "drag") "拖拽" else "滑动")
        return if (ok) {
            val out = JSONObject()
                .put("action", action)
                .put("from", JSONArray(listOf(x1.toInt(), y1.toInt())))
                .put("to", JSONArray(listOf(x2.toInt(), y2.toInt())))
                .put("summary", "已执行$action")
            if (args.optBoolean("observe_after", false)) {
                attachPostActionObservation(service, out)
            }
            Result.success(out)
        } else {
            Result.failure(IllegalStateException("$action 手势未执行"))
        }
    }

    private suspend fun executeUiInput(
        ctx: Context,
        cfg: PhoneControlConfig,
        args: JSONObject,
    ): Result<JSONObject> {
        val service = PhoneObservationService.active
            ?: return Result.failure(IllegalStateException("无障碍服务未开启"))
        val action = args.optString("action").lowercase().trim()
        val text = if (args.has("text") && !args.isNull("text")) args.optString("text") else null
        val ref = args.optString("ref").takeIf { it.isNotBlank() }
        val resId = args.optString("resource_id").takeIf { it.isNotBlank() }
        val hint = args.optString("hint").takeIf { it.isNotBlank() }

        val lookup = if (ref != null || resId != null || hint != null) {
            service.resolveTarget(ref = ref, text = hint, contentDesc = hint, resourceId = resId)
        } else {
            null
        }
        val fg = lookup?.packageName?.takeIf { it.isNotBlank() } ?: service.foregroundPackage()
        if (PhoneScope.blocked(ctx, fg)) {
            return Result.failure(IllegalStateException("目标窗口在黑名单内"))
        }

        if (action == "submit" &&
            cfg.isConfirmationRequired(PhoneConfirmRule.PAYMENT) &&
            PhoneControlPrefs.isLikelyPaymentAction(lookup?.label.orEmpty() + " " + text.orEmpty(), fg)
        ) {
            val outcome = PhoneConfirmationGate.request(
                context = ctx,
                rule = PhoneConfirmRule.PAYMENT,
                title = "即将提交疑似支付/敏感表单",
                detail = "是否允许执行 submit？",
                targetApp = PhoneScope.appLabel(ctx, fg),
            )
            if (outcome != PhoneConfirmationGate.Outcome.APPROVED) {
                return Result.failure(IllegalStateException("支付提交确认未通过（$outcome）"))
            }
        }

        return service.performInputAction(
            action = action,
            targetNode = lookup?.node,
            text = text,
            selectionStart = args.optInt("start", 0),
            selectionEnd = args.optInt("end", 0),
        ).map { msg ->
            val out = JSONObject().put("action", action).put("summary", msg)
            if (args.optBoolean("observe_after", false)) {
                attachPostActionObservation(service, out)
            }
            out
        }
    }

    /**
     * 执行同一页面内的连续确定性动作链（`ui_batch`）。
     *
     * 核心防误触保证：
     * 1. 每步前检查任务停止态、黑名单与 `stay_in_package`（若上一步已导致跨应用跳转则立即截断）；
     * 2. 支持 `wait_for_text` 等待目标控件出现且可用；
     * 3. 每步后执行动态 UI 稳定轮询（`awaitUiSettledAndObserve`），避免动画未结束就点下一步；
     * 4. 支持 `expect_text` 与 `expect_selected` 断言（筛选标签未选中时自动用真实触摸手势补按一次，仍未选中则立即熔断，绝不盲点「确定」）。
     */
    private suspend fun executeUiBatch(
        ctx: Context,
        cfg: PhoneControlConfig,
        args: JSONObject,
    ): Result<JSONObject> {
        val service = PhoneObservationService.active
            ?: return Result.failure(IllegalStateException("无障碍服务未开启"))
        val stepsArr = args.optJSONArray("steps")
            ?: return Result.failure(IllegalArgumentException("ui_batch 需要提供 steps 数组"))
        if (stepsArr.length() == 0) {
            return Result.failure(IllegalArgumentException("steps 数组不能为空"))
        }
        val total = stepsArr.length().coerceAtMost(10)
        val stayInPackage = args.optBoolean("stay_in_package", true)
        val settleTimeoutMs = args.optLong("settle_timeout_ms", 850L).coerceIn(200L, 4_000L)
        val observeAfter = args.optBoolean("observe_after", true)

        val startPkg = service.foregroundPackage()
        val executedLogs = JSONArray()
        var abortedStepIndex = -1
        var abortReason = ""
        var latestSnap: ScreenSnapshot? = null

        for (i in 0 until total) {
            if (PhoneTaskController.state.value.status == PhoneTaskStatus.STOPPED) {
                abortedStepIndex = i
                abortReason = "任务已被停止"
                break
            }
            val step = stepsArr.optJSONObject(i) ?: continue
            val type = step.optString("type", "click").lowercase().trim()

            val curPkg = service.foregroundPackage()
            if (curPkg.isNotBlank() && PhoneScope.blocked(ctx, curPkg)) {
                val label = PhoneScope.appLabel(ctx, curPkg)
                PhoneTaskController.stop("批量操作期间进入黑名单应用「$label」")
                return Result.failure(IllegalStateException("进入黑名单应用「$label」，已停止"))
            }
            if (stayInPackage && i > 0 && startPkg.isNotBlank() && curPkg.isNotBlank() && curPkg != startPkg) {
                abortedStepIndex = i
                abortReason = "第 $i 步后前台已切换至「${PhoneScope.appLabel(ctx, curPkg)} ($curPkg)」，为防跨页误触已中止后续同页步骤"
                break
            }

            // 步前等待特定文本控件就绪
            val waitForText = step.optString("wait_for_text").trim()
            if (waitForText.isNotEmpty()) {
                val waitTimeout = step.optLong("wait_timeout_ms", 2_500L).coerceIn(300L, 10_000L)
                val ready = awaitTargetTextReady(service, waitForText, waitTimeout)
                if (!ready) {
                    abortedStepIndex = i
                    abortReason = "第 ${i + 1} 步前置等待超时：屏幕未出现可用控件「$waitForText」，已熔断中止后续步骤"
                    break
                }
            }

            val stepOutcome: Result<JSONObject> = when (type) {
                "click" -> executeUiClick(ctx, cfg, JSONObject(step.toString()).put("observe_after", false))
                "input" -> executeUiInput(ctx, cfg, JSONObject(step.toString()).put("observe_after", false))
                "gesture" -> executeUiGesture(ctx, JSONObject(step.toString()).put("observe_after", false))
                "global_action" -> executeGlobalAction(step)
                "wait" -> {
                    val waitMs = step.optLong("duration_ms", 400L).coerceIn(60L, 5_000L)
                    delay(waitMs)
                    Result.success(JSONObject().put("action", "wait").put("summary", "已等待 ${waitMs}ms"))
                }
                else -> Result.failure(IllegalArgumentException("不支持的批量步骤类型：$type"))
            }

            if (stepOutcome.isFailure) {
                abortedStepIndex = i
                abortReason = "第 ${i + 1} 步 ($type) 执行失败：${stepOutcome.exceptionOrNull()?.message}"
                break
            }

            val stepSummary = stepOutcome.getOrNull()?.optString("summary").orEmpty()
            latestSnap = awaitUiSettledAndObserve(service, settleTimeoutMs)

            // 步后选中态断言（专门针对购物软件筛选条件未点上就点「确定」的场景）
            if (type == "click" && step.optBoolean("expect_selected", false)) {
                val targetText = step.optString("text").trim()
                    .ifBlank { step.optString("content_desc").trim() }
                val targetRef = step.optString("ref").trim()
                var isSelectedNow = isTargetSelectedInSnapshot(latestSnap, targetRef, targetText)
                if (!isSelectedNow) {
                    // 自动用真实坐标触摸手势补按一次并再次等稳
                    val retryArgs = JSONObject(step.toString())
                        .put("prefer_gesture", true)
                        .put("observe_after", false)
                    executeUiClick(ctx, cfg, retryArgs)
                    latestSnap = awaitUiSettledAndObserve(service, settleTimeoutMs)
                    isSelectedNow = isTargetSelectedInSnapshot(latestSnap, targetRef, targetText)
                }
                if (!isSelectedNow && latestSnap != null) {
                    // 仅当该节点在树上显式报告了 selected=false && checked=false 且无高亮变化时熔断
                    val nodeStillUnselected = findMatchingSnapshotNode(latestSnap, targetRef, targetText)
                        ?.let { !it.selected && !it.checked } == true
                    if (nodeStillUnselected) {
                        abortedStepIndex = i
                        abortReason = "第 ${i + 1} 步点击「${targetText.ifBlank { targetRef }}」后控件仍未处于选中状态，已熔断中止后续步骤（防止未选中就误点确定）"
                        break
                    }
                }
            }

            // 步后期望文本断言
            val expectText = step.optString("expect_text").trim()
            if (expectText.isNotEmpty()) {
                var hit = snapshotContainsText(latestSnap, expectText)
                if (!hit) {
                    delay(320L)
                    latestSnap = service.captureScreenSnapshot(110).getOrNull() ?: latestSnap
                    hit = snapshotContainsText(latestSnap, expectText)
                }
                if (!hit) {
                    abortedStepIndex = i
                    abortReason = "第 ${i + 1} 步后未检测到预期文本「$expectText」，已熔断中止后续步骤"
                    break
                }
            }

            executedLogs.put(
                JSONObject()
                    .put("step", i + 1)
                    .put("type", type)
                    .put("summary", stepSummary),
            )
        }

        val completedCount = executedLogs.length()
        val out = JSONObject().apply {
            put("total_steps", total)
            put("completed_steps", completedCount)
            put("aborted", abortedStepIndex >= 0)
            if (abortedStepIndex >= 0) {
                put("aborted_at_step", abortedStepIndex + 1)
                put("abort_reason", abortReason)
            }
            put("steps_executed", executedLogs)
            put(
                "summary",
                if (abortedStepIndex < 0) {
                    "已连贯完成全部 $completedCount/$total 步操作"
                } else {
                    "已完成 $completedCount/$total 步，在第 ${abortedStepIndex + 1} 步熔断：$abortReason"
                },
            )
        }

        if (observeAfter) {
            val snap = latestSnap ?: service.captureScreenSnapshot(110).getOrNull()
            if (snap != null) {
                out.put("screen", snapshotToJson(snap))
                attachMatchedSkills(out, snap.packageName, snap.appLabel)
            }
        }
        return Result.success(out)
    }

    private suspend fun awaitTargetTextReady(
        service: PhoneObservationService,
        needle: String,
        timeoutMs: Long,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val snap = service.captureScreenSnapshot(110).getOrNull()
            if (snap != null && snap.nodes.any {
                    it.enabled && (it.text.contains(needle, ignoreCase = true) ||
                        it.contentDesc.contains(needle, ignoreCase = true))
                }
            ) {
                return true
            }
            delay(100L)
        }
        return false
    }

    private fun snapshotContainsText(snap: ScreenSnapshot?, needle: String): Boolean {
        if (snap == null || needle.isEmpty()) return false
        return snap.nodes.any {
            it.text.contains(needle, ignoreCase = true) ||
                it.contentDesc.contains(needle, ignoreCase = true)
        }
    }

    private fun findMatchingSnapshotNode(
        snap: ScreenSnapshot?,
        ref: String,
        text: String,
    ): UiNodeSnapshot? {
        if (snap == null) return null
        if (text.isNotEmpty()) {
            snap.nodes.firstOrNull {
                it.text.contains(text, ignoreCase = true) ||
                    it.contentDesc.contains(text, ignoreCase = true)
            }?.let { return it }
        }
        if (ref.isNotEmpty()) {
            snap.nodes.firstOrNull { it.ref.equals(ref, ignoreCase = true) }?.let { return it }
        }
        return null
    }

    private fun isTargetSelectedInSnapshot(
        snap: ScreenSnapshot?,
        ref: String,
        text: String,
    ): Boolean {
        val node = findMatchingSnapshotNode(snap, ref, text) ?: return false
        return node.selected || node.checked
    }

    private suspend fun awaitUiSettledAndObserve(
        service: PhoneObservationService,
        settleTimeoutMs: Long = 750L,
        maxNodes: Int = 110,
    ): ScreenSnapshot? {
        val deadline = System.currentTimeMillis() + settleTimeoutMs
        delay(110L)
        var prev = service.captureScreenSnapshot(maxNodes).getOrNull()
        while (System.currentTimeMillis() < deadline) {
            delay(95L)
            val cur = service.captureScreenSnapshot(maxNodes).getOrNull() ?: continue
            if (prev != null && cur.signature == prev.signature) {
                return cur
            }
            prev = cur
        }
        return prev
    }

    private suspend fun attachPostActionObservation(
        service: PhoneObservationService,
        targetJson: JSONObject,
    ) {
        val snap = awaitUiSettledAndObserve(service) ?: return
        targetJson.put("screen", snapshotToJson(snap))
        attachMatchedSkills(targetJson, snap.packageName, snap.appLabel)
    }

    private suspend fun executeGlobalAction(args: JSONObject): Result<JSONObject> {
        val service = PhoneObservationService.active
            ?: return Result.failure(IllegalStateException("无障碍服务未开启"))
        val action = args.optString("action")
        return service.performGlobal(action).map { msg ->
            JSONObject().put("action", action).put("summary", msg)
        }
    }

    private suspend fun executeAppManage(
        ctx: Context,
        cfg: PhoneControlConfig,
        args: JSONObject,
    ): Result<JSONObject> {
        val action = args.optString("action").lowercase().trim()
        val appName = args.optString("app_name")
        val pkg = args.optString("package_name")
        return when (action) {
            "list", "find" -> Result.success(
                PhoneSystemCapabilities.listInstalledApps(
                    context = ctx,
                    query = appName.ifBlank { pkg },
                    includeSystem = args.optBoolean("include_system", false),
                    limit = args.optInt("limit", 60),
                ).also { out ->
                    if (appName.isNotBlank() || pkg.isNotBlank()) {
                        attachMatchedSkills(out, pkg.ifBlank { null }, appName.ifBlank { null })
                    }
                },
            )
            "launch", "switch" -> PhoneSystemCapabilities.launchOrSwitchApp(ctx, appName, pkg).map { out ->
                val resolvedPkg = out.optString("target_package").ifBlank { pkg }
                val resolvedName = out.optString("target_name").ifBlank { appName }
                attachMatchedSkills(out, resolvedPkg, resolvedName)
                out
            }
            "close" -> {
                if (cfg.isConfirmationRequired(PhoneConfirmRule.CLOSE_APP)) {
                    val outcome = PhoneConfirmationGate.request(
                        context = ctx,
                        rule = PhoneConfirmRule.CLOSE_APP,
                        title = "关闭应用「${appName.ifBlank { pkg }.ifBlank { "当前应用" }}」",
                        detail = "将打开最近任务并上滑关闭应用卡片",
                        targetApp = appName.ifBlank { pkg },
                    )
                    if (outcome != PhoneConfirmationGate.Outcome.APPROVED) {
                        return Result.failure(IllegalStateException("用户取消了关闭应用操作（$outcome）"))
                    }
                }
                PhoneSystemCapabilities.closeAppViaRecents(ctx, appName, pkg)
            }
            else -> Result.failure(IllegalArgumentException("不支持的 app_manage 动作：$action"))
        }
    }

    private suspend fun executeSystemSetting(
        ctx: Context,
        cfg: PhoneControlConfig,
        args: JSONObject,
    ): Result<JSONObject> {
        val setting = args.optString("setting")
        val action = args.optString("action", "get")
        val value = if (args.has("value") && !args.isNull("value")) args.optString("value") else null
        val stream = if (args.has("stream") && !args.isNull("stream")) args.optString("stream") else null

        if (action.equals("set", ignoreCase = true) && cfg.isConfirmationRequired(PhoneConfirmRule.SYSTEM_SETTINGS)) {
            val outcome = PhoneConfirmationGate.request(
                context = ctx,
                rule = PhoneConfirmRule.SYSTEM_SETTINGS,
                title = "修改系统设置：$setting = ${value.orEmpty()}",
                detail = "确认允许修改该项系统设置吗？",
            )
            if (outcome != PhoneConfirmationGate.Outcome.APPROVED) {
                return Result.failure(IllegalStateException("修改系统设置未获确认（$outcome）"))
            }
        }
        return PhoneSystemCapabilities.handleSystemSetting(ctx, setting, action, value, stream)
    }

    private suspend fun executeScreenshot(ctx: Context, args: JSONObject): Result<JSONObject> {
        val service = PhoneObservationService.active
            ?: return Result.failure(IllegalStateException("截图需要无障碍服务开启（Android 10 及以下需屏幕捕获授权）"))
        val scope = args.optString("scope", "screen")
        val analyze = args.optBoolean("analyze", false)
        val question = args.optString("question")
        val drawGrid = args.optBoolean("draw_grid", true)
        val scale = args.optDouble("scale", PhoneVisionAnalyzer.DEFAULT_SCREENSHOT_SCALE.toDouble())
            .toFloat()
            .coerceIn(0.4f, 1.0f)
        val quality = args.optInt("quality", PhoneVisionAnalyzer.DEFAULT_SCREENSHOT_JPEG_QUALITY)
            .coerceIn(40, 100)
        val snap = service.captureScreenSnapshot(80).getOrNull()
        return captureAndSaveScreenshot(ctx, service, scope, analyze, question, drawGrid, snap, scale, quality)
    }

    private suspend fun captureAndSaveScreenshot(
        ctx: Context,
        service: PhoneObservationService,
        scope: String,
        analyze: Boolean,
        question: String,
        drawGrid: Boolean,
        snap: ScreenSnapshot?,
        scale: Float = PhoneVisionAnalyzer.DEFAULT_SCREENSHOT_SCALE,
        quality: Int = PhoneVisionAnalyzer.DEFAULT_SCREENSHOT_JPEG_QUALITY,
    ): Result<JSONObject> {
        val bmp = service.captureScreenshotBitmap(scope).getOrElse { return Result.failure(it) }
        val screenW = bmp.width
        val screenH = bmp.height
        val (targetW, targetH) = PhoneVisionAnalyzer.computeScaledDimensions(screenW, screenH, scale)
        val scaledBmp = if (targetW < screenW || targetH < screenH) {
            Bitmap.createScaledBitmap(bmp, targetW, targetH, true)
        } else {
            bmp
        }
        return try {
            val dir = PhoneRuntime.artifactDir(ctx, "screenshots")
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val file = File(dir, "shot_$stamp.jpg")
            file.outputStream().use { out ->
                check(scaledBmp.compress(Bitmap.CompressFormat.JPEG, quality, out)) { "JPEG 编码失败" }
            }
            val dhash = PhoneVisionAnalyzer.computeDHash(scaledBmp)
            val artifact = PhoneArtifactStore.register(
                context = ctx,
                type = PhoneArtifactType.SCREENSHOT,
                file = file,
                width = scaledBmp.width,
                height = scaledBmp.height,
            )
            PhoneObservationService.active?.showOverlayBadge("已截图 ${scaledBmp.width}x${scaledBmp.height} (80% 精度)")

            val out = JSONObject().apply {
                put("artifact_id", artifact.id)
                put("file_name", artifact.fileName)
                put("workspace_path", artifact.workspacePath)
                put("absolute_path", artifact.absolutePath)
                put("width", scaledBmp.width)
                put("height", scaledBmp.height)
                put("screen_width", screenW)
                put("screen_height", screenH)
                put("scale", scale.toDouble())
                put("quality", quality)
                put("format", "jpeg")
                put("dhash", dhash)
                put("size_bytes", artifact.sizeBytes)
                put(
                    "summary",
                    "已保存 ${(scale * 100).toInt()}% 精度截图 ${artifact.workspacePath} (${scaledBmp.width}x${scaledBmp.height} JPEG，原始触控坐标 ${screenW}x${screenH})",
                )
            }
            if (analyze) {
                val uiSummary = snap?.nodes?.take(30)?.joinToString(" | ") { n ->
                    "${n.ref}:${n.text.ifBlank { n.contentDesc }}@[${n.centerX},${n.centerY}]"
                }.orEmpty()
                val vision = PhoneVisionAnalyzer.analyzeBitmap(
                    bitmap = scaledBmp,
                    question = question,
                    drawGrid = drawGrid,
                    uiSummary = uiSummary,
                    config = PhoneRuntime.visionModelConfig.value,
                    screenWidth = screenW,
                    screenHeight = screenH,
                )
                vision.onSuccess { out.put("analysis", it) }
                    .onFailure { out.put("analysis_fallback", it.message) }
            }
            Result.success(out)
        } finally {
            if (scaledBmp !== bmp) scaledBmp.recycle()
            bmp.recycle()
        }
    }

    private suspend fun executeScreenAnalyze(ctx: Context, args: JSONObject): Result<JSONObject> {
        val question = args.optString("question").ifBlank { "请描述当前屏幕内容与可点击目标坐标" }
        val drawGrid = args.optBoolean("draw_grid", true)
        val imagePath = args.optString("image_path").trim()

        val service = PhoneObservationService.active
        val snap = service?.captureScreenSnapshot(80)?.getOrNull()
        val uiSummary = snap?.nodes?.take(40)?.joinToString(" | ") { n ->
            "${n.ref}:${n.text.ifBlank { n.contentDesc }}@[${n.centerX},${n.centerY}]"
        }.orEmpty()

        if (imagePath.isNotBlank()) {
            val file = File(imagePath).let { f -> if (f.isAbsolute) f else File(PhoneRuntime.workspaceRoot(ctx), imagePath) }
            if (!file.isFile) return Result.failure(IllegalArgumentException("找不到指定图片：$imagePath"))
            val bmp = BitmapFactory.decodeFile(file.absolutePath)
                ?: return Result.failure(IllegalStateException("图片解码失败：${file.name}"))
            val realW = snap?.screenWidth?.takeIf { it > 0 } ?: bmp.width
            val realH = snap?.screenHeight?.takeIf { it > 0 } ?: bmp.height
            return try {
                val vision = PhoneVisionAnalyzer.analyzeBitmap(
                    bitmap = bmp,
                    question = question,
                    drawGrid = drawGrid,
                    uiSummary = uiSummary,
                    config = PhoneRuntime.visionModelConfig.value,
                    screenWidth = realW,
                    screenHeight = realH,
                )
                val out = JSONObject().apply {
                    put("workspace_path", PhoneRuntime.toWorkspaceRelativePath(ctx, file))
                    put("width", bmp.width)
                    put("height", bmp.height)
                }
                vision.onSuccess {
                    out.put("analysis", it)
                    out.put("summary", "已完成视觉分析")
                }.onFailure {
                    out.put("analysis_fallback", it.message)
                    out.put("ui_summary", uiSummary)
                    out.put("summary", "视觉模型暂不可用，已回退控件摘要")
                }
                Result.success(out)
            } finally {
                bmp.recycle()
            }
        }

        val obsService = service
            ?: return Result.failure(IllegalStateException("无障碍服务未开启，无法现场截屏分析"))
        return captureAndSaveScreenshot(
            ctx = ctx,
            service = obsService,
            scope = "screen",
            analyze = true,
            question = question,
            drawGrid = drawGrid,
            snap = snap,
        )
    }

    private suspend fun executeScreenRecord(ctx: Context, args: JSONObject): Result<JSONObject> {
        return when (val act = args.optString("action").lowercase().trim()) {
            "status" -> Result.success(
                JSONObject()
                    .put("recording", PhoneMediaController.isScreenRecording())
                    .put("authorized", PhoneMediaController.hasScreenCaptureConsent()),
            )
            "start" -> {
                val withAudio = args.optBoolean("with_audio", false)
                PhoneMediaController.startScreenRecording(ctx, withAudio).map { wsPath ->
                    PhoneObservationService.active?.showOverlayBadge("开始录屏")
                    JSONObject()
                        .put("recording", true)
                        .put("workspace_path", wsPath)
                        .put("summary", "录屏已开始：$wsPath")
                }
            }
            "stop" -> {
                PhoneMediaController.stopScreenRecording(ctx).map { art ->
                    PhoneObservationService.active?.showOverlayBadge("录屏已保存")
                    JSONObject()
                        .put("recording", false)
                        .put("artifact_id", art.id)
                        .put("file_name", art.fileName)
                        .put("workspace_path", art.workspacePath)
                        .put("absolute_path", art.absolutePath)
                        .put("duration_ms", art.durationMs)
                        .put("size_bytes", art.sizeBytes)
                        .put("summary", "录屏已保存至 ${art.workspacePath}")
                }
            }
            else -> Result.failure(IllegalArgumentException("不支持的 screen_record 动作：$act"))
        }
    }

    private suspend fun executeAudioRecord(ctx: Context, args: JSONObject): Result<JSONObject> {
        return when (val act = args.optString("action").lowercase().trim()) {
            "status" -> Result.success(
                JSONObject()
                    .put("recording", PhoneMediaController.isAudioRecording())
                    .put("paused", PhoneMediaController.isAudioPaused()),
            )
            "start" -> PhoneMediaController.startAudioRecording(ctx).map { wsPath ->
                PhoneObservationService.active?.showOverlayBadge("开始录音")
                JSONObject()
                    .put("recording", true)
                    .put("workspace_path", wsPath)
                    .put("summary", "录音已开始：$wsPath")
            }
            "pause" -> PhoneMediaController.pauseAudioRecording().map { msg ->
                JSONObject().put("paused", true).put("summary", msg)
            }
            "resume" -> PhoneMediaController.resumeAudioRecording().map { msg ->
                JSONObject().put("paused", false).put("summary", msg)
            }
            "stop" -> PhoneMediaController.stopAudioRecording(ctx).map { art ->
                PhoneObservationService.active?.showOverlayBadge("录音已保存")
                JSONObject()
                    .put("recording", false)
                    .put("artifact_id", art.id)
                    .put("file_name", art.fileName)
                    .put("workspace_path", art.workspacePath)
                    .put("absolute_path", art.absolutePath)
                    .put("duration_ms", art.durationMs)
                    .put("size_bytes", art.sizeBytes)
                    .put("summary", "录音已保存至 ${art.workspacePath}")
            }
            else -> Result.failure(IllegalArgumentException("不支持的 audio_record 动作：$act"))
        }
    }

    private suspend fun executeNotifications(ctx: Context, args: JSONObject): Result<JSONObject> {
        val action = args.optString("action").lowercase().trim()
        if (action == "expand_shade") {
            val service = PhoneObservationService.active
                ?: return Result.failure(IllegalStateException("展开通知栏需要开启无障碍服务"))
            return service.performGlobal("notifications").map { msg ->
                JSONObject().put("action", "expand_shade").put("summary", msg)
            }
        }
        val listener = PhoneNotificationService.active
            ?: return Result.failure(IllegalStateException("通知读取服务未授权，请在系统设置「通知使用权」中启用「CodeForge 手机通知读取」"))
        return when (action) {
            "list" -> {
                val filter = args.optString("package_filter")
                val arr = listener.listVisibleNotifications(ctx, filter)
                Result.success(
                    JSONObject()
                        .put("count", arr.length())
                        .put("notifications", arr)
                        .put("summary", "共读取到 ${arr.length()} 条可见通知"),
                )
            }
            "open" -> listener.openNotification(ctx, args.optString("key"))
                .map { JSONObject().put("summary", it) }
            "clear" -> listener.clearNotification(ctx, args.optString("key"))
                .map { JSONObject().put("summary", it) }
            "click_action" -> listener.triggerNotificationAction(
                ctx,
                args.optString("key"),
                args.optInt("action_index", 0),
            ).map { JSONObject().put("summary", it) }
            else -> Result.failure(IllegalArgumentException("不支持的 notifications 动作：$action"))
        }
    }

    private suspend fun executeFileMedia(
        ctx: Context,
        cfg: PhoneControlConfig,
        args: JSONObject,
    ): Result<JSONObject> {
        val action = args.optString("action").lowercase().trim()
        val path = args.optString("path")
        if (action == "delete" && cfg.isConfirmationRequired(PhoneConfirmRule.DELETE_FILE)) {
            val outcome = PhoneConfirmationGate.request(
                context = ctx,
                rule = PhoneConfirmRule.DELETE_FILE,
                title = "删除文件「${path.substringAfterLast('/')}」",
                detail = "完整路径：$path",
            )
            if (outcome != PhoneConfirmationGate.Outcome.APPROVED) {
                return Result.failure(IllegalStateException("删除文件未获确认（$outcome）"))
            }
        }
        if (action == "share" && cfg.isConfirmationRequired(PhoneConfirmRule.SEND_MESSAGE)) {
            val outcome = PhoneConfirmationGate.request(
                context = ctx,
                rule = PhoneConfirmRule.SEND_MESSAGE,
                title = "对外分享文件「${path.substringAfterLast('/')}」",
                detail = "将调起系统分享面板发送该文件",
            )
            if (outcome != PhoneConfirmationGate.Outcome.APPROVED) {
                return Result.failure(IllegalStateException("分享文件未获确认（$outcome）"))
            }
        }
        return PhoneSystemCapabilities.handleFileMedia(
            context = ctx,
            action = action,
            path = path,
            destPath = args.optString("dest_path"),
            newName = args.optString("new_name"),
            mimeType = args.optString("mime_type"),
        )
    }

    private suspend fun executeCalendarReminder(ctx: Context, args: JSONObject): Result<JSONObject> =
        PhoneSystemCapabilities.handleCalendarReminder(
            context = ctx,
            action = args.optString("action"),
            title = args.optString("title"),
            description = args.optString("description"),
            startMs = if (args.has("start_ms") && !args.isNull("start_ms")) args.optLong("start_ms") else null,
            endMs = if (args.has("end_ms") && !args.isNull("end_ms")) args.optLong("end_ms") else null,
            hour = if (args.has("hour") && !args.isNull("hour")) args.optInt("hour") else null,
            minute = if (args.has("minute") && !args.isNull("minute")) args.optInt("minute") else null,
            seconds = if (args.has("seconds") && !args.isNull("seconds")) args.optInt("seconds") else null,
        )

    private suspend fun executeCommunication(
        ctx: Context,
        cfg: PhoneControlConfig,
        args: JSONObject,
    ): Result<JSONObject> {
        val action = args.optString("action").lowercase().trim()
        val phone = args.optString("phone")
        val body = args.optString("body")
        val filePath = args.optString("file_path")

        if (action == "open_dialer" && cfg.isConfirmationRequired(PhoneConfirmRule.CALL_DIAL)) {
            val outcome = PhoneConfirmationGate.request(
                context = ctx,
                rule = PhoneConfirmRule.CALL_DIAL,
                title = "准备打开拨号盘：$phone",
                detail = "确认后将跳转到系统拨号界面",
            )
            if (outcome != PhoneConfirmationGate.Outcome.APPROVED) {
                return Result.failure(IllegalStateException("打开拨号未获确认（$outcome）"))
            }
        }
        if ((action == "send_sms" || action == "share_text" || action == "share_file") &&
            cfg.isConfirmationRequired(PhoneConfirmRule.SEND_MESSAGE)
        ) {
            val outcome = PhoneConfirmationGate.request(
                context = ctx,
                rule = PhoneConfirmRule.SEND_MESSAGE,
                title = when (action) {
                    "send_sms" -> "发送短信给 $phone"
                    "share_file" -> "对外分享文件 ${filePath.substringAfterLast('/')}"
                    else -> "对外分享文本消息"
                },
                detail = body.ifBlank { filePath }.take(180),
            )
            if (outcome != PhoneConfirmationGate.Outcome.APPROVED) {
                return Result.failure(IllegalStateException("发送/分享确认未通过（$outcome）"))
            }
        }
        return PhoneSystemCapabilities.handleCommunication(
            context = ctx,
            action = action,
            query = args.optString("query"),
            phone = phone,
            body = body,
            subject = args.optString("subject"),
            filePath = filePath,
        )
    }

    // ---------------------------------------------------------------- 序列化与辅助

    private fun snapshotToJson(snap: ScreenSnapshot): JSONObject {
        val nodesArr = JSONArray()
        snap.nodes.forEach { n ->
            nodesArr.put(
                JSONObject().apply {
                    put("ref", n.ref)
                    put("cls", n.className)
                    if (n.text.isNotEmpty()) put("text", n.text)
                    if (n.contentDesc.isNotEmpty()) put("desc", n.contentDesc)
                    if (n.resourceId.isNotEmpty()) put("id", n.resourceId)
                    put("bounds", JSONArray(listOf(n.left, n.top, n.right, n.bottom)))
                    put("center", JSONArray(listOf(n.centerX, n.centerY)))
                    val flags = buildList {
                        if (n.clickable) add("clickable")
                        if (n.longClickable) add("long_clickable")
                        if (n.editable) add("editable")
                        if (n.scrollable) add("scrollable")
                        if (n.checkable) add("checkable")
                        if (n.checked) add("checked")
                        if (n.selected) add("selected")
                        if (n.focused) add("focused")
                        if (n.password) add("password")
                        if (!n.enabled) add("disabled")
                    }
                    if (flags.isNotEmpty()) put("flags", JSONArray(flags))
                },
            )
        }
        return JSONObject().apply {
            put("package", snap.packageName)
            put("app_name", snap.appLabel)
            if (snap.className.isNotBlank()) put("activity", snap.className)
            put("screen", JSONArray(listOf(snap.screenWidth, snap.screenHeight)))
            put("signature", snap.signature)
            put("node_count", snap.nodes.size)
            put("truncated", snap.truncated)
            put("nodes", nodesArr)
        }
    }

    private fun describeToolAction(toolName: String, args: JSONObject): String = when (toolName) {
        "screen_observe" -> "观察当前屏幕控件"
        "screen_wait_change" -> "等待屏幕变化"
        "ui_click" -> "点击 ${args.optString("text").ifBlank { args.optString("ref") }.ifBlank { "坐标" }}"
        "ui_gesture" -> "${args.optString("action", "swipe")} ${args.optString("direction")}"
        "ui_input" -> "输入操作 (${args.optString("action")})"
        "ui_batch" -> "连贯执行 ${args.optJSONArray("steps")?.length() ?: 0} 步界面动作"
        "ui_global_action" -> "系统按键 (${args.optString("action")})"
        "app_manage" -> "应用管理：${args.optString("action")} ${args.optString("app_name").ifBlank { args.optString("package_name") }}"
        "system_setting" -> "系统设置：${args.optString("setting")} ${args.optString("action", "get")}"
        "screenshot" -> "截取屏幕"
        "screen_analyze" -> "分析屏幕画面"
        "screen_record" -> "录屏：${args.optString("action")}"
        "audio_record" -> "录音：${args.optString("action")}"
        "notifications" -> "通知操作：${args.optString("action")}"
        "file_media" -> "文件操作：${args.optString("action")}"
        "calendar_reminder" -> "日程与提醒：${args.optString("action")}"
        "communication" -> "通讯操作：${args.optString("action")}"
        "device_status" -> "查询设备状态"
        "app_skill" -> "应用技能库：${args.optString("action", "list")}"
        else -> toolName
    }.trim()

    private fun attachMatchedSkills(
        targetJson: JSONObject,
        packageName: String?,
        appLabel: String?,
    ) {
        if (!PhoneControlPrefs.config.value.appSkillsEnabled) return
        val matched = PhoneAppSkillStore.match(
            packageName = packageName,
            appLabel = appLabel,
            includeDisabled = false,
            limit = 3,
        )
        if (matched.isEmpty()) return
        targetJson.put("matched_app_skills", PhoneAppSkillStore.toSummaryJsonArray(matched))
        targetJson.put(
            "app_skill_hint",
            "命中当前应用的操作技能，如需完整步骤请调用 phone.app_skill(action=\"get\", id=\"...\")",
        )
    }

    private fun executeAppSkill(args: JSONObject): Result<JSONObject> {
        val cfg = PhoneControlPrefs.config.value
        val action = args.optString("action", "list").lowercase().trim()
        return when (action) {
            "list" -> {
                val query = args.optString("query").trim()
                val all = if (query.isNotEmpty()) {
                    PhoneAppSkillStore.match(query = query, includeDisabled = false, limit = 50)
                } else {
                    PhoneAppSkillStore.listAll(includeDisabled = false)
                }
                Result.success(
                    JSONObject().apply {
                        put("count", all.size)
                        put("skills", PhoneAppSkillStore.toSummaryJsonArray(all))
                        put("summary", "当前共有 ${all.size} 条可用应用技能")
                    }
                )
            }
            "match" -> {
                if (!cfg.appSkillsEnabled) {
                    return Result.success(
                        JSONObject().apply {
                            put("count", 0)
                            put("skills", JSONArray())
                            put("summary", "应用技能库自动匹配已关闭")
                        }
                    )
                }
                val pkg = args.optString("package_name").trim().ifBlank { null }
                val appName = args.optString("app_name").trim().ifBlank { null }
                val query = args.optString("query").trim().ifBlank { null }
                val matched = PhoneAppSkillStore.match(
                    packageName = pkg,
                    appLabel = appName,
                    query = query,
                    includeDisabled = false,
                    limit = 5,
                )
                Result.success(
                    JSONObject().apply {
                        put("count", matched.size)
                        put("skills", PhoneAppSkillStore.toSummaryJsonArray(matched))
                        put("summary", "匹配到 ${matched.size} 条应用技能")
                    }
                )
            }
            "get" -> {
                val idOrName = args.optString("id").trim()
                    .ifBlank { args.optString("name").trim() }
                if (idOrName.isEmpty()) {
                    return Result.failure(IllegalArgumentException("get 需要提供技能 id 或 name"))
                }
                val skill = PhoneAppSkillStore.getByIdOrName(idOrName, includeDisabled = false)
                    ?: return Result.failure(IllegalArgumentException("未找到或未启用应用技能：$idOrName"))
                Result.success(
                    PhoneAppSkillStore.toDetailJson(skill).apply {
                        put("summary", "已读取应用技能「${skill.name}」")
                    }
                )
            }
            "save" -> {
                if (!cfg.allowSkillSelfLearning) {
                    return Result.failure(
                        IllegalStateException("用户已关闭「允许模型自动沉淀应用技能」，无法写入自定义技能库")
                    )
                }
                val id = args.optString("id").trim()
                val name = args.optString("name").trim()
                val desc = args.optString("description").trim()
                val content = args.optString("content").trim()
                val packages = jsonStringList(args, "packages")
                val appNames = jsonStringList(args, "app_names")
                val tags = jsonStringList(args, "tags")
                val priority = args.optInt("priority", 80)
                PhoneAppSkillStore.saveCustomSkill(
                    id = id.ifBlank { name },
                    name = name.ifBlank { id },
                    description = desc,
                    packages = packages,
                    appNames = appNames,
                    tags = tags,
                    priority = priority,
                    content = content,
                ).map { saved ->
                    PhoneAppSkillStore.toDetailJson(saved).apply {
                        put("saved", true)
                        put("file_path", saved.filePath)
                        put("summary", "已保存应用技能「${saved.name}」(${saved.id})")
                    }
                }
            }
            else -> Result.failure(IllegalArgumentException("不支持的 app_skill 动作：$action"))
        }
    }

    private fun jsonStringList(args: JSONObject, key: String): List<String> {
        val arr = args.optJSONArray(key)
        if (arr != null) {
            return buildList {
                for (i in 0 until arr.length()) {
                    val v = arr.optString(i).trim()
                    if (v.isNotEmpty()) add(v)
                }
            }
        }
        val single = args.optString(key).trim()
        if (single.isEmpty()) return emptyList()
        return single.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun ok(json: JSONObject): PhoneMcpServer.ToolCallOutcome {
        val text = json.toString()
        val bounded = if (text.length > MAX_OUTPUT_CHARS) {
            text.take(MAX_OUTPUT_CHARS) + "...(truncated)"
        } else {
            text
        }
        return PhoneMcpServer.ToolCallOutcome(success = true, text = bounded)
    }

    private fun fail(message: String): PhoneMcpServer.ToolCallOutcome =
        PhoneMcpServer.ToolCallOutcome(
            success = false,
            text = JSONObject().put("ok", false).put("error", message).toString(),
        )
}
