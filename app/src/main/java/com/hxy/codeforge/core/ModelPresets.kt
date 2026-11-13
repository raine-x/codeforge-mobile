package com.hxy.codeforge.core

/**
 * 常见国内外开闭源模型的上下文规格内置表与默认兜底。
 *
 * 解决「新增/探测模型时不填 ctx_in 就传 0，导致内核退回 120K 默认预算且界面提示未声明」的问题：
 * - 命中内置规则时自动回填已知输入/输出上限（以及图片/视频多模态标记）；
 * - 输出上限未公开的条目设为 `0`（不强行发 `ctx_out`，只补足输入上限与能力）；
 * - 未命中任何规则的未知模型兜底给至少 [MIN_DEFAULT_CTX_IN]（128K），避免以 0 入库。
 */
data class ModelSpecPreset(
    val ctxIn: Int,
    val ctxOut: Int,
    val vision: Boolean? = null,
    val video: Boolean? = null,
)

object ModelPresets {
    const val MIN_DEFAULT_CTX_IN = 128_000
    const val DEFAULT_CTX_OUT = 16_384

    private data class Rule(
        val keywords: List<String>,
        val spec: ModelSpecPreset,
    )

    // 重要：按「长串/具体后缀优先，短前缀/家族兜底在后」排序，
    // 例如 glm-5.3-flash 必须在 glm-5.3 与 glm-5 之前，避免短串抢先命中。
    private val rules: List<Rule> = listOf(
        // -------- OpenAI --------
        Rule(
            listOf("gpt-6-astra", "gpt-6.1-sol", "gpt-6.1-terra", "gpt-6-luna"),
            ModelSpecPreset(1_050_000, 128_000, vision = true, video = false),
        ),
        Rule(listOf("gpt-6-rosalind"), ModelSpecPreset(MIN_DEFAULT_CTX_IN, 0, vision = false, video = false)),
        Rule(listOf("gpt-5"), ModelSpecPreset(400_000, 128_000, vision = true, video = false)),
        Rule(listOf("gpt-4.1"), ModelSpecPreset(1_000_000, 32_768, vision = true, video = false)),
        Rule(listOf("o3", "o4-mini", "o1"), ModelSpecPreset(200_000, 100_000, vision = true, video = false)),
        Rule(listOf("gpt-4o"), ModelSpecPreset(128_000, 16_384, vision = true, video = false)),
        Rule(listOf("gpt-4-turbo"), ModelSpecPreset(128_000, 4_096, vision = true, video = false)),
        Rule(listOf("gpt-oss"), ModelSpecPreset(128_000, 32_768, vision = false, video = false)),

        // -------- Anthropic / Claude --------
        Rule(
            listOf(
                "claude-fable-5-2", "claude-fable-5.2",
                "claude-mythos-5-2", "claude-mythos-5.2",
                "claude-opus-5-5", "claude-opus-5.5",
                "claude-sonnet-5-5", "claude-sonnet-5.5",
                "claude-haiku-5-5", "claude-haiku-5.5",
                "claude-fable-5-1", "claude-fable-5.1",
                "claude-mythos-5-1", "claude-mythos-5.1",
                "claude-fable-5", "claude-mythos-5",
                "claude-opus-5", "claude-sonnet-5",
                "claude-opus-4-8", "claude-opus-4.8",
                "claude-opus-4-7", "claude-opus-4.7",
                "claude-opus-4-6", "claude-opus-4.6",
                "claude-sonnet-4-6", "claude-sonnet-4.6",
            ),
            ModelSpecPreset(1_000_000, 128_000, vision = true, video = false),
        ),
        Rule(
            listOf(
                "claude-opus-4-5", "claude-opus-4.5",
                "claude-sonnet-4-5", "claude-sonnet-4.5",
                "claude-haiku-4-5", "claude-haiku-4.5",
            ),
            ModelSpecPreset(200_000, 64_000, vision = true, video = false),
        ),
        Rule(
            listOf("claude-sonnet-4", "claude-opus-4", "claude-haiku-4", "claude-4"),
            ModelSpecPreset(200_000, 64_000, vision = true, video = false),
        ),
        Rule(listOf("claude-3-7", "claude-3.7"), ModelSpecPreset(200_000, 64_000, vision = true, video = false)),
        Rule(listOf("claude-3-5", "claude-3.5"), ModelSpecPreset(200_000, 8_192, vision = true, video = false)),
        Rule(listOf("claude"), ModelSpecPreset(200_000, 16_384, vision = true, video = false)),

        // -------- Google / Gemini & Gemma --------
        Rule(
            listOf("gemini-4-carbon", "gemini-4-argon", "gemini-4-hydrogen", "gemini-4"),
            ModelSpecPreset(1_000_000, 65_536, vision = true, video = true),
        ),
        Rule(
            listOf("gemini-3.8-live-extended-thinking", "gemini-3.8-live"),
            ModelSpecPreset(MIN_DEFAULT_CTX_IN, 0, vision = false, video = false),
        ),
        Rule(
            listOf(
                "gemini-3.8-flash", "gemini-3.6-flash",
                "gemini-3.5-flash-lite", "gemini-3.1-pro-preview",
                "gemini-3.1-flash-lite", "gemini-3-flash-preview",
                "gemini-3.8", "gemini-3.6", "gemini-3.5", "gemini-3.1", "gemini-3-",
            ),
            ModelSpecPreset(MIN_DEFAULT_CTX_IN, 0, vision = true, video = true),
        ),
        Rule(
            listOf("gemini-2.5", "gemini-2.0", "gemini-1.5", "gemini"),
            ModelSpecPreset(1_048_576, 65_536, vision = true, video = true),
        ),
        Rule(listOf("gemma-3"), ModelSpecPreset(128_000, 16_384, vision = true, video = false)),

        // -------- DeepSeek --------
        Rule(listOf("deepseek-flash"), ModelSpecPreset(1_000_000, 384_000, vision = true, video = false)),
        Rule(listOf("deepseek-v4-pro", "deepseek-v4"), ModelSpecPreset(1_000_000, 384_000, vision = false, video = false)),
        Rule(listOf("deepseek-reasoner", "deepseek-r1"), ModelSpecPreset(128_000, 64_000, vision = false, video = false)),
        Rule(listOf("deepseek-chat", "deepseek-v3"), ModelSpecPreset(128_000, 16_384, vision = false, video = false)),
        Rule(listOf("deepseek-coder"), ModelSpecPreset(128_000, 16_384, vision = false, video = false)),
        Rule(listOf("deepseek"), ModelSpecPreset(128_000, 16_384)),

        // -------- Moonshot / Kimi --------
        Rule(
            listOf("kimi-k3.1-pro-highspeed", "kimi-k3.1-pro", "kimi-k3"),
            ModelSpecPreset(1_048_576, 1_048_576, vision = true, video = true),
        ),
        Rule(
            listOf("kimi-k2.7-code-highspeed", "kimi-k2.7-code", "kimi-k2.6"),
            ModelSpecPreset(262_144, 0, vision = false, video = false),
        ),
        Rule(listOf("kimi-k2", "kimi"), ModelSpecPreset(128_000, 32_768)),
        Rule(listOf("moonshot-v1-128k", "moonshot"), ModelSpecPreset(128_000, 16_384)),

        // -------- 智谱 / GLM --------
        Rule(
            listOf("glm-5.3-flashx-legal", "glm-5.3-flashx", "glm-5.3-flash"),
            ModelSpecPreset(1_000_000, 128_000, vision = true, video = true),
        ),
        Rule(listOf("glm-5.3", "glm-5.2"), ModelSpecPreset(1_000_000, 128_000, vision = false, video = false)),
        Rule(listOf("glm-5.1", "glm-5-turbo", "glm-5"), ModelSpecPreset(200_000, 128_000, vision = false, video = false)),
        Rule(
            listOf("glm-4.7-flashx", "glm-4.7-flash", "glm-4.7", "glm-4.6"),
            ModelSpecPreset(200_000, 128_000, vision = false, video = false),
        ),
        Rule(
            listOf("glm-4.5-airx", "glm-4.5-air", "glm-4.5-flash"),
            ModelSpecPreset(128_000, 96_000, vision = false, video = false),
        ),
        Rule(listOf("glm-4-long"), ModelSpecPreset(1_000_000, 4_000, vision = false, video = false)),
        Rule(
            listOf("glm-4-flashx-250414", "glm-4-flash-250414", "glm-4-flashx", "glm-4-flash"),
            ModelSpecPreset(128_000, 16_000, vision = false, video = false),
        ),
        Rule(listOf("glm-4v"), ModelSpecPreset(128_000, 16_000, vision = true, video = true)),
        Rule(listOf("glm-4", "glm-z1", "glm"), ModelSpecPreset(128_000, 16_000, vision = false, video = false)),

        // -------- MiniMax --------
        Rule(listOf("minimax-m3.1-flash-preview"), ModelSpecPreset(1_000_000, 0, vision = true, video = true)),
        Rule(listOf("minimax-m3.1-flash", "minimax-m3"), ModelSpecPreset(1_000_000, 512_000, vision = true, video = true)),
        Rule(listOf("minimax-m2.7"), ModelSpecPreset(200_000, 128_000, vision = false, video = false)),
        Rule(
            listOf("minimax-m2.5", "minimax-m2.1", "minimax-m2"),
            ModelSpecPreset(MIN_DEFAULT_CTX_IN, 0, vision = false, video = false),
        ),
        Rule(listOf("minimax-m1"), ModelSpecPreset(1_000_000, 64_000, vision = false, video = false)),
        Rule(listOf("minimax", "abab6.5"), ModelSpecPreset(200_000, 32_768)),

        // -------- Meta / Muse & Llama --------
        Rule(listOf("muse-ember-1.0", "muse-ember"), ModelSpecPreset(1_048_576, 524_288, vision = true, video = true)),
        Rule(
            listOf(
                "muse-wonder-1.0",
                "muse-spark-1.3-contributor",
                "muse-spark-1.2-contributor",
                "muse-spark-1.3",
                "muse-spark-1.2",
                "muse-spark-1.1",
                "muse",
            ),
            ModelSpecPreset(1_048_576, 0, vision = true, video = true),
        ),
        Rule(listOf("llama-4-scout"), ModelSpecPreset(1_000_000, 32_768, vision = true, video = false)),
        Rule(listOf("llama-4", "llama-3.3", "llama-3.1", "llama"), ModelSpecPreset(128_000, 16_384)),

        // -------- xAI Grok --------
        Rule(listOf("grok-4"), ModelSpecPreset(256_000, 64_000, vision = true, video = false)),
        Rule(listOf("grok-3", "grok"), ModelSpecPreset(131_072, 32_768, vision = true, video = false)),

        // -------- 阿里 Qwen / 通义千问 --------
        Rule(listOf("qwen-long"), ModelSpecPreset(1_000_000, 32_768, vision = false, video = false)),
        Rule(listOf("qwen3-coder", "qwen3-max", "qwen3"), ModelSpecPreset(262_144, 32_768)),
        Rule(listOf("qwen-vl", "qvq", "qwen2.5-vl"), ModelSpecPreset(128_000, 16_384, vision = true, video = true)),
        Rule(listOf("qwen-max", "qwen-plus", "qwen-turbo", "qwen2.5", "qwq", "qwen"), ModelSpecPreset(131_072, 16_384)),

        // -------- 百度 / 腾讯 / 字节 / 阶跃 / 零一 / 小米 / Mistral --------
        Rule(listOf("doubao-seed-1.6", "doubao-seed-1-6", "doubao-pro-256k"), ModelSpecPreset(256_000, 32_768, vision = true)),
        Rule(listOf("doubao-1.5", "doubao-1-5", "doubao"), ModelSpecPreset(128_000, 16_384)),
        Rule(listOf("ernie-4.5", "ernie-x1", "ernie"), ModelSpecPreset(128_000, 16_384)),
        Rule(listOf("hunyuan"), ModelSpecPreset(128_000, 16_384)),
        Rule(listOf("step-2", "step-3", "step"), ModelSpecPreset(128_000, 16_384)),
        Rule(listOf("yi-"), ModelSpecPreset(128_000, 16_384)),
        Rule(listOf("mimo"), ModelSpecPreset(128_000, 32_768)),
        Rule(listOf("codestral", "devstral", "mistral", "mixtral", "magistral", "pixtral"), ModelSpecPreset(128_000, 32_768)),
    )

    /**
     * 规范化模型 ID（剥离网关前缀如 `deepseek/`、`Pro/zai-org/` 以及 `:free` 等后缀）后匹配内置规格；
     * 未命中返回 null。
     */
    fun find(modelId: String): ModelSpecPreset? {
        val raw = modelId.trim().lowercase()
        if (raw.isEmpty()) return null
        val tail = raw.substringAfterLast('/').substringBefore(':')
        return rules.firstOrNull { rule ->
            rule.keywords.any { kw -> tail.contains(kw) || raw.contains(kw) }
        }?.spec
    }

    /** 命中内置模型返回对应规格（保证输入 ≥ [MIN_DEFAULT_CTX_IN]），未知模型兜底给 128K。 */
    fun resolveOrDefault(modelId: String): ModelSpecPreset {
        val hit = find(modelId)
        return if (hit != null) {
            hit.copy(ctxIn = hit.ctxIn.coerceAtLeast(MIN_DEFAULT_CTX_IN))
        } else {
            ModelSpecPreset(ctxIn = MIN_DEFAULT_CTX_IN, ctxOut = DEFAULT_CTX_OUT)
        }
    }
}
