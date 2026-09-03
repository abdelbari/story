package app.morpho.engine.pdf

import app.morpho.engine.layout.Paragraph
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList
import org.apache.pdfbox.pdmodel.documentinterchange.taggedpdf.StandardStructureTypes
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * A book, a manual, a thesis exported to PDF opens on a contents page
 * whose every line jumps to a page of the same file. Nothing outside a
 * PDF knows what "page 3" means, so a converter either drops those links
 * or writes them as addresses — and a converted manual's contents page
 * then reads like one and does nothing at all.
 */
class ContentsPageTest {

    /**
     * Three pages: a contents page linking to the two chapters after it.
     *
     * With [tagged], the producer says outright what each paragraph is,
     * which is what most real files do — Word says it, and so does a
     * browser printing a page — and a different reading takes over. Every
     * test here used to be run on the untagged one alone, which is how the
     * tagged reading came to be leaving its own marks in the file: it
     * imported the thing that turns a mark into a place and never called
     * it.
     */
    private fun manual(byAction: Boolean, tagged: Boolean = false): ByteArray {
        mcid = 0
        PDDocument().use { doc ->
            val pages = (1..3).map { PDPage(PDRectangle.A4) }
            pages.forEach(doc::addPage)
            val root = if (tagged) {
                PDStructureTreeRoot().also { doc.documentCatalog.structureTreeRoot = it }
            } else {
                null
            }
            val holder = root?.let {
                PDStructureElement(StandardStructureTypes.DOCUMENT, it).also(it::appendKid)
            }
            write(doc, pages[0], listOf("Contents", "1. Beginnings", "2. Endings"), holder)
            write(doc, pages[1], listOf("Beginnings", "How it started."), holder)
            write(doc, pages[2], listOf("Endings", "How it finished."), holder)
            // Each contents line covers the words of one entry.
            for ((index, top) in listOf(676f, 652f).withIndex()) {
                val link = PDAnnotationLink()
                link.rectangle = PDRectangle(70f, top, 140f, 18f)
                val where = PDPageFitWidthDestination().apply { page = pages[index + 1] }
                if (byAction) {
                    link.action = PDActionGoTo().apply { destination = where }
                } else {
                    // A great many producers write the destination on the
                    // link itself and no action at all.
                    link.destination = where
                }
                pages[0].annotations.add(link)
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }

    /** Where the next marked paragraph's number comes from within one file. */
    private var mcid = 0

    private fun write(doc: PDDocument, page: PDPage, lines: List<String>, holder: PDStructureElement?) {
        holder?.page = page
        PDPageContentStream(doc, page).use { content ->
            var y = 700f
            for (line in lines) {
                // One paragraph, drawn as one marked content the tree
                // points at, so the tagged reading has something to read.
                val element = holder?.let {
                    PDStructureElement(StandardStructureTypes.P, it).also { made ->
                        made.page = page
                        it.appendKid(made)
                    }
                }
                if (element != null) {
                    val properties = COSDictionary().apply { setInt(COSName.MCID, mcid) }
                    content.beginMarkedContent(COSName.P, PDPropertyList.create(properties))
                }
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 12f)
                content.newLineAtOffset(72f, y)
                content.showText(line)
                content.endText()
                if (element != null) {
                    content.endMarkedContent()
                    element.appendKid(PDMarkedContent(COSName.P, COSDictionary().apply {
                        setInt(COSName.MCID, mcid)
                    }))
                    mcid++
                }
                y -= 24f
            }
        }
    }

    @Test
    fun `a contents line leads to the chapter it names`() {
        for (byAction in listOf(true, false)) {
            val model = PdfReader().extract(manual(byAction))
            val paragraphs = model.blocks.filterIsInstance<Paragraph>()
            val links = paragraphs.flatMap { it.runs }.mapNotNull { it.link }.distinct()
            assertEquals(listOf("#page2", "#page3"), links.sorted(), "destination written by action=$byAction")

            // And the chapters answer to those names.
            val named = paragraphs.filter { it.bookmarks.isNotEmpty() }
            assertEquals(listOf("page2", "page3"), named.flatMap { it.bookmarks }.sorted())
            assertTrue(named.any { it.text.contains("Beginnings") }, named.map { it.text }.toString())
            assertTrue(named.any { it.text.contains("Endings") }, named.map { it.text }.toString())
        }
    }

    @Test
    fun `no mark of the reader's own reaches the converted file`() {
        for (tagged in listOf(false, true)) {
            val model = PdfReader().extract(manual(byAction = true, tagged = tagged))
            val links = model.blocks.filterIsInstance<Paragraph>().flatMap { it.runs }.mapNotNull { it.link }
            assertTrue(links.none { it.startsWith("morpho:") }, "tagged=$tagged: $links")
        }
    }

    @Test
    fun `a tagged contents line leads to the chapter it names`() {
        // The reading most real files get, and the one that used to leave
        // every internal link of every one of them as a mark.
        val model = PdfReader().extract(manual(byAction = true, tagged = true))
        val paragraphs = model.blocks.filterIsInstance<Paragraph>()
        val links = paragraphs.flatMap { it.runs }.mapNotNull { it.link }.distinct().sorted()
        assertEquals(listOf("#page2", "#page3"), links, paragraphs.map { it.text }.toString())
        val named = paragraphs.filter { it.bookmarks.isNotEmpty() }
        assertEquals(listOf("page2", "page3"), named.flatMap { it.bookmarks }.sorted())
        assertTrue(named.any { it.text.contains("Beginnings") }, named.map { it.text }.toString())
        assertTrue(named.any { it.text.contains("Endings") }, named.map { it.text }.toString())
    }

    @Test
    fun `a link that leads out of the document is left alone`() {
        // Only the pages of this file become names; the web stays the web.
        // Asked of both readings, since a check run on one of them is how
        // the tagged path came to be shipping dead links in the first place.
        for (tagged in listOf(false, true)) {
            val model = PdfReader().extract(manual(byAction = true, tagged = tagged))
            assertNull(
                model.blocks.filterIsInstance<Paragraph>()
                    .flatMap { it.runs }
                    .firstOrNull { it.text.contains("How it started") }
                    ?.link,
                "tagged=$tagged",
            )
        }
    }
}
