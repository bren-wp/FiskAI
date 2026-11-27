package hr.brendigo.fiskai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class IncomingInvoiceInstrumentedTest {
    private fun xml(amount:String="12.50",id:String="TEST-001") = """
        <Invoice xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2" xmlns:c="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2" xmlns:a="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2">
        <c:ID>$id</c:ID><c:IssueDate>2026-11-27</c:IssueDate><c:DocumentCurrencyCode>EUR</c:DocumentCurrencyCode>
        <a:AccountingSupplierParty><a:Party><a:PartyLegalEntity><c:RegistrationName>Testni dobavljač</c:RegistrationName><c:CompanyID>12345678903</c:CompanyID></a:PartyLegalEntity></a:Party></a:AccountingSupplierParty>
        <a:AccountingCustomerParty><a:Party><a:PartyLegalEntity><c:CompanyID>00000000001</c:CompanyID></a:PartyLegalEntity></a:Party></a:AccountingCustomerParty>
        <a:LegalMonetaryTotal><c:PayableAmount currencyID="EUR">$amount</c:PayableAmount></a:LegalMonetaryTotal><a:InvoiceLine/>
        </Invoice>
    """.trimIndent().toByteArray()
    @Test fun importDeduplicationConflictReviewAndUnsafeXml(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="incoming-"+UUID.randomUUID()+".db"
        val db=RecordDatabase(context,name)
        try{
            val store=IncomingInvoiceStore(context,db)
            assertTrue(store.import(xml(),"00000000001"))
            assertFalse(store.import(xml(),"00000000001"))
            assertTrue(runCatching{store.import(xml("20.00"),"00000000001")}.isFailure)
            assertTrue(runCatching{IncomingUbl.parse(xml(),"12345678903")}.isFailure)
            assertTrue(runCatching{IncomingUbl.parse("<!DOCTYPE Invoice><Invoice/>".toByteArray(),"00000000001")}.isFailure)
            val row=store.read().single()
            assertEquals("12.50",row.amount.toPlainString())
            store.review(row.key,"REVIEW")
            assertEquals("REVIEW",store.read().single().status)
            assertTrue(db.read("fiskai_incoming","invoices")!!.startsWith("gcm1:"))
        }finally{db.close();context.deleteDatabase(name)}
    }
}
