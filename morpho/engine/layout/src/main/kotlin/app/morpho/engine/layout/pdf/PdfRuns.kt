package app.morpho.engine.layout.pdf

import app.morpho.engine.layout.TextRun

/** The one way a measured [PdfLook] becomes a run of the document model. */
object PdfRuns {

    fun toTextRun(text: String, look: PdfLook?): TextRun = TextRun(
        text = text,
        bold = look?.bold ?: false,
        italic = look?.italic ?: false,
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
     * before it.
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
            if (look != null && current != null && look != current) flush()
            if (look != null) current = look
            text.append(run.text)
        }
        flush()
        return out
    }
}
