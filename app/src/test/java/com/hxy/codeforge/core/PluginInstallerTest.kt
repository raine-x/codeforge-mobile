package com.hxy.codeforge.core

import com.hxy.codeforge.model.InstallVersionRelation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 动态插件安装器（[PluginInstaller]）与多工作区技能同步器（[PluginSkills]）的单元测试。
 *
 * 覆盖设计文档 §5.1 要求的全部断言：
 * 1. manifest 合法 / 缺字段 / 非法 id / 非法 version / 非法 schema
 * 2. type 与必填字段不匹配（skill 缺 skills[]、mcp 缺 command、mcp-http 缺 endpoint）
 * 3. skills[] 与包内实际目录不一致（多列 / 漏列）
 * 4. zip 路径穿越（`../x`、`/abs`）被拒
 * 5. P-052：`description: |` 与多行缩进 frontmatter 被拒；`name != slug` 被拒
 * 6. 版本比较：升级 / 同版本 / 降级 / id 冲突 / slug 冲突
 * 7. 权限解析（缺省、空数组、未知字段忽略）
 * 8. 完整落盘 + 多工作区技能同步 + 停用清理 + 用户自建技能保护闭环
 */
class PluginInstallerTest {

    // ---------------------------------------------------------------- 1. manifest 校验

    @Test
    fun `manifest 合法 mcp-http 与 skill 最小清单均可解析`() {
        val fullJson = """
            {
              "schema": 1,
              "id": "weather",
              "name": "天气查询",
              "version": "1.2.0",
              "description": "查询城市天气",
              "author": "someone",
              "homepage": "https://example.com/weather",
              "type": "mcp-http",
              "endpoint": "https://api.example.com/mcp",
              "env": { "API_KEY": "" },
              "permissions": {
                "network": ["api.example.com"],
                "filesystem": "none",
                "secrets": ["API_KEY"],
                "requires_approval": true,
                "allowed_actions": ["query"]
              },
              "skills": ["weather-guide"]
            }
        """.trimIndent()

        val res = PluginInstaller.parseManifest(fullJson)
        assertTrue("合法 mcp-http 清单应解析成功", res is PluginInstaller.InstallResult.Ok)
        val m = (res as PluginInstaller.InstallResult.Ok).value
        assertEquals("weather", m.id)
        assertEquals("天气查询", m.name)
        assertEquals("1.2.0", m.version)
        assertEquals("mcp-http", m.type)
        assertEquals("https://api.example.com/mcp", m.endpoint)
        assertEquals(listOf("weather-guide"), m.skills)
        assertEquals(listOf("api.example.com"), m.permissions.network)
        assertEquals(listOf("API_KEY"), m.permissions.secrets)
        assertTrue(m.permissions.requiresApproval)
        assertFalse(m.isPureSkill)
        assertTrue(m.isSupportedInStage1)

        val minSkillJson = """
            {
              "schema": 1,
              "id": "ios-review",
              "name": "iOS 代码审查",
              "version": "1.0.0",
              "type": "skill",
              "skills": ["ios-review"]
            }
        """.trimIndent()
        val skillRes = PluginInstaller.parseManifest(minSkillJson)
        assertTrue("最小 skill 清单应解析成功", skillRes is PluginInstaller.InstallResult.Ok)
        val sm = (skillRes as PluginInstaller.InstallResult.Ok).value
        assertTrue(sm.isPureSkill)
        assertEquals(listOf("ios-review"), sm.skills)
    }

