package com.hxy.codeforge.ui.chat

import com.hxy.codeforge.model.ChatMessage
import com.hxy.codeforge.model.MsgRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegenerateRollbackTest {

    @Test
    fun testMessagesRollbackToLastUserQuestion() {
        val messages = listOf(
            ChatMessage(id = "1", role = MsgRole.User, text = "你好"),
            ChatMessage(id = "2", role = MsgRole.Assistant, text = "你好！很高兴为你服务。"),
            ChatMessage(id = "3", role = MsgRole.User, text = "帮我写个排序算法"),
            ChatMessage(id = "4", role = MsgRole.Assistant, text = "代码生成中断...", thinking = "我在思考..."),
        )

        val lastUserIdx = messages.indexOfLast { it.role == MsgRole.User }
        assertEquals(2, lastUserIdx)

        val truncated = messages.take(lastUserIdx + 1)
        assertEquals(3, truncated.size)
        assertEquals("3", truncated.last().id)
        assertEquals(MsgRole.User, truncated.last().role)
        assertEquals("帮我写个排序算法", truncated.last().text)
    }

    @Test
    fun testRollbackWhenNoUserMessage() {
        val messages = listOf(
            ChatMessage(id = "1", role = MsgRole.Assistant, text = "欢迎使用"),
        )
        val lastUserIdx = messages.indexOfLast { it.role == MsgRole.User }
        assertEquals(-1, lastUserIdx)
        val truncated = if (lastUserIdx >= 0) messages.take(lastUserIdx + 1) else messages
        assertEquals(1, truncated.size)
    }
}
