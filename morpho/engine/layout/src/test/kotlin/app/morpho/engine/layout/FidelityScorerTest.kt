package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FidelityScorerTest {

    // ------------------------------------------------------------------
    // textSimilarity
    // ------------------------------------------------------------------

    @Test
    fun `identical strings score exactly one`() {
        assertEquals(1.0, FidelityScorer.textSimilarity("The quick brown fox", "The quick brown fox"))
        assertEquals(
            1.0,
            FidelityScorer.textSimilarity("تُعلن إدارة المكتبة عن المواعيد", "تُعلن إدارة المكتبة عن المواعيد"),
        )
    }

    @Test
    fun `two empty strings score one`() {
        assertEquals(1.0, FidelityScorer.textSimilarity("", ""))
    }

    @Test
    fun `whitespace-only input equals the empty string`() {
        assertEquals(1.0, FidelityScorer.textSimilarity(" \n\t ", ""))
    }

    @Test
    fun `disjoint strings of equal length score zero`() {
        assertEquals(0.0, FidelityScorer.textSimilarity("aaaaaaaa", "zzzzzzzz"), 1e-9)
    }

    @Test
    fun `empty actual against a full expected scores zero`() {
        assertEquals(0.0, FidelityScorer.textSimilarity("anything at all", ""), 1e-9)
    }

    @Test
    fun `runs of whitespace and line wrapping never count as differences`() {
        assertEquals(1.0, FidelityScorer.textSimilarity("line one\nline two", "line one line two"))
        assertEquals(1.0, FidelityScorer.textSimilarity("مرحبا   بالعالم", " مرحبا بالعالم "))
    }

    @Test
    fun `composed and decomposed accents are equal under NFC`() {
        assertEquals(1.0, FidelityScorer.textSimilarity("café crème", "café crème"))
    }

    @Test
    fun `a single substitution costs one edit over the longer length`() {
        assertEquals(1.0 - 1.0 / 6, FidelityScorer.textSimilarity("kitten", "sitten"), 1e-9)
    }

    @Test
    fun `an arabic near match scores high but below one`() {
        val score = FidelityScorer.textSimilarity("مرحبا بالعالم", "مرحبا بالعالمين")
        assertTrue(score < 1.0, "score: $score")
        assertTrue(score > 0.8, "score: $score")
    }

    // ------------------------------------------------------------------
    // structureSimilarity
    // ------------------------------------------------------------------

    private fun body(text: String) = Paragraph(listOf(TextRun(text)))

    private fun heading(text: String) =
        Paragraph(listOf(TextRun(text)), ParagraphStyle(kind = ParagraphKind.HEADING_1))

    @Test
    fun `identical structure scores one and ignores text content`() {
        val expected = DocumentModel(listOf(heading("Report"), body("First"), body("Second")))
        val actual = DocumentModel(listOf(heading("تقرير"), body("أولا"), body("ثانيا")))
        assertEquals(1.0, FidelityScorer.structureSimilarity(expected, actual))
    }

    @Test
    fun `two empty documents score one`() {
        assertEquals(
            1.0,
            FidelityScorer.structureSimilarity(DocumentModel(emptyList()), DocumentModel(emptyList())),
        )
    }

    @Test
    fun `one heading downgraded to body scores below one but above half`() {
        val expected = DocumentModel(listOf(heading("Title"), body("a"), body("b"), body("c")))
        val actual = DocumentModel(listOf(body("Title"), body("a"), body("b"), body("c")))
        val score = FidelityScorer.structureSimilarity(expected, actual)
        assertTrue(score < 1.0, "score: $score")
        assertTrue(score > 0.5, "score: $score")
        assertEquals(0.75, score, 1e-9)
    }

    @Test
    fun `a lost RTL paragraph direction shows up in the score`() {
        val rtl = ParagraphStyle(direction = TextDirection.RTL)
        val expected = DocumentModel(listOf(Paragraph(listOf(TextRun("مرحبا")), rtl), body("Hello")))
        val actual = DocumentModel(listOf(body("مرحبا"), body("Hello")))
        assertEquals(0.5, FidelityScorer.structureSimilarity(expected, actual), 1e-9)
    }

    @Test
    fun `direction inherited from the document default matches an explicit one`() {
        val explicit = DocumentModel(
            blocks = listOf(Paragraph(listOf(TextRun("مرحبا")), ParagraphStyle(direction = TextDirection.RTL))),
        )
        val inherited = DocumentModel(
            blocks = listOf(body("مرحبا")),
            defaultDirection = TextDirection.RTL,
        )
        assertEquals(1.0, FidelityScorer.structureSimilarity(explicit, inherited))
    }

    @Test
    fun `a dropped list marker costs one edit`() {
        val bullet = Paragraph(listOf(TextRun("item")), ParagraphStyle(listMarker = ListMarker.BULLET))
        val expected = DocumentModel(listOf(bullet, body("tail")))
        val actual = DocumentModel(listOf(body("item"), body("tail")))
        assertEquals(0.5, FidelityScorer.structureSimilarity(expected, actual), 1e-9)
    }

    @Test
    fun `table dimensions are part of the signature`() {
        fun table(rows: Int, columns: Int) = Table(
            rows = List(rows) { TableRow(List(columns) { TableCell(emptyList()) }) },
        )
        val expected = DocumentModel(listOf(table(2, 2)))
        assertEquals(1.0, FidelityScorer.structureSimilarity(expected, DocumentModel(listOf(table(2, 2)))))
        assertTrue(
            FidelityScorer.structureSimilarity(expected, DocumentModel(listOf(table(2, 3)))) < 1.0,
        )
    }
}