    @Test
    fun `manifest 缺字段或非法 schema 或非法 id 或非法 version 被拒`() {
        // 非法 schema
        assertErr(
            PluginInstaller.parseManifest(
                """{"schema":2,"id":"demo","name":"Demo","version":"1.0.0","type":"skill","skills":["s"]}""",
            ),
        )
        // 非法 id（含大写 / 数字开头 / 太短）
        assertErr(
            PluginInstaller.parseManifest(
                """{"schema":1,"id":"BadId","name":"Demo","version":"1.0.0","type":"skill","skills":["s"]}""",
            ),
        )
        assertErr(
            PluginInstaller.parseManifest(
                """{"schema":1,"id":"1abc","name":"Demo","version":"1.0.0","type":"skill","skills":["s"]}""",
            ),
        )
        assertErr(
            PluginInstaller.parseManifest(
                """{"schema":1,"id":"a","name":"Demo","version":"1.0.0","type":"skill","skills":["s"]}""",
            ),
        )
        // 缺 name
        assertErr(
            PluginInstaller.parseManifest(
                """{"schema":1,"id":"demo","version":"1.0.0","type":"skill","skills":["s"]}""",
            ),
        )
        // 非法 version（非 x.y.z）
        assertErr(
            PluginInstaller.parseManifest(
                """{"schema":1,"id":"demo","name":"Demo","version":"v1.0.0","type":"skill","skills":["s"]}""",
            ),
        )
        assertErr(
            PluginInstaller.parseManifest(
                """{"schema":1,"id":"demo","name":"Demo","version":"1.0","type":"skill","skills":["s"]}""",
            ),
        )
    }

    // ---------------------------------------------------------------- 2. type 与必填字段匹配

    @Test
    fun `type 与必填字段不匹配时拒绝`() {
        // skill 缺 skills[]
        assertErr(
            PluginInstaller.parseManifest(
                """{"schema":1,"id":"empty-skill","name":"空技能","version":"1.0.0","type":"skill","skills":[]}""",
            ),
        )
        // mcp 缺 command
        assertErr(
            PluginInstaller.parseManifest(
                """{"schema":1,"id":"bad-mcp","name":"MCP","version":"1.0.0","type":"mcp","command":""}""",
            ),
        )
        // mcp-http 缺 endpoint 或非 http(s)
        assertErr(
            PluginInstaller.parseManifest(
                """{"schema":1,"id":"bad-http","name":"HTTP","version":"1.0.0","type":"mcp-http","endpoint":"ftp://a.com"}""",
            ),
        )
        // wasm 阶段 1 可解析，但 isSupportedInStage1 为 false
        val wasmRes = PluginInstaller.parseManifest(
            """{"schema":1,"id":"calc-wasm","name":"Calc","version":"1.0.0","type":"wasm"}""",
        )
        assertTrue(wasmRes is PluginInstaller.InstallResult.Ok)
        assertFalse((wasmRes as PluginInstaller.InstallResult.Ok).value.isSupportedInStage1)
    }

    // ---------------------------------------------------------------- 3. skills[] 与包内目录一致性

    @Test
    fun `skills 数组与包内实际目录不一致时拒绝（多列或漏列）`() {
        val validSkillMd = validSkillMarkdown("s1", "技能一")

        // 情况 A：manifest 声明了 ["s1", "s2"]，但包里只有 skills/s1/SKILL.md（多列）
        val zipMissingDir = createTempPluginZip(
            entries = mapOf(
                "plugin.json" to """
                    {"schema":1,"id":"test-pkg","name":"测试","version":"1.0.0","type":"skill","skills":["s1","s2"]}
                """.trimIndent(),
                "skills/s1/SKILL.md" to validSkillMd,
            ),
        )
        val r1 = PluginInstaller.inspectStagedPackage(
            stagedFile = zipMissingDir,
            sourceFileName = "missing.cfplugin",
            installedPlugins = emptyList(),
            kernelTools = emptyList(),
            builtinSkillSlugs = emptySet(),
        )
        assertErr(r1)
        zipMissingDir.delete()

        // 情况 B：manifest 只声明了 ["s1"]，但包里多出了 skills/undeclared/SKILL.md（漏列）
        val zipExtraDir = createTempPluginZip(
            entries = mapOf(
                "plugin.json" to """
                    {"schema":1,"id":"test-pkg","name":"测试","version":"1.0.0","type":"skill","skills":["s1"]}
                """.trimIndent(),
                "skills/s1/SKILL.md" to validSkillMd,
                "skills/undeclared/SKILL.md" to validSkillMarkdown("undeclared", "未声明技能"),
            ),
        )
        val r2 = PluginInstaller.inspectStagedPackage(
            stagedFile = zipExtraDir,
            sourceFileName = "extra.cfplugin",
            installedPlugins = emptyList(),
            kernelTools = emptyList(),
            builtinSkillSlugs = emptySet(),
        )
        assertErr(r2)
        zipExtraDir.delete()
    }

