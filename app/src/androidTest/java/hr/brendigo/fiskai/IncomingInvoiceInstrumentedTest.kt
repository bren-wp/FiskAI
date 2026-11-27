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
        val room=hr.brendigo.fiskai.room.FiscalRoomDatabase.open(context,name)
        try{
            val store=IncomingInvoiceStore(context,db,room)
            assertTrue(store.import(xml(),"00000000001"))
            assertFalse(store.import(xml(),"00000000001"))
            assertTrue(runCatching{store.import(xml("20.00"),"00000000001")}.isFailure)
            assertTrue(runCatching{IncomingUbl.parse(xml(),"12345678903")}.isFailure)
            assertTrue(runCatching{IncomingUbl.parse("<!DOCTYPE Invoice><Invoice/>".toByteArray(),"00000000001")}.isFailure)
            val row=store.read().single()
            assertEquals("12.50",row.amount.toPlainString())
            store.review(row.key,"REVIEW")
            assertEquals("REVIEW",store.read().single().status)
            assertTrue(room.incoming().find(row.key).payload.startsWith("gcm1:"))
            assertNull(db.read("fiskai_incoming","invoices"))
        }finally{room.close();db.close();context.deleteDatabase(name)}
    }

    @Test fun legacyCollectionMigratesAndBackupRestoresNormalizedRows(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val sourceName="incoming-source-"+UUID.randomUUID()+".db"
        val targetName="incoming-target-"+UUID.randomUUID()+".db"
        val source=RecordDatabase(context,sourceName)
        val target=RecordDatabase(context,targetName)
        val sr=hr.brendigo.fiskai.room.FiscalRoomDatabase.open(context,sourceName)
        val tr=hr.brendigo.fiskai.room.FiscalRoomDatabase.open(context,targetName)
        try{
            val row=IncomingUbl.parse(xml(),"00000000001")
            assertTrue(SecureRecords(context,"fiskai_incoming",source).write("invoices",IncomingInvoiceStore.encode(listOf(row)).toString()))
            val store=IncomingInvoiceStore(context,source,sr)
            assertEquals(row.key,store.read().single().key)
            store.review(row.key,"REVIEW")
            val backup=SecureRecords.exportWorkspace(context,source)
            SecureRecords.restoreWorkspace(context,backup,target)
            val restored=IncomingInvoiceStore(context,target,tr).read().single()
            assertEquals("REVIEW",restored.status)
            assertEquals(row.digest,restored.digest)
            assertEquals(row.xml,restored.xml)
            assertTrue(runCatching{SecureRecords.restoreWorkspace(context,backup,target)}.isFailure)
        }finally{sr.close();tr.close();source.close();target.close();context.deleteDatabase(sourceName);context.deleteDatabase(targetName)}
    }
}
