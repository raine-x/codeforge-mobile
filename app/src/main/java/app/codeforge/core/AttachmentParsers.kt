package app.codeforge.core

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 附件自带解析器：把「模型原生读不了」的格式在壳侧转成它读得了的形态。
 *
 * 背景：直连测试模型没有文件工具语义，附件必须在上行时就地展开。图片走
 * vision、纯文本直接内联，剩下最常见的两类：
 *
 *  - PDF：Android 自带 [PdfRenderer] 只能渲染成位图 → 按页出图，视觉模型
 *    直接看（非视觉模型收不了图，这是平台能力的边界，不是解析器的）。
 *  - DOCX / XLSX / PPTX：本质是 ZIP+XML，不引 POI（几十 MB 级依赖），
 *    直接抽 XML 文本节点 —— 保内容不保排版，对「读文档」够用。
 *
 * 解析全在调用方给的 IO 线程跑；任何失败返回 null 由调用方降级为「忽略」。
 */
object AttachmentParsers {

    sealed interface Parsed {
        /** 解析出的纯文本（已含截断处理）。 */
        data class Text(val body: String) : Parsed

        /** 按页/张渲染出的位图。 */
        data class Images(val bitmaps: List<Bitmap>) : Parsed
    }

    private const val MAX_TEXT = 300_000
    private const val MAX_PDF_PAGES = 10
    private const val MAX_PDF_DIM = 1280

    fun parse(file: File): Parsed? = runCatching {
        when (file.extension.lowercase()) {
            "pdf" -> parsePdf(file)?.let { Parsed.Images(it) }
            "docx" -> parseDocx(file)?.let { Parsed.Text(it) }
            "xlsx" -> parseXlsx(file)?.let { Parsed.Text(it) }
            "pptx" -> parsePptx(file)?.let { Parsed.Text(it) }
            else -> null
        }
    }.getOrNull()

    // ---------------------------------------------------------------- PDF

    private fun parsePdf(file: File): List<Bitmap>? =
        android.os.ParcelFileDescriptor.open(
            file,
            android.os.ParcelFileDescriptor.MODE_READ_ONLY,
        ).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                if (renderer.pageCount <= 0) return null
                val pages = minOf(renderer.pageCount, MAX_PDF_PAGES)
                (0 until pages).mapNotNull { i ->
                    renderer.openPage(i).use { page ->
                        var w = page.width * 2
                        var h = page.height * 2
                        if (maxOf(w, h) > MAX_PDF_DIM) {
                            val s = MAX_PDF_DIM.toFloat() / maxOf(w, h)
                            w = (w * s).toInt(); h = (h * s).toInt()
                        }
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        // PDF 页面是透明底，不垫白字会看不清
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                }
            }
        }

    // ---------------------------------------------------------------- Office（ZIP + XML）

    /** 读取 zip 内第一个匹配 [namePred] 的条目全文（小文件，整体读进内存）。 */
    private fun zipEntryText(file: File, namePred: (String) -> Boolean): String? {
        ZipInputStream(file.inputStream().buffered()).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                if (!e.isDirectory && namePred(e.name)) {
                    return zis.readBytes().toString(Charsets.UTF_8)
                }
                zis.closeEntry()
            }
        }
        return null
    }

    /** docx：段落 </w:p> 换行、tab 制表，其余剥标签。 */
    private fun parseDocx(file: File): String? {
        val xml = zipEntryText(file) { it == "word/document.xml" } ?: return null
        return truncate(
            xml.replace("</w:p>", "\n")
                .replace(Regex("<w:tab[^>]*/>"), "\t")
                .replace(Regex("<w:br[^>]*/>"), "\n")
                .replace(Regex("<[^>]+>"), "")
                .let(::unescape)
                .trim(),
        )
    }

    /** xlsx：共享字符串表 + 各 sheet 的单元格值，行列用制表/换行还原。 */
    private fun parseXlsx(file: File): String? {
        val shared = zipEntryText(file) { it == "xl/sharedStrings.xml" }
            ?.let { shared ->
                Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL)
                    .findAll(shared)
                    .map { si -> Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL).findAll(si.value).joinToString("") { it.groupValues[1] } }
                    .map(::unescape)
                    .toList()
            } ?: emptyList()

        val sheets = mutableListOf<String>()
        ZipInputStream(file.inputStream().buffered()).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                if (!e.isDirectory && Regex("""^xl/worksheets/sheet\d+\.xml$""").matches(e.name)) {
                    val xml = zis.readBytes().toString(Charsets.UTF_8)
                    sheets += sheetToRows(xml, shared)
                }
                zis.closeEntry()
            }
        }
        if (sheets.isEmpty()) return null
        return truncate(sheets.joinToString("\n\n").trim())
    }

    /** sheet XML → 行文本：t="s" 查共享表，inlineStr 取 <t>，其余取 <v> 原文。 */
    private fun sheetToRows(xml: String, shared: List<String>): String {
        val rows = Regex("<row[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { row ->
            val cells = mutableListOf<String>()
            // 当前单元格是否共享字符串引用（开标签带 t="s"，<v> 里是下标不是值）
            var sharedRef = false
            Regex("""<c\b[^>]*>|</c>|<v>(.*?)</v>|<t[^>]*>(.*?)</t>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(row.value)
                .forEach { m ->
                    val v = m.value
                    when {
                        v.startsWith("<c") -> {
                            cells += ""
                            sharedRef = v.contains("t=\"s\"")
                        }

                        v == "</c>" -> sharedRef = false

                        m.groupValues[1].isNotEmpty() ->
                            cells[cells.lastIndex] = if (sharedRef) {
                                shared.getOrNull(m.groupValues[1].toIntOrNull() ?: -1).orEmpty()
                            } else {
                                m.groupValues[1]
                            }

                        m.groupValues[2].isNotEmpty() ->
                            cells[cells.lastIndex] = unescape(m.groupValues[2])
                    }
                }
            cells.joinToString("\t")
        }
        return rows.joinToString("\n")
    }

    /** pptx：每张幻灯片的 <a:t> 文本串起来，页间空行分隔。 */
    private fun parsePptx(file: File): String? {
        val slides = mutableListOf<Pair<Int, String>>()
        ZipInputStream(file.inputStream().buffered()).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                val m = Regex("""^ppt/slides/slide(\d+)\.xml$""").find(e.name)
                if (!e.isDirectory && m != null) {
                    val xml = zis.readBytes().toString(Charsets.UTF_8)
                    val text = Regex("<a:t>(.*?)</a:t>", RegexOption.DOT_MATCHES_ALL)
                        .findAll(xml)
                        .joinToString(" ") { unescape(it.groupValues[1]) }
                    slides += m.groupValues[1].toInt() to text
                }
                zis.closeEntry()
            }
        }
        if (slides.isEmpty()) return null
        return truncate(
            slides.sortedBy { it.first }
                .joinToString("\n\n") { "[幻灯片 ${it.first}]\n${it.second.trim()}" },
        )
    }

    // ---------------------------------------------------------------- 工具

    private fun unescape(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&") // amp 最后还原，避免二次解义

    private fun truncate(s: String): String =
        if (s.length <= MAX_TEXT) s else s.take(MAX_TEXT) + "\n…（内容过长已截断）"
}
