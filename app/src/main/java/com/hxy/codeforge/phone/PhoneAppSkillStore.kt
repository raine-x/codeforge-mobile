package com.hxy.codeforge.phone

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 手机操控「应用技能库」管理器。
 *
 * 负责加载、解析、匹配与持久化面向具体 App 的操作经验技能（支持 YAML Frontmatter + Markdown
 * 与纯 YAML 两种格式），通过渐进式披露（先暴露 id/name/description，再按需读取正文）减少模型探索消耗。
 */
object PhoneAppSkillStore {

    private const val ASSET_ZIP = "phone-app-skills.zip"
    private const val SKILL_PACK_VERSION = "2"
    private const val VERSION_FILE = "phone-app-skills.version"
    private const val MAX_AUTO_MATCHES = 3

    private lateinit var appContext: Context
    private val lock = Any()

    private val _skills = MutableStateFlow<List<PhoneAppSkill>>(emptyList())
    val skills: StateFlow<List<PhoneAppSkill>> = _skills.asStateFlow()

    fun init(context: Context) {
        synchronized(lock) {
            appContext = context.applicationContext
            ensureBuiltinSkillsExtracted(appContext)
            reloadLocked()
        }
    }

    fun reload() {
        synchronized(lock) {
            if (!::appContext.isInitialized) return
            reloadLocked()
        }
    }

    private fun builtinDir(context: Context): File =
        File(context.filesDir, "phone_control/skills/builtin")

    private fun customDir(context: Context): File =
        File(context.filesDir, "phone_control/skills/custom")

    private fun workspaceDir(): File? {
        val raw = PhoneRuntime.activeWorkspace.value.trim()
        if (raw.isEmpty()) return null
        val dir = File(raw, ".codeforge/phone-skills")
        return dir.takeIf { it.isDirectory }
    }

