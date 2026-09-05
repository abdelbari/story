package app.morpho.engine.ooxml

import app.morpho.engine.layout.Alignment
import app.morpho.engine.layout.Comment
import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.DocumentProperties
import app.morpho.engine.layout.ImageBlock
import app.morpho.engine.layout.ListMarker
import app.morpho.engine.layout.PageSetup
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.ParagraphKind
import app.morpho.engine.layout.ParagraphStyle
import app.morpho.engine.layout.RunField
import app.morpho.engine.layout.Table
import app.morpho.engine.layout.TableCell
import app.morpho.engine.layout.TableRow
import app.morpho.engine.layout.TextDirection
import app.morpho.engine.layout.TextRun
import java.util.Base64

/**
 * One document holding everything a Word package can carry, and so
 * everything it can be wrong about: a title and headings, a note, a link,
 * pictures inline and as blocks, lists of both kinds and a numbered one in
 * Arabic letters, a table of merged cells with another inside it, an
 * Arabic paragraph, a page turned sideways, a running head with a table in
 * it and a numbered foot, a head for the left-hand pages, what the file
 * says about itself, and a comment — one anchored and one anchored to
 * nothing.
 *
 * Shared, because two checks want the same document for opposite reasons.
 * [PackageIntegrityTest] asks whether writing all of that produces a
 * package Word will open. [OoxmlHardeningTest] damages the bytes of it and
 * asks whether reading the wreckage fails as an exception the app can
 * report. A plain document of twenty paragraphs, which is what the second
 * used to damage, never reaches the comments part, the running head, the
 * notes part or a picture — the very code most likely to fall over on
 * rubbish. Adding to this fixture strengthens both at once.
 */
object RichDocument {

    val png: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    )

    fun line(text: String, style: ParagraphStyle = ParagraphStyle()) =
        Paragraph(listOf(TextRun(text)), style)

    fun of(): DocumentModel {
        val picture = ImageBlock(png, "image/png", 1, 1)
        val inner = Table(listOf(TableRow(listOf(TableCell(listOf(line("inner")))))))
        val outer = Table(
            listOf(
                TableRow(listOf(TableCell(listOf(line("head"))), TableCell(listOf(line("also")))), repeatsAsHeader = true),
                TableRow(listOf(TableCell(listOf(line("a cell"), inner)), TableCell(listOf(picture)))),
            )
        )
        return DocumentModel(
            blocks = listOf(
                line("A title", ParagraphStyle(kind = ParagraphKind.TITLE)),
                line("A heading", ParagraphStyle(kind = ParagraphKind.HEADING_1)),
                Paragraph(
                    listOf(
                        TextRun("A claim"),
                        TextRun("1", note = listOf(line("The note itself."))),
                        TextRun(" and a "),
                        TextRun("link", link = "https://example.com/x", commentIds = listOf(2)),
                        TextRun(" and a picture "),
                        TextRun("", image = ImageBlock(png, "image/png", 1, 1)),
                    )
                ),
                line("An item", ParagraphStyle(listMarker = ListMarker.BULLET)),
                line("A deeper item", ParagraphStyle(listMarker = ListMarker.NUMBERED, listLevel = 1)),
                line("Numbered", ParagraphStyle(listMarker = ListMarker.NUMBERED, listFormat = "arabicAlpha")),
                outer,
                line("سطر عربي", ParagraphStyle(direction = TextDirection.RTL, alignment = Alignment.END)),
                line(
                    "On a turned page",
                    ParagraphStyle(
                        sectionSetup = PageSetup(842f, 595f, 72f, 72f, 72f, 72f),
                    ),
                ),
            ),
            defaultDirection = TextDirection.RTL,
            defaultLanguage = "ar-DZ",
            pageSetup = PageSetup(595f, 842f, 72f, 72f, 72f, 72f, firstPageNumber = 48),
            header = listOf(line("The running head"), Table(listOf(TableRow(listOf(TableCell(listOf(line("h")))))))),
            footer = listOf(
                Paragraph(listOf(TextRun("48", field = RunField.PAGE_NUMBER))),
            ),
            evenHeader = listOf(line("The other side")),
            properties = DocumentProperties(title = "A Study", author = "R. Nebbar"),
            comments = listOf(
                Comment(id = 2, text = "Is this still up?", author = "R. Nebbar", dateIso = "2026-09-03T09:15:00Z"),
                // A note nothing is about: the writer must leave it out
                // rather than write a note the file cannot show.
                Comment(id = 5, text = "Nothing points at this."),
            ),
        )
    }
}
