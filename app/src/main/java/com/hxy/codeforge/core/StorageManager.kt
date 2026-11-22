package com.hxy.codeforge.core

import java.io.File

/**
 * 三级存储空间管理（防止长期使用后应用私有目录持续膨胀）。
 *
 * 总原则：**用户工作区源码（非附件/非产物）与已安装插件本体绝不静默删除**；
 * 仅对可安全重建的缓存（Tier A）、受配额/TTL 管控的附件与操控产物（Tier B）执行自动回收，
 * 并把未关联任何会话的 `workspace/p-*` 孤儿项目目录标出供用户在设置页手动确认清理。
 *
 * 约束：本文件只依赖 `java.io.File`，不引 Android UI / Context，可在纯 JVM 单测全覆盖。
 */
object StorageManager {

    const val OUTBOX_QUOTA_BYTES = 100L * 1024 * 1024
    const val ATTACHMENTS_QUOTA_BYTES = 500L * 1024 * 1024
    const val PHONE_ARTIFACTS_QUOTA_BYTES = 300L * 1024 * 1024
    const val MAX_CRASH_LOGS = 5

    private const val DAY_MS = 24L * 3600 * 1000
    private const val WEEK_MS = 7L * 24 * 3600 * 1000

    data class BucketStats(
        val bytes: Long,
        val fileCount: Int,
    )

    data class OrphanWorkspace(
        val path: String,
        val name: String,
        val bytes: Long,
        val fileCount: Int,
        val lastModifiedMs: Long,
    )

    data class StorageReport(
        val totalBytes: Long,
        val tempAndCache: BucketStats,
        val containerApkCache: BucketStats,
        val attachmentsAndImports: BucketStats,
        val phoneArtifacts: BucketStats,
        val runtimeEnv: BucketStats,
        val projectsAndPlugins: BucketStats,
        val orphanWorkspaces: List<OrphanWorkspace>,
    ) {
        val reclaimableCacheBytes: Long get() = tempAndCache.bytes + containerApkCache.bytes
        val mediaAndArtifactsBytes: Long get() = attachmentsAndImports.bytes + phoneArtifacts.bytes
    }

    data class CleanResult(
        val deletedFiles: Int,
        val freedBytes: Long,
    )

    /** 列出受托管的工作区根目录（默认 `workspace`、`spaces/inbox` 及系统生成的 `workspace/p-*`）。 */
    internal fun managedWorkspaceRoots(filesDir: File): List<File> = buildList {
        add(File(filesDir, "workspace"))
        add(File(filesDir, "spaces/inbox"))
        File(filesDir, "workspace").takeIf { it.isDirectory }?.listFiles()?.forEach { d ->
            if (d.isDirectory && d.name.startsWith("p-")) add(d)
        }
    }

    private fun managedAttachmentDirs(filesDir: File): List<File> =
        managedWorkspaceRoots(filesDir).map { File(it, "attachments") }

    private fun managedImportDirs(filesDir: File): List<File> =
        managedWorkspaceRoots(filesDir).map { File(it, "imports") }

    private fun phoneArtifactDirs(filesDir: File): List<File> = buildList {
        managedWorkspaceRoots(filesDir).forEach { add(File(it, "phone_artifacts")) }
        add(File(filesDir, "phone_control/artifacts"))
    }

