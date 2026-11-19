package com.hxy.codeforge.ui.chat

/**
 * 工具卡片的标题文案。
 *
 * 规则逐条对照 `web/dist/ui.js:1298` 的 `toolLabel()`：
 * 中文短语 + 目标（文件名 / 命令），**任何情况下都不把工具 ID 显示给用户** ——
 * `read_file` 是系统内部标识，界面上应该写「读取了文件 server.go」。
 *
 * ## 为什么不引 org.json
 *
 * 参数是模型生成的简单 JSON，这里只需要取几个字符串字段。用 [JSONObject]
 * 会让这段纯逻辑在 JVM 单测里抛 "not mocked"（android.jar 是 stub），
 * 而它恰恰是最该被单测覆盖的部分。所以用最小正则提取。
 */
fun toolLabel(name: String?, input: String?): String {
    val raw = name.orEmpty()
    // 插件工具形如「插件名.工具名」（如 parallel_search.web_search），按去前缀的本名匹配，
    // 否则默认分支会把生硬的限定名摊给用户。
    var local = if (raw.contains('.')) raw.substringAfter('.') else raw
    // 同族工具用前缀匹配：Exa 给的是 web_search_exa / web_fetch_exa
    when {
        local.startsWith("web_search") -> local = "web_search"
        local.startsWith("web_fetch") -> local = "web_fetch"
    }

    val body = input.orEmpty()
    val arg = jsonString(body, "path").orEmpty()
        .ifEmpty { jsonString(body, "command").orEmpty() }
        // 测试模型的工具 schema 用 cmd（{tool:"run", cmd:"..."}）
        .ifEmpty { jsonString(body, "cmd").orEmpty() }

    // 只取路径最后一段，避免长路径把卡片撑宽；完整参数仍能在展开区看到
    val base = arg.trimEnd('/', '\\').split('/', '\\').lastOrNull().orEmpty().ifEmpty { arg }
    val named = if (base.length > 60) base.take(60) + "…" else base
    val shown = if (arg.length > 80) arg.take(80) + "…" else arg

    return when (local) {
        "read_file" -> if (named.isNotEmpty()) "读取了文件 $named" else "读取了文件"
        "list_dir" -> "查看项目"
        "search_files" -> "搜索项目"
        "write_file" -> if (named.isNotEmpty()) "创建 $named" else "创建了文件"
        "edit_file" -> if (named.isNotEmpty()) "编辑 $named" else "编辑了文件"
        "delete_file" -> if (named.isNotEmpty()) "删除了文件 $named" else "删除了文件"
        "run_command", "run" -> if (shown.isNotEmpty()) "运行 $shown" else "运行了命令"
        "read" -> if (named.isNotEmpty()) "读取了文件 $named" else "读取了文件"
        "write" -> if (named.isNotEmpty()) "创建 $named" else "创建了文件"
        "save_memory" -> "保存了记忆"
        "todo_write" -> "更新了任务清单"

        "web_fetch" -> {
            // 内置用 {url}；远程 MCP（Parallel）用 {urls:[…]}，两者都取首个地址
            val u = jsonString(body, "url").orEmpty()
                .ifEmpty { firstArrayString(body, "urls").orEmpty() }
            val sliced = if (u.length > 80) u.take(80) + "…" else u
            if (sliced.isNotEmpty()) "读取了网页 $sliced" else "读取了网页"
        }

        "web_search" -> {
            // 内置用 {query}；Parallel 用 {objective, search_queries}
            val q = jsonString(body, "query").orEmpty()
                .ifEmpty { jsonString(body, "objective").orEmpty() }
                .ifEmpty { firstArrayString(body, "search_queries").orEmpty() }
            val sliced = if (q.length > 60) q.take(60) + "…" else q
            if (sliced.isNotEmpty()) "搜索了 $sliced" else "搜索了网页"
        }

        "create_skill" -> {
            val sk = jsonString(body, "name").orEmpty()
            "使用插件 Skill Creator" + if (sk.isNotEmpty()) "：创建技能 $sk" else ""
        }

        "delegate_subagents" -> {
            val n = countArrayItems(body, "tasks")
            "使用插件 Multi-Agent：并行委派 " + if (n > 0) "$n 个子智能体" else "子智能体"
        }

        "goal_verify" -> {
            // 目标模式的价值在于「判定是可见的」，只写「验证了目标」等于把证据藏起来
            val verdict = jsonString(body, "verdict").orEmpty().uppercase()
            val label = when (verdict) {
                "PASS" -> "目标已验证通过"
                "FAIL" -> "目标未达成"
                "BLOCKED" -> "无法观察，未判定"
                "SKIP" -> "无可观察面"
                else -> "验证了目标"
            }
            val round = jsonNumber(body, "round")
            val rd = if (round > 0) {
                val max = jsonNumber(body, "max_rounds")
                "（第 $round/" + (if (max > 0) "$max" else "?") + " 轮）"
            } else {
                ""
            }
            "使用插件 目标模式：$label$rd"
        }

        // 手机操控（phone.*）全部映射成中文动作描述，不暴露原始工具名
        "screen_observe" -> "观察手机当前屏幕控件"
        "screen_wait_change" -> {
            val expect = jsonString(body, "expect_text").orEmpty()
                .ifEmpty { jsonString(body, "expect_package").orEmpty() }
            if (expect.isNotEmpty()) "等待界面出现 ${expect.take(24)}" else "等待屏幕变化"
        }
        "ui_click" -> {
            val act = when (jsonString(body, "action").orEmpty().lowercase()) {
                "double_click" -> "双击"
                "long_press" -> "长按"
                else -> "点击"
            }
            val target = jsonString(body, "text").orEmpty()
                .ifEmpty { jsonString(body, "content_desc").orEmpty() }
                .ifEmpty { jsonString(body, "ref").orEmpty() }
            if (target.isNotEmpty()) "$act「${target.take(24)}」" else "${act}屏幕位置"
        }
        "ui_gesture" -> {
            val act = when (jsonString(body, "action").orEmpty().lowercase()) {
                "drag" -> "拖拽"
                "scroll" -> "滚动"
                else -> "滑动"
            }
            val dir = jsonString(body, "direction").orEmpty()
            if (dir.isNotEmpty()) "向 $dir $act" else "${act}屏幕"
        }
        "ui_input" -> when (jsonString(body, "action").orEmpty().lowercase()) {
            "clear" -> "清空输入框"
            "select_all" -> "全选输入内容"
            "select_range" -> "选择部分文本"
            "copy" -> "复制文本"
            "paste" -> "粘贴文本"
            "submit" -> "提交输入"
            "append" -> "追加输入文本"
            else -> "输入文本"
        }
        "ui_batch" -> "连贯执行同页多步手机操作"
        "ui_global_action" -> when (jsonString(body, "action").orEmpty().lowercase()) {
            "back" -> "执行系统返回"
            "home" -> "回到手机桌面"
            "recents" -> "打开最近任务"
            "notifications" -> "展开通知栏"
            "quick_settings" -> "展开快捷设置"
            "power_dialog" -> "打开电源菜单"
            "lock_screen" -> "锁定屏幕"
            else -> "执行系统按键"
        }
        "app_manage" -> {
            val app = jsonString(body, "app_name").orEmpty()
                .ifEmpty { jsonString(body, "package_name").orEmpty() }
            when (jsonString(body, "action").orEmpty().lowercase()) {
                "list", "find" -> if (app.isNotEmpty()) "查找应用 $app" else "读取已安装应用"
                "close" -> if (app.isNotEmpty()) "关闭应用 $app" else "关闭当前应用"
                "switch" -> if (app.isNotEmpty()) "切换到 $app" else "切换应用"
                else -> if (app.isNotEmpty()) "打开应用 $app" else "打开应用"
            }
        }
        "system_setting" -> {
            val item = when (jsonString(body, "setting").orEmpty().lowercase()) {
                "volume" -> "音量"
                "brightness" -> "屏幕亮度"
                "rotation" -> "自动旋转"
                "dnd" -> "勿扰模式"
                "wifi", "wi-fi" -> "Wi-Fi"
                "bluetooth" -> "蓝牙"
                "hotspot" -> "个人热点"
                "location" -> "定位服务"
                else -> "系统设置"
            }
            when (jsonString(body, "action").orEmpty().lowercase()) {
                "set" -> "调整$item"
                "open_panel" -> "打开${item}设置面板"
                else -> "查询$item"
            }
        }
        "screenshot" -> "截取手机屏幕"
        "screen_analyze" -> "识别屏幕画面"
        "screen_record" -> when (jsonString(body, "action").orEmpty().lowercase()) {
            "start" -> "开始手机录屏"
            "stop" -> "停止并保存录屏"
            else -> "查询录屏状态"
        }
        "audio_record" -> when (jsonString(body, "action").orEmpty().lowercase()) {
            "start" -> "开始麦克风录音"
            "pause" -> "暂停录音"
            "resume" -> "继续录音"
            "stop" -> "停止并保存录音"
            else -> "查询录音状态"
        }
        "notifications" -> when (jsonString(body, "action").orEmpty().lowercase()) {
            "expand_shade" -> "展开通知栏"
            "open" -> "打开通知"
            "clear" -> "清除通知"
            "click_action" -> "操作通知按钮"
            else -> "读取可见通知"
        }
        "file_media" -> when (jsonString(body, "action").orEmpty().lowercase()) {
            "save_to_downloads" -> "导出文件到手机下载目录"
            "import_to_workspace" -> "导入文件到工作区"
            "pick" -> "调用系统选择器选文件"
            "move" -> "移动文件"
            "rename" -> "重命名文件"
            "delete" -> "删除文件"
            "open" -> "打开手机文件"
            "share" -> "分享手机文件"
            else -> "查看手机文件"
        }
        "calendar_reminder" -> when (jsonString(body, "action").orEmpty().lowercase()) {
            "list_events" -> "查询日程安排"
            "create_event" -> "创建日历日程提醒"
            "set_alarm" -> "设置系统闹钟"
            "set_timer" -> "设置倒计时"
            else -> "查看系统闹钟"
        }
        "communication" -> when (jsonString(body, "action").orEmpty().lowercase()) {
            "find_contacts" -> "查找联系人"
            "open_dialer" -> "打开拨号界面"
            "draft_sms" -> "起草短信"
            "send_sms" -> "发送短信"
            "share_text" -> "分享文本内容"
            "share_file" -> "分享文件"
            else -> "执行通讯操作"
        }
        "device_status" -> "查询手机设备状态"
        "task_control" -> "更新手机操控任务状态"
        "app_skill" -> when (jsonString(body, "action").orEmpty().lowercase()) {
            "get" -> {
                val id = jsonString(body, "id").orEmpty()
                if (id.isNotEmpty()) "读取应用技能「${id.take(24)}」" else "读取应用操作技能"
            }
            "save" -> "沉淀新的应用操作技能"
            "match" -> "匹配当前应用操作技能"
            else -> "检索应用技能库"
        }

        // 兜底也不能吐工具 ID
        else -> "调用了外部能力"
    }
}

