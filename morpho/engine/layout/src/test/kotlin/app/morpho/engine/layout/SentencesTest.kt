package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Both readings ask this, and a paragraph break in a converted document
 * hangs on the answer: the reading of a laid-out page asks it of a line
 * that stopped short of its margin, and the reading of a scanned one asks
 * it of the last words on a page, where there is no margin to measure.
 * Pinned here so the answer cannot drift with whichever path calls it.
 */
class SentencesTest {

    @Test
    fun `a sentence that stopped has stopped`() {
        assertTrue(Sentences.finishes("The committee met in the spring."))
        assertTrue(Sentences.finishes("Was it settled?"))
        assertTrue(Sentences.finishes("It was not!"))
        assertTrue(Sentences.finishes("The reasons were as follows:"))
        assertTrue(Sentences.finishes("and so on…"))
    }

    @Test
    fun `a sentence in Arabic stops on its own marks`() {
        // The Arabic question mark and full stop, which a reading that
        // knows only the Latin ones takes for the middle of a sentence.
        assertTrue(Sentences.finishes("ما هي الاستمارة؟"))
        assertTrue(Sentences.finishes("وانتهى البحث۔"))
    }

    @Test
    fun `what is closed after the stop does not hide it`() {
        assertTrue(Sentences.finishes("(as the report says.)"))
        assertTrue(Sentences.finishes("""he said "it was settled."""""))
        assertTrue(Sentences.finishes("the year of the report.”"))
        assertTrue(Sentences.finishes("as follows:]"))
    }

    @Test
    fun `a line that stopped mid-sentence has not finished`() {
        assertFalse(Sentences.finishes("The committee met in the spring and"))
        assertFalse(Sentences.finishes("a clause, and then"))
        assertFalse(Sentences.finishes("inter-"))
        assertFalse(Sentences.finishes("والمواضيع الفرعية"))
    }

    @Test
    fun `trailing space does not hide the stop`() {
        assertTrue(Sentences.finishes("It was settled.   "))
        assertFalse(Sentences.finishes("It was settled and   "))
    }

    @Test
    fun `nothing at all has finished nothing`() {
        assertFalse(Sentences.finishes(""))
        assertFalse(Sentences.finishes("   "))
        // A line of nothing but what may close a sentence has no sentence
        // in it to close.
        assertFalse(Sentences.finishes(")"))
        assertFalse(Sentences.finishes("”"))
    }
}
