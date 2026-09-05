package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * The editor's page written out for looking at: two documents, one
 * Arabic and one English, holding everything the page draws — the
 * heading levels, set words, a list, a ruled table with a head and a
 * fill, a picture, a note, the band of doubt in two colours, a line set
 * to a tab stop — as files under `build/editor-look/`, which the look
 * script beside the spike (`src/test/spike/editor-look.mjs`) opens in
 * headless Chromium at a phone's size and a tablet's, in light and in
 * dark, and photographs. The photographs are for a person to look at;
 * this test only asserts that the pages were written.
 */
class EditorLookTest {

    @Test
    fun `the pages to look at are written`() {
        val out = File("build/editor-look").apply { mkdirs() }
        File(out, "arabic.html").writeText(HtmlWriter.writeEditor(arabic()))
        File(out, "english.html").writeText(HtmlWriter.writeEditor(english()))
        assertTrue(File(out, "arabic.html").length() > 1000)
    }

    private fun p(vararg runs: TextRun, style: ParagraphStyle = ParagraphStyle(), confidence: Float = 1f) =
        Paragraph(runs.toList(), style, confidence)

    private fun r(text: String, bold: Boolean = false, italic: Boolean = false, link: String? = null, comments: List<Int> = emptyList()) =
        TextRun(text, bold = bold, italic = italic, link = link, commentIds = comments)

    private fun cell(text: String, bold: Boolean = false, span: Int = 1, fill: Int? = null) =
        TableCell(listOf(Paragraph(listOf(TextRun(text, bold = bold)))), columnSpan = span, shadingRgb = fill)

    private fun arabic(): DocumentModel {
        val rtl = ParagraphStyle(direction = TextDirection.RTL)
        return DocumentModel(
            blocks = listOf(
                p(r("الاستمارة في البحث العلمي"), style = rtl.copy(kind = ParagraphKind.TITLE, alignment = Alignment.CENTER)),
                p(r("د. سميرة بن عيسى — جامعة الجزائر"), style = rtl.copy(alignment = Alignment.CENTER)),
                p(r("مقدمة"), style = rtl.copy(kind = ParagraphKind.HEADING_1)),
                p(r("تُعدّ الاستمارة من أهم أدوات جمع البيانات في البحث العلمي، وهي "), r("مجموعة من الأسئلة", bold = true), r(" تُوجَّه إلى المبحوثين للحصول على معلومات حول موضوع معين. وتختلف الاستمارة عن المقابلة في أن الباحث "), r("لا يكون حاضراً", italic = true), r(" عند الإجابة."), style = rtl.copy(alignment = Alignment.JUSTIFY)),
                p(r("أنواع الاستمارة"), style = rtl.copy(kind = ParagraphKind.HEADING_2)),
                p(r("الاستمارة المغلقة: أسئلة محددة الإجابات."), style = rtl.copy(listMarker = ListMarker.NUMBERED)),
                p(r("الاستمارة المفتوحة: يترك فيها المجال للمبحوث."), style = rtl.copy(listMarker = ListMarker.NUMBERED)),
                p(r("الاستمارة المختلطة، وهي الأكثر شيوعاً في الدراسات الميدانية."), style = rtl.copy(listMarker = ListMarker.NUMBERED), confidence = 0.6f),
                p(r("جدول 1: توزيع أفراد العينة حسب الجنس والفئة العمرية"), style = rtl.copy(kind = ParagraphKind.HEADING_3)),
                Table(
                    rows = listOf(
                        TableRow(listOf(cell("الفئة", bold = true, fill = 0xE8EEF7), cell("ذكور", bold = true, fill = 0xE8EEF7), cell("إناث", bold = true, fill = 0xE8EEF7), cell("المجموع", bold = true, fill = 0xE8EEF7)), repeatsAsHeader = true),
                        TableRow(listOf(cell("20–29"), cell("14"), cell("22"), cell("36"))),
                        TableRow(listOf(cell("30–39"), cell("18"), cell("11"), cell("29"))),
                        TableRow(listOf(cell("المجموع", bold = true, span = 3), cell("65", bold = true))),
                    ),
                    columnWidthsPt = listOf(120f, 90f, 90f, 100f),
                    direction = TextDirection.RTL,
                ),
                p(r("تُبيّن نتائج الجدول أن نسبة الإناث "), r("أعلى", comments = listOf(1)), r(" في الفئة الأولى، وهو ما "), r("يتفق مع الدراسات السابقة", link = "https://example.org/prior"), r("."), style = rtl.copy(alignment = Alignment.JUSTIFY), confidence = 0.5f),
                ImageBlock(picture(), "image/png", 240, 140, widthPt = 240f, heightPt = 140f, description = "ختم الكلية"),
                p(r("الاسم:\tسميرة"), style = rtl.copy(tabStopsPt = listOf(120f))),
            ),
            defaultDirection = TextDirection.RTL,
            defaultLanguage = "ar",
            comments = listOf(Comment(1, "يُراجَع الرقم مع الجدول الأصلي", "ر. م.")),
            pageSetup = PageSetup(595.3f, 841.9f, 72f, 72f, 72f, 72f),
            properties = DocumentProperties(title = "الاستمارة في البحث العلمي"),
        )
    }

