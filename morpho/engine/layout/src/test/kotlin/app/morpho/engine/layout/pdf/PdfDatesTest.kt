package app.morpho.engine.layout.pdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PdfDatesTest {

    @Test
    fun `a date written in full comes back as the instant it is`() {
        assertEquals("2026-09-03T09:15:00+01:00", PdfDates.isoOf("D:20260903091500+01'00'"))
        assertEquals("2026-09-03T09:15:00Z", PdfDates.isoOf("D:20260903091500Z"))
        assertEquals("2026-09-03T09:15:00-05:30", PdfDates.isoOf("D:20260903091500-05'30'"))
    }

    @Test
    fun `a date that stops early is still a date`() {
        // Every part after the year is optional, and a producer that
        // writes only some of them has still said something true.
        assertEquals("2026-01-01T00:00:00", PdfDates.isoOf("D:2026"))
        assertEquals("2026-09-01T00:00:00", PdfDates.isoOf("D:202609"))
        assertEquals("2026-09-03T00:00:00", PdfDates.isoOf("D:20260903"))
        assertEquals("2026-09-03T09:00:00", PdfDates.isoOf("D:2026090309"))
    }

    @Test
    fun `a date with no zone is written with none rather than given one`() {
        assertEquals("2026-09-03T09:15:00", PdfDates.isoOf("D:20260903091500"))
    }

    @Test
    fun `the marker in front is not required`() {
        assertEquals("2026-09-03T09:15:00Z", PdfDates.isoOf("20260903091500Z"))
    }

    @Test
    fun `a month or a day of nothing is the first of it`() {
        assertEquals("2026-01-01T00:00:00", PdfDates.isoOf("D:20260000"))
    }

    @Test
    fun `what is not a date says nothing`() {
        assertNull(PdfDates.isoOf(null))
        assertNull(PdfDates.isoOf(""))
        assertNull(PdfDates.isoOf("   "))
        assertNull(PdfDates.isoOf("D:"))
        assertNull(PdfDates.isoOf("yesterday"))
        assertNull(PdfDates.isoOf("D:202"))
    }

    @Test
    fun `a zone the file is wrong about is left off rather than carried`() {
        assertEquals("2026-09-03T09:15:00", PdfDates.isoOf("D:20260903091500+99'00'"))
        assertEquals("2026-09-03T09:15:00", PdfDates.isoOf("D:20260903091500+0'"))
    }
}
