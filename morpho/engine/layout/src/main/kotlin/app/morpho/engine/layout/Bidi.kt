package app.morpho.engine.layout

/**
 * Minimal direction heuristics. Full BiDi run analysis (UAX #9) comes with the
 * PDF extraction milestone; first-strong detection is what the importers need
 * to tag paragraph direction correctly.
 */
object Bidi {

    /**
     * Direction of the first strongly-directional code point, or null when the
     * text has none (digits, punctuation, whitespace only).
     */
    fun firstStrongDirection(text: String): TextDirection? {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            when (Character.getDirectionality(cp)) {
                Character.DIRECTIONALITY_LEFT_TO_RIGHT ->
                    return TextDirection.LTR
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC ->
                    return TextDirection.RTL
                else -> {}
            }
            i += Character.charCount(cp)
        }
        return null
    }
}
