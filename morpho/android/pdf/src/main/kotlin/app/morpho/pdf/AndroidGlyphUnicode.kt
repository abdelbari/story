package app.morpho.pdf

import app.morpho.engine.layout.ExtractedText
import com.tom_roush.fontbox.ttf.CmapLookup
import com.tom_roush.pdfbox.pdmodel.font.PDCIDFontType2
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.text.TextPosition
import java.util.IdentityHashMap

/**
 * Android twin of the engine's GlyphUnicode (:engine:pdf-read), on the
 * tom-roush PDFBox port. The two mirror each other line for line — change
 * both together.
 *
 * The Unicode text of a glyph, taken from the embedded font's own character
 * map when the PDF's ToUnicode map has demonstrably lost the plot.
 *
 * A PDF carries two answers to "which character is this glyph": the
 * ToUnicode map the producer wrote alongside the text, and the cmap inside
 * the embedded font itself. Readers take the first, and rightly — it is the
 * one that can say a ligature glyph stands for two letters. But Word 2010
 * writes a corrupt one for its Arabic subsets: on a real paper it labelled
 * the digit 0 as 5, swapped the parentheses, and called the medial lam a
 * meem, so every "العلمي" came out "العممي" and every 2022 came out 2522.
 * The font underneath was fine, its cmap running 0,1,2…9 in order.
 *
 * So, per font, the two are compared over its character codes. A font whose
 * maps agree — after folding presentation forms, since a cmap says ﻠ where
 * ToUnicode says ل and both mean the same letter — is left entirely alone.
 * One whose maps disagree gets its ToUnicode overruled by its cmap, glyph
 * by glyph, and only for glyphs the cmap actually knows: a contextual form
 * with no cmap entry keeps whatever ToUnicode said. A glyph ToUnicode maps
 * to more than one character — a ligature — is never touched, because that
 * is the one thing a cmap cannot express.
 *
 * What counts as a disagreement is a glyph the two maps name as different
 * characters of the same script — a lam called a meem, a 0 called a 5 —
 * and two of those are enough: each one is wrong in every word it appears
 * in. Nothing else is evidence. A code the PDF never mapped at all is not,
 * whatever a library makes up for it (desktop PDFBox answers with the code
 * as a character, the Android port with the font's own reading, and a rule
 * that counted either would reach different verdicts on the same file — as
 * one did, repairing a paper's bold words on the desktop and not on the
 * phone). A candidate in the private-use area is not: a symbol font's cmap
 * names shapes, not characters. And only a font's true Unicode cmap is
 * consulted, never a Macintosh or symbol table that some producers fill
 * with glyph numbers.
 *
 * Only a font that is really embedded qualifies. For one that is not, the
 * TrueType object is a substitute from the system, and its cmap describes
 * a different font entirely.
 */
internal class AndroidGlyphUnicode {

    private val correctors = IdentityHashMap<PDFont, Corrector?>()

    /** What [position] says, unless its font's ToUnicode is broken and its cmap knows better. */
    fun of(position: TextPosition): String {
        val declared = position.unicode.orEmpty()
        val codes = position.characterCodes ?: return declared
        if (codes.size != 1) return declared
        val font = position.font ?: return declared
        val corrector = if (correctors.containsKey(font)) {
            correctors[font]
        } else {
            Corrector.forEmbedded(font).also { correctors[font] = it }
        }
        return corrector?.correct(codes[0], declared) ?: declared
    }

    private class Corrector(private val cid: PDCIDFontType2, private val cmap: CmapLookup) {

        fun correct(code: Int, declared: String): String? {
            // A ligature is the one thing a cmap cannot describe; leave it.
            if (declared.codePointCount(0, declared.length) != 1) return null
            val gid = runCatching { cid.codeToGID(code) }.getOrNull() ?: return null
            if (gid <= 0) return null
            val candidates = cmap.getCharCodes(gid)?.takeIf { it.isNotEmpty() } ?: return null
            if (agrees(candidates, declared)) return null
            return contradiction(candidates, declared)?.let(::text)
        }

