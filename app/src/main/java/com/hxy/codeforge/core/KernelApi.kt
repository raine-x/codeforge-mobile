package com.hxy.codeforge.core

import com.hxy.codeforge.model.BackgroundTask
import com.hxy.codeforge.model.BuiltinPluginInfo
import com.hxy.codeforge.model.KernelConfigSummary
import com.hxy.codeforge.model.SkillInfo
import com.hxy.codeforge.model.SubagentPolicy
import com.hxy.codeforge.model.TreeEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
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
                // 内核专门给「模型库被清空、生效配置还在」这一种情况准备的信号
                modelInLibrary = o.optBoolean("model_in_library", true),
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

    // ---------------------------------------------------------------- 后台任务

    /**
     * GET /api/tasks：全部后台脚本任务（含最近输出尾部）。
     *
     * 任务归内核进程，不归会话 —— 页面关掉脚本照跑，重开 App 也能拉回列表。
     */
    suspend fun listTasks(): ApiResult<List<BackgroundTask>> =
        get("/api/tasks") { body ->
            val arr = JSONObject(body).optJSONArray("items") ?: org.json.JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                if (id.isBlank()) {
                    null
                } else {
                    BackgroundTask(
                        id = id,
                        label = o.optString("label"),
                        command = o.optString("command"),
                        cwd = o.optString("cwd"),
                        status = o.optString("status", "running"),
                        exitCode = o.optInt("exit_code", 0),
                        startedAt = o.optLong("started_at", 0),
                        endedAt = o.optLong("ended_at", 0),
                        output = o.optString("output"),
                    )
                }
            }
        }

    /**
     * POST /api/tasks/start：手动启动后台脚本。返回任务 id。
     *
     * [cwd] 留空 = 内核当前工作区根；内核会做工作区围栏校验。
     */
    suspend fun startTask(command: String, label: String = "", cwd: String = ""): ApiResult<String> =
        post("/api/tasks/start", JSONObject().apply {
            put("command", command)
            if (label.isNotBlank()) put("label", label)
            if (cwd.isNotBlank()) put("cwd", cwd)
        }) { body ->
            JSONObject(body).optJSONObject("task")?.optString("id").orEmpty()
                .ifEmpty { error("内核未返回任务 id") }
        }

    /** POST /api/tasks/kill：取消运行中的任务（连同子进程整组杀掉）。 */
    suspend fun killTask(id: String): ApiResult<Unit> =
        post("/api/tasks/kill", JSONObject().put("id", id)) { }

    // ---------------------------------------------------------------- 权限

    /** POST /api/perm：readonly / ask / auto 三档，热生效。 */
    suspend fun setPerm(mode: String): ApiResult<Unit> =
        post("/api/perm", JSONObject().put("mode", mode)) { }

    // ---------------------------------------------------------------- 附件

    /**
     * POST /api/upload_file（multipart）：把本地文件原子写入**内核当前工作区**的
     * `attachments/`，返回相对工作区路径（staged_path）。
     *
     * 为什么不让壳侧直接猜落点：工作区在会话创建那一刻才固定（还可能是项目目录），
     * 壳侧按会话 id 拼出来的目录与内核读附件用的 `FS.root()` 不是同一棵 ——
     * 这正是「发送后图片转圈、路径不对」的根因（问题 1）。交给内核落盘，
     * 写入方与读取方天然同一个根。
     */
    suspend fun uploadFile(file: java.io.File): ApiResult<String> = withContext(Dispatchers.IO) {
        if (!handshaked) {
            (init() as? ApiResult.Err)?.let { return@withContext it as ApiResult<String> }
        }
        runCatching {
            val body = okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    file.name,
                    file.asRequestBody(
                        okhttp3.MediaType.Companion.run { "application/octet-stream".toMediaType() },
                    ),
                )
                .build()
            client.newCall(Request.Builder().url("$base/api/upload_file").post(body).build())
                .execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        return@withContext ApiResult.Err("HTTP ${resp.code}：${text.take(120)}")
                    }
                    val staged = JSONObject(text).optString("staged_path")
                    if (staged.isBlank()) {
                        return@withContext ApiResult.Err("内核未返回附件路径")
                    }
                    ApiResult.Ok(staged)
                }
        }.getOrElse { ApiResult.Err(it.message ?: it.javaClass.simpleName) }
    }

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

    /**
     * GET /api/workspace —— 内核**当前**的工作区根（`{root}`）。
     *
     * 这是内核扫技能的目录（`<root>/.codeforge/skills/`）。壳侧不能自己猜：
     * 内核启动时从 state.yaml 恢复上次的工作区，壳侧此刻可能还没发过
     * `setWorkspace`，两者会对不上。铺内置技能必须以这里的 root 为准。
     */
    suspend fun currentWorkspace(): ApiResult<String> =
        get("/api/workspace") { body ->
            JSONObject(body).optString("root")
        }

    // ---------------------------------------------------------------- 模型库 / 供应商 / 思考规格
    // 以下端点的响应形态按「{items:[…]} 或裸数组」双形态防御解析；字段名以
    // models 命令行实测为准，联调期允许显示降级（Err 落到界面可见处）。

    data class ModelEntry(
        val id: String,
        val name: String,
        /** 归属供应商 id（内核下发的键是 `provider_id`；空 = 未归属/旧格式自带连接）。 */
        val provider: String,
        val badge: String = "",
        /** 供应商显示名（`provider_name`，内核已拼好，界面不必再拉供应商表）。 */
        val providerName: String = "",
        val ctxIn: Int = 0,
        val ctxOut: Int = 0,
        /** 三态：null = 未声明（≠ 不支持，见 [saveModelFull] 注释）。 */
        val vision: Boolean? = null,
        val video: Boolean? = null,
        val rpm: Int = 0,
        val keySource: String = "",
        val keyName: String = "",
        val keySet: Boolean = false,
        val baseUrl: String = "",
        val protocol: String = "",
    )

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
                if (id.isEmpty()) {
                    null
                } else {
                    ModelEntry(
                        id = id,
                        name = o.optString("display_name").ifEmpty { o.optString("name").ifEmpty { id } },
                        // 内核 models[] 的归属键是 provider_id（provider_name 只是显示名）。
                        // 旧解析读的 `provider` 键内核从不下发 → 分组与回填永远是空。
                        provider = o.optString("provider_id").ifEmpty { o.optString("provider") },
                        badge = o.optString("badge"),
                        providerName = o.optString("provider_name"),
                        ctxIn = o.optInt("ctx_in", 0),
                        ctxOut = o.optInt("ctx_out", 0),
                        vision = triState(o, "vision"),
                        video = triState(o, "video"),
                        rpm = o.optInt("rpm", 0),
                        keySource = o.optString("key_source"),
                        keyName = o.optString("key_name"),
                        keySet = o.optBoolean("key_set", false),
                        baseUrl = o.optString("base_url"),
                        protocol = o.optString("protocol"),
                    )
                }
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
        /**
         * 供应商自己的明文密钥（内核 `/api/providers/list` **每个供应商都回填**）。
         *
         * ## 为什么必须留着
         *
         * 内核的 `applyProviderDefaults` 只在「请求里既没有变量名也没有明文」时才继承
         * 供应商；供应商**自己没有密钥**时，`handleModelDiscover` 会回退到
         * **当前生效模型**的密钥（响应里的 `key_from_active`）——
         * 于是「探测供应商 A」实际用了模型 B 的密钥，界面却看不出来。
         *
         * 拿到这份明文，探测时就能把它带上，绕开那次回退。
         */
        val keyPlain: String = "",
    )

    /**
     * GET /api/providers/list 的完整字段版。 */
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
                    keyPlain = o.optString("key_plain"),
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
    /**
     * POST /api/models/test —— 连通性测试（**所见即所测**）。
     *
     * ## 字段名必须与内核的 `modelTestReq` 一致
     *
     * 内核的请求体是 `provider_id / protocol / base_url / model /
     * key_source / key_name / key_value`，**没有 `id` 字段**。
     * Go 的 JSON 解码器**静默忽略未知字段** —— 早先这里发的是 `{"id": …}`，
     * 内核收到的是一片空：模型 id 空、地址空、密钥空，于是每次都回
     * 「API Key 为空」。按钮看着能点，实际从来没测过任何东西。
     *
     * @param model 模型 id（要原样传给上游的 model 字段）。
     * @param providerId 归属供应商 id；给了它，内核就用**该供应商**的地址/协议/密钥。
     * @param keyValue 供应商的明文密钥。带上它就不必依赖内核进程的环境变量，
     *        也不会回退到「当前生效模型」的密钥。
     */
    suspend fun testModel(
        model: String,
        providerId: String = "",
        baseUrl: String = "",
        protocol: String = "",
        keyValue: String = "",
    ): ApiResult<String> =
        post("/api/models/test", JSONObject().apply {
            put("model", model)
            if (providerId.isNotBlank()) put("provider_id", providerId)
            if (baseUrl.isNotBlank()) put("base_url", baseUrl)
            if (protocol.isNotBlank()) put("protocol", protocol)
            if (keyValue.isNotBlank()) {
                put("key_source", "plain")
                put("key_value", keyValue)
            }
        }) { body ->
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
     *
     * ## 为什么这里要能带上密钥
     *
     * 内核的 `handleModelDiscover` 在「请求里没有可用密钥」时会**回退到当前生效模型的
     * 密钥**（`s.cfg.LLM.APIKey`），并用 `key_from_active` 如实回报。
     * 供应商自己没配密钥时，那次回退就会让「探测 A 供应商」实际用上 B 模型的密钥。
     * 把供应商的明文密钥带上，就绕开了这��回退。
     */
    suspend fun discoverModels(
        provider: String = "",
        baseUrl: String = "",
        protocol: String = "",
        keyName: String = "",
        keyValue: String = "",
    ): ApiResult<DiscoverResult> =
        post(
            "/api/models/discover",
            JSONObject().apply {
                if (provider.isNotBlank()) put("provider_id", provider)
                if (baseUrl.isNotBlank()) put("base_url", baseUrl)
                if (protocol.isNotBlank()) put("protocol", protocol)
                // 内核的 modelTestReq 用 key_source 区分「环境变量名」与「明文」。
                // 这里统一走明文：keyValue 是从内核拿到的**已解析**密钥，
                // 原样转交不会丢语义。
                if (keyName.isNotBlank()) {
                    put("key_source", "env")
                    put("key_name", keyName)
                } else if (keyValue.isNotBlank()) {
                    put("key_source", "plain")
                    put("key_value", keyValue)
                }
            },
        ) { body ->
            val o = JSONObject(body)
            // 内核失败时也是 200，用 ok:false + error 表达
            if (!o.optBoolean("ok", true)) {
                throw IllegalStateException(o.optString("error").ifEmpty { "探测失败" })
            }
            val arr = o.optJSONArray("models")
                ?: o.optJSONArray("items")
                ?: org.json.JSONArray()
            val models = (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONObject(i)
                e?.optString("id")?.ifEmpty { null } ?: arr.optString(i).ifEmpty { null }
            }
            DiscoverResult(
                models = models,
                // 内核用了「当前生效模型」的密钥 —— 必须让用户看见，
                // 否则「探测用的到底是谁的密钥」是黑箱。
                keyFromActive = o.optBoolean("key_from_active", false),
                endpoint = o.optString("endpoint"),
            )
        }

    /** POST /api/models/discover 的结果。 */
    data class DiscoverResult(
        val models: List<String>,
        /** 内核回退到了**当前生效模型**的密钥（该供应商自己没有独立密钥）。 */
        val keyFromActive: Boolean,
        /** 实际请求的上游地址（内核回报，便于核对是不是打到了预期网关）。 */
        val endpoint: String,
    )

    /**
     * POST /api/models/save —— 新增/更新一条模型（按 id 判重）。
     *
     * 与 `save_batch` 的区别：这个端点接受**完整字段**，包括
     * `ctx_in` / `ctx_out`（上下文与输出上限）、`vision` / `video`（三态多模态能力）、
     * `rpm`（客户端节流）、`key_source` / `key_name` / `key_value`（密钥覆盖）、
     * `inherit_key`（是否清掉条目上的密钥覆盖、改用供应商的）。
     * 批量接口只带 id/name/provider，规格会丢。
     *
     * [vision] / [video] 传 null 表示**未声明**，不能传 false —— 内核把
     * 「未声明」和「明确不支持」分得很开：前者乐观发送、撞墙再记忆，后者直接拦。
     *
     * 密钥三件套：[keySource] 取 `env`（用 [keyName] 指向的环境变量）或 `plain`
     * （用 [keyValue] 明文）；编辑时 [keyValue] 留空 = 不动库里的旧密钥。
     * 只要给了密钥字段，`inherit_key` 会被强制为 false —— 内核在 inherit_key=true
     * 时会把 [keyValue] 清掉（见 handleModelSave），声明继承与会手输密钥互斥。
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
        keySource: String = "",
        keyName: String = "",
        keyValue: String = "",
        inheritKey: Boolean = true,
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
        if (keySource.isNotBlank()) put("key_source", keySource)
        if (keyName.isNotBlank()) put("key_name", keyName)
        if (keyValue.isNotBlank()) put("key_value", keyValue)
        put(
            "inherit_key",
            inheritKey && keySource.isBlank() && keyName.isBlank() && keyValue.isBlank(),
        )
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

    /**
     * GET /api/sessions?archived=1：已归档会话（归档管理页）。
     *
     * 这个端点的条目同样带 workspace/workspace_name/archived_at，
     * 字段解析与 WS 的 sessions 帧保持一致（P-008 的教训：不跨帧类推键名）。
     */
    suspend fun listArchivedSessions(): ApiResult<List<com.hxy.codeforge.model.SessionItem>> =
        get("/api/sessions?archived=1") { body ->
            val arr = JSONObject(body).optJSONArray("items") ?: org.json.JSONArray(body)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                com.hxy.codeforge.model.SessionItem(
                    id = o.optString("id"),
                    title = o.optString("title").ifEmpty { "已归档会话" },
                    updatedAt = o.optLong("updated_at", 0L),
                    busy = o.optBoolean("busy", false),
                    workspace = o.optString("workspace"),
                    workspaceName = o.optString("workspace_name"),
                    archived = true,
                )
            }.filter { it.id.isNotEmpty() }
        }

    /**
     * POST /api/undo {session_id}：撤销该会话最近一次文件写入。
     * 内核按会话分桶、带字节预算与 spill；成功/失败都必须把 [message] 原样给用户。
     */
    data class UndoResult(
        val ok: Boolean,
        val path: String = "",
        val remaining: Int = 0,
        val message: String = "",
    )

    suspend fun undo(sessionId: String): ApiResult<UndoResult> =
        post("/api/undo", JSONObject().put("session_id", sessionId)) { body ->
            val o = JSONObject(body)
            UndoResult(
                ok = o.optBoolean("ok", false),
                path = o.optString("path"),
                remaining = o.optInt("remaining", 0),
                message = o.optString("message"),
            )
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

    /** POST /api/memory：新增一条用户记忆（按当前工作区隔离）。 */
    suspend fun addMemory(content: String): ApiResult<Unit> =
        post("/api/memory", JSONObject().put("content", content)) { }

    /** PATCH /api/memory：按数字 id 更新记忆内容。 */
    suspend fun updateMemory(id: String, content: String): ApiResult<Unit> =
        request(
            "PATCH",
            "/api/memory",
            JSONObject().put("id", id.toLongOrNull() ?: 0L).put("content", content),
        ) { }

    /**
     * GET /api/skills：技能列表（{items:[{name,display_name,description,triggers,enabled}]}）。
     *
     * 内核的 `name` 是 SKILL.md 目录名（= @ 补全 token），`display_name` 才是界面文案；
     * 旧解析只取 name/description，技能页因此看不到触发词与显示名。
     */
    suspend fun listSkills(): ApiResult<List<SkillInfo>> =
        get("/api/skills") { body ->
            val arr = JSONObject(body).optJSONArray("items") ?: org.json.JSONArray(body)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i)
                if (o == null) {
                    // 裸字符串数组形态：只能拿到 slug
                    val raw = arr.optString(i)
                    if (raw.isEmpty()) return@mapNotNull null
                    SkillInfo(slug = raw, name = raw)
                } else {
                    val slug = o.optString("name").ifEmpty { o.optString("id") }
                    if (slug.isEmpty()) {
                        null
                    } else {
                        SkillInfo(
                            slug = slug,
                            name = o.optString("display_name").ifEmpty { slug },
                            description = o.optString("description"),
                            triggers = jsonStringList(o.optJSONArray("triggers")),
                            enabled = o.optBoolean("enabled", true),
                        )
                    }
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
     * [env] 是子进程的环境变量（内核按 `map[string]string` 的 `env` 键接收，
     * 见 handlePlugins 的 POST 分支），空列表时不写这个键。
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
        env: List<Pair<String, String>> = emptyList(),
    ): ApiResult<Unit> = post("/api/plugins", JSONObject().apply {
        put("name", name)
        put("display_name", displayName)
        put("type", type)
        if (command.isNotBlank()) put("command", command)
        if (args.isNotEmpty()) put("args", org.json.JSONArray(args))
        if (endpoint.isNotBlank()) put("endpoint", endpoint)
        if (description.isNotBlank()) put("description", description)
        if (env.isNotEmpty()) {
            put("env", JSONObject().apply { env.forEach { (k, v) -> put(k, v) } })
        }
    }) { }

    /** DELETE /api/plugins?name= —— 删除一个 MCP 插件。 */
    suspend fun deleteMcpPlugin(name: String): ApiResult<Unit> =
        request("DELETE", "/api/plugins?name=$name", null) { }

    /**
     * GET /api/builtin-plugins：内置功能（{items:[{id,name,purpose,when_to_use,enabled}]}）。
     *
     * 内核同时下发 `purpose`（做什么）与 `when_to_use`（何时该开），
     * 设置页的说明文案直接来自这里，不必在壳侧另编一份映射表。
     */
    suspend fun listBuiltinPlugins(): ApiResult<List<BuiltinPluginInfo>> =
        get("/api/builtin-plugins") { body ->
            val arr = JSONObject(body).optJSONArray("items") ?: org.json.JSONArray(body)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").ifEmpty { o.optString("name") }
                if (id.isEmpty()) {
                    null
                } else {
                    BuiltinPluginInfo(
                        id = id,
                        name = o.optString("name").ifEmpty { id },
                        purpose = o.optString("purpose"),
                        whenToUse = o.optString("when_to_use"),
                        enabled = o.optBoolean("enabled", false),
                    )
                }
            }
        }

    /** POST /api/builtin-plugins：开关内置功能。 */
    // 内置插件启停是 PATCH（同 P-025）：POST 在该端点没有定义，会被 405 拒掉，
    // 表现为「开关点了没反应」。
    suspend fun setBuiltinPlugin(id: String, enabled: Boolean): ApiResult<Unit> =
        request("PATCH", "/api/builtin-plugins", JSONObject().put("id", id).put("enabled", enabled)) { }

    /** 项目（工作区）条目：GET /api/workspaces 的完整结构。 */
    data class WorkspaceEntry(
        val workspace: String,
        val name: String = "",
        val count: Int = 0,
        val archived: Boolean = false,
    )

    /**
     * GET /api/workspaces —— 项目列表。
     *
     * 内核当前实际返回 `{items: ["<路径>", …]}`（纯字符串数组，见
     * history.ListWorkspaces），未来若升级成对象数组要能直接吃下，
     * 所以两种形态都解析（P-008：契约按写方代码核对，不按相似端点类推）。
     */
    suspend fun listWorkspaces(): ApiResult<List<WorkspaceEntry>> =
        get("/api/workspaces") { body ->
            val arr = JSONObject(body).optJSONArray("items") ?: org.json.JSONArray(body)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i)
                if (o != null) {
                    val ws = o.optString("workspace")
                        .ifEmpty { o.optString("path").ifEmpty { o.optString("id") } }
                    if (ws.isEmpty() && !o.has("name")) {
                        null
                    } else {
                        WorkspaceEntry(
                            workspace = ws,
                            name = o.optString("name").ifEmpty { dirName(ws) },
                            count = o.optInt("count", o.optInt("session_count", 0)),
                            archived = o.optBoolean("archived", false),
                        )
                    }
                } else {
                    // 纯字符串数组形态：拿不到显示名，回落路径末段（界面同款兜底）
                    val ws = arr.optString(i)
                    if (ws.isEmpty()) null else WorkspaceEntry(workspace = ws, name = dirName(ws))
                }
            }
        }

    /**
     * PATCH /api/workspaces —— 重命名（只改显示名）/ 归档整组。
     *
     * `archive` 在内核是 `*bool`：显式传 false 才是「恢复整组」。
     * [archive] 传 null 表示不改归档态；[newName] 传 null 表示不改名。
     */
    suspend fun patchWorkspace(
        workspace: String,
        newName: String? = null,
        archive: Boolean? = null,
    ): ApiResult<Unit> =
        request("PATCH", "/api/workspaces", JSONObject().apply {
            put("workspace", workspace)
            newName?.let { put("new_name", it) }
            archive?.let { put("archive", it) }
        }) { }

    /** DELETE /api/workspaces?workspace= —— 删除整个项目及其全部会话。 */
    suspend fun deleteWorkspace(workspace: String): ApiResult<Unit> =
        request("DELETE", "/api/workspaces?workspace=${encode(workspace)}", null) { }

    /** GET /api/tree 的响应：root/path/parent/items + 读取错误（权限等）。 */
    data class TreeResult(
        val root: String = "",
        val path: String = "",
        val parent: String = "",
        val items: List<TreeEntry> = emptyList(),
        val error: String? = null,
    )

    /**
     * GET /api/tree —— 工作区文件树（懒加载）。
     *
     * [path] 空 = 工作区根；绝对路径必须落在工作区内，除非 [picker]（选工作区
     * 的目录选择器要能浏览区外）。`read_error` 与「空目录」必须分开报：
     * 安卓未授权存储时目录 stat 成功但读不出内容，吞掉错误会渲染成一个假的空目录。
     */
    suspend fun listTree(path: String = "", depth: Int = 1, picker: Boolean = false): ApiResult<TreeResult> {
        val q = buildString {
            append("/api/tree?depth=").append(depth.coerceIn(1, 5))
            if (path.isNotBlank()) append("&path=").append(encode(path))
            if (picker) append("&picker=1")
        }
        return get(q) { body ->
            val o = JSONObject(body)
            val arr = o.optJSONArray("items") ?: org.json.JSONArray()
            TreeResult(
                root = o.optString("root"),
                path = o.optString("path"),
                parent = o.optString("parent"),
                items = (0 until arr.length()).mapNotNull { i ->
                    val e = arr.optJSONObject(i) ?: return@mapNotNull null
                    TreeEntry(
                        name = e.optString("name"),
                        path = e.optString("path"),
                        isDir = e.optBoolean("is_dir", false),
                        size = e.optLong("size", 0L),
                    )
                },
                error = o.optString("read_error").ifEmpty { null },
            )
        }
    }

    /** 路径末段（项目默认显示名）；空串回落空串，由调用方决定占位文案。 */
    private fun dirName(path: String): String =
        path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')

    /**
     * 三态布尔：键缺失或为 null → null（未声明），否则取真值。
     *
     * `optBoolean(key, false)` 会把「没写这个字段」读成 false —— 对 vision/video
     * 这类三态字段，那就等于替用户声明了「不支持图片」，图片从此收不到（P-027 同源）。
     */
    private fun triState(o: JSONObject, key: String): Boolean? =
        if (!o.has(key) || o.isNull(key)) null else o.optBoolean(key)

    /** JSON 字符串数组 → List<String>；null 或空数组给空列表。 */
    private fun jsonStringList(arr: org.json.JSONArray?): List<String> =
        if (arr == null) {
            emptyList()
        } else {
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
        }

    /** query 参数必须编码：工作区是绝对路径，含 `/`、`: `、中文与空格。 */
    private fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

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
