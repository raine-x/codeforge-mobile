package com.hxy.codeforge.core

import android.content.Context
import java.io.File

/** 内核本地覆盖配置（`filesDir/kernel-config/local.yaml`）写入器。 */
object KernelConfig {

    private const val CONFIG_DIR = "kernel-config"
    private const val FILE_NAME = "local.yaml"

    fun configFile(context: Context): File =
        File(File(context.filesDir, CONFIG_DIR), FILE_NAME)

    /** 写入 LLM 连接配置，其中 `display_name` 供内核 `ready` 帧回显。 */
    fun writeLlm(
        context: Context,
        provider: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        displayName: String = model,
    ): File {
        val file = configFile(context)
        file.parentFile?.mkdirs()
        file.writeText(
            """
            # 由 CodeForge 设置页生成，保存时整体覆盖
            llm:
              provider: ${provider.trim()}
              base_url: ${baseUrl.trim()}
              api_key: ${apiKey.trim()}
              model: ${model.trim()}
              display_name: ${displayName.trim()}
            """.trimIndent() + "\n",
        )
        return file
    }
}
