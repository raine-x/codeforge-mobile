package com.hxy.codeforge.phone

import com.hxy.codeforge.core.PluginInstaller
import com.hxy.codeforge.ui.chat.toolLabel
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 手机操控策略、确认门禁、任务控制、视觉指纹、技能铺放与中文工具卡片文案单测。
 */
class PhonePolicyAndControlTest {

    @Test
    fun `skill deployer writes P-052 flat frontmatter and preserves user-owned skill`() {
        val ws = Files.createTempDirectory("cf-phone-skill-").toFile()
        try {
            PhoneSkillDeployer.sync(ws.absolutePath, enabled = true)
            val skillDir = File(ws, ".codeforge/skills/phone")
            val skillMd = File(skillDir, "SKILL.md")
            val marker = File(skillDir, "phone-skill.version")
            assertTrue(skillMd.isFile)
            assertTrue(marker.isFile)

            // 必须通过内核同款 P-052 扁平单行校验
            val parsed = PluginInstaller.validateSkillMarkdown("phone", skillMd.readText())
            assertTrue("Phone SKILL.md 违反 P-052：$parsed", parsed is PluginInstaller.InstallResult.Ok)
            val ref = (parsed as PluginInstaller.InstallResult.Ok).value
            assertTrue("phone" in ref.triggers || "@phone" in ref.triggers)
            assertEquals("4", marker.readText().trim())
            val skillBody = skillMd.readText()
            assertTrue(skillBody.contains("剪贴板"))
            assertTrue(skillBody.contains("粘贴"))
            assertTrue(skillBody.contains("set_text"))
            assertTrue(skillBody.contains("80%"))
            assertTrue(skillBody.contains("app_skill"))
            assertTrue(skillBody.contains("ui_batch"))
            assertTrue(skillBody.contains("complete"))

            // 停用时清理带标记的目录
            PhoneSkillDeployer.sync(ws.absolutePath, enabled = false)
            assertFalse(skillDir.exists())

            // 用户自建无标记目录绝不覆盖也绝不删除
            skillDir.mkdirs()
            val userContent = "---\nname: phone\ndescription: user custom\n---\ncustom body"
            skillMd.writeText(userContent)
            PhoneSkillDeployer.sync(ws.absolutePath, enabled = true)
            assertEquals(userContent, skillMd.readText())
            PhoneSkillDeployer.sync(ws.absolutePath, enabled = false)
            assertTrue(skillMd.isFile)
        } finally {
            ws.deleteRecursively()
        }
    }

    @Test
    fun `payment action heuristic catches checkout and payment apps`() {
        assertTrue(PhoneControlPrefs.isLikelyPaymentAction("立即支付 99 元", "com.example.shop"))
        assertTrue(PhoneControlPrefs.isLikelyPaymentAction("确认转账", "com.bank.app"))
        assertTrue(PhoneControlPrefs.isLikelyPaymentAction("确认", "com.eg.android.alipaygphone"))
        assertFalse(PhoneControlPrefs.isLikelyPaymentAction("打开蓝牙设置", "com.android.settings"))
        assertFalse(PhoneControlPrefs.isLikelyPaymentAction("搜索天气", "com.example.browser"))
    }

    @Test
    fun `confirmation gate approves rejects and times out cleanly`() = runBlocking {
        // Approve
        val approvedJob = async {
            PhoneConfirmationGate.request(
                context = null,
                rule = PhoneConfirmRule.SEND_MESSAGE,
                title = "发送短信",
                detail = "测试内容",
            )
        }
        while (PhoneConfirmationGate.pending.value == null) delay(10)
        val req1 = PhoneConfirmationGate.pending.value!!
        PhoneConfirmationGate.resolve(req1.id, allow = true)
        assertEquals(PhoneConfirmationGate.Outcome.APPROVED, approvedJob.await())
        assertNull(PhoneConfirmationGate.pending.value)

        // Reject
        val rejectedJob = async {
            PhoneConfirmationGate.request(
                context = null,
                rule = PhoneConfirmRule.DELETE_FILE,
                title = "删除文件",
                detail = "a.txt",
            )
        }
        while (PhoneConfirmationGate.pending.value == null) delay(10)
        val req2 = PhoneConfirmationGate.pending.value!!
        PhoneConfirmationGate.resolve(req2.id, allow = false)
        assertEquals(PhoneConfirmationGate.Outcome.REJECTED, rejectedJob.await())

        // Timeout
        val timedOut = PhoneConfirmationGate.request(
            context = null,
            rule = PhoneConfirmRule.PAYMENT,
            title = "支付",
            detail = "1元",
            timeoutMs = 80L,
        )
        assertEquals(PhoneConfirmationGate.Outcome.TIMED_OUT, timedOut)
        assertNull(PhoneConfirmationGate.pending.value)
    }

