package com.hxy.codeforge.core

import android.content.Context
import java.io.File

/**
 * 动态插件的「多工作区技能同步器」。
 *
 * ## 为什么需要单独的同步器（而非只在安装那一刻铺一次）
 *
 * 1. 内核只扫**当前工作区** `<workspace>/.codeforge/skills/<slug>/SKILL.md`；
 * 2. 本 App 的工作区按项目隔离（`spaces/inbox`、`workspace/<projectId>` 或外部目录），且随时切换；
 * 3. 若只在安装时往当时工作区写一份，用户一切项目技能就消失；反过来，卸载或停用时其他项目工作区
 *    又会留下孤儿技能。
 *
 * 因此：
 * - **唯一母本**保存在 `filesDir/plugins/<id>/<version>/skills/<slug>/`；
 * - **单一同步点**：挂在 `ChatViewModel.installBuiltinSkills(workspace)` 内，工作区切换、新建项目、
 *   内核就绪等 6 个入口自动全覆盖；
 * - **三级标记隔离**：
 *   - `builtin.version`：内置技能（归 [BuiltinSkills] 管，本同步器绝不覆盖、绝不删除）；
 *   - `plugin.marker`（内容为 `<pluginId>@<version>`）：动态插件技能；
 *   - 无任何标记：用户自建技能（**永不覆盖、永不删除**）。
 */
object PluginSkills {

    private const val SKILLS_SUBDIR = ".codeforge/skills"
    internal const val PLUGIN_MARKER = "plugin.marker"
    internal const val BUILTIN_MARKER = "builtin.version"

    internal enum class SyncPlan {
        INSTALL,
        SKIP,
    }

    /**
     * 格式化 `plugin.marker` 的标记内容：`<pluginId>@<version>`。
     */
    internal fun markerValue(pluginId: String, version: String): String = "$pluginId@$version"

    /**
     * 判定工作区下某个技能目录 `<workspace>/.codeforge/skills/<slug>` 是否应该铺放（纯函数，供单测）。
     *
     * 铁律：
     * - 目录已存在且含 `builtin.version` → 内置技能，跳过；
     * - 目录已存在但无 `plugin.marker` → 用户手写技能，跳过（绝不覆盖用户资产）；
     * - `plugin.marker` 与目标 `<pluginId>@<version>` 完全一致 → 已是最新，跳过；
     * - 目录不存在，或 `plugin.marker` 版本/归属变化 → 覆盖重铺。
     */
    internal fun planFor(
        exists: Boolean,
        hasBuiltinMarker: Boolean,
        pluginMarkerContent: String?,
        expectedMarker: String,
    ): SyncPlan = when {
        !exists -> SyncPlan.INSTALL
        hasBuiltinMarker -> SyncPlan.SKIP
        pluginMarkerContent == null -> SyncPlan.SKIP
        pluginMarkerContent == expectedMarker -> SyncPlan.SKIP
        else -> SyncPlan.INSTALL
    }

    /**
     * 判定工作区下某个既有技能目录是否为「应清理的孤儿/已停用插件技能」（纯函数，供单测）。
     *
     * 只有**带 `plugin.marker`**、且不在当前「已安装且 enabled」的激活集合 `(slug -> expectedMarker)`
     * 中的目录才允许删除；无标记（用户自建）或带 `builtin.version`（内置）一律返回 false。
     */
    internal fun shouldRemoveOrphan(
        slug: String,
        hasBuiltinMarker: Boolean,
        pluginMarkerContent: String?,
        activeMarkersBySlug: Map<String, String>,
    ): Boolean {
        if (hasBuiltinMarker) return false
        if (pluginMarkerContent == null) return false
        val expected = activeMarkersBySlug[slug] ?: return true
        val installedPluginId = pluginMarkerContent.substringBefore('@')
        val expectedPluginId = expected.substringBefore('@')
        return installedPluginId != expectedPluginId
    }

