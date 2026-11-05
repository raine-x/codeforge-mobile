package com.hxy.codeforge.ui

import com.hxy.codeforge.core.KernelApi
import com.hxy.codeforge.model.LibraryModel
import com.hxy.codeforge.ui.chat.buildProviderEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真机反馈的两处修复的回归测试。
 *
 * ① 提示词泄露：内核把上行文本原样存进历史，`history` 帧回放时整段替换消息列表，
 *    注入的能力说明会漏回用户气泡。
 * ② 供应商身份：内核 config 给 provider id，模型库给 id + 显示名，供应商库给显示名。
 *    拿显示名去比 id 永远比不中，于是界面同时出现「anthropic」与「DeepSeek 官方」。
 */
class PromptInjectTest {

    // ------------------------------------------------------------ 提示词剥离

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

    // ------------------------------------------------------------ 供应商身份

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
        // 供应商库与模型库指向同一家 —— 早先会把「名字一次、id 一次」列成两家
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
