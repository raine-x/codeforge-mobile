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
