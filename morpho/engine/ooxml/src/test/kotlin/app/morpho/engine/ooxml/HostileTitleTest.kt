package app.morpho.engine.ooxml

import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.DocumentProperties
import app.morpho.engine.layout.HtmlWriter
import app.morpho.engine.layout.MarkdownWriter
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.PlainTextImporter
import app.morpho.engine.layout.TextRun
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * What a document says about itself, when what it says is hostile.
 *
 * A title comes out of a file this converter did not write, and it is put
 * in three places with three different quotings: the preview's `title` and
 * `meta` tags, the Word package's `docProps/core.xml`, and the Markdown
 * file's front matter. Each has a way to be broken out of — an angle
 * bracket, a quote, a `]]>`, a line that is itself a fence — and the
 * preview matters most, being HTML the app renders in a WebView.
 *
 * The check is not "does the output look escaped": read that way, the
 * writer's own tags are mistaken for the document's and the test passes or
 * fails for the wrong reason, which is what a first attempt at this did
 * twice. It is the parsed result. What a reader's browser shows as the
 * title has to be the string the document gave, exactly, with nothing
 * extra in the head beside it.
 */
class HostileTitleTest {

    private val hostile = listOf(
        "\"><script>alert(1)</script>",
        "</title><meta http-equiv=refresh content=0>",
        "]]></dc:title><dc:creator>someone else",
        "--- \n title: injected \n ---",
        "& < > \" ' &amp; &#39;",
        "</style><style>body{display:none}",
    )

    @Test
    fun `the preview shows the title the document gave and nothing else`() {
        for (title in hostile) {
            val head = headOf(HtmlWriter.write(model(title), null))
            val titles = head.getElementsByTagName("title")
            assertEquals(1, titles.length, "\"$title\" made ${titles.length} titles")
            assertEquals(title, titles.item(0).textContent, "the preview's title is not the document's")
            // Nothing the document said became a tag: the head holds the
            // writer's own and no more.
            for (tag in listOf("script", "iframe", "object", "link")) {
                assertEquals(
                    0,
                    head.getElementsByTagName(tag).length,
                    "\"$title\" put a <$tag> in the head",
                )
            }
            val metas = head.getElementsByTagName("meta")
            for (index in 0 until metas.length) {
                assertTrue(
                    metas.item(index).attributes.getNamedItem("http-equiv") == null,
                    "\"$title\" put an http-equiv in the head",
                )
            }
        }
    }

    @Test
    fun `the Word package still parses, and says what it was given`() {
        for (title in hostile) {
            val docx = DocxWriter.toByteArray(model(title))
            // An unescaped title would close dc:title early or open an
            // element of its own, and the part would not parse at all.
            DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(ByteArrayInputStream(partOf(docx, "docProps/core.xml")))
            assertEquals(
                title,
                DocxReader.read(docx).properties.title,
                "\"$title\" did not survive the Word package",
            )
        }
    }

    @Test
    fun `the Markdown front matter gives the title back as it was given`() {
        for (title in hostile) {
            val md = MarkdownWriter.write(model(title))
            val back = PlainTextImporter.import(md)
            assertEquals(title, back.properties.title, "\"$title\" did not survive the front matter")
            // A title that is itself a fence must not close the block early
            // and leave its own words as the document's first paragraph.
            assertEquals(
                listOf("The body."),
                back.blocks.filterIsInstance<Paragraph>().map { it.text },
                "\"$title\" leaked into the document",
            )
        }
    }

    @Test
    fun `a title carries no character XML has no way to hold`() {
        // A producer that writes a control character into a title writes
        // one XML cannot carry: a package holding a raw one is a package
        // Word will not open, and a preview holding one is a page a
        // parser refuses. The rule is XML's own, and it is narrower than
        // "no control characters" — tab, newline and carriage return are
        // allowed, and so is DEL, which is why it comes through: dropping
        // a character the format permits would be losing part of a title
        // for no reason.
        val title = "before \u0001 then \u000B then \uFFFE and \u007F after"
        val docx = DocxWriter.toByteArray(model(title))
        // Parsing is the assertion: neither part would parse with a
        // forbidden character in it.
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(partOf(docx, "docProps/core.xml")))
        val shown = headOf(HtmlWriter.write(model(title), null))
            .getElementsByTagName("title").item(0).textContent
        assertTrue(shown.all { legalInXml(it) }, "the preview holds ${shown.map { it.code }}")
        assertEquals(
            title.filter { legalInXml(it) },
            DocxReader.read(docx).properties.title,
            "the Word package lost or kept the wrong characters",
        )
        // DEL is legal, so it is still there; the rest are gone.
        assertTrue(shown.contains('\u007F'), "a character XML allows was dropped anyway")
        assertTrue(shown.none { it == '\u0001' || it == '\u000B' || it == '\uFFFE' })
    }

    /** Whether XML 1.0 can carry [c] at all. */
    private fun legalInXml(c: Char): Boolean = when {
        c == '\t' || c == '\n' || c == '\r' -> true
        c.code < 0x20 -> false
        c == '\uFFFE' || c == '\uFFFF' -> false
        else -> true
    }

    private fun model(title: String) = DocumentModel(
        blocks = listOf(Paragraph(listOf(TextRun("The body.")))),
        properties = DocumentProperties.of(title, title, title, title),
    )

    /** The preview's head, parsed — the only reading of it that cannot lie. */
    private fun headOf(html: String): org.w3c.dom.Element {
        val from = html.indexOf("<head>")
        val to = html.indexOf("</head>") + "</head>".length
        assertTrue(from in 0 until to, "the preview has no head")
        val fragment = html.substring(from, to)
            .replace("<style>", "<style><![CDATA[")
            .replace("</style>", "]]></style>")
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(fragment.toByteArray(Charsets.UTF_8)))
            .documentElement
    }

    private fun partOf(docx: ByteArray, name: String): ByteArray {
        ZipInputStream(ByteArrayInputStream(docx)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == name) return zip.readBytes()
            }
        }
        error("no $name in the package")
    }
}
