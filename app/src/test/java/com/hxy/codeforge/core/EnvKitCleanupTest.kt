package com.hxy.codeforge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 验证 EnvKit 暂存孤儿清理与跨工作区过期附件回收，以及只删 outbox 副本的目录边界守卫。
 */
class EnvKitCleanupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `cleanupOrphanDirs prunes stale outbox, camera, logs, and multi-workspace attachments`() {
        val filesDir = tmp.newFolder("files")
        val cacheDir = tmp.newFolder("cache")
        val now = 1_800_000_000_000L
        val old25h = now - 25L * 3600 * 1000
        val fresh1h = now - 1L * 3600 * 1000
        val old8d = now - 8L * 24 * 3600 * 1000
        val fresh2d = now - 2L * 24 * 3600 * 1000

        fun touch(parent: File, name: String, mtime: Long): File {
            parent.mkdirs()
            return File(parent, name).apply {
                writeText("x")
                setLastModified(mtime)
            }
        }

        val outboxTmp = touch(File(filesDir, "outbox"), "partial.mp4.tmp", now)
        val outboxOld = touch(File(filesDir, "outbox"), "old_video.mp4", old25h)
        val outboxFresh = touch(File(filesDir, "outbox"), "fresh_video.mp4", fresh1h)

        val cameraOld = touch(File(cacheDir, "camera"), "IMG_old.jpg", old25h)
        val cameraFresh = touch(File(cacheDir, "camera"), "IMG_fresh.jpg", fresh1h)

        val logOld = touch(File(cacheDir, "logs"), "CodeForge-logs-old.txt", old25h)
        val logFresh = touch(File(cacheDir, "logs"), "CodeForge-logs-fresh.txt", fresh1h)

        val wsLegacyOld = touch(File(filesDir, "workspace/attachments"), "legacy_old.png", old8d)
        val wsLegacyFresh = touch(File(filesDir, "workspace/attachments"), "legacy_fresh.png", fresh2d)
        val inboxOld = touch(File(filesDir, "spaces/inbox/attachments"), "inbox_old.mp4", old8d)
        val inboxFresh = touch(File(filesDir, "spaces/inbox/attachments"), "inbox_fresh.mp4", fresh2d)
        val projOld = touch(File(filesDir, "workspace/p-abc123/attachments"), "proj_old.mp4", old8d)
        val projFresh = touch(File(filesDir, "workspace/p-abc123/attachments"), "proj_fresh.mp4", fresh2d)
        val userDirKept = touch(File(filesDir, "workspace/p-abc123/attachments/stagedDir"), "keep.txt", old8d)
        val nonManagedProject = touch(File(filesDir, "workspace/custom/attachments"), "user.png", old8d)

        EnvKit.cleanupOrphanDirs(filesDir, cacheDir, nowMs = now)

        assertFalse("未完成的 .tmp 应立即清理", outboxTmp.exists())
        assertFalse("超 24h 的 outbox 孤儿应清理", outboxOld.exists())
        assertTrue("24h 内暂存附件应保留", outboxFresh.exists())

        assertFalse(cameraOld.exists())
        assertTrue(cameraFresh.exists())
        assertFalse(logOld.exists())
        assertTrue(logFresh.exists())

        assertFalse("旧默认工作区超 7 天附件应清理", wsLegacyOld.exists())
        assertTrue(wsLegacyFresh.exists())
        assertFalse("spaces/inbox 超 7 天附件应清理", inboxOld.exists())
        assertTrue(inboxFresh.exists())
        assertFalse("workspace/p-* 超 7 天附件应清理", projOld.exists())
        assertTrue(projFresh.exists())

