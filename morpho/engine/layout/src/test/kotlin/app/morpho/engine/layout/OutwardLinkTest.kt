package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * An address read out of a file is whatever that file said.
 *
 * A link annotation on a PDF, or a relationship in a .docx, is carried
 * straight through to the converted document — so a file made to point at
 * a share on somebody else's machine becomes a document that reaches a
 * stranger's host the moment it is opened, from an app whose whole promise
 * is that a document converted here stays here. Pointed at a scheme the
 * system hands to another program, it is worse than a leak.
 *
 * The schemes a reader chooses to follow are carried; the rest lose the
 * address and keep the words.
 */
class OutwardLinkTest {

    @Test
    fun `the schemes a reader follows are carried`() {
        for (target in listOf(
            "https://example.org/a",
            "http://example.org",
            "HTTPS://EXAMPLE.ORG",
            "mailto:someone@example.org",
            "ftp://files.example.org/x",
            "#a-place-in-this-document",
        )) {
            assertTrue(Links.writable(target), "$target should be carried")
        }
    }

    @Test
    fun `a path on somebody's machine is not an address to write out`() {
        for (target in listOf(
            "file:///etc/passwd",
            "file://attacker.example/share/x",
            """\\attacker.example\share\x""",
            "//attacker.example/share/x",
            "C:\\Users\\someone\\secret.txt",
            "../../etc/passwd",
            "an-ordinary-relative-name.docx",
        )) {
            assertFalse(Links.writable(target), "$target must not be written out")
        }
    }

    @Test
    fun `a scheme handed to another program is not written out`() {
        for (target in listOf(
            "javascript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "vbscript:msgbox",
            "ms-msdt:/id",
            "search-ms:query=x",
            "smb://attacker.example/share",
            "jar:http://example.org/a!/b",
        )) {
            assertFalse(Links.writable(target), "$target must not be written out")
        }
    }

    @Test
    fun `a scheme dressed up to look like one of ours is not one of ours`() {
        for (target in listOf(
            " javascript:alert(1)",
            "java\nscript:alert(1)",
            "jAvAsCrIpT:alert(1)",
            "#",
            "",
            "   ",
        )) {
            assertFalse(Links.writable(target), "$target must not be written out")
        }
        // And the other way round, worth saying outright because it is
        // the rule and not an oversight: this asks what a scheme is, not
        // what a host is. An https address is carried wherever it points,
        // including somewhere nobody should go — a reader clicking a link
        // in a converted document is doing what they chose to do. What is
        // refused is the address that needs no click, or that hands the
        // system a program instead of a page.
        assertTrue(Links.writable("https://attacker.example"))
        assertTrue(Links.writable("https:/\\attacker.example"))
    }

    @Test
    fun `every address the text pass makes is one that can be written out`() {
        // The pass that finds addresses in a document's own words writes
        // mailto in front of an email and https in front of a www, so
        // nothing it makes is ever dropped by the rule above. If that
        // stops being true the rule has silently started throwing away
        // links this converter went to trouble to find.
        val text = "Write to a.b@example.org, or see www.example.org and https://example.org/x " +
            "and ftp://files.example.org/y for the rest."
        val found = Links.find(text)
        assertTrue(found.size >= 4, found.toString())
        for (match in found) {
            assertTrue(
                Links.writable(match.target),
                "the text pass made ${match.target}, which the writers would then drop",
            )
        }
    }

    @Test
    fun `the preview leaves out an address it will not vouch for and keeps the words`() {
        val page = HtmlWriter.write(
            DocumentModel(
                listOf(
                    Paragraph(
                        listOf(
                            TextRun("see "),
                            TextRun("the share", link = """\\attacker.example\share\x"""),
                            TextRun(" and "),
                            TextRun("the site", link = "https://example.org/"),
                        )
                    )
                )
            )
        )
        assertTrue(page.contains("the share"), "the words went with the address: $page")
        assertFalse(page.contains("attacker.example"), page)
        assertTrue(page.contains("""<a href="https://example.org/">the site</a>"""), page)
    }

    @Test
    fun `Markdown leaves out an address it will not vouch for and keeps the words`() {
        val written = MarkdownWriter.write(
            DocumentModel(
                listOf(
                    Paragraph(
                        listOf(
                            TextRun("see "),
                            TextRun("the share", link = "file:///etc/passwd"),
                            TextRun(" and "),
                            TextRun("the site", link = "https://example.org/"),
                        )
                    )
                )
            )
        )
        assertTrue(written.contains("the share"), written)
        assertFalse(written.contains("/etc/passwd"), written)
        assertTrue(written.contains("[the site](https://example.org/)"), written)
    }

    @Test
    fun `a name inside the document is not an outward address at all`() {
        assertEquals(true, Links.writable("#introduction"))
        val page = HtmlWriter.write(
            DocumentModel(
                listOf(
                    Paragraph(listOf(TextRun("see below", link = "#introduction"))),
                    Paragraph(listOf(TextRun("Introduction")), bookmarks = listOf("introduction")),
                )
            )
        )
        assertTrue(page.contains("""href="#bm-introduction""""), page)
    }
}
