package com.hxy.codeforge.ui.chat

import com.hxy.codeforge.ui.attachmentLimitBanner
import com.hxy.codeforge.ui.isAttachmentLimitError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守护壳侧视频直选/分享入口、去除 20MiB 客户端拦截及受限直达设置提示。
 */
class VideoAttachmentTest {

    private fun readAppFile(relative: String): String = listOf(
        File("src/main/$relative"),
        File("app/src/main/$relative"),
    ).firstOrNull { it.exists() }?.readText().orEmpty()

    @Test
    fun `composer gallery picker accepts images and videos`() {
        val composer = readAppFile("java/com/hxy/codeforge/ui/chat/Composer.kt")
        assertTrue("Composer.kt 应可读", composer.isNotEmpty())
        assertTrue(composer.contains("PickVisualMedia.ImageAndVideo"))
        assertFalse(composer.contains("PickVisualMedia.ImageOnly"))
        assertFalse(composer.contains("20MiB"))
    }

    @Test
    fun `envkit and workspace remove 20MiB client limit and route oversized files to imports`() {
        val envKit = readAppFile("java/com/hxy/codeforge/core/EnvKit.kt")
        val workspace = readAppFile("java/com/hxy/codeforge/ui/ChatViewModelWorkspace.kt")
        val vm = readAppFile("java/com/hxy/codeforge/ui/ChatViewModel.kt")
        assertTrue(envKit.isNotEmpty())
        assertTrue(workspace.isNotEmpty())
        assertTrue(vm.isNotEmpty())
        assertFalse(envKit.contains("onTooLarge"))
        assertTrue(envKit.contains("fun stageOutboxFileToWorkspaceImports"))
        assertFalse(workspace.contains("20MiB"))
        assertTrue(vm.contains("stageOutboxFileToWorkspaceImports"))
        assertTrue(vm.contains("KERNEL_UPLOAD_LIMIT_BYTES"))
    }

    @Test
    fun `manifest accepts incoming video shares`() {
        val manifest = readAppFile("AndroidManifest.xml")
        assertTrue(manifest.isNotEmpty())
        assertTrue(manifest.contains("""android:mimeType="video/*""""))
    }

    @Test
    fun `attachment size limit errors produce file transfer settings banner`() {
        assertTrue(isAttachmentLimitError("HTTP 413：http: request body too large"))
        assertTrue(isAttachmentLimitError("每段视频不能超过20MiB"))
        assertTrue(isAttachmentLimitError("总大小不能超过20MiB"))
        assertFalse(isAttachmentLimitError("网络连接超时"))

        val banner = attachmentLimitBanner("HTTP 413：http: request body too large")
        assertTrue(banner.isError)
        assertTrue(banner.openFileTransfer)
        assertEquals(
            "上传受限（HTTP 413：http: request body too large），请前往「设置 → 文件传输」直接将文件上传至工作区",
            banner.text,
        )
    }
}
