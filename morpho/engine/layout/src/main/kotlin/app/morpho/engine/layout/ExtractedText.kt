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
     * Turns text captured in painting order into the logical order a
     * document model holds: presentation forms folded back to the letters
     * they stand for, then right-to-left runs put back the way they were
     * written. Left-to-right text with no ligatures comes back untouched.
     */
    fun toLogical(text: String): String = Bidi.visualToLogical(foldPresentationForms(text))

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
    fun foldPresentationForms(text: String): String {
        if (text.none(::isPresentationForm)) return text
        val out = StringBuilder(text.length)
        for (c in text) {
            if (isPresentationForm(c)) {
                out.append(Normalizer.normalize(c.toString(), Normalizer.Form.NFKC))
            } else {
                out.append(c)
            }
        }
        return out.toString()
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
