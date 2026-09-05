package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** Just enough JSON, read as if a hostile page wrote it. */
class JsonTest {

    private val formFeed = 0x0C.toChar()
    private val lineSeparator = 0x2028.toChar()

    private fun value(random: Random, depth: Int): Any? = when (random.nextInt(if (depth > 4) 5 else 7)) {
        0 -> null
        1 -> random.nextBoolean()
        2 -> if (random.nextBoolean()) random.nextInt(-1000, 1000).toDouble() else random.nextDouble(-1e6, 1e6)
        3, 4 -> (1..random.nextInt(0, 12)).map {
            listOf("a", "\"", "\\", "\n", "\t", "é", "🙂", "$lineSeparator", "$formFeed", "<b>", "ع")[random.nextInt(11)]
        }.joinToString("")
        5 -> (1..random.nextInt(0, 4)).map { value(random, depth + 1) }
        else -> LinkedHashMap<String, Any?>().also { map ->
            repeat(random.nextInt(0, 4)) { map["k$it" + listOf("", "\"", " ")[random.nextInt(3)]] = value(random, depth + 1) }
        }
    }

    @Test
    fun `whatever is written reads back as itself`() {
        for (seed in 1..1000) {
            val value = value(Random(seed), 0)
            val text = Json.write(value)
            assertEquals(value, Json.parse(text), "seed $seed: $text")
            // And what was written is a JavaScript string too: nothing a
            // script would take for a line end, and nothing unescaped that
            // could close a tag when the reply is put into a page.
            assertFalse(text.contains(lineSeparator), "seed $seed wrote a line separator raw")
            assertTrue(text.none { it < ' ' }, "seed $seed wrote a control character raw")
        }
    }

    @Test
    fun `a string with every escape reads back`() {
        val expected = StringBuilder().append("\"\\/\b").append(formFeed).append("\n\r\t\u00e9").toString()
        assertEquals(expected, Json.parse("\"\\\"\\\\\\/\\b\\f\\n\\r\\t\\u00e9\""))
        assertEquals("\\u2028", Json.write("$lineSeparator").trim('"'))
    }

    @Test
    fun `numbers read as JSON has them`() {
        assertEquals(listOf(1.0, -2.5, 300.0, 0.0), Json.parse("[1, -2.5, 3e2, 0]"))
        assertEquals("[1,-2.5,300,0.5]", Json.write(listOf(1.0, -2.5, 300.0, 0.5)))
        assertEquals("[7,8]", Json.write(listOf(7, 8L)))
    }

    @Test
    fun `a hundred thousand brackets are refused, not overflowed`() {
        // What a parser that recurses without counting hands an attacker:
        // a stack overflow, which is not an exception anything above
        // catches. This one counts.
        assertThrows(Json.Malformed::class.java) { Json.parse("[".repeat(100_000)) }
        assertThrows(Json.Malformed::class.java) { Json.parse("{\"a\":".repeat(100_000)) }
        var deep: Any? = emptyList<Any?>()
        repeat(200) { deep = listOf(deep) }
        assertThrows(Json.Malformed::class.java) { Json.write(deep) }
    }

    @Test
    fun `what is not JSON is refused`() {
        for (bad in listOf(
            "", "tru", "[1,]", "{\"a\" 1}", "\"abc", "\"\\x\"", "01", "1.", "-", "[1] x", "1e400",
            "{1:2}", "[1 2]", "\"a" + 0x01.toChar() + "b\"", "\"\\u12\"", "nul", "{\"a\":}",
        )) {
            assertThrows(Json.Malformed::class.java, { Json.parse(bad) }, "read: $bad")
        }
    }
}