    /** 扫描全量存储占用并按三级分桶汇总（纯 IO，需在后台线程调用）。 */
    fun scanStorageUsage(
        filesDir: File,
        cacheDir: File,
        activeWorkspaces: Set<String> = emptySet(),
    ): StorageReport {
        val activeCanon = activeWorkspaces.mapNotNull { runCatching { File(it).canonicalPath }.getOrNull() }.toSet()

        // Tier A: 暂存与可全清缓存（outbox、logs、plugins/incoming、cacheDir 全部）
        val tempDirs = listOf(
            File(filesDir, "outbox"),
            File(filesDir, "logs"),
            File(filesDir, "plugins/incoming"),
            cacheDir,
        )
        val tempStats = sumStats(tempDirs, skipNames = emptySet())

        // Tier A-2: 容器包管理器缓存与临时目录（rootfs/var/cache/apk 与 rootfs/tmp）
        val apkCacheDirs = listOf(
            File(filesDir, "rootfs/var/cache/apk"),
            File(filesDir, "rootfs/tmp"),
        )
        val apkCacheStats = sumStats(apkCacheDirs, skipNames = emptySet())

        // Tier B: 托管 attachments（仅直接子文件）与 imports（整目录）
        var attBytes = 0L
        var attCount = 0
        managedAttachmentDirs(filesDir).forEach { dir ->
            val s = sumDirectFiles(dir)
            attBytes += s.bytes
            attCount += s.fileCount
        }
        val impStats = sumStats(managedImportDirs(filesDir), skipNames = emptySet())
        val attachmentsStats = BucketStats(attBytes + impStats.bytes, attCount + impStats.fileCount)

        // Tier B-2: 手机操控产物（phone_artifacts）
        val phoneStats = sumStats(phoneArtifactDirs(filesDir), skipNames = emptySet())

        // Tier C-1: 容器运行环境本体（rootfs 减去 apk 缓存/tmp + usr）
        val rootfsAll = sumStats(listOf(File(filesDir, "rootfs"), File(filesDir, "usr")), skipNames = emptySet())
        val runtimeEnvStats = BucketStats(
            bytes = (rootfsAll.bytes - apkCacheStats.bytes).coerceAtLeast(0L),
            fileCount = (rootfsAll.fileCount - apkCacheStats.fileCount).coerceAtLeast(0),
        )

        // Tier C-2: 用户项目工作区 + 已装插件本体（扣除已归入 Tier B 的 attachments/imports/phone_artifacts 与 plugins/incoming）
        val wsAndInboxTotal = sumStats(
            listOf(File(filesDir, "workspace"), File(filesDir, "spaces")),
            skipNames = emptySet(),
        )
        val pluginsAll = sumStats(listOf(File(filesDir, "plugins")), skipNames = setOf("incoming"))
        val protectedBytes = (wsAndInboxTotal.bytes - attachmentsStats.bytes - phoneStats.bytes + pluginsAll.bytes)
            .coerceAtLeast(0L)
        val protectedCount = (wsAndInboxTotal.fileCount - attachmentsStats.fileCount - phoneStats.fileCount + pluginsAll.fileCount)
            .coerceAtLeast(0)

        // 孤立工作区：workspace/p-* 下且不在 activeWorkspaces 中的目录
        val orphans = findOrphanWorkspaces(filesDir, activeCanon)

        val total = tempStats.bytes + apkCacheStats.bytes + attachmentsStats.bytes +
            phoneStats.bytes + runtimeEnvStats.bytes + protectedBytes

        return StorageReport(
            totalBytes = total,
            tempAndCache = tempStats,
            containerApkCache = apkCacheStats,
            attachmentsAndImports = attachmentsStats,
            phoneArtifacts = phoneStats,
            runtimeEnv = runtimeEnvStats,
            projectsAndPlugins = BucketStats(protectedBytes, protectedCount),
            orphanWorkspaces = orphans,
        )
    }

