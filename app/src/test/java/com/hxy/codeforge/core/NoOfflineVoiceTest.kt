package com.hxy.codeforge.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 校验源码与资产中不存在离线语音识别（Vosk / `libvosk.so` / `voice-model-zh.zip`）残留，避免增大安装包体积。
 */
class NoOfflineVoiceTest {

    private val mainDir: File = File("src/main")

    @Test
    fun `源码里不再有 Vosk 引用`() {
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
