package hr.brendigo.fiskai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RecordMigrationInstrumentedTest {
    @Test fun v4UpgradeRetainsCiphertextAndBlocksReplace() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="migration-"+UUID.randomUUID()+".db"
        var db=RecordDatabase(context,name)
        val ns="migration_"+UUID.randomUUID()
        try {
            val record=SecureRecords(context,ns,db)
            assertTrue(record.write("drafts","[1]"))
            val encrypted=db.read(ns,"drafts")
            val product=Product(name="Test",code="T",price=BigDecimal.ONE,vat=BigDecimal("25"),service=false)
            val movement=UUID.randomUUID().toString()
            Inventory(context,db).post(movement,product,BigDecimal.ONE,true,"Ulaz")
            val register=Register("PP1","1","DEMO blagajna")
            val operator=Operator("OP1","Test","12345678903")
            val setup=FiscalSetup().withPremise(Premise("PP1","Test","Testna adresa")).withRegister(register).withOperator(operator)
            val business=BusinessProfile(name="DEMO",oib="12345678903",address="Test",city="Test",vatRegistered=true)
            val draft=Draft(kind="Račun",customer="DEMO",oib="",lines=listOf(Line("Test",BigDecimal.ONE,BigDecimal("10.00"),BigDecimal("25"))))
            assertEquals(1L,DemoLedger(context,db).freeze(draft,business,setup,register,operator))
            val old=db.writableDatabase
            // Construct a v4-equivalent schema without the two v5 triggers.
            old.execSQL("DROP TRIGGER demo_receipt_no_replace")
            old.execSQL("DROP TRIGGER stock_event_no_replace")
            old.version=4
            db.close()
            db=RecordDatabase(context,name)
            val sql=db.writableDatabase
            assertEquals(5,sql.version)
            assertEquals(encrypted,db.read(ns,"drafts"))
            assertEquals("[1]",SecureRecords(context,ns,db).getString("drafts","[]"))
            assertEquals(10000L,Inventory(context,db).balance()[product.id]!!)
            assertTrue(runCatching { sql.execSQL("INSERT OR REPLACE INTO stock_event SELECT * FROM stock_event WHERE id=?",arrayOf(movement)) }.isFailure)
            assertTrue(runCatching { sql.execSQL("INSERT OR REPLACE INTO demo_receipt SELECT * FROM demo_receipt WHERE draft_id=?",arrayOf(draft.id)) }.isFailure)
            assertEquals(1,DemoLedger(context,db).history().size)
            assertEquals(1,Inventory(context,db).history().size)
            assertEquals(2L,DemoLedger(context,db).freeze(draft.copy(id=UUID.randomUUID().toString()),business,setup,register,operator))
        } finally { db.close();context.deleteDatabase(name);context.getSharedPreferences(ns,0).edit().clear().commit() }
    }
}
