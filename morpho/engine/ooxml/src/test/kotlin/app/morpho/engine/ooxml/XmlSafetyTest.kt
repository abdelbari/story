package app.morpho.engine.ooxml

import app.morpho.engine.layout.Paragraph
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A .docx is a stranger's XML, and this app's whole promise is that a
 * document converted on a phone stays on it.
 *
 * An XML parser told nothing will fetch what a document's own doctype
 * tells it to fetch. A file crafted to declare an entity pointing at
 * `/etc/passwd`, or at an address, and then to use that entity in a
 * paragraph, comes out as a converted document with the contents of
 * somebody's file in it — or reaches the network from an app that has no
 * permission to. Either one is the promise broken by the format rather
 * than by the code.
 *
 * The reader disallows a doctype outright, which is the setting that
 * closes all of it: with no doctype there is no entity to declare, no
 * file to fetch and no expansion to run away with. That is the behaviour
 * pinned here, and the reason the last test reads the reader's own source
 * — a third parser added later without the setting would reopen every one
 * of these doors, and would pass every other test in this suite.
 *
 * A file is what the first test reaches for, and an address would do as
 * well: a `SYSTEM` entity is the same instruction whichever scheme it
 * names, and the parser is told to take no instructions. That case is not
 * tested separately because it could not be tested honestly here — a check
 * that an address goes unfetched passes whether the reader is hardened or
 * not, since nothing in this container answers, and a test that cannot
 * fail is worse than none. The file case proves the mechanism, and there
 * is one mechanism.
 */
class XmlSafetyTest {

    /** A package whose document part is [documentXml] verbatim. */
    private fun packaged(documentXml: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun part(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            part(
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8"?>""" +
                    """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
                    """<Default Extension="xml" ContentType="application/xml"/>""" +
                    """<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>""" +
                    """</Types>""",
            )
            part(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8"?>""" +
                    """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                    """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>""" +
                    """</Relationships>""",
            )
            part("word/document.xml", documentXml)
        }
        return out.toByteArray()
    }

    private fun textOf(docx: ByteArray): String =
        DocxReader.read(docx).blocks.filterIsInstance<Paragraph>().joinToString(" ") { it.text }

    /** What the reader made of [documentXml], or null where it refused it. */
    private fun read(documentXml: String): String? =
        runCatching { textOf(packaged(documentXml)) }.getOrNull()

    private val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    @Test
    fun `a file that declares an entity for a local file gets none of it`() {
        // A real file on this machine, so a reader that resolved the entity
        // would have something to show for it.
        val secret = File.createTempFile("morpho-xxe", ".txt")
        secret.writeText("THE-SECRET-CONTENTS")
        try {
            val said = read(
                """<?xml version="1.0" encoding="UTF-8"?>""" +
                    """<!DOCTYPE w:document [<!ENTITY leak SYSTEM "file://${secret.absolutePath}">]>""" +
                    """<w:document xmlns:w="$w"><w:body><w:p><w:r>""" +
                    """<w:t>before &leak; after</w:t></w:r></w:p></w:body></w:document>""",
            )
            // Refused outright is the answer, and the right one. What must
            // never happen is a document that came back holding the file.
            assertFalse(
                said?.contains("THE-SECRET-CONTENTS") == true,
                "a crafted file read this machine's files into the document: $said",
            )
        } finally {
            secret.delete()
        }
    }

    @Test
    fun `an entity that expands into itself does not run away with the reading`() {
        // The billion laughs: entities that each name the one before them,
        // so a parser that expands them allocates gigabytes from a file of
        // a few hundred bytes. Refusing the doctype refuses the lot.
        val entities = (1..8).joinToString("") { level ->
            val inner = if (level == 1) "haha" else "&e${level - 1};&e${level - 1};&e${level - 1};"
            """<!ENTITY e$level "$inner">"""
        }
        org.junit.jupiter.api.assertTimeoutPreemptively(java.time.Duration.ofSeconds(20)) {
            val said = read(
                """<?xml version="1.0" encoding="UTF-8"?>""" +
                    """<!DOCTYPE w:document [$entities]>""" +
                    """<w:document xmlns:w="$w"><w:body><w:p><w:r>""" +
                    """<w:t>&e8;</w:t></w:r></w:p></w:body></w:document>""",
            )
            assertTrue(said == null, "an expanding entity was read rather than refused")
        }
    }

    @Test
    fun `an ordinary file is still read`() {
        // The setting must close the door and no more: a package with no
        // doctype in it reads exactly as it always did.
        assertTrue(
            read(
                """<?xml version="1.0" encoding="UTF-8"?>""" +
                    """<w:document xmlns:w="$w"><w:body><w:p><w:r>""" +
                    """<w:t>an ordinary paragraph</w:t></w:r></w:p></w:body></w:document>""",
            ) == "an ordinary paragraph",
        )
    }

    @Test
    fun `every parser the reader makes is told to refuse a doctype`() {
        // The three tests above prove the two parsers that exist today.
        // This one is for the parser somebody adds tomorrow: it would pass
        // all of them, since they only ever reach the parts these parsers
        // read, and it would reopen every door at once.
        val source = File("src/main/kotlin/app/morpho/engine/ooxml/DocxReader.kt")
        assertTrue(source.isFile, "no reader source at ${source.absolutePath}")
        val text = source.readText()
        val made = Regex("""DocumentBuilderFactory\.newInstance\(\)""").findAll(text).count()
        val told = Regex("""disallow-doctype-decl""").findAll(text).count()
        assertTrue(made > 0, "the reader stopped making XML parsers the way this checks for")
        assertTrue(
            told >= made,
            "the reader makes $made XML parsers and tells $told of them to refuse a doctype. " +
                "A parser that takes a stranger's doctype will fetch whatever it names.",
        )
    }
}
