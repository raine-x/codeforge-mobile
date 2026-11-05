package com.hxy.codeforge.core

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths

/**
 * 统一兼容环境（proot + Alpine rootfs）的构建与生命周期。
 *
 * ## 环境契约 —— 「跑 A 的地方必须看得到跑 B 的地方」
 *
 * 一切路径锚定应用沙盒单根（filesDir，下称 $CELL）：
 *
 * ```
 * $CELL/                     HOME（内核、工具、rootfs 全在这棵树上）
 * ├── workspace/             工作区（内核 -workdir；proot 内同路径可见）
 * ├── tmp/                   TMPDIR（proot 内绑定为 /tmp）
 * ├── mnt/android/           安卓侧存储的稳定入口（见下方映射表）
 * ├── usr/bin/proot          Termux 构建（bionic，可直接 exec）
 * ├── usr/libexec/proot/     proot 的 loader（与 bin 的相对布局保持原样）
 * ├── usr/bin/cfrun          统一执行入口（见 [cfrunScript]）
 * └── rootfs/                Alpine minirootfs（musl + apk 生态）
 * ```
 *
 * 对齐机制：proot 以 `-b $CELL:$CELL -b $CELL/tmp:/tmp` 把宿主目录按**同一路径**
 * 绑进 rootfs —— 内核在 $CELL/workspace 改文件，rootfs 里的 python/go 在
 * **同一路径**读写同一文件；反之亦然。不存在「A 跑在这、B 跑在那、互不相见」。
 *
 * ## 安卓侧存储映射（isolated 沙盒之外的世界）
 *
 * 候选按优先级排序，逐个 `[ -e ] && [ -r ]` 探测，不可用一律跳过 —— 不申请权限、
 * 不报错、不影响沙盒内命令（P-014：硬绑不存在的路径会告警并拖垮 glue rootfs）：
 *
 * ```
 * 宿主                              guest 可见位置
 * ────────────────────────────────────────────────────────────────────────
 * /storage/emulated/0          →    /storage/emulated/0   +  $CELL/mnt/android
 * /sdcard                      →    /sdcard
 * getExternalFilesDir(null)    →    同一绝对路径          +  $CELL/mnt/android
 * 全不可用                     →    都不绑；$CELL/mnt/android 是个空目录
 * ```
 *
 * 两条规则：① 候选**按自身绝对路径**绑 —— guest 里写死的 `/sdcard/foo` 与宿主
 * 内核看到的是同一份数据；② 首个可用者**叠绑**到 `$CELL/mnt/android` —— 这是
 * 给内核与脚本的稳定入口，换设备、换权限状态时路径不变，取不到时它只是空目录。
 * 首个可用者的判定顺序与 [androidStorage] 必须一致，否则 `CF_ANDROID_STORAGE`
 * 会和 guest 里的 `$CELL/mnt/android` 指向两处。
 *
 * 首启流水线（AGENTS §2.6）：语音模型解压之后跑本类，两步合计约 4.2 MB 资产
 * 解包，进度并入「环境准备中」遮罩。
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val status = kotlinx.coroutines.flow.MutableStateFlow<Status>(Status.Idle)

    // ---------------------------------------------------------------- 路径契约

    fun cellRoot(context: Context): File = context.filesDir
    fun usrBin(context: Context): File = File(cellRoot(context), "usr/bin")
    fun rootfsDir(context: Context): File = File(cellRoot(context), "rootfs")
    fun tmpDir(context: Context): File = File(cellRoot(context), "tmp")
    fun cfrun(context: Context): File = File(usrBin(context), "cfrun")

    /**
     * rootfs 内（= 宿主同一路径，因为 $CELL 整体绑进 guest）的安卓存储稳定入口：
     * 有可用存储时 cfrun 把首个可用候选叠绑到这里，没有就只是个空目录（见类注释映射表）。
     */
    fun androidMnt(context: Context): File = File(cellRoot(context), "mnt/android")

    // ---------------------------------------------------------------- 独立空间

    /** 测试模型的专属空间（不与内核工作区混住）。 */

    /**
     * 会话独立空间：未显式选工作区的会话各拿一个小目录（附件与文件互不串台）。
     * 目录名做保守清洗 —— sessionId 来自内核，拿来拼路径前必须过一遍白名单。
     */
    fun sessionSpace(context: Context, sessionId: String): File {
        val safe = sessionId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48).ifEmpty { "default" }
        return ensureWr(File(File(cellRoot(context), "spaces"), safe))
    }

    /**
     * 未选择项目时的默认工作区（应用私有空间）。
     *
     * 为什么不再「每个会话一个空格子」：会话在**创建那一刻**就把当时的工作区
     * 写进内核的 sessions.workspace，之后壳侧再切 /api/workspace 也改不回这条
     * 记录。按会话 id 建目录必须先有 id，而会话正是要工作区才能建 —— 死循环。
     * 统一用一个稳定目录，会话归属、附件落点、后续加载时的工作区切换三者同源。
     */
    fun inboxSpace(context: Context): File =
        ensureWr(File(File(cellRoot(context), "spaces"), "inbox"))

    /**
     * 项目工作区的父目录（`$CELL/workspace`）。
     *
     * 也是内核历史上默认的工作区根；「不选文件夹的新项目」在它下面建独立子目录。
     */
    fun workspaceRoot(context: Context): File = ensureWr(File(cellRoot(context), "workspace"))

    /**
     * 生成一个项目 id 作为文件夹名。
     *
     * 为什么不用会话 id：工作区在**会话创建之前**就要定好（`History.Create`
     * 会把当时的 root 写进 `sessions.workspace`，事后改不回来），而会话 id
     * 正是创建后才有的；另外项目会被多个会话共享，拿第一条会话的 id 当目录名
     * 语义也不对。用项目自己的短 id，稳定、无碰撞、不随重命名变化。
     */
    fun newProjectId(): String =
        "p-" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)

    /** 不选文件夹时的项目工作区：`$CELL/workspace/<项目id>`。 */
    fun newProjectWorkspace(context: Context, projectId: String): File =
        ensureWr(File(workspaceRoot(context), projectId))

    /**
     * 附件本地暂存区（应用私有，与内核工作区无关）。
     *
     * 选择器的 content:// URI 随时可能失效，必须先落盘；但落点不能猜工作区 ——
     * 会话还没创建时工作区尚不存在。统一落到这里，发送时由 `/api/upload_file`
     * 原子写进内核当前工作区（附件路径与读方从此一致，见问题 1）。
     */
    fun outboxDir(context: Context): File =
        File(cellRoot(context), "outbox").apply { mkdirs() }

    // ---------------------------------------------------------------- 文件传输（sdcard）

    /**
     * 手机侧公共下载目录：`/storage/emulated/0/Download` → `/sdcard/Download` →
     * 应用外私目录 `Download`，取首个存在（或能建出来）且可写的。
     *
     * targetSdk 28 在 Android 10+ 仍按 legacy 存储处理，本进程与内核子进程
     * （同一 uid）都能直接写公共目录，不需要 SAF 授权 —— 这是本项目既能
     * 「产物跑在容器里」又能「文件落到用户可见处」的关键前提。
     */
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

    /** 把单个文件复制到 `Download/CodeForge/`；重名追加序号。返回落地文件。 */
    fun exportFileToDownloads(context: Context, src: File): File? {
        if (!src.isFile) return null
        val dir = File(publicDownloadsDir(context) ?: return null, "CodeForge").apply { mkdirs() }
        var target = File(dir, src.name)
        var n = 2
        while (target.exists()) {
            target = File(dir, "${src.nameWithoutExtension}-$n.${src.extension}")
            n++
        }
        return runCatching {
            src.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target
        }.getOrNull()
    }

    /**
     * 把整个目录打包成 zip 复制到 `Download/CodeForge/`。
     *
     * 空目录不打包（返回 null）：用户点「导出」却拿到一个空文件只会更困惑。
     */
    fun exportDirectoryAsZip(context: Context, dir: File, zipName: String): File? {
        if (!dir.isDirectory || dir.walkTopDown().none { it.isFile }) return null
        val outDir = File(publicDownloadsDir(context) ?: return null, "CodeForge").apply { mkdirs() }
        val target = File(outDir, zipName)
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

    /**
     * 从系统选择器（SAF / 相册）导入一个文件到默认工作区的 `imports/`。
     *
     * 落点用 [inboxSpace] 而不是临时目录：导入是用户显式动作，重启后还要能在
     * 工作区文件树里看到它。返回落地文件（绝对路径，可直接当工作区相对路径用）。
     */
    fun importFileFromUri(context: Context, uri: Uri, subdir: String = "imports"): File? {
        val resolver = context.contentResolver
        var name = resolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        } ?: "imported"
        name = name.replace(Regex("[/\\\\\u0000:]"), "_").take(80).ifEmpty { "imported" }
        val dir = File(inboxSpace(context), subdir).apply { mkdirs() }
        var target = File(dir, name)
        var n = 2
        while (target.exists()) {
            target = File(dir, "${target.nameWithoutExtension}-$n.${target.extension}")
            n++
        }
        return runCatching {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            target
        }.getOrNull()
    }

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

    /**
     * 安卓侧可共享的存储根：主外部存储 → /sdcard → 应用外部私有目录，取首个存在且可读的；
     * 都拿不到返回 null。**不申请权限、不弹 UI**：没权限时 /storage/emulated/0 的
     * canRead() 为假，自然落到免权限的外私目录，最差纯沙盒 —— 静默降级。
     *
     * 判定顺序必须与 [cfrunScript] 生成的候选列表逐项一致：内核拿到的
     * `CF_ANDROID_STORAGE` 就是 guest 里 `$CELL/mnt/android` 的源。
     */
    private fun androidStorage(context: Context): File? =
        listOf(File("/storage/emulated/0"), File("/sdcard"), externalFilesDir(context))
            .filterNotNull()
            .firstOrNull { it.exists() && it.canRead() }

    /**
     * 内核进程的完整环境（CoreService 启动内核时注入；内核 spawn 的命令继承之，
     * 与 cfrun/rootfs 看到的世界完全一致）。
     */
    fun kernelEnv(context: Context): Map<String, String> = buildMap {
        put("HOME", cellRoot(context).absolutePath)
        put("TMPDIR", tmpDir(context).absolutePath)
        put("PATH", usrBin(context).absolutePath + ":/system/bin")
        // 兼容钩子：内核 run_command 读到它就把命令包一层 cfrun（见内核 terminal.go）
        put("CF_EXEC_WRAPPER", "/system/bin/sh " + cfrun(context).absolutePath)
        // 防误判 Termux（IsTermux 唯一判据是 PREFIX）
        put("PROOT_TMP_DIR", tmpDir(context).absolutePath)
        // 安卓侧存储的实际可用路径（与 guest 里 $CELL/mnt/android 同源）；空串 = 本次没有
        // 可用存储，调用方据此降级（不申请权限、不重试）。供内核与诊断日志使用。
        put("CF_ANDROID_STORAGE", androidStorage(context)?.absolutePath.orEmpty())
    }

    fun refresh(context: Context) {
        if (status.value is Status.Preparing) return
        status.value = if (isReady(context)) Status.Ready else Status.Idle
    }

    // ---------------------------------------------------------------- 软件包

    /**
     * 环境软件包预设：apk 一条命令装进 rootfs（= 轻量工具链管理器 v1）。
     *
     * [probe] 是**存在性探测**：结论只看它的退出码，所以必须是「目标命令自己」的
     * 退出码。**不能带管道** —— 管道的退出码取自最后一个命令（`head`），恒为 0，
     * 目标命令不存在时也会被判成「已装」（真机实测：rootfs 里一个语言都没有，
     * 设置页却把 C/C++、Java 17、FFmpeg 三项标成了已装）。
     */
    data class EnvPackage(val label: String, val installCmd: String, val probe: String)

    val packages = listOf(
        EnvPackage("Python", "apk add --no-cache python3 py3-pip", "python3 --version"),
        EnvPackage("Node.js", "apk add --no-cache nodejs npm", "node --version"),
        EnvPackage("Go", "apk add --no-cache go", "go version"),
        EnvPackage("C/C++", "apk add --no-cache build-base", "gcc --version"),
        EnvPackage("Rust", "apk add --no-cache cargo rust", "rustc --version"),
        EnvPackage("Git", "apk add --no-cache git", "git --version"),
        EnvPackage("Java 17", "apk add --no-cache openjdk17-jre-headless", "java -version"),
        // ffmpeg 走 rootfs 而非 so 库（ffmpeg-kit 已停更）：apk 官方构建，
        // 装完内核可直接 `ffmpeg -i ...` 处理工作区媒体
        EnvPackage("FFmpeg", "apk add --no-cache ffmpeg", "ffmpeg -version"),
    )

    /**
     * 在 rootfs 环境里执行命令（阻塞，须在 IO 线程调）。
     * 输出逐行回调（apk 进度/版本探测都走这里），返回退出码。
     * 网络命令前自动补 DNS —— Android 没有 /etc/resolv.conf 可复制，
     * musl 的解析器又只认它，写成公共 DNS（国内优先 223.5.5.5）。
     */
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

    /** 探测包是否已装（probe 命令退出码 0 = 已装）。 */
    fun isPackageInstalled(context: Context, pkg: EnvPackage): Boolean =
        runInRootfs(context, pkg.probe + " >/dev/null 2>&1") { } == 0

    /**
     * 网络前提每次强制重写，不走「已有就不动」：
     * - resolv.conf：Android 没有 /etc/resolv.conf 可复制，musl 的解析器只认它；
     * - repositories：官方 dl-cdn 国内常超时，替换为中科大镜像（保留原版本号）。
     *
     * ⚠️ Alpine minirootfs 自带的 `repositories` 是**空文件**，不是不存在。
     * 旧写法 `mirrored ?: 默认源` 只在 null 时兜底，空字符串会原样写回 ——
     * 于是仓库源永远是空的，`apk add` 报 `unable to select packages: python3
     * (no such package)`（真机实测，环境软件包全部装不上）。
     * 现在用 `ifBlank` 兜底，空文件也能补上。
     */
    private fun ensureNet(context: Context) {
        runCatching {
            val etc = File(rootfsDir(context), "etc")
            File(etc, "resolv.conf").writeText("nameserver 223.5.5.5\nnameserver 8.8.8.8\n")

            val repo = File(etc, "apk/repositories")
            repo.parentFile?.mkdirs()

            // 版本号从 rootfs 自己读，避免镜像升级后硬编码的 v3.24 对不上。
            // ⚠️ alpine-release 的内容是 `3.24.3`（**不带 v**），而镜像目录名是
            // `v3.24` —— 少了这个 v，所有镜像的 APKINDEX 全部 404（真机实测）。
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
            // 只有「含 /alpine/v 的合法镜像行」才沿用；否则（空文件、或历史版本
            // 写坏的 `alpine/3.24` 这种缺 v 的路径）一律按默认多源重建。
            val content = if (existing.contains("/alpine/v")) {
                existing.replace("dl-cdn.alpinelinux.org", "mirrors.ustc.edu.cn")
            } else {
                // 多镜像并列：apk 会依次尝试，任一可用即可。
                // 真机实测中科大在部分移动网络下对 APKINDEX 返回 404
                // （同一 URL 从 PC 访问是 200），单源会让安装整体失败。
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

    /**
     * 执行前兜底同步 cfrun（幂等）：启动时 ensureReady 的重写若被错过或失败，
     * 这里保证真正跑的一定是当前版本脚本 —— 生成脚本永不随 APK 自动更新的
     * 漏洞由此彻底封死（P-014）。
     */
    private fun syncCfrun(context: Context) {
        runCatching {
            val f = cfrun(context)
            val script = cfrunScript(cellRoot(context), externalFilesDir(context))
            if (f.readText() != script) {
                f.writeText(script)
                f.setExecutable(true, false)
                log(context, "syncCfrun: 检测到旧版脚本，已重写")
            }
        }
    }

    /** 幂等入口：Application 启动时调；失败可从设置页重试。 */
    fun ensureReady(context: Context) {
        val app = context.applicationContext
        when {
            isReady(app) -> {
                status.value = Status.Ready
                // 已就绪也要重写 cfrun：脚本随 app 版本演进（P-014 修复就靠这层下发），
                // 不重跑整套解压也能让旧安装拿到新脚本
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

    /**
     * 重置（IO 线程执行删除）。⚠️ 绝不能在主线程 deleteRecursively ——
     * rootfs 有 3800+ 文件，主线程删必 ANR 被系统杀（真机实测「点击重新准备
     * 直接崩溃」即此因，且 ANR 不走异常处理器、崩溃日志不会有记录）。
     */
    fun reset(context: Context) {
        val app = context.applicationContext
        status.value = Status.Idle
        scope.launch {
            log(app, "reset: 清理 rootfs…")
            rootfsDir(app).deleteRecursively()
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
     * cfrun —— 内外统一的命令执行入口（内核 CF_EXEC_WRAPPER 指向它）。
     *
     * rootfs 就绪：一律经 proot 进 Alpine 执行（PATH=/usr/bin → python/go/gcc
     * 由 apk 安装在 rootfs 里，与宿主同一棵 $CELL 树）；rootfs 缺席：退化为
     * 直执行（静态/bionic 二进制仍可用）。`--` 后是原始命令字符串。
     *
     * [externalFiles] 应用外部私有目录（写进候选列表兜底）；null 表示本次拿不到。
     */
    private fun cfrunScript(cell: File, externalFiles: File?): String = """
        #!/system/bin/sh
        # 由 EnvKit 生成 —— 路径是 bake 死的绝对值，勿手工迁移目录。
        # proot 离开 Termux 原生路径后必须显式给齐环境（坑记 AGENTS P-014）：
        #   PROOT_TMP_DIR  glue rootfs 的临时目录 —— Android 没有可写的 /tmp，
        #                   缺了必报 can't create glue rootfs，guest exec 连锁失败
        #   PROOT_LOADER   Termux 构建把 loader 路径 bake 死在 com.termux 数据目录，
        #                   换目录后必须重指，否则 guest 动态二进制起不来
        #   LD_LIBRARY_PATH proot 自身的 libtalloc / libandroid-shmem
        CELL=${cell.absolutePath}
        ROOT=${'$'}CELL/rootfs
        USR=${'$'}CELL/usr
        CMD="${'$'}*"
        export PROOT_TMP_DIR="${'$'}CELL/tmp"
        export PROOT_LOADER="${'$'}USR/libexec/proot/loader"
        export LD_LIBRARY_PATH="${'$'}USR/lib${'$'}{LD_LIBRARY_PATH:+:${'$'}LD_LIBRARY_PATH}"
        # 第二个目录是安卓存储稳定入口的挂载点（宿主与 guest 同一路径）：有可用候选才
        # 往上叠绑，没有就剩个空目录 —— 空目录不影响任何沙盒内命令。与 tmp 合并成一次
        # mkdir，免得每条命令多 spawn 一个进程。
        mkdir -p "${'$'}PROOT_TMP_DIR" "${'$'}CELL/mnt/android"
        # -0 = fake root：guest 里 geteuid() 返回 0。
        # 缺了它，Alpine 的 apk 会以普通 uid 运行并拒绝写 /lib/apk/db
        # （真机实测：35 个包全部下载安装完成，最后一步
        #  "failed to write database: Permission denied" 整体失败）。
        if [ -x "${'$'}ROOT/bin/busybox" ]; then
          BINDS=""
          # 条件绑定：宿主上不存在的路径硬绑会产生 warning 并拖垮 glue（部分 ROM 无 /system/lib64）
          for D in /system/bin /system/lib64 /system/lib /system/etc; do
            [ -e "${'$'}D" ] && BINDS="${'$'}BINDS -b ${'$'}D:${'$'}D"
          done
          # 安卓侧存储：候选顺序与 EnvKit.androidStorage 一致（改顺序必须两边一起改）：
          #   /storage/emulated/0  主外部存储；没有存储权限时 -r 为假 → 静默跳过
          #   /sdcard              多数 ROM 是上者的符号链接，绑它只为让写死 /sdcard/xx
          #                        的老脚本也能用（重复绑定无害）
          #   应用外部私有目录     免权限可读写，最后兜底
          # 判据 -e && -r：不可读的目录绑进去 guest 也进不去（权限在宿主侧判定）。
          # 候选路径不含空格，故与上面系统目录一样按空格拼进 BINDS 即可。
          STORAGE=""
          for D in ${storageCandidates(externalFiles)}; do
            if [ -e "${'$'}D" ] && [ -r "${'$'}D" ]; then
              BINDS="${'$'}BINDS -b ${'$'}D:${'$'}D"
              [ -n "${'$'}STORAGE" ] || STORAGE="${'$'}D"
            fi
          done
          # 首个可用者叠绑到稳定入口：CELL 已整体绑进 guest，这里在其子目录上再叠一层
          # （proot 后绑覆盖先绑），于是 guest 里 CELL/mnt/android 就是安卓存储根，
          # 换设备、换权限状态时这个路径都不变。取不到就保持空目录。
          if [ -n "${'$'}STORAGE" ]; then
            BINDS="${'$'}BINDS -b ${'$'}STORAGE:${'$'}CELL/mnt/android"
          fi
          # 环境在宿主侧 export，proot 原样带给 guest —— 不经 /usr/bin/env 中转，少一个 exec 失败点
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
          exec /system/bin/sh -c "${'$'}CMD"
        fi
    """.trimIndent() + "\n"

    /**
     * cfrun 的安卓存储候选列表（顺序 = 优先级，与 [androidStorage] 逐项对应）。
     * 每项加引号，防路径含空格被按词拆开；null 候选直接不列。
     */
    private fun storageCandidates(externalFiles: File?): String = listOfNotNull(
        "/storage/emulated/0",
        "/sdcard",
        externalFiles?.absolutePath,
    ).joinToString(" ") { "\"$it\"" }

    // ---------------------------------------------------------------- tar.gz

    /**
     * 极简 tar 解包（std 库没有 tar，也不想为此引 commons-compress）。
     *
     * 进度按「已处理条目 / [weighEntries] 估算」—— tar 流单遍拿不到总条目数，
     * 估算值只用于显示，钳到 99% 防止假完成。符号链接两类处理：
     * 相对目标原样建；**绝对目标重写为 rootfs 内路径**（否则在宿主上悬空，
     * proot 里也会解析到真实根导致串台）。
     */
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