        assertTrue("工作区子目录内文件不应被平层附件 GC 递归误删", userDirKept.exists())
        assertTrue("非 p-* 前缀目录不受托管清理影响", nonManagedProject.exists())
    }

    @Test
    fun `deleteFileIfInDir deletes only files directly inside outbox`() {
        val outbox = tmp.newFolder("outbox")
        val workspace = tmp.newFolder("workspace")
        val staged = File(outbox, "123_clip.mp4").apply { writeText("v") }
        val wsFile = File(workspace, "123_clip.mp4").apply { writeText("keep") }

        assertTrue(EnvKit.deleteFileIfInDir(outbox, staged.absolutePath))
        assertFalse(staged.exists())

        assertFalse(EnvKit.deleteFileIfInDir(outbox, wsFile.absolutePath))
        assertTrue(wsFile.exists())

        assertFalse(EnvKit.deleteFileIfInDir(outbox, "attachments/123_clip.mp4"))
    }

    @Test
    fun `stageOutboxFileToWorkspaceImports strips timestamp prefix and deduplicates cleanly`() {
        val workspace = tmp.newFolder("ws")
        val outbox = tmp.newFolder("staged")
        val src1 = File(outbox, "1730000000123_demo.mp4").apply { writeText("video-1") }
        val src2 = File(outbox, "1730000000456_demo.mp4").apply { writeText("video-2") }
        val noExt1 = File(outbox, "1730000000789_Makefile").apply { writeText("all:") }
        val noExt2 = File(outbox, "1730000000999_Makefile").apply { writeText("test:") }

        val dest1 = EnvKit.stageOutboxFileToWorkspaceImports(workspace, src1)
        val dest2 = EnvKit.stageOutboxFileToWorkspaceImports(workspace, src2)
        val destNoExt1 = EnvKit.stageOutboxFileToWorkspaceImports(workspace, noExt1)
        val destNoExt2 = EnvKit.stageOutboxFileToWorkspaceImports(workspace, noExt2)

        assertNotNull(dest1)
        assertNotNull(dest2)
        assertNotNull(destNoExt1)
        assertNotNull(destNoExt2)
        assertEquals("demo.mp4", dest1!!.name)
        assertEquals("demo-2.mp4", dest2!!.name)
        assertEquals("Makefile", destNoExt1!!.name)
        assertEquals("Makefile-2", destNoExt2!!.name)
        assertEquals("video-2", dest2.readText())
    }

    @Test
    fun `file manager primitives validate names, rename, safe delete, clear, and format size`() {
        val root = tmp.newFolder("fm-root")
        val outside = tmp.newFile("outside.txt").apply { writeText("keep") }

        assertFalse(EnvKit.isSafeEntryName(""))
        assertFalse(EnvKit.isSafeEntryName("."))
        assertFalse(EnvKit.isSafeEntryName(".."))
        assertFalse(EnvKit.isSafeEntryName("a/b"))
        assertFalse(EnvKit.isSafeEntryName("a:b"))
        assertTrue(EnvKit.isSafeEntryName("my-folder_01"))

        val sub = EnvKit.createSubdir(root, "assets")
        assertNotNull(sub)
        assertTrue(sub!!.isDirectory)
        assertNull(EnvKit.createSubdir(root, "assets"))

        val f = File(sub, "raw.txt").apply { writeText("abc") }
        val renamed = EnvKit.renameEntry(f, "renamed.txt")
        assertNotNull(renamed)
        assertTrue(renamed!!.isFile)
        assertFalse(f.exists())

        assertFalse("不能删根目录本身", EnvKit.deleteRecursivelySafe(root, root))
        assertFalse("不能越权删根外部文件", EnvKit.deleteRecursivelySafe(outside, root))
        assertTrue(outside.exists())

        assertTrue(EnvKit.deleteRecursivelySafe(sub, root))
        assertFalse(sub.exists())

        File(root, "a.tmp").writeText("1")
        File(root, "nested").apply { mkdirs() }.let { File(it, "b.tmp").writeText("2") }
        assertEquals(2, EnvKit.clearDirectoryContents(root))
        assertTrue(root.isDirectory)
        assertEquals(0, root.listFiles()?.size ?: -1)

        assertEquals("512 B", EnvKit.formatFileSize(512L))
        assertEquals("20.0 MB", EnvKit.formatFileSize(20L * 1024 * 1024))
    }
}
