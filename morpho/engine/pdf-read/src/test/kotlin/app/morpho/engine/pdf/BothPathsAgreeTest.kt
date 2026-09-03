package app.morpho.engine.pdf

import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.TextDirection
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList
import org.apache.pdfbox.pdmodel.documentinterchange.taggedpdf.StandardStructureTypes
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * The two readers, on the same page.
 *
 * A tagged PDF is read from its tags and an untagged one from where its
 * glyphs sit, and the two are entirely separate bodies of code. They are
 * meant to arrive at the same document: the same words, in the same
 * order, right way round. This takes one page painted the way a real
 * paper is painted — Arabic set right to left, glyph by glyph, a heading,
 * an indented paragraph, a line of dates spread on tabs — and reads it
 * both ways, once with its tags and once with them taken away.
 *
 * The paper this was built from agrees on 3712 of its 3714 words. A
 * synthetic page has to do better than that, because nothing about it is
 * accidental.
 */
class BothPathsAgreeTest {

    private val lines = listOf(
        "الاستمارة في البحث العلمي",
        "من شروط البحث العلمي الالمام بجميع المعلومات المتصلة بموضوع البحث",
        "والاطلاع على المصادر والدراسات السابقة، ويتم ذلك من خلال استعمال",
        "مجموعة من الأدوات البحثية، إذ لكل نوع من الأبحاث أدوات مناسبة لها",
        "وتقسم الأسئلة إلى أربعة أنواع وهي كالتالي في هذه الورقة البحثية",
        "الأسئلة المفتوحة أو الحرة، وفيها يترك للمبحوث الإجابة على الأسئلة",
        "المطروحة بطريقته الخاصة وبألفاظه التي يعتبرها ملائمة، ويستخدم هذا",
        "النوع من الأسئلة لما لا يكون لدى الباحث دراية تامة ومعلومات وافية",
    )

    @Test
    fun `the tagged and untagged readers read the same page the same way`() {
        val tagged = paper(tagged = true)
        val untagged = paper(tagged = false)

        val fromTags = words(PdfReader().extract(tagged))
        val fromPositions = words(PdfReader().extract(untagged))

        assertTrue(fromTags.isNotEmpty(), "the tagged reader read nothing")
        val shared = fromPositions.count { it in fromTags.toSet() }
        assertEquals(
            fromPositions.size,
            shared,
            "the untagged reader read words the tagged one did not: " +
                fromPositions.filterNot { it in fromTags.toSet() },
        )
        assertEquals(fromTags, fromPositions, "the two readers put the words in a different order")
    }

    @Test
    fun `both readers see the page as arabic`() {
        for (tagged in listOf(true, false)) {
            val model = PdfReader().extract(paper(tagged = tagged))
            assertEquals(
                TextDirection.RTL,
                model.defaultDirection,
                "the ${if (tagged) "tagged" else "untagged"} reader called an Arabic page left-to-right",
            )
        }
    }

    /**
     * Three paragraphs the page shows as three: its lines a line-pitch
     * apart and its paragraphs half as much again, which is what a page
     * of prose looks like.
     */
    private val prose = listOf(
        listOf(
            "من شروط البحث العلمي الالمام بجميع المعلومات المتصلة بموضوع البحث",
            "والاطلاع على المصادر والدراسات السابقة، ويتم ذلك من خلال استعمال",
            "مجموعة من الأدوات البحثية، إذ لكل نوع من الأبحاث أدوات مناسبة لها",
        ),
        listOf(
            "وتقسم الأسئلة إلى أربعة أنواع وهي كالتالي في هذه الورقة البحثية",
            "الأسئلة المفتوحة أو الحرة، وفيها يترك للمبحوث الإجابة على الأسئلة",
        ),
        listOf(
            "المطروحة بطريقته الخاصة وبألفاظه التي يعتبرها ملائمة، ويستخدم هذا",
            "النوع من الأسئلة لما لا يكون لدى الباحث دراية تامة ومعلومات وافية",
            "عن الموضوع الذي يبحث فيه، وهي أسئلة تحتاج إلى وقت طويل لتحليلها",
        ),
    )

    @Test
    fun `where a page shows its paragraphs, both readers find the same ones`() {
        // The tags say outright where each paragraph begins; the untagged
        // reading has only the space between the lines to go on. Where the
        // page leaves that space, the two must agree — and it is the
        // paragraph splitting, the most-guessed part of the reading, that
        // this holds still. Comparing words alone would not: a reading
        // that ran three paragraphs into one has every word of them.
        //
        // At this pitch the threshold that binds is the one measured in
        // type sizes rather than in line pitches, and widening it is what
        // makes this fail — which is how it was checked to be worth
        // having.
        val fromTags = paragraphsOf(PdfReader().extract(pages(prose, tagged = true)))
        val fromPositions = paragraphsOf(PdfReader().extract(pages(prose, tagged = false)))
        assertEquals(prose.size, fromTags.size, "the tags were not read as they were written: $fromTags")
        assertEquals(
            fromTags,
            fromPositions,
            "the untagged reading found different paragraphs from the ones the producer declared",
        )
    }

