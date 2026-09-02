package app.morpho.pdf

import app.morpho.engine.layout.ExtractedText
import com.tom_roush.fontbox.ttf.CmapLookup
import com.tom_roush.pdfbox.pdmodel.font.PDCIDFontType2
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.encoding.Encoding
import com.tom_roush.pdfbox.pdmodel.font.encoding.GlyphList
import com.tom_roush.pdfbox.pdmodel.font.encoding.SymbolEncoding
import com.tom_roush.pdfbox.pdmodel.font.encoding.ZapfDingbatsEncoding
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
 * with glyph numbers. Once a font is known to be broken, though, a glyph
 * the two maps read as different kinds of character — the colon that
 * paper's ToUnicode called a 4 — is overruled as well.
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
        val corrected = corrector?.correct(codes[0], declared) ?: declared
        return SymbolFonts.character(font.name, corrected) ?: corrected
    }

    private class Corrector(private val cid: PDCIDFontType2, private val cmap: CmapLookup) {

        fun correct(code: Int, declared: String): String? {
            // A ligature is the one thing a cmap cannot describe; leave it.
            if (declared.codePointCount(0, declared.length) != 1) return null
            val gid = runCatching { cid.codeToGID(code) }.getOrNull() ?: return null
            if (gid <= 0) return null
            val candidates = cmap.getCharCodes(gid)?.takeIf { it.isNotEmpty() } ?: return null
            if (agrees(candidates, declared)) return null
            return (contradiction(candidates, declared) ?: crossKind(candidates, declared))?.let(::text)
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

            /**
             * The cmap's reading of a glyph where ToUnicode names a different
             * kind of character altogether — a colon it calls a 4, a digit it
             * calls a bracket. This is not evidence that a font is broken, so
             * it is not counted; but once a font is known to be broken, it is
             * a wrong answer like any other, and the paper's every "ملخص:"
             * would otherwise read "ملخص4". Null when both readings are of a
             * kind, whatever script: a comma named as the Arabic comma, or a
             * hyphen as a dash, is a producer's choice, not a broken map.
             */
            private fun crossKind(candidates: List<Int>, declared: String): Int? {
                val meant = ExtractedText.foldPresentationForms(declared).codePointAt(0)
                val meantKind = kindOf(meant) ?: return null
                return candidates
                    .filter { candidate ->
                        val said = ExtractedText.foldPresentationForms(text(candidate)).codePointAt(0)
                        val kind = kindOf(said)
                        kind != null && kind != meantKind
                    }
                    .minByOrNull { if (it in PRESENTATION_FORMS) it + 0x110000 else it }
            }

            private enum class Kind { LETTER, DIGIT, SIGN }

            /** Whether a code point is a letter, a digit or a sign — punctuation or a symbol; null for anything else. */
            private fun kindOf(codePoint: Int): Kind? {
                if (codePoint in PRIVATE_USE || Character.isISOControl(codePoint) || Character.isWhitespace(codePoint)) return null
                if (Character.isDigit(codePoint)) return Kind.DIGIT
                if (Character.isLetter(codePoint) || Character.getType(codePoint) == Character.NON_SPACING_MARK.toInt()) return Kind.LETTER
                return when (Character.getType(codePoint).toByte()) {
                    Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
                    Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                    Character.OTHER_PUNCTUATION, Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL,
                    Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL -> Kind.SIGN
                    else -> null
                }
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

/**
 * The character a symbol font's glyph really is.
 *
 * A symbol font is addressed through a "(3,0)" character map, which holds
 * its glyphs at F000 plus the font's own byte. So a reader is told that the
 * bullet Word draws before a list item is U+F0B7 — a private-use code point,
 * which means nothing outside that one font: a word processor shows a blank
 * box for it, and, being a left-to-right character, it turns the whole
 * right-to-left item around it into a left-to-right one, so the marker of an
 * Arabic list ends up on the wrong side of the page.
 *
 * Two symbol fonts have byte assignments that are standard and published —
 * Adobe's Symbol and ZapfDingbats. For those the byte names a glyph, and the
 * Adobe Glyph List says which character the glyph is: Symbol's B7 is
 * "bullet", •, and its 2D is "minus", −. Any other symbol font's bytes are
 * its own business and are left alone, since guessing would put a real
 * character where the page shows a picture.
 */
internal object SymbolFonts {

    /** Where a symbol font's own character map puts its bytes. */
    private val SYMBOL_AREA = 0xF000..0xF0FF
    private val PRIVATE_USE = 0xE000..0xF8FF

    /**
     * What the glyph [text] of the font named [fontName] really is, or null
     * when the two say nothing better than [text] does already.
     */
    fun character(fontName: String?, text: String): String? {
        if (text.codePointCount(0, text.length) != 1) return null
        val codePoint = text.codePointAt(0)
        if (codePoint !in SYMBOL_AREA) return null
        // A subset font is named for the font it was cut from: "ABCDEE+Symbol".
        val face = fontName?.substringAfterLast('+') ?: return null
        val encoding: Encoding
        val glyphs: GlyphList
        when {
            face.equals("Symbol", ignoreCase = true) -> {
                encoding = SymbolEncoding.INSTANCE
                glyphs = GlyphList.getAdobeGlyphList()
            }
            face.equals("ZapfDingbats", ignoreCase = true) -> {
                encoding = ZapfDingbatsEncoding.INSTANCE
                glyphs = GlyphList.getZapfDingbats()
            }
            else -> return null
        }
        val name = encoding.getName(codePoint - SYMBOL_AREA.first) ?: return null
        val unicode = runCatching { glyphs.toUnicode(name) }.getOrNull() ?: return null
        if (unicode.isEmpty() || unicode.codePointAt(0) in PRIVATE_USE) return null
        return unicode
    }
}
