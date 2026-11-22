package com.hxy.codeforge.ui

import com.hxy.codeforge.core.CoreEvent
import com.hxy.codeforge.model.ChatMessage
import com.hxy.codeforge.model.MessageSegment
import com.hxy.codeforge.model.MsgRole
import com.hxy.codeforge.model.SessionItem
import com.hxy.codeforge.model.ToolCall
import com.hxy.codeforge.model.ToolState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 事件折叠的纯 JVM 回归测试（`ChatStateFold.kt`）。
 *
 * 覆盖的都是历史上出过问题的点：流式拼接段序、工具卡挂错轮次、ToolEnd 只改一份拷贝、
 * 空闲/失败收尾不清运行中工具、会话切换残留旧状态、会话列表吞掉新会话、后台会话污染当前界面。
 */
class ChatEventFoldTest {

    private var seq = 0
    private val newId: () -> String = { "m-${++seq}" }

    private fun st(vararg msgs: ChatMessage, cur: String = "s1") =
        ChatUiState(currentSessionId = cur, messages = msgs.toList())

    private fun tool(id: String, state: ToolState = ToolState.Running) =
        ToolCall(id = id, name = "bash", input = "ls", state = state)

    // ---------- 流式拼接 ----------

    @Test
    fun `正文增量会并入同一条流式消息且段合并`() {
        var s = ChatUiState(currentSessionId = "s1")
        s = s.foldAssistantText("你", newId, 1L)
        s = s.foldAssistantText("好", newId, 2L)
        assertEquals(1, s.messages.size)
        val m = s.messages.single()
        assertEquals("你好", m.text)
        assertTrue(m.streaming)
        assertEquals(listOf<MessageSegment>(MessageSegment.Text("你好")), m.segments)
    }

    @Test
    fun `思考工具正文保持真实发生顺序`() {
        var s = ChatUiState(currentSessionId = "s1")
        s = s.foldThinking("想", newId, 1L)
        s = s.foldToolStart(tool("t1"), newId, 2L)
        s = s.foldAssistantText("答", newId, 3L)
        val segs = s.messages.single().segments
        assertEquals(3, segs.size)
        assertTrue(segs[0] is MessageSegment.Thinking)
        assertTrue(segs[1] is MessageSegment.Tool)
        assertTrue(segs[2] is MessageSegment.Text)
    }

    @Test
    fun `上一条消息已结束时正文会新开一条`() {
        val done = ChatMessage(id = "a", role = MsgRole.Assistant, text = "旧", streaming = false)
        val s = st(done).foldAssistantText("新", newId, 1L)
        assertEquals(2, s.messages.size)
        assertEquals("新", s.messages.last().text)
    }

    @Test
    fun `思考增量追加到同一思考段`() {
        var s = ChatUiState(currentSessionId = "s1")
        s = s.foldThinking("a", newId, 1L).foldThinking("b", newId, 2L)
        val m = s.messages.single()
        assertEquals("ab", m.thinking)
        assertEquals(listOf<MessageSegment>(MessageSegment.Thinking("ab")), m.segments)
    }

    // ---------- 工具卡 ----------

    @Test
    fun `新工具卡不会挂到上一轮已结束的助手消息`() {
        val oldAssistant = ChatMessage(id = "a", role = MsgRole.Assistant, text = "上一轮")
        val user = ChatMessage(id = "u", role = MsgRole.User, text = "再来")
        val s = st(oldAssistant, user).foldToolStart(tool("t1"), newId, 1L)
        assertTrue(s.messages.first().tools.isEmpty())
        assertEquals(3, s.messages.size)
        assertEquals("t1", s.messages.last().tools.single().id)
    }

    @Test
    fun `同 id 的工具开始事件按 id 更新而不是重复追加`() {
        var s = ChatUiState(currentSessionId = "s1")
        s = s.foldToolStart(tool("t1"), newId, 1L)
        s = s.foldToolStart(tool("t1").copy(input = "pwd"), newId, 2L)
        val m = s.messages.single()
        assertEquals(1, m.tools.size)
        assertEquals("pwd", m.tools.single().input)
        assertEquals(1, m.segments.size)
    }