/** 取 `"key": "value"` 里的字符串值。 */
private fun jsonString(json: String, key: String): String? {
    val m = Regex(""""$key"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(json) ?: return null
    return unescape(m.groupValues[1])
}

/**
 * JSON 字符串反转义。
 *
 * 必须**单遍扫描**，不能链式 replace：`"C:\\new"` 里的 `\\n` 是先出现 `\\`（一个反斜杠）
 * 再出现 `n`，链式 replace 会先把 `\n` 当换行吃掉，把路径变成 `C:` + 换行 + `ew`。
 * 实测就是靠这条单测抓到的。
 */
private fun unescape(s: String): String {
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c != '\\' || i + 1 >= s.length) {
            sb.append(c); i++; continue
        }
        when (val n = s[i + 1]) {
            'n' -> { sb.append('\n'); i += 2 }
            't' -> { sb.append('\t'); i += 2 }
            'r' -> { sb.append('\r'); i += 2 }
            '"' -> { sb.append('"'); i += 2 }
            '\\' -> { sb.append('\\'); i += 2 }
            '/' -> { sb.append('/'); i += 2 }
            'u' -> {
                if (i + 5 < s.length) {
                    s.substring(i + 2, i + 6).toIntOrNull(16)?.let { sb.append(it.toChar()) }
                    i += 6
                } else {
                    sb.append(n); i += 2
                }
            }

            else -> { sb.append(n); i += 2 }
        }
    }
    return sb.toString()
}

/** 取 `"key": [ "a", "b" ]` 的第一个字符串元素。 */
private fun firstArrayString(json: String, key: String): String? {
    val m = Regex(""""$key"\s*:\s*\[\s*"((?:[^"\\]|\\.)*)"""").find(json) ?: return null
    return m.groupValues[1]
}

/** 数 `"key": [ … ]` 的元素个数（只用于「委派了 N 个子智能体」）。 */
private fun countArrayItems(json: String, key: String): Int {
    val start = Regex(""""$key"\s*:\s*\[""").find(json) ?: return 0
    var depth = 0
    var count = 0
    var seenAny = false
    var i = start.range.last
    while (i < json.length) {
        when (json[i]) {
            '[' -> depth++
            ']' -> {
                depth--
                if (depth == 0) return if (seenAny) count + 1 else 0
            }

            '{' -> if (depth == 1) { count++; seenAny = true }
            '"' -> if (depth == 1) {
                // 顶层字符串元素也算一项
                val end = json.indexOf('"', i + 1)
                if (end > i) { count++; seenAny = true; i = end }
            }
        }
        i++
    }
    return if (seenAny) count else 0
}

/** 取 `"key": 12` 里的数字。 */
private fun jsonNumber(json: String, key: String): Int {
    val m = Regex(""""$key"\s*:\s*(\d+)""").find(json) ?: return 0
    return m.groupValues[1].toIntOrNull() ?: 0
}