    private fun english(): DocumentModel = DocumentModel(
        blocks = listOf(
            p(r("The Questionnaire in Scientific Research"), style = ParagraphStyle(kind = ParagraphKind.TITLE)),
            p(r("Introduction"), style = ParagraphStyle(kind = ParagraphKind.HEADING_1)),
            p(r("The questionnaire is among the most important instruments for gathering data, "), r("a set of questions", bold = true), r(" put to respondents, and it differs from the interview in that the researcher "), r("is not present", italic = true), r(" when it is answered. See "), r("the prior work", link = "https://example.org/prior"), r("."), style = ParagraphStyle(alignment = Alignment.JUSTIFY)),
            p(r("Kinds of questionnaire"), style = ParagraphStyle(kind = ParagraphKind.HEADING_2)),
            p(r("Closed: the answers are fixed."), style = ParagraphStyle(listMarker = ListMarker.BULLET)),
            p(r("Open: the respondent writes freely."), style = ParagraphStyle(listMarker = ListMarker.BULLET)),
            p(r("Mixed, the commonest in field studies."), style = ParagraphStyle(listMarker = ListMarker.BULLET, listLevel = 1), confidence = 0.6f),
            Table(
                rows = listOf(
                    TableRow(listOf(cell("Group", bold = true, fill = 0xE8EEF7), cell("Men", bold = true, fill = 0xE8EEF7), cell("Women", bold = true, fill = 0xE8EEF7), cell("Total", bold = true, fill = 0xE8EEF7)), repeatsAsHeader = true),
                    TableRow(listOf(cell("20–29"), cell("14"), cell("22"), cell("36"))),
                    TableRow(listOf(cell("Total", bold = true, span = 3), cell("36", bold = true))),
                ),
                columnWidthsPt = listOf(120f, 90f, 90f, 100f),
            ),
            p(r("The table shows women "), r("outnumber", comments = listOf(1)), r(" men in the first group."), confidence = 0.5f),
            ImageBlock(picture(), "image/png", 240, 140, widthPt = 240f, heightPt = 140f, description = "the faculty's seal"),
        ),
        comments = listOf(Comment(1, "Check against the original table", "R. M.")),
        pageSetup = PageSetup(612f, 792f, 72f, 72f, 72f, 72f),
        properties = DocumentProperties(title = "The Questionnaire"),
    )

    /** A picture to look at: a soft gradient with a darker frame, 240 by 140. */
    private fun picture(): ByteArray {
        val width = 240
        val height = 140
        val raw = ByteArrayOutputStream()
        for (y in 0 until height) {
            raw.write(0)
            for (x in 0 until width) {
                val edge = x < 6 || y < 6 || x >= width - 6 || y >= height - 6
                val r = if (edge) 0x2b else 0x9c + (x * 60 / width)
                val g = if (edge) 0x4a else 0xb8 + (y * 40 / height)
                val b = if (edge) 0x6e else 0xd8
                raw.write(r); raw.write(g); raw.write(b)
            }
        }
        fun chunk(type: String, data: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            val length = data.size
            out.write(byteArrayOf((length ushr 24).toByte(), (length ushr 16).toByte(), (length ushr 8).toByte(), length.toByte()))
            val typed = type.toByteArray(Charsets.US_ASCII) + data
            out.write(typed)
            val crc = CRC32().apply { update(typed) }.value
            out.write(byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte()))
            return out.toByteArray()
        }
        val header = ByteArrayOutputStream().apply {
            for (v in listOf(width, height)) write(byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()))
            write(byteArrayOf(8, 2, 0, 0, 0))
        }.toByteArray()
        val deflater = Deflater()
        deflater.setInput(raw.toByteArray())
        deflater.finish()
        val compressed = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (!deflater.finished()) compressed.write(buffer, 0, deflater.deflate(buffer))
        return byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10) +
            chunk("IHDR", header) + chunk("IDAT", compressed.toByteArray()) + chunk("IEND", ByteArray(0))
    }
}
