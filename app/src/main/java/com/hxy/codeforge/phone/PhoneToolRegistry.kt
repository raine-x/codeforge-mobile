package com.hxy.codeforge.phone

import org.json.JSONArray
import org.json.JSONObject

/**
 * 手机操控 MCP 工具契约声明（纯 JSON Schema，可在 JVM 单测中直接核验）。
 *
 * 共 20 个高内聚工具，覆盖屏幕观察、页面变化等待、界面交互、同页批量动作链、应用管理、应用技能库、系统设置、
 * 截图、视觉分析、录屏、录音、通知管理、文件媒体、日历闹钟、通讯分享、设备状态与任务控制。
 */
object PhoneToolRegistry {

    data class ToolDef(
        val name: String,
        val description: String,
        val requiredCapability: PhoneCapability?,
        val schema: JSONObject,
    )

    private fun prop(type: String, desc: String, enumValues: List<String>? = null): JSONObject =
        JSONObject().apply {
            put("type", type)
            put("description", desc)
            if (enumValues != null) {
                put("enum", JSONArray(enumValues))
            }
        }

    private fun arrayProp(desc: String): JSONObject = JSONObject().apply {
        put("type", "array")
        put("description", desc)
        put("items", JSONObject().put("type", "string"))
    }

    private fun schema(
        props: Map<String, JSONObject>,
        required: List<String> = emptyList(),
    ): JSONObject = JSONObject().apply {
        put("type", "object")
        put(
            "properties",
            JSONObject().apply {
                props.forEach { (k, v) -> put(k, v) }
            },
        )
        if (required.isNotEmpty()) {
            put("required", JSONArray(required))
        }
    }

