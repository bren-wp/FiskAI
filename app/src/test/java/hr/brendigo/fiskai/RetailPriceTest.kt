package hr.brendigo.fiskai
import org.junit.Assert.*
import org.junit.Test
class RetailPriceTest {
    @Test fun kpdShape(){assertEquals("62.01.11",normalizeKpd("620111"));assertNull(normalizeKpd("62.01"));assertNull(normalizeKpd("abc"))}
    @Test fun baseRequiresEvidence(){assertTrue(runCatching{parseRetail("12","10","2026-09-10","",false)}.isFailure)}
    @Test fun validDate(){assertTrue(runCatching{parseRetail("12","10","2026-02-30","Dokaz",false)}.isFailure)}
    @Test fun separatePrices(){val p=parseRetail("12,50","10","2026-09-10","Cjenik",true)!!;assertEquals("12.50",p.gross.toPlainString());assertEquals("10.00",p.base!!.toPlainString());assertTrue(p.special)}
}
