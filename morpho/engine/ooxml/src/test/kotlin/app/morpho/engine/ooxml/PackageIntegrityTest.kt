package app.morpho.engine.ooxml

import app.morpho.engine.layout.Alignment
import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.DocumentProperties
import app.morpho.engine.layout.ImageBlock
import app.morpho.engine.layout.ListMarker
import app.morpho.engine.layout.PageSetup
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.ParagraphKind
import app.morpho.engine.layout.ParagraphStyle
import app.morpho.engine.layout.PlainTextImporter
import app.morpho.engine.layout.RunField
import app.morpho.engine.layout.Table
import app.morpho.engine.layout.TableCell
import app.morpho.engine.layout.TableRow
import app.morpho.engine.layout.TextDirection
import app.morpho.engine.layout.TextRun
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A .docx is a package of parts that point at one another, and Word is
 * unforgiving about it: a relationship id naming nothing, a list numbered
 * against a definition that is not there, a part with no content type, and
 * the file opens as "unreadable content" — or opens with the numbering
 * silently gone. None of that shows in a round trip, because our own
 * reader resolves what it can and ignores what it cannot.
 *
 * So the packages we write are checked here as a package: every part
 * typed, every reference resolving to something the package holds, every
 * list and every style named actually defined.
 *
 * This is the same reading that found a blank line under every table —
 * done by somebody else's parser then, and kept here so it stays done.
 */
class PackageIntegrityTest {

    private val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val r = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private val contentTypes = "http://schemas.openxmlformats.org/package/2006/content-types"
    private val packageRels = "http://schemas.openxmlformats.org/package/2006/relationships"