    val tools: List<ToolDef> = listOf(
        ToolDef(
            name = "screen_observe",
            description = "读取当前前台应用包名/名称、窗口分辨率与可见控件树（返回 ref、中心坐标 [cx,cy]、文本、描述、resource_id 及可点击/可编辑/可滚动/选中状态）。" +
                "操作屏幕前应优先调用此工具；当控件树信息不足或界面为图片/画布时可传 include_screenshot=true 并结合 analyze=true。",
            requiredCapability = PhoneCapability.SCREEN_OBSERVE,
            schema = schema(
                props = mapOf(
                    "max_nodes" to prop("integer", "最大返回控件数量，默认 120，范围 20~240"),
                    "include_screenshot" to prop("boolean", "是否同时截取当前屏幕并保存 80% 精度 JPEG 截图"),
                    "analyze" to prop("boolean", "include_screenshot=true 时是否顺便调用视觉分析"),
                    "question" to prop("string", "传给视觉分析的任务问题"),
                ),
            ),
        ),
        ToolDef(
            name = "screen_wait_change",
            description = "等待当前屏幕发生结构变化、指定文字出现或切换到目标应用包名，常用于点击或启动应用后的结果核验。",
            requiredCapability = PhoneCapability.SCREEN_OBSERVE,
            schema = schema(
                props = mapOf(
                    "timeout_ms" to prop("integer", "最大等待毫秒数，默认 5000，最大 25000"),
                    "interval_ms" to prop("integer", "轮询间隔毫秒数，默认 350"),
                    "expect_text" to prop("string", "期望屏幕控件中出现的文字（包含匹配）"),
                    "expect_package" to prop("string", "期望前台切换到的应用包名"),
                ),
            ),
        ),
        ToolDef(
            name = "ui_click",
            description = "对屏幕目标执行点击、双击或长按。优先按 ref（来自 screen_observe）、text、content_desc、resource_id 定位，也可直接传像素坐标 x, y。" +
                "内置随机拟人延时与无障碍点击失效自动回退真实触摸手势；传 observe_after=true 可在点击稳定后直接附带最新屏幕控件树。",
            requiredCapability = PhoneCapability.UI_ACTION,
            schema = schema(
                props = mapOf(
                    "action" to prop("string", "动作类型，默认 click", listOf("click", "double_click", "long_press")),
                    "ref" to prop("string", "screen_observe 返回的控件引用，如 n5"),
                    "text" to prop("string", "按控件可见文本匹配（包含忽略大小写）"),
                    "content_desc" to prop("string", "按控件 contentDescription 匹配"),
                    "resource_id" to prop("string", "按控件 resourceId 匹配"),
                    "index" to prop("integer", "匹配到多个控件时的下标（从 0 开始）"),
                    "x" to prop("number", "直接点击的屏幕像素 X 坐标"),
                    "y" to prop("number", "直接点击的屏幕像素 Y 坐标"),
                    "prefer_gesture" to prop("boolean", "是否跳过无障碍 ACTION_CLICK 直接派发真实坐标触摸手势（适合电商筛选标签/自定义控件）"),
                    "observe_after" to prop("boolean", "点击并等待界面稳定后，是否在本次回包中直接返回最新屏幕控件树"),
                ),
            ),
        ),
        ToolDef(
            name = "ui_gesture",
            description = "在屏幕上执行滑动（swipe）、拖拽（drag）或列表滚动（scroll）。支持方向快捷滑动或精确起止坐标。",
            requiredCapability = PhoneCapability.UI_ACTION,
            schema = schema(
                props = mapOf(
                    "action" to prop("string", "手势类型，默认 swipe", listOf("swipe", "drag", "scroll")),
                    "direction" to prop("string", "滚动或滑动方向", listOf("up", "down", "left", "right")),
                    "ref" to prop("string", "目标控件引用（scroll 时可指定可滚动容器，drag 时可作起点）"),
                    "start_x" to prop("number", "起点 X 坐标"),
                    "start_y" to prop("number", "起点 Y 坐标"),
                    "end_x" to prop("number", "终点 X 坐标"),
                    "end_y" to prop("number", "终点 Y 坐标"),
                    "duration_ms" to prop("integer", "手势持续时间（毫秒），swipe 默认 350，drag 默认 800"),
                    "observe_after" to prop("boolean", "滑动并稳定后是否直接返回最新屏幕控件树"),
                ),
            ),
        ),
        ToolDef(
            name = "ui_input",
            description = "对输入框执行输入文本、追加、清空、全选、区间选区、复制、粘贴或提交回车（IME Enter）。" +
                "注意本工具运行环境不自带软键盘：若控件支持 set_text 会直接写入；若控件不响应 set_text，会自动改用设置系统剪贴板并粘贴（paste），" +
                "亦可显式传 action=paste（若仍未生效，文本已在剪贴板，可长按输入框并点击弹出的「粘贴」）。",
            requiredCapability = PhoneCapability.UI_ACTION,
            schema = schema(
                props = mapOf(
                    "action" to prop(
                        "string",
                        "输入动作",
                        listOf("set_text", "append", "clear", "select_all", "select_range", "copy", "paste", "submit"),
                    ),
                    "text" to prop("string", "要输入、追加或写入剪贴板粘贴的文本"),
                    "ref" to prop("string", "目标输入框控件引用，可省略以自动查找聚焦输入框"),
                    "resource_id" to prop("string", "按 resourceId 定位输入框"),
                    "hint" to prop("string", "按文本或描述定位输入框"),
                    "start" to prop("integer", "select_range 起始字符索引"),
                    "end" to prop("integer", "select_range 结束字符索引"),
                    "observe_after" to prop("boolean", "输入操作完成后是否直接返回最新屏幕控件树"),
                ),
                required = listOf("action"),
            ),
        ),
        ToolDef(
            name = "ui_batch",
            description = "在同一页面内连续执行多步确定性界面动作（如「点击搜索框 → 输入关键词 → 点击搜索」或「勾选筛选条件 16+512G → 点击确定」）。" +
                "每步之间自动执行动态 UI 稳定等待与随机拟人延时，支持步前 wait_for_text 与步后 expect_selected / expect_text 校验；" +
                "一旦任意一步未生效、目标未出现或意外跨应用跳页，立即熔断中止后续步骤并返回最新控件树，绝不盲目点击下一步。",
            requiredCapability = PhoneCapability.UI_ACTION,
            schema = schema(
                props = mapOf(
                    "steps" to JSONObject().apply {
                        put("type", "array")
                        put("description", "按顺序执行的同页动作列表（1~10 步），每项可包含 type(click/input/gesture/wait/global_action)、ref、text、content_desc、resource_id、x、y、action、direction、prefer_gesture、wait_for_text、expect_text、expect_selected")
                        put(
                            "items",
                            schema(
                                props = mapOf(
                                    "type" to prop("string", "步骤类型", listOf("click", "input", "gesture", "wait", "global_action")),
                                    "action" to prop("string", "子动作（如 click/double_click/long_press，input 的 set_text/paste/submit，gesture 的 swipe/scroll）"),
                                    "ref" to prop("string", "目标控件 ref"),
                                    "text" to prop("string", "click 时为匹配文本；input 时为输入内容"),
                                    "hint" to prop("string", "input 时按提示词定位输入框"),
                                    "content_desc" to prop("string", "按 contentDescription 匹配"),
                                    "resource_id" to prop("string", "按 resourceId 匹配"),
                                    "index" to prop("integer", "同名控件下标"),
                                    "x" to prop("number", "像素 X 坐标"),
                                    "y" to prop("number", "像素 Y 坐标"),
                                    "direction" to prop("string", "gesture 方向", listOf("up", "down", "left", "right")),
                                    "duration_ms" to prop("integer", "wait 或 gesture 的毫秒数"),
                                    "prefer_gesture" to prop("boolean", "click 时是否强制使用真实坐标触摸手势"),
                                    "wait_for_text" to prop("string", "执行本步前必须先等待屏幕出现该文本且控件可用"),
                                    "wait_timeout_ms" to prop("integer", "wait_for_text 超时时间，默认 2500"),
                                    "expect_text" to prop("string", "本步执行后屏幕必须出现的文本，否则立即熔断中止后续步骤"),
                                    "expect_selected" to prop("boolean", "本步点击筛选标签后是否校验其变为选中/勾选态，未选中则自动重按或熔断"),
                                ),
                                required = listOf("type"),
                            ),
                        )
                    },
                    "stay_in_package" to prop("boolean", "是否要求全程留在起始应用包名内（默认 true，发生跨应用跳页立即截断）"),
                    "settle_timeout_ms" to prop("integer", "每步之后等待 UI 稳定的最大毫秒数，默认 850"),
                    "observe_after" to prop("boolean", "批次结束或熔断时是否附带最新屏幕控件树，默认 true"),
                ),
                required = listOf("steps"),
            ),
        ),
        ToolDef(
            name = "ui_global_action",
            description = "执行系统级全局导航动作：返回、回到桌面、打开最近任务、展开通知栏、快捷设置、电源菜单或锁屏。",
            requiredCapability = PhoneCapability.UI_ACTION,
            schema = schema(
                props = mapOf(
                    "action" to prop(
                        "string",
                        "全局导航动作",
                        listOf("back", "home", "recents", "notifications", "quick_settings", "power_dialog", "lock_screen"),
                    ),
                ),
                required = listOf("action"),
            ),
        ),
        ToolDef(
            name = "app_manage",
            description = "管理手机应用：列出已安装应用、按名称或包名查找、打开应用、切换应用或通过最近任务关闭应用（自动跳过黑名单应用）。",
            requiredCapability = PhoneCapability.APP_MANAGE,
            schema = schema(
                props = mapOf(
                    "action" to prop("string", "操作类型", listOf("list", "find", "launch", "switch", "close")),
                    "app_name" to prop("string", "应用显示名称或关键字"),
                    "package_name" to prop("string", "精确应用包名"),
                    "include_system" to prop("boolean", "list/find 时是否包含无桌面图标的系统组件，默认 false"),
                    "limit" to prop("integer", "最多返回条数，默认 60"),
                ),
                required = listOf("action"),
            ),
        ),
        ToolDef(
            name = "system_setting",
            description = "查询或调整系统设置：音量（volume）、亮度（brightness）、自动旋转（rotation）、勿扰（dnd）、Wi-Fi（wifi）、蓝牙（bluetooth）、热点（hotspot）、定位（location）。执行后会回读并返回真实状态。",
            requiredCapability = PhoneCapability.SYSTEM_SETTINGS,
            schema = schema(
                props = mapOf(
                    "setting" to prop(
                        "string",
                        "设置项名称",
                        listOf("volume", "brightness", "rotation", "dnd", "wifi", "bluetooth", "hotspot", "location"),
                    ),
                    "action" to prop("string", "动作：get（查询）、set（设置）、open_panel（打开系统面板），默认 get", listOf("get", "set", "open_panel")),
                    "value" to prop("string", "set 时传目标值：音量 0~100、亮度 0~100 或 auto、开关类传 on/off"),
                    "stream" to prop("string", "音量流类型（仅 volume 适用）", listOf("music", "ring", "alarm", "notification", "call")),
                ),
                required = listOf("setting"),
            ),
        ),
        ToolDef(
            name = "screenshot",
            description = "截取当前全屏或活动窗口并保存 JPEG 文件（默认 80% 缩放精度与 80 压缩质量）到工作区 phone_artifacts/screenshots/，返回工作区相对路径、图像分辨率、原始触控屏分辨率（screen_width/screen_height）、dHash 指纹与可选视觉分析结果。",
            requiredCapability = PhoneCapability.SCREENSHOT,
            schema = schema(
                props = mapOf(
                    "scope" to prop("string", "截图范围：screen（全屏，默认）或 window（仅当前窗口，Android 14+）", listOf("screen", "window")),
                    "scale" to prop("number", "缩放精度（0.4~1.0，默认 0.80 即 80% 精度；触控坐标仍以返回的 screen_width/screen_height 为准）"),
                    "quality" to prop("integer", "JPEG 压缩质量（40~100，默认 80）"),
                    "analyze" to prop("boolean", "是否调用视觉分析模型解读截图画面"),
                    "question" to prop("string", "analyze=true 时的具体观察问题"),
                    "draw_grid" to prop("boolean", "送入视觉模型分析时是否叠加原始坐标参考网格"),
                ),
            ),
        ),
        ToolDef(
            name = "screen_analyze",
            description = "分析当前屏幕截图或指定截图文件，用于控件树不足时识别界面元素、文字或推荐点击坐标。",
            requiredCapability = PhoneCapability.SCREEN_OBSERVE,
            schema = schema(
                props = mapOf(
                    "question" to prop("string", "希望识别什么信息或定位什么按钮/目标"),
                    "image_path" to prop("string", "已有截图路径（留空则现场截一张新图）"),
                    "draw_grid" to prop("boolean", "分析副本是否叠加像素坐标网格，默认 true"),
                ),
                required = listOf("question"),
            ),
        ),
        ToolDef(
            name = "screen_record",
            description = "屏幕录制控制：查询状态、发起系统屏幕捕获授权并开始录制 MP4 视频、或停止录屏保存视频到工作区并返回文件路径。",
            requiredCapability = PhoneCapability.SCREEN_RECORD,
            schema = schema(
                props = mapOf(
                    "action" to prop("string", "动作类型", listOf("status", "start", "stop")),
                    "with_audio" to prop("boolean", "start 时是否同时录制麦克风声音，默认 false"),
                ),
                required = listOf("action"),
            ),
        ),
        ToolDef(
            name = "audio_record",
            description = "麦克风录音控制：查询状态、申请麦克风权限并开始录音、暂停、继续、或停止保存 M4A 音频文件并返回路径。",
            requiredCapability = PhoneCapability.AUDIO_RECORD,
            schema = schema(
                props = mapOf(
                    "action" to prop("string", "动作类型", listOf("status", "start", "pause", "resume", "stop")),
                ),
                required = listOf("action"),
            ),
        ),
        ToolDef(
            name = "notifications",
            description = "通知栏操作：读取当前可见通知（自动过滤黑名单应用）、展开通知栏、打开通知、清除通知或触发通知上的快捷按钮。",
            requiredCapability = PhoneCapability.NOTIFICATIONS,
            schema = schema(
                props = mapOf(
                    "action" to prop("string", "动作类型", listOf("list", "expand_shade", "open", "clear", "click_action")),
                    "key" to prop("string", "通知唯一 key（来自 list 返回；clear 时传 all 可清全部可清除通知）"),
                    "action_index" to prop("integer", "click_action 时通知动作按钮索引（从 0 开始）"),
                    "package_filter" to prop("string", "list 时按包名关键字过滤"),
                ),
                required = listOf("action"),
            ),
        ),
        ToolDef(
            name = "file_media",
            description = "手机文件与媒体管理：列举目录、保存文件到手机 Download/CodeForge、选择/导入文件到工作区、移动、重命名、安全删除、系统打开或分享。",
            requiredCapability = PhoneCapability.FILES_MEDIA,
            schema = schema(
                props = mapOf(
                    "action" to prop(
                        "string",
                        "文件动作",
                        listOf("list", "save_to_downloads", "import_to_workspace", "pick", "move", "rename", "delete", "open", "share"),
                    ),
                    "path" to prop("string", "目标文件或目录路径（工作区相对路径或绝对路径）"),
                    "dest_path" to prop("string", "move 时的目标路径"),
                    "new_name" to prop("string", "rename 时的新文件名"),
                    "mime_type" to prop("string", "pick/open/share 时的 MIME 类型，如 image/*"),
                ),
                required = listOf("action"),
            ),
        ),
        ToolDef(
            name = "calendar_reminder",
            description = "查询和创建日历日程事件（带提醒），或设置系统闹钟、倒计时计时器与查看闹钟列表。",
            requiredCapability = PhoneCapability.CALENDAR_ALARM,
            schema = schema(
                props = mapOf(
                    "action" to prop(
                        "string",
                        "动作类型",
                        listOf("list_events", "create_event", "set_alarm", "set_timer", "show_alarms"),
                    ),
                    "title" to prop("string", "日程标题或闹钟/计时器备注"),
                    "description" to prop("string", "日程详细描述"),
                    "start_ms" to prop("integer", "日程开始时间戳（毫秒），或 list_events 起始时间"),
                    "end_ms" to prop("integer", "日程结束时间戳（毫秒），或 list_events 截止时间"),
                    "hour" to prop("integer", "闹钟小时 0~23"),
                    "minute" to prop("integer", "闹钟分钟 0~59"),
                    "seconds" to prop("integer", "计时器秒数（正整数）"),
                ),
                required = listOf("action"),
            ),
        ),
        ToolDef(
            name = "communication",
            description = "通讯与分享：按姓名或号码查找联系人、打开拨号界面、起草短信、发送短信或调用系统分享文本/文件。",
            requiredCapability = PhoneCapability.COMMUNICATION,
            schema = schema(
                props = mapOf(
                    "action" to prop(
                        "string",
                        "动作类型",
                        listOf("find_contacts", "open_dialer", "draft_sms", "send_sms", "share_text", "share_file"),
                    ),
                    "query" to prop("string", "联系人搜索关键字"),
                    "phone" to prop("string", "电话号码"),
                    "body" to prop("string", "短信正文或分享文本"),
                    "subject" to prop("string", "分享标题"),
                    "file_path" to prop("string", "share_file 时要分享的文件路径"),
                ),
                required = listOf("action"),
            ),
        ),
        ToolDef(
            name = "device_status",
            description = "查询设备实时状态：电量、充电状态、网络连接类型、存储剩余空间、当前音量、响铃模式、锁屏状态、前台应用与全部手机操控权限状态。",
            requiredCapability = PhoneCapability.DEVICE_STATUS,
            schema = schema(props = emptyMap()),
        ),
        ToolDef(
            name = "task_control",
            description = "读取或上报手机操控当前任务状态、记录自主执行步骤，或在任务完成/异常时执行暂停/继续/停止（complete/stop 可自动收起跑马灯并按设置切回 CodeForge 前台）。",
            requiredCapability = null,
            schema = schema(
                props = mapOf(
                    "action" to prop("string", "动作类型", listOf("status", "report_step", "pause", "resume", "stop", "complete")),
                    "task_title" to prop("string", "当前任务目标摘要"),
                    "step_summary" to prop("string", "当前步骤简述或完成总结"),
                    "return_to_app" to prop("boolean", "stop/complete 时是否立即切回 CodeForge 前台（不传则跟随用户设置）"),
                ),
                required = listOf("action"),
            ),
        ),
        ToolDef(
            name = "app_skill",
            description = "检索、读取或沉淀特定 App 的操作技能库（YAML/Markdown 经验文档）。默认只返回名称与简介，调用 get 才加载完整步骤；save 整篇写入自定义技能；append 只追加一条经验到技能末尾的「自动沉淀的经验」小节（推荐，不会丢原有步骤）。",
            requiredCapability = null,
            schema = schema(
                props = mapOf(
                    "action" to prop("string", "动作类型", listOf("list", "match", "get", "save", "append")),
                    "id" to prop("string", "技能 id 或名称（get/save 时使用）"),
                    "package_name" to prop("string", "目标应用包名（match 时可选）"),
                    "app_name" to prop("string", "目标应用名称（match 时可选）"),
                    "query" to prop("string", "搜索关键词（match/list 时可选）"),
                    "name" to prop("string", "save 时的技能显示名称"),
                    "description" to prop("string", "save 时的技能一句话简介"),
                    "packages" to arrayProp("save 时适用的 Android 包名列表（支持末尾 .* 通配）"),
                    "app_names" to arrayProp("save 时适用的应用名称或别名列表"),
                    "tags" to arrayProp("save 时的分类标签列表"),
                    "priority" to prop("integer", "save 时的匹配优先级，默认 80"),
                    "content" to prop("string", "save 时的 Markdown 操作经验正文"),
                    "kind" to prop("string", "append 时的经验类型：tip 通用技巧 / pitfall 踩坑 / coords 坐标点击位置 / flow 操作流程", listOf("tip", "pitfall", "coords", "flow")),
                    "lesson" to prop("string", "append 时的一条经验（200 字内，一句话讲清；不得含账号、手机号、聊天内容等隐私）"),
                ),
                required = listOf("action"),
            ),
        ),
    )

    fun toMcpToolsListJson(): JSONArray {
        val arr = JSONArray()
        tools.forEach { t ->
            arr.put(
                JSONObject().apply {
                    put("name", t.name)
                    put("description", t.description)
                    put("inputSchema", t.schema)
                },
            )
        }
        return arr
    }

    fun find(name: String): ToolDef? = tools.firstOrNull { it.name == name }
}
