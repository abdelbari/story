package app.morpho.engine.layout.pdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Reading what recognition says about a page.
 *
 * Asked for plain text, Tesseract hands back a string. Asked for hOCR it
 * writes out the box of every word, which words share a line, and its own
 * estimate of how big that line's type is. The fixtures here are shaped
 * like the markup it really writes — mixed quoting, the page and the
 * column as `div`s, the line and the word as `span`s, a `title` holding a
 * semicolon-separated list of clauses — because a reader tested only
 * against tidy markup is a reader tested against markup it will never see.
 *
 * Pages are rendered at 200 dpi before recognition, so every measurement
 * arrives in pixels of that image and leaves in points.
 */
class HocrTest {

    private val dpi = 200f

    /** 72 points to the inch, at the resolution pages are rendered. */
    private fun pt(pixels: Float) = pixels * 72f / dpi

    private fun page(lines: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en" lang="en">
        <head><title></title>
        <meta name='ocr-system' content='tesseract 5.3.0' />
        <meta name='ocr-capabilities' content='ocr_page ocr_carea ocr_par ocr_line ocrx_word ocrp_wconf'/>
        </head>
        <body>
        <div class='ocr_page' id='page_1' title='image ""; bbox 0 0 1654 2339; ppageno 0'>
        <div class='ocr_carea' id='block_1_1' title="bbox 200 300 1454 900">
        <p class='ocr_par' dir='ltr' id='par_1_1' title="bbox 200 300 1454 900">
        $lines
        </p></div></div>
        </body></html>
    """.trimIndent()

    /** A line of hOCR as Tesseract writes it, with [size] as its `x_size`. */
    private fun line(size: Int?, kind: String = "ocr_line", words: String) =
        "<span class='$kind' id='line_1_1' title=\"bbox 200 300 1454 356; " +
            "baseline 0.002 -11" + (if (size == null) "" else "; x_size $size") +
            "; x_descenders 12; x_ascenders 13\">$words</span>"

    private fun word(text: String, left: Int, top: Int, right: Int, bottom: Int, conf: Int = 96) =
        "<span class='ocrx_word' id='word_1_1' title='bbox $left $top $right $bottom; " +
            "x_wconf $conf'>$text</span>"

    @Test
    fun `a page of hOCR comes back as the words that are on it`() {
        val words = Hocr.wordsOf(
            page(
                line(30, words = word("The", 200, 300, 268, 340) + word("form", 280, 300, 390, 350)) +
                    line(30, words = word("in", 200, 380, 240, 420) + word("research", 252, 380, 460, 430)),
            ),
            page = 1,
            dpi = dpi,
        )
        assertEquals(listOf("The", "form", "in", "research"), words.map { it.text })
        assertEquals(listOf(1, 1, 1, 1), words.map { it.page })
        assertEquals(
            listOf(true, false, true, false),
            words.map { it.startsLine },
            "the first word of each line opens it and no other word does",
        )
    }

    @Test
    fun `the pixels a page was recognised in come back as points`() {
        val words = Hocr.wordsOf(
            page(line(30, words = word("Tagged", 200, 300, 468, 356))),
            page = 3,
            dpi = dpi,
        )
        val only = words.single()
        assertEquals(pt(200f), only.left, 0.001f)
        assertEquals(pt(300f), only.top, 0.001f)
        assertEquals(pt(468f), only.right, 0.001f)
        assertEquals(pt(356f), only.bottom, 0.001f)
        assertEquals(3, only.page, "the page is the document's, not recognition's own")
    }

    @Test
    fun `recognition's measure of the type is what makes a heading findable`() {
        // The whole reason for reading hOCR rather than plain text. A
        // word's box is only as tall as the tallest letter in it, so
        // "man" boxes at nearly half of "Tagged" in the very same type
        // and a heading in a paper measured that way is indistinguishable
        // from a line of body text that happens to have a capital in it.
        // Recognition measured the line's x-height and its ascenders and
        // will say so, if asked.
        val words = Hocr.wordsOf(
            page(
                line(56, words = word("Introduction", 200, 300, 700, 356)) +
                    line(30, words = word("man", 200, 400, 290, 430)),
            ),
            page = 1,
            dpi = dpi,
        )
        assertEquals(pt(56f), words[0].sizePt!!, 0.001f)
        assertEquals(pt(30f), words[1].sizePt!!, 0.001f)
        assertTrue(
            words[0].sizePt!! / words[1].sizePt!! > 1.2f,
            "a heading has to measure larger than body text or it is not found",
        )
        // Measured from the boxes instead, the two are the same size and
        // the heading is lost — which is what a scan used to convert to.
        assertEquals(56f, (words[0].bottom - words[0].top) / pt(1f), 0.01f)
        assertEquals(30f, (words[1].bottom - words[1].top) / pt(1f), 0.01f)
    }

    @Test
    fun `every word of a line carries that line's measure`() {
        val words = Hocr.wordsOf(
            page(line(56, words = word("A", 200, 300, 240, 356) + word("Study", 252, 300, 480, 356))),
            page = 1,
            dpi = dpi,
        )
        assertEquals(listOf(pt(56f), pt(56f)), words.map { it.sizePt })
    }

    @Test
    fun `a line recognition did not measure leaves its words unmeasured`() {
        // Null is not zero and not a guess: the lines are measured from
        // their boxes instead, which is worse but is at least a measure of
        // something. An invented size would be quietly wrong everywhere.
        val words = Hocr.wordsOf(
            page(line(null, words = word("unmeasured", 200, 300, 600, 356))),
            page = 1,
            dpi = dpi,
        )
        assertNull(words.single().sizePt)
    }

    @Test
    fun `a heading, a caption and a line beside the text all begin lines`() {
        // Recognition does not call every line `ocr_line`. A reader that
        // knew only that name would run a page's caption into whatever
        // paragraph came before it, and a heading it set as its own class
        // would vanish into the body.
        for (kind in listOf("ocr_line", "ocr_header", "ocr_caption", "ocr_textfloat")) {
            val words = Hocr.wordsOf(
                page(
                    line(30, words = word("body", 200, 300, 300, 340)) +
                        line(56, kind = kind, words = word("Chapter", 200, 380, 500, 436)),
                ),
                page = 1,
                dpi = dpi,
            )
            assertEquals(
                listOf(true, true),
                words.map { it.startsLine },
                "a <$kind> did not begin a line",
            )
            assertEquals(pt(56f), words[1].sizePt!!, 0.001f, "a <$kind> was not measured")
        }
    }

    @Test
    fun `a word recognition set in bold or in italics is marked as such`() {
        val words = Hocr.wordsOf(
            page(
                line(
                    30,
                    words = word("<strong>heavy</strong>", 200, 300, 340, 340) +
                        word("<em>slanted</em>", 352, 300, 500, 340) +
                        word("<b>heavy</b>", 512, 300, 640, 340) +
                        word("<i>slanted</i>", 652, 300, 780, 340) +
                        word("plain", 792, 300, 880, 340),
                ),
            ),
            page = 1,
            dpi = dpi,
        )
        assertEquals(
            listOf("heavy", "slanted", "heavy", "slanted", "plain"),
            words.map { it.text },
            "the markup around a word is not part of the word",
        )
        assertEquals(listOf(true, false, true, false, false), words.map { it.bold })
        assertEquals(listOf(false, true, false, true, false), words.map { it.italic })
    }

    @Test
    fun `what a word says is what it said, entities and all`() {
        val words = Hocr.wordsOf(
            page(
                line(
                    30,
                    words = word("&amp;", 200, 300, 240, 340) +
                        word("&lt;tag&gt;", 252, 300, 340, 340) +
                        word("&quot;quoted&quot;", 352, 300, 500, 340) +
                        word("it&#39;s", 512, 300, 600, 340) +
                        word("&#x627;&#x644;&#x628;&#x62D;&#x62B;", 612, 300, 760, 340),
                ),
            ),
            page = 1,
            dpi = dpi,
        )
        assertEquals(
            listOf("&", "<tag>", "\"quoted\"", "it's", "البحث"),
            words.map { it.text },
        )
    }

    @Test
    fun `an ampersand that names nothing stays an ampersand`() {
        val words = Hocr.wordsOf(
            page(line(30, words = word("R&amp;D &notanentity; 100&nbsp;km", 200, 300, 700, 340))),
            page = 1,
            dpi = dpi,
        )
        // A space that does not break stays one that does not break: it is
        // a different character from a space, and a measurement written
        // with one is written that way on purpose.
        assertEquals("R&D &notanentity; 100\u00A0km", words.single().text)
    }

    @Test
    fun `markup a little off costs a word at most, never the page`() {
        // hOCR is machine-written and regular, which is why this scans it
        // instead of parsing it as a document: a parser meeting one
        // malformed tag throws and the page is gone. A page of a scan is
        // seconds of recognition that cannot be had again cheaply, and a
        // reader is far better served losing a word out of the middle of
        // it than losing the page.
        val unclosed = page(
            line(30, words = word("before", 200, 300, 340, 340)) +
                "<span class='ocrx_word' title='bbox 352 300 460 340'>ragged" +
                line(30, words = word("after", 200, 380, 320, 420)),
        )
        val words = Hocr.wordsOf(unclosed, page = 1, dpi = dpi)
        assertEquals(
            listOf("before", "ragged", "after"),
            words.map { it.text },
            "an unclosed word swallowed the line that followed it",
        )
        assertEquals(
            listOf(true, false, true),
            words.map { it.startsLine },
            "the line after the fault did not begin",
        )

        // A page cut off mid-tag — the recogniser killed, the process out
        // of memory — keeps everything read up to the cut.
        val cut = page(line(30, words = word("kept", 200, 300, 300, 340)))
            .substringBefore("</p>") +
            "<span class='ocr_line' title=\"x_size 30\"><span class='ocrx_word' title='bbox 1"
        assertEquals(listOf("kept"), Hocr.wordsOf(cut, page = 1, dpi = dpi).map { it.text })
    }

    @Test
    fun `a word with no box and a word with nothing in it are not words`() {
        val words = Hocr.wordsOf(
            page(
                line(
                    30,
                    words = "<span class='ocrx_word' id='word_1_1' title='x_wconf 12'>boxless</span>" +
                        word("   ", 200, 300, 240, 340) +
                        word("", 252, 300, 260, 340) +
                        word("real", 272, 300, 360, 340),
                ),
            ),
            page = 1,
            dpi = dpi,
        )
        assertEquals(listOf("real"), words.map { it.text })
        assertTrue(
            words.single().startsLine,
            "the line's beginning belongs to the first word that is really there",
        )
    }

    @Test
    fun `a page of nothing is no words`() {
        assertEquals(emptyList<RecognizedWord>(), Hocr.wordsOf("", page = 1, dpi = dpi))
        assertEquals(emptyList<RecognizedWord>(), Hocr.wordsOf(page(""), page = 1, dpi = dpi))
        assertEquals(
            emptyList<RecognizedWord>(),
            Hocr.wordsOf("not markup at all, just a sentence", page = 1, dpi = dpi),
        )
    }

    @Test
    fun `a resolution of nothing is not divided by`() {
        for (nothing in listOf(0f, -200f, Float.NaN)) {
            val words = Hocr.wordsOf(
                page(line(30, words = word("word", 200, 300, 340, 340))),
                page = 1,
                dpi = nothing,
            )
            val only = words.single()
            assertTrue(only.left.isFinite() && only.right.isFinite(), "$nothing dpi gave $only")
            assertTrue(only.right > only.left, "$nothing dpi turned the box inside out")
        }
    }

    @Test
    fun `a bare title is read whichever quote it is written with`() {
        // Tesseract quotes `class` with apostrophes and a line's `title`
        // with quotation marks, in the same tag. Both are hOCR.
        val double = Hocr.wordsOf(
            "<span class=\"ocr_line\" title=\"x_size 56\">" +
                "<span class=\"ocrx_word\" title=\"bbox 10 20 30 40\">word</span></span>",
            page = 1,
            dpi = dpi,
        )
        assertEquals(listOf("word"), double.map { it.text })
        assertEquals(pt(56f), double.single().sizePt!!, 0.001f)
    }

    @Test
    fun `the words of a page become the lines a reading takes`() {
        // The end of it: what recognition wrote, through to the lines the
        // untagged reading works on. A heading has to come out measurably
        // larger than the body around it, because that is the measure the
        // reading uses and the one a scan used to have no way to give.
        val words = Hocr.wordsOf(
            page(
                line(
                    56,
                    kind = "ocr_header",
                    words = word("Introduction", 200, 300, 700, 356),
                ) +
                    line(
                        30,
                        words = word("The", 200, 400, 268, 430) +
                            word("form", 280, 400, 390, 440) +
                            word("in", 402, 400, 442, 430),
                    ) +
                    line(30, words = word("research.", 200, 460, 420, 500)),
            ),
            page = 1,
            dpi = dpi,
        )
        val lines = RecognizedText.linesOf(words)
        assertEquals(listOf("Introduction", "The form in", "research."), lines.map { it.text })
        // The sizes are anchored on the document's body rather than left
        // in the units recognition measured, so the two body lines land on
        // the body size and the heading keeps its ratio to them: 56 to 30
        // in the image, 22.5 to 12 on the page.
        assertEquals(22.5f, lines[0].maxFontSize, 0.001f)
        assertEquals(12f, lines[1].maxFontSize, 0.001f)
        assertEquals(
            56f / 30f,
            lines[0].maxFontSize / lines[1].maxFontSize,
            0.02f,
            "the scale recognition measured did not survive being anchored",
        )
        assertEquals(pt(200f), lines[1].x, 0.001f)
        assertEquals(pt(442f), lines[1].xEnd, 0.001f)
        assertEquals(
            listOf("The", "form", "in"),
            lines[1].segments.map { it.text },
            "a table is found from where the words sit, so each has to be its own",
        )
        // And it measures as a heading against the body around it, by the
        // one rule both PDF readers use — which is the whole point of
        // asking recognition for boxes rather than for a string.
        val body = HeadingSizes.median(lines.drop(1).map { it.maxFontSize })
        assertTrue(
            HeadingSizes.isCandidate(lines[0].maxFontSize, lines[0].text.length, body),
            "${lines[0].maxFontSize}pt over a ${body}pt body does not read as a heading",
        )
    }
}
