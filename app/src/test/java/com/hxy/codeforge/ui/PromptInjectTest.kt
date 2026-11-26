package com.hxy.codeforge.ui

import com.hxy.codeforge.core.KernelApi
import com.hxy.codeforge.model.LibraryModel
import com.hxy.codeforge.ui.chat.buildProviderEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 注入标记剥离与供应商身份去重回归测试。 */
class PromptInjectTest {

    @Test
    fun plainUserTextIsUntouched() {
        val t = "试下能不能在当前页面创建内嵌 HTML"
        assertEquals(t, stripInjectedText(t))
    }

    @Test
    fun stripsCapabilityPrompt() {
        val wire = "帮我出三道题\n\n【CodeForge 客户端渲染能力】\n本客户端支持……"
        assertEquals("帮我出三道题", stripInjectedText(wire))
    }

    @Test
    fun stripsElementBlock() {
        val wire = "元素1 这个按钮是干什么的\n\n" +
            "（下面是从应用内置浏览器里挑出的页面元素，编号即对话中的引用名）\n" +
            "【CodeForge 页面元素】\n元素1 | 标签=button\n……"
        assertEquals("元素1 这个按钮是干什么的", stripInjectedText(wire))
    }

    @Test
    fun stripsElementBlockBeforeCapabilityPrompt() {
        // 上行文本的真实结构：用户话 → 元素块 → 能力说明
        val wire = "元素1 解释一下\n\n" +
            "（下面是从应用内置浏览器里挑出的页面元素，编号即对话中的引用名）\n" +
            "【CodeForge 页面元素】\n元素1 | 标签=button\n……\n\n" +
            "【CodeForge 客户端渲染能力】\n本客户端支持……"
        assertEquals("元素1 解释一下", stripInjectedText(wire))
    }

    @Test
    fun stripsCapabilityPromptWhenNoElementBlock() {
        val wire = "出题\n\n【CodeForge 客户端渲染能力】\n本客户端支持……\n"
        assertEquals("出题", stripInjectedText(wire))
    }

    @Test
    fun appendNoteBeforeInjectedMarkersKeepsNoteVisibleOnReplay() {
        val note = "[以下大文件超过 20MiB 内核直传限制，已自动上传至工作区：\n- imports/big.mp4 (25.0 MB)]"
        val wireWithText = "请剪辑前十秒\n\n【CodeForge 客户端渲染能力】\n本客户端支持……"
        val mergedWithText = appendNoteBeforeInjectedMarkers(wireWithText, note)
        assertTrue(mergedWithText.indexOf(note) < mergedWithText.indexOf(INJECT_CAPABILITY_MARKER))
        assertEquals("请剪辑前十秒\n\n$note", stripInjectedText(mergedWithText))

        val wireEmptyUser = "\n\n【CodeForge 客户端渲染能力】\n本客户端支持……"
        val mergedEmptyUser = appendNoteBeforeInjectedMarkers(wireEmptyUser, note)
        assertEquals(note, stripInjectedText(mergedEmptyUser))
        assertTrue(mergedEmptyUser.endsWith("本客户端支持……"))
    }

    // 供应商身份

    @Test
    fun providerIdentityIsIdAndDisplayIsName() {
        val entries = buildProviderEntries(
            libraryModels = listOf(
                LibraryModel(id = "deepseek-chat", name = "DeepSeek Chat", provider = "deepseek", providerName = "DeepSeek 官方"),
            ),
            libraryProviders = listOf("DeepSeek 官方"),
            providerDetails = listOf(
                KernelApi.ProviderInfo(
                    id = "deepseek", name = "DeepSeek 官方",
                    baseUrl = "https://api.deepseek.com/v1", protocol = "openai",
                    keySource = "plain", keyName = "", disabled = false,
                    keyPlain = "sk-test",
                ),
            ),
        )
        assertEquals(1, entries.size)
        // 身份是 id，显示是名字 —— 这两个不能再各说各的
        assertEquals("deepseek", entries[0].id)
        assertEquals("DeepSeek 官方", entries[0].name)
    }

    @Test
    fun providerWithoutDetailsFallsBackToModelLibrary() {
        val entries = buildProviderEntries(
            libraryModels = listOf(
                LibraryModel(id = "m1", name = "M1", provider = "anthropic", providerName = "Anthropic 官方"),
            ),
            libraryProviders = emptyList(),
            providerDetails = emptyList(),
        )
        assertEquals(1, entries.size)
        assertEquals("anthropic", entries[0].id)
        assertEquals("Anthropic 官方", entries[0].name)
    }

    @Test
    fun providerNameOnlyListUsesNameAsId() {
        val entries = buildProviderEntries(
            libraryModels = emptyList(),
            libraryProviders = listOf("自定义中转"),
            providerDetails = emptyList(),
        )
        assertEquals(1, entries.size)
        assertEquals("自定义中转", entries[0].id)
        assertEquals("自定义中转", entries[0].name)
    }

    @Test
    fun sameProviderFromTwoSourcesIsNotListedTwice() {
        // 供应商库与模型库指向同一供应商 id 时不应重复列出
        val entries = buildProviderEntries(
            libraryModels = listOf(
                LibraryModel(id = "m1", name = "M1", provider = "deepseek", providerName = "DeepSeek 官方"),
            ),
            libraryProviders = listOf("DeepSeek 官方"),
            providerDetails = listOf(
                KernelApi.ProviderInfo(
                    id = "deepseek", name = "DeepSeek 官方",
                    baseUrl = "", protocol = "openai",
                    keySource = "plain", keyName = "", disabled = false,
                ),
            ),
        )
        assertEquals(1, entries.size)
        assertTrue(entries[0].id == "deepseek")
    }
}