    private fun paragraphsOf(model: app.morpho.engine.layout.DocumentModel): List<String> =
        model.blocks.filterIsInstance<Paragraph>()
            .map { it.text.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() }

    /**
     * [paragraphs] painted down one page, each as a tagged paragraph of
     * its own where [tagged], with a plain line's space inside a paragraph
     * and half as much again between them.
     */
    private fun pages(paragraphs: List<List<String>>, tagged: Boolean): ByteArray {
        val bytes = ByteArrayOutputStream()
        PDDocument().use { document ->
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            document.documentCatalog.language = "ar"
            val holder = if (tagged) {
                val root = PDStructureTreeRoot()
                document.documentCatalog.structureTreeRoot = root
                PDStructureElement(StandardStructureTypes.DOCUMENT, root).also {
                    it.page = page
                    root.appendKid(it)
                }
            } else {
                null
            }
            val arabic: PDFont = PDType0Font.load(
                document,
                javaClass.getResourceAsStream("/fonts/NotoNaskhArabic-Regular.ttf") ?: error("test font missing"),
                false,
            )
            var mcid = 0
            var top = 110f
            PDPageContentStream(document, page).use { content ->
                for (lines in paragraphs) {
                    val element = holder?.let {
                        PDStructureElement(StandardStructureTypes.P, it).apply {
                            this.page = page
                            it.appendKid(this)
                        }
                    }
                    val properties = COSDictionary().apply { setInt(COSName.MCID, mcid) }
                    if (tagged) content.beginMarkedContent(COSName.P, PDPropertyList.create(properties))
                    for (line in lines) {
                        val width = arabic.getStringWidth(line) / 1000f * 12f
                        content.beginText()
                        content.setFont(arabic, 12f)
                        content.newLineAtOffset(510f - width, PDRectangle.A4.height - top)
                        content.showText(line.reversed())
                        content.endText()
                        top += LINE_PITCH_PT
                    }
                    if (tagged) {
                        content.endMarkedContent()
                        element?.appendKid(PDMarkedContent(COSName.P, properties))
                    }
                    mcid++
                    top += PARAGRAPH_GAP_PT - LINE_PITCH_PT
                }
            }
            document.save(bytes)
        }
        return bytes.toByteArray()
    }

    /** A line's step down the page, and a paragraph's, as a page of prose sets them. */
    private val LINE_PITCH_PT = 22f
    private val PARAGRAPH_GAP_PT = 34f

    private fun words(model: app.morpho.engine.layout.DocumentModel): List<String> =
        model.blocks.filterIsInstance<Paragraph>()
            .flatMap { it.text.split(Regex("\\s+")) }
            .filter { it.isNotBlank() }

    /** One A4 page of Arabic, painted as a producer paints it, with or without its tags. */
    private fun paper(tagged: Boolean): ByteArray {
        val bytes = ByteArrayOutputStream()
        PDDocument().use { document ->
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            document.documentCatalog.language = "ar"
            val root = PDStructureTreeRoot()
            val docElement: PDStructureElement?
            if (tagged) {
                document.documentCatalog.structureTreeRoot = root
                docElement = PDStructureElement(StandardStructureTypes.DOCUMENT, root)
                docElement.page = page
                root.appendKid(docElement)
            } else {
                docElement = null
            }
            val arabic: PDFont = PDType0Font.load(
                document,
                javaClass.getResourceAsStream("/fonts/NotoNaskhArabic-Regular.ttf") ?: error("test font missing"),
                false,
            )
            var mcid = 0
            PDPageContentStream(document, page).use { content ->
                for ((index, line) in lines.withIndex()) {
                    val size = if (index == 0) 15f else 12f
                    val width = arabic.getStringWidth(line) / 1000f * size
                    val y = PDRectangle.A4.height - (110f + index * 22f)
                    val element = docElement?.let {
                        val kind = if (index == 0) StandardStructureTypes.H1 else StandardStructureTypes.P
                        PDStructureElement(kind, it).apply {
                            this.page = page
                            it.appendKid(this)
                        }
                    }
                    val properties = COSDictionary().apply { setInt(COSName.MCID, mcid) }
                    if (tagged) content.beginMarkedContent(COSName.P, PDPropertyList.create(properties))
                    content.beginText()
                    content.setFont(arabic, size)
                    // Right-aligned, as an Arabic page sets its text.
                    content.newLineAtOffset(510f - width, y)
                    content.showText(line.reversed())
                    content.endText()
                    if (tagged) {
                        content.endMarkedContent()
                        element?.appendKid(PDMarkedContent(COSName.P, properties))
                    }
                    mcid++
                }
            }
            document.save(bytes)
        }
        return bytes.toByteArray()
    }

}
