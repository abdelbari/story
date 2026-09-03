package app.morpho.engine.layout.pdf

/**
 * When something in a PDF was written, as the rest of the world writes it.
 *
 * A PDF states a date in a shape of its own — `D:20260903091500+01'00'` —
 * and every other format this app touches states one as ISO-8601. A note
 * somebody left on a document is worth little without the day they left
 * it on, and a date carried across in the PDF's own shape shows up in
 * Word's margin as the string it is.
 *
 * The parts after the year are all optional, which is the useful part of
 * the format and the part that makes a naive reading wrong: a producer
 * that writes only the year and the month is writing a legal date, and a
 * reader expecting fourteen digits throws it away.
 */
object PdfDates {

    /** How long each part of a PDF date is, from the year down to the second. */
    private val PARTS = listOf(4, 2, 2, 2, 2, 2)

    /** What each part means when the date stops before reaching it. */
    private val ABSENT = listOf("0000", "01", "01", "00", "00", "00")

    /**
     * [said] as an ISO-8601 instant, or null when it is not a date at all.
     *
     * A date with no zone is written with none: the file did not say which
     * zone it meant, and stamping one on would be inventing the hour.
     */
    fun isoOf(said: String?): String? {
        val text = said?.trim()?.removePrefix("D:")?.trim() ?: return null
        if (text.length < PARTS.first()) return null
        val digits = text.takeWhile { it.isDigit() }
        if (digits.length < PARTS.first()) return null
        val fields = mutableListOf<String>()
        var at = 0
        for ((index, width) in PARTS.withIndex()) {
            fields += if (at + width <= digits.length) digits.substring(at, at + width) else ABSENT[index]
            at += width
        }
        // A month or a day of 00 is what a producer writes for "not said";
        // ISO has no such day, so it is the first of the month instead.
        val month = fields[1].takeUnless { it == "00" } ?: "01"
        val day = fields[2].takeUnless { it == "00" } ?: "01"
        val stamp = "${fields[0]}-$month-${day}T${fields[3]}:${fields[4]}:${fields[5]}"
        return stamp + zoneOf(text.substring(digits.length))
    }

    /**
     * The zone the date ends with, as ISO writes one: "Z", "+01:00", or
     * nothing at all where the file said nothing.
     */
    private fun zoneOf(rest: String): String {
        val said = rest.trim()
        if (said.isEmpty()) return ""
        if (said.startsWith("Z")) return "Z"
        val sign = said.first().takeIf { it == '+' || it == '-' } ?: return ""
        val offset = said.drop(1).filter { it.isDigit() }
        if (offset.length < 2) return ""
        val hours = offset.substring(0, 2)
        val minutes = if (offset.length >= 4) offset.substring(2, 4) else "00"
        // An offset past a day is not an offset; the file is wrong about
        // its own zone and the instant is better left without one.
        if (hours.toInt() > 23 || minutes.toInt() > 59) return ""
        return "$sign$hours:$minutes"
    }
}