    /**
     * 把已安装且处于启用态的插件技能同步到目标 [workspace]，并清理该工作区内已卸载或已停用插件留下的副本。
     *
     * 必须在 IO 线程调用。
     */
    fun sync(context: Context, workspace: String) {
        sync(context.filesDir, workspace)
    }

    /**
     * [sync] 的文件根版本（便于 JVM 单测直接传入临时 `filesDir` 与 `workspace`）。
     */
    fun sync(filesDir: File, workspace: String) {
        if (workspace.isBlank()) return
        val wsDir = File(workspace)
        if (!wsDir.exists() && !wsDir.mkdirs()) return

        val skillsRoot = File(wsDir, SKILLS_SUBDIR)
        val installed = PluginInstaller.listInstalled(filesDir)

        // slug -> (pluginId, version, expectedMarker, sourceDir)
        data class ActiveSkill(
            val pluginId: String,
            val version: String,
            val marker: String,
            val sourceDir: File,
        )

        val activeSkills = LinkedHashMap<String, ActiveSkill>()
        for (plugin in installed) {
            if (!plugin.enabled) continue
            val m = plugin.manifest
            for (slug in m.skills) {
                val src = PluginInstaller.skillSourceDir(filesDir, m.id, m.version, slug)
                if (File(src, "SKILL.md").isFile) {
                    activeSkills[slug] = ActiveSkill(
                        pluginId = m.id,
                        version = m.version,
                        marker = markerValue(m.id, m.version),
                        sourceDir = src,
                    )
                }
            }
        }

        val activeMarkerMap = activeSkills.mapValues { it.value.marker }

        // 1. 孤儿与已停用插件技能清理：只动带 plugin.marker 的目录
        if (skillsRoot.isDirectory) {
            skillsRoot.listFiles()?.forEach { dir ->
                if (!dir.isDirectory) return@forEach
                val hasBuiltin = File(dir, BUILTIN_MARKER).isFile
                val markerFile = File(dir, PLUGIN_MARKER)
                val markerText = if (markerFile.isFile) {
                    runCatching { markerFile.readText().trim() }.getOrNull()
                } else {
                    null
                }
                if (shouldRemoveOrphan(dir.name, hasBuiltin, markerText, activeMarkerMap)) {
                    runCatching { dir.deleteRecursively() }
                }
            }
        }

        if (activeSkills.isEmpty()) return
        if (!skillsRoot.isDirectory && !skillsRoot.mkdirs()) return

        // 2. 增量铺放激活中的插件技能
        for ((slug, active) in activeSkills) {
            val targetDir = File(skillsRoot, slug)
            val hasBuiltin = File(targetDir, BUILTIN_MARKER).isFile
            val markerFile = File(targetDir, PLUGIN_MARKER)
            val currentMarker = if (markerFile.isFile) {
                runCatching { markerFile.readText().trim() }.getOrNull()
            } else {
                null
            }
            when (planFor(targetDir.exists(), hasBuiltin, currentMarker, active.marker)) {
                SyncPlan.SKIP -> continue
                SyncPlan.INSTALL -> {
                    runCatching {
                        if (targetDir.exists()) targetDir.deleteRecursively()
                        active.sourceDir.copyRecursively(targetDir, overwrite = true)
                        File(targetDir, PLUGIN_MARKER).writeText(active.marker)
                    }
                }
            }
        }
    }

    /**
     * 检查目标 [workspace] 下指定插件技能是否已同步到位。
     */
    fun isSkillSynced(workspace: String, pluginId: String, version: String, slug: String): Boolean {
        if (workspace.isBlank()) return false
        val dir = File(File(workspace, SKILLS_SUBDIR), slug)
        val marker = File(dir, PLUGIN_MARKER)
        if (!marker.isFile || !File(dir, "SKILL.md").isFile) return false
        val content = runCatching { marker.readText().trim() }.getOrDefault("")
        return content == markerValue(pluginId, version)
    }
}