    private fun ensureBuiltinSkillsExtracted(context: Context) {
        val dir = builtinDir(context)
        val versionFile = File(context.filesDir, "phone_control/$VERSION_FILE")
        val existingVersion = runCatching { versionFile.readText().trim() }.getOrNull()
        val hasFiles = dir.listFiles()?.any { isSkillFile(it) } == true
        if (existingVersion == SKILL_PACK_VERSION && hasFiles) return

        runCatching {
            if (dir.exists()) {
                dir.deleteRecursively()
            }
            dir.mkdirs()
            context.assets.open(ASSET_ZIP).use { rawStream ->
                ZipInputStream(rawStream).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val name = File(entry.name).name
                            if (name.isNotBlank() && !name.startsWith(".")) {
                                val out = File(dir, name)
                                out.outputStream().use { zis.copyTo(it) }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
            versionFile.parentFile?.mkdirs()
            versionFile.writeText(SKILL_PACK_VERSION)
        }
    }

    private fun reloadLocked() {
        val disabled = PhoneControlPrefs.config.value.disabledAppSkillIds
        val merged = linkedMapOf<String, PhoneAppSkill>()

        fun scanDir(dir: File?, source: PhoneAppSkillSource) {
            if (dir == null || !dir.isDirectory) return
            val files = dir.listFiles()?.filter { isSkillFile(it) }?.sortedBy { it.name } ?: return
            for (file in files) {
                val raw = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: continue
                val fallbackId = sanitizeId(file.nameWithoutExtension)
                val parsed = parseSkillDocument(
                    raw = raw,
                    fallbackId = fallbackId,
                    source = source,
                    filePath = file.absolutePath,
                    enabled = true,
                ) ?: continue
                val key = parsed.id.lowercase()
                merged[key] = parsed.copy(enabled = !disabled.contains(parsed.id))
            }
        }

        // 优先级由低到高覆盖：内置 < 自定义 < 工作区
        scanDir(builtinDir(appContext), PhoneAppSkillSource.BUILTIN)
        scanDir(customDir(appContext), PhoneAppSkillSource.CUSTOM)
        scanDir(workspaceDir(), PhoneAppSkillSource.WORKSPACE)

        _skills.value = merged.values.sortedWith(
            compareByDescending<PhoneAppSkill> { it.priority }
                .thenBy { it.name.lowercase() }
        )
    }

    fun listAll(includeDisabled: Boolean = true): List<PhoneAppSkill> {
        val current = _skills.value
        return if (includeDisabled) current else current.filter { it.enabled }
    }

    fun getByIdOrName(idOrName: String, includeDisabled: Boolean = true): PhoneAppSkill? {
        val needle = idOrName.trim().lowercase()
        if (needle.isEmpty()) return null
        val pool = listAll(includeDisabled)
        return pool.firstOrNull { it.id.lowercase() == needle }
            ?: pool.firstOrNull { it.name.lowercase() == needle }
            ?: pool.firstOrNull { it.name.lowercase().contains(needle) }
    }

    /**
     * 根据当前前台包名、应用名或检索关键词匹配相关技能。
     *
     * 自动匹配模式下（query 为空）仅当包名或应用名命中时才返回，避免无关联技能污染上下文。
     */
    fun match(
        packageName: String? = null,
        appLabel: String? = null,
        query: String? = null,
        includeDisabled: Boolean = false,
        limit: Int = MAX_AUTO_MATCHES,
    ): List<PhoneAppSkill> {
        val pkg = packageName?.trim()?.lowercase().orEmpty()
        val label = appLabel?.trim()?.lowercase().orEmpty()
        val q = query?.trim()?.lowercase().orEmpty()
        if (pkg.isEmpty() && label.isEmpty() && q.isEmpty()) return emptyList()

        val pool = listAll(includeDisabled)
        val scored = ArrayList<Pair<PhoneAppSkill, Int>>()

        for (skill in pool) {
            var baseScore = 0

            if (pkg.isNotEmpty()) {
                for (patternRaw in skill.packages) {
                    val pattern = patternRaw.trim().lowercase()
                    if (pattern.isEmpty()) continue
                    when {
                        pattern == pkg -> baseScore = maxOf(baseScore, 120)
                        pattern.endsWith(".*") && pkg.startsWith(pattern.dropLast(1)) ->
                            baseScore = maxOf(baseScore, 95)
                        pattern.contains('*') && wildcardMatch(pattern, pkg) ->
                            baseScore = maxOf(baseScore, 90)
                    }
                }
            }

            if (label.isNotEmpty()) {
                for (aliasRaw in skill.appNames) {
                    val alias = aliasRaw.trim().lowercase()
                    if (alias.isEmpty()) continue
                    when {
                        alias == label -> baseScore = maxOf(baseScore, 85)
                        label.contains(alias) || alias.contains(label) ->
                            baseScore = maxOf(baseScore, 55)
                    }
                }
                if (skill.name.lowercase().contains(label)) {
                    baseScore = maxOf(baseScore, 35)
                }
            }

            if (q.isNotEmpty()) {
                val idLower = skill.id.lowercase()
                val nameLower = skill.name.lowercase()
                val descLower = skill.description.lowercase()
                when {
                    idLower == q -> baseScore = maxOf(baseScore, 110)
                    nameLower == q -> baseScore = maxOf(baseScore, 100)
                    nameLower.contains(q) || idLower.contains(q) -> baseScore = maxOf(baseScore, 70)
                    skill.packages.any { it.lowercase().contains(q) } ->
                        baseScore = maxOf(baseScore, 65)
                    skill.appNames.any { it.lowercase().contains(q) } ->
                        baseScore = maxOf(baseScore, 60)
                    skill.tags.any { it.lowercase().contains(q) } ->
                        baseScore = maxOf(baseScore, 50)
                    descLower.contains(q) -> baseScore = maxOf(baseScore, 35)
                    skill.content.lowercase().contains(q) -> baseScore = maxOf(baseScore, 15)
                }
            }

            if (baseScore > 0) {
                val finalScore = baseScore + skill.priority.coerceIn(-50, 50)
                scored.add(skill to finalScore)
            }
        }

        return scored
            .sortedWith(
                compareByDescending<Pair<PhoneAppSkill, Int>> { it.second }
                    .thenByDescending { it.first.priority }
                    .thenBy { it.first.name.lowercase() }
            )
            .take(limit.coerceAtLeast(1))
            .map { it.first }
    }

    fun saveCustomSkill(
        id: String,
        name: String,
        description: String,
        packages: List<String>,
        appNames: List<String>,
        tags: List<String>,
        priority: Int,
        content: String,
    ): Result<PhoneAppSkill> = runCatching {
        synchronized(lock) {
            check(::appContext.isInitialized) { "PhoneAppSkillStore 尚未初始化" }
            val cleanId = sanitizeId(id.ifBlank { name })
            require(cleanId.isNotBlank()) { "技能 id 不能为空" }
            val cleanName = name.trim().ifBlank { cleanId }
            val cleanDesc = description.trim().ifBlank { cleanName }
            val cleanBody = content.trim()
            require(cleanBody.isNotBlank()) { "技能正文内容不能为空" }

            val markdown = buildMarkdownDocument(
                id = cleanId,
                name = cleanName,
                description = cleanDesc,
                packages = packages.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                appNames = appNames.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                tags = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                priority = priority,
                content = cleanBody,
            )

            val dir = customDir(appContext).apply { mkdirs() }
            // 移除同名 yaml/md 旧副本，避免同 id 留下两份不同后缀的文件
            listOf("md", "yaml", "yml").forEach { ext ->
                val f = File(dir, "$cleanId.$ext")
                if (f.exists() && ext != "md") f.delete()
            }
            val target = File(dir, "$cleanId.md")
            target.writeText(markdown, Charsets.UTF_8)
            reloadLocked()
            _skills.value.firstOrNull { it.id == cleanId }
                ?: error("保存后未能重新加载技能：$cleanId")
        }
    }

    // ---------- 经验沉淀、行级索引与最近成功路径 ----------

    internal const val EXPERIENCE_HEADING = "## 自动沉淀的经验"
    internal const val RECENT_FLOW_HEADING = "## 最近成功路径"
    internal const val MAX_EXPERIENCE_LINES = 30
    internal const val MAX_RECENT_FLOWS = 3
    internal const val MAX_LESSON_CHARS = 200
    internal const val MAX_SKILL_CHARS = 16_000
    internal const val SMALL_SKILL_INLINE_LINES = 80
    internal val EXPERIENCE_KINDS = setOf("tip", "pitfall", "coords", "flow")

    /** 章节行号区间（1-based，基于 `skill.content`）。 */
    internal data class SkillSectionIndex(val title: String, val startLine: Int, val endLine: Int)

    /** 索引条目：精确行号、所属小节、标签与单行预览。 */
    internal data class SkillEntryIndex(
        val line: Int,
        val section: String,
        val tag: String,
        val preview: String,
    )

    /** 技能文档轻量行号索引表，供模型「主动第 1 步」先定位行号再精准读取/替换。 */
    internal data class SkillIndex(
        val totalLines: Int,
        val sections: List<SkillSectionIndex>,
        val entries: List<SkillEntryIndex>,
    )

    /** [mergeExperience] 的结果：新正文、是否写入变更、合并后经验条数、是否原地替换。 */
    internal data class ExperienceMerge(
        val content: String,
        val added: Boolean,
        val lessonCount: Int,
        val replaced: Boolean = false,
    )

    internal fun packageSkillId(packageName: String): String =
        sanitizeId("pkg-" + packageName.trim().lowercase().replace('.', '-'))

    fun resolveSkill(
        idOrName: String? = null,
        packageName: String? = null,
        includeDisabled: Boolean = false,
    ): PhoneAppSkill? {
        val id = idOrName?.trim().orEmpty()
        if (id.isNotEmpty()) {
            getByIdOrName(id, includeDisabled)?.let { return it }
        }
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isNotEmpty()) {
            return match(packageName = pkg, includeDisabled = includeDisabled, limit = 1).firstOrNull()
        }
        return null
    }

    /** 扫描 Markdown 生成章节行号范围与条目行号预览（纯函数，可 JVM 单测）。 */
    internal fun buildSkillIndex(content: String): SkillIndex {
        val lines = content.replace("\r\n", "\n").lines()
        val total = lines.size
        val headings = mutableListOf<Pair<Int, String>>()
        val entryRegex = Regex("""^(?:-|\d+\.)\s+(?:\[([^\]]+)])?(?:\[([^\]]+)])?\s*(.+)$""")
        val rawEntries = mutableListOf<SkillEntryIndex>()

        var currentSection = "正文"
        lines.forEachIndexed { idx, rawLine ->
            val lineNo = idx + 1
            val trimmed = rawLine.trim()
            if (trimmed.startsWith("#")) {
                val cleanTitle = trimmed.trimStart('#').trim()
                if (cleanTitle.isNotEmpty()) {
                    headings.add(lineNo to trimmed)
                    currentSection = cleanTitle
                }
            } else {
                val m = entryRegex.matchEntire(trimmed)
                if (m != null) {
                    val tag1 = m.groupValues[1].trim()
                    val tag2 = m.groupValues[2].trim()
                    val body = m.groupValues[3].trim()
                    val preview = (if (tag2.isNotEmpty()) "[$tag2] $body" else body).take(90)
                    rawEntries.add(
                        SkillEntryIndex(
                            line = lineNo,
                            section = currentSection,
                            tag = tag1.ifBlank { "step" },
                            preview = preview,
                        ),
                    )
                }
            }
        }

        val sections = headings.mapIndexed { i, (start, title) ->
            val end = if (i + 1 < headings.size) headings[i + 1].first - 1 else total
            SkillSectionIndex(title = title, startLine = start, endLine = end.coerceAtLeast(start))
        }
        return SkillIndex(totalLines = total, sections = sections, entries = rawEntries)
    }

    /** 按 1-based 行号区间切片，返回 `(actualStart, actualEnd, 带行号正文)`。 */
    internal fun readSkillLines(content: String, startLine: Int?, endLine: Int?): Triple<Int, Int, String> {
        val lines = content.replace("\r\n", "\n").lines()
        if (lines.isEmpty()) return Triple(1, 1, "")
        val total = lines.size
        val start = (startLine ?: 1).coerceIn(1, total)
        val defaultEnd = minOf(total, start + SMALL_SKILL_INLINE_LINES - 1)
        val end = (endLine ?: defaultEnd).coerceIn(start, minOf(total, start + 119))
        val slice = (start..end).joinToString("\n") { ln -> "$ln: ${lines[ln - 1]}" }
        return Triple(start, end, slice)
    }

    private fun extractSection(content: String, heading: String): Triple<String, List<String>, String> {
        val norm = content.replace("\r\n", "\n")
        val start = norm.indexOf(heading)
        if (start < 0) return Triple(norm.trimEnd(), emptyList(), "")
        val bodyStart = start + heading.length
        val next = norm.indexOf("\n## ", bodyStart)
        val sectionEnd = if (next >= 0) next else norm.length
        val head = norm.substring(0, start).trimEnd()
        val lines = norm.substring(bodyStart, sectionEnd).lines()
            .map { it.trim() }.filter { it.startsWith("- ") }
        val tail = norm.substring(sectionEnd).trim('\n')
        return Triple(head, lines, tail)
    }

    private fun renderWithSection(head: String, heading: String, lines: List<String>, tail: String): String =
        buildString {
            if (head.isNotEmpty()) append(head).append("\n\n")
            if (lines.isNotEmpty()) {
                append(heading).append('\n')
                append(lines.joinToString("\n")).append('\n')
                if (tail.isNotEmpty()) append('\n')
            }
            if (tail.isNotEmpty()) append(tail).append('\n')
        }

    private fun normEntryBody(line: String): String =
        line.replace(Regex("""^- (?:\[[^\]]*]){1,2}\s*"""), "").lowercase().filter { !it.isWhitespace() }

    /**
     * 把一条经验并入技能的「自动沉淀的经验」小节；支持按 [targetLine]（1-based 行号）或 [replaces] 关键词原地替换。
     */
    internal fun mergeExperience(
        content: String,
        kind: String,
        lesson: String,
        date: String,
        targetLine: Int? = null,
        replaces: String = "",
    ): ExperienceMerge {
        val k = kind.trim().lowercase().takeIf { it in EXPERIENCE_KINDS } ?: "tip"
        val clean = lesson.replace(Regex("\\s+"), " ").trim().take(MAX_LESSON_CHARS)
        require(clean.isNotBlank()) { "经验内容不能为空" }
        val formatted = "- [$k][$date] $clean"

        // 1) 精确行号原地替换：AI 先用 index 看准过时条目的行号，再直接改那一行
        if (targetLine != null && targetLine >= 1) {
            val allLines = content.replace("\r\n", "\n").lines().toMutableList()
            if (targetLine <= allLines.size) {
                val old = allLines[targetLine - 1].trim()
                if (old.startsWith("- ")) {
                    allLines[targetLine - 1] = formatted
                    val (_, expLines, _) = extractSection(allLines.joinToString("\n"), EXPERIENCE_HEADING)
                    return ExperienceMerge(
                        content = allLines.joinToString("\n"),
                        added = true,
                        lessonCount = expLines.size,
                        replaced = true,
                    )
                }
            }
        }

        val (head, sectionLines, tail) = extractSection(content, EXPERIENCE_HEADING)

        // 2) 关键词原地替换
        val rep = replaces.trim()
        if (rep.isNotEmpty()) {
            val hitIdx = sectionLines.indexOfFirst { it.contains(rep, ignoreCase = true) }
            if (hitIdx >= 0) {
                val updated = sectionLines.toMutableList().also { it[hitIdx] = formatted }
                val out = renderWithSection(head, EXPERIENCE_HEADING, updated, tail)
                return ExperienceMerge(out, added = true, lessonCount = updated.size, replaced = true)
            }
        }

        // 3) 去重追加
        if (sectionLines.any { normEntryBody(it) == normEntryBody(formatted) }) {
            return ExperienceMerge(content, added = false, lessonCount = sectionLines.size)
        }

        var lines = (sectionLines + formatted).takeLast(MAX_EXPERIENCE_LINES)
        var out = renderWithSection(head, EXPERIENCE_HEADING, lines, tail)
        while (out.length > MAX_SKILL_CHARS && lines.size > 1) {
            lines = lines.drop(1)
            out = renderWithSection(head, EXPERIENCE_HEADING, lines, tail)
        }
        return ExperienceMerge(out, added = true, lessonCount = lines.size)
    }

    /** 按精确行号或关键词删除已过时/失效的经验条目（只删 `- ` 列表项，不破坏核心标题）。 */
    internal fun pruneExperience(
        content: String,
        targetLine: Int? = null,
        match: String = "",
    ): Pair<String, Int> {
        val norm = content.replace("\r\n", "\n")
        if (targetLine != null && targetLine >= 1) {
            val allLines = norm.lines().toMutableList()
            if (targetLine <= allLines.size && allLines[targetLine - 1].trim().startsWith("- ")) {
                allLines.removeAt(targetLine - 1)
                return allLines.joinToString("\n").trimEnd() + "\n" to 1
            }
            return content to 0
        }
        val needle = match.trim()
        if (needle.isEmpty()) return content to 0

        var removed = 0
        var work = norm
        for (heading in listOf(EXPERIENCE_HEADING, RECENT_FLOW_HEADING)) {
            if (!work.contains(heading)) continue
            val (head, lines, tail) = extractSection(work, heading)
            val kept = lines.filterNot { line ->
                val hit = line.contains(needle, ignoreCase = true)
                if (hit) removed++
                hit
            }
            work = renderWithSection(head, heading, kept, tail)
        }
        return (if (removed > 0) work else content) to removed
    }

    /** 更新「最近成功路径」小节：仅保留最新 [MAX_RECENT_FLOWS] 条，去重淘汰旧路径。 */
    internal fun mergeRecentFlow(content: String, flow: String, date: String): String {
        val clean = flow.replace(Regex("\\s+"), " ").trim().take(MAX_LESSON_CHARS)
        require(clean.isNotBlank()) { "路径不能为空" }
        val entry = "- [$date] $clean"
        val (head, existing, tail) = extractSection(content, RECENT_FLOW_HEADING)
        val deduped = existing.filterNot { normEntryBody(it) == normEntryBody(entry) }
        val updated = (deduped + entry).takeLast(MAX_RECENT_FLOWS)
        return renderWithSection(head, RECENT_FLOW_HEADING, updated, tail)
    }

    /** 往技能追加或原地替换一条经验；技能不存在时按 [name]/[packageName] 新建。 */
    internal fun appendExperience(
        id: String,
        kind: String,
        lesson: String,
        name: String,
        description: String,
        packages: List<String>,
        appNames: List<String>,
        packageName: String = "",
        targetLine: Int? = null,
        replaces: String = "",
    ): Result<Pair<PhoneAppSkill, ExperienceMerge>> = runCatching {
        synchronized(lock) {
            check(::appContext.isInitialized) { "PhoneAppSkillStore 尚未初始化" }
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            val pkg = packageName.trim()
            val allPkgs = (packages + listOfNotNull(pkg.takeIf { it.isNotEmpty() })).distinct()
            val existing = resolveSkill(id.ifBlank { null }, pkg.ifBlank { null }, includeDisabled = true)
            if (existing == null) {
                require((name.isNotBlank() || pkg.isNotEmpty()) && (allPkgs.isNotEmpty() || appNames.isNotEmpty())) {
                    "技能「$id」不存在：新建需提供 name 与 packages（或 package_name）/ app_names"
                }
                val newId = id.ifBlank { if (pkg.isNotEmpty()) packageSkillId(pkg) else name }
                val cleanName = name.ifBlank { pkg }
                val merged = mergeExperience("", kind, lesson, today)
                val saved = saveCustomSkill(
                    id = newId,
                    name = cleanName,
                    description = description.ifBlank { "$cleanName 的操作经验" },
                    packages = allPkgs,
                    appNames = appNames.ifEmpty { listOf(cleanName) },
                    tags = emptyList(),
                    priority = 80,
                    content = merged.content,
                ).getOrThrow()
                saved to merged
            } else {
                val merged = mergeExperience(existing.content, kind, lesson, today, targetLine, replaces)
                if (!merged.added) return@synchronized existing to merged
                val saved = saveCustomSkill(
                    id = existing.id,
                    name = existing.name,
                    description = existing.description,
                    packages = existing.packages,
                    appNames = existing.appNames,
                    tags = existing.tags,
                    priority = existing.priority,
                    content = merged.content,
                ).getOrThrow()
                saved to merged
            }
        }
    }

    internal fun pruneCustomExperience(
        idOrName: String,
        packageName: String,
        targetLine: Int?,
        match: String,
    ): Result<Pair<PhoneAppSkill, Int>> = runCatching {
        synchronized(lock) {
            check(::appContext.isInitialized) { "PhoneAppSkillStore 尚未初始化" }
            val existing = resolveSkill(idOrName.ifBlank { null }, packageName.ifBlank { null }, includeDisabled = true)
                ?: error("未找到对应应用技能：$idOrName $packageName".trim())
            val (newContent, removed) = pruneExperience(existing.content, targetLine, match)
            if (removed == 0) return@synchronized existing to 0
            val saved = saveCustomSkill(
                id = existing.id,
                name = existing.name,
                description = existing.description,
                packages = existing.packages,
                appNames = existing.appNames,
                tags = existing.tags,
                priority = existing.priority,
                content = newContent,
            ).getOrThrow()
            saved to removed
        }
    }

    /** 按包名自动记录本轮「最近成功路径」（不存在则按包名建档）。 */
    internal fun recordRecentFlow(
        packageName: String,
        appLabel: String,
        flow: String,
    ): Result<PhoneAppSkill> = runCatching {
        synchronized(lock) {
            check(::appContext.isInitialized) { "PhoneAppSkillStore 尚未初始化" }
            val pkg = packageName.trim()
            require(pkg.isNotEmpty()) { "packageName 不能为空" }
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            val existing = resolveSkill(null, pkg, includeDisabled = true)
            if (existing == null) {
                val label = appLabel.trim().ifBlank { pkg }
                val content = mergeRecentFlow("# $label\n", flow, today)
                saveCustomSkill(
                    id = packageSkillId(pkg),
                    name = label,
                    description = "$label（$pkg）自动沉淀的操作路径与经验",
                    packages = listOf(pkg),
                    appNames = listOf(label),
                    tags = listOf("自动沉淀"),
                    priority = 75,
                    content = content,
                ).getOrThrow()
            } else {
                val updated = mergeRecentFlow(existing.content, flow, today)
                saveCustomSkill(
                    id = existing.id,
                    name = existing.name,
                    description = existing.description,
                    packages = (existing.packages + pkg).distinct(),
                    appNames = existing.appNames,
                    tags = existing.tags,
                    priority = existing.priority,
                    content = updated,
                ).getOrThrow()
            }
        }
    }

    fun importSkillUri(context: Context, uri: Uri): Result<PhoneAppSkill> = runCatching {
        val displayName = resolveDisplayName(context, uri)
        val raw = context.contentResolver.openInputStream(uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: error("无法读取所选技能文件")

        val ext = displayName.substringAfterLast('.', "md").lowercase()
            .let { if (it in setOf("md", "yaml", "yml")) it else "md" }
        val fallbackId = sanitizeId(displayName.substringBeforeLast('.').ifBlank { "custom-skill" })
        val parsed = parseSkillDocument(
            raw = raw,
            fallbackId = fallbackId,
            source = PhoneAppSkillSource.CUSTOM,
        ) ?: error("文件格式无效：请提供包含 YAML Frontmatter 的 .md 或合法 .yaml 技能文件")

        synchronized(lock) {
            val ctx = if (::appContext.isInitialized) appContext else context.applicationContext
            val dir = customDir(ctx).apply { mkdirs() }
            val cleanId = sanitizeId(parsed.id)
            val target = File(dir, "$cleanId.$ext")
            target.writeText(raw.trimStart('\uFEFF'), Charsets.UTF_8)
            if (::appContext.isInitialized) {
                reloadLocked()
            }
            _skills.value.firstOrNull { it.id.equals(cleanId, ignoreCase = true) } ?: parsed
        }
    }

    fun deleteCustomSkill(id: String): Boolean {
        synchronized(lock) {
            if (!::appContext.isInitialized) return false
            val cleanId = sanitizeId(id)
            val dir = customDir(appContext)
            var deleted = false
            listOf("md", "yaml", "yml").forEach { ext ->
                val f = File(dir, "$cleanId.$ext")
                if (f.exists() && f.delete()) {
                    deleted = true
                }
            }
            val exact = _skills.value.firstOrNull {
                it.id.equals(id, ignoreCase = true) && it.source == PhoneAppSkillSource.CUSTOM
            }?.filePath?.let { File(it) }
            if (exact != null && exact.exists() && exact.delete()) {
                deleted = true
            }
            if (deleted) reloadLocked()
            return deleted
        }
    }

    fun toSummaryJsonArray(list: List<PhoneAppSkill>): JSONArray = JSONArray().apply {
        for (skill in list) {
            put(
                JSONObject().apply {
                    put("id", skill.id)
                    put("name", skill.name)
                    put("description", skill.description)
                    put("source", skill.source.label)
                    put("total_lines", skill.content.replace("\r\n", "\n").lines().size)
                    if (skill.packages.isNotEmpty()) {
                        put("packages", JSONArray(skill.packages))
                    }
                    if (skill.tags.isNotEmpty()) {
                        put("tags", JSONArray(skill.tags))
                    }
                }
            )
        }
    }

    fun toDetailJson(skill: PhoneAppSkill): JSONObject = JSONObject().apply {
        put("id", skill.id)
        put("name", skill.name)
        put("description", skill.description)
        put("source", skill.source.label)
        put("priority", skill.priority)
        put("enabled", skill.enabled)
        put("packages", JSONArray(skill.packages))
        put("app_names", JSONArray(skill.appNames))
        put("tags", JSONArray(skill.tags))
        put("total_lines", skill.content.replace("\r\n", "\n").lines().size)
        put("content", skill.content)
    }

    fun toIndexJson(skill: PhoneAppSkill): JSONObject {
        val index = buildSkillIndex(skill.content)
        return JSONObject().apply {
            put("id", skill.id)
            put("name", skill.name)
            put("description", skill.description)
            put("packages", JSONArray(skill.packages))
            put("total_lines", index.totalLines)
            put("sections", JSONArray().apply {
                index.sections.forEach { s ->
                    put(JSONObject().put("title", s.title).put("start_line", s.startLine).put("end_line", s.endLine))
                }
            })
            put("entries", JSONArray().apply {
                index.entries.forEach { e ->
                    put(JSONObject().put("line", e.line).put("section", e.section).put("tag", e.tag).put("preview", e.preview))
                }
            })
        }
    }

    fun toLineSliceJson(skill: PhoneAppSkill, startLine: Int?, endLine: Int?): JSONObject {
        val index = buildSkillIndex(skill.content)
        val (s, e, slice) = readSkillLines(skill.content, startLine, endLine)
        return JSONObject().apply {
            put("id", skill.id)
            put("name", skill.name)
            put("total_lines", index.totalLines)
            put("start_line", s)
            put("end_line", e)
            put("content", slice)
        }
    }

    /**
     * 解析 Markdown+Frontmatter 或纯 YAML 技能文档。
     * 不依赖第三方 YAML 库，以保证零额外体积与完全可控的容错能力。
     */
    internal fun parseSkillDocument(
        raw: String,
        fallbackId: String,
        source: PhoneAppSkillSource,
        filePath: String = "",
        enabled: Boolean = true,
    ): PhoneAppSkill? {
        val text = raw.trimStart('\uFEFF').replace("\r\n", "\n").trim()
        if (text.isEmpty()) return null

        val lines = text.lines()
        val metaLines: List<String>
        val bodyText: String

        if (lines.firstOrNull()?.trim() == "---") {
            val closeIdx = lines.drop(1).indexOfFirst { it.trim() == "---" }
            if (closeIdx < 0) return null
            metaLines = lines.subList(1, closeIdx + 1)
            bodyText = lines.drop(closeIdx + 2).joinToString("\n").trim()
        } else {
            // 纯 YAML 格式
            metaLines = lines
            bodyText = ""
        }

        val scalars = linkedMapOf<String, String>()
        val lists = linkedMapOf<String, MutableList<String>>()
        var currentKey: String? = null
        var multilineMode: Char? = null
        val multilineBuffer = StringBuilder()

        fun flushMultiline() {
            val key = currentKey
            if (multilineMode != null && key != null) {
                scalars[key] = multilineBuffer.toString().trimEnd()
            }
            multilineMode = null
            multilineBuffer.clear()
        }

        for (line in metaLines) {
            if (multilineMode != null) {
                if (line.startsWith("  ") || line.startsWith("\t") || line.isBlank()) {
                    val trimmedIndent = when {
                        line.startsWith("  ") -> line.substring(2)
                        line.startsWith("\t") -> line.substring(1)
                        else -> ""
                    }
                    if (multilineBuffer.isNotEmpty()) multilineBuffer.append('\n')
                    multilineBuffer.append(trimmedIndent)
                    continue
                } else {
                    flushMultiline()
                }
            }

            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            if (trimmed.startsWith("- ") && currentKey != null) {
                val item = unquote(trimmed.substring(2).trim())
                if (item.isNotEmpty()) {
                    lists.getOrPut(currentKey) { mutableListOf() }.add(item)
                }
                continue
            }

            val colonIdx = line.indexOf(':')
            if (colonIdx <= 0 || line.startsWith("  ") || line.startsWith("\t")) {
                // 可能是纯 YAML 中缩进的子块，保留到正文回退
                continue
            }

            val key = line.substring(0, colonIdx).trim().lowercase()
            val valuePart = line.substring(colonIdx + 1).trim()
            currentKey = key

            when {
                valuePart == "|" || valuePart == ">" || valuePart == "|-" || valuePart == ">-" -> {
                    multilineMode = valuePart.first()
                    multilineBuffer.clear()
                }
                valuePart.startsWith("[") && valuePart.endsWith("]") -> {
                    val items = parseInlineList(valuePart)
                    lists[key] = items.toMutableList()
                }
                valuePart.isEmpty() -> {
                    lists.putIfAbsent(key, mutableListOf())
                }
                else -> {
                    scalars[key] = unquote(valuePart)
                }
            }
        }
        flushMultiline()

        val rawId = scalars["id"].orEmpty().ifBlank { fallbackId }
        val id = sanitizeId(rawId)
        if (id.isEmpty()) return null

        val name = scalars["name"].orEmpty().ifBlank { id }
        val description = scalars["description"].orEmpty()
            .ifBlank { scalars["summary"].orEmpty() }
            .ifBlank { name }

        val packages = mergeListFields(scalars, lists, "packages", "package", "pkg")
        val appNames = mergeListFields(scalars, lists, "app_names", "appnames", "apps", "aliases")
        val tags = mergeListFields(scalars, lists, "tags", "tag")
        val priority = scalars["priority"]?.toIntOrNull() ?: 0

        val content = when {
            bodyText.isNotBlank() -> bodyText
            !scalars["content"].isNullOrBlank() -> scalars["content"]!!
            !scalars["guide"].isNullOrBlank() -> scalars["guide"]!!
            else -> text
        }
        if (content.isBlank()) return null

        return PhoneAppSkill(
            id = id,
            name = name,
            description = description,
            packages = packages,
            appNames = appNames,
            tags = tags,
            priority = priority,
            content = content,
            source = source,
            filePath = filePath,
            enabled = enabled,
        )
    }

    private fun buildMarkdownDocument(
        id: String,
        name: String,
        description: String,
        packages: List<String>,
        appNames: List<String>,
        tags: List<String>,
        priority: Int,
        content: String,
    ): String = buildString {
        appendLine("---")
        appendLine("id: ${yamlEscape(id)}")
        appendLine("name: ${yamlEscape(name)}")
        appendLine("description: ${yamlEscape(description)}")
        appendLine("packages: [${packages.joinToString(", ") { yamlEscape(it) }}]")
        appendLine("app_names: [${appNames.joinToString(", ") { yamlEscape(it) }}]")
        appendLine("tags: [${tags.joinToString(", ") { yamlEscape(it) }}]")
        appendLine("priority: $priority")
        appendLine("---")
        appendLine()
        append(content.trim())
        appendLine()
    }

    private fun yamlEscape(value: String): String {
        val clean = value.replace("\n", " ").trim()
        val needsQuotes = clean.isEmpty() ||
            clean.contains(':') ||
            clean.contains('#') ||
            clean.contains('[') ||
            clean.contains(']') ||
            clean.contains(',') ||
            clean.startsWith('"') ||
            clean.startsWith('\'')
        return if (needsQuotes) {
            "\"${clean.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        } else {
            clean
        }
    }

    private fun mergeListFields(
        scalars: Map<String, String>,
        lists: Map<String, MutableList<String>>,
        vararg keys: String,
    ): List<String> {
        val out = LinkedHashSet<String>()
        for (key in keys) {
            lists[key]?.forEach { item ->
                if (item.isNotBlank()) out.add(item.trim())
            }
            scalars[key]?.let { single ->
                if (single.isNotBlank()) {
                    single.split(',', '，').forEach { part ->
                        val p = unquote(part.trim())
                        if (p.isNotEmpty()) out.add(p)
                    }
                }
            }
        }
        return out.toList()
    }

    private fun parseInlineList(raw: String): List<String> {
        val inner = raw.removePrefix("[").removeSuffix("]").trim()
        if (inner.isEmpty()) return emptyList()
        return inner.split(',').mapNotNull { token ->
            unquote(token.trim()).takeIf { it.isNotEmpty() }
        }
    }

    private fun unquote(raw: String): String {
        val s = raw.trim()
        return if (s.length >= 2 && ((s.startsWith('"') && s.endsWith('"')) || (s.startsWith('\'') && s.endsWith('\'')))) {
            s.substring(1, s.length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        } else {
            s
        }
    }

    private fun wildcardMatch(pattern: String, text: String): Boolean {
        val regex = Regex(
            "^" + pattern.split('*').joinToString(".*") { Regex.escape(it) } + "$",
            RegexOption.IGNORE_CASE,
        )
        return regex.matches(text)
    }

    private fun sanitizeId(raw: String): String =
        raw.trim()
            .lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-')

    private fun isSkillFile(file: File): Boolean {
        if (!file.isFile) return false
        val ext = file.extension.lowercase()
        return ext == "md" || ext == "yaml" || ext == "yml"
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) {
                            val name = cursor.getString(idx)
                            if (!name.isNullOrBlank()) return name
                        }
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "imported-skill.md"
    }
}
