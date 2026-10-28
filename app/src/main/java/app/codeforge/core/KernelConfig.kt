package app.codeforge.core

import android.content.Context
import java.io.File

/**
 * 内核配置文件（filesDir/kernel-config/local.yaml）的壳侧写入器。
 *
 * 内核的配置优先级：default.yaml → local.yaml → state.yaml → 环境变量。
 * local.yaml 定位是「用户手改的本地覆盖」—— 在手机上没有文本编辑器，
 * 设置页就是这个「用户编辑器」：保存时整文件重写（只含 llm 段），
 * 随后重启内核进程让它按新配置拉起。
 *
 * 整文件重写会丢掉用户通过 adb 手改的其他段（plugins 等）—— 测试期
 * 接受，接 /api/config 热切换（REST）后此文件退居首启种子。
 */
object KernelConfig {

    private const val CONFIG_DIR = "kernel-config"
    private const val FILE_NAME = "local.yaml"

    fun configFile(context: Context): File =
        File(File(context.filesDir, CONFIG_DIR), FILE_NAME)

    /**
     * 写入 LLM 连接配置。display_name 给 ready 帧回显（输入卡胶囊）。
     * 返回写入路径（记录/提示用）。
     */
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
        // 手拼而非引 YAML 库：只有一段扁平键值，模板字符串最直白
        file.writeText(
            """
            # 由 CodeForge Android 设置页生成 —— 整文件以本页为准，手改内容会被覆盖
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
