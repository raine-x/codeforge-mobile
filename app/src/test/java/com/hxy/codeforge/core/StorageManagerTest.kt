package com.hxy.codeforge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 三级存储空间管理（扫描分桶、自动配额回收、容器缓存清理、孤儿工作区安全校验）JVM 单测。
 */
class StorageManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun writeSized(parent: File, name: String, bytes: Int, mtime: Long): File {
        parent.mkdirs()
        return File(parent, name).apply {
            writeBytes(ByteArray(bytes) { 1 })
            setLastModified(mtime)
        }
    }

    @Test
    fun `scanStorageUsage splits temp, apk cache, attachments, phone artifacts, runtime, and protected assets`() {
        val files = tmp.newFolder("files")
        val cache = tmp.newFolder("cache")
        val now = 1_800_000_000_000L

        writeSized(File(files, "outbox"), "stage.bin", 100, now)
        writeSized(File(files, "plugins/incoming"), "tmp.cfplugin", 50, now)
        writeSized(File(cache, "camera"), "cam.jpg", 40, now)
        writeSized(File(files, "rootfs/var/cache/apk"), "pkg.apk", 200, now)
        writeSized(File(files, "rootfs/bin"), "busybox", 500, now)
        writeSized(File(files, "workspace/p-active/attachments"), "att.png", 80, now)
        writeSized(File(files, "workspace/p-active/phone_artifacts"), "shot.png", 120, now)
        writeSized(File(files, "workspace/p-active"), "main.kt", 300, now)
        writeSized(File(files, "workspace/p-orphan"), "old.kt", 400, now)
        writeSized(File(files, "plugins/myplug/1.0"), "SKILL.md", 60, now)

        val activePath = File(files, "workspace/p-active").canonicalPath
        val report = StorageManager.scanStorageUsage(files, cache, setOf(activePath))

        assertEquals(190L, report.tempAndCache.bytes)
        assertEquals(200L, report.containerApkCache.bytes)
        assertEquals(80L, report.attachmentsAndImports.bytes)
        assertEquals(120L, report.phoneArtifacts.bytes)
        assertEquals(500L, report.runtimeEnv.bytes)
        // main.kt (300) + old.kt (400) + SKILL.md (60)
        assertEquals(760L, report.projectsAndPlugins.bytes)
        assertEquals(1, report.orphanWorkspaces.size)
        assertEquals("p-orphan", report.orphanWorkspaces.single().name)
        assertEquals(400L, report.orphanWorkspaces.single().bytes)
    }

    @Test
    fun `enforceAutoQuota trims by quota, cleans apk cache and keeps user code safe`() {
        val files = tmp.newFolder("files")
        val cache = tmp.newFolder("cache")
        val now = 1_800_000_000_000L
        val fresh1h = now - 3600_000L
        val fresh2h = now - 2 * 3600_000L

        val outOld = writeSized(File(files, "outbox"), "a.bin", 600, fresh2h)
        val outNew = writeSized(File(files, "outbox"), "b.bin", 600, fresh1h)

        val attOld = writeSized(File(files, "workspace/p-1/attachments"), "old.png", 600, fresh2h)
        val attNew = writeSized(File(files, "workspace/p-1/attachments"), "new.png", 600, fresh1h)

        val artOld = writeSized(File(files, "workspace/p-1/phone_artifacts"), "shot1.png", 500, fresh2h)
        val artNew = writeSized(File(files, "workspace/p-1/phone_artifacts"), "shot2.png", 500, fresh1h)

        val apkCached = writeSized(File(files, "rootfs/var/cache/apk"), "python3.apk", 300, fresh1h)
        val userCode = writeSized(File(files, "workspace/p-1"), "App.kt", 1000, fresh2h)
        val pluginFile = writeSized(File(files, "plugins/p1/1.0"), "SKILL.md", 200, fresh2h)

        // 只保留 5 份崩溃日志
        val logs = (1..7).map { idx ->
            writeSized(File(files, "logs"), "crash-$idx.txt", 10, now - idx * 1000L)
        }

        val res = StorageManager.enforceAutoQuota(
            filesDir = files,
            cacheDir = cache,
            nowMs = now,
            outboxQuotaBytes = 700L,
            attachmentsQuotaBytes = 700L,
            phoneArtifactsQuotaBytes = 600L,
        )

        assertTrue(res.deletedFiles > 0)
        assertFalse(outOld.exists())
        assertTrue(outNew.exists())
        assertFalse(attOld.exists())
        assertTrue(attNew.exists())
        assertFalse(artOld.exists())
        assertTrue(artNew.exists())
        assertFalse(apkCached.exists())
        assertTrue("最新 5 份日志应保留", logs.take(5).all { it.exists() })
        assertTrue("第 6/7 旧日志应删除", logs.drop(5).none { it.exists() })
        assertTrue("受保护项目源码绝不能删除", userCode.exists())
        assertTrue("已安装插件绝不能删除", pluginFile.exists())
    }

    @Test
    fun `deleteOrphanWorkspace only permits direct p-prefix child dirs`() {
        val files = tmp.newFolder("files")
        val orphan = File(files, "workspace/p-dead").apply { mkdirs() }
        File(orphan, "x.txt").writeText("1")
        val custom = File(files, "workspace/my-repo").apply { mkdirs() }
        File(custom, "keep.txt").writeText("2")

        assertFalse(StorageManager.deleteOrphanWorkspace(files, custom))
        assertTrue(custom.exists())
        assertFalse(StorageManager.deleteOrphanWorkspace(files, File(files, "plugins")))

        assertTrue(StorageManager.deleteOrphanWorkspace(files, orphan))
        assertFalse(orphan.exists())
    }
}
