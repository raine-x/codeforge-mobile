package com.hxy.codeforge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * 内置纯知识技能的铺放判据。
 *
 * ## 为什么这些断言重要
 *
 * 铺内置技能是**写用户工作区**的动作，判据写错的后果不对称：
 *  - 该铺没铺 → 技能列表少几个，功能缺失（用户看得见）；
 *  - 不该铺却铺了 → **静默覆盖用户自建的同名技能**，用户写的东西没了（用户看不见）。
 *
 * 后者更危险，所以「用户自建目录绝不碰」这条要钉死。
 */
class BuiltinSkillsTest {

    /**
     * 目录不存在 → 铺。
     */
    @Test
    fun `目录不存在时要铺`() {
        assertEquals(
            BuiltinSkills.Plan.INSTALL,
            BuiltinSkills.planFor(exists = false, markerVersion = null),
        )
    }

    /**
     * 目录存在但**没有版本标记** → 用户自建，绝不覆盖。
     *
     * 这是本文件最重要的一条：内置技能与用户自建技能同名时，
     * 覆盖用户资产是不可逆的数据损失，而少一个技能只是功能缺失。
     */
    @Test
    fun `用户自建的同名目录不能被覆盖`() {
        assertEquals(
            BuiltinSkills.Plan.SKIP,
            BuiltinSkills.planFor(exists = true, markerVersion = null),
        )
    }

    /**
     * 有标记且版本一致 → 已是最新版，跳过（幂等，不重复解包）。
     */
    @Test
    fun `版本一致时跳过`() {
        assertEquals(
            BuiltinSkills.Plan.SKIP,
            BuiltinSkills.planFor(exists = true, markerVersion = "1"),
        )
    }

    /**
     * 有标记但版本号不同 → 内容升级，重铺。
     */
    @Test
    fun `版本变化时重铺`() {
        assertEquals(
            BuiltinSkills.Plan.INSTALL,
            BuiltinSkills.planFor(exists = true, markerVersion = "0"),
        )
    }
}

/**
 * 内置技能资产本身的校验。
 *
 * 这组断言防的是**静默失效**：资产里的 SKILL.md 如果 frontmatter 格式不对，
 * 内核的解析器会把它解析成空描述或 `description="|"`，技能列表里只剩一根竖线，
 * 而 App 侧完全看不出哪里出了问题。所以直接按内核的解析规则验一遍。
 */
class BuiltinSkillsAssetTest {

    /** 资产文件位置（app/src/main/assets）。 */
    private val asset = File("src/main/assets/builtin-skills.zip")

    /** 内核能识别的 frontmatter 键。 */
    private val knownKeys = setOf("name", "display_name", "description", "triggers", "enabled")

    @Test
    fun `资产存在且非空`() {
        assertTrue("找不到内置技能资产：${asset.absolutePath}", asset.isFile)
        assertTrue("内置技能资产为空", asset.length() > 0)
    }

    /**
     * 逐个技能校验 frontmatter。
     *
     * 内核的解析规则（见内核 pkg/agent/skills.go 的 parseSkill）：
     * 逐行 `strings.Cut(line, ":")` —— **不支持 YAML 块标量**。
     * 所以 `description: |` + 缩进多行会被解析成 description="|"，
     * 技能列表里显示成一根竖线，模型也拿不到触发词。
     */
    @Test
    fun `每个技能的 frontmatter 都能被内核解析`() {
        val skills = readSkills()
        assertTrue("资产里没有技能", skills.isNotEmpty())

        skills.forEach { (slug, text) ->
            val (front, body) = splitFrontmatter(text)
            assertTrue("$slug 缺少 frontmatter", front.isNotEmpty())

            val fields = parseLikeKernel(front)
            assertEquals("$slug 的 name 与目录名不符", slug, fields["name"])
            assertTrue("$slug 缺少 display_name", !fields["display_name"].isNullOrBlank())
            assertTrue("$slug 的 description 为空或退化成了 |", !fields["description"].isNullOrBlank())
            assertFalse("$slug 的 description 退化成了 |", fields["description"] == "|")
            assertTrue("$slug 没有触发词", !fields["triggers"].isNullOrBlank())
            assertEquals("$slug 应为启用状态", "true", fields["enabled"])

            // 正文不能是空的，也不能以 frontmatter 残留开头
            assertTrue("$slug 正文为空", body.isNotBlank())
            assertFalse("$slug 正文以 -- 开头（frontmatter 尾部残留）", body.trimStart().startsWith("--"))
        }
    }

    /**
     * 触发词必须是逗号分隔的短词，不能是整段散文。
     *
     * 内核按逗号切分后逐个做子串匹配（忽略大小写），触发词里带逗号或
     * 太长都会让匹配行为变得不可预期。
     */
    @Test
    fun `触发词是逗号分隔的短词`() {
        readSkills().forEach { (slug, text) ->
            val (front, _) = splitFrontmatter(text)
            val triggers = parseLikeKernel(front)["triggers"].orEmpty()
            triggers.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { t ->
                assertFalse("$slug 的触发词 \"$t\" 含逗号", t.contains(","))
                assertTrue("$slug 的触发词 \"$t\" 太长（>24 字）", t.length <= 24)
            }
        }
    }

    /** 技能 slug → SKILL.md 原文。 */
    private fun readSkills(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipFile(asset).use { zf ->
            zf.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith("/SKILL.md") }
                .forEach { e ->
                    val slug = e.name.substringBefore('/')
                    out[slug] = zf.getInputStream(e).bufferedReader().readText()
                }
        }
        return out
    }

    /** 拆 frontmatter 与正文（与内核同规则：以 `---` 开头，找下一个 `\n---`）。 */
    private fun splitFrontmatter(text: String): Pair<String, String> {
        val t = text.trimStart()
        assertTrue("frontmatter 必须以 --- 开头", t.startsWith("---"))
        val end = t.indexOf("\n---", 3)
        assertTrue("frontmatter 未闭合", end > 0)
        return t.substring(3, end) to t.substring(end + 4).trim()
    }

    /**
     * 复刻内核的 frontmatter 解析（逐行按第一个冒号切）。
     *
     * 刻意不引 YAML 库：这里要验的正是「内核那套朴素解析能不能吃下我们的写法」。
     */
    private fun parseLikeKernel(front: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        front.lines().forEach { line ->
            val s = line.trim()
            if (s.isEmpty() || s.startsWith("#")) return@forEach
            val idx = s.indexOf(':')
            if (idx < 0) return@forEach
            val k = s.substring(0, idx).trim()
            val v = s.substring(idx + 1).trim()
            if (k !in knownKeys) return@forEach
            out[k] = v
        }
        return out
    }
}
