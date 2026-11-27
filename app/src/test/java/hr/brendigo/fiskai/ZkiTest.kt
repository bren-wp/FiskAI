package hr.brendigo.fiskai
import org.junit.Test
import org.junit.Assert.*
import java.math.BigDecimal
import java.security.KeyPairGenerator

class ZkiTest {
    @Test fun canonicalInput(){
        val bytes=Zki.input("12345678903","27.11.2026 10:20:30","1","PP1","2",BigDecimal("10.5"))
        assertEquals("1234567890327.11.2026 10:20:301PP1210.50",bytes.toString(Charsets.UTF_8))
    }
    @Test fun deterministicTestKey(){
        val key=KeyPairGenerator.getInstance("RSA").apply{initialize(2048)}.generateKeyPair().private
        val bytes=Zki.input("12345678903","27.11.2026 10:20:30","1","PP1","2",BigDecimal("10.50"))
        val zki=Zki.compute(key,bytes)
        assertTrue(zki.matches(Regex("[0-9a-f]{32}")))
        assertEquals(zki,Zki.compute(key,bytes))
    }
}
