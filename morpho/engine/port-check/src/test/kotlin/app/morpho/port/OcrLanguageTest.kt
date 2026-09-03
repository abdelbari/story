package app.morpho.port

import app.morpho.pdf.AndroidOcrReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every language recognition can be asked for against the packs that ship.
 *
 * The set to recognise with follows the phone's own language, and each
 * name in it is a file the app extracts out of its assets before Tesseract
 * is started. A name with no file behind it does not fail a build, a lint
 * or a review: `assets.open` throws on the phone, the whole run fails, and
 * what the reader sees is not "no French model" but a converter whose OCR
 * has stopped working — for every scan, in that locale, for good.
 *
 * So the table and the assets are held to each other here, in both
 * directions: a pack that can be asked for is one that ships, and a pack
 * that ships is one something can ask for, since each of them is a
 * megabyte or four of the download.
 */
class OcrLanguageTest {

    private val tessdata = File("../../android/pdf/src/main/assets/tessdata")

    /** Every pack in the module's assets, by the name recognition asks for it under. */
    private fun shipped(): Set<String> =
        tessdata.list().orEmpty().filter { it.endsWith(SUFFIX) }
            .map { it.removeSuffix(SUFFIX) }.toSet()

    /** Every set the app can end up asking for, whatever the phone is set to. */
    private fun askable(): List<String> =
        AndroidOcrReader.LANGUAGES_BY_LOCALE.values + AndroidOcrReader.OTHERWISE +
            AndroidOcrReader.DEFAULT_LANGUAGES

    @Test
    fun `the packs this compares against are where they are expected to be`() {
        assertTrue(tessdata.isDirectory, "no language packs at ${tessdata.absolutePath}")
        assertTrue(shipped().isNotEmpty(), "no language packs in ${tessdata.absolutePath}")
    }

    @Test
    fun `every language that can be asked for is one that ships`() {
        val have = shipped()
        val missing = askable().flatMap { it.split('+') }.distinct().sorted()
            .filterNot { it in have }
        assertEquals(
            emptyList<String>(),
            missing,
            "recognition can be asked for a pack the app does not ship; it would fail " +
                "on the phone, for every scan in that locale. Packs present: ${have.sorted()}",
        )
    }

    @Test
    fun `every pack that ships is one something can ask for`() {
        // Each is megabytes of the download. One nothing can reach is
        // weight every reader carries and none of them can use.
        val asked = askable().flatMap { it.split('+') }.toSet()
        assertEquals(
            emptyList<String>(),
            shipped().sorted().filterNot { it in asked },
            "a language pack ships that no locale can ask for",
        )
    }

    @Test
    fun `a phone set to none of them still recognises`() {
        // The one branch a reader outside the four app languages takes.
        for (language in listOf("en", "zh", "hi", "ru", "", "xx")) {
            val said = AndroidOcrReader.languagesFor(language)
            assertEquals(AndroidOcrReader.OTHERWISE, said, "\"$language\" fell somewhere else")
        }
    }

    @Test
    fun `the language the app exists for leads its own set`() {
        // Tesseract leans on the first language named, so the order of a
        // pair is the difference between an Arabic page read as Arabic and
        // one read as badly-spelled English.
        val arabic = AndroidOcrReader.languagesFor("ar")
        assertEquals("ara", arabic.substringBefore('+'), "an Arabic phone does not lead with Arabic")
        assertEquals(AndroidOcrReader.DEFAULT_LANGUAGES, arabic)
        for ((locale, set) in AndroidOcrReader.LANGUAGES_BY_LOCALE) {
            assertTrue(
                set.split('+').size >= 2,
                "$locale recognises with one model only; a mixed document would not read",
            )
            assertTrue(
                set.split('+').contains("eng"),
                "$locale carries no English, so the Latin in a mixed document would not read",
            )
        }
    }

    private companion object {
        const val SUFFIX = ".traineddata"
    }
}
