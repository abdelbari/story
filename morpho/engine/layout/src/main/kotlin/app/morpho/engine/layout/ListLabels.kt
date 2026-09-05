package app.morpho.engine.layout

/**
 * What a list item is marked with, and how far in it sits, level by level.
 *
 * Word draws a list from its numbering rather than from the paragraph: the
 * outer level counts 1. 2. 3., the one inside it a) b) c), the one inside
 * that i. ii. iii., and each level of a bulleted list takes a bullet of its
 * own. A document converted to a page has no numbering to draw from — the
 * marker has to be written into the line — so this is where both the
 * preview and the exported PDF go to ask what to write.
 */
object ListLabels {

    /** What a page draws before a list item: a bullet, a dash, a star. */
    private const val MARKER_CHARACTERS =
        "\u2022\u00B7\u2219\u2212\u2013\u2014-*\u25AA\u25AB\u25CF\u25CB\u25E6\u2023\u2043\u00BB\u203A"

    /** What an enumerator ends with: "3.", "\u0623-", "a)". */
    private const val LABEL_TERMINATORS = "-.)\u2013\u061B:"

    /** An enumerator is this many characters at most, its terminator included. */
    private const val LONGEST_LABEL = 3

    /**
     * Whether [text] opens with the label a page drew for a list item: a
     * marker character — a bullet, a dash, a star — or a short enumerator
     * such as "\u0623-", "3." or "a)", either of them followed by a space,
     * which is what separates a label from a sentence that merely begins
     * with a dash.
     *
     * Both readers ask this. The one with tags asks so as not to draw a
     * second marker over the one the page already drew; the one without
     * asks because a label is where one item ends and the next begins,
     * and a list is set too tight for the gaps to say so.
     */
    fun opensWithLabel(text: String): Boolean {
        val space = text.indexOfFirst { it == ' ' || it == '\t' }
        if (space <= 0) return false
        val label = text.substring(0, space)
        if (label.length == 1) return label[0] in MARKER_CHARACTERS
        if (label.length > LONGEST_LABEL) return false
        if (label.last() !in LABEL_TERMINATORS) return false
        return label.dropLast(1).all { it.isLetterOrDigit() }
    }


    /** However deep a document nests its lists, no deeper than Word's own nine levels. */
    const val DEEPEST_LEVEL = 8

    /** A quarter of an inch a level, which is the step Word indents a list by. */
    const val LEVEL_INDENT_PT = 18f

    private val BULLETS = listOf("•", "◦", "▪")

    private val ROMAN = listOf(
        1000 to "m", 900 to "cm", 500 to "d", 400 to "cd",
        100 to "c", 90 to "xc", 50 to "l", 40 to "xl",
        10 to "x", 9 to "ix", 5 to "v", 4 to "iv", 1 to "i",
    )

    /** The bullet drawn at [level]: •, then ◦, then ▪, and round again. */
    fun bullet(level: Int): String = BULLETS[level.coerceAtLeast(0) % BULLETS.size]

    /**
     * The [count]th item of a numbered list at [level], marker and all:
     * "3.", "b)", "iv.". [format] is how the document itself counts, in
     * the words a Word file uses — an Arabic list that counts أ ب ت says
     * "arabicAlpha" and is written that way rather than 1 2 3.
     */
    fun number(level: Int, count: Int, format: String? = null): String {
        val at = count.coerceAtLeast(1)
        return when (format) {
            "decimal" -> "$at."
            "decimalZero" -> (if (at < 10) "0$at" else "$at") + "."
            "lowerLetter" -> letter(at) + ")"
            "upperLetter" -> letter(at).uppercase() + ")"
            "lowerRoman" -> roman(at) + "."
            "upperRoman" -> roman(at).uppercase() + "."
            "arabicAlpha" -> arabic(at, ALPHABET) + "-"
            "arabicAbjad" -> arabic(at, ABJAD) + "-"
            // A way of counting nobody here knows how to draw is counted
            // the way the level would have been counted anyway.
            else -> when (level.coerceAtLeast(0) % 3) {
                0 -> "$at."
                1 -> letter(at) + ")"
                else -> roman(at) + "."
            }
        }
    }

    /**
     * The [count]th letter of [letters], repeated once more each time the
     * list outlasts them, as a Latin list goes a, b, …, z, aa.
     */
    private fun arabic(count: Int, letters: String): String {
        val at = count.coerceAtLeast(1) - 1
        return letters[at % letters.length].toString().repeat(at / letters.length + 1)
    }

    /** The Arabic alphabet in its own order, which is how a list letters its items. */
    private const val ALPHABET = "أبتثجحخدذرزسشصضطظعغفقكلمنهوي"

    /** The older abjad order, which some documents count in instead. */
    private const val ABJAD = "أبجدهوزحطيكلمنسعفصقرشتثخذضظغ"

    /**
     * The [count]th letter of the alphabet as a list counts them: a, b, …,
     * z, then aa, bb, as Word writes a list that outlasts the alphabet.
     */
    fun letter(count: Int): String {
        val at = count.coerceAtLeast(1) - 1
        return ('a' + at % 26).toString().repeat(at / 26 + 1)
    }

    /** [count] in the lower-case roman numerals a list counts in. */
    fun roman(count: Int): String {
        var left = count.coerceAtLeast(1)
        val out = StringBuilder()
        for ((value, numeral) in ROMAN) {
            while (left >= value) {
                out.append(numeral)
                left -= value
            }
        }
        return out.toString()
    }

    /** How far in a list item of [style] sits, over any indent the source measured. */
    fun indentPt(style: ParagraphStyle): Float =
        if (style.listMarker == null) 0f
        else style.listLevel.coerceIn(0, DEEPEST_LEVEL) * LEVEL_INDENT_PT

    /** The whole marker to write before an item of [style], the space after it included. */
    fun markerFor(style: ParagraphStyle, count: Int): String = when (style.listMarker) {
        ListMarker.BULLET -> bullet(style.listLevel) + " "
        ListMarker.NUMBERED -> number(style.listLevel, count, style.listFormat) + " "
        null -> ""
    }
}

/**
 * The count of each level of the list being written out. An item counts at
 * its own level, and a level that is left starts again the next time it is
 * entered, so a document numbered 1. a. b. 2. a. comes out that way rather
 * than 1. 2. 3. 4. 5.
 */
class ListCounts {
    private val counts = mutableListOf<Int>()

    /** The number to draw before an item of [style]; 0 where nothing is drawn. */
    fun next(style: ParagraphStyle): Int {
        if (style.listMarker == null) {
            clear()
            return 0
        }
        val level = style.listLevel.coerceIn(0, ListLabels.DEEPEST_LEVEL)
        while (counts.size <= level) counts.add(0)
        // Coming back out of a list inside a list ends it; going into one
        // again starts its count over.
        while (counts.size > level + 1) counts.removeAt(counts.size - 1)
        counts[level] = counts[level] + 1
        return counts[level]
    }

    /** Whatever came between the items ended their lists. */
    fun clear() = counts.clear()
}
