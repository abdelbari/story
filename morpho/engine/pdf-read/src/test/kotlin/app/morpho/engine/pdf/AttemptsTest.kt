package app.morpho.engine.pdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * A reader guards its optional passes so that one failing costs a document
 * its pictures rather than the reader its life. Running out of room is not
 * that kind of failure: a document with pages quietly missing from it,
 * handed back as though it were whole, is worse than being told it was too
 * large to convert.
 */
class AttemptsTest {

    @Test
    fun `what works is what comes back`() {
        assertEquals(7, attempt { 7 })
    }

    @Test
    fun `a pass that fails costs only itself`() {
        assertNull(attempt { error("the page would not parse") })
        assertNull(attempt<Int> { throw IllegalStateException() })
    }

    @Test
    fun `running out of room is not shrugged off`() {
        assertThrows<OutOfMemoryError> { attempt { throw OutOfMemoryError("Java heap space") } }
    }

    @Test
    fun `nor is anything else the machine itself raises`() {
        assertThrows<StackOverflowError> { attempt { throw StackOverflowError() } }
    }
}
