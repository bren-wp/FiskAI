package hr.brendigo.fiskai

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class BusinessReviewTest {
    @Test fun missingFieldsAndUnavailableEvidenceAreExplicit(){
        val p=Product(name="Test",code="T",price=BigDecimal.ONE,vat=BigDecimal("25"),service=false)
        val rows=BusinessReview.findings(BusinessProfile(),emptyList(),listOf(p),FiscalSetup(),null,null,null)
        assertTrue(rows.any{it.destination=="Poslovni subjekt"})
        assertTrue(rows.any{it.title=="Stanje zaliha nije potvrđeno"})
        assertFalse(rows.any{it.title=="Artikli bez zalihe"})
        assertTrue(rows.any{it.title=="Katalog za eRačune"})
    }
    @Test fun businessScopeAndArchive(){
        val owner="12345678903"
        val p=Product(name="Test",code="T",price=BigDecimal.ONE,vat=BigDecimal("25"),service=false,archived=true)
        val rows=BusinessReview.findings(BusinessProfile(oib=owner),emptyList(),listOf(p),FiscalSetup(),
            ReceiptWorkspace(listOf(BankEvidence("other","00000000001","","","2027-01-01",500,"")),emptyList()),
            emptyList(),emptyMap())
        assertFalse(rows.any{it.title=="Nepotvrđeni bankovni priljevi"||it.title=="Katalog za eRačune"||it.title=="Artikli bez zalihe"})
    }
}
