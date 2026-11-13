package com.hxy.codeforge.core

import android.content.Context
import android.net.Uri
import com.hxy.codeforge.model.InstallVersionRelation
import com.hxy.codeforge.model.InstalledPlugin
import com.hxy.codeforge.model.PendingPluginInstall
import com.hxy.codeforge.model.PluginManifest
import com.hxy.codeforge.model.PluginPermissions
import com.hxy.codeforge.model.PluginSkillRef
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * 动态插件安装器（`.cfplugin` 包解析、安全校验、版本比对、原子落盘与卸载）。
 *
 * ## 目录结构（锚定 `filesDir/plugins/`）
 *
 * - `filesDir/plugins/incoming/<uuid>.cfplugin`：SAF 选入后的临时暂存包（预览取消或安装完成后即删）；
 * - `filesDir/plugins/<id>/install.json`：已装插件元信息（manifest + enabled + configuredSecrets + sha256）；
 * - `filesDir/plugins/<id>/<version>/`：解压后的母本（含 `skills/<slug>/`、`payload/`、`README.md`）。
 *
 * ## 安全与不变量
 *
 * 1. **防 zip 炸弹与路径穿越**：包体积 ≤ 8 MiB、解压总字节 ≤ 32 MiB、条目数 ≤ 512；
 *    逐条目拒绝 `..`、反斜杠与绝对路径。
 * 2. **防 P-052 坏技能静默失效**：包内每个 `skills/<slug>/SKILL.md` 在安装期强制检查扁平单行
 *    frontmatter，`name` 必须等于 `<slug>`，`description` 禁止 `|` / `>` 多行块标量或缩进续行。
 * 3. **原子落盘与失败回滚**：先解压到临时目录，校验全部通过后再写入正式目录；
 *    若后续向内核注册 MCP 失败，调用方回滚恢复旧版本或清理目录，绝不留半装状态。
 */
object PluginInstaller {

    /** 单个 `.cfplugin` 压缩包上限：8 MiB。 */
    const val MAX_ARCHIVE_BYTES: Long = 8L * 1024L * 1024L

    /** 解压后总字节数上限：32 MiB。 */
    const val MAX_UNPACKED_BYTES: Long = 32L * 1024L * 1024L

    /** zip 内最大条目数上限：512。 */
    const val MAX_ENTRIES: Int = 512

    private const val PLUGINS_DIR = "plugins"
    private const val INCOMING_DIR = "incoming"
    private const val INSTALL_META_FILE = "install.json"
    private const val MANIFEST_FILE = "plugin.json"
    private const val README_FILE = "README.md"
    private const val SKILLS_PREFIX = "skills/"

