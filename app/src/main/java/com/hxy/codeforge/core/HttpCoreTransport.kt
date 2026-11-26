package com.hxy.codeforge.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 基于 HTTP + WebSocket 的本地内核传输实现（连接顺序：`/api/health` → GET `/` 取 Cookie → `/ws`，P-003）。
 */
class HttpCoreTransport(
    private val host: String,
    private val port: Int,
) : CoreTransport {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<CoreEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<CoreEvent> = _events.asSharedFlow()

    private val cookieJar = MemoryCookieJar()

    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(5, TimeUnit.SECONDS)
        // WebSocket 长连接禁用读超时
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private var currentSession = ""
    private var msgSeq = 0

    @Volatile private var visibilityHidden: Boolean = false

    private val baseUrl: String get() = "http://$host:$port"

    private val running = AtomicBoolean(false)
    private var loopJob: Job? = null

    /** 启动带退避自愈的连接循环，直到 [disconnect] 停止。 */
    override fun connect() {
        if (!running.compareAndSet(false, true)) return
        loopJob = scope.launch {
            var attempt = 0
            while (running.get()) {
                val startedAt = System.currentTimeMillis()
                val everOpen = try {
                    connectOnce()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
                if (!running.get()) break

                // 稳定运行超过 30 秒再断开视为正常断开，退避归零
                val stable = everOpen && System.currentTimeMillis() - startedAt > 30_000
                attempt = if (stable) 1 else attempt + 1
                val wait = backoffMs(attempt)
                _events.tryEmit(CoreEvent.Link(connected = false, attempt = attempt, retryInMs = wait))
                delay(wait)
            }
        }
    }

    private suspend fun connectOnce(): Boolean {
        val healthOk = withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url("$baseUrl/api/health").build())
                    .execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }
        if (!healthOk) return false

        // 每次重连重新请求根路径获取最新的 codeforge_token Cookie
        val cookieOk = withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url("$baseUrl/").build())
                    .execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }
        if (!cookieOk) return false

        val done = CompletableDeferred<Boolean>()
        val everOpen = AtomicBoolean(false)
        val req = Request.Builder().url("ws://$host:$port/ws").build()
        val socket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                everOpen.set(true)
                _events.tryEmit(CoreEvent.Link(connected = true))
                send(JSONObject().put("type", "visibility").put("hidden", visibilityHidden))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { handleFrame(text) }
                    .onFailure { _events.tryEmit(CoreEvent.Failure("解析内核帧失败：${it.message}")) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!done.isCompleted) done.complete(everOpen.get())
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!done.isCompleted) done.complete(everOpen.get())
            }
        })
        ws = socket
        return done.await()
    }

    private fun backoffMs(attempt: Int): Long =
        (1_000L shl (attempt - 1).coerceIn(0, 3)).coerceAtMost(8_000L)

    override fun disconnect() {
        running.set(false)
        loopJob?.cancel()
        loopJob = null
        ws?.close(1000, "client closing")
        ws = null
        // 保留 client 实例以便后续重连复用
    }

    override fun sendMessage(text: String, attachments: List<String>) {
        val o = JSONObject()
            .put("type", "user_message")
            // 必须携带 session_id，空值会触发服务端创建新会话
            .put("session_id", currentSession)
            .put("text", text)
        if (attachments.isNotEmpty()) {
            o.put("attachments", JSONArray(attachments))
        }
        send(o)
    }

    override fun steer(text: String) {
        send(
            JSONObject()
                .put("type", "steer")
                .put("session_id", currentSession)
                .put("text", text),
        )
    }

    override fun regenerate() {
        send(
            JSONObject()
                .put("type", "regenerate")
                .put("session_id", currentSession),
        )
    }

    override fun pullContext() {
        send(
            JSONObject()
                .put("type", "context")
                .put("session_id", currentSession),
        )
    }

    override fun pullTodos() {
        send(
            JSONObject()
                .put("type", "todos")
                .put("session_id", currentSession),
        )
    }

    override fun sendVisibility(hidden: Boolean) {
        visibilityHidden = hidden
        send(JSONObject().put("type", "visibility").put("hidden", hidden))
    }

    override fun cancel() {
        send(JSONObject().put("type", "cancel").put("session_id", currentSession))
    }

    override fun editUserMessage(text: String, back: Int, rollbackFiles: Boolean) {
        send(
            JSONObject()
                .put("type", "edit_user_message")
                .put("session_id", currentSession)
                .put("text", text)
                .put("back", back)
                .put("rollback_files", rollbackFiles),
        )
    }

    override fun continueTurn() {
        send(JSONObject().put("type", "continue_turn").put("session_id", currentSession))
    }

    override fun retry(back: Int, rollbackFiles: Boolean) {
        send(
            JSONObject()
                .put("type", "retry")
                .put("session_id", currentSession)
                .put("back", back.coerceAtLeast(0))
                .put("rollback_files", rollbackFiles),
        )
    }

    override fun rewindFiles(toStep: Int) {
        send(
            JSONObject()
                .put("type", "rewind")
                .put("session_id", currentSession)
                .put("to_step", toStep),
        )
    }

    override fun pullCheckpoints() {
        send(JSONObject().put("type", "checkpoints").put("session_id", currentSession))
    }

    override fun decideApproval(approvalId: String, allow: Boolean) {
        send(
            JSONObject()
                .put("type", "hitl_decision")
                .put("approval_id", approvalId)
                .put("approved", allow),
        )
    }

    override fun listSessions() {
        send(JSONObject().put("type", "list_sessions"))
    }

    override fun loadSession(sessionId: String) {
        currentSession = sessionId
        send(JSONObject().put("type", "load_session").put("session_id", sessionId))
    }

    override fun setCurrentSession(sessionId: String) {
        if (sessionId.isNotBlank()) currentSession = sessionId
    }

    override fun newSession(): Boolean =
        send(JSONObject().put("type", "new_session"))

    private fun send(obj: JSONObject): Boolean {
        val socket = ws ?: return false
        return runCatching { socket.send(obj.toString()) }.getOrDefault(false)
    }

    private fun handleFrame(raw: String) {
        val event = CoreFrames.parse(JSONObject(raw), currentSession) { "h-${msgSeq++}" } ?: return
        when (event) {
            is CoreEvent.SessionClaimed -> currentSession = event.sessionId
            is CoreEvent.History -> currentSession = event.sessionId
            else -> Unit
        }
        _events.tryEmit(event)
    }

    private class MemoryCookieJar : CookieJar {
        private val store = mutableListOf<Cookie>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            synchronized(store) {
                for (c in cookies) {
                    store.removeAll { it.name == c.name }
                    store.add(c)
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            synchronized(store) { store.toList() }
    }
}