        companion object {
            private val PRESENTATION_FORMS = 0xFB00..0xFEFF
            private val PRIVATE_USE = 0xE000..0xF8FF
            private const val PROBE_CODES = 2048
            /** Disagreements of the kind that count before a font's ToUnicode is overruled. */
            private const val MIN_DISAGREEMENTS = 2

            private fun text(codePoint: Int) = String(Character.toChars(codePoint))

            /**
             * The cmap's reading of a glyph where it contradicts ToUnicode's
             * in a way that counts: a character of the same script, not a
             * private-use code or a control, with a nominal letter preferred
             * over a presentation form when several code points share the
             * glyph (a space and a no-break space, say). Null when the two
             * merely differ — which is not the same as one being wrong.
             */
            private fun contradiction(candidates: List<Int>, declared: String): Int? {
                val meant = ExtractedText.foldPresentationForms(declared).codePointAt(0)
                if (!isCharacter(meant)) return null
                return candidates
                    .filter { candidate ->
                        val said = ExtractedText.foldPresentationForms(text(candidate)).codePointAt(0)
                        isCharacter(said) && sameScript(said, meant)
                    }
                    .minByOrNull { if (it in PRESENTATION_FORMS) it + 0x110000 else it }
            }

            /** A letter, digit or mark of a real script — something a map can be wrong about. */
            private fun isCharacter(codePoint: Int): Boolean =
                codePoint !in PRIVATE_USE && !Character.isISOControl(codePoint) &&
                    !Character.isWhitespace(codePoint) &&
                    (Character.isLetterOrDigit(codePoint) || Character.getType(codePoint) == Character.NON_SPACING_MARK.toInt())

            /**
             * Whether two characters belong to the same writing system,
             * digits counting as one and a combining mark — whose script is
             * "inherited" from the letter it sits on — as belonging to any.
             */
            private fun sameScript(a: Int, b: Int): Boolean {
                if (Character.isDigit(a) && Character.isDigit(b)) return true
                val scriptA = Character.UnicodeScript.of(a)
                val scriptB = Character.UnicodeScript.of(b)
                if (scriptA == Character.UnicodeScript.INHERITED || scriptB == Character.UnicodeScript.INHERITED) return true
                return scriptA == scriptB && scriptA != Character.UnicodeScript.COMMON
            }

            /**
             * Whether the font's [candidates] for a glyph mean the same
             * character ToUnicode [declared] for it. Presentation forms are
             * folded first — ﻠ and ل are one letter. A bidi mirror pair also
             * counts as agreement: on a right-to-left line the producer draws
             * the "(" shape for a logical ")", so the cmap naming the shape
             * and ToUnicode naming the character are both right, and
             * "correcting" it would turn every (الجزائر) into )الجزائر(.
             */
            private fun agrees(candidates: List<Int>, declared: String): Boolean {
                val meant = ExtractedText.foldPresentationForms(declared)
                return candidates.any {
                    val said = ExtractedText.foldPresentationForms(text(it))
                    said == meant || mirror(said) == meant
                }
            }

            private fun mirror(s: String): String = when (s) {
                "(" -> ")"; ")" -> "("
                "[" -> "]"; "]" -> "["
                "{" -> "}"; "}" -> "{"
                "<" -> ">"; ">" -> "<"
                "\u00AB" -> "\u00BB"; "\u00BB" -> "\u00AB"
                "\u2039" -> "\u203A"; "\u203A" -> "\u2039"
                else -> s
            }

            /** A corrector for [font], or null when it is not embedded or its maps agree. */
            fun forEmbedded(font: PDFont): Corrector? {
                val type0 = font as? PDType0Font ?: return null
                val cid = type0.descendantFont as? PDCIDFontType2 ?: return null
                if (cid.fontDescriptor?.fontFile2 == null) return null
                val ttf = cid.trueTypeFont ?: return null
                // Strictly the font's Unicode cmap: in lenient mode a font
                // without one hands back its Macintosh or symbol table.
                val cmap = runCatching { ttf.getUnicodeCmapLookup(true) }.getOrNull() ?: return null
                var disagree = 0
                for (code in 1 until PROBE_CODES) {
                    val declared = runCatching { type0.toUnicode(code) }.getOrNull() ?: continue
                    if (declared.codePointCount(0, declared.length) != 1) continue
                    val gid = runCatching { cid.codeToGID(code) }.getOrNull() ?: continue
                    if (gid <= 0) continue
                    val candidates = cmap.getCharCodes(gid)?.takeIf { it.isNotEmpty() } ?: continue
                    if (agrees(candidates, declared)) continue
                    if (contradiction(candidates, declared) != null) disagree++
                    if (disagree >= MIN_DISAGREEMENTS) return Corrector(cid, cmap)
                }
                return null
            }
        }
    }
}
