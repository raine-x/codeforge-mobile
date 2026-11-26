package com.hxy.codeforge.model

/** 动态插件数据模型；解析、校验与落盘位于 core 层。 */

/** 插件声明式权限清单（对应 `plugin.json` 的 `permissions` 字段）。 */
data class PluginPermissions(
    val network: List<String> = emptyList(),
    val filesystem: String = "none",
    val secrets: List<String> = emptyList(),
    val requiresApproval: Boolean = false,
    val allowedActions: List<String> = emptyList(),
)

/** `.cfplugin` 包内 `plugin.json` 清单定义。 */
data class PluginManifest(
    val schema: Int = 1,
    /** 全局唯一标识（`^[a-z][a-z0-9-]{1,31}$`），作为工具名前缀 `<id>.<tool>`。 */
    val id: String,
    val name: String,
    val version: String,
    val description: String = "",
    val author: String = "",
    val homepage: String = "",
    /** 驱动类型：`skill` / `mcp` / `mcp-http` / `wasm` / `http`。 */
    val type: String,
    val endpoint: String = "",
    val command: String = "",
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val permissions: PluginPermissions = PluginPermissions(),
    val skills: List<String> = emptyList(),
) {
    val isPureSkill: Boolean get() = type == "skill"

    val isSupportedInStage1: Boolean get() = type == "skill" || type == "mcp" || type == "mcp-http"

    /** 汇总所有待用户填写的密钥键名（合并 `permissions.secrets` 与 `env` 中空值键并去重）。 */
    val secretKeys: List<String>
        get() = (permissions.secrets + env.filterValues { it.isBlank() }.keys)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
}

/** 随包技能摘要，提取自 `skills/<slug>/SKILL.md` frontmatter。 */
data class PluginSkillRef(
    val slug: String,
    val displayName: String = "",
    val description: String = "",
    val triggers: List<String> = emptyList(),
    val syncedInWorkspace: Boolean = false,
)

/** 已安装的插件记录（对应 `filesDir/plugins/<id>/install.json`）。 */
data class InstalledPlugin(
    val manifest: PluginManifest,
    val enabled: Boolean = true,
    val installedAt: Long = 0L,
    val sourceFileName: String = "",
    val sha256: String = "",
    val configuredSecrets: Map<String, String> = emptyMap(),
    val readme: String = "",
    val skillRefs: List<PluginSkillRef> = emptyList(),
) {
    val id: String get() = manifest.id

    /** 合并默认 `manifest.env` 与用户填写的密钥，用于下发给 `/api/plugins`。 */
    fun mergedEnv(): List<Pair<String, String>> {
        val merged = LinkedHashMap<String, String>()
        manifest.env.forEach { (k, v) -> if (k.isNotBlank()) merged[k] = v }
        configuredSecrets.forEach { (k, v) -> if (k.isNotBlank() && v.isNotBlank()) merged[k] = v }
        return merged.entries.map { it.key to it.value }
    }
}

enum class InstallVersionRelation {
    FRESH,
    UPGRADE,
    REINSTALL,
    DOWNGRADE,
}

/** 导入 `.cfplugin` 后的安装前预览态（暂存在 `filesDir/plugins/incoming/`）。 */
data class PendingPluginInstall(
    val stagedPath: String,
    val sourceFileName: String,
    val sha256: String,
    val manifest: PluginManifest,
    val readme: String = "",
    val skillRefs: List<PluginSkillRef> = emptyList(),
    val versionRelation: InstallVersionRelation = InstallVersionRelation.FRESH,
    val existingVersion: String? = null,
    val requiresKernelSupport: Boolean = false,
    val kernelSupportReason: String = "",
)
