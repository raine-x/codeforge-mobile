package com.hxy.codeforge.ui.chat

import com.hxy.codeforge.model.ChatMessage
import com.hxy.codeforge.model.EditableUserMessage
import com.hxy.codeforge.model.MsgRole
import com.hxy.codeforge.ui.CLIENT_CAPABILITY_PROMPT
import com.hxy.codeforge.ui.resolveEditBack
import com.hxy.codeforge.ui.truncateMessagesForEdit
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

    @Test
    fun resolveEditBackAndTruncateHandlesOlderMessagesAndDuplicateText() {
        val m1 = ChatMessage(id = "u1", role = MsgRole.User, text = "继续排查")
        val m2 = ChatMessage(id = "a1", role = MsgRole.Assistant, text = "答1")
        val m3 = ChatMessage(id = "u2", role = MsgRole.User, text = "第二问")
        val m4 = ChatMessage(id = "a2", role = MsgRole.Assistant, text = "答2")
        val m5 = ChatMessage(id = "u3", role = MsgRole.User, text = "第三问")
        val m6 = ChatMessage(id = "a3", role = MsgRole.Assistant, text = "答3")
        val m7 = ChatMessage(id = "u4", role = MsgRole.User, text = "继续排查")
        val m8 = ChatMessage(id = "a4", role = MsgRole.Assistant, text = "答4")
        val msgs = listOf(m1, m2, m3, m4, m5, m6, m7, m8)

        // 内核 editables 只发最近 3 条且带注入提示词；编辑最早的第 1 条同名消息必须命中 back=3，而不是误命中最后一条 back=0。
        val anchors = listOf(
            EditableUserMessage(back = 2, text = "第二问\n\n$CLIENT_CAPABILITY_PROMPT"),
            EditableUserMessage(back = 1, text = "第三问\n\n$CLIENT_CAPABILITY_PROMPT"),
            EditableUserMessage(back = 0, text = "继续排查\n\n$CLIENT_CAPABILITY_PROMPT"),
        )
        assertEquals(3, resolveEditBack(msgs, anchors, m1))
        assertEquals(1, resolveEditBack(msgs, anchors, m5))
        assertEquals(0, resolveEditBack(msgs, anchors, m7))

        // 即使消息已被回收仅剩 anchors（带注入标记），也能通过剥离后匹配到正确 back。
        assertEquals(2, resolveEditBack(emptyList(), anchors, m3))

        // 本地立即截断 m3 之后的所有对话并更新其文本。
        val truncated = truncateMessagesForEdit(msgs, m3.id, back = 2, newText = "修改后的第二问")
        assertEquals(3, truncated.size)
        assertEquals(listOf("u1", "a1", "u2"), truncated.map { it.id })
        assertEquals("修改后的第二问", truncated.last().text)
    }
}
