package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FidelityReportTest {

    private fun para(text: String, confidence: Float, kind: ParagraphKind = ParagraphKind.BODY) =
        Paragraph(
            runs = listOf(TextRun(text)),
            style = ParagraphStyle(kind = kind),
            confidence = confidence,
        )

    @Test
    fun `bands follow the reader conventions`() {
        val report = FidelityReport.of(
            DocumentModel(
                blocks = listOf(
                    para("native", 1.0f),
                    para("tagged", 0.9f),
                    para("heuristic", 0.6f),
                    para("ocr", 0.5f),
                )
            )
        )
        assertEquals(
            listOf(
                FidelityReport.Band.HIGH,
                FidelityReport.Band.HIGH,
                FidelityReport.Band.MEDIUM,
                FidelityReport.Band.LOW,
            ),
            report.entries.map { it.band },
        )
        assertEquals(2, report.counts[FidelityReport.Band.HIGH])
        assertEquals(1, report.counts[FidelityReport.Band.MEDIUM])
        assertEquals(1, report.counts[FidelityReport.Band.LOW])
    }

    @Test
    fun `sources name where each block came from`() {
        val report = FidelityReport.of(
            DocumentModel(
                blocks = listOf(
                    para("native", 1.0f),
                    para("tagged", 0.9f),
                    para("heuristic", 0.6f),
                    para("ocr", 0.5f),
                )
            )
        )
        assertEquals(
            listOf(
                FidelityReport.Source.EXACT,
                FidelityReport.Source.TAGGED,
                FidelityReport.Source.RECONSTRUCTED,
                FidelityReport.Source.RECOGNIZED,
            ),
            report.entries.map { it.source },
        )
    }

    @Test
    fun `reviewables list the doubtful blocks, most doubtful first`() {
        val report = FidelityReport.of(
            DocumentModel(
                blocks = listOf(
                    para("sure", 1.0f),
                    para("shaky", 0.6f),
                    para("guessy", 0.5f),
                )
            )
        )
        assertEquals(listOf(2, 1), report.reviewables.map { it.index })
        assertTrue(report.entries[0] !in report.reviewables)
    }

    @Test
    fun `overall confidence weighs blocks by their text length`() {
        // 90 chars at 1.0 vs 10 chars at 0.5: the long block dominates.
        val report = FidelityReport.of(
            DocumentModel(
                blocks = listOf(
                    para("x".repeat(90), 1.0f),
                    para("y".repeat(10), 0.5f),
                )
            )
        )
        assertEquals(0.95f, report.overall, 0.001f)
    }

    @Test
    fun `excerpts truncate long text on a code point boundary`() {
        val long = "م".repeat(100)
        val report = FidelityReport.of(DocumentModel(blocks = listOf(para(long, 0.6f))))
        val excerpt = report.entries.single().excerpt
        assertEquals(81, excerpt.length, "80 code points plus the ellipsis")
        assertTrue(excerpt.endsWith("…"))
    }

    @Test
    fun `headings tables and images carry their kinds`() {
        val report = FidelityReport.of(
            DocumentModel(
                blocks = listOf(
                    para("Title", 0.9f, ParagraphKind.HEADING_1),
                    Table(
                        rows = listOf(TableRow(listOf(TableCell(listOf(para("cell", 0.6f)))))),
                        confidence = 0.6f,
                    ),
                    ImageBlock(byteArrayOf(1), "image/png", 10, 10, confidence = 0.9f),
                )
            )
        )
        assertEquals(
            listOf(
                FidelityReport.Kind.HEADING,
                FidelityReport.Kind.TABLE,
                FidelityReport.Kind.IMAGE,
            ),
            report.entries.map { it.kind },
        )
        assertEquals("cell", report.entries[1].excerpt)
        assertEquals("", report.entries[2].excerpt)
    }

    @Test
    fun `an empty model reports full confidence and nothing to review`() {
        val report = FidelityReport.of(DocumentModel(blocks = emptyList()))
        assertEquals(1f, report.overall)
        assertTrue(report.reviewables.isEmpty())
    }
}