    // ---------------------------------------------------------------- 4. zip 路径穿越防御

    @Test
    fun `zip 条目含路径穿越或绝对路径时被拒绝`() {
        assertErr(PluginInstaller.validateZipEntryName("../x"))
        assertErr(PluginInstaller.validateZipEntryName("skills/../../etc/passwd"))
        assertErr(PluginInstaller.validateZipEntryName("/abs/path.txt"))
        assertErr(PluginInstaller.validateZipEntryName("\\windows\\path.txt"))
        assertTrue(PluginInstaller.validateZipEntryName("skills/ios-review/SKILL.md") is PluginInstaller.InstallResult.Ok)

        // 真实压缩包内带 ../ 穿越条目同样在 inspect 阶段被拦下
        val badZip = createTempPluginZip(
            entries = mapOf(
                "plugin.json" to """
                    {"schema":1,"id":"evil-pkg","name":"Evil","version":"1.0.0","type":"mcp-http","endpoint":"https://example.com/mcp"}
                """.trimIndent(),
                "../escaped.txt" to "pwn",
            ),
        )
        val inspectRes = PluginInstaller.inspectStagedPackage(
            stagedFile = badZip,
            sourceFileName = "evil.cfplugin",
            installedPlugins = emptyList(),
            kernelTools = emptyList(),
            builtinSkillSlugs = emptySet(),
        )
        assertErr(inspectRes)
        badZip.delete()
    }

    // ---------------------------------------------------------------- 5. P-052 SKILL.md 校验

    @Test
    fun `P-052 校验：拒绝块标量、多行缩进与 name 不等于 slug`() {
        // 1. description: | 块标量被拒
        val blockScalarMd = """
            ---
            name: my-skill
            display_name: 测试技能
            description: |
              第一行描述
              第二行描述
            triggers: 测试
            enabled: true
            ---
            # 正文
            内容
        """.trimIndent()
        assertErr(PluginInstaller.validateSkillMarkdown("my-skill", blockScalarMd))

        // 2. description: > 折叠块标量被拒
        val foldedScalarMd = """
            ---
            name: my-skill
            description: >
              折叠行
            ---
            # 正文
        """.trimIndent()
        assertErr(PluginInstaller.validateSkillMarkdown("my-skill", foldedScalarMd))

        // 3. 多行缩进续行被拒
        val indentedMd = """
            ---
            name: my-skill
            description: 第一行
              缩进的第二行
            ---
            # 正文
        """.trimIndent()
        assertErr(PluginInstaller.validateSkillMarkdown("my-skill", indentedMd))

        // 4. name != slug 被拒
        val wrongNameMd = """
            ---
            name: another-name
            description: 扁平单行描述
            ---
            # 正文
        """.trimIndent()
        assertErr(PluginInstaller.validateSkillMarkdown("my-skill", wrongNameMd))

        // 5. 合法扁平单行通过
        val okRes = PluginInstaller.validateSkillMarkdown(
            "my-skill",
            validSkillMarkdown("my-skill", "审计指南"),
        )
        assertTrue(okRes is PluginInstaller.InstallResult.Ok)
        val ref = (okRes as PluginInstaller.InstallResult.Ok).value
        assertEquals("my-skill", ref.slug)
        assertEquals("审计指南", ref.displayName)
        assertEquals(listOf("审计", "检查"), ref.triggers)
    }

    // ---------------------------------------------------------------- 6. 版本比较与冲突判定

