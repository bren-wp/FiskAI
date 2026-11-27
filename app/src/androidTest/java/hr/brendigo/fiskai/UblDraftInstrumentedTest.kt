package hr.brendigo.fiskai

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(AndroidJUnit4::class)
class UblDraftInstrumentedTest {
    @Test fun draftContainsKpdAndTotals(){
        val b=BusinessProfile(name="Test & partner",oib="12345678903",address="Test 1",city="Test",vatRegistered=true)
        val line=Line("Usluga & test",BigDecimal.ONE,BigDecimal("10.00"),BigDecimal("25"),"62.20.20","HUR")
        val d=Draft(kind="Račun",customer="Test",oib="12345678903",lines=listOf(line))
        val bytes=UblDraft.build(d,b)
        val doc=DocumentBuilderFactory.newInstance().apply{isNamespaceAware=true}.newDocumentBuilder().parse(bytes.inputStream())
        assertEquals(UblDraft.INV,doc.documentElement.namespaceURI)
        val code=doc.getElementsByTagNameNS(UblDraft.CBC,"ItemClassificationCode").item(0) as org.w3c.dom.Element
        assertEquals("CG",code.getAttribute("listID"))
        assertEquals("62.20.20",code.textContent)
        assertEquals("12.50",doc.getElementsByTagNameNS(UblDraft.CBC,"PayableAmount").item(0).textContent)
        assertFalse(UblDraft.canExport(d.copy(lines=listOf(line.copy(unitCode=""))),b))
    }
}
