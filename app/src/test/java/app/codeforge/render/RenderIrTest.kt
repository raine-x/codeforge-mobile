package app.codeforge.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OpenRender IR 解析的 JVM 单测。
 *
 * 这里断言的是**线格式**（`json.rs` 的键名与值），不跑 Compose：
 * 键名一旦与引擎不一致，界面会表现为「有正文但块是空的」，真机很难定位。
 */
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
