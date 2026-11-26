package com.hxy.codeforge.ui.chat

import com.hxy.codeforge.core.KernelApi
import com.hxy.codeforge.model.LibraryModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 当前生效供应商 id 反查测试（按生效模型从模型库检索，不使用 `config.provider` 协议族，见 P-045）。 */
class ProviderResolveTest {

    private val deepseekModel = LibraryModel(
        id = "deepseek-chat",
        name = "DeepSeek Chat",
        provider = "deepseek",
        providerName = "DeepSeek 官方",
    )

    @Test
    fun resolvesProviderFromTheEffectiveModel() {
        val id = resolveActiveProviderId(
            libraryModels = listOf(deepseekModel),
            kernelModelId = "deepseek-chat",
        )
        assertEquals("deepseek", id)
    }

    @Test
    fun emptyLibraryHasNoCurrentProvider() {
        // 关键：不能退回到内核的协议族 "anthropic"
        val id = resolveActiveProviderId(
            libraryModels = emptyList(),
            kernelModelId = "claude-sonnet-4-20250514",
        )
        assertEquals("", id)
    }

    @Test
    fun effectiveModelDeletedFromLibraryHasNoCurrentProvider() {
        // 生效配置还在（对话能用），但条目被删了 —— 供应商无从谈起
        val id = resolveActiveProviderId(
            libraryModels = listOf(deepseekModel),
            kernelModelId = "some-deleted-model",
        )
        assertEquals("", id)
    }

    @Test
    fun pickedModelWinsOverKernelEffectiveModel() {
        val other = LibraryModel(id = "glm-4.7", name = "GLM", provider = "zhipu", providerName = "智谱")
        val id = resolveActiveProviderId(
            libraryModels = listOf(deepseekModel, other),
            kernelModelId = "deepseek-chat",
            pickedModelId = "glm-4.7",
        )
        assertEquals("zhipu", id)
    }

    @Test
    fun modelWithoutProviderHasNoCurrentProvider() {
        // 未归属供应商的模型（provider 为空）
        val orphan = LibraryModel(id = "standalone", name = "Standalone", provider = "")
        val id = resolveActiveProviderId(
            libraryModels = listOf(orphan),
            kernelModelId = "standalone",
        )
        assertEquals("", id)
    }

    @Test
    fun providerEntryKeepsIdAsIdentity() {
        // 身份是 id，显示是名字 —— 这两个不能再各说各的
        val entries = buildProviderEntries(
            libraryModels = listOf(deepseekModel),
            libraryProviders = listOf("DeepSeek 官方"),
            providerDetails = emptyList(),
        )
        assertEquals(1, entries.size)
        assertEquals("deepseek", entries[0].id)
        assertEquals("DeepSeek 官方", entries[0].name)
    }

    // 模型分组

    @Test
    fun modelsAreGroupedByProvider() {
        val zhipu = LibraryModel(id = "glm-4.7", name = "GLM-4.7", provider = "zhipu", providerName = "智谱")
        val groups = buildModelGroups(
            libraryModels = listOf(deepseekModel, zhipu),
            providerDetails = emptyList(),
            filterProviderId = "",
        )
        assertEquals(2, groups.size)
        assertEquals("deepseek", groups[0].providerId)
        assertEquals("DeepSeek 官方", groups[0].providerName)
        assertEquals(1, groups[0].models.size)
        assertEquals("zhipu", groups[1].providerId)
    }

    @Test
    fun selectingProviderFiltersTheModelList() {
        val zhipu = LibraryModel(id = "glm-4.7", name = "GLM-4.7", provider = "zhipu", providerName = "智谱")
        val groups = buildModelGroups(
            libraryModels = listOf(deepseekModel, zhipu),
            providerDetails = emptyList(),
            filterProviderId = "zhipu",
        )
        assertEquals(1, groups.size)
        assertEquals("zhipu", groups[0].providerId)
        assertEquals("glm-4.7", groups[0].models[0].id)
    }

    @Test
    fun providerWithoutKeyIsMarkedMissingKey() {
        // 首装状态：供应商建了、模型也建了，但 Key 留空 → 选它必然失败
        val groups = buildModelGroups(
            libraryModels = listOf(deepseekModel),
            providerDetails = listOf(
                KernelApi.ProviderInfo(
                    id = "deepseek", name = "DeepSeek 官方",
                    baseUrl = "https://api.deepseek.com/v1", protocol = "openai",
                    keySource = "plain", keyName = "", disabled = false,
                    keyPlain = "",
                ),
            ),
            filterProviderId = "",
        )
        assertTrue(groups[0].missingKey)
    }

    @Test
    fun providerWithKeyIsNotMarkedMissingKey() {
        val groups = buildModelGroups(
            libraryModels = listOf(deepseekModel),
            providerDetails = listOf(
                KernelApi.ProviderInfo(
                    id = "deepseek", name = "DeepSeek 官方",
                    baseUrl = "", protocol = "openai",
                    keySource = "plain", keyName = "", disabled = false,
                    keyPlain = "sk-real",
                ),
            ),
            filterProviderId = "",
        )
        assertFalse(groups[0].missingKey)
    }

    @Test
    fun modelsWithoutProviderGoToTheirOwnGroup() {
        val orphan = LibraryModel(id = "standalone", name = "Standalone", provider = "")
        val groups = buildModelGroups(
            libraryModels = listOf(orphan),
            providerDetails = emptyList(),
            filterProviderId = "",
        )
        assertEquals(1, groups.size)
        assertEquals("", groups[0].providerId)
        assertEquals("未归属供应商", groups[0].providerName)
    }
}
