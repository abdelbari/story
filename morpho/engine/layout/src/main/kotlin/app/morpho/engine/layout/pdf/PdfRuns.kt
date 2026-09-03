package app.morpho.engine.layout.pdf

import app.morpho.engine.layout.TextRun

/** The one way a measured [PdfLook] becomes a run of the document model. */
object PdfRuns {

    fun toTextRun(text: String, look: PdfLook?): TextRun = TextRun(
        text = text,
        bold = look?.bold ?: false,
        italic = look?.italic ?: false,
        underline = look?.underline ?: false,
        strikethrough = look?.struck ?: false,
        fontFamily = look?.fontFamily,
        fontSizePt = look?.fontSizePt?.takeIf { it > 0f },
        superscript = look?.raised == 1,
        subscript = look?.raised == -1,
        colorRgb = look?.colorRgb,
        highlightRgb = look?.highlightRgb,
        link = look?.link,
    )

    /**
     * Runs of one look joined into runs of the model, in order. A stretch
     * whose look is unknown — a space no glyph painted — joins the run
     * before it, and so does a stretch of nothing but spaces whose look
     * differs only in ways a space cannot show.
     */
    fun toTextRuns(runs: List<PdfRun>): List<TextRun> {
        val out = mutableListOf<TextRun>()
        val text = StringBuilder()
        var current: PdfLook? = null
        fun flush() {
            if (text.isEmpty()) return
            out += toTextRun(text.toString(), current)
            text.setLength(0)
        }
        for (run in runs) {
            if (run.text.isEmpty()) continue
            val look = run.look
            val invisible = run.text.isBlank() && showsNothing(current, look)
            if (look != null && current != null && look != current && !invisible) flush()
            if (look != null && !invisible) current = look
            text.append(run.text)
        }
        flush()
        return out
    }

    /**
     * Whether a space set in [look] would look any different from one set
     * in [current] — which, for the face it was set in, its weight, its
     * slant and its colour, it would not.
     *
     * A page whose words are one font and whose spaces are another is what
     * printing a document through a renderer produces, and it broke a
     * paper into a run for every word: 6,879 runs where 402 say the same
     * thing, in a file larger, slower and far harder to edit than the
     * document it came from. Naming a face for a space is a distinction
     * the page itself does not draw.
     *
     * What a space does show is kept: it takes its width from its size, a
     * highlight paints across it, a link underlines it, a rule drawn under
     * or through the words carries on across it, and a raised one sits off
     * the line. A stretch differing in any of those is a run of its own.
     */
    private fun showsNothing(current: PdfLook?, look: PdfLook?): Boolean {
        if (current == null || look == null) return false
        return current.fontSizePt == look.fontSizePt &&
            current.highlightRgb == look.highlightRgb &&
            current.link == look.link &&
            current.underline == look.underline &&
            current.struck == look.struck &&
            current.raised == look.raised
    }
}
