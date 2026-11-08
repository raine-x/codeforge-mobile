package com.hxy.codeforge.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MediaJumpTagTest {

    @Test
    fun parseStandardVideoTag() {
        val res = parseMediaJumpTag("[ ▶视频 https://example.com/demo.mp4 ]")
        assertNotNull(res)
        assertEquals("视频", res?.first)
        assertEquals("https://example.com/demo.mp4", res?.second)
    }

    @Test
    fun parseStandardAudioTag() {
        val res = parseMediaJumpTag("[ ▶音频 https://example.com/demo.mp3 ]")
        assertNotNull(res)
        assertEquals("音频", res?.first)
        assertEquals("https://example.com/demo.mp3", res?.second)
    }

    @Test
    fun parseTagWithoutUrl() {
        val res1 = parseMediaJumpTag("[ ▶视频 ]")
        assertNotNull(res1)
        assertEquals("视频", res1?.first)
        assertEquals("", res1?.second)

        val res2 = parseMediaJumpTag("[ ▶音频 ]")
        assertNotNull(res2)
        assertEquals("音频", res2?.first)
        assertEquals("", res2?.second)
    }

    @Test
    fun parseTolerantFormats() {
        // 冒号格式
        val res1 = parseMediaJumpTag("[▶视频: http://cdn.test/v.webm]")
        assertNotNull(res1)
        assertEquals("视频", res1?.first)
        assertEquals("http://cdn.test/v.webm", res1?.second)

        // 括号嵌套格式
        val res2 = parseMediaJumpTag("[ ▶音频 (https://cdn.test/a.wav) ]")
        assertNotNull(res2)
        assertEquals("音频", res2?.first)
        assertEquals("https://cdn.test/a.wav", res2?.second)

        // 全角中文方括号
        val res3 = parseMediaJumpTag("【 ▶视频 attachments/test.mp4 】")
        assertNotNull(res3)
        assertEquals("视频", res3?.first)
        assertEquals("attachments/test.mp4", res3?.second)
    }

    @Test
    fun rejectNonMediaTags() {
        assertNull(parseMediaJumpTag("普通消息"))
        assertNull(parseMediaJumpTag("[1] 参考文献"))
        assertNull(parseMediaJumpTag("▶播放"))
        assertNull(parseMediaJumpTag("[ ▶图片 https://img.png ]"))
    }

    /**
     * Markdown 链接形态必须**不被**认成标签。
     *
     * 模型很爱写成 `[ ▶视频 描述文字 ](https://.../a.mp4)`。没有末尾的 `(?!\()` 断言时，
     * 正则会吃掉「描述文字」当地址，方括号后面真正的链接反被丢弃 ——
     * 渲染出一张点开提示「未指定跳转链接」的卡片。
     */
    @Test
    fun rejectMarkdownLinkForm() {
        assertNull(parseMediaJumpTag("[ ▶视频 描述文字 ](https://example.com/a.mp4)"))
        assertNull(parseMediaJumpTag("[ ▶音频 描述 ](https://example.com/a.mp3)"))
    }

    /** 标签后面紧跟括号（带空格）仍应正常识别 —— 断言只挡紧贴的 `(`。 */
    @Test
    fun tagFollowedByParenthesizedTextStillParses() {
        val res = parseMediaJumpTag("[ ▶视频 https://example.com/a.mp4 ]（备选）")
        assertNotNull(res)
        assertEquals("视频", res?.first)
        assertEquals("https://example.com/a.mp4", res?.second)
    }
}