    @Test
    fun `版本比较与冲突检查：升级、同版本、降级、工具名冲突、技能名冲突`() {
        assertEquals(
            InstallVersionRelation.FRESH,
            PluginInstaller.classifyVersion(null, "1.0.0"),
        )
        assertEquals(
            InstallVersionRelation.UPGRADE,
            PluginInstaller.classifyVersion("1.0.0", "1.2.0"),
        )
        assertEquals(
            InstallVersionRelation.REINSTALL,
            PluginInstaller.classifyVersion("1.2.0", "1.2.0"),
        )
        assertEquals(
            InstallVersionRelation.DOWNGRADE,
            PluginInstaller.classifyVersion("1.2.0", "1.1.9"),
        )

        // 工具名与内置工具冲突（可执行插件 id == "read_file"）
        val mcpConflictManifest = (PluginInstaller.parseManifest(
            """{"schema":1,"id":"bash","name":"Bash 冲突","version":"1.0.0","type":"mcp","command":"npx"}""",
        ) as PluginInstaller.InstallResult.Ok).value
        assertErr(
            PluginInstaller.checkConflicts(
                manifest = mcpConflictManifest,
                installedPlugins = emptyList(),
                kernelTools = listOf("bash", "read_file", "other.tool"),
                builtinSkillSlugs = emptySet(),
            ),
        )

        // 纯文本 skill 包不检查内置工具撞名，只检查技能 slug 冲突
        val skillManifest = (PluginInstaller.parseManifest(
            """{"schema":1,"id":"bash","name":"Bash 指南","version":"1.0.0","type":"skill","skills":["bash-guide"]}""",
        ) as PluginInstaller.InstallResult.Ok).value
        assertTrue(
            PluginInstaller.checkConflicts(
                manifest = skillManifest,
                installedPlugins = emptyList(),
                kernelTools = listOf("bash"),
                builtinSkillSlugs = setOf("builtin-a"),
            ) is PluginInstaller.InstallResult.Ok,
        )

        // 随包技能与内置技能同名冲突
        assertErr(
            PluginInstaller.checkConflicts(
                manifest = skillManifest,
                installedPlugins = emptyList(),
                kernelTools = emptyList(),
                builtinSkillSlugs = setOf("bash-guide"),
            ),
        )
    }

    // ---------------------------------------------------------------- 7. 权限缺省与未知字段忽略

    @Test
    fun `权限声明缺省、空数组与未知字段优雅忽略`() {
        val json = """
            {
              "schema": 1,
              "id": "clean-pkg",
              "name": "Clean",
              "version": "0.1.0",
              "type": "mcp-http",
              "endpoint": "https://mcp.exa.ai/mcp",
              "unknown_top_field": 12345,
              "permissions": {
                "network": [],
                "unknown_perm": "ignored"
              }
            }
        """.trimIndent()
        val res = PluginInstaller.parseManifest(json)
        assertTrue(res is PluginInstaller.InstallResult.Ok)
        val p = (res as PluginInstaller.InstallResult.Ok).value.permissions
        assertTrue(p.network.isEmpty())
        assertEquals("none", p.filesystem)
        assertTrue(p.secrets.isEmpty())
        assertFalse(p.requiresApproval)
        assertTrue(p.allowedActions.isEmpty())
    }

    // ---------------------------------------------------------------- 8. 落盘 + 多工作区技能同步 + 孤儿清理闭环

