package com.hxy.codeforge.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 内置离线语音（Vosk）已移除 —— 这条测试守住「别把它悄悄加回来」。
 *
 * ## 为什么需要守护
 *
 * Vosk 的代价很容易被低估：
 *  - `assets/voice-model-zh.zip`：**41.9 MB**（中文小模型）
 *  - `libvosk.so`：**10.0 MB**（AAR 带来的原生库）
 *  - 合计约 **52 MB**，占原 APK（65.5 MB）的八成。
 *
 * 用户明确要求砍掉它。砍掉之后如果有人「顺手优化语音」又把 Vosk 拉回来，
 * APK 会无声地胖回 60 MB+ —— 这条断言让回归在**构建期**就暴露，
 * 而不是等用户抱怨「安装包怎么这么大」。
 *
 * 断言方式：扫 `app/src/main` 下的源码与资产 —— 这是**唯一**的真相源，
 * 产物里的痕迹是它的推论。
 */
class NoOfflineVoiceTest {

    /** 源码根目录（Gradle 单测的工作目录是模块目录）。 */
    private val mainDir: File = File("src/main")

    @Test
    fun `源码里不再有 Vosk 引用`() {
        // 只查**代码里的引用**，不查注释里的字样 —— VoiceInput.kt 的类注释
        // 解释了「为什么不用 Vosk」，那是正面文档，提到它是应该的。
        val offenders = findFiles(matching = { it.name.endsWith(".kt") })
            .filter { file ->
                val text = file.readText()
                text.contains("org.vosk") ||
                    text.contains("OfflineVoice") ||
                    text.contains("vosk-android")
            }
            .map { it.path }
        assertTrue(
            "源码里仍有离线语音引用：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `资产里不再有语音模型`() {
        val model = File(mainDir, "assets/voice-model-zh.zip")
        assertFalse(
            "语音模型资产还在（41.9 MB）：${model.absolutePath}",
            model.exists(),
        )
    }

    @Test
    fun `原生库目录里不再有 libvosk`() {
        val lib = File(mainDir, "jniLibs")
        if (!lib.isDirectory) return
        val offenders = lib.walkTopDown()
            .filter { it.isFile && it.name.contains("vosk", ignoreCase = true) }
            .map { it.path }
            .toList()
        assertTrue(
            "原生库里仍有 Vosk 库（10 MB）：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `依赖里不再有 vosk-android`() {
        val gradle = File("build.gradle.kts")
        assertTrue("找不到 build.gradle.kts", gradle.isFile)
        val text = gradle.readText()
        assertFalse(
            "build.gradle.kts 里仍有 vosk-android 依赖",
            text.contains("vosk-android"),
        )
    }

    /** 递归收集匹配的文件。 */
    private fun findFiles(matching: (File) -> Boolean): List<File> {
        if (!mainDir.isDirectory) return emptyList()
        return mainDir.walkTopDown()
            .filter { it.isFile && matching(it) }
            .toList()
    }
}