    /** 找出 `filesDir/workspace/p-*` 中不再被任何已知会话/项目引用的目录（已按规范路径比对）。 */
    fun findOrphanWorkspaces(filesDir: File, activeCanonicalPaths: Set<String>): List<OrphanWorkspace> {
        val root = File(filesDir, "workspace")
        if (!root.isDirectory) return emptyList()
        return root.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("p-") }
            ?.mapNotNull { dir ->
                val canon = runCatching { dir.canonicalPath }.getOrNull() ?: return@mapNotNull null
                if (canon in activeCanonicalPaths) return@mapNotNull null
                val stats = sumStats(listOf(dir), skipNames = emptySet())
                OrphanWorkspace(
                    path = canon,
                    name = dir.name,
                    bytes = stats.bytes,
                    fileCount = stats.fileCount,
                    lastModifiedMs = dir.lastModified(),
                )
            }
            ?.sortedByDescending { it.bytes }
            ?: emptyList()
    }

    /** 安全删除孤立工作区：只允许删除 `filesDir/workspace/p-*` 的直接子目录。 */
    fun deleteOrphanWorkspace(filesDir: File, targetDir: File): Boolean {
        val root = runCatching { File(filesDir, "workspace").canonicalFile }.getOrNull() ?: return false
        val target = runCatching { targetDir.canonicalFile }.getOrNull() ?: return false
        if (target.parentFile != root || !target.name.startsWith("p-") || !target.isDirectory) return false
        return runCatching { target.deleteRecursively() }.getOrDefault(false)
    }

    /**
     * 冷启动后台自动配额回收（Tier A + Tier B TTL/容量上限 + rootfs apk 缓存）。
     *
     * 约束：只清理托管 `attachments/` 的直接文件（不递归用户子目录，也不碰非 `p-*` 自定义项目）；
     * 绝不删除用户源码与已装插件。
     */
    fun enforceAutoQuota(
        filesDir: File,
        cacheDir: File,
        nowMs: Long = System.currentTimeMillis(),
        outboxQuotaBytes: Long = OUTBOX_QUOTA_BYTES,
        attachmentsQuotaBytes: Long = ATTACHMENTS_QUOTA_BYTES,
        phoneArtifactsQuotaBytes: Long = PHONE_ARTIFACTS_QUOTA_BYTES,
    ): CleanResult {
        var deleted = 0
        var freed = 0L
        val dayDeadline = nowMs - DAY_MS
        val weekDeadline = nowMs - WEEK_MS

        // 1. outbox：.tmp 立即清；满 24h 清；仍超配额按最旧淘汰
        val outboxDir = File(filesDir, "outbox")
        if (outboxDir.isDirectory) {
            outboxDir.listFiles()?.forEach { f ->
                if (f.isFile && (f.name.endsWith(".tmp") || f.lastModified() < dayDeadline)) {
                    val len = f.length()
                    if (runCatching { f.delete() }.getOrDefault(false)) {
                        deleted++; freed += len
                    }
                }
            }
            val r = trimDirectFilesToQuota(listOf(outboxDir), outboxQuotaBytes)
            deleted += r.deletedFiles; freed += r.freedBytes
        }

        // 2. cacheDir/camera & cacheDir/logs & plugins/incoming：满 24h 清
        listOf(File(cacheDir, "camera"), File(cacheDir, "logs"), File(filesDir, "plugins/incoming")).forEach { dir ->
            if (dir.isDirectory) {
                dir.listFiles()?.forEach { f ->
                    if (f.isFile && f.lastModified() < dayDeadline) {
                        val len = f.length()
                        if (runCatching { f.delete() }.getOrDefault(false)) {
                            deleted++; freed += len
                        }
                    }
                }
            }
        }

        // 3. filesDir/logs：最多保留最近 MAX_CRASH_LOGS 份
        val crashLogsDir = File(filesDir, "logs")
        if (crashLogsDir.isDirectory) {
            val sortedLogs = crashLogsDir.listFiles()?.filter { it.isFile }
                ?.sortedByDescending { it.lastModified() }
                .orEmpty()
            sortedLogs.drop(MAX_CRASH_LOGS).forEach { f ->
                val len = f.length()
                if (runCatching { f.delete() }.getOrDefault(false)) {
                    deleted++; freed += len
                }
            }
        }

        // 4. 容器 apk 下载缓存与 /tmp 过期文件（不影响已装包）
        val apkCache = File(filesDir, "rootfs/var/cache/apk")
        if (apkCache.isDirectory) {
            val r = cleanDirectoryContents(apkCache)
            deleted += r.deletedFiles; freed += r.freedBytes
        }
        val rootfsTmp = File(filesDir, "rootfs/tmp")
        if (rootfsTmp.isDirectory) {
            rootfsTmp.listFiles()?.forEach { f ->
                if (f.isFile && f.lastModified() < dayDeadline) {
                    val len = f.length()
                    if (runCatching { f.delete() }.getOrDefault(false)) {
                        deleted++; freed += len
                    }
                }
            }
        }

        // 5. 托管 attachments（仅直接文件，不递归子目录）：满 7 天清 + 总量超 500MB 按最旧淘汰
        val attDirs = managedAttachmentDirs(filesDir)
        attDirs.forEach { dir ->
            if (dir.isDirectory) {
                dir.listFiles()?.forEach { f ->
                    if (f.isFile && f.lastModified() < weekDeadline) {
                        val len = f.length()
                        if (runCatching { f.delete() }.getOrDefault(false)) {
                            deleted++; freed += len
                        }
                    }
                }
            }
        }
        val attTrim = trimDirectFilesToQuota(attDirs, attachmentsQuotaBytes)
        deleted += attTrim.deletedFiles; freed += attTrim.freedBytes

        // 6. 手机操控产物总配额控制（超 300MB 按最旧淘汰）
        val phoneTrim = trimFilesRecursiveToQuota(phoneArtifactDirs(filesDir), phoneArtifactsQuotaBytes)
        deleted += phoneTrim.deletedFiles; freed += phoneTrim.freedBytes

        return CleanResult(deleted, freed)
    }

    /** 手动一键清理 Tier A（全部暂存、logs、cacheDir、plugins/incoming 与容器 apk/tmp 缓存）。 */
    fun cleanTempAndContainerCaches(filesDir: File, cacheDir: File): CleanResult {
        var deleted = 0
        var freed = 0L
        listOf(
            File(filesDir, "outbox"),
            File(filesDir, "logs"),
            File(filesDir, "plugins/incoming"),
            File(filesDir, "rootfs/var/cache/apk"),
            File(filesDir, "rootfs/tmp"),
            cacheDir,
        ).forEach { dir ->
            val r = cleanDirectoryContents(dir)
            deleted += r.deletedFiles
            freed += r.freedBytes
        }
        return CleanResult(deleted, freed)
    }

    /** 手动清理 Tier B 手机操控产物与托管附件（保留工作区其它源码文件）。 */
    fun cleanPhoneArtifactsAndAttachments(filesDir: File): CleanResult {
        var deleted = 0
        var freed = 0L
        managedAttachmentDirs(filesDir).forEach { dir ->
            dir.listFiles()?.forEach { f ->
                if (f.isFile) {
                    val len = f.length()
                    if (runCatching { f.delete() }.getOrDefault(false)) {
                        deleted++; freed += len
                    }
                }
            }
        }
        managedImportDirs(filesDir).forEach { dir ->
            val r = cleanDirectoryContents(dir)
            deleted += r.deletedFiles; freed += r.freedBytes
        }
        phoneArtifactDirs(filesDir).forEach { dir ->
            val r = cleanDirectoryContents(dir)
            deleted += r.deletedFiles; freed += r.freedBytes
        }
        return CleanResult(deleted, freed)
    }

    private fun trimDirectFilesToQuota(dirs: List<File>, quotaBytes: Long): CleanResult {
        val files = dirs.filter { it.isDirectory }
            .flatMap { it.listFiles()?.filter { f -> f.isFile }.orEmpty() }
            .sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        var deleted = 0
        var freed = 0L
        for (f in files) {
            if (total <= quotaBytes) break
            val len = f.length()
            if (runCatching { f.delete() }.getOrDefault(false)) {
                total -= len
                deleted++
                freed += len
            }
        }
        return CleanResult(deleted, freed)
    }

    private fun trimFilesRecursiveToQuota(dirs: List<File>, quotaBytes: Long): CleanResult {
        val files = dirs.filter { it.isDirectory }
            .flatMap { d -> d.walkTopDown().filter { it.isFile }.toList() }
            .sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        var deleted = 0
        var freed = 0L
        for (f in files) {
            if (total <= quotaBytes) break
            val len = f.length()
            if (runCatching { f.delete() }.getOrDefault(false)) {
                total -= len
                deleted++
                freed += len
            }
        }
        return CleanResult(deleted, freed)
    }

    private fun cleanDirectoryContents(dir: File): CleanResult {
        if (!dir.isDirectory) return CleanResult(0, 0L)
        var deleted = 0
        var freed = 0L
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                val r = cleanDirectoryContents(child)
                deleted += r.deletedFiles
                freed += r.freedBytes
                runCatching { child.delete() }
            } else if (child.isFile) {
                val len = child.length()
                if (runCatching { child.delete() }.getOrDefault(false)) {
                    deleted++
                    freed += len
                }
            }
        }
        return CleanResult(deleted, freed)
    }

    private fun sumDirectFiles(dir: File): BucketStats {
        if (!dir.isDirectory) return BucketStats(0L, 0)
        var bytes = 0L
        var count = 0
        dir.listFiles()?.forEach { f ->
            if (f.isFile) {
                bytes += f.length()
                count++
            }
        }
        return BucketStats(bytes, count)
    }

    private fun sumStats(roots: List<File>, skipNames: Set<String>): BucketStats {
        var bytes = 0L
        var count = 0
        val seen = HashSet<String>()
        for (root in roots) {
            if (!root.exists()) continue
            val stack = ArrayDeque<File>()
            stack.add(root)
            while (stack.isNotEmpty()) {
                val cur = stack.removeLast()
                // 避免 symlink 重复统计或跳出（例如 rootfs 里的软链）
                val canon = runCatching { cur.canonicalPath }.getOrNull() ?: cur.absolutePath
                if (!seen.add(canon)) continue
                if (cur.isDirectory) {
                    cur.listFiles()?.forEach { child ->
                        if (child.name !in skipNames) stack.add(child)
                    }
                } else if (cur.isFile) {
                    bytes += cur.length()
                    count++
                }
            }
        }
        return BucketStats(bytes, count)
    }
}
