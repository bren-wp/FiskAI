package hr.brendigo.fiskai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hr.brendigo.fiskai.room.FiscalRoomDatabase
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReceiptInstrumentedTest {
    private val business=BusinessProfile(name="Test",oib="12345678903",iban="HR1210010051863000160")
    private val date="2026-11-27"
    private fun csv(vararg rows:String)=("id;datum;iban;iznos;valuta;opis\n"+rows.joinToString("\n")+"\n").toByteArray()
    private fun line(id:String,amount:String,desc:String="Uplata")=listOf(id,date,business.iban,amount,"EUR",desc).joinToString(";")

    @Test fun importConfirmationImmutabilityAndPortableRestore(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val sourceName="receipt-source-"+UUID.randomUUID()+".db"
        val targetName="receipt-target-"+UUID.randomUUID()+".db"
        val source=RecordDatabase(context,sourceName)
        val target=RecordDatabase(context,targetName)
        val sr=FiscalRoomDatabase.open(context,sourceName)
        val tr=FiscalRoomDatabase.open(context,targetName)
        try{
            val store=ReceiptStore(context,source,sr)
            val data=csv(line("B1","12,50"),line("B2","-5.00","Odljev"))
            assertEquals(2,store.importCsv(data,business))
            assertEquals(0,store.importCsv(data,business))
            assertTrue(store.read().receipts.isEmpty())
            assertTrue(runCatching{store.importCsv(csv(line("B3","1"),line("B1","99")),business)}.isFailure)
            assertEquals(2,store.read().bank.size)
            val positive=store.read().bank.single{it.cents>0}
            val negative=store.read().bank.single{it.cents<0}
            assertTrue(runCatching{store.confirmBank(business,negative.id,"Odljev")}.isFailure)
            assertTrue(runCatching{store.confirmBank(business.copy(oib="00000000001"),positive.id,"Drugi subjekt")}.isFailure)
            assertTrue(store.confirmBank(business,positive.id,"Dokaz 1"))
            assertFalse(store.confirmBank(business,positive.id,"Dokaz 1"))
            assertTrue(runCatching{store.confirmBank(business,positive.id,"Drugi dokaz")}.isFailure)
            assertTrue(store.cash(business,"G1",date,"2,50","Gotovinski dokaz"))
            assertFalse(store.cash(business,"G1",date,"2.50","Gotovinski dokaz"))
            assertTrue(runCatching{store.cash(business,"G1",date,"3","Gotovinski dokaz")}.isFailure)
            assertEquals(1500L,store.read().receipts.sumOf{it.cents})
            assertTrue(sr.receipts().bank().all{it.payload.startsWith("gcm1:")})
            val sql=source.writableDatabase
            assertTrue(runCatching{sql.execSQL("UPDATE receipt_event SET payload='tampered'")}.isFailure)
            assertTrue(runCatching{sql.execSQL("DELETE FROM bank_transaction")}.isFailure)
            assertTrue(runCatching{sql.execSQL("INSERT OR REPLACE INTO receipt_event SELECT * FROM receipt_event")}.isFailure)
            val exported=SecureRecords.exportWorkspace(context,source)
            val bad=JSONObject(exported)
            val records=bad.getJSONObject("records")
            records.put(ReceiptStore.BANK,org.json.JSONArray())
            assertTrue(runCatching{SecureRecords.restoreWorkspace(context,bad.toString(),target)}.isFailure)
            SecureRecords.restoreWorkspace(context,exported,target)
            val restored=ReceiptStore(context,target,tr)
            assertEquals(store.read(),restored.read())
            assertEquals(0,restored.importCsv(data,business))
            assertTrue(runCatching{SecureRecords.restoreWorkspace(context,exported,target)}.isFailure)
        }finally{
            sr.close();tr.close();source.close();target.close()
            context.deleteDatabase(sourceName);context.deleteDatabase(targetName)
        }
    }

    @Test fun strictCsvValidation(){
        assertTrue(runCatching{ReceiptRules.csv(csv(line("B1","1"),line("B1","1")),business.oib,business.iban)}.isFailure)
        assertTrue(runCatching{ReceiptRules.csv(csv(line("B1","1.001")),business.oib,business.iban)}.isFailure)
        assertTrue(runCatching{ReceiptRules.csv(csv(line("B1","1").replace("EUR","USD")),business.oib,business.iban)}.isFailure)
        assertTrue(runCatching{ReceiptRules.csv(csv(line("B1","1").replace(date,"2026-02-30")),business.oib,business.iban)}.isFailure)
        assertTrue(runCatching{ReceiptRules.csv(csv(line("B1","1").replace(business.iban,"HR0000000000000000000")),business.oib,business.iban)}.isFailure)
        assertTrue(runCatching{ReceiptRules.csv(csv(line("B1","1","a;b")),business.oib,business.iban)}.isFailure)
        assertEquals(1234L,ReceiptRules.cents("12,34"))
        assertEquals(-1234L,ReceiptRules.cents("-12.34"))
    }
}
