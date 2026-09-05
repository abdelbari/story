package app.morpho.engine.layout

import java.text.Normalizer

/**
 * The one way text pulled out of a PDF is turned into what the document
 * model holds.
 *
 * A PDF is a description of marks on a page, not of a document: glyphs are
 * painted in the order they appear, left to right, and an Arabic word may be
 * stored as the ligature that was drawn rather than the letters that were
 * typed. Both readers — the tagged one walking a structure tree and the
 * untagged one clustering by position — capture that raw painted form, so
 * both put it through here and neither can drift from the other.
 */
object ExtractedText {

    /**
     * Turns one line captured in visual order — glyphs left to right as they
     * sit on the page — into the logical order a document model holds:
     * presentation forms folded back to the letters they stand for, then
     * right-to-left runs put back the way they were written, words and
     * letters both. Left-to-right text with no ligatures comes back
     * untouched.
     *
     * Both readers call this on lines they have first put into visual
     * order by position, which is the only order a PDF can be trusted on,
     * and pass the document's [base] direction, which a single line cannot
     * work out for itself.
     */
    fun toLogical(text: String, base: TextDirection? = null): String = reorder(text, base).text

    /** A line in logical order, with what painted each of its characters. */
    class Logical<T>(val text: String, val painters: List<T?>)

    /**
     * [toLogical] for a reader that knows what painted each character of
     * [text] — [painters] runs parallel to it — and wants that knowledge to
     * survive the reordering. Every character of the result carries the
     * painter of the character it came from: a ligature folded into two
     * letters gives both its glyph, a run of spaces collapsed into one keeps
     * the first's, and the reversal moves each painter with its character.
     * That is what lets a bold word or a raised footnote mark keep its look
     * after the line has been put back in order around it.
     */
    fun <T> toLogical(text: String, painters: List<T?>, base: TextDirection? = null): Logical<T> {
        require(painters.size == text.length) { "one painter per character" }
        val reordered = reorder(text, base)
        return Logical(reordered.text, reordered.sources.map { if (it >= 0) painters[it] else null })
    }

    /**
     * A glyph's text as it should enter a line that is about to be
     * reconstructed from visual order.
     *
     * A ligature glyph — لا drawn as one mark — carries two characters, and
     * ToUnicode gives them in logical order. Reversing the line to recover
     * logical order reverses those two as well, and every لا in a document
     * comes out ال. So a multi-character right-to-left glyph is entered
     * backwards, and the line's reversal puts it right. Left-to-right
     * ligatures (ﬁ) are not reversed by the line, so they are left alone.
     */
    fun paintedForm(glyphText: String): String =
        if (glyphText.length > 1 && glyphText.any(::isRtlLetter)) StringBuilder(glyphText).reverse().toString()
        else glyphText

    // Reconstruct first, fold after: a presentation-form ligature such as
    // ﻻ is one character while the line is put back in order and only
    // then becomes the two letters لا — in logical order. Folding first
    // would hand the reversal two letters to swap.
    private fun reorder(text: String, base: TextDirection?): Bidi.Reordered =
        collapseSpaces(canonical(foldPresentationForms(Bidi.reorder(text, base))))

    /**
     * Each letter and the marks written over and under it, put in the one
     * order Unicode calls canonical.
     *
     * A vowelled Arabic page — a verse, a line of classical poetry, a
     * school book — sets a shadda and a fatha over the same letter, and
     * the page paints them in whatever order the producer wrote them.
     * Unicode says a fatha comes before a shadda, and that is the order
     * every keyboard types and every search box holds: get it the other
     * way round and the converted document *renders* correctly and can
     * still not be searched, because the phrase a reader types is a
     * different string from the one in the file.
     *
     * Only a letter that carries a mark is touched, and only into the form
     * that means the same thing: a hamza written over an alef becomes the
     * one letter Unicode has for that, which is what a document holds and
     * what Word writes.
     */
    private fun canonical(reordered: Bidi.Reordered): Bidi.Reordered {
        val text = reordered.text
        if (text.none(::isMark)) return reordered
        val out = StringBuilder(text.length)
        val sources = ArrayList<Int>(text.length)
        var i = 0
        while (i < text.length) {
            var end = i + 1
            while (end < text.length && isMark(text[end])) end++
            if (end - i == 1) {
                out.append(text[i])
                sources += reordered.sources[i]
                i = end
                continue
            }
            val cluster = text.substring(i, end)
            val settled = Normalizer.normalize(cluster, Normalizer.Form.NFC)
            out.append(settled)
            // The letter keeps its own painter and every mark on it keeps
            // the painter of a mark: they are set in the one font at the
            // one size, being one letter as far as the page is concerned.
            for (at in settled.indices) sources += reordered.sources[minOf(i + at, end - 1)]
            i = end
        }
        return Bidi.Reordered(out.toString(), sources.toIntArray())
    }

