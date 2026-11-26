package com.hxy.codeforge.core

import android.content.Context
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 内置技能同步器：将 `builtin-skills.zip` 按需解包至 `<workspace>/.codeforge/skills/`。
 *
 * 仅处理带 `builtin.version` 标记的目录：无标记视为用户自建技能并跳过；
 * 内核 frontmatter 解析不支持 YAML 块标量，资产统一使用扁平单行格式（P-052）。
 */
object BuiltinSkills {

    private const val ASSET = "builtin-skills.zip"
    private const val VERSION = "3"
    private const val SKILLS_SUBDIR = ".codeforge/skills"
    private const val MARKER = "builtin.version"

    @Volatile
    private var cachedSlugs: List<String>? = null

    /** 返回内置技能 slug 集合，供插件安装冲突检查使用。 */
    fun builtinSlugs(context: Context): Set<String> =
        (cachedSlugs ?: assetSlugs(context)?.also { cachedSlugs = it }).orEmpty().toSet()

    /** 在 IO 线程将内置技能铺放至 [workspace]；失败静默忽略，不阻塞工作区切换。 */
    fun install(context: Context, workspace: String): Int {
        if (workspace.isBlank()) return 0
        val root = File(workspace, SKILLS_SUBDIR)

        val slugs = cachedSlugs ?: assetSlugs(context)?.also { cachedSlugs = it } ?: return 0
        if (slugs.isEmpty()) return 0

        // 高频切换工作区时若已全部同步直接短路，避免重复读取 zip
        if (slugs.all { planFor(File(root, it)) == Plan.SKIP }) return 0

        if (!root.isDirectory && !root.mkdirs()) return 0

        var installed = 0
        for (slug in slugs) {
            val dir = File(root, slug)
            when (planFor(dir)) {
                Plan.SKIP -> continue
                Plan.INSTALL -> {
                    if (dir.exists()) dir.deleteRecursively()
                    if (unpackSkill(context, slug, dir)) installed++
                }
            }
        }
        return installed
    }

    internal enum class Plan { INSTALL, SKIP }

    private fun planFor(dir: File): Plan = planFor(
        exists = dir.exists(),
        markerVersion = File(dir, MARKER)
            .takeIf { it.isFile }
            ?.let { runCatching { it.readText().trim() }.getOrDefault("") },
    )

    internal fun planFor(exists: Boolean, markerVersion: String?): Plan = when {
        !exists -> Plan.INSTALL
        // 无版本标记目录属于用户自建资产，严禁覆盖
        markerVersion == null -> Plan.SKIP
        markerVersion == VERSION -> Plan.SKIP
        else -> Plan.INSTALL
    }

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
        runCatching { File(dest, MARKER).writeText(VERSION) }
        return true
    }

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