    @Test
    fun `工具结束同时改 tools 与 segments 两份拷贝`() {
        var s = ChatUiState(currentSessionId = "s1")
        s = s.foldToolStart(tool("t1"), newId, 1L)
        s = s.foldToolEnd(CoreEvent.ToolEnd("s1", "t1", "ok", failed = false, diffAdded = 3))
        val m = s.messages.single()
        assertEquals(ToolState.Ok, m.tools.single().state)
        assertEquals(3, m.tools.single().diffAdded)
        val seg = m.segments.single() as MessageSegment.Tool
        assertEquals(ToolState.Ok, seg.call.state)
        assertEquals("ok", seg.call.output)
    }

    @Test
    fun `工具失败标记为 Failed`() {
        var s = ChatUiState(currentSessionId = "s1")
        s = s.foldToolStart(tool("t1"), newId, 1L)
        s = s.foldToolEnd(CoreEvent.ToolEnd("s1", "t1", "boom", failed = true))
        assertEquals(ToolState.Failed, s.messages.single().tools.single().state)
    }

    @Test
    fun `找不到对应工具的结束事件不改变状态`() {
        var s = ChatUiState(currentSessionId = "s1")
        s = s.foldToolStart(tool("t1"), newId, 1L)
        val after = s.foldToolEnd(CoreEvent.ToolEnd("s1", "nope", "x", failed = false))
        assertEquals(s.messages, after.messages)
    }

    // ---------- 收尾 ----------

    @Test
    fun `空闲收尾清流式标记并把运行中工具改为 Interrupted`() {
        var s = ChatUiState(currentSessionId = "s1", running = true, steerQueued = true)
        s = s.foldToolStart(tool("t1"), newId, 1L)
        s = s.foldAssistantText("hi", newId, 2L)
        val done = s.foldIdle("s1", showPlan = false)
        assertFalse(done.running)
        assertFalse(done.steerQueued)
        val m = done.messages.single()
        assertFalse(m.streaming)
        assertEquals(ToolState.Interrupted, m.tools.single().state)
        assertEquals(
            ToolState.Interrupted,
            (m.segments.first { it is MessageSegment.Tool } as MessageSegment.Tool).call.state,
        )
    }

    @Test
    fun `计划回合结束把最后一条非空助手正文装进操作面板`() {
        val s = st(
            ChatMessage(id = "u", role = MsgRole.User, text = "做计划"),
            ChatMessage(id = "a", role = MsgRole.Assistant, text = "计划正文", streaming = true),
            ChatMessage(id = "b", role = MsgRole.Assistant, text = "  ", streaming = true),
        )
        val done = s.foldIdle("s1", showPlan = true)
        assertEquals("计划正文", done.actionPanel?.content)
    }

    @Test
    fun `普通回合结束不弹计划面板`() {
        val s = st(ChatMessage(id = "a", role = MsgRole.Assistant, text = "x", streaming = true))
        assertNull(s.foldIdle("s1", showPlan = false).actionPanel)
    }

    @Test
    fun `失败收尾挂横幅并清运行中状态`() {
        var s = ChatUiState(currentSessionId = "s1", running = true)
        s = s.foldToolStart(tool("t1"), newId, 1L)
        val failed = s.foldFailure(Banner("限流", isError = true, retryable = true))
        assertFalse(failed.running)
        assertEquals("限流", failed.banner?.text)
        assertTrue(failed.banner!!.retryable)
        assertEquals(ToolState.Interrupted, failed.messages.single().tools.single().state)
    }

    // ---------- 会话 ----------

    @Test
    fun `忙碌事件同时置运行态与列表 busy 并清横幅`() {
        val s = ChatUiState(
            currentSessionId = "s1",
            banner = Banner("旧", isError = false),
            sessions = listOf(SessionItem("s1", "a", 1L), SessionItem("s2", "b", 1L)),
        ).foldBusy("s1")
        assertTrue(s.running)
        assertNull(s.banner)
        assertTrue(s.sessions.first { it.id == "s1" }.busy)
        assertFalse(s.sessions.first { it.id == "s2" }.busy)
    }

