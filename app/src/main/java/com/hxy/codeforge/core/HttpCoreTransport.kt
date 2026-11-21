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
 * 真实内核连接：HTTP + WebSocket，打 127.0.0.1:<内核随机端口>。
 *
 * ## 鉴权是 Cookie，不是 query 参数
 *
 * 内核 `validToken()` 只读名为 `codeforge_token` 的 Cookie（`TokenCookie` 常量），
 * 不看 `?token=`。令牌由 `handleRoot`（GET `/`）通过 Set-Cookie 下发。
 * 所以连接顺序必须是：**先 GET `/` 收下 Cookie，再连 `/ws`**。
 * 顺序反了会直接 401 —— 这是接壳时最容易踩的一处。
 *
 * ## 端口从哪来
 *
 * 内核在 `port: 0` 时会绑定随机端口并把 `PORT=xxxxx` 打到 stdout 首行，
 * [CoreService] 读到后传进来。此处不自己猜端口。
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
        // WS 是长连接，读超时必须关掉，否则空闲几十秒就被自己断开。
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private var currentSession = ""
    private var msgSeq = 0

    /** VM 注入的页面可见性快照：连上时随首帧上报真实值 */
    @Volatile private var visibilityHidden: Boolean = false

    private val baseUrl: String get() = "http://$host:$port"

    /** 自愈循环是否在跑。重复 connect() 不叠加循环。 */
    private val running = AtomicBoolean(false)
    private var loopJob: Job? = null

    /**
     * 建立连接，并**一直维持**它。
     *
     * 旧实现只尝试一次：失败就发一个 Failure 然后彻底放弃，于是
     * 「内核 bind 成功但 accept 还没就绪」这种毫秒级窗口会把界面永久卡在
     * 「未连接」（通知栏显示内核运行中、设置页说未连接，就是这个症状）。
     * 现在改成自愈循环：健康探测 → 取 Cookie → 连 WS → 挂起等断 → 退避重连，
     * 直到 [disconnect] 被调用。
     */
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

                // 连上并稳定运行超过 30 秒再断 → 视为正常断开，退避归零重来；
                // 连上就断 / 根本连不上 → 累加退避。
                val stable = everOpen && System.currentTimeMillis() - startedAt > 30_000
                attempt = if (stable) 1 else attempt + 1
                val wait = backoffMs(attempt)
                _events.tryEmit(CoreEvent.Link(connected = false, attempt = attempt, retryInMs = wait))
                delay(wait)
            }
        }
    }

    /**
     * 一次完整连接尝试，**挂起直到链路断开**。
     *
     * 返回「是否曾经连上过」——决定退避是否重置。
     */
    private suspend fun connectOnce(): Boolean {
        // 1. 健康探测。只探 TCP 端口不够：内核可能已 bind 但 handler 还没接客，
        //    这时 TCP 能连上、HTTP 却拿不到响应。/api/health 免鉴权，正好用来探。
        val healthOk = withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url("$baseUrl/api/health").build())
                    .execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }
        if (!healthOk) return false

        // 2. 取 Cookie。内核重启后 token 会变，所以每次重连都重新拿一遍。
        val cookieOk = withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url("$baseUrl/").build())
                    .execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }
        if (!cookieOk) return false

        // 3. 连 WS，挂起等它断开
        val done = CompletableDeferred<Boolean>()
        val everOpen = AtomicBoolean(false)
        val req = Request.Builder().url("ws://$host:$port/ws").build()
        val socket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                everOpen.set(true)
                // 链路一通就报「已连接」，**不等 ready 帧**。
                // ready 只是带会话与配置的附加载荷，它没来不该让界面显示未连接。
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

    /** 退避：1 → 2 → 4 → 8s 封顶。内核重启通常 1~3 秒，再长只会让界面显得更迟钝。 */
    private fun backoffMs(attempt: Int): Long =
        (1_000L shl (attempt - 1).coerceIn(0, 3)).coerceAtMost(8_000L)

    override fun disconnect() {
        running.set(false)
        loopJob?.cancel()
        loopJob = null
        ws?.close(1000, "client closing")
        ws = null
        // 不 shutdown client：自愈循环可能还要用它重连。
        // OkHttp 的空闲连接与线程会在 60 秒后自行回收。
    }

    override fun sendMessage(text: String, attachments: List<String>) {
        val o = JSONObject()
            .put("type", "user_message")
            // ⚠️ 必须带 session_id：服务端对空 session_id 会 History.Create，
            // 表现就是「说一句话新建一个会话」。currentSession 由 session/history 帧回填。
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
        // 按会话打断：不带 id 时服务端只在「恰好一个会话在跑」时才停，
        // 多会话并发时可能停错/不停。
        send(JSONObject().put("type", "cancel").put("session_id", currentSession))
    }

    // ---- 断点三件套上行（B6-P0，帧名与键对照 ws_handler.go） ----
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

    /**
     * 同步当前会话，不发帧。
     *
     * 新建会话成功后内核只回 `session` 帧（不是 `history`），旧实现只在
     * history 分支里写 currentSession —— 于是新会话的下一条 user_message
     * 仍带旧会话 id：要么把消息送进旧会话，要么在内核已经重启/清理后
     * 因 id 为空而触发 History.Create，表现就是「发一句又自动新开一个对话」。
     */
    override fun setCurrentSession(sessionId: String) {
        if (sessionId.isNotBlank()) currentSession = sessionId
    }

    override fun newSession(): Boolean =
        send(JSONObject().put("type", "new_session"))

    /**
     * 发一帧；返回是否真的发出去了。
     *
     * ⚠️ 之前是 `ws?.send(...)` 直接吞掉失败：WS 还没连上或刚断开时，
     * 帧被静默丢弃，调用方以为成功 —— 表现就是「点新建对话没反应」。
     */
    private fun send(obj: JSONObject): Boolean {
        val socket = ws ?: return false
        return runCatching { socket.send(obj.toString()) }.getOrDefault(false)
    }

    /**
     * 处理一帧下行：解析交给 [CoreFrames]，这里只维护传输层自己的「当前会话」并发事件。
     * session / history 帧确立当前会话，后续上行帧必须带它（见 setCurrentSession 注释）。
     */
    private fun handleFrame(raw: String) {
        val event = CoreFrames.parse(JSONObject(raw), currentSession) { "h-${msgSeq++}" } ?: return
        when (event) {
            is CoreEvent.SessionClaimed -> currentSession = event.sessionId
            is CoreEvent.History -> currentSession = event.sessionId
            else -> Unit
        }
        _events.tryEmit(event)
    }

    /** 内存 CookieJar：只服务一个 host，不需要持久化。 */
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
