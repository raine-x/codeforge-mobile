package com.hxy.codeforge.core

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths

/**
 * proot + Alpine rootfs 兼容环境生命周期管理。
 * 所有路径锚定应用私有目录 `filesDir`（`$CELL`），通过 `proot -b` 保持宿主机与容器路径一致，
 * 并按顺序探测可用 Android 存储目录绑定至 `$CELL/mnt/android`。
 */
object EnvKit {

    sealed class Status {
        data object Idle : Status()
        data class Preparing(val step: String, val percent: Int) : Status()
        data object Ready : Status()
        data class Failed(val message: String) : Status()
    }

    private const val ENV_KIT_ASSET = "env-kit.tgz"
    private const val ROOTFS_ASSET = "rootfs-alpine.tgz"
    private const val READY_MARKER = ".env-ready"

    /** 内核 /api/upload_file 与视频 Base64 编码的 20MiB 上限；超限改由壳直接写入工作区 imports/。 */
    const val KERNEL_UPLOAD_LIMIT_BYTES = 20L * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val status = kotlinx.coroutines.flow.MutableStateFlow<Status>(Status.Idle)

    // 路径契约

    fun cellRoot(context: Context): File = context.filesDir
    fun usrBin(context: Context): File = File(cellRoot(context), "usr/bin")
    fun rootfsDir(context: Context): File = File(cellRoot(context), "rootfs")
    fun tmpDir(context: Context): File = File(cellRoot(context), "tmp")
    fun cfrun(context: Context): File = File(usrBin(context), "cfrun")

    fun androidMnt(context: Context): File = File(cellRoot(context), "mnt/android")

