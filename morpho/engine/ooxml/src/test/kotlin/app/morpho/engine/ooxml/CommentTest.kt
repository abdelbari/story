package app.morpho.engine.ooxml

import app.morpho.engine.layout.Comment
import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.ParagraphStyle
import app.morpho.engine.layout.Table
import app.morpho.engine.layout.TableCell
import app.morpho.engine.layout.TableRow
import app.morpho.engine.layout.TextDirection
import app.morpho.engine.layout.TextRun
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * What a supervisor wrote in the margin of a thesis is not the thesis,
 * and it is not nothing either: it is the reason the file was sent back.
 * A converter that hands over the document without it hands over the
 * document as it stood before anybody read it.
 */
class CommentTest {

    private fun partsOf(docx: ByteArray): Map<String, String> {
        val parts = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(docx)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                parts[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return parts
    }

    private fun commented(vararg text: String) = Paragraph(
        text.mapIndexed { index, piece -> TextRun(piece, commentIds = if (index == 1) listOf(7) else emptyList()) }
    )

    private val note = Comment(
        id = 7,
        text = "Say which year.",
        author = "Amina Barry",
        dateIso = "2026-09-03T09:15:00Z",
    )

    @Test
    fun `a note is written as a Word comment about the words it is on`() {
        val docx = DocxWriter.toByteArray(
            DocumentModel(
                blocks = listOf(commented("Written in ", "the spring", " of that year.")),
                comments = listOf(note),
            )
        )
        val parts = partsOf(docx)
        val comments = parts["word/comments.xml"] ?: error("no comments part: ${parts.keys}")
        assertTrue(comments.contains("Say which year."), comments)
        assertTrue(comments.contains("""w:author="Amina Barry""""), comments)
        assertTrue(comments.contains("""w:date="2026-09-03T09:15:00Z""""), comments)
        // Word shows these in the margin; taken from the name when the
        // file did not give them.
        assertTrue(comments.contains("""w:initials="AB""""), comments)

        val document = parts["word/document.xml"]!!
        // The stretch the note is about: opened before its words, closed
        // after them, and referred to once.
        val start = document.indexOf("<w:commentRangeStart")
        val end = document.indexOf("<w:commentRangeEnd")
        assertTrue(start in 1 until end, document)
        assertTrue(document.indexOf("Written in ") < start, "the note opens after the words before it")
        assertTrue(document.indexOf("the spring") in start until end, "the note is not about its own words")
        assertTrue(document.indexOf(" of that year.") > end, "the note reaches past the words it is about")
        assertEquals(1, Regex("<w:commentReference").findAll(document).count(), document)

        // Word refuses a package that names a part it does not declare.
        assertTrue(parts["[Content_Types].xml"]!!.contains("/word/comments.xml"), parts["[Content_Types].xml"])
        assertTrue(
            parts["word/_rels/document.xml.rels"]!!.contains("comments.xml"),
            parts["word/_rels/document.xml.rels"],
        )
    }

    @Test
    fun `a note read back from Word says what it said and is about the same words`() {
        val model = DocumentModel(
            blocks = listOf(commented("Written in ", "the spring", " of that year.")),
            comments = listOf(note),
        )
        val read = DocxReader.read(DocxWriter.toByteArray(model))
        assertEquals(1, read.comments.size, read.comments.toString())
        val back = read.comments.single()
        assertEquals("Say which year.", back.text)
        assertEquals("Amina Barry", back.author)
        assertEquals("2026-09-03T09:15:00Z", back.dateIso)
        val runs = (read.blocks.single() as Paragraph).runs
        assertEquals(3, runs.size, runs.map { it.text }.toString())
        assertEquals(emptyList<Int>(), runs[0].commentIds)
        assertEquals(listOf(back.id), runs[1].commentIds, "the note came back about the wrong words")
        assertEquals(emptyList<Int>(), runs[2].commentIds)
    }

    @Test
    fun `the file numbers its own notes, whatever the model called them`() {
        // Word wants its comments numbered from nothing upwards, each once.
        // A model is free to number them any way at all — a reader of a PDF
        // numbers them the way that PDF did — so the writer gives the file
        // its own numbers and keeps the marks in step with them.
        val model = DocumentModel(
            blocks = listOf(
                Paragraph(listOf(TextRun("First.", commentIds = listOf(40)))),
                Paragraph(listOf(TextRun("Second.", commentIds = listOf(-3)))),
            ),
            comments = listOf(Comment(id = -3, text = "Later."), Comment(id = 40, text = "Earlier.")),
        )
        val read = DocxReader.read(DocxWriter.toByteArray(model))
        assertEquals(listOf(0, 1), read.comments.map { it.id })
        // Numbered in the order the text meets them, not the order they
        // were given: the note on the first paragraph is the file's first.
        assertEquals(listOf("Earlier.", "Later."), read.comments.map { it.text })
        val paragraphs = read.blocks.filterIsInstance<Paragraph>()
        assertEquals(listOf(0), paragraphs[0].runs.single().commentIds)
        assertEquals(listOf(1), paragraphs[1].runs.single().commentIds)
    }

    @Test
    fun `a note about a passage keeps hold of every paragraph of it`() {
        val model = DocumentModel(
            blocks = listOf(
                Paragraph(listOf(TextRun("The first claim.", commentIds = listOf(1)))),
                Paragraph(listOf(TextRun("The second, which follows from it.", commentIds = listOf(1)))),
                Paragraph(listOf(TextRun("Something else entirely."))),
            ),
            comments = listOf(Comment(id = 1, text = "This whole argument needs a source.")),
        )
        val document = String(
            partsOf(DocxWriter.toByteArray(model))["word/document.xml"]!!.toByteArray(),
            Charsets.UTF_8,
        )
        // One stretch, not one per paragraph: naming the same note twice
        // over is a file Word opens with the note in two places.
        assertEquals(1, Regex("<w:commentRangeStart").findAll(document).count(), document)
        assertEquals(1, Regex("<w:commentRangeEnd").findAll(document).count(), document)
        assertTrue(
            document.indexOf("The second, which follows from it.") < document.indexOf("<w:commentRangeEnd"),
            "the note stopped at the first paragraph of the passage",
        )

        val read = DocxReader.read(DocxWriter.toByteArray(model))
        // Word numbers the comments of a file itself, from nothing upwards,
        // so what comes back is the file's number for the note and not the
        // one the model happened to give it.
        val id = read.comments.single().id
        val paragraphs = read.blocks.filterIsInstance<Paragraph>()
        assertEquals(listOf(id), paragraphs[0].runs.single().commentIds)
        assertEquals(listOf(id), paragraphs[1].runs.single().commentIds, "the second paragraph lost the note")
        assertEquals(emptyList<Int>(), paragraphs[2].runs.single().commentIds)
    }

    @Test
    fun `a note on a cell of a table is a note on that cell`() {
        val model = DocumentModel(
            blocks = listOf(
                Table(
                    listOf(
                        TableRow(
                            listOf(
                                TableCell(listOf(Paragraph(listOf(TextRun("1200", commentIds = listOf(3)))))),
                                TableCell(listOf(Paragraph(listOf(TextRun("1500"))))),
                            )
                        )
                    )
                )
            ),
            comments = listOf(Comment(id = 3, text = "Where is this figure from?")),
        )
        val read = DocxReader.read(DocxWriter.toByteArray(model))
        assertEquals(1, read.comments.size)
        val id = read.comments.single().id
        val row = (read.blocks.filterIsInstance<Table>().single()).rows.single()
        assertEquals(listOf(id), (row.cells[0].blocks.single() as Paragraph).runs.single().commentIds)
        assertEquals(emptyList<Int>(), (row.cells[1].blocks.single() as Paragraph).runs.single().commentIds)
    }

    @Test
    fun `a note whose subject stops and starts again points at the first stretch only`() {
        // Word gives a comment one stretch of the text and no more, so a
        // subject that stops and starts again cannot be written as it
        // stands. A stretch from the first run to the last would swallow
        // everything between them — which is what a note left beside one
        // line of a two-column page would do to half a column — so the
        // note keeps the unbroken stretch it opens on. Pointing at less of
        // the subject is honest; pointing at text it is not about is not.
        val model = DocumentModel(
            blocks = listOf(
                Paragraph(
                    listOf(
                        TextRun("about this", commentIds = listOf(1)),
                        TextRun(" not this "),
                        TextRun("nor this"),
                        TextRun(" but this too", commentIds = listOf(1)),
                    )
                )
            ),
            comments = listOf(Comment(id = 1, text = "Which is it?")),
        )
        val read = DocxReader.read(DocxWriter.toByteArray(model))
        val id = read.comments.single().id
        val covered = (read.blocks.single() as Paragraph).runs
            .filter { id in it.commentIds }
            .joinToString("") { it.text }
        assertEquals("about this", covered, "the note reached past the stretch it opened on")
    }

    @Test
    fun `a note over runs standing next to each other keeps every one of them`() {
        // The ordinary case, and the one the rule above must not spoil: a
        // reader's highlight covers every word under it, so the runs it
        // marks stand next to one another and the whole of it is kept.
        val model = DocumentModel(
            blocks = listOf(
                Paragraph(
                    listOf(
                        TextRun("before "),
                        TextRun("all", commentIds = listOf(1)),
                        TextRun(" of", commentIds = listOf(1)),
                        TextRun(" this", commentIds = listOf(1)),
                        TextRun(" after"),
                    )
                )
            ),
            comments = listOf(Comment(id = 1, text = "Source?")),
        )
        val read = DocxReader.read(DocxWriter.toByteArray(model))
        val id = read.comments.single().id
        val covered = (read.blocks.single() as Paragraph).runs
            .filter { id in it.commentIds }
            .joinToString("") { it.text }
        assertEquals("all of this", covered)
    }

    @Test
    fun `a document nobody has commented on is written as it always was`() {
        val docx = DocxWriter.toByteArray(
            DocumentModel(listOf(Paragraph(listOf(TextRun("Nothing to say about this.")))))
        )
        val parts = partsOf(docx)
        assertFalse("word/comments.xml" in parts, parts.keys.toString())
        assertFalse(parts["word/document.xml"]!!.contains("comment"), parts["word/document.xml"])
        assertFalse(parts["[Content_Types].xml"]!!.contains("comments"), parts["[Content_Types].xml"])
    }

    @Test
    fun `a note nothing is about is left out rather than written where nobody can see it`() {
        val docx = DocxWriter.toByteArray(
            DocumentModel(
                blocks = listOf(Paragraph(listOf(TextRun("Plain text.")))),
                comments = listOf(Comment(id = 9, text = "A note about nothing.")),
            )
        )
        assertFalse("word/comments.xml" in partsOf(docx), "a note with nothing to point at was written anyway")
    }

    @Test
    fun `a note left in Arabic comes back the way it was written`() {
        val model = DocumentModel(
            blocks = listOf(Paragraph(listOf(TextRun("المنهج الوصفي", commentIds = listOf(2))))),
            defaultDirection = TextDirection.RTL,
            comments = listOf(Comment(id = 2, text = "وضّح المصدر.", author = "المشرف")),
        )
        val docx = DocxWriter.toByteArray(model)
        // The note is a paragraph of an Arabic document and is laid out
        // like one, rather than turned round in the margin.
        assertTrue(partsOf(docx)["word/comments.xml"]!!.contains("<w:bidi/>"), partsOf(docx)["word/comments.xml"])
        val read = DocxReader.read(docx)
        assertEquals("وضّح المصدر.", read.comments.single().text)
        assertEquals("المشرف", read.comments.single().author)
    }

    @Test
    fun `a note of more than one paragraph keeps its second paragraph`() {
        val model = DocumentModel(
            blocks = listOf(Paragraph(listOf(TextRun("A claim.", commentIds = listOf(4))))),
            comments = listOf(Comment(id = 4, text = "Two things.\nThe second of them.")),
        )
        val read = DocxReader.read(DocxWriter.toByteArray(model))
        assertEquals("Two things.\nThe second of them.", read.comments.single().text)
    }

    @Test
    fun `a mark left behind by a deleted comment anchors nothing`() {
        // Word leaves the marks in the text when a comment is deleted from
        // an older file. A reader that trusted them would say a note is
        // about these words and then have no note to show.
        val model = DocumentModel(
            blocks = listOf(Paragraph(listOf(TextRun("Still here.", commentIds = listOf(5))))),
            comments = emptyList(),
        )
        val read = DocxReader.read(DocxWriter.toByteArray(model))
        assertTrue(read.comments.isEmpty())
        assertEquals(emptyList<Int>(), (read.blocks.single() as Paragraph).runs.single().commentIds)
    }

    @Test
    fun `a note on a linked address is a note on the link`() {
        val model = DocumentModel(
            blocks = listOf(
                Paragraph(
                    listOf(
                        TextRun("See "),
                        TextRun("the register", link = "https://example.org/", commentIds = listOf(6)),
                        TextRun("."),
                    ),
                    ParagraphStyle(),
                )
            ),
            comments = listOf(Comment(id = 6, text = "Dead link.")),
        )
        val read = DocxReader.read(DocxWriter.toByteArray(model))
        val runs = (read.blocks.single() as Paragraph).runs
        val linked = runs.single { it.link != null }
        assertEquals("https://example.org/", linked.link)
        assertEquals(
            listOf(read.comments.single().id), linked.commentIds,
            "the note was lost inside the link",
        )
    }
}
