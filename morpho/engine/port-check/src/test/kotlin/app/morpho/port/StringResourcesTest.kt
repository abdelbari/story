package app.morpho.port

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * What the app says, held to the rules the resource compiler holds it to.
 *
 * Three different mistakes in the app's strings have each cost a build,
 * and none of them could be made anywhere a compiler on this machine
 * would see it: the app's resources are compiled by the Android build,
 * which is minutes away in CI and not runnable here at all. So what the
 * compiler and the linter would have said is said here instead, in the
 * two seconds this takes.
 *
 * The three: a string written in English only, in an app that ships in
 * five languages and fails a build that forgets one; an apostrophe left
 * as it is written in French, which the resource compiler refuses
 * outright and reports as a file it cannot read; and — not yet made, and
 * the worst of the three because it survives the build — a translation
 * that drops the number out of a sentence that formats one, which throws
 * on the phone, in that language, at the moment the sentence is shown.
 */
class StringResourcesTest {

    private val res = File("../../android/app/src/main/res")

    /** The default strings, and every translation of them. */
    private fun folders(): List<File> =
        res.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values") }
            .filter { File(it, "strings.xml").isFile }
            .sortedBy { it.name }

    /**
     * Every named string and plural in one folder, by name — leaving out
     * the ones marked as not for translating.
     *
     * The app's own name is the one of those: it is the same word in
     * every language, and a translation of it would be a different app.
     */
    private fun namesIn(folder: File): Map<String, Element> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(folder, "strings.xml"))
        val out = mutableMapOf<String, Element>()
        val root = document.documentElement.childNodes
        for (at in 0 until root.length) {
            val node = root.item(at) as? Element ?: continue
            val name = node.getAttribute("name")
            val translated = node.getAttribute("translatable") != "false"
            if (name.isNotEmpty() && translated) out[name] = node
        }
        return out
    }

    /** Everything an element says, its plural items included. */
    private fun textsOf(element: Element): List<String> {
        if (element.tagName != "plurals") return listOf(element.textContent)
        val items = element.getElementsByTagName("item")
        return (0 until items.length).map { items.item(it).textContent }
    }

    @Test
    fun `the strings this compares are where they are expected to be`() {
        assertTrue(res.isDirectory, "no resources at ${res.absolutePath}")
        val folders = folders().map { it.name }
        assertTrue("values" in folders, "no default strings among $folders")
        assertTrue(folders.size >= 5, "the app ships five languages; found $folders")
    }

    @Test
    fun `everything the app says, it says in every language it ships`() {
        val wanted = namesIn(File(res, "values")).keys
        for (folder in folders()) {
            if (folder.name == "values") continue
            val have = namesIn(folder).keys
            assertEquals(
                emptyList<String>(),
                (wanted - have).sorted(),
                "${folder.name} is missing strings the app shows in English",
            )
            assertEquals(
                emptyList<String>(),
                (have - wanted).sorted(),
                "${folder.name} translates strings the app no longer has",
            )
        }
    }

    @Test
    fun `an apostrophe is escaped, which is the resource compiler's rule and not a preference`() {
        // Unescaped, it does not warn and it does not fail the string: it
        // fails the whole file, reported as one the compiler cannot read,
        // which is a long way from the word that caused it.
        for (folder in folders()) {
            for ((name, element) in namesIn(folder)) {
                for (text in textsOf(element)) {
                    // Android's other way of quoting: a value wrapped whole
                    // in quotation marks is taken literally.
                    if (text.startsWith("\"") && text.endsWith("\"")) continue
                    for (at in text.indices) {
                        val c = text[at]
                        if (c != '\'' && c != '"') continue
                        assertTrue(
                            at > 0 && text[at - 1] == '\\',
                            "${folder.name}/$name has a bare $c: write it \\$c — " +
                                "\"${text.substring(maxOf(0, at - 16), minOf(text.length, at + 16))}\"",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `a translation never formats more than the sentence supplies`() {
        // The mistake that survives the build: a translation that formats
        // something the app never passes throws where it is shown, in that
        // language only, on somebody else's phone.
        //
        // Which direction is dangerous is not symmetric, and the rule
        // follows the platform rather than a preference. Formatting is
        // given arguments by position, and it throws for an argument that
        // was asked for and not supplied — never for one supplied and not
        // asked for. So a translation may use fewer, and may not use more
        // or different ones.
        //
        // A plural is where using fewer is not a mistake but the point.
        // Arabic has a word for two of a thing: "صورتان" is "two
        // pictures", the number inside the word, and writing it "2 صورة"
        // to keep a specifier in place would be worse Arabic in service of
        // a tidier test. A plain sentence is different — one that drops
        // the number it was written round has lost something — so that is
        // still held to an exact match.
        val english = namesIn(File(res, "values"))
        for (folder in folders()) {
            if (folder.name == "values") continue
            for ((name, element) in namesIn(folder)) {
                val own = english[name] ?: continue
                val supplied = textsOf(own).flatMap(::marksIn).toSet()
                for (text in textsOf(element)) {
                    val mine = marksIn(text).toSet()
                    assertEquals(
                        emptySet<String>(),
                        mine - supplied,
                        "${folder.name}/$name formats $mine, and the app supplies only $supplied",
                    )
                    if (element.tagName != "plurals") {
                        assertEquals(
                            supplied,
                            mine,
                            "${folder.name}/$name dropped what the sentence was written round",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `Arabic counts the ways Arabic counts`() {
        // Six of them, and three are reachable for a count of pictures: a
        // pair has an ending of its own, three to ten takes the plural,
        // and eleven upward takes the singular after the number. A plural
        // that only knows one and many says "2 صورة", which is the sort
        // of thing that tells a reader the app was written for somebody
        // else and translated afterwards.
        val arabic = namesIn(File(res, "values-ar"))
        val plurals = arabic.filterValues { it.tagName == "plurals" }
        assertTrue(plurals.isNotEmpty(), "no Arabic plurals to check")
        for ((name, element) in plurals) {
            val items = element.getElementsByTagName("item")
            val quantities = (0 until items.length)
                .map { (items.item(it) as Element).getAttribute("quantity") }
                .toSet()
            assertTrue(
                setOf("one", "two", "few", "many", "other").all { it in quantities },
                "Arabic $name counts only $quantities",
            )
        }
    }

    /**
     * Every format specifier in [text], as the platform would read them.
     *
     * A doubled percent is a percent and not a specifier, and it has to be
     * taken out of the way first rather than skipped over: read from the
     * left, "%% confidence" is a literal percent followed by a word, and
     * read carelessly it is a specifier with a space flag — which is what
     * a first attempt at this reported, on a string that was perfectly
     * correct.
     */
    private fun marksIn(text: String): List<String> =
        Regex("%%|%(\\d+\\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[a-zA-Z]")
            .findAll(text)
            .map { it.value }
            .filter { it != "%%" }
            .toList()
}
