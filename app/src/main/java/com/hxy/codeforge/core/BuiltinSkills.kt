package com.hxy.codeforge.core

import android.content.Context
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 内置纯知识技能：把 APK 里的 `builtin-skills.zip` 铺到工作区的技能目录。
 *
 * ## 为什么必须铺到工作区，而不是放一份全局的
 *
 * 内核的技能发现路径是**当前工作区**下的 `.codeforge/skills/<slug>/SKILL.md`
 * （见内核 pkg/agent/skills.go 的 `skillsDir()`）。它是 `WorkDir()` 的相对路径，
 * 不是全局目录 —— 所以「一份放 app 私有目录」的做法内核根本看不见。
 *
 * 铺放目标因此是 `<workspace>/.codeforge/skills/`：
 *  - 会话创建/切换工作区时铺一次，之后工作区内一直可见；
 *  - 每个工作区一份副本（同一份 zip 解出来的，6 个技能约 2 MB）。
 *
 * ## 幂等与冲突
 *
 *  - 目录已存在且带 `builtin.version` 标记 → 是我们铺的，跳过（不重复解包）。
 *  - 目录已存在但**没有**标记 → 用户自己建的同名技能，**不碰**（不覆盖用户资产）。
 *  - 标记存在但版本号不同 → 内容升级，重铺。
 *
 * ## frontmatter 适配（不做了就静默失效）
 *
 * 内核的 frontmatter 解析器逐行做 `strings.Cut(line, ":")`，**不支持 YAML 块标量**。
 * 上游 skill 的 `description: |` + 缩进多行会被解析成 `description="|"`，技能列表里只剩一根竖线。
 * 所以内置资源的 frontmatter 一律改写成单行 description，并补 display_name / triggers / enabled。
 */
object BuiltinSkills {

    /** APK 内的资产名。 */
    private const val ASSET = "builtin-skills.zip"

    /** 内置技能版本；变更内容时 +1，铺放会据此重铺。 */
    private const val VERSION = "1"

    /** 内置技能目录名（相对工作区）。与内核约定一致。 */
    private const val SKILLS_SUBDIR = ".codeforge/skills"

    /** 铺放标记文件名（放在每个技能目录内）。 */
    private const val MARKER = "builtin.version"

    /** 资产里的技能 slug 列表（首次读 zip 后缓存，避免每次切工作区都开包）。 */
    @Volatile
    private var cachedSlugs: List<String>? = null

    /**
     * 把内置技能铺到 [workspace]。
     *
     * 必须在 IO 线程调用（要解 zip + 写文件）。失败不抛：技能是增强项，
     * 铺不上不该阻断工作区切换。
     *
     * @return 实际铺放的技能数量（已存在跳过时为 0）。
     */
    fun install(context: Context, workspace: String): Int {
        if (workspace.isBlank()) return 0
        val root = File(workspace, SKILLS_SUBDIR)

        val slugs = cachedSlugs ?: assetSlugs(context)?.also { cachedSlugs = it } ?: return 0
        if (slugs.isEmpty()) return 0

        // 快路径：全部已是当前版本 → 连 zip 都不用开。
        // 切工作区是高频操作（每次发消息都会重新指一次），不能每次都解包。
        if (slugs.all { planFor(File(root, it)) == Plan.SKIP }) return 0

        if (!root.isDirectory && !root.mkdirs()) return 0

        var installed = 0
        for (slug in slugs) {
            val dir = File(root, slug)
            when (planFor(dir)) {
                Plan.SKIP -> continue
                Plan.INSTALL -> {
                    // 先删旧版（版本升级场景），再解包
                    if (dir.exists()) dir.deleteRecursively()
                    if (unpackSkill(context, slug, dir)) installed++
                }
            }
        }
        return installed
    }

    /** 某个技能目录的铺放决策。 */
    internal enum class Plan { INSTALL, SKIP }

    /**
     * 判定一个技能目录要不要铺。
     *
     * 没有标记 → 用户自建，**跳过**（宁可少一个技能，也不覆盖用户写的东西）。
     * 有标记但版本旧 → 重铺；版本一致 → 跳过。
     */
    private fun planFor(dir: File): Plan = planFor(
        exists = dir.exists(),
        markerVersion = File(dir, MARKER)
            .takeIf { it.isFile }
            ?.let { runCatching { it.readText().trim() }.getOrDefault("") },
    )

    /**
     * [planFor] 的纯函数版本（给单测用）。
     *
     * 抽出来是因为这条判定带一条**不可违反的约束**：用户自建的同名技能目录
     * 绝不能被内置资源覆盖 —— 那是用户自己的资产，铺内置技能会静默毁掉它。
     * 这条判据藏在文件系统交互里，没法在 JVM 单测里直接断言。
     */
    internal fun planFor(exists: Boolean, markerVersion: String?): Plan = when {
        !exists -> Plan.INSTALL
        // 没有标记 = 用户自己建的 → 不碰
        markerVersion == null -> Plan.SKIP
        markerVersion == VERSION -> Plan.SKIP
        else -> Plan.INSTALL
    }

    /**
     * 从 zip 里解出单个技能目录。
     *
     * zip 里的路径形如 `<slug>/SKILL.md`、`<slug>/references/x.md`；只取该 slug 前缀的条目。
     */
    private fun unpackSkill(context: Context, slug: String, dest: File): Boolean {
        val prefix = "$slug/"
        var wrote = false
        runCatching {
            context.assets.open(ASSET).use { raw ->
                ZipInputStream(raw).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (!entry.isDirectory && name.startsWith(prefix)) {
                            val rel = name.substring(prefix.length)
                            // 越界路径直接拒（zip 理论上可信，但这是外部输入）
                            if (rel.isNotEmpty() && !rel.contains("..")) {
                                val out = File(dest, rel)
                                out.parentFile?.mkdirs()
                                out.outputStream().use { zip.copyTo(it) }
                                wrote = true
                            }
                        }
                        entry = zip.nextEntry
                    }
                }
            }
        }.onFailure { return false }

        if (!wrote) return false
        // 写版本标记：下次铺放据此判定「这是我们铺的」
        runCatching { File(dest, MARKER).writeText(VERSION) }
        return true
    }

    /**
     * 列出资产里的一级目录名（技能 slug）。
     *
     * 读 zip 的中央目录做这件事：只解一次目录，不解内容。
     */
    private fun assetSlugs(context: Context): List<String>? {
        val names = LinkedHashSet<String>()
        runCatching {
            context.assets.open(ASSET).use { raw ->
                ZipInputStream(raw).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val slash = entry.name.indexOf('/')
                        if (slash > 0) names.add(entry.name.substring(0, slash))
                        entry = zip.nextEntry
                    }
                }
            }
        }.onFailure { return null }
        return names.toList()
    }
}
