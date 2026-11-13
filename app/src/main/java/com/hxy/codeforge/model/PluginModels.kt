package com.hxy.codeforge.model

/**
 * 动态插件系统的纯数据模型（遵守 AGENTS.md §2.3 第 5 条：仅含 data class / enum，
 * 无业务逻辑、无 JSON 解析、无 Android 依赖）。
 *
 * JSON 解析、zip 校验与落盘逻辑全部在 `core/PluginInstaller.kt` 与 `core/PluginSkills.kt`。
 */

/**
 * 插件声明式权限清单（`plugin.json` 的 `permissions` 段）。
 *
 * 阶段 1 只做「安装前展示 + 详情页展示 + 风险提示」，不做内核侧强拦截
 * （强拦截依赖阶段 2 内核 `POST /api/plugins` 接收 `security_policy`）。
 */
data class PluginPermissions(
    /** 声明会访问的域名列表（如 `["api.example.com"]`）。 */
    val network: List<String> = emptyList(),
    /** 文件系统访问范围声明（如 `"none"` / `"workspace"` / `"full"`）。 */
    val filesystem: String = "none",
    /** 需要的密钥环境变量名列表（如 `["API_KEY"]`），驱动详情页与安装预览的密钥输入框。 */
    val secrets: List<String> = emptyList(),
    /** 是否声明每次调用需要人工审批。 */
    val requiresApproval: Boolean = false,
    /** 允许的动作白名单声明。 */
    val allowedActions: List<String> = emptyList(),
)

/**
 * `.cfplugin` 包内的 `plugin.json` 清单（`schema = 1`）。
 *
 * 一个包可以是：
 * - 纯文本技能包（`type == "skill"`，仅含 `skills/`，不调 `/api/plugins`）；
 * - 可执行工具包（`type == "mcp"` 或 `"mcp-http"`，可同时携带 `skills/`）；
 * - 阶段 2 类型（`type == "wasm"` 或 `"http"`，阶段 1 可解析识别但安装置灰）。
 */
data class PluginManifest(
    val schema: Int = 1,
    /** 全局唯一标识（`^[a-z][a-z0-9-]{1,31}$`），可执行插件同时作为工具名前缀 `<id>.<tool>`。 */
    val id: String,
    /** 界面显示名。 */
    val name: String,
    /** 语义化版本号 `x.y.z`，用于升级/重装/降级判定。 */
    val version: String,
    val description: String = "",
    val author: String = "",
    val homepage: String = "",
    /** 插件类型：`skill` / `mcp` / `mcp-http` / `wasm` / `http`。 */
    val type: String,
    /** `mcp-http` / `http` 驱动的远程端点 URL。 */
    val endpoint: String = "",
    /** `mcp`（stdio）驱动的启动命令。 */
    val command: String = "",
    /** `mcp`（stdio）驱动的启动参数。 */
    val args: List<String> = emptyList(),
    /** 默认环境变量表（子进程或插件运行时读取，可被用户填写的密钥覆盖）。 */
    val env: Map<String, String> = emptyMap(),
    val permissions: PluginPermissions = PluginPermissions(),
    /** 随包技能的 slug 列表，必须与包内 `skills/<slug>/` 目录一一对应。 */
    val skills: List<String> = emptyList(),
) {
    /** 是否为纯文本技能包（不向内核注册可执行插件，只铺放 `SKILL.md`）。 */
    val isPureSkill: Boolean get() = type == "skill"

    /** 是否为阶段 1 可直接安装的驱动类型。 */
    val isSupportedInStage1: Boolean get() = type == "skill" || type == "mcp" || type == "mcp-http"

    /**
     * 汇总所有需要用户填写的密钥键名（`permissions.secrets` 与 `env` 中值为空的键并集）。
     *
     * 有些包只在 `permissions.secrets` 声明，有些包在 `env` 里留空串 `{"API_KEY": ""}` ——
     * 合并去重后详情页不会漏掉任何一种写法。
     */
    val secretKeys: List<String>
        get() = (permissions.secrets + env.filterValues { it.isBlank() }.keys)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
}

/**
 * 随包技能摘要（从包内 `skills/<slug>/SKILL.md` 的 frontmatter 提取）。
 */
data class PluginSkillRef(
    val slug: String,
    val displayName: String = "",
    val description: String = "",
    val triggers: List<String> = emptyList(),
    /** 当前工作区下是否已铺放该技能且版本标记一致。 */
    val syncedInWorkspace: Boolean = false,
)

/**
 * 已落盘安装的插件记录（对应 `filesDir/plugins/<id>/install.json`）。
 */
data class InstalledPlugin(
    val manifest: PluginManifest,
    /** 壳侧启用态：停用时可执行插件 PATCH `enabled=false`，随包技能从工作区移除副本。 */
    val enabled: Boolean = true,
    val installedAt: Long = 0L,
    val sourceFileName: String = "",
    val sha256: String = "",
    /** 用户已填写的密钥/环境变量（键 → 明文值）。 */
    val configuredSecrets: Map<String, String> = emptyMap(),
    /** 包内 `README.md` 原文（若存在）。 */
    val readme: String = "",
    /** 随包技能摘要列表。 */
    val skillRefs: List<PluginSkillRef> = emptyList(),
) {
    val id: String get() = manifest.id

    /** 合并默认 `manifest.env` 与用户填写的 `configuredSecrets`，用于下发给 `/api/plugins`。 */
    fun mergedEnv(): List<Pair<String, String>> {
        val merged = LinkedHashMap<String, String>()
        manifest.env.forEach { (k, v) -> if (k.isNotBlank()) merged[k] = v }
        configuredSecrets.forEach { (k, v) -> if (k.isNotBlank() && v.isNotBlank()) merged[k] = v }
        return merged.entries.map { it.key to it.value }
    }
}

/**
 * 待安装插件与已装插件的版本关系。
 */
enum class InstallVersionRelation {
    /** 首次安装（同 id 未安装）。 */
    FRESH,
    /** 新包版本号高于已装版本。 */
    UPGRADE,
    /** 新包版本号与已装版本相同。 */
    REINSTALL,
    /** 新包版本号低于已装版本（默认拒绝，需用户显式勾选允许降级）。 */
    DOWNGRADE,
}

/**
 * 导入 `.cfplugin` 后的安装前预览态（暂存在 `filesDir/plugins/incoming/`，等用户确认才落盘）。
 */
data class PendingPluginInstall(
    val stagedPath: String,
    val sourceFileName: String,
    val sha256: String,
    val manifest: PluginManifest,
    val readme: String = "",
    val skillRefs: List<PluginSkillRef> = emptyList(),
    val versionRelation: InstallVersionRelation = InstallVersionRelation.FRESH,
    val existingVersion: String? = null,
    /** `wasm` / `http` 在阶段 1 解析识别，但因内核 `POST /api/plugins` 无 `path` 字段置灰。 */
    val requiresKernelSupport: Boolean = false,
    val kernelSupportReason: String = "",
)
