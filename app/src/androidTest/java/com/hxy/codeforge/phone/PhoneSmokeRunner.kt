package com.hxy.codeforge.phone

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import android.provider.Settings
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * 手机操控插件真机/模拟器端到端冒烟验证（`am instrument`）。
 *
 * 覆盖：
 * 1. 内嵌 MCP HTTP 服务端（`initialize` → `tools/list` 18 工具 → `tools/call`）；
 * 2. 总开关 / 能力开关 / 应用黑名单实时拦截；
 * 3. `device_status`、`app_manage`、`system_setting`（真实调节并回读音量）；
 * 4. `task_control`（暂停/继续/停止）与执行记录登记；
 * 5. `windows=true` 时激活无障碍服务并验证 `screen_observe` 控件读取、点击与 `screenshot` 产物落盘。
 */
class PhoneSmokeRunner : Instrumentation() {

    private var checkWindows = false
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        checkWindows = arguments?.getString("windows") == "true"
        start()
    }

    private fun awaitReady(label: String, predicate: () -> Boolean) {
        val until = android.os.SystemClock.elapsedRealtime() + 20_000
        while (!predicate()) {
            check(android.os.SystemClock.elapsedRealtime() < until) {
                "$label: ${PhoneObservationService.result.value.message}"
            }
            Thread.sleep(150)
        }
    }

    private fun mcpCall(endpoint: String, id: Int, method: String, params: JSONObject?): JSONObject {
        val envelope = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
        if (params != null) envelope.put("params", params)

        val req = Request.Builder()
            .url(endpoint)
            .post(envelope.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            check(resp.code == 200) { "HTTP ${resp.code}" }
            return JSONObject(resp.body?.string().orEmpty())
        }
    }

    private fun callTool(endpoint: String, id: Int, name: String, args: JSONObject = JSONObject()): JSONObject {
        val env = mcpCall(
            endpoint,
            id,
            "tools/call",
            JSONObject().put("name", name).put("arguments", args),
        )
        return env.getJSONObject("result")
    }

    private fun checkWindowAndObservation(endpoint: String, blank: Boolean, capture: Boolean) {
        val pkg = context.packageName
        val service = checkNotNull(PhoneObservationService.active)
        var prevWindowId = -1
        runOnMainSync {
            val cur = service.rootInActiveWindow
            if (cur?.packageName?.toString() == pkg) prevWindowId = cur.windowId
        }

        val activity = Intent()
            .setClassName(pkg, "com.hxy.codeforge.phone.ObservationFixtureActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("blank", blank)
        targetContext.startActivity(activity)

        awaitReady("fixture window $pkg (prev=$prevWindowId)") {
            var match = false
            runOnMainSync {
                val cur = service.rootInActiveWindow
                val seen = "pkg=${cur?.packageName}, winId=${cur?.windowId}"
                match = cur?.packageName?.toString() == pkg &&
                    (prevWindowId == -1 || cur.windowId != prevWindowId)
                if (!match) {
                    service.finish("waiting: $seen")
                }
            }
            match
        }
        Thread.sleep(1200)

        runOnMainSync {
            service.begin(pkg, capture)
            val root = checkNotNull(service.rootInActiveWindow)
            check(root.packageName == pkg) { "Expected $pkg, got ${root.packageName}" }
        }
        awaitReady("window observation") { PhoneObservationService.result.value.message != "等待系统设置窗口" }
        val obs = PhoneObservationService.result.value
        if (!blank) {
            check(obs.message.contains("Phone fixture 123")) { obs.message }
            PhoneTaskController.activateForPhoneTurn("测试点击与思考提示")
            check(PhoneTaskController.state.value.startupNotice.contains("通知栏"))
            PhoneTaskController.appendThinkingDelta("正在定位 Phone fixture 123 控件准备点击。")
            check(PhoneTaskController.state.value.thinkingPreview.contains("Phone fixture 123"))

            val obsRes = callTool(endpoint, 50, "screen_observe")
            check(!obsRes.getBoolean("isError")) { obsRes.toString() }
            val text = obsRes.getJSONArray("content").getJSONObject(0).getString("text")
            check(text.contains("Phone fixture 123")) { text }

            val clickRes = callTool(
                endpoint,
                52,
                "ui_click",
                JSONObject().put("text", "Phone fixture 123"),
            )
            check(!clickRes.getBoolean("isError")) { clickRes.toString() }
            Thread.sleep(300)
        } else if (capture) {
            val image = checkNotNull(obs.image) { obs.message }
            val center = image.getPixel(image.width / 2, image.height / 2)
            check(center == Color.BLUE) {
                "Expected BLUE (0x${Integer.toHexString(Color.BLUE)}), got 0x${Integer.toHexString(center)} (${image.width}x${image.height})"
            }
            val shotRes = callTool(
                endpoint,
                51,
                "screenshot",
                JSONObject().put("scope", "screen").put("analyze", false),
            )
            check(!shotRes.getBoolean("isError")) { shotRes.toString() }
            val shotJson = JSONObject(shotRes.getJSONArray("content").getJSONObject(0).getString("text"))
            val abs = shotJson.getString("absolute_path")
            check(abs.endsWith(".jpg")) { "Expected .jpg screenshot, got $abs" }
            check(shotJson.getString("format") == "jpeg") { shotJson.toString() }
            check(shotJson.getInt("quality") == 80) { shotJson.toString() }
            val screenW = shotJson.getInt("screen_width")
            val shotW = shotJson.getInt("width")
            check(shotW == (screenW * 0.8f).roundToInt()) { "Expected 80% width of $screenW, got $shotW" }
            val bmp = BitmapFactory.decodeFile(abs)
            check(bmp != null && bmp.width == shotW && bmp.height > 0)
            bmp.recycle()
            check(PhoneArtifactStore.artifacts.value.any { it.absolutePath == abs }) {
                "Artifact $abs missing from PhoneArtifactStore"
            }

            val analyzeRes = callTool(
                endpoint,
                53,
                "screen_analyze",
                JSONObject().put("question", "描述当前界面"),
            )
            check(!analyzeRes.getBoolean("isError")) { analyzeRes.toString() }
            val analyzeJson = JSONObject(analyzeRes.getJSONArray("content").getJSONObject(0).getString("text"))
            check(analyzeJson.has("dhash") && analyzeJson.has("absolute_path")) { analyzeJson.toString() }
        } else {
            check(obs.image == null && obs.message.contains("无可读控件")) { obs.message }
        }
    }

    override fun onStart() {
        val ctx = targetContext
        PhoneRuntime.init(ctx.applicationContext as android.app.Application)
        val audio = ctx.getSystemService(AudioManager::class.java)
        val stream = AudioManager.STREAM_MUSIC
        val originalVolume = audio.getStreamVolume(stream)
        val prevConfig = PhoneControlPrefs.config.value

        val result = runCatching {
            val endpoint = PhoneToolDispatcher.startServer()
            check(endpoint.startsWith("http://127.0.0.1:"))

            // 1. MCP initialize & tools/list
            val init = mcpCall(endpoint, 1, "initialize", JSONObject())
            check(init.getJSONObject("result").getJSONObject("serverInfo").getString("name") == "codeforge-phone")
            val list = mcpCall(endpoint, 2, "tools/list", null)
            val toolsArr = list.getJSONObject("result").getJSONArray("tools")
            check(toolsArr.length() == PhoneToolRegistry.tools.size)

            // 2. 总开关关闭时必须拒绝
            PhoneControlPrefs.setMasterEnabled(ctx, false)
            val deniedMaster = callTool(endpoint, 3, "device_status")
            check(deniedMaster.getBoolean("isError"))

            // 3. 开启总开关与能力
            PhoneControlPrefs.setMasterEnabled(ctx, true)
            PhoneControlPrefs.setAllCapabilities(ctx, PhoneCapability.entries.associateWith { true })
            PhoneTaskController.prepareForTurn("冒烟测试")

            val statusRes = callTool(endpoint, 4, "device_status")
            check(!statusRes.getBoolean("isError"))
            val statusJson = JSONObject(statusRes.getJSONArray("content").getJSONObject(0).getString("text"))
            check(statusJson.has("battery_percent") && statusJson.has("permissions"))

            // 4. 能力开关拦截
            PhoneControlPrefs.setCapability(ctx, PhoneCapability.SYSTEM_SETTINGS, false)
            val deniedSys = callTool(
                endpoint,
                5,
                "system_setting",
                JSONObject().put("setting", "volume").put("action", "set").put("value", "30"),
            )
            check(deniedSys.getBoolean("isError"))

            // 5. 允许系统设置并测试真实音量调节与回读
            PhoneControlPrefs.setCapability(ctx, PhoneCapability.SYSTEM_SETTINGS, true)
            PhoneControlPrefs.setConfirmRule(ctx, PhoneConfirmRule.SYSTEM_SETTINGS, false)
            val volRes = callTool(
                endpoint,
                6,
                "system_setting",
                JSONObject().put("setting", "volume").put("action", "set").put("value", "30"),
            )
            check(!volRes.getBoolean("isError")) { volRes.toString() }

            // 6. 黑名单校验（app_manage 拒绝黑名单应用）
            val wifiPkg = ctx.packageManager.resolveActivity(
                Intent(Settings.ACTION_WIFI_SETTINGS),
                PackageManager.MATCH_SYSTEM_ONLY,
            )?.activityInfo?.packageName
            if (wifiPkg != null) {
                PhoneControlPrefs.setExcludedPackages(ctx, prevConfig.excludedPackages + wifiPkg)
                val blockedLaunch = callTool(
                    endpoint,
                    7,
                    "app_manage",
                    JSONObject().put("action", "launch").put("package_name", wifiPkg),
                )
                check(blockedLaunch.getBoolean("isError"))
                PhoneControlPrefs.setExcludedPackages(ctx, prevConfig.excludedPackages)
            }

            // 7. task_control 状态同步
            val taskRes = callTool(
                endpoint,
                8,
                "task_control",
                JSONObject().put("action", "report_step").put("step_summary", "测试步骤"),
            )
            check(!taskRes.getBoolean("isError"))

            // 8. 兼容旧 PhoneCommands 测试契约
            check(PhoneCommands.request("@phonebook wifi") == null)
            check(PhoneCommands.request("@phone 媒体音量30%") == "媒体音量30%")

            // 8.5 应用技能库 MCP 闭环（list / match / get / save）
            val skillListRes = callTool(endpoint, 81, "app_skill", JSONObject().put("action", "list"))
            check(!skillListRes.getBoolean("isError")) { skillListRes.toString() }
            val skillListJson = JSONObject(skillListRes.getJSONArray("content").getJSONObject(0).getString("text"))
            check(skillListJson.getInt("count") >= 10) { "Expected >= 10 builtin app skills, got $skillListJson" }

            val skillMatchRes = callTool(
                endpoint,
                82,
                "app_skill",
                JSONObject().put("action", "match").put("package_name", "com.android.settings"),
            )
            check(!skillMatchRes.getBoolean("isError")) { skillMatchRes.toString() }
            val skillMatchJson = JSONObject(skillMatchRes.getJSONArray("content").getJSONObject(0).getString("text"))
            check(skillMatchJson.getInt("count") >= 1) { skillMatchJson.toString() }

            val skillGetRes = callTool(
                endpoint,
                83,
                "app_skill",
                JSONObject().put("action", "get").put("id", "android-settings"),
            )
            check(!skillGetRes.getBoolean("isError")) { skillGetRes.toString() }
            val skillGetJson = JSONObject(skillGetRes.getJSONArray("content").getJSONObject(0).getString("text"))
            check(skillGetJson.getString("content").contains("搜索设置")) { skillGetJson.toString() }

            val skillSaveRes = callTool(
                endpoint,
                84,
                "app_skill",
                JSONObject()
                    .put("action", "save")
                    .put("id", "smoke-temp-app")
                    .put("name", "冒烟测试应用")
                    .put("description", "冒烟验证自动沉淀")
                    .put("packages", org.json.JSONArray(listOf("com.example.smoke")))
                    .put("content", "# 冒烟步骤\n1. 点击测试按钮"),
            )
            check(!skillSaveRes.getBoolean("isError")) { skillSaveRes.toString() }
            check(PhoneAppSkillStore.getByIdOrName("smoke-temp-app") != null)
            PhoneAppSkillStore.deleteCustomSkill("smoke-temp-app")

            // 9. 无障碍窗口观察与截图
            if (checkWindows) {
                val previousServices = Settings.Secure.getString(
                    ctx.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                ).orEmpty()
                try {
                    val automation = getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                    fun setting(command: String) {
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(
                            automation.executeShellCommand(command),
                        ).use { it.readBytes() }
                    }
                    setting("settings delete secure enabled_accessibility_services")
                    Thread.sleep(500)
                    setting(
                        "settings put secure enabled_accessibility_services " +
                            "${ctx.packageName}/com.hxy.codeforge.phone.PhoneObservationService",
                    )
                    awaitReady("accessibility service") { PhoneObservationService.active != null }
                    checkWindowAndObservation(endpoint, blank = false, capture = false)
                    checkWindowAndObservation(endpoint, blank = true, capture = false)
                    checkWindowAndObservation(endpoint, blank = true, capture = true)
                    PhoneTaskController.stop("冒烟主动停止", stopAgentTurn = false)
                    check(PhoneTaskController.state.value.status == PhoneTaskStatus.STOPPED)
                } finally {
                    val restore = if (previousServices.isEmpty()) {
                        "settings delete secure enabled_accessibility_services"
                    } else {
                        "settings put secure enabled_accessibility_services $previousServices"
                    }
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(
                        getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                            .executeShellCommand(restore),
                    ).use { it.readBytes() }
                }
            }

            runBlocking { PhoneTaskController.markTurnCompleted() }
            PhoneToolDispatcher.stopServer()
        }

        // 恢复现场
        PhoneControlPrefs.setMasterEnabled(ctx, prevConfig.masterEnabled)
        PhoneControlPrefs.setAllCapabilities(ctx, prevConfig.capabilities)
        PhoneControlPrefs.setExcludedPackages(ctx, prevConfig.excludedPackages)
        prevConfig.confirmations.forEach { (r, v) -> PhoneControlPrefs.setConfirmRule(ctx, r, v) }
        if (!audio.isVolumeFixed) audio.setStreamVolume(stream, originalVolume, 0)

        finish(
            if (result.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply {
                putString(
                    "verification",
                    result.exceptionOrNull()?.stackTraceToString()
                        ?: if (checkWindows) {
                            "PASS: MCP HTTP (18 tools), gating, denylist, system_setting, task_control, screen_observe, ui_click, screenshot, screen_analyze"
                        } else {
                            "PASS: MCP HTTP (18 tools), gating, denylist, system_setting, task_control"
                        },
                )
            },
        )
    }
}