    private val ID_REGEX = Regex("^[a-z][a-z0-9-]{1,31}$")
    private val SEMVER_REGEX = Regex("^\\d+\\.\\d+\\.\\d+$")
    private val SKILL_SLUG_REGEX = Regex("^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$")
    private val HTTP_URL_REGEX = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)
    private val ALLOWED_TYPES = setOf("skill", "mcp", "mcp-http", "wasm", "http")
    private val BLOCK_SCALAR_MARKERS = setOf("|", ">", "|-", ">-", "|+", ">+")

    sealed class InstallResult<out T> {
        data class Ok<T>(val value: T) : InstallResult<T>()
        data class Err(val message: String) : InstallResult<Nothing>()
    }

    /**
     * 把 SAF 选中的 `content://` URI 复制到 `filesDir/plugins/incoming/<uuid>.cfplugin`。
     *
     * 必须在 IO 线程调用；复制过程中流式检查 [MAX_ARCHIVE_BYTES] 上限，超限立即中断并删残片。
     */
    fun stageUriToIncoming(context: Context, uri: Uri, displayName: String): InstallResult<File> {
        val incomingDir = File(File(context.filesDir, PLUGINS_DIR), INCOMING_DIR)
        if (!incomingDir.isDirectory && !incomingDir.mkdirs()) {
            return InstallResult.Err("无法创建插件暂存目录")
        }
        val dest = File(incomingDir, "${UUID.randomUUID()}.cfplugin")
        return runCatching {
            val input = context.contentResolver.openInputStream(uri)
                ?: return InstallResult.Err("无法读取所选插件包文件")
            input.use { raw ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val n = raw.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_ARCHIVE_BYTES) {
                            out.close()
                            dest.delete()
                            return InstallResult.Err("插件包超过 8 MiB 上限")
                        }
                        out.write(buf, 0, n)
                    }
                    if (total == 0L) {
                        out.close()
                        dest.delete()
                        return InstallResult.Err("插件包文件为空：$displayName")
                    }
                }
            }
            InstallResult.Ok(dest)
        }.getOrElse { e ->
            dest.delete()
            InstallResult.Err("读取插件包失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * 校验 zip 条目路径安全性（纯函数）。
     *
     * 拒绝空路径、绝对路径（`/` 或盘符开头）、反斜杠路径以及任何含 `..` 的路径穿越。
     */
    internal fun validateZipEntryName(rawName: String): InstallResult<String> {
        if (rawName.isBlank()) {
            return InstallResult.Err("压缩包存在空条目名")
        }
        if (rawName.startsWith("/") || rawName.startsWith("\\") || rawName.contains("\\")) {
            return InstallResult.Err("拒绝非法或绝对路径条目：$rawName")
        }
        val segments = rawName.split('/')
        if (segments.any { it == ".." || it == "." } || rawName.contains("..")) {
            return InstallResult.Err("拒绝路径穿越条目：$rawName")
        }
        if (File(rawName).isAbsolute) {
            return InstallResult.Err("拒绝绝对路径条目：$rawName")
        }
        return InstallResult.Ok(rawName)
    }

    /**
     * 解析并校验 `plugin.json` 清单（纯函数，可直接单测）。
     */
    internal fun parseManifest(jsonText: String): InstallResult<PluginManifest> {
        val obj = runCatching { JSONObject(jsonText) }.getOrElse {
            return InstallResult.Err("plugin.json 不是合法的 JSON 对象")
        }
        val schema = obj.optInt("schema", -1)
        if (schema != 1) {
            return InstallResult.Err("不支持的 plugin.json schema：$schema（仅支持 schema: 1）")
        }
        val id = obj.optString("id").trim()
        if (!ID_REGEX.matches(id)) {
            return InstallResult.Err("插件 id 不合法（需匹配 ^[a-z][a-z0-9-]{1,31}$）：$id")
        }
        val name = obj.optString("name").trim()
        if (name.isEmpty()) {
            return InstallResult.Err("plugin.json 缺少非空的 name 字段")
        }
        val version = obj.optString("version").trim()
        if (!SEMVER_REGEX.matches(version)) {
            return InstallResult.Err("插件 version 必须为 x.y.z 语义化版本号：$version")
        }
        val type = obj.optString("type").trim()
        if (type !in ALLOWED_TYPES) {
            return InstallResult.Err("不支持的插件 type：$type（允许：skill / mcp / mcp-http / wasm / http）")
        }

        val endpoint = obj.optString("endpoint").trim()
        val command = obj.optString("command").trim()
        val args = jsonStringList(obj.optJSONArray("args"))
        val skills = jsonStringList(obj.optJSONArray("skills"))

        for (slug in skills) {
            if (!SKILL_SLUG_REGEX.matches(slug)) {
                return InstallResult.Err("技能名称不合法：$slug")
            }
        }
        if (skills.distinct().size != skills.size) {
            return InstallResult.Err("skills[] 存在重复条目")
        }

        // 按类型校验必填项（§2.0 / §2.2）
        when (type) {
            "skill" -> {
                if (skills.isEmpty()) {
                    return InstallResult.Err("纯文本技能包（type: skill）必须声明非空的 skills[]")
                }
            }
            "mcp" -> {
                if (command.isBlank()) {
                    return InstallResult.Err("mcp 插件必须提供非空的 command 启动命令")
                }
            }
            "mcp-http", "http" -> {
                if (!HTTP_URL_REGEX.matches(endpoint)) {
                    return InstallResult.Err("$type 插件必须提供合法的 http(s):// endpoint")
                }
            }
            "wasm" -> {
                // 阶段 1 解析识别，安装入口置灰
            }
        }

        val envObj = obj.optJSONObject("env")
        val envMap = LinkedHashMap<String, String>()
        if (envObj != null) {
            val keys = envObj.keys()
            while (keys.hasNext()) {
                val k = keys.next().trim()
                if (k.isNotEmpty()) {
                    envMap[k] = envObj.optString(k, "")
                }
            }
        }

        val permObj = obj.optJSONObject("permissions")
        val permissions = if (permObj == null) {
            PluginPermissions()
        } else {
            PluginPermissions(
                network = jsonStringList(permObj.optJSONArray("network")),
                filesystem = permObj.optString("filesystem", "none").trim().ifEmpty { "none" },
                secrets = jsonStringList(permObj.optJSONArray("secrets")),
                requiresApproval = permObj.optBoolean("requires_approval", false),
                allowedActions = jsonStringList(permObj.optJSONArray("allowed_actions")),
            )
        }

        return InstallResult.Ok(
            PluginManifest(
                schema = schema,
                id = id,
                name = name,
                version = version,
                description = obj.optString("description").trim(),
                author = obj.optString("author").trim(),
                homepage = obj.optString("homepage").trim(),
                type = type,
                endpoint = endpoint,
                command = command,
                args = args,
                env = envMap,
                permissions = permissions,
                skills = skills,
            ),
        )
    }

    /**
     * 校验单个 `skills/<slug>/SKILL.md` 的 frontmatter 是否符合内核解析规则（P-052 硬性）。
     *
     * 内核 `pkg/agent/skills.go` 的 `parseSkill` 逐行按第一个冒号切分，不支持 YAML 块标量（`|` / `>`）
     * 与多行缩进续行。一旦放行，技能描述在界面会退化成一根竖线 `"|"`。
     */
    internal fun validateSkillMarkdown(slug: String, rawText: String): InstallResult<PluginSkillRef> {
        val trimmed = rawText.removePrefix("\uFEFF").trimStart()
        if (!trimmed.startsWith("---")) {
            return InstallResult.Err("技能 $slug 的 SKILL.md 必须以 --- frontmatter 开头")
        }
        val endIdx = trimmed.indexOf("\n---", startIndex = 3)
        if (endIdx <= 3) {
            return InstallResult.Err("技能 $slug 的 SKILL.md frontmatter 未闭合（缺少 ---）")
        }
        val front = trimmed.substring(3, endIdx)
        val body = trimmed.substring(endIdx + 4).trim()
        if (body.isEmpty()) {
            return InstallResult.Err("技能 $slug 的 SKILL.md 正文不能为空")
        }
        if (body.startsWith("--")) {
            return InstallResult.Err("技能 $slug 的 SKILL.md 正文开头存在未闭合的 --- 残留")
        }

        val fields = LinkedHashMap<String, String>()
        for (rawLine in front.lines()) {
            val cleanLine = rawLine.trimEnd('\r')
            if (cleanLine.isBlank() || cleanLine.trimStart().startsWith("#")) continue
            // 缩进续行直接拒绝：内核逐行 Cut(":") 不拼多行 YAML
            if (cleanLine.first().isWhitespace()) {
                return InstallResult.Err("技能 $slug 的 SKILL.md 含多行缩进 frontmatter（内核仅支持扁平单行，见 P-052）")
            }
            val colon = cleanLine.indexOf(':')
            if (colon <= 0) {
                return InstallResult.Err("技能 $slug 的 SKILL.md frontmatter 存在无效行：$cleanLine")
            }
            val key = cleanLine.substring(0, colon).trim()
            val value = cleanLine.substring(colon + 1).trim()
            if (value in BLOCK_SCALAR_MARKERS || value.startsWith("|") || value.startsWith(">")) {
                return InstallResult.Err(
                    "技能 $slug 的 frontmatter 字段 $key 使用了 YAML 块标量（$value），请改为扁平单行（见 P-052）",
                )
            }
            fields[key] = value
        }

        val declaredName = fields["name"].orEmpty()
        if (declaredName != slug) {
            return InstallResult.Err("技能 $slug 的 frontmatter name（$declaredName）必须与目录名 $slug 一致")
        }
        val description = fields["description"].orEmpty()
        if (description.isBlank()) {
            return InstallResult.Err("技能 $slug 的 SKILL.md 缺少非空的单行 description")
        }
        val displayName = fields["display_name"].orEmpty().ifBlank { slug }
        val triggers = fields["triggers"].orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        return InstallResult.Ok(
            PluginSkillRef(
                slug = slug,
                displayName = displayName,
                description = description,
                triggers = triggers,
            ),
        )
    }

    /**
     * 比较两个语义化版本号 `x.y.z`。
     *
     * @return 正数表示 `a > b`，0 表示相等，负数表示 `a < b`。
     */
    internal fun compareSemver(a: String, b: String): Int {
        val pa = a.trim().split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.trim().split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0..2) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }

    /**
     * 判定待安装包版本与已装版本的关系。
     */
    internal fun classifyVersion(installedVersion: String?, incomingVersion: String): InstallVersionRelation {
        if (installedVersion.isNullOrBlank()) return InstallVersionRelation.FRESH
        val cmp = compareSemver(incomingVersion, installedVersion)
        return when {
            cmp > 0 -> InstallVersionRelation.UPGRADE
            cmp == 0 -> InstallVersionRelation.REINSTALL
            else -> InstallVersionRelation.DOWNGRADE
        }
    }

    /**
     * 冲突检查（纯函数）：
     * 1. 可执行插件（`type != "skill"`）：`id` 不能与内置工具名或用户手动添加的 MCP 插件撞名；
     * 2. 随包技能（`manifest.skills`）：不能与内置技能 slug 或其他已安装插件的技能 slug 撞名。
     *
     * @param manifest 待安装清单
     * @param installedPlugins 当前已通过安装器安装的插件列表
     * @param kernelTools 内核 `/api/config` 返回的当前已注册工具名列表
     * @param builtinSkillSlugs 内置技能（`builtin-skills.zip`）的 slug 集合
     * @param manualMcpIds 用户在面板手动添加的 MCP 服务 id 集合（不含安装器管理的插件 id）
     */
    internal fun checkConflicts(
        manifest: PluginManifest,
        installedPlugins: List<InstalledPlugin>,
        kernelTools: List<String>,
        builtinSkillSlugs: Set<String>,
        manualMcpIds: Set<String> = emptySet(),
    ): InstallResult<Unit> {
        if (!manifest.isPureSkill) {
            // 内置工具名是那些不带 `.` 前缀的工具（插件工具形如 `<pluginId>.<toolName>`）
            val builtinToolNames = kernelTools
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.contains('.') }
                .toSet()
            if (manifest.id in builtinToolNames) {
                return InstallResult.Err("插件 id「${manifest.id}」与内置工具同名冲突")
            }
            if (manifest.id in manualMcpIds) {
                return InstallResult.Err("插件 id「${manifest.id}」与手动添加的 MCP 服务同名冲突，请先删除同名手动条目")
            }
        }

        // 技能 slug 冲突检查（内置技能 + 其他已安装插件的技能）
        val otherPluginSkills = HashMap<String, String>()
        for (p in installedPlugins) {
            if (p.id != manifest.id) {
                for (slug in p.manifest.skills) {
                    otherPluginSkills[slug] = p.id
                }
            }
        }
        for (slug in manifest.skills) {
            if (slug in builtinSkillSlugs) {
                return InstallResult.Err("随包技能「$slug」与内置技能同名冲突")
            }
            val owner = otherPluginSkills[slug]
            if (owner != null) {
                return InstallResult.Err("随包技能「$slug」已被已安装插件「$owner」占用")
            }
        }
        return InstallResult.Ok(Unit)
    }

    /**
     * 解析并全面校验暂存的 `.cfplugin` 文件，产出安装前预览 [PendingPluginInstall]。
     */
    fun inspectStagedPackage(
        stagedFile: File,
        sourceFileName: String,
        installedPlugins: List<InstalledPlugin>,
        kernelTools: List<String>,
        builtinSkillSlugs: Set<String>,
        manualMcpIds: Set<String> = emptySet(),
    ): InstallResult<PendingPluginInstall> {
        if (!stagedFile.isFile) {
            return InstallResult.Err("找不到暂存插件包：${stagedFile.name}")
        }
        val fileLen = stagedFile.length()
        if (fileLen <= 0L) {
            return InstallResult.Err("插件包文件为空")
        }
        if (fileLen > MAX_ARCHIVE_BYTES) {
            return InstallResult.Err("插件包超过 8 MiB 上限")
        }

        var manifestText: String? = null
        var readmeText = ""
        val actualSkillDirs = LinkedHashSet<String>()
        val skillMarkdowns = LinkedHashMap<String, String>()
        var entryCount = 0
        var totalUnpacked = 0L

        runCatching {
            stagedFile.inputStream().buffered().use { raw ->
                ZipInputStream(raw).use { zip ->
                    val buf = ByteArray(8 * 1024)
                    var entry = zip.nextEntry
                    while (entry != null) {
                        entryCount++
                        if (entryCount > MAX_ENTRIES) {
                            return InstallResult.Err("插件包条目数超过 $MAX_ENTRIES 上限")
                        }
                        when (val pathCheck = validateZipEntryName(entry.name)) {
                            is InstallResult.Err -> return pathCheck
                            is InstallResult.Ok -> Unit
                        }
                        val name = entry.name.trimEnd('/')
                        if (name.startsWith(SKILLS_PREFIX)) {
                            val rest = name.substring(SKILLS_PREFIX.length)
                            val slug = rest.substringBefore('/')
                            if (slug.isNotEmpty()) {
                                actualSkillDirs.add(slug)
                            }
                        }

                        if (!entry.isDirectory) {
                            val needText = entry.name == MANIFEST_FILE ||
                                entry.name.equals(README_FILE, ignoreCase = true) ||
                                (entry.name.startsWith(SKILLS_PREFIX) &&
                                    entry.name.substringAfter(SKILLS_PREFIX).count { it == '/' } == 1 &&
                                    entry.name.endsWith("/SKILL.md"))
                            val out = if (needText) ByteArrayOutputStream() else null
                            while (true) {
                                val n = zip.read(buf)
                                if (n < 0) break
                                totalUnpacked += n
                                if (totalUnpacked > MAX_UNPACKED_BYTES) {
                                    return InstallResult.Err("插件包解压后总大小超过 32 MiB 上限")
                                }
                                out?.write(buf, 0, n)
                            }
                            if (out != null) {
                                val text = out.toByteArray().toString(Charsets.UTF_8)
                                when {
                                    entry.name == MANIFEST_FILE -> manifestText = text
                                    entry.name.equals(README_FILE, ignoreCase = true) -> readmeText = text
                                    entry.name.startsWith(SKILLS_PREFIX) && entry.name.endsWith("/SKILL.md") -> {
                                        val slug = entry.name.removePrefix(SKILLS_PREFIX).substringBefore('/')
                                        skillMarkdowns[slug] = text
                                    }
                                }
                            }
                        }
                        entry = zip.nextEntry
                    }
                }
            }
        }.onFailure { e ->
            return InstallResult.Err("读取插件压缩包失败：${e.message ?: e.javaClass.simpleName}")
        }

        val rawManifest = manifestText
            ?: return InstallResult.Err("插件包根目录缺少 plugin.json")

        val manifest = when (val mr = parseManifest(rawManifest)) {
            is InstallResult.Ok -> mr.value
            is InstallResult.Err -> return mr
        }

        // skills[] 与包内 skills/<slug>/ 实际目录必须一一对应（多列/漏列都拒）
        val declaredSkills = manifest.skills.toSet()
        if (declaredSkills != actualSkillDirs) {
            val missingInZip = declaredSkills - actualSkillDirs
            val undeclaredInZip = actualSkillDirs - declaredSkills
            val detail = buildString {
                if (missingInZip.isNotEmpty()) append("缺少目录：${missingInZip.joinToString(", ")}；")
                if (undeclaredInZip.isNotEmpty()) append("未在 skills[] 声明的目录：${undeclaredInZip.joinToString(", ")}")
            }.trimEnd('；')
            return InstallResult.Err("skills[] 清单与包内 skills/ 目录不一致（$detail）")
        }

        val parsedSkills = ArrayList<PluginSkillRef>(manifest.skills.size)
        for (slug in manifest.skills) {
            val md = skillMarkdowns[slug]
                ?: return InstallResult.Err("技能目录 skills/$slug/ 缺少 SKILL.md")
            when (val sr = validateSkillMarkdown(slug, md)) {
                is InstallResult.Ok -> parsedSkills.add(sr.value)
                is InstallResult.Err -> return sr
            }
        }

        when (val cr = checkConflicts(manifest, installedPlugins, kernelTools, builtinSkillSlugs, manualMcpIds)) {
            is InstallResult.Ok -> Unit
            is InstallResult.Err -> return cr
        }

        val existing = installedPlugins.firstOrNull { it.id == manifest.id }
        val relation = classifyVersion(existing?.manifest?.version, manifest.version)
        val requiresKernel = !manifest.isSupportedInStage1
        val kernelReason = if (requiresKernel) {
            "该插件类型为 ${manifest.type}，需内核 POST /api/plugins 支持 path 与 security_policy 字段（阶段 2）"
        } else {
            ""
        }

        val sha = sha256Hex(stagedFile)
        return InstallResult.Ok(
            PendingPluginInstall(
                stagedPath = stagedFile.absolutePath,
                sourceFileName = sourceFileName,
                sha256 = sha,
                manifest = manifest,
                readme = readmeText,
                skillRefs = parsedSkills,
                versionRelation = relation,
                existingVersion = existing?.manifest?.version,
                requiresKernelSupport = requiresKernel,
                kernelSupportReason = kernelReason,
            ),
        )
    }

    /**
     * 把通过校验的 [PendingPluginInstall] 原子解压落盘到 `filesDir/plugins/<id>/<version>/` 并写 `install.json`。
     *
     * - 降级（[InstallVersionRelation.DOWNGRADE]）默认拒绝，除非 [allowDowngrade] 为 true；
     * - 阶段 2 类型（`wasm` / `http`）直接拒绝落盘；
     * - 升级或重装时保留旧版已填写的密钥（与新传入的 [secrets] 合并，新非空值优先）与启用态。
     */
    fun commitStagedInstall(
        filesDir: File,
        pending: PendingPluginInstall,
        allowDowngrade: Boolean = false,
        secrets: Map<String, String> = emptyMap(),
    ): InstallResult<InstalledPlugin> {
        val manifest = pending.manifest
        if (!manifest.isSupportedInStage1) {
            return InstallResult.Err(
                pending.kernelSupportReason.ifBlank { "当前阶段暂不支持安装 ${manifest.type} 类型插件" },
            )
        }
        if (pending.versionRelation == InstallVersionRelation.DOWNGRADE && !allowDowngrade) {
            return InstallResult.Err(
                "目标版本 v${manifest.version} 低于已安装版本 v${pending.existingVersion.orEmpty()}，请勾选「允许降级」后再试",
            )
        }
        val stagedFile = File(pending.stagedPath)
        if (!stagedFile.isFile) {
            return InstallResult.Err("暂存插件包已失效，请重新选择文件")
        }

        val pluginsRoot = File(filesDir, PLUGINS_DIR)
        if (!pluginsRoot.isDirectory && !pluginsRoot.mkdirs()) {
            return InstallResult.Err("无法创建插件根目录")
        }

        val existing = readInstalledPlugin(File(pluginsRoot, manifest.id))
        val mergedSecrets = LinkedHashMap<String, String>()
        existing?.configuredSecrets?.forEach { (k, v) -> if (k.isNotBlank() && v.isNotBlank()) mergedSecrets[k] = v }
        secrets.forEach { (k, v) ->
            val key = k.trim()
            if (key.isNotEmpty()) {
                if (v.isNotBlank()) mergedSecrets[key] = v.trim()
            }
        }
        val enabled = existing?.enabled ?: true

        // 1. 先解压到同分区的临时目录
        val tmpDir = File(pluginsRoot, ".tmp-${manifest.id}-${UUID.randomUUID()}")
        if (!tmpDir.mkdirs()) {
            return InstallResult.Err("无法创建临时解压目录")
        }

        val unpackedOk = runCatching {
            stagedFile.inputStream().buffered().use { raw ->
                ZipInputStream(raw).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val validName = when (val r = validateZipEntryName(entry.name)) {
                            is InstallResult.Ok -> r.value
                            is InstallResult.Err -> error(r.message)
                        }
                        val outFile = File(tmpDir, validName)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { out -> zip.copyTo(out) }
                        }
                        entry = zip.nextEntry
                    }
                }
            }
            true
        }.getOrElse {
            tmpDir.deleteRecursively()
            return InstallResult.Err("解压插件包失败：${it.message}")
        }
        if (!unpackedOk) {
            tmpDir.deleteRecursively()
            return InstallResult.Err("解压插件包失败")
        }

        // 2. 移入 filesDir/plugins/<id>/<version>/ 并写 install.json
        val pluginDir = File(pluginsRoot, manifest.id)
        if (!pluginDir.isDirectory && !pluginDir.mkdirs()) {
            tmpDir.deleteRecursively()
            return InstallResult.Err("无法创建插件目录：${manifest.id}")
        }
        val versionDir = File(pluginDir, manifest.version)
        val backupVersionDir = if (versionDir.exists()) {
            File(pluginDir, ".bak-${manifest.version}-${UUID.randomUUID()}").also { bak ->
                versionDir.renameTo(bak)
            }
        } else {
            null
        }

        if (!tmpDir.renameTo(versionDir)) {
            // 跨目录 rename 极少失败，兜底 copy + delete
            val copied = runCatching {
                tmpDir.copyRecursively(versionDir, overwrite = true)
            }.getOrDefault(false)
            tmpDir.deleteRecursively()
            if (!copied) {
                backupVersionDir?.renameTo(versionDir)
                return InstallResult.Err("无法写入插件版本目录")
            }
        }

        val now = System.currentTimeMillis()
        val installed = InstalledPlugin(
            manifest = manifest,
            enabled = enabled,
            installedAt = now,
            sourceFileName = pending.sourceFileName,
            sha256 = pending.sha256,
            configuredSecrets = mergedSecrets,
            readme = pending.readme,
            skillRefs = pending.skillRefs,
        )

        val metaWritten = runCatching {
            writeInstallMeta(File(pluginDir, INSTALL_META_FILE), installed)
            true
        }.getOrDefault(false)

        if (!metaWritten) {
            versionDir.deleteRecursively()
            backupVersionDir?.renameTo(versionDir)
            return InstallResult.Err("写入插件安装记录失败")
        }

        // 清理备份及旧版本目录（保留当前 version 目录与 install.json）
        backupVersionDir?.deleteRecursively()
        pluginDir.listFiles()?.forEach { child ->
            if (child.isDirectory && child.name != manifest.version) {
                child.deleteRecursively()
            }
        }

        return InstallResult.Ok(installed)
    }

    /**
     * 列出 `filesDir/plugins/` 下全部已安装的插件（按安装时间倒序）。
     *
     * @param activeWorkspace 当前工作区路径（非空时順带填入每个随包技能的 [PluginSkillRef.syncedInWorkspace]）。
     */
    fun listInstalled(filesDir: File, activeWorkspace: String = ""): List<InstalledPlugin> {
        val root = File(filesDir, PLUGINS_DIR)
        if (!root.isDirectory) return emptyList()
        return root.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != INCOMING_DIR }
            ?.mapNotNull { readInstalledPlugin(it, activeWorkspace) }
            ?.sortedByDescending { it.installedAt }
            .orEmpty()
    }

    /**
     * 修改已安装插件的壳侧启用状态（写回 `install.json`）。
     */
    fun setPluginEnabled(filesDir: File, id: String, enabled: Boolean): InstallResult<InstalledPlugin> {
        val pluginDir = File(File(filesDir, PLUGINS_DIR), id)
        val current = readInstalledPlugin(pluginDir)
            ?: return InstallResult.Err("找不到已安装插件：$id")
        val updated = current.copy(enabled = enabled)
        return runCatching {
            writeInstallMeta(File(pluginDir, INSTALL_META_FILE), updated)
            InstallResult.Ok(updated)
        }.getOrElse {
            InstallResult.Err("更新插件状态失败：${it.message}")
        }
    }

    /**
     * 更新已安装插件的密钥配置（写回 `install.json`）。
     */
    fun updatePluginSecrets(
        filesDir: File,
        id: String,
        secrets: Map<String, String>,
    ): InstallResult<InstalledPlugin> {
        val pluginDir = File(File(filesDir, PLUGINS_DIR), id)
        val current = readInstalledPlugin(pluginDir)
            ?: return InstallResult.Err("找不到已安装插件：$id")
        val cleaned = LinkedHashMap<String, String>()
        secrets.forEach { (k, v) ->
            val key = k.trim()
            if (key.isNotEmpty() && v.isNotBlank()) {
                cleaned[key] = v.trim()
            }
        }
        val updated = current.copy(configuredSecrets = cleaned)
        return runCatching {
            writeInstallMeta(File(pluginDir, INSTALL_META_FILE), updated)
            InstallResult.Ok(updated)
        }.getOrElse {
            InstallResult.Err("保存插件密钥失败：${it.message}")
        }
    }

    /**
     * 卸载指定插件（删除 `filesDir/plugins/<id>` 整棵目录）。
     */
    fun uninstall(filesDir: File, id: String): Boolean {
        if (!ID_REGEX.matches(id)) return false
        val pluginDir = File(File(filesDir, PLUGINS_DIR), id)
        if (!pluginDir.exists()) return false
        return pluginDir.deleteRecursively()
    }

    /**
     * 获取已装插件内某个随包技能的母本目录：`filesDir/plugins/<id>/<version>/skills/<slug>`。
     */
    fun skillSourceDir(filesDir: File, id: String, version: String, slug: String): File =
        File(File(File(File(filesDir, PLUGINS_DIR), id), version), "skills/$slug")

    private fun readInstalledPlugin(pluginDir: File, activeWorkspace: String = ""): InstalledPlugin? {
        val metaFile = File(pluginDir, INSTALL_META_FILE)
        if (!metaFile.isFile) return null
        return runCatching {
            val obj = JSONObject(metaFile.readText())
            val manifestObj = obj.optJSONObject("manifest") ?: return null
            val manifest = (parseManifest(manifestObj.toString()) as? InstallResult.Ok)?.value
                ?: return null
            val versionDir = File(pluginDir, manifest.version)
            if (!versionDir.isDirectory) return null

            val secretsObj = obj.optJSONObject("secrets")
            val secrets = LinkedHashMap<String, String>()
            if (secretsObj != null) {
                val keys = secretsObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = secretsObj.optString(k, "")
                    if (k.isNotBlank() && v.isNotBlank()) secrets[k] = v
                }
            }

            val readmeFile = File(versionDir, README_FILE).takeIf { it.isFile }
                ?: versionDir.listFiles()?.firstOrNull { it.isFile && it.name.equals(README_FILE, ignoreCase = true) }
            val readme = readmeFile?.let { runCatching { it.readText() }.getOrDefault("") }.orEmpty()

            val skillRefs = manifest.skills.map { slug ->
                val mdFile = File(File(File(versionDir, "skills"), slug), "SKILL.md")
                val parsed = if (mdFile.isFile) {
                    (validateSkillMarkdown(slug, mdFile.readText()) as? InstallResult.Ok)?.value
                } else {
                    null
                }
                val synced = activeWorkspace.isNotBlank() &&
                    PluginSkills.isSkillSynced(activeWorkspace, manifest.id, manifest.version, slug)
                (parsed ?: PluginSkillRef(slug = slug, displayName = slug)).copy(
                    syncedInWorkspace = synced,
                )
            }

            InstalledPlugin(
                manifest = manifest,
                enabled = obj.optBoolean("enabled", true),
                installedAt = obj.optLong("installed_at", 0L),
                sourceFileName = obj.optString("source_file_name"),
                sha256 = obj.optString("sha256"),
                configuredSecrets = secrets,
                readme = readme,
                skillRefs = skillRefs,
            )
        }.getOrNull()
    }

    private fun writeInstallMeta(metaFile: File, plugin: InstalledPlugin) {
        val m = plugin.manifest
        val manifestJson = JSONObject().apply {
            put("schema", m.schema)
            put("id", m.id)
            put("name", m.name)
            put("version", m.version)
            put("description", m.description)
            put("author", m.author)
            put("homepage", m.homepage)
            put("type", m.type)
            put("endpoint", m.endpoint)
            put("command", m.command)
            put("args", JSONArray(m.args))
            put("env", JSONObject().apply { m.env.forEach { (k, v) -> put(k, v) } })
            put(
                "permissions",
                JSONObject().apply {
                    put("network", JSONArray(m.permissions.network))
                    put("filesystem", m.permissions.filesystem)
                    put("secrets", JSONArray(m.permissions.secrets))
                    put("requires_approval", m.permissions.requiresApproval)
                    put("allowed_actions", JSONArray(m.permissions.allowedActions))
                },
            )
            put("skills", JSONArray(m.skills))
        }
        val root = JSONObject().apply {
            put("id", m.id)
            put("version", m.version)
            put("enabled", plugin.enabled)
            put("installed_at", plugin.installedAt)
            put("source_file_name", plugin.sourceFileName)
            put("sha256", plugin.sha256)
            put("secrets", JSONObject().apply { plugin.configuredSecrets.forEach { (k, v) -> put(k, v) } })
            put("manifest", manifestJson)
        }
        val tmp = File(metaFile.parentFile, "${metaFile.name}.tmp")
        tmp.writeText(root.toString(2))
        if (!tmp.renameTo(metaFile)) {
            metaFile.writeText(root.toString(2))
            tmp.delete()
        }
    }

    private fun jsonStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length())
            .map { arr.optString(it).trim() }
            .filter { it.isNotEmpty() }
    }

    private fun sha256Hex(file: File): String = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(8 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")
}
