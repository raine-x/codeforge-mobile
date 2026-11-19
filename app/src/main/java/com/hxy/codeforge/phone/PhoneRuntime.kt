package com.hxy.codeforge.phone

import android.app.Application
import android.content.Context
import com.hxy.codeforge.core.EnvKit
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * 手机操控模块的进程级运行时持有者（遵循 AGENTS §2.3，不依赖 Compose/ViewModel）。
 *
 * 维护：
 * - [appContext]：Application 上下文；
 * - [activeWorkspace]：当前会话对应的工作区目录（产物优先落进 `<workspace>/phone_artifacts/`）；
 * - [visionModelConfig]：可选视觉识别模型配置（来自内核生效模型 + 供应商密钥）；
 * - [mcpStatus]：内置 HTTP MCP 服务的端口与端点状态；
 * - [onStopAgentTurn]：用户在设置或悬浮条点「立即停止」时同步中止内核当前回合。
 */
object PhoneRuntime {

    @Volatile
    var appContext: Application? = null
        private set

    val activeWorkspace = MutableStateFlow("")
    val visionModelConfig = MutableStateFlow<VisionModelConfig?>(null)
    val mcpStatus = MutableStateFlow<PhoneMcpStatus>(PhoneMcpStatus.Stopped)

    /** 由 ChatViewModel 注册：触发立即停止时联动取消当前内核轮次。 */
    @Volatile
    var onStopAgentTurn: (() -> Unit)? = null

    fun init(app: Application) {
        appContext = app
        PhoneControlPrefs.init(app)
        PhoneArtifactStore.init(app)
        PhoneTaskController.init(app)
        PhoneAppSkillStore.init(app)
        if (activeWorkspace.value.isBlank()) {
            activeWorkspace.value = EnvKit.inboxSpace(app).absolutePath
        }
    }

    /**
     * 解析当前有效的工作区根目录；若未显式设置则退回 `EnvKit.inboxSpace`。
     */
    fun workspaceRoot(context: Context): File {
        val ws = activeWorkspace.value.trim()
        return if (ws.isNotBlank()) {
            File(ws).apply { mkdirs() }
        } else {
            EnvKit.inboxSpace(context)
        }
    }

    /**
     * 产物输出目录：`<workspace>/phone_artifacts/<subDir>/`。
     *
     * 直接落进当前会话工作区，模型就能通过工作区相对路径在回复里输出
     * `![截图](phone_artifacts/screenshots/...)`、` ```video ` 或 ` ```audio `。
     */
    fun artifactDir(context: Context, subDir: String): File =
        File(File(workspaceRoot(context), "phone_artifacts"), subDir).apply { mkdirs() }

    /**
     * 计算 [file] 相对于当前工作区的相对路径；若不在工作区内则返回绝对路径。
     */
    fun toWorkspaceRelativePath(context: Context, file: File): String {
        val rootCanon = runCatching { workspaceRoot(context).canonicalFile }.getOrNull()
        val fileCanon = runCatching { file.canonicalFile }.getOrNull()
        if (rootCanon != null && fileCanon != null) {
            val prefix = rootCanon.path.trimEnd(File.separatorChar) + File.separator
            if (fileCanon.path.startsWith(prefix)) {
                return fileCanon.path.removePrefix(prefix).replace('\\', '/')
            }
        }
        return file.absolutePath
    }
}
