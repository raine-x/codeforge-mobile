package com.hxy.codeforge.phone

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/**
 * 嵌入宿主进程内的 Streamable HTTP MCP 服务端（仅绑定 `127.0.0.1:<随机端口>`）。
 *
 * 与 Go 内核 `pkg/tools/plugins/driver_mcphttp.go` 逐字对齐：
 * - 支持随机 token 路径 `/mcp/<token>`，防同机其他非授权进程误调；
 * - 完整解析 HTTP/1.1 请求（`Content-Length` 与 `Transfer-Encoding: chunked`、`Expect: 100-continue`）；
 * - 支持 JSON-RPC 2.0 `initialize`、`notifications/initialized`、`ping`、`tools/list`、`tools/call`；
 * - 对接 [PhoneToolRegistry] 声明的工具列表，并通过 [toolExecutor] 异步执行工具并返回 MCP `content[]`。
 */
class PhoneMcpServer(
    private val toolExecutor: suspend (toolName: String, args: JSONObject) -> ToolCallOutcome,
) {

    data class ToolCallOutcome(
        val success: Boolean,
        val text: String,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptJob: Job? = null

    @Volatile
    var port: Int = -1
        private set

    @Volatile
    var token: String = ""
        private set

    val endpoint: String
        get() = if (port > 0 && token.isNotEmpty()) "http://127.0.0.1:$port/mcp/$token" else ""

    val isRunning: Boolean
        get() = serverSocket?.let { !it.isClosed } == true && port > 0

    fun start(): String {
        synchronized(lock) {
            if (isRunning) return endpoint
            val ss = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
            token = generateToken()
            port = ss.localPort
            serverSocket = ss
            acceptJob = scope.launch {
                while (!ss.isClosed) {
                    val socket = runCatching { ss.accept() }.getOrNull() ?: break
                    scope.launch { handleConnection(socket) }
                }
            }
            return endpoint
        }
    }

    fun stop() {
        synchronized(lock) {
            acceptJob?.cancel()
            acceptJob = null
            runCatching { serverSocket?.close() }
            serverSocket = null
            port = -1
            token = ""
        }
    }

    private suspend fun handleConnection(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 30_000
            val input = BufferedInputStream(s.getInputStream(), 16 * 1024)
            val output = s.getOutputStream()

            val req = runCatching { readHttpRequest(input, output) }.getOrNull() ?: return
            if (req.method == "GET" && req.path == "/health") {
                writeHttpResponse(output, 200, "OK", """{"ok":true}""")
                return
            }
            if (req.path != "/mcp/$token") {
                writeHttpResponse(output, 404, "Not Found", """{"error":"unknown mcp endpoint"}""")
                return
            }
            if (req.method != "POST") {
                writeHttpResponse(output, 405, "Method Not Allowed", """{"error":"only POST supported"}""")
                return
            }

            val bodyText = req.body.toString(Charsets.UTF_8).trim()
            if (bodyText.isEmpty()) {
                writeHttpResponse(output, 400, "Bad Request", """{"error":"empty body"}""")
                return
            }
            val msg = runCatching { JSONObject(bodyText) }.getOrNull()
            if (msg == null) {
                val errEnv = jsonRpcError(JSONObject.NULL, -32700, "JSON parse error")
                writeHttpResponse(output, 200, "OK", errEnv.toString())
                return
            }

            val hasId = msg.has("id") && !msg.isNull("id")
            val idVal: Any = if (hasId) msg.get("id") else JSONObject.NULL
            val method = msg.optString("method")

            // 通知类消息（无 id）返回 202 Accepted
            if (!hasId) {
                writeHttpEmpty(output, 202, "Accepted")
                return
            }

            val responseJson = when (method) {
                "initialize" -> {
                    val result = JSONObject().apply {
                        put("protocolVersion", "2025-03-26")
                        put(
                            "capabilities",
                            JSONObject().put("tools", JSONObject()),
                        )
                        put(
                            "serverInfo",
                            JSONObject().put("name", "codeforge-phone").put("version", "1.0.0"),
                        )
                    }
                    jsonRpcOk(idVal, result)
                }
                "ping" -> jsonRpcOk(idVal, JSONObject())
                "tools/list" -> {
                    val result = JSONObject().put("tools", PhoneToolRegistry.toMcpToolsListJson())
                    jsonRpcOk(idVal, result)
                }
                "tools/call" -> {
                    val params = msg.optJSONObject("params") ?: JSONObject()
                    val toolName = params.optString("name").trim()
                    val args = params.optJSONObject("arguments") ?: JSONObject()
                    if (toolName.isEmpty()) {
                        jsonRpcError(idVal, -32602, "Missing tool name")
                    } else {
                        val outcome = runCatching { toolExecutor(toolName, args) }.getOrElse { e ->
                            ToolCallOutcome(false, "工具执行异常：${e.message ?: e.javaClass.simpleName}")
                        }
                        val content = JSONArray().put(
                            JSONObject()
                                .put("type", "text")
                                .put("text", outcome.text),
                        )
                        val result = JSONObject()
                            .put("content", content)
                            .put("isError", !outcome.success)
                        jsonRpcOk(idVal, result)
                    }
                }
                else -> jsonRpcError(idVal, -32601, "Method not found: $method")
            }

            writeHttpResponse(output, 200, "OK", responseJson.toString())
        }
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private fun readHttpRequest(input: BufferedInputStream, output: java.io.OutputStream): HttpRequest? {
        val requestLine = readLineCrlf(input) ?: return null
        if (requestLine.isBlank()) return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val rawPath = parts[1].substringBefore('?')

        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLineCrlf(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                val k = line.substring(0, colon).trim().lowercase()
                val v = line.substring(colon + 1).trim()
                headers[k] = v
            }
        }

        if (headers["expect"]?.equals("100-continue", ignoreCase = true) == true) {
            output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.flush()
        }

        val te = headers["transfer-encoding"].orEmpty().lowercase()
        val body = if (te.contains("chunked")) {
            readChunkedBody(input)
        } else {
            val len = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 8 * 1024 * 1024) ?: 0
            if (len > 0) readExactBytes(input, len) else ByteArray(0)
        }
        return HttpRequest(method, rawPath, headers, body)
    }

    private fun readLineCrlf(input: InputStream): String? {
        val buf = ByteArrayOutputStream(128)
        var b = input.read()
        if (b < 0) return null
        while (b >= 0) {
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > 16 * 1024) break
            b = input.read()
        }
        return buf.toString(Charsets.US_ASCII.name())
    }

    private fun readExactBytes(input: InputStream, count: Int): ByteArray {
        val out = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val n = input.read(out, offset, count - offset)
            if (n < 0) throw EOFException("Unexpected EOF while reading body")
            offset += n
        }
        return out
    }

    private fun readChunkedBody(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLineCrlf(input) ?: break
            val hex = sizeLine.substringBefore(';').trim()
            val chunkSize = hex.toIntOrNull(16) ?: break
            if (chunkSize == 0) {
                // Consume trailer headers until blank line
                while (true) {
                    val trailer = readLineCrlf(input) ?: break
                    if (trailer.isEmpty()) break
                }
                break
            }
            out.write(readExactBytes(input, chunkSize))
            readLineCrlf(input) // Trailing CRLF after chunk
        }
        return out.toByteArray()
    }

    private fun writeHttpResponse(output: java.io.OutputStream, code: Int, status: String, jsonBody: String) {
        val bytes = jsonBody.toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(status).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII)
        output.write(headers)
        output.write(bytes)
        output.flush()
    }

    private fun writeHttpEmpty(output: java.io.OutputStream, code: Int, status: String) {
        val headers = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(status).append("\r\n")
            append("Content-Length: 0\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII)
        output.write(headers)
        output.flush()
    }

    private fun jsonRpcOk(id: Any, result: JSONObject): JSONObject =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }

    private fun jsonRpcError(id: Any, code: Int, message: String): JSONObject =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put(
                "error",
                JSONObject().put("code", code).put("message", message),
            )
        }

    private fun generateToken(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val rnd = SecureRandom()
        return buildString(24) {
            repeat(24) { append(alphabet[rnd.nextInt(alphabet.length)]) }
        }
    }
}
