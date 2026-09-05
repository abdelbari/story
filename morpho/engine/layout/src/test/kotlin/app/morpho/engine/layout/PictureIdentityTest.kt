package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A picture is bytes, and bytes compare by identity unless somebody says
 * otherwise, so [ImageBlock] writes its own equals and hashCode and its
 * own copy. Hand-written, all three can fall behind the class: a field
 * added later and left out of equals makes two different pictures equal,
 * and one left out of copy is silently dropped wherever a picture is
 * rebuilt — which is how a table's cells once lost the colour they were
 * filled with.
 *
 * What holds them to the class is the count below: it is read off the
 * constructor rather than written out, so adding a field to a picture
 * without saying here what else it could be fails this test rather than
 * passing it quietly.
 */
class PictureIdentityTest {

    private val one = ImageBlock(
        bytes = byteArrayOf(1, 2, 3),
        mimeType = "image/png",
        widthPx = 12,
        heightPx = 8,
        confidence = 0.6f,
        widthPt = 24f,
        heightPt = 16f,
        description = "what it shows",
    )

    /** The same picture with one thing about it different, one for each field. */
    private val differing = mapOf(
        "bytes" to one.copy(bytes = byteArrayOf(9)),
        "mimeType" to one.copy(mimeType = "image/jpeg"),
        "widthPx" to one.copy(widthPx = 13),
        "heightPx" to one.copy(heightPx = 9),
        "confidence" to one.copy(confidence = 0.9f),
        "widthPt" to one.copy(widthPt = 25f),
        "heightPt" to one.copy(heightPt = 17f),
        "description" to one.copy(description = "something else"),
    )

    /**
     * How many things a picture is made of, read off the constructor
     * itself. Kotlin writes a second constructor to carry the defaults,
     * with two arguments of its own; the real one is the shorter.
     */
    private fun fieldCount(): Int =
        ImageBlock::class.java.declaredConstructors.minOf { it.parameterCount }

    @Test
    fun `every field a picture is made of has something different to try`() {
        assertEquals(
            fieldCount(),
            differing.size,
            "a field was added to a picture and this test was not told what else it could be",
        )
    }

    @Test
    fun `a picture copied without changing anything is the same picture`() {
        assertEquals(one, one.copy())
        assertEquals(one.hashCode(), one.copy().hashCode())
        assertTrue(one.bytes.contentEquals(one.copy().bytes))
    }

    @Test
    fun `changing any one thing makes it a different picture`() {
        for ((name, other) in differing) {
            assertNotEquals(
                one,
                other,
                "two pictures differing in $name compare equal, so equals has fallen behind the class",
            )
        }
    }

    @Test
    fun `a copy changes the one thing it is given and no other`() {
        val described = one.copy(description = "something else")
        assertEquals("something else", described.description)
        assertEquals(one.widthPt, described.widthPt)
        assertEquals(one.confidence, described.confidence)
        assertTrue(one.bytes.contentEquals(described.bytes))
        val resized = one.copy(widthPx = 99)
        assertEquals(one.mimeType, resized.mimeType)
        assertEquals(one.description, resized.description)
    }
}
