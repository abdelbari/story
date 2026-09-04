package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The excerpt a block is listed by, which is now cut without building
 * the block.
 *
 * A paragraph's text is made afresh from its runs every time it is read,
 * and an excerpt is eighty code points of a paragraph that may be
 * thousands — so the report was building whole documents to throw nearly
 * all of them away, once for the excerpt and once again to weigh it, and
 * it does that after every correction a reader makes. Measured over three
 * thousand blocks: 70ms a report became 15ms where the paragraphs are
 * paragraphs, and 271ms became 35ms where they are long.
 *
 * None of which is worth anything if the excerpt changed, so this holds
 * it to what it was: the same words, cut at the same place, however the
 * runs beneath it are divided.
 */
class ExcerptTest {

    private fun excerptOf(vararg runs: String): String =
        FidelityReport.of(DocumentModel(listOf(Paragraph(runs.map { TextRun(it) })))).entries[0].excerpt

    @Test
    fun `how a paragraph is divided into runs cannot change its excerpt`() {
        val words = "الاستمارة في البحث العلمي the committee found that the form had been received "
        val whole = words.repeat(20)
        // The same text as one run, as a run per word, and as a run per
        // character: the excerpt must not be able to tell.
        val byWord = whole.split(" ").map { "$it " }.toTypedArray()
        val byCharacter = whole.map(Char::toString).toTypedArray()
        val one = excerptOf(whole)
        assertEquals(one, excerptOf(*byWord), "the excerpt moved when the runs did")
        assertEquals(one, excerptOf(*byCharacter))
        assertTrue(one.endsWith("…"), "a long paragraph is not marked as cut: [$one]")
    }

    @Test
    fun `a paragraph short enough to be its own excerpt is`() {
        assertEquals("Report of the Committee", excerptOf("Report of the ", "Committee"))
        assertEquals("", excerptOf(""))
        assertEquals("", excerptOf())
    }

    @Test
    fun `the cut is at the same place either side of the boundary`() {
        // Eighty code points exactly is not cut; eighty-one is.
        val eighty = "x".repeat(80)
        assertEquals(eighty, excerptOf(eighty))
        assertEquals(eighty, excerptOf(*eighty.map(Char::toString).toTypedArray()))
        assertEquals("$eighty…", excerptOf(eighty + "y"))
        assertEquals("$eighty…", excerptOf(eighty, "y"))
    }

    @Test
    fun `a letter written as two characters is not cut in half`() {
        // The stop is counted in characters and the cut in code points,
        // which is only safe while the stop can never fall short. An
        // emoji is two characters and one code point, so a paragraph of
        // them is the case where the two counts are furthest apart.
        val emoji = "😀"
        val many = emoji.repeat(200)
        val excerpt = excerptOf(many)
        assertEquals(excerptOf(*List(200) { emoji }.toTypedArray()), excerpt)
        assertEquals(80, excerpt.removeSuffix("…").codePointCount(0, excerpt.length - 1))
        assertTrue(
            excerpt.removeSuffix("…").all { it.isHighSurrogate() || it.isLowSurrogate() },
            "a letter was cut in half: [$excerpt]",
        )
    }

    @Test
    fun `weighing a block does not depend on how it is divided either`() {
        // The overall score is weighted by how much text each block holds,
        // so counting it off the runs rather than off the text has to give
        // the same number.
        val long = Paragraph(List(50) { TextRun("some words here ") }, confidence = 0.5f)
        val same = Paragraph(listOf(TextRun("some words here ".repeat(50))), confidence = 0.5f)
        val certain = Paragraph(listOf(TextRun("x")), confidence = 1f)
        assertEquals(
            FidelityReport.of(DocumentModel(listOf(same, certain))).overall,
            FidelityReport.of(DocumentModel(listOf(long, certain))).overall,
        )
    }
}
