package com.hxy.codeforge.phone

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 验证内嵌 Streamable HTTP MCP 服务端（[PhoneMcpServer]）的真实环回网络协议兼容性。
 *
 * 逐项对齐 Go 内核 `pkg/tools/plugins/driver_mcphttp.go` 的请求/响应格式：
 * initialize → tools/list → tools/call（成功与 isError=true）→ 通知 202 → 错误令牌拦截 → chunked 请求体。
 */
class PhoneMcpServerTest {

    private var server: PhoneMcpServer? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    @After
    fun tearDown() {
        server?.stop()
        server = null
    }

    @Test
    fun `initialize tools_list and tools_call work end to end over loopback HTTP`() = runBlocking {
        val srv = PhoneMcpServer { toolName, args ->
            when (toolName) {
                "device_status" -> PhoneMcpServer.ToolCallOutcome(
                    success = true,
                    text = JSONObject().put("battery_percent", 88).toString(),
                )
                "ui_click" -> {
                    val target = args.optString("text")
                    if (target.isEmpty()) {
                        PhoneMcpServer.ToolCallOutcome(false, """{"error":"missing target"}""")
                    } else {
                        PhoneMcpServer.ToolCallOutcome(true, """{"clicked":"$target"}""")
                    }
                }
                else -> PhoneMcpServer.ToolCallOutcome(false, "unknown")
            }
        }
        server = srv
        val endpoint = srv.start()
        assertTrue(endpoint.startsWith("http://127.0.0.1:"))
        assertTrue(srv.isRunning)

        // 1. /health
        val healthResp = client.newCall(
            Request.Builder().url("http://127.0.0.1:${srv.port}/health").get().build(),
        ).execute()
        assertEquals(200, healthResp.code)
        assertTrue(healthResp.body?.string().orEmpty().contains("ok"))
        healthResp.close()

        // 2. initialize
        val initRes = rpc(endpoint, 1, "initialize", JSONObject().put("protocolVersion", "2025-03-26"))
        assertEquals(1, initRes.getInt("id"))
        val serverInfo = initRes.getJSONObject("result").getJSONObject("serverInfo")
        assertEquals("codeforge-phone", serverInfo.getString("name"))

        // 3. notification (no id -> 202)
        val notifBody = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", "notifications/initialized")
            .toString()
        val notifResp = client.newCall(
            Request.Builder()
                .url(endpoint)
                .post(notifBody.toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute()
        assertEquals(202, notifResp.code)
        notifResp.close()

        // 4. tools/list
        val listRes = rpc(endpoint, 2, "tools/list", null)
        val tools = listRes.getJSONObject("result").getJSONArray("tools")
        assertEquals(PhoneToolRegistry.tools.size, tools.length())
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }.toSet()
        assertTrue("screen_observe" in names)
        assertTrue("ui_click" in names)
        assertTrue("system_setting" in names)
        assertTrue("calendar_reminder" in names)
        assertTrue("task_control" in names)

        // 5. tools/call success
        val callOk = rpc(
            endpoint,
            3,
            "tools/call",
            JSONObject()
                .put("name", "ui_click")
                .put("arguments", JSONObject().put("text", "确定")),
        )
        val callResult = callOk.getJSONObject("result")
        assertFalse(callResult.getBoolean("isError"))
        val contentArr = callResult.getJSONArray("content")
        assertEquals("text", contentArr.getJSONObject(0).getString("type"))
        assertTrue(contentArr.getJSONObject(0).getString("text").contains("确定"))

        // 6. tools/call failure -> isError=true
        val callErr = rpc(
            endpoint,
            4,
            "tools/call",
            JSONObject()
                .put("name", "ui_click")
                .put("arguments", JSONObject()),
        )
        val errResult = callErr.getJSONObject("result")
        assertTrue(errResult.getBoolean("isError"))
        assertTrue(errResult.getJSONArray("content").getJSONObject(0).getString("text").contains("missing target"))

        // 7. wrong token -> 404
        val badResp = client.newCall(
            Request.Builder()
                .url("http://127.0.0.1:${srv.port}/mcp/wrongtoken")
                .post("""{"jsonrpc":"2.0","id":9,"method":"ping"}""".toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute()
        assertEquals(404, badResp.code)
        badResp.close()
    }

    @Test
    fun `supports chunked transfer encoding and concurrent tool calls`() = runBlocking {
        val srv = PhoneMcpServer { toolName, args ->
            PhoneMcpServer.ToolCallOutcome(true, "$toolName:${args.optInt("seq")}")
        }
        server = srv
        val endpoint = srv.start()

        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 77)
            .put("method", "tools/call")
            .put(
                "params",
                JSONObject()
                    .put("name", "ping_test")
                    .put("arguments", JSONObject().put("seq", 77)),
            )
            .toString()
            .toByteArray(Charsets.UTF_8)

        // Chunked request body (contentLength = -1 forces OkHttp Transfer-Encoding: chunked)
        val chunkedBody = object : RequestBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun writeTo(sink: BufferedSink) {
                sink.write(payload, 0, payload.size / 2)
                sink.flush()
                sink.write(payload, payload.size / 2, payload.size - payload.size / 2)
            }
        }
        val chunkedResp = client.newCall(Request.Builder().url(endpoint).post(chunkedBody).build()).execute()
        assertEquals(200, chunkedResp.code)
        val chunkJson = JSONObject(chunkedResp.body?.string().orEmpty())
        chunkedResp.close()
        assertEquals(77, chunkJson.getInt("id"))
        assertEquals(
            "ping_test:77",
            chunkJson.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text"),
        )

        // Concurrent calls
        val responses = (1..8).map { i ->
            async {
                rpc(
                    endpoint,
                    100 + i,
                    "tools/call",
                    JSONObject().put("name", "echo").put("arguments", JSONObject().put("seq", i)),
                )
            }
        }.awaitAll()
        responses.forEachIndexed { idx, json ->
            assertEquals(101 + idx, json.getInt("id"))
            val text = json.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text")
            assertEquals("echo:${idx + 1}", text)
        }
    }

    @Test
    fun `tool registry schemas are complete and unique`() {
        val defs = PhoneToolRegistry.tools
        assertEquals(20, defs.size)
        val names = defs.map { it.name }
        assertEquals(names.size, names.distinct().size)

        defs.forEach { t ->
            assertTrue("Tool ${t.name} description empty", t.description.isNotBlank())
            assertEquals("object", t.schema.getString("type"))
            val props = t.schema.getJSONObject("properties")
            val req = t.schema.optJSONArray("required") ?: JSONArray()
            for (i in 0 until req.length()) {
                val r = req.getString(i)
                assertNotNull("Required field $r missing in ${t.name} properties", props.optJSONObject(r))
            }
        }
    }

    private fun rpc(endpoint: String, id: Int, method: String, params: JSONObject?): JSONObject {
        val envelope = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
        if (params != null) envelope.put("params", params)

        val req = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json, text/event-stream")
            .post(envelope.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            assertEquals(200, resp.code)
            return JSONObject(resp.body?.string().orEmpty())
        }
    }
}