    @Test
    fun `完整闭环：落盘安装、多工作区同步、保护用户与内置技能、停用清理与卸载`() {
        val tempRoot = Files.createTempDirectory("cf-plugin-test").toFile()
        try {
            val filesDir = File(tempRoot, "files").apply { mkdirs() }
            val wsA = File(tempRoot, "ws-a").apply { mkdirs() }
            val wsB = File(tempRoot, "ws-b").apply { mkdirs() }

            // 在工作区 A 预先放一个「用户手写无标记技能」与一个「内置技能」
            val userSkillDir = File(wsA, ".codeforge/skills/user-custom").apply { mkdirs() }
            File(userSkillDir, "SKILL.md").writeText("用户自己的宝贵技能")
            val builtinSkillDir = File(wsA, ".codeforge/skills/builtin-doc").apply { mkdirs() }
            File(builtinSkillDir, "SKILL.md").writeText("内置技能内容")
            File(builtinSkillDir, PluginSkills.BUILTIN_MARKER).writeText("1")

            // 造一个合法的纯文本技能插件包 v1.0.0
            val pkgV1 = createTempPluginZip(
                entries = mapOf(
                    "plugin.json" to """
                        {
                          "schema": 1,
                          "id": "ios-review",
                          "name": "iOS 审查",
                          "version": "1.0.0",
                          "type": "skill",
                          "skills": ["ios-review"]
                        }
                    """.trimIndent(),
                    "skills/ios-review/SKILL.md" to validSkillMarkdown("ios-review", "iOS 审查 v1"),
                    "README.md" to "# iOS Review Plugin",
                ),
            )

            val inspectV1 = PluginInstaller.inspectStagedPackage(
                stagedFile = pkgV1,
                sourceFileName = "ios-review-1.0.0.cfplugin",
                installedPlugins = emptyList(),
                kernelTools = emptyList(),
                builtinSkillSlugs = setOf("builtin-doc"),
            )
            assertTrue(inspectV1 is PluginInstaller.InstallResult.Ok)
            val pendingV1 = (inspectV1 as PluginInstaller.InstallResult.Ok).value

            val commitV1 = PluginInstaller.commitStagedInstall(filesDir, pendingV1)
            assertTrue(commitV1 is PluginInstaller.InstallResult.Ok)
            pkgV1.delete()

            // 同步到工作区 A 与工作区 B
            PluginSkills.sync(filesDir, wsA.absolutePath)
            PluginSkills.sync(filesDir, wsB.absolutePath)

            assertTrue(PluginSkills.isSkillSynced(wsA.absolutePath, "ios-review", "1.0.0", "ios-review"))
            assertTrue(PluginSkills.isSkillSynced(wsB.absolutePath, "ios-review", "1.0.0", "ios-review"))

            // 停用插件后，同步器应清理工作区 A 和 B 的插件技能副本，但绝不碰用户自建技能与内置技能
            PluginInstaller.setPluginEnabled(filesDir, "ios-review", false)
            PluginSkills.sync(filesDir, wsA.absolutePath)
            PluginSkills.sync(filesDir, wsB.absolutePath)

            assertFalse(File(wsA, ".codeforge/skills/ios-review").exists())
            assertFalse(File(wsB, ".codeforge/skills/ios-review").exists())
            assertEquals("用户自己的宝贵技能", File(userSkillDir, "SKILL.md").readText())
            assertTrue(File(builtinSkillDir, "SKILL.md").isFile)

            // 重新启用后恢复铺放
            PluginInstaller.setPluginEnabled(filesDir, "ios-review", true)
            PluginSkills.sync(filesDir, wsA.absolutePath)
            assertTrue(PluginSkills.isSkillSynced(wsA.absolutePath, "ios-review", "1.0.0", "ios-review"))

            // 卸载后彻底清除
            assertTrue(PluginInstaller.uninstall(filesDir, "ios-review"))
            PluginSkills.sync(filesDir, wsA.absolutePath)
            assertFalse(File(wsA, ".codeforge/skills/ios-review").exists())
            assertTrue(PluginInstaller.listInstalled(filesDir).isEmpty())
            assertNotNull(File(userSkillDir, "SKILL.md").takeIf { it.isFile })
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------- 辅助构造

    private fun validSkillMarkdown(slug: String, displayName: String): String = """
        ---
        name: $slug
        display_name: $displayName
        description: 扁平单行技能描述，供内核 strings.Cut 正常解析
        triggers: 审计, 检查
        enabled: true
        ---
        # $displayName
        1. 第一步检查
        2. 第二步验证
    """.trimIndent()

    private fun createTempPluginZip(entries: Map<String, String>): File {
        val file = Files.createTempFile("test-plugin-", ".cfplugin").toFile()
        ZipOutputStream(file.outputStream().buffered()).use { zos ->
            for ((path, content) in entries) {
                zos.putNextEntry(ZipEntry(path))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return file
    }

    private fun <T> assertErr(result: PluginInstaller.InstallResult<T>) {
        assertTrue(
            "期望返回 Err，实际返回了 $result",
            result is PluginInstaller.InstallResult.Err,
        )
    }
}
