package hr.brendigo.fiskai

import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class InvoiceMathTest {
    @Test fun vatCalculation() {
        val line = Line("Usluga", BigDecimal("2"), BigDecimal("19.99"), BigDecimal("25"))
        assertEquals(BigDecimal("39.98"), line.net)
        assertEquals(BigDecimal("10.00"), line.vat)
        assertEquals(BigDecimal("49.98"), line.total)
    }
    @Test fun oibChecksum() {
        assertTrue(validOib("12345678903"))
        assertFalse(validOib("12345678901"))
        assertFalse(validOib("abc"))
    }
    @Test fun decimalComma() { assertEquals(BigDecimal("12.50"), parseDecimal("12,50")) }
    @Test(expected = IllegalArgumentException::class) fun negativeQuantity() { Line("Test", BigDecimal("-1"), BigDecimal.ONE, BigDecimal.ZERO) }
}
