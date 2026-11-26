package com.hxy.codeforge.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** OpenRender IR JSON 解析与 delta 应用单测（校验线格式键名与结构）。 */
class RenderIrTest {

    private val delta = """
        {"v":1,"rev":1,"ops":[
          {"op":"put","block":{"id":0,"type":"heading","level":2,
            "spans":[{"type":"text","text":"标题","bold":true}]}},
          {"op":"put","block":{"id":1,"type":"table","table":{
            "align":["left","center","right"],
            "header":[[{"type":"text","text":"A"}],[{"type":"text","text":"B"}],[{"type":"text","text":"C"}]],
            "rows":[[[{"type":"text","text":"1"}],[{"type":"text","text":"2"}],[{"type":"text","text":"3"}]]]}}},
          {"op":"put","block":{"id":2,"type":"paragraph","spans":[
            {"type":"image","src":"attachments/a.png","alt":"示意图","width":640,"height":360},
            {"type":"latex_inline","tex":"E=mc^2","math":{"mime":"image/png","width":166,"height":45,"data":"aGk="}}]}},
          {"op":"put","block":{"id":3,"type":"list_item","depth":1,"marker":{"ordered":false},"checked":true,
            "spans":[{"type":"text","text":"任务"}]}},
          {"op":"put","block":{"id":4,"type":"code_block","lang":"rust","text":"let x = 1;","closed":false}}
        ]}
    """.trimIndent()

    @Test
    fun parsesAllBlockAndSpanKinds() {
        val applier = RenderApplier()
        assertTrue(applier.apply(delta))
        val blocks = applier.snapshot()
        assertEquals(5, blocks.size)

        val heading = blocks[0]
        assertEquals("heading", heading.type)
        assertEquals(2, heading.level)
        assertTrue(heading.spans[0].bold)

        val table = blocks[1].table
        assertNotNull(table)
        assertEquals(listOf("left", "center", "right"), table!!.align)
        assertEquals(3, table.header.size)
        assertEquals("3", table.rows[0][2][0].text)

        val paragraph = blocks[2]
        val image = paragraph.spans[0]
        assertEquals("image", image.type)
        assertEquals("attachments/a.png", image.src)
        assertEquals("示意图", image.text)
        assertEquals(640, image.width)

        val math = paragraph.spans[1]
        assertEquals("latex_inline", math.type)
        assertEquals("E=mc^2", math.text)
        val mathImage = math.math
        assertNotNull(mathImage)
        assertEquals("image/png", mathImage!!.mime)
        assertEquals(2, mathImage.data.size)

        val item = blocks[3]
        assertEquals(1, item.depth)
        assertEquals(false, item.ordered)
        assertEquals(true, item.checked)

        val code = blocks[4]
        assertEquals("rust", code.lang)
        assertFalse(code.closed)
    }

    @Test
    fun appliesAppendCloseAndRemove() {
        val applier = RenderApplier()
        applier.apply(delta)
        applier.apply(
            """{"v":1,"rev":2,"ops":[{"op":"append","id":4,"text":"\nlet y = 2;"}]}""",
        )
        assertEquals("let x = 1;\nlet y = 2;", applier.snapshot()[4].text)

        applier.apply("""{"v":1,"rev":3,"ops":[{"op":"close","id":4}]}""")
        assertTrue(applier.snapshot()[4].closed)

        applier.apply("""{"v":1,"rev":4,"ops":[{"op":"remove","id":2}]}""")
        assertEquals(4, applier.snapshot().size)
        assertNull(applier.snapshot().firstOrNull { it.id == 2L })
    }

    @Test
    fun normalizesLatexDelimitersOutsideCodeAndMath() {
        assertEquals("\$x\$", normalizeMathDelimiters("""\(x\)"""))
        assertEquals("\$\$x\$\$", normalizeMathDelimiters("""\[x\]"""))
        assertEquals(
            "\$\$a\$\$ and \$b\$",
            normalizeMathDelimiters("""\[a\] and \(b\)"""),
        )

        // 代码围栏里的反斜杠是代码内容，必须原样保留
        val fenced = "```latex\n\\(x\\)\n```"
        assertEquals(fenced, normalizeMathDelimiters(fenced))

        // $$ 块内的 \[2pt] 是行距命令，不能当块级定界符
        val spacing = """$$ a \\[2pt] b $$"""
        assertEquals(spacing, normalizeMathDelimiters(spacing))

        // 单行内嵌 ```code``` 不得被误判为多行围栏开启而禁用后续公式归一
        val inlineTriple = """用 ```code``` 表示，公式 \(a+b\)"""
        assertEquals("用 ```code``` 表示，公式 \$a+b\$", normalizeMathDelimiters(inlineTriple))
    }

    @Test
    fun normalizesGluedFenceOpenersAndMarkdownTables() {
        val gluedSvg = """
            ```svg<svg viewBox="0 0 400 320" xmlns="http://www.w3.org/2000/svg">
              <text x="20" y="26">hi</text>
            </svg>
            ```
        """.trimIndent()
        val expectedSvg = """
            ```svg
            <svg viewBox="0 0 400 320" xmlns="http://www.w3.org/2000/svg">
              <text x="20" y="26">hi</text>
            </svg>
            ```
        """.trimIndent()
        assertEquals(expectedSvg, normalizeMarkdownForRender(gluedSvg))

        // 标题/正文与 5 列表头粘连在同一行时须拆分并补空行
        val gluedTable = """
            二线：值得考虑| # | 选题 | 一句话 | 赛道 | 核心资产 |
            |---|---|---|---|---|
            | 5 | 复习提纲 | 描述 | 学习 | 算法 |
        """.trimIndent()
        val expectedTable = """
            二线：值得考虑

            | # | 选题 | 一句话 | 赛道 | 核心资产 |
            |---|---|---|---|---|
            | 5 | 复习提纲 | 描述 | 学习 | 算法 |
        """.trimIndent()
        assertEquals(expectedTable, normalizeMarkdownForRender(gluedTable))

        // 无前导 | 的正常表头不得被误拆
        val pipeLessTable = """
            A | B | C |
            ---|---|---|
            1 | 2 | 3 |
        """.trimIndent()
        assertEquals(pipeLessTable, normalizeMarkdownForRender(pipeLessTable))

        // 代码围栏内部样例不可被修改
        val fencedTable = """
            ```markdown
            前缀| A | B |
            |---|---|
            ```
        """.trimIndent()
        assertEquals(fencedTable, normalizeMarkdownForRender(fencedTable))
    }

    @Test
    fun nativeFailureFallsBack() {
        val applier = RenderApplier()
        assertFalse(applier.apply(null))
        assertTrue(applier.failed)

        val other = RenderApplier()
        assertFalse(other.apply("""{"v":1,"rev":0,"ops":[],"error":"internal_error"}"""))
        assertTrue(other.failed)
    }

}
