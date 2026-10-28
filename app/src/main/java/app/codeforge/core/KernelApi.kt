package app.codeforge.core

import app.codeforge.model.KernelConfigSummary
import app.codeforge.model.SubagentPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 内核 REST API 客户端（/api 系列端点，Cookie 鉴权与 WS 同一套，见 P-003）。
 *
 * 覆盖设置页需要的端点：config（读/写生成与重试参数）、perm（权限模式）、
 * context/compress（手动压缩）、sessions（重命名/归档/删除）、subagents（子智能体
 * 策略）、notify（通知偏好）、workspace（工作区）。
 *
 * 生命周期由 ChatViewModel 管理：内核 Ready(port) 时创建，重启换端口时重建。
 * 所有方法挂起且切 IO；错误不抛异常，返回 [ApiResult]——设置页要能显示
 * 「内核未就绪」而不是白屏。
 */
class KernelApi(private val host: String, private val port: Int) {

    sealed class ApiResult<out T> {
        data class Ok<T>(val value: T) : ApiResult<T>()
        data class Err(val message: String) : ApiResult<Nothing>()
    }

    private val cookieJar = MemoryCookieJar()
    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val base: String get() = "http://$host:$port"

    private var handshaked = false

    /** GET / 收 Cookie（P-003：没有这步一切 401）。幂等。 */
    suspend fun init(): ApiResult<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url("$base/").build()).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
            }
        }.fold(
            onSuccess = { handshaked = true; ApiResult.Ok(Unit) },
            onFailure = { ApiResult.Err("连接内核失败：${it.message}") },
        )
    }

    // ---------------------------------------------------------------- 配置

    /** GET /api/config → 设置页展示用的摘要（只取我们要的字段，其余忽略）。 */
    suspend fun getConfig(): ApiResult<KernelConfigSummary> =
        get("/api/config") { body ->
            val o = JSONObject(body)
            // permission 段历史上有两种形态：对象 {mode} 或裸字符串，都认；缺省 ask
            val perm = o.optJSONObject("permission")?.optString("mode")
                ?: o.optString("permission").ifEmpty { "ask" }
            KernelConfigSummary(
                model = o.optString("model"),
                displayName = o.optString("display_name").ifEmpty { o.optString("model") },
                provider = o.optString("provider"),
                maxTokens = o.optInt("max_tokens", 0),
                temperature = o.optDouble("temperature", 0.0),
                retryMaxAttempts = o.optInt("retry_max_attempts", 0),
                retryMode = o.optString("retry_mode").ifEmpty { "backoff" },
                retryIntervalSec = o.optInt("retry_interval_sec", 0),
                maxSteps = o.optInt("max_steps", 0),
                permMode = perm,
                workDir = o.optString("work_dir"),
                apiKeySet = o.optBoolean("api_key_set", false),
            )
        }

    /** POST /api/config：部分更新（null 字段不下发）。model/provider 用于弹层选择模型与供应商。 */
    suspend fun updateConfig(
        maxTokens: Int? = null,
        temperature: Double? = null,
        retryMaxAttempts: Int? = null,
        retryMode: String? = null,
        retryIntervalSec: Int? = null,
        model: String? = null,
        provider: String? = null,
        maxSteps: Int? = null,
    ): ApiResult<Unit> = post("/api/config", JSONObject().apply {
        maxTokens?.let { put("max_tokens", it) }
        temperature?.let { put("temperature", it) }
        retryMaxAttempts?.let { put("retry_max_attempts", it) }
        retryMode?.let { put("retry_mode", it) }
        retryIntervalSec?.let { put("retry_interval_sec", it) }
        model?.let { put("model", it) }
        provider?.let { put("provider", it) }
        maxSteps?.let { put("max_steps", it) }
    }) { ApiResult.Ok(Unit) }

    // ---------------------------------------------------------------- 权限

    /** POST /api/perm：readonly / ask / auto 三档，热生效。 */
    suspend fun setPerm(mode: String): ApiResult<Unit> =
        post("/api/perm", JSONObject().put("mode", mode)) { }

    /** GET /api/perm：读内核当前权限档（Ready 后回填 UI，防两端不一致）。 */
    suspend fun getPerm(): ApiResult<String> =
        get("/api/perm") { body ->
            val mode = JSONObject(body).optString("mode").ifEmpty { "ask" }
            if (mode !in listOf("readonly", "ask", "auto")) error("未知档位：$mode")
            mode
        }

    /**
     * POST /api/shutdown：让内核优雅收尾（会话落盘、审计 flush）。
     * 该端点不吃 Cookie，认 X-CodeForge-Token 头 —— 值就是 Cookie 里的令牌，
     * 从 jar 里取（cookieJar 对本类可见）。
     */
    suspend fun shutdown(): ApiResult<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val token = cookieJar.loadForRequest(
                okhttp3.HttpUrl.Companion.run {
                    "http://$host:$port/".toHttpUrl()
                },
            ).firstOrNull { it.name == "codeforge_token" }?.value
                ?: return@withContext ApiResult.Err("缺少内核令牌")
            client.newCall(
                Request.Builder()
                    .url("$base/api/shutdown")
                    .header("X-CodeForge-Token", token)
                    .post("{}".toRequestBody(null as okhttp3.MediaType?))
                    .build(),
            ).execute().use { resp ->
                if (!resp.isSuccessful) ApiResult.Err("HTTP ${resp.code}")
                else ApiResult.Ok(Unit)
            }
        }.getOrElse { ApiResult.Err(it.message ?: it.javaClass.simpleName) }
    }

    // ---------------------------------------------------------------- 上下文

    /** POST /api/context/compress：手动压缩当前会话。结果同时会经 WS compress 帧进消息流。 */
    suspend fun compressContext(sessionId: String?): ApiResult<String> =
        post(
            "/api/context/compress",
            JSONObject().apply { sessionId?.let { put("session_id", it) } },
        ) { body ->
            val o = JSONObject(body)
            if (!o.optBoolean("ok", false)) error(o.optString("error", "压缩失败"))
            "已压缩：${o.optLong("before")} → ${o.optLong("after")} tokens（摘要 +${o.optLong("added")}）"
        }

    // ---------------------------------------------------------------- 会话

    /** PATCH /api/sessions：重命名 / 归档（archived=true）或取消归档。 */
    suspend fun patchSession(id: String, title: String? = null, archived: Boolean? = null): ApiResult<Unit> =
        request("PATCH", "/api/sessions", JSONObject().apply {
            put("id", id)
            title?.let { put("title", it) }
            archived?.let { put("archived", it) }
        }) { }

    /** DELETE /api/sessions?id= */
    suspend fun deleteSession(id: String): ApiResult<Unit> =
        request("DELETE", "/api/sessions?id=$id", null) { }

    // ---------------------------------------------------------------- 子智能体

    suspend fun getSubagentPolicy(): ApiResult<SubagentPolicy> =
        get("/api/subagents") { body ->
            val o = JSONObject(body)
            SubagentPolicy(
                enabled = o.optBoolean("enabled", false),
                maxConcurrent = o.optInt("max_concurrent", 0),
                maxSteps = o.optInt("max_steps", 0),
                allowWrite = o.optBoolean("allow_write", false),
                allowDelete = o.optBoolean("allow_delete", false),
                allowMemory = o.optBoolean("allow_memory", false),
            )
        }

    suspend fun updateSubagentPolicy(p: SubagentPolicy): ApiResult<Unit> =
        post("/api/subagents", JSONObject().apply {
            put("enabled", p.enabled)
            put("max_concurrent", p.maxConcurrent)
            put("max_steps", p.maxSteps)
            put("allow_write", p.allowWrite)
            put("allow_delete", p.allowDelete)
            put("allow_memory", p.allowMemory)
        }) { }

    // ---------------------------------------------------------------- 通知 / 工作区

    suspend fun setNotifyEnabled(enabled: Boolean): ApiResult<Unit> =
        post("/api/notify", JSONObject().put("enabled", enabled)) { ApiResult.Ok(Unit) }

    suspend fun setWorkspace(path: String): ApiResult<Unit> =
        post("/api/workspace", JSONObject().put("path", path)) { ApiResult.Ok(Unit) }

    // ---------------------------------------------------------------- 模型库 / 供应商 / 思考规格
    // 以下端点的响应形态按「{items:[…]} 或裸数组」双形态防御解析；字段名以
    // models 命令行实测为准，联调期允许显示降级（Err 落到界面可见处）。

    data class ModelEntry(val id: String, val name: String, val provider: String, val badge: String = "")

    /** GET /api/models/list：可用模型库。 */
    suspend fun listModels(): ApiResult<List<ModelEntry>> =
        get("/api/models/list") { body ->
            // P-008 同款陷阱：这个端点的载荷是 {models, providers, active}，
            // 模型数组键就叫 models（items 键不存在，旧解析永远空）
            val arr = JSONObject(body).optJSONArray("models")
                ?: JSONObject(body).optJSONArray("items")
                ?: org.json.JSONArray(body)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").ifEmpty { o.optString("model") }
                if (id.isEmpty()) null else ModelEntry(
                    id = id,
                    name = o.optString("display_name").ifEmpty { o.optString("name").ifEmpty { id } },
                    provider = o.optString("provider"),
                    badge = o.optString("badge"),
                )
            }
        }

    /** GET /api/providers/list：供应商名单。 */
    suspend fun listProviders(): ApiResult<List<String>> =
        get("/api/providers/list") { body ->
            // 与 /api/models/list 共用同一份载荷（见内核 modelLibraryPayload）
            val arr = JSONObject(body).optJSONArray("providers")
                ?: JSONObject(body).optJSONArray("items")
                ?: org.json.JSONArray(body)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i)
                o?.optString("name")?.ifEmpty { o.optString("id") }?.ifEmpty { null } ?: arr.optString(i).ifEmpty { null }
            }
        }

    data class ThinkingSpec(val steps: List<String> = emptyList(), val min: Int = 0, val max: Int = 0)

    /** 供应商完整信息（管理页要编辑，光有名字不够）。 */
    data class ProviderInfo(
        val id: String,
        val name: String,
        val baseUrl: String,
        val protocol: String,
        val keySource: String,
        val keyName: String,
        val disabled: Boolean,
    )

    /** GET /api/providers/list 的完整字段版。 */
    suspend fun listProviderDetails(): ApiResult<List<ProviderInfo>> =
        get("/api/providers/list") { body ->
            val arr = JSONObject(body).optJSONArray("providers") ?: org.json.JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                if (id.isEmpty()) return@mapNotNull null
                ProviderInfo(
                    id = id,
                    name = o.optString("name").ifEmpty { id },
                    baseUrl = o.optString("base_url"),
                    protocol = o.optString("protocol").ifEmpty { "openai" },
                    keySource = o.optString("key_source").ifEmpty { "plain" },
                    keyName = o.optString("key_name"),
                    disabled = o.optBoolean("disabled", false),
                )
            }
        }

    /**
     * POST /api/providers/save —— 新增或按 id 更新。
     *
     * [keyTouched] 为 false 且 [keyValue] 为空时，内核沿用库里的旧密钥
     * （界面拿到的是脱敏视图，空输入框不代表用户清空了密钥，见内核 providerSaveReq 注释）。
     */
    suspend fun saveProvider(
        id: String,
        name: String,
        baseUrl: String,
        protocol: String,
        keySource: String,
        keyName: String,
        keyValue: String,
        keyTouched: Boolean,
        disabled: Boolean = false,
    ): ApiResult<Unit> = post("/api/providers/save", JSONObject().apply {
        put("id", id)
        put("name", name)
        put("base_url", baseUrl)
        put("protocol", protocol)
        put("key_source", keySource)
        put("key_name", keyName)
        put("disabled", disabled)
        put("key_touched", keyTouched)
        if (keyTouched && keyValue.isNotEmpty()) put("key_value", keyValue)
    }) { }

    /** POST /api/providers/delete —— 按 id 删；内核会顺带解绑该供应商下的模型。 */
    suspend fun deleteProvider(id: String): ApiResult<Unit> =
        post("/api/providers/delete", JSONObject().put("provider", id)) { }

    /** GET /api/config 里的 thinking 规格（steps 档名 / range 数值域），驱动拉条。 */
    suspend fun getThinkingSpec(): ApiResult<ThinkingSpec> =
        get("/api/config") { body ->
            val th = JSONObject(body).optJSONObject("thinking") ?: JSONObject()
            val stepsArr = th.optJSONArray("steps")
            ThinkingSpec(
                steps = if (stepsArr == null) emptyList() else (0 until stepsArr.length())
                    .map { stepsArr.optString(it) }.filter { it.isNotEmpty() },
                min = th.optJSONObject("range")?.optInt("min", 0) ?: 0,
                max = th.optJSONObject("range")?.optInt("max", 0) ?: 0,
            )
        }

    /** POST /api/models/apply：把库里的模型应用到当前配置（切通道）。 */
    suspend fun applyModel(id: String): ApiResult<Unit> =
        post("/api/models/apply", JSONObject().put("model", id)) { }

    /** POST /api/models/test：连接测试（返回 {ok, latency_ms|error}）。 */
    suspend fun testModel(id: String): ApiResult<String> =
        post("/api/models/test", JSONObject().put("id", id)) { body ->
            val o = JSONObject(body)
            if (o.optBoolean("ok", false)) "连通（${o.optLong("latency_ms", -1)} ms）"
            else error(o.optString("error", "测试失败"))
        }

    /** POST /api/models/discover：上游模型发现 → 模型 id 列表。 */
    /**
     * POST /api/models/discover —— 列出上游可用模型。
     *
     * [provider] 非空时，内核用**该供应商真实生效**的地址/协议/密钥去探测
     * （`applyProviderDefaults`），前端不必重复提交这些字段。
     */
    suspend fun discoverModels(provider: String = ""): ApiResult<List<String>> =
        post(
            "/api/models/discover",
            JSONObject().apply { if (provider.isNotBlank()) put("provider", provider) },
        ) { body ->
            val o = JSONObject(body)
            // 内核失败时也是 200，用 ok:false + error 表达
            if (!o.optBoolean("ok", true)) {
                throw IllegalStateException(o.optString("error").ifEmpty { "探测失败" })
            }
            val arr = o.optJSONArray("models")
                ?: o.optJSONArray("items")
                ?: org.json.JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONObject(i)
                e?.optString("id")?.ifEmpty { null } ?: arr.optString(i).ifEmpty { null }
            }
        }

    /**
     * POST /api/models/save —— 新增/更新一条模型（按 id 判重）。
     *
     * 与 `save_batch` 的区别：这个端点接受**完整字段**，包括
     * `ctx_in` / `ctx_out`（上下文与输出上限）、`vision` / `video`（三态多模态能力）、
     * `rpm`（客户端节流）。批量接口只带 id/name/provider，规格会丢。
     *
     * [vision] / [video] 传 null 表示**未声明**，不能传 false —— 内核把
     * 「未声明」和「明确不支持」分得很开：前者乐观发送、撞墙再记忆，后者直接拦。
     */
    suspend fun saveModelFull(
        id: String,
        name: String,
        providerId: String,
        ctxIn: Int = 0,
        ctxOut: Int = 0,
        vision: Boolean? = null,
        video: Boolean? = null,
        rpm: Int = 0,
        baseUrl: String = "",
        protocol: String = "",
    ): ApiResult<Unit> = post("/api/models/save", JSONObject().apply {
        put("id", id)
        put("name", name)
        put("provider_id", providerId)
        if (ctxIn > 0) put("ctx_in", ctxIn)
        if (ctxOut > 0) put("ctx_out", ctxOut)
        vision?.let { put("vision", it) }
        video?.let { put("video", it) }
        if (rpm > 0) put("rpm", rpm)
        if (baseUrl.isNotBlank()) put("base_url", baseUrl)
        if (protocol.isNotBlank()) put("protocol", protocol)
        put("inherit_key", true)
    }) { }

    /** POST /api/models/delete —— 从模型库移除一个模型。 */
    suspend fun deleteModel(id: String): ApiResult<Unit> =
        post("/api/models/delete", JSONObject().put("model", id)) { }

    /** POST /api/models/save_batch：批量增改（壳侧一次提交一条）。 */
    suspend fun saveModel(id: String, displayName: String, provider: String): ApiResult<Unit> =
        post(
            "/api/models/save_batch",
            org.json.JSONArray().put(
                JSONObject().put("id", id).put("display_name", displayName).put("provider", provider),
            ),
        ) { }

    // ---------------------------------------------------------------- 归档 / diff

    /** GET /api/sessions?archived=1：已归档会话（侧栏折叠组）。 */
    suspend fun listArchivedSessions(): ApiResult<List<app.codeforge.model.SessionItem>> =
        get("/api/sessions?archived=1") { body ->
            val arr = JSONObject(body).optJSONArray("items") ?: org.json.JSONArray(body)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                app.codeforge.model.SessionItem(
                    id = o.optString("id"),
                    title = o.optString("title").ifEmpty { "已归档会话" },
                    updatedAt = o.optLong("updated_at", 0L),
                    busy = o.optBoolean("busy", false),
                )
            }.filter { it.id.isNotEmpty() }
        }

    /** GET /api/diff?session=&call_id=：历史工具卡的 diff/output 回放。 */
    suspend fun fetchToolReplay(sessionId: String, callId: String): ApiResult<Pair<String?, String?>> =
        get("/api/diff?session=$sessionId&call_id=$callId") { body ->
            val o = JSONObject(body)
            val diff = o.optString("diff").takeIf { it.isNotEmpty() && it != "null" }
            val output = o.optString("output").takeIf { it.isNotEmpty() && it != "null" }
            if (diff == null && output == null) error("内核没有该调用的回放数据")
            diff to output
        }

    // ---------------------------------------------------------------- 诊断 / 记忆 / 技能 / 插件

    /** GET /api/health：原始 JSON 美化后给诊断页。 */
    suspend fun getHealth(): ApiResult<String> =
        get("/api/health") { body ->
            runCatching { JSONObject(body).toString(2) }.getOrDefault(body)
        }

    /** GET /api/memory：记忆条目（字符串数组或 {items:[{id,content}]} 双形态）。 */
    suspend fun listMemories(): ApiResult<List<Pair<String, String>>> =
        get("/api/memory") { body ->
            val arr = JSONObject(body).optJSONArray("items") ?: org.json.JSONArray(body)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i)
                if (o != null) {
                    val id = o.optString("id").ifEmpty { i.toString() }
                    val content = o.optString("content").ifEmpty { o.optString("text") }
                    id to content
                } else {
                    i.toString() to arr.optString(i)
                }
            }
        }

    suspend fun deleteMemory(id: String): ApiResult<Unit> =
        request("DELETE", "/api/memory?id=$id", null) { }

    /** GET /api/skills：技能列表（{items:[{name,description}]} 或字符串数组）。 */
    suspend fun listSkills(): ApiResult<List<Pair<String, String>>> =
        get("/api/skills") { body ->
            val arr = JSONObject(body).optJSONArray("items") ?: org.json.JSONArray(body)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i)
                if (o != null) {
                    o.optString("name").ifEmpty { o.optString("id") } to o.optString("description")
                } else {
                    arr.optString(i).ifEmpty { null } to ""
                }
            }
        }

    /** GET /api/plugins：MCP 插件（{items:[{id,name,enabled}]}）。 */
    /**
     * 一个 MCP 插件的状态。
     *
     * [configured] 与 [loaded] 必须分开看：
     * 内核 GET 返回 `enabled = configured && loaded`、`configured = 纯配置态`。
     * 停用后插件进程被卸载 → loaded=false → enabled=false，开关会「弹回」。
     * 若 UI 只读 enabled，用户点开开关、加载又失败（比如 npx 不存在），
     * 界面永远显示「关」，看起来就是「打不开」。
     */
    data class McpPluginInfo(
        val id: String,
        val name: String,
        val configured: Boolean,
        val loaded: Boolean,
    ) {
        /** 运行态：进程真的起来了（工具已注册）。 */
        val running: Boolean get() = configured && loaded
    }

    suspend fun listPlugins(): ApiResult<List<McpPluginInfo>> =
        get("/api/plugins") { body ->
            val arr = JSONObject(body).optJSONArray("items") ?: org.json.JSONArray(body)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                // 内核下发的字段名是 name（没有 id），display_name 只是文案
                val id = o.optString("id").ifEmpty { o.optString("name") }
                if (id.isEmpty()) {
                    null
                } else {
                    McpPluginInfo(
                        id = id,
                        name = o.optString("display_name").ifEmpty { id },
                        configured = o.optBoolean("configured", o.optBoolean("enabled", false)),
                        loaded = o.optBoolean("enabled", false),
                    )
                }
            }
        }

    /** POST /api/plugins：开关单个插件。 */
    /**
     * 启停一个插件。
     *
     * ⚠️ 必须走 **PATCH** 且字段名是 `name`：内核 `/api/plugins` 的 POST 分支是
     * 「添加插件」（要求 name + type + command/endpoint），拿 POST 发 {id,enabled}
     * 会撞上「名称不能为空」直接失败 —— 之前 MCP 开关点了一点反应都没有就是这个。
     */
    suspend fun setPlugin(id: String, enabled: Boolean): ApiResult<Unit> =
        request("PATCH", "/api/plugins", JSONObject().put("name", id).put("enabled", enabled)) { }

    /**
     * POST /api/plugins —— 添加一个 MCP 插件。
     *
     * [type] 取 `mcp`（stdio，需 [command]）或 `mcp-http`（远程，需 [endpoint]）。
     * 内核会做增量热加载：只拉起新增插件，不重启其他运行中的 MCP。
     */
    suspend fun addMcpPlugin(
        name: String,
        displayName: String,
        type: String,
        command: String,
        args: List<String>,
        endpoint: String,
        description: String,
    ): ApiResult<Unit> = post("/api/plugins", JSONObject().apply {
        put("name", name)
        put("display_name", displayName)
        put("type", type)
        if (command.isNotBlank()) put("command", command)
        if (args.isNotEmpty()) put("args", org.json.JSONArray(args))
        if (endpoint.isNotBlank()) put("endpoint", endpoint)
        if (description.isNotBlank()) put("description", description)
    }) { }

    /** DELETE /api/plugins?name= —— 删除一个 MCP 插件。 */
    suspend fun deleteMcpPlugin(name: String): ApiResult<Unit> =
        request("DELETE", "/api/plugins?name=$name", null) { }

    /** GET /api/builtin-plugins：内置功能开关（plan/goal/vuln/reverse）。 */
    suspend fun listBuiltinPlugins(): ApiResult<List<Pair<String, Boolean>>> =
        get("/api/builtin-plugins") { body ->
            val arr = JSONObject(body).optJSONArray("items") ?: org.json.JSONArray(body)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").ifEmpty { o.optString("name") }
                if (id.isEmpty()) null else id to o.optBoolean("enabled", false)
            }
        }

    /** POST /api/builtin-plugins：开关内置功能。 */
    suspend fun setBuiltinPlugin(id: String, enabled: Boolean): ApiResult<Unit> =
        post("/api/builtin-plugins", JSONObject().put("id", id).put("enabled", enabled)) { }

    /** GET /api/workspaces：工作区列表（分组用；形态未定，双形态解析）。 */
    suspend fun listWorkspaces(): ApiResult<List<Pair<String, String>>> =
        get("/api/workspaces") { body ->
            val arr = JSONObject(body).optJSONArray("items") ?: org.json.JSONArray(body)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i)
                if (o != null) {
                    o.optString("id").ifEmpty { o.optString("path") } to o.optString("name").ifEmpty { o.optString("path") }
                } else {
                    arr.optString(i).ifEmpty { null }?.let { it to it }
                }
            }
        }

    // ---------------------------------------------------------------- 通用

    private suspend fun <T> get(path: String, parse: (String) -> T): ApiResult<T> =
        request("GET", path, null, parse)

    private suspend fun <T> post(path: String, body: JSONObject, parse: (String) -> T): ApiResult<T> =
        request("POST", path, body, parse)

    /** 批量端点（save_batch 等）要数组体：body 实际只 toString，加一个数组重载即可。 */
    private suspend fun <T> post(path: String, body: org.json.JSONArray, parse: (String) -> T): ApiResult<T> =
        request("POST", path, body, parse)

    private suspend fun <T> request(
        method: String,
        path: String,
        body: Any?,
        parse: (String) -> T,
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        if (!handshaked) {
            (init() as? ApiResult.Err)?.let { return@withContext it as ApiResult<T> }
        }
        runCatching {
            val builder = Request.Builder().url(base + path)
            when (method) {
                "GET" -> builder.get()
                else -> builder.method(
                    method,
                    (body?.toString() ?: "").toRequestBody(
                        okhttp3.MediaType.Companion.run { "application/json".toMediaType() },
                    ),
                )
            }
            client.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext ApiResult.Err("HTTP ${resp.code}：${text.take(120)}")
                }
                ApiResult.Ok(parse(text))
            }
        }.getOrElse { ApiResult.Err(it.message ?: it.javaClass.simpleName) }
    }

    /** 与 HttpCoreTransport 同款：只服务一个 host，不需要持久化。 */
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