    @Test
    fun `会话列表帧不含当前会话时本地补一条`() {
        val s = ChatUiState(currentSessionId = "new").foldSessions(
            listOf(SessionItem("other", "x", 5L)),
            now = 99L,
        )
        assertTrue(s.sessions.any { it.id == "new" })
        assertEquals("新建会话", s.sessions.first { it.id == "new" }.title)
        assertTrue(s.sessions.any { it.id == "other" })
    }

    @Test
    fun `会话列表帧刷新时保留本地忙碌标记`() {
        val before = ChatUiState(
            currentSessionId = "s1",
            sessions = listOf(SessionItem("s1", "a", 1L, busy = true)),
        )
        val after = before.foldSessions(listOf(SessionItem("s1", "a", 2L, busy = false)), now = 3L)
        assertTrue(after.sessions.single().busy)
    }

    @Test
    fun `新建会话会清掉上一条会话的全部会话级状态`() {
        val old = ChatUiState(
            currentSessionId = "old",
            messages = listOf(ChatMessage(id = "1", role = MsgRole.User, text = "旧")),
            running = true,
            editText = "草稿",
            resumeBack = 2,
        )
        val s = old.foldSessionClaimed("new", "", now = 1L)
        assertEquals("new", s.currentSessionId)
        assertEquals("新建会话", s.title)
        assertTrue(s.messages.isEmpty())
        assertFalse(s.running)
        assertNull(s.editText)
        assertEquals(-1, s.resumeBack)
        assertNotNull(s.sessions.firstOrNull { it.id == "new" })
    }

    @Test
    fun `重复认领当前会话不清消息`() {
        val cur = ChatUiState(
            currentSessionId = "s1",
            messages = listOf(ChatMessage(id = "1", role = MsgRole.User, text = "保留")),
            sessions = listOf(SessionItem("s1", "旧标题", 1L)),
        )
        val s = cur.foldSessionClaimed("s1", "新标题", now = 2L)
        assertEquals(1, s.messages.size)
        assertEquals("新标题", s.sessions.single().title)
    }

    // ---------- 后台会话过滤 ----------

    @Test
    fun `别的会话的流式输出被判为后台事件`() {
        assertTrue(isBackgroundSessionEvent(CoreEvent.Delta("s2", "x"), "s1"))
        assertTrue(isBackgroundSessionEvent(CoreEvent.Busy("s2"), "s1"))
        assertTrue(isBackgroundSessionEvent(CoreEvent.Idle("s2"), "s1"))
    }

    @Test
    fun `当前会话与无当前会话时不算后台事件`() {
        assertFalse(isBackgroundSessionEvent(CoreEvent.Delta("s1", "x"), "s1"))
        assertFalse(isBackgroundSessionEvent(CoreEvent.Delta("s2", "x"), ""))
        assertFalse(isBackgroundSessionEvent(CoreEvent.Delta("", "x"), "s1"))
    }

    @Test
    fun `切会话本身的事件不被当作后台事件`() {
        assertFalse(isBackgroundSessionEvent(CoreEvent.SessionClaimed("s2", "t"), "s1"))
    }

    @Test
    fun `等待阶段文案在 Busy 时出现并在首段响应到达或结束时清除`() {
        val busy = ChatUiState(currentSessionId = "s1").foldBusy("s1")
        assertTrue(busy.running)
        assertTrue(busy.pendingStage.isNotBlank())

        val streamed = busy.foldAssistantText("你", newId, 1L)
        assertEquals("", streamed.pendingStage)

        val viaThinking = busy.foldThinking("想", newId, 1L)
        assertEquals("", viaThinking.pendingStage)

        val viaTool = busy.foldToolStart(tool("t1"), newId, 1L)
        assertEquals("", viaTool.pendingStage)

        val idled = busy.foldIdle("s1", showPlan = false)
        assertEquals("", idled.pendingStage)
    }
}
