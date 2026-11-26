package com.hxy.codeforge.core

import android.content.Context
import java.io.File

/**
 * 动态插件多工作区技能同步器。
 * 母本保存在 `filesDir/plugins/<id>/<version>/skills/<slug>/`，工作区切换或启停时同步到
 * `<workspace>/.codeforge/skills/`；通过 `plugin.marker` 与 `builtin.version` 隔离，
 * 绝不覆盖或删除内置技能与用户自建技能目录。
 */
object PluginSkills {

    private const val SKILLS_SUBDIR = ".codeforge/skills"
    internal const val PLUGIN_MARKER = "plugin.marker"
    internal const val BUILTIN_MARKER = "builtin.version"

    internal enum class SyncPlan {
        INSTALL,
        SKIP,
    }

    internal fun markerValue(pluginId: String, version: String): String = "$pluginId@$version"

    /** 判定目标技能目录是否需要铺放；内置标记或无标记（用户自建）目录一律跳过。 */
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

    /** 仅当目录带 `plugin.marker` 且不再归属当前启用插件时才允许作为孤儿清理。 */
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

    fun sync(context: Context, workspace: String) {
        sync(context.filesDir, workspace)
    }

    fun sync(filesDir: File, workspace: String) {
        if (workspace.isBlank()) return
        val wsDir = File(workspace)
        if (!wsDir.exists() && !wsDir.mkdirs()) return

        val skillsRoot = File(wsDir, SKILLS_SUBDIR)
        val installed = PluginInstaller.listInstalled(filesDir)

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

    fun isSkillSynced(workspace: String, pluginId: String, version: String, slug: String): Boolean {
        if (workspace.isBlank()) return false
        val dir = File(File(workspace, SKILLS_SUBDIR), slug)
        val marker = File(dir, PLUGIN_MARKER)
        if (!marker.isFile || !File(dir, "SKILL.md").isFile) return false
        val content = runCatching { marker.readText().trim() }.getOrDefault("")
        return content == markerValue(pluginId, version)
    }
}