    /** A mark written over or under the letter before it. */
    private fun isMark(c: Char): Boolean = when (Character.getType(c)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }

    /**
     * Runs of spaces become one. On a page, spacing is geometry — Word
     * justifies an Arabic heading by widening its gaps, and a tab-aligned
     * line of dates arrives as words separated by twenty spaces — and none
     * of it is content.
     */
    private fun collapseSpaces(reordered: Bidi.Reordered): Bidi.Reordered {
        val text = reordered.text
        if (!SPACE_RUN.containsMatchIn(text)) return reordered
        val out = StringBuilder(text.length)
        val sources = IntArray(text.length)
        var length = 0
        var i = 0
        while (i < text.length) {
            var end = i
            while (end < text.length && isSpace(text[end])) end++
            if (end - i >= 2) {
                out.append(' ')
                sources[length++] = reordered.sources[i]
                i = end
            } else {
                out.append(text[i])
                sources[length++] = reordered.sources[i]
                i++
            }
        }
        return Bidi.Reordered(out.toString(), sources.copyOf(length))
    }

    private val SPACE_RUN = Regex("[ \u00A0]{2,}")

    private fun isSpace(c: Char): Boolean = c == ' ' || c == '\u00A0'

    private fun isRtlLetter(c: Char): Boolean = when (Character.getDirectionality(c)) {
        Character.DIRECTIONALITY_RIGHT_TO_LEFT,
        Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> true
        else -> false
    }


    /**
     * Folds Arabic and Latin presentation forms to their nominal letters.
     *
     * A PDF can map a glyph to the shape that was drawn — the lam-alef
     * ligature, an initial or final form of a letter — rather than to the
     * letter itself. Those code points are not what anyone searches for or
     * types, and Word will not join them into words, so they are folded to
     * the nominal letters through NFKC.
     *
     * Only the presentation-form blocks are folded. NFKC over everything
     * would reach much further than intended — it rewrites the micro sign
     * into Greek mu, fullwidth Latin into ASCII, and superscript digits into
     * plain ones — and none of that belongs in a faithful conversion.
     */
    fun foldPresentationForms(text: String): String =
        foldPresentationForms(Bidi.Reordered(text, IntArray(text.length) { it })).text

    /** [foldPresentationForms] keeping each folded letter's source. */
    private fun foldPresentationForms(reordered: Bidi.Reordered): Bidi.Reordered {
        val text = reordered.text
        if (text.none(::isPresentationForm)) return reordered
        val out = StringBuilder(text.length)
        val sources = ArrayList<Int>(text.length)
        for ((i, c) in text.withIndex()) {
            if (isPresentationForm(c)) {
                val folded = Normalizer.normalize(c.toString(), Normalizer.Form.NFKC)
                out.append(folded)
                repeat(folded.length) { sources += reordered.sources[i] }
            } else {
                out.append(c)
                sources += reordered.sources[i]
            }
        }
        return Bidi.Reordered(out.toString(), sources.toIntArray())
    }

    /**
     * Alphabetic Presentation Forms and Arabic Presentation Forms-A
     * (FB00–FDFF), and Arabic Presentation Forms-B up to its last assigned
     * code point (FE70–FEFC). FEFD and FEFE are unassigned, and FEFF is the
     * byte-order mark rather than a letter, so the range stops short of them.
     */
    private fun isPresentationForm(c: Char): Boolean =
        c in '\uFB00'..'\uFDFF' || c in '\uFE70'..'\uFEFC'
}
