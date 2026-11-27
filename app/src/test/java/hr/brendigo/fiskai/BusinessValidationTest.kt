package hr.brendigo.fiskai

import org.junit.Assert.*
import org.junit.Test

class BusinessValidationTest {
    @Test fun ibanChecksum() {
        assertTrue(validCroatianIban("HR12 1001 0051 8630 0016 0"))
        assertFalse(validCroatianIban("HR12 1001 0051 8630 0016 1"))
        assertFalse(validCroatianIban("DE89370400440532013000"))
        assertFalse(validCroatianIban(""))
    }
}