    private fun partsOf(docx: ByteArray): Map<String, ByteArray> {
        val parts = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(docx)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) parts[entry.name] = zip.readBytes()
            }
        }
        return parts
    }

    private fun parse(bytes: ByteArray): Element {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement
    }

    private fun descendants(root: Element): List<Element> {
        val out = mutableListOf<Element>()
        fun walk(node: Node) {
            if (node is Element) out += node
            var child = node.firstChild
            while (child != null) {
                walk(child)
                child = child.nextSibling
            }
        }
        walk(root)
        return out
    }

    private fun valuesOf(root: Element, localName: String): List<String> =
        descendants(root).filter { it.namespaceURI == w && it.localName == localName }
            .mapNotNull { it.getAttributeNS(w, "val").ifEmpty { null } }

    /** Everything wrong with [docx] as a package, in the words a reader would use. */
    private fun faults(docx: ByteArray): List<String> {
        val parts = partsOf(docx)
        val faults = mutableListOf<String>()

        val types = parse(parts["[Content_Types].xml"] ?: return listOf("no [Content_Types].xml"))
        val defaults = descendants(types).filter { it.localName == "Default" && it.namespaceURI == contentTypes }
            .map { it.getAttribute("Extension").lowercase() }.toSet()
        val overrides = descendants(types).filter { it.localName == "Override" && it.namespaceURI == contentTypes }
            .map { it.getAttribute("PartName") }.toSet()
        for (name in parts.keys) {
            if (name == "[Content_Types].xml") continue
            val extension = name.substringAfterLast('.', "").lowercase()
            if ("/$name" !in overrides && extension !in defaults) faults += "$name has no content type"
        }

        for ((name, bytes) in parts) {
            if (!name.endsWith(".xml") || name.endsWith(".rels")) continue
            val folder = name.substringBeforeLast('/', "")
            val relsName = if (folder.isEmpty()) "_rels/${name}.rels"
            else "$folder/_rels/${name.substringAfterLast('/')}.rels"
            val targets = parts[relsName]?.let { rels ->
                descendants(parse(rels))
                    .filter { it.localName == "Relationship" && it.namespaceURI == packageRels }
                    .associate { it.getAttribute("Id") to (it.getAttribute("Target") to it.getAttribute("TargetMode")) }
            }.orEmpty()
            val root = parse(bytes)
            val used = descendants(root).flatMap { element ->
                (0 until element.attributes.length).mapNotNull { index ->
                    val attribute = element.attributes.item(index)
                    if (attribute.namespaceURI == r) attribute.nodeValue else null
                }
            }.toSet()
            for (id in used.sorted()) {
                val target = targets[id]
                if (target == null) {
                    faults += "$name names $id, which its relationships do not define"
                    continue
                }
                if (target.second == "External") continue
                val resolved = File(folder, target.first).normalize().path.replace('\\', '/')
                if (resolved !in parts) faults += "$name points at $resolved, which the package does not hold"
            }
        }

        val document = parts["word/document.xml"]?.let(::parse)
        if (document != null) {
            val numbering = parts["word/numbering.xml"]?.let(::parse)
            val defined = numbering?.let { root ->
                descendants(root).filter { it.namespaceURI == w && it.localName == "num" }
                    .mapNotNull { it.getAttributeNS(w, "numId").ifEmpty { null } }.toSet()
            }.orEmpty()
            for (id in valuesOf(document, "numId")) {
                if (id != "0" && id !in defined) faults += "a paragraph is numbered $id, which numbering.xml does not define"
            }
            val styles = parts["word/styles.xml"]?.let { bytes ->
                descendants(parse(bytes)).filter { it.namespaceURI == w && it.localName == "style" }
                    .mapNotNull { it.getAttributeNS(w, "styleId").ifEmpty { null } }.toSet()
            }.orEmpty()
            for (name in listOf("pStyle", "rStyle", "tblStyle")) {
                for (id in valuesOf(document, name)) {
                    if (id !in styles) faults += "a $name names $id, which styles.xml does not define"
                }
            }
        }
        return faults
    }

    private val png: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    )

    private fun line(text: String, style: ParagraphStyle = ParagraphStyle()) =
        Paragraph(listOf(TextRun(text)), style)

    /** One document holding what a package can be wrong about. */
    private fun rich(): DocumentModel {
        val picture = ImageBlock(png, "image/png", 1, 1)
        val inner = Table(listOf(TableRow(listOf(TableCell(listOf(line("inner")))))))
        val outer = Table(
            listOf(
                TableRow(listOf(TableCell(listOf(line("head"))), TableCell(listOf(line("also")))), repeatsAsHeader = true),
                TableRow(listOf(TableCell(listOf(line("a cell"), inner)), TableCell(listOf(picture)))),
            )
        )
        return DocumentModel(
            blocks = listOf(
                line("A title", ParagraphStyle(kind = ParagraphKind.TITLE)),
                line("A heading", ParagraphStyle(kind = ParagraphKind.HEADING_1)),
                Paragraph(
                    listOf(
                        TextRun("A claim"),
                        TextRun("1", note = listOf(line("The note itself."))),
                        TextRun(" and a "),
                        TextRun("link", link = "https://example.com/x"),
                        TextRun(" and a picture "),
                        TextRun("", image = ImageBlock(png, "image/png", 1, 1)),
                    )
                ),
                line("An item", ParagraphStyle(listMarker = ListMarker.BULLET)),
                line("A deeper item", ParagraphStyle(listMarker = ListMarker.NUMBERED, listLevel = 1)),
                line("Numbered", ParagraphStyle(listMarker = ListMarker.NUMBERED, listFormat = "arabicAlpha")),
                outer,
                line("سطر عربي", ParagraphStyle(direction = TextDirection.RTL, alignment = Alignment.END)),
                line(
                    "On a turned page",
                    ParagraphStyle(
                        sectionSetup = PageSetup(842f, 595f, 72f, 72f, 72f, 72f),
                    ),
                ),
            ),
            defaultDirection = TextDirection.RTL,
            defaultLanguage = "ar-DZ",
            pageSetup = PageSetup(595f, 842f, 72f, 72f, 72f, 72f, firstPageNumber = 48),
            header = listOf(line("The running head"), Table(listOf(TableRow(listOf(TableCell(listOf(line("h")))))))),
            footer = listOf(
                Paragraph(listOf(TextRun("48", field = RunField.PAGE_NUMBER))),
            ),
            evenHeader = listOf(line("The other side")),
            properties = DocumentProperties(title = "A Study", author = "R. Nebbar"),
        )
    }

    private fun corpusFiles(): List<String> {
        val url = PackageIntegrityTest::class.java.getResource("/corpus") ?: return emptyList()
        return File(url.toURI()).list()!!.sorted()
    }

    private fun corpusModel(name: String): DocumentModel =
        PlainTextImporter.import(
            PackageIntegrityTest::class.java.getResourceAsStream("/corpus/$name")!!
                .readBytes().toString(Charsets.UTF_8)
        )

    @Test
    fun `a package holding every part a document can have points only at itself`() {
        val faults = faults(DocxWriter.toByteArray(rich()))
        assertTrue(faults.isEmpty(), "the package Word would refuse:\n" + faults.joinToString("\n"))
    }

    @Test
    fun `a document read back and written again is still a whole package`() {
        val once = DocxWriter.toByteArray(rich())
        val faults = faults(DocxWriter.toByteArray(DocxReader.read(once)))
        assertTrue(faults.isEmpty(), "the package Word would refuse:\n" + faults.joinToString("\n"))
    }

    @Test
    fun `every document of the corpus is written as a whole package`() {
        val names = corpusFiles()
        assertTrue(names.isNotEmpty(), "corpus resource directory missing")
        val faults = names.flatMap { name ->
            faults(DocxWriter.toByteArray(corpusModel(name))).map { "$name: $it" }
        }
        assertTrue(faults.isEmpty(), "the packages Word would refuse:\n" + faults.joinToString("\n"))
    }

    @Test
    fun `a document with nothing in it is still a whole package`() {
        val faults = faults(DocxWriter.toByteArray(DocumentModel(emptyList())))
        assertTrue(faults.isEmpty(), faults.joinToString("\n"))
    }

    /** [docx] with [from] replaced by [to] in its main part, and nothing else touched. */
    private fun edited(docx: ByteArray, from: String, to: String): ByteArray {
        val parts = partsOf(docx).toMutableMap()
        val document = parts["word/document.xml"]!!.toString(Charsets.UTF_8)
        check(document.contains(from)) { "the fixture does not hold \"$from\"" }
        parts["word/document.xml"] = document.replace(from, to).toByteArray(Charsets.UTF_8)
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            for ((name, bytes) in parts) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `the reading finds what a broken package is broken about`() {
        // A checker that passes everything proves nothing, so here is a
        // package broken each of the three ways Word minds.
        val good = DocxWriter.toByteArray(rich())
        val numbered = faults(edited(good, "<w:numId w:val=", "<w:numId w:val=\"9911\"/><w:ignored w:val="))
        assertTrue(
            numbered.any { it.contains("9911") },
            "a list numbered against nothing went unnoticed: $numbered",
        )
        val pointed = faults(edited(good, "r:id=\"rId", "r:id=\"rIdMissing"))
        assertTrue(
            pointed.any { it.contains("rIdMissing") },
            "a reference to nothing went unnoticed: $pointed",
        )
        val styled = faults(edited(good, "<w:pStyle w:val=", "<w:pStyle w:val=\"NoSuchStyle\"/><w:ignored w:val="))
        assertTrue(
            styled.any { it.contains("NoSuchStyle") },
            "a style that is not defined went unnoticed: $styled",
        )
    }
}