    @Test
    fun `task controller handles pause resume and stop`() = runBlocking {
        PhoneTaskController.clearHistory()
        PhoneTaskController.prepareForTurn("测试任务")
        assertEquals(PhoneTaskStatus.IDLE, PhoneTaskController.state.value.status)

        PhoneTaskController.beginAction("ui_click", "点击确定", "设置")
        assertEquals(PhoneTaskStatus.RUNNING, PhoneTaskController.state.value.status)
        assertNull(PhoneTaskController.awaitRunnableOrReason(100L))

        PhoneTaskController.pause("暂停中")
        assertEquals(PhoneTaskStatus.PAUSED, PhoneTaskController.state.value.status)
        val resumeJob = async {
            delay(60L)
            PhoneTaskController.resume()
        }
        assertNull(PhoneTaskController.awaitRunnableOrReason(1_000L))
        resumeJob.await()
        assertEquals(PhoneTaskStatus.RUNNING, PhoneTaskController.state.value.status)

        PhoneTaskController.finishAction("ui_click", "点击确定", "设置", true, "已点击", 25L)
        assertEquals(1, PhoneTaskController.state.value.stepCount)
        assertEquals(1, PhoneTaskController.state.value.steps.size)

        PhoneTaskController.stop("测试立即停止", stopAgentTurn = false)
        assertEquals(PhoneTaskStatus.STOPPED, PhoneTaskController.state.value.status)
        val reason = PhoneTaskController.awaitRunnableOrReason(200L)
        assertNotNull(reason)
        assertTrue(reason!!.contains("停止"))

        PhoneTaskController.clearHistory()
        PhoneTaskController.prepareForTurn("")
    }

    @Test
    fun `vision dhash detects identical vs different brightness gradients`() {
        val leftBright = IntArray(72) { i -> 200 - (i % 9) * 20 }
        val leftBright2 = IntArray(72) { i -> 195 - (i % 9) * 20 }
        val rightBright = IntArray(72) { i -> (i % 9) * 25 }

        val h1 = PhoneVisionAnalyzer.computeDHashFromLuma(leftBright)
        val h2 = PhoneVisionAnalyzer.computeDHashFromLuma(leftBright2)
        val h3 = PhoneVisionAnalyzer.computeDHashFromLuma(rightBright)

        assertEquals(16, h1.length)
        assertEquals(0, PhoneVisionAnalyzer.hammingDistance(h1, h2))
        assertTrue(PhoneVisionAnalyzer.hammingDistance(h1, h3) > 20)
    }

    @Test
    fun `tool labels translate all 18 phone tools without exposing raw IDs`() {
        for (def in PhoneToolRegistry.tools) {
            val label = toolLabel("phone.${def.name}", "{}")
            assertNotEquals("Tool ${def.name} fell back to generic label", "调用了外部能力", label)
            assertFalse("Tool ${def.name} leaked raw ID in '$label'", label.contains(def.name))
        }
    }

    @Test
    fun `thinking preview extracts clean concise summary and respects lifecycle`() {
        val sample = """
            **第一步**：先检查当前屏幕
            发现还没有打开设置页面，需要点击右上角「设置」按钮。
        """.trimIndent()
        val preview = PhoneTaskController.extractThinkingPreview(sample, maxChars = 68)
        assertFalse(preview.contains("**"))
        assertTrue(preview.contains("设置"))

        PhoneTaskController.clearHistory()
        var stopInvoked = false
        PhoneRuntime.onStopAgentTurn = { stopInvoked = true }
        try {
            PhoneTaskController.activateForPhoneTurn("打开设置")
            val activeState = PhoneTaskController.state.value
            assertEquals(PhoneTaskStatus.RUNNING, activeState.status)
            assertTrue(activeState.startupNotice.contains("通知栏"))
            assertTrue(activeState.startupNotice.contains("停止"))
            assertTrue(activeState.startupNoticeNonce > 0L)

            PhoneTaskController.appendThinkingDelta("**正在识别**\n需要点击「确定」按钮完成确认。")
            assertTrue(PhoneTaskController.state.value.thinkingPreview.contains("确定"))

            PhoneTaskController.stop("测试停止", stopAgentTurn = true)
            assertTrue(stopInvoked)
            assertEquals(PhoneTaskStatus.STOPPED, PhoneTaskController.state.value.status)
            assertEquals("", PhoneTaskController.state.value.thinkingPreview)
        } finally {
            PhoneRuntime.onStopAgentTurn = null
            PhoneTaskController.prepareForTurn("")
            PhoneTaskController.clearHistory()
        }
    }

    @Test
    fun `vision scaled dimensions default to 80 percent precision`() {
        assertEquals(864 to 1920, PhoneVisionAnalyzer.computeScaledDimensions(1080, 2400))
        assertEquals(1152 to 2560, PhoneVisionAnalyzer.computeScaledDimensions(1440, 3200, 0.80f))
        val clampedLow = PhoneVisionAnalyzer.computeScaledDimensions(1000, 1000, 0.05f)
        assertEquals(250 to 250, clampedLow)
    }

    @Test
    fun `ui_input schema documents clipboard paste strategy`() {
        val uiInput = PhoneToolRegistry.find("ui_input")!!
        assertTrue(uiInput.description.contains("剪贴板"))
        assertTrue(uiInput.description.contains("粘贴"))
        assertTrue(uiInput.description.contains("set_text"))
    }
}
