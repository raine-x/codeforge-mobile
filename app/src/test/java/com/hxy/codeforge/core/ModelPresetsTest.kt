package com.hxy.codeforge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPresetsTest {

    @Test
    fun `未知或空白模型走至少 128K 默认兜底`() {
        assertNull(ModelPresets.find(""))
        assertNull(ModelPresets.find("   "))
        assertNull(ModelPresets.find("my-private-custom-model"))

        val fallback = ModelPresets.resolveOrDefault("my-private-custom-model")
        assertTrue(fallback.ctxIn >= ModelPresets.MIN_DEFAULT_CTX_IN)
        assertEquals(128_000, fallback.ctxIn)
        assertTrue(fallback.ctxOut > 0)
    }

    @Test
    fun `剥离网关前缀与标签后缀后命中预设`() {
        val ds = ModelPresets.find("Pro/deepseek-ai/DeepSeek-V4-Pro:thinking")
        assertNotNull(ds)
        assertEquals(1_000_000, ds!!.ctxIn)
        assertEquals(384_000, ds.ctxOut)
        assertEquals(false, ds.vision)
        assertEquals(false, ds.video)

        val gemini = ModelPresets.find("google/gemini-4-carbon:free")
        assertNotNull(gemini)
        assertEquals(1_000_000, gemini!!.ctxIn)
        assertEquals(65_536, gemini.ctxOut)
        assertEquals(true, gemini.vision)
        assertEquals(true, gemini.video)
    }

    @Test
    fun `精准匹配用户提供的各厂商模型上下文与多模态标记`() {
        data class Case(
            val id: String,
            val ctxIn: Int,
            val ctxOut: Int,
            val vision: Boolean? = null,
            val video: Boolean? = null,
        )

        val cases = listOf(
            // OpenAI
            Case("gpt-6-astra", 1_050_000, 128_000, vision = true, video = false),
            Case("gpt-6.1-sol", 1_050_000, 128_000, vision = true, video = false),
            Case("gpt-6.1-terra", 1_050_000, 128_000, vision = true, video = false),
            Case("gpt-6-luna", 1_050_000, 128_000, vision = true, video = false),
            Case("gpt-6-rosalind", 128_000, 0, vision = false, video = false),

            // Anthropic / Claude
            Case("claude-fable-5-2", 1_000_000, 128_000, vision = true, video = false),
            Case("claude-mythos-5-2", 1_000_000, 128_000, vision = true, video = false),
            Case("claude-opus-5-5", 1_000_000, 128_000, vision = true, video = false),
            Case("claude-opus-4-6", 1_000_000, 128_000, vision = true, video = false),
            Case("claude-sonnet-4-6", 1_000_000, 128_000, vision = true, video = false),
            Case("claude-opus-4-5-20251101", 200_000, 64_000, vision = true, video = false),
            Case("claude-sonnet-4-5-20250929", 200_000, 64_000, vision = true, video = false),
            Case("claude-haiku-4-5-20251001", 200_000, 64_000, vision = true, video = false),

            // Google / Gemini
            Case("gemini-4-argon", 1_000_000, 65_536, vision = true, video = true),
            Case("gemini-4-hydrogen", 1_000_000, 65_536, vision = true, video = true),
            Case("gemini-3.8-flash", 128_000, 0, vision = true, video = true),
            Case("gemini-3.1-pro-preview", 128_000, 0, vision = true, video = true),
            Case("gemini-3.8-live-extended-thinking", 128_000, 0, vision = false, video = false),

            // DeepSeek
            Case("deepseek-flash", 1_000_000, 384_000, vision = true, video = false),
            Case("deepseek-v4-pro", 1_000_000, 384_000, vision = false, video = false),

            // Moonshot / Kimi
            Case("kimi-k3.1-pro", 1_048_576, 1_048_576, vision = true, video = true),
            Case("kimi-k3.1-pro-highspeed", 1_048_576, 1_048_576, vision = true, video = true),
            Case("kimi-k3", 1_048_576, 1_048_576, vision = true, video = true),
            Case("kimi-k2.7-code", 262_144, 0, vision = false, video = false),
            Case("kimi-k2.6", 262_144, 0, vision = false, video = false),

            // 智谱 / GLM（验证长串规则优先）
            Case("glm-5.3", 1_000_000, 128_000, vision = false, video = false),
            Case("glm-5.3-flash", 1_000_000, 128_000, vision = true, video = true),
            Case("glm-5.3-flashx-legal", 1_000_000, 128_000, vision = true, video = true),
            Case("glm-5.2", 1_000_000, 128_000, vision = false, video = false),
            Case("glm-5.1", 200_000, 128_000, vision = false, video = false),
            Case("glm-5", 200_000, 128_000, vision = false, video = false),
            Case("glm-4.7-flash", 200_000, 128_000, vision = false, video = false),
            Case("glm-4.6", 200_000, 128_000, vision = false, video = false),
            Case("glm-4.5-air", 128_000, 96_000, vision = false, video = false),
            Case("glm-4-long", 1_000_000, 4_000, vision = false, video = false),
            Case("glm-4-flash-250414", 128_000, 16_000, vision = false, video = false),

            // MiniMax
            Case("minimax-m3", 1_000_000, 512_000, vision = true, video = true),
            Case("minimax-m3.1-flash", 1_000_000, 512_000, vision = true, video = true),
            Case("MiniMax-M3.1-Flash-Preview", 1_000_000, 0, vision = true, video = true),
            Case("minimax-m2.7", 200_000, 128_000, vision = false, video = false),
            Case("MiniMax-M2.5", 128_000, 0, vision = false, video = false),

            // Meta / Muse
            Case("muse-wonder-1.0", 1_048_576, 0, vision = true, video = true),
            Case("muse-spark-1.3-contributor", 1_048_576, 0, vision = true, video = true),
            Case("muse-ember-1.0", 1_048_576, 524_288, vision = true, video = true),
        )

        for (c in cases) {
            val spec = ModelPresets.resolveOrDefault(c.id)
            assertEquals("ctxIn mismatch for ${c.id}", c.ctxIn, spec.ctxIn)
            assertEquals("ctxOut mismatch for ${c.id}", c.ctxOut, spec.ctxOut)
            assertEquals("vision mismatch for ${c.id}", c.vision, spec.vision)
            assertEquals("video mismatch for ${c.id}", c.video, spec.video)
            assertTrue("ctxIn < 128K for ${c.id}", spec.ctxIn >= ModelPresets.MIN_DEFAULT_CTX_IN)
        }
    }
}