    /** 为未关联项目的会话分配经过白名单清洗的独立子目录。 */
    fun sessionSpace(context: Context, sessionId: String): File {
        val safe = sessionId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48).ifEmpty { "default" }
        return ensureWr(File(File(cellRoot(context), "spaces"), safe))
    }

    /** 未选定项目时使用的默认稳定工作区，确保会话归属与附件相对路径同源。 */
    fun inboxSpace(context: Context): File =
        ensureWr(File(File(cellRoot(context), "spaces"), "inbox"))

    fun workspaceRoot(context: Context): File = ensureWr(File(cellRoot(context), "workspace"))

    /** 在创建会话前生成稳定的短项目目录标识（`p-<12位>`）。 */
    fun newProjectId(): String =
        "p-" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)

    fun newProjectWorkspace(context: Context, projectId: String): File =
        ensureWr(File(workspaceRoot(context), projectId))

    /** 附件私有暂存目录；因选择时会话可能尚未确立，发送时再通过接口或直写同步入工作区。 */
    fun outboxDir(context: Context): File =
        File(cellRoot(context), "outbox").apply { mkdirs() }

    fun deleteOutboxFileIfStaged(context: Context, path: String) {
        deleteFileIfInDir(outboxDir(context), path)
    }

    internal fun deleteFileIfInDir(dir: File, path: String): Boolean {
        val f = File(path)
        if (!f.isAbsolute || !f.isFile) return false
        val expectedDir = runCatching { dir.canonicalFile }.getOrNull() ?: return false
        val actualDir = runCatching { f.parentFile?.canonicalFile }.getOrNull() ?: return false
        if (actualDir != expectedDir) return false
        return runCatching { f.delete() }.getOrDefault(false)
    }

    fun cleanupOrphansAsync(context: Context) {
        val files = context.filesDir
        val cache = context.cacheDir
        scope.launch { runCatching { cleanupOrphanDirs(files, cache) } }
    }

    internal fun cleanupOrphanDirs(
        filesDir: File,
        cacheDir: File,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        StorageManager.enforceAutoQuota(filesDir, cacheDir, nowMs)
    }

    /** 将 `content://` Uri 原子暂存至 [outboxDir]（同目录临时文件 + rename，并保留可执行位）。 */
    suspend fun stageUriToOutbox(
        context: Context,
        uri: Uri,
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            var name = resolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            } ?: "file"
            name = sanitizeEntryName(name, "file")
            val out = File(outboxDir(context), System.currentTimeMillis().toString() + "_" + name)
            val tmp = File(out.parentFile, out.name + ".tmp")
            try {
                resolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tmp).use { output -> input.copyTo(output) }
                } ?: return@withContext null
                if (!tmp.renameTo(out)) {
                    return@withContext null
                }
            } finally {
                if (tmp.exists()) tmp.delete()
            }
            out.setExecutable(true, false)
            out.absolutePath
        }.getOrNull()
    }

    suspend fun stageFileToOutbox(
        context: Context,
        src: File,
    ): String? = withContext(Dispatchers.IO) {
        if (!src.isFile) return@withContext null
        runCatching {
            val clean = sanitizeEntryName(src.name, "file")
            val out = File(outboxDir(context), "${System.currentTimeMillis()}_$clean")
            val tmp = File(out.parentFile, "${out.name}.tmp")
            try {
                src.inputStream().use { input ->
                    FileOutputStream(tmp).use { output -> input.copyTo(output) }
                }
                if (!tmp.renameTo(out)) return@withContext null
            } finally {
                if (tmp.exists()) tmp.delete()
            }
            out.setExecutable(true, false)
            out.absolutePath
        }.getOrNull()
    }

    /** 将超限 outbox 文件原子转存到工作区 `imports/` 目录。 */
    internal fun stageOutboxFileToWorkspaceImports(workspaceDir: File, srcFile: File): File? {
        if (!srcFile.isFile) return null
        val importsDir = File(workspaceDir, "imports").apply { mkdirs() }
        val target = uniqueChildFile(importsDir, stripOutboxTimestampPrefix(srcFile.name), "file")
        val tmp = File(importsDir, ".${target.name}.tmp")
        return try {
            srcFile.inputStream().use { input ->
                FileOutputStream(tmp).use { output -> input.copyTo(output) }
            }
            if (!tmp.renameTo(target)) return null
            target.setExecutable(true, false)
            target
        } catch (_: Throwable) {
            null
        } finally {
            if (tmp.exists()) runCatching { tmp.delete() }
        }
    }

    internal fun stripOutboxTimestampPrefix(name: String): String =
        name.replaceFirst(Regex("^\\d{10,}_"), "").ifBlank { name }

    internal fun sanitizeEntryName(rawName: String, fallbackName: String = "file"): String =
        rawName.replace(Regex("[/\\\\\u0000:]"), "_").trim().take(80).ifEmpty { fallbackName }

    internal fun uniqueChildFile(
        dir: File,
        rawName: String,
        fallbackName: String = "file",
    ): File {
        val clean = sanitizeEntryName(rawName, fallbackName)
        var candidate = File(dir, clean)
        if (!candidate.exists()) return candidate
        val stem = candidate.nameWithoutExtension.ifEmpty { clean }
        val ext = candidate.extension
        var n = 2
        while (candidate.exists()) {
            val nextName = if (ext.isEmpty()) "$stem-$n" else "$stem-$n.$ext"
            candidate = File(dir, nextName)
            n++
        }
        return candidate
    }

    /** 按优先级解析首个可读外部/外私下载目录（targetSdk 28 可直接写入，无需 SAF 授权）。 */
    fun publicDownloadsDir(context: Context): File? {
        val candidates = buildList {
            add(File("/storage/emulated/0/Download"))
            add(File("/sdcard/Download"))
            externalFilesDir(context)?.let { add(File(it, "Download")) }
        }
        return candidates.firstOrNull { dir ->
            (dir.exists() || runCatching { dir.mkdirs() }.getOrDefault(false)) &&
                runCatching { dir.canWrite() }.getOrDefault(false)
        }
    }

    /** 复制文件到 `Download/CodeForge/`（重名追加序号）。 */
    fun exportFileToDownloads(context: Context, src: File): File? {
        if (!src.isFile) return null
        val dir = File(publicDownloadsDir(context) ?: return null, "CodeForge").apply { mkdirs() }
        val target = uniqueChildFile(dir, src.name, "export")
        return runCatching {
            src.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target
        }.getOrNull()
    }

    /** 将非空目录打包导出至 `Download/CodeForge/`；目录内无文件返回 null。 */
    fun exportDirectoryAsZip(context: Context, dir: File, zipName: String): File? {
        if (!dir.isDirectory || dir.walkTopDown().none { it.isFile }) return null
        val outDir = File(publicDownloadsDir(context) ?: return null, "CodeForge").apply { mkdirs() }
        val target = uniqueChildFile(outDir, zipName, "export.zip")
        return runCatching {
            java.util.zip.ZipOutputStream(target.outputStream().buffered()).use { zip ->
                val base = dir.toPath()
                dir.walkTopDown().forEach { f ->
                    if (f.isFile) {
                        val rel = base.relativize(f.toPath()).toString().replace('\\', '/')
                        zip.putNextEntry(java.util.zip.ZipEntry(rel))
                        f.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
            target
        }.getOrNull()
    }

    /** 将系统选择器返回的 Uri 持久化写入指定工作区子目录。 */
    fun importFileFromUri(context: Context, uri: Uri, baseDir: File? = null, subdir: String = "imports"): File? {
        val resolver = context.contentResolver
        val rawName = resolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        } ?: "imported"
        val base = baseDir ?: inboxSpace(context)
        val dir = (if (subdir.isBlank()) base else File(base, subdir)).apply { mkdirs() }
        val target = uniqueChildFile(dir, rawName, "imported")
        return runCatching {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            target
        }.getOrNull()
    }

    // 文件管理原语

    /** 校验新建目录 / 重命名目标名是否合法，拒绝路径穿越与特殊控制字符。 */
    internal fun isSafeEntryName(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") return false
        return !Regex("[/\\\\\u0000:]").containsMatchIn(trimmed)
    }

    internal fun createSubdir(parentDir: File, name: String): File? {
        if (!parentDir.isDirectory || !isSafeEntryName(name)) return null
        val target = File(parentDir, name.trim())
        if (target.exists()) return null
        return if (runCatching { target.mkdir() }.getOrDefault(false)) target else null
    }

    internal fun renameEntry(entry: File, newName: String): File? {
        if (!entry.exists() || !isSafeEntryName(newName)) return null
        val parent = entry.parentFile ?: return null
        val target = File(parent, newName.trim())
        if (target.exists()) return null
        return if (runCatching { entry.renameTo(target) }.getOrDefault(false)) target else null
    }

    /** 仅当 [entry] 严格位于 [allowedRoot] 之下（绝不删根本身）时才执行删除。 */
    internal fun deleteRecursivelySafe(entry: File, allowedRoot: File): Boolean {
        val entryCanon = runCatching { entry.canonicalFile }.getOrNull() ?: return false
        val rootCanon = runCatching { allowedRoot.canonicalFile }.getOrNull() ?: return false
        if (entryCanon == rootCanon) return false
        val prefix = rootCanon.path.trimEnd(File.separatorChar) + File.separator
        if (!entryCanon.path.startsWith(prefix)) return false
        return runCatching { entryCanon.deleteRecursively() }.getOrDefault(false)
    }

    /** 清空目录内容但保留目录本身，返回成功清理的直接子项数。 */
    internal fun clearDirectoryContents(dir: File): Int {
        if (!dir.isDirectory) return 0
        var removed = 0
        dir.listFiles()?.forEach { child ->
            if (runCatching { child.deleteRecursively() }.getOrDefault(false)) {
                removed++
            }
        }
        return removed
    }

    internal fun formatFileSize(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        return when {
            safe < 1024L -> "$safe B"
            safe < 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f KB", safe / 1024.0)
            safe < 1024L * 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MB", safe / (1024.0 * 1024.0))
            else -> String.format(java.util.Locale.US, "%.2f GB", safe / (1024.0 * 1024.0 * 1024.0))
        }
    }

    fun deviceStorageRoot(context: Context): File? = androidStorage(context)

    /** 确保目录存在且 rwx（app uid 属主；x 位是脚本/二进制可执行的前提）。 */
    private fun ensureWr(dir: File): File {
        dir.mkdirs()
        dir.setExecutable(true, false)
        dir.setWritable(true, false)
        dir.setReadable(true, false)
        return dir
    }

    fun isReady(context: Context): Boolean =
        File(cellRoot(context), READY_MARKER).exists() && cfrun(context).canExecute()

    /** 应用外部私有目录（免权限可读写；无外置存储/未挂载时系统返回 null）。 */
    private fun externalFilesDir(context: Context): File? =
        runCatching { context.getExternalFilesDir(null) }.getOrNull()

    /** 按优先级解析首个可读 Android 存储目录（顺序须与 [storageCandidates] 一致），无可用目录时静默回退 null。 */
    private fun androidStorage(context: Context): File? =
        listOf(File("/storage/emulated/0"), File("/sdcard"), externalFilesDir(context))
            .filterNotNull()
            .firstOrNull { it.exists() && it.canRead() }

    /** 构造内核及子进程共用的环境变量表，确保宿主与容器路径一致。 */
    fun kernelEnv(context: Context): Map<String, String> = buildMap {
        put("HOME", cellRoot(context).absolutePath)
        put("TMPDIR", tmpDir(context).absolutePath)
        put("PATH", usrBin(context).absolutePath + ":/system/bin")
        put("CF_EXEC_WRAPPER", "/system/bin/sh " + cfrun(context).absolutePath)
        put("PROOT_TMP_DIR", tmpDir(context).absolutePath)
        put("CF_ANDROID_STORAGE", androidStorage(context)?.absolutePath.orEmpty())
    }

    fun refresh(context: Context) {
        if (status.value is Status.Preparing) return
        status.value = if (isReady(context)) Status.Ready else Status.Idle
    }

    /** 环境软件包定义；[probe] 不得包含管道，需由目标命令自身退出码判定安装态（P-051）。 */
    data class EnvPackage(val label: String, val installCmd: String, val probe: String)

    val packages = listOf(
        EnvPackage("Python", "apk add --no-cache python3 py3-pip", "python3 --version"),
        EnvPackage("Node.js", "apk add --no-cache nodejs npm", "node --version"),
        EnvPackage("Go", "apk add --no-cache go", "go version"),
        EnvPackage("C/C++", "apk add --no-cache build-base", "gcc --version"),
        EnvPackage("Rust", "apk add --no-cache cargo rust", "rustc --version"),
        EnvPackage("Git", "apk add --no-cache git", "git --version"),
        EnvPackage("Java 17", "apk add --no-cache openjdk17-jre-headless", "java -version"),
        EnvPackage("FFmpeg", "apk add --no-cache ffmpeg", "ffmpeg -version"),
        EnvPackage(
            "逆向工具 (binutils/file/radare2)",
            "apk add --no-cache file binutils patchelf radare2 openssl py3-capstone py3-unicorn py3-cryptography",
            "r2 -v",
        ),
        EnvPackage(
            "APK 逆向 (apktool/签名)",
            "apk add --no-cache openjdk17-jre-headless zip unzip openssl ca-certificates && " +
                "mkdir -p /usr/local/share/apktool && " +
                "(wget -q -T 25 -O /usr/local/share/apktool/apktool.jar https://bitbucket.org/iBotPeaches/apktool/downloads/apktool_2.11.1.jar || " +
                "wget -q -T 25 -O /usr/local/share/apktool/apktool.jar https://github.com/iBotPeaches/Apktool/releases/download/v2.11.1/apktool_2.11.1.jar) && " +
                "printf '#!/bin/sh\nexec java -Xmx1024m -jar /usr/local/share/apktool/apktool.jar \"$@\"\n' > /usr/bin/apktool && " +
                "chmod +x /usr/bin/apktool",
            "apktool -version",
        ),
    )

    /** 在 rootfs 中阻塞执行命令（仅限 IO 线程调用），并确保 DNS 与软件源就绪。 */
    fun runInRootfs(
        context: Context,
        command: String,
        onLine: (String) -> Unit,
    ): Int {
        val app = context.applicationContext
        if (!isReady(app)) {
            onLine("环境未就绪（设置 → 兼容环境 → 立即准备）")
            return -1
        }
        ensureNet(app)
        syncCfrun(app)
        return try {
            val proc = ProcessBuilder("/system/bin/sh", cfrun(app).absolutePath, command)
                .redirectErrorStream(true)
                .start()
            proc.inputStream.bufferedReader().forEachLine(onLine)
            proc.waitFor()
        } catch (e: Exception) {
            onLine("执行失败：${e.message}")
            -1
        }
    }

    fun isPackageInstalled(context: Context, pkg: EnvPackage): Boolean =
        runInRootfs(context, pkg.probe + " >/dev/null 2>&1") { } == 0

    /**
     * 写入 DNS 与 Alpine 多源软件源配置。
     * 注意 `repositories` 可能为空文件（用 ifBlank/格式检查处理）且镜像路径需带 `v` 前缀（P-020）。
     */
    private fun ensureNet(context: Context) {
        runCatching {
            val etc = File(rootfsDir(context), "etc")
            File(etc, "resolv.conf").writeText("nameserver 223.5.5.5\nnameserver 8.8.8.8\n")

            val repo = File(etc, "apk/repositories")
            repo.parentFile?.mkdirs()

            val branch = File(etc, "alpine-release")
                .takeIf { it.exists() }
                ?.readText()
                ?.trim()
                ?.split('.')
                ?.take(2)
                ?.joinToString(".")
                ?.takeIf { it.isNotEmpty() }
                ?.let { "v$it" }
                ?: "v3.24"

            val existing = repo.takeIf { it.exists() }?.readText().orEmpty()
            val content = if (existing.contains("/alpine/v")) {
                existing.replace("dl-cdn.alpinelinux.org", "mirrors.ustc.edu.cn")
            } else {
                listOf(
                    "mirrors.aliyun.com",
                    "mirrors.ustc.edu.cn",
                    "mirror.nju.edu.cn",
                ).joinToString("") { host ->
                    "https://$host/alpine/$branch/main\n" +
                        "https://$host/alpine/$branch/community\n"
                }
            }
            repo.writeText(content)
        }
    }

    /** 幂等同步 `cfrun` 脚本，保证应用更新后立即覆盖旧版脚本（P-014）。 */
    private fun syncCfrun(context: Context) {
        runCatching {
            val f = cfrun(context)
            val script = cfrunScript(cellRoot(context), externalFilesDir(context))
            if (!f.exists() || f.readText() != script) {
                File(tmpDir(context), ".proot-probe").delete()
                f.writeText(script)
                f.setExecutable(true, false)
                log(context, "syncCfrun: 检测到旧版脚本，已重写")
            }
        }
    }

    fun ensureReady(context: Context) {
        val app = context.applicationContext
        when {
            isReady(app) -> {
                status.value = Status.Ready
                scope.launch { syncCfrun(app) }
            }
            status.value is Status.Preparing -> return
            else -> status.value = Status.Idle
        }
        if (status.value != Status.Idle) return
        status.value = Status.Preparing("环境准备", 0)
        scope.launch {
            try {
                prepare(app)
                status.value = Status.Ready
            } catch (e: Exception) {
                status.value = Status.Failed("兼容环境准备失败：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** 在 IO 线程清理 rootfs 与就绪标记，避免数千文件同步删除引发主线程 ANR。 */
    fun reset(context: Context) {
        val app = context.applicationContext
        status.value = Status.Idle
        scope.launch {
            log(app, "reset: 清理 rootfs…")
            rootfsDir(app).deleteRecursively()
            File(tmpDir(app), ".proot-probe").delete()
            File(cellRoot(app), READY_MARKER).delete()
            log(app, "reset: 完成")
        }
    }

    /** 重新准备 = 删干净 + 完整重来（全程 IO 线程，UI 只看 status）。 */
    fun reprepare(context: Context) {
        val app = context.applicationContext
        when {
            status.value is Status.Preparing -> return
            isReady(app) -> Unit
            else -> {
                ensureReady(app)
                return
            }
        }
        status.value = Status.Idle
        scope.launch {
            try {
                log(app, "reprepare: 清理旧环境…")
                rootfsDir(app).deleteRecursively()
                File(tmpDir(app), ".proot-probe").delete()
                File(cellRoot(app), READY_MARKER).delete()
                log(app, "reprepare: 重新解压…")
                prepare(app)
                status.value = Status.Ready
                log(app, "reprepare: 就绪")
            } catch (e: Exception) {
                log(app, "reprepare 失败: $e")
                status.value = Status.Failed("兼容环境准备失败：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** 环境流水线日志（filesDir/logs/env.log，截断式只留最近一次）。 */
    private fun log(context: Context, line: String) {
        runCatching {
            val f = File(File(context.filesDir, "logs"), "env.log")
            f.parentFile?.mkdirs()
            if (!f.exists() || f.length() > 256 * 1024) f.writeText("")
            f.appendText("[${System.currentTimeMillis()}] $line\n")
        }
    }

    private fun prepare(context: Context) {
        val cell = cellRoot(context)
        log(context, "prepare: 开始（cell=${cell.absolutePath}）")
        tmpDir(context).mkdirs()
        usrBin(context).mkdirs()
        // 存储稳定入口的挂载点：宿主先建好（$CELL 整体绑进 guest，guest 里同路径可见），
        // cfrun 只在有可用候选时才往上面叠绑；没有可用存储时它就是个空目录
        androidMnt(context).mkdirs()

        // 1) proot 工具件（~140KB）
        status.value = Status.Preparing("安装 proot", 5)
        extractTarGz(context, ENV_KIT_ASSET, cell, weighEntries = 160) { p, _ ->
            status.value = Status.Preparing("安装 proot", p / 20)
        }

        // 2) Alpine rootfs（~4MB，几千个条目）
        extractTarGz(context, ROOTFS_ASSET, rootfsDir(context), weighEntries = 3_800) { p, _ ->
            status.value = Status.Preparing("解压 Linux 环境", 5 + p * 9 / 10)
        }

        // 3) 统一执行入口
        status.value = Status.Preparing("写入执行入口", 96)
        cfrun(context).writeText(cfrunScript(cell, externalFilesDir(context)))
        cfrun(context).setExecutable(true, false)
        File(cell, READY_MARKER).writeText("alpine-3.24.3/proot-5.1.107.101\n")
        log(context, "prepare: 完成")
        status.value = Status.Preparing("完成", 100)
    }

    /**
     * 生成统一命令包装脚本 `cfrun`：proot 可用时进入 Alpine rootfs 执行，
     * 在 ptrace 受限设备上自动降级至 Android 原生 shell。
     */
    private fun cfrunScript(cell: File, externalFiles: File?): String = """
        #!/system/bin/sh
        # 由 EnvKit 自动生成；显式指定 PROOT_TMP_DIR/PROOT_LOADER/LD_LIBRARY_PATH（P-014）
        CELL=${cell.absolutePath}
        ROOT=${'$'}CELL/rootfs
        USR=${'$'}CELL/usr
        CMD="${'$'}*"
        export PROOT_TMP_DIR="${'$'}CELL/tmp"
        export PROOT_LOADER="${'$'}USR/libexec/proot/loader"
        export LD_LIBRARY_PATH="${'$'}USR/lib${'$'}{LD_LIBRARY_PATH:+:${'$'}LD_LIBRARY_PATH}"
        mkdir -p "${'$'}PROOT_TMP_DIR" "${'$'}CELL/mnt/android"
        # 执行前探测 ptrace/proot 可用性并缓存至 .proot-probe，避免受限环境中工具整体失效
        PROOT_OK=0
        if [ -x "${'$'}ROOT/bin/busybox" ] && [ -x "${'$'}USR/bin/proot" ]; then
          CACHE="${'$'}PROOT_TMP_DIR/.proot-probe"
          PROBE=""
          [ -r "${'$'}CACHE" ] && IFS= read -r PROBE < "${'$'}CACHE" 2>/dev/null
          if [ "${'$'}PROBE" != "ok" ] && [ "${'$'}PROBE" != "fail" ]; then
            if HOME="${'$'}CELL" "${'$'}USR/bin/proot" -0 -r "${'$'}ROOT" -b "${'$'}CELL:${'$'}CELL" /bin/busybox true >/dev/null 2>&1; then
              PROBE="ok"
            else
              PROBE="fail"
            fi
            echo "${'$'}PROBE" > "${'$'}CACHE" 2>/dev/null
          fi
          [ "${'$'}PROBE" = "ok" ] && PROOT_OK=1
        fi
        if [ "${'$'}PROOT_OK" = "1" ]; then
          BINDS=""
          for D in /system/bin /system/lib64 /system/lib /system/etc; do
            [ -e "${'$'}D" ] && BINDS="${'$'}BINDS -b ${'$'}D:${'$'}D"
          done
          STORAGE=""
          for D in ${storageCandidates(externalFiles)}; do
            if [ -e "${'$'}D" ] && [ -r "${'$'}D" ]; then
              BINDS="${'$'}BINDS -b ${'$'}D:${'$'}D"
              [ -n "${'$'}STORAGE" ] || STORAGE="${'$'}D"
            fi
          done
          if [ -n "${'$'}STORAGE" ]; then
            BINDS="${'$'}BINDS -b ${'$'}STORAGE:${'$'}CELL/mnt/android"
          fi
          export HOME="${'$'}CELL"
          export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
          export TMPDIR=/tmp
          export TERM=xterm
          exec "${'$'}USR/bin/proot" \
            -0 \
            -r "${'$'}ROOT" \
            -b "${'$'}CELL:${'$'}CELL" \
            -b "${'$'}CELL/tmp:/tmp" \
            -b /proc:/proc \
            -b /dev:/dev \
            ${'$'}BINDS \
            /bin/sh -c "${'$'}CMD"
        else
          export HOME="${'$'}CELL"
          export TMPDIR="${'$'}PROOT_TMP_DIR"
          export PATH="/product/bin:/apex/com.android.art/bin:/apex/com.android.runtime/bin:/system/bin:/system/xbin:/vendor/bin${'$'}{PATH:+:${'$'}PATH}"
          exec /system/bin/sh -c "${'$'}CMD"
        fi
    """.trimIndent() + "\n"

    private fun storageCandidates(externalFiles: File?): String = listOfNotNull(
        "/storage/emulated/0",
        "/sdcard",
        externalFiles?.absolutePath,
    ).joinToString(" ") { "\"$it\"" }

    /** 流式解包 tar.gz；绝对路径符号链接统一重写至 [targetDir] 内。 */
    private fun extractTarGz(
        context: Context,
        asset: String,
        targetDir: File,
        weighEntries: Int,
        onProgress: (Int, String) -> Unit,
    ) {
        val input = context.assets.open(asset)
        val gunzip = java.util.zip.GZIPInputStream(input, 64 * 1024)
        var processed = 0
        val header = ByteArray(512)

        fun readFully(buf: ByteArray) {
            var n = 0
            while (n < buf.size) {
                val r = gunzip.read(buf, n, buf.size - n)
                if (r < 0) throw IOException("tar 流意外结束")
                n += r
            }
        }

        while (true) {
            readFully(header)
            if (header.all { it == 0.toByte() }) break // 结尾双零块
            var name = string(header, 0, 100)
            val size = octal(header, 124, 12)
            val mtime = octal(header, 136, 12)
            val typeFlag = header[156].toInt().toChar()
            val linkName = string(header, 157, 100)
            val pfx = string(header, 345, 155)
            if (pfx.isNotEmpty()) name = "$pfx/$name"

            val out = File(targetDir, name.removePrefix("./"))
            when (typeFlag) {
                '5' -> out.mkdirs()
                '2' -> {
                    out.parentFile?.mkdirs()
                    runCatching {
                        val target = if (linkName.startsWith("/")) {
                            // 绝对符号链接：重写进本 rootfs，避免解析到设备真实根
                            File(targetDir, linkName).toPath()
                        } else {
                            Paths.get(linkName)
                        }
                        Files.deleteIfExists(out.toPath())
                        Files.createSymbolicLink(out.toPath(), target)
                    }
                }
                '1' -> Unit // 硬链接：minirootfs 罕见，跳过（缺失文件 APK 补装可救）
                '0', 0.toChar() -> {
                    out.parentFile?.mkdirs()
                    java.io.FileOutputStream(out).use { fos ->
                        var left = size
                        val buf = ByteArray(32 * 1024)
                        while (left > 0) {
                            val n = minOf(buf.size.toLong(), left).toInt()
                            readFullyInto(gunzip, buf, n)
                            fos.write(buf, 0, n)
                            left -= n
                        }
                    }
                    // 可执行位：tar 的 mode 只保留 x 位（0o111=73），目录默认 rwx
                    val modeBits = octal(header, 100, 8)
                    if (modeBits and 73L != 0L) out.setExecutable(true, false)
                    if (mtime > 0) out.setLastModified(mtime * 1000L)
                }
                else -> Unit // 设备文件/pax 头等：跳过
            }
            // padding
            val pad = ((512 - size % 512) % 512).toInt()
            if (pad > 0) {
                val skip = ByteArray(pad)
                readFully(skip)
            }
            processed++
            if (processed % 64 == 0) {
                onProgress(((processed * 100) / weighEntries).coerceAtMost(99), name)
            }
        }
        onProgress(99, "")
    }

    private fun readFullyInto(src: java.io.InputStream, buf: ByteArray, want: Int) {
        var n = 0
        while (n < want) {
            val r = src.read(buf, n, want - n)
            if (r < 0) throw IOException("tar 流意外结束")
            n += r
        }
    }

    private fun string(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8).trim()
    }

    private fun octal(b: ByteArray, off: Int, len: Int): Long {
        val s = string(b, off, len)
        if (s.isEmpty()) return 0
        return s.toLongOrNull(radix = 8) ?: 0L
    }
}
