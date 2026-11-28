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
            old.execSQL("DROP TABLE legal_decision_ledger")
            old.execSQL("DROP TABLE demo_payment_preparation")
            old.execSQL("DROP TABLE fiscal_outbox")
            old.execSQL("DROP TABLE fiscal_document")
            old.execSQL("DROP TABLE fiscal_cutover")
            old.execSQL("DROP TABLE incoming_invoice")
            old.execSQL("DROP TABLE incoming_cutover")
            old.execSQL("DROP TABLE receipt_event")
            old.execSQL("DROP TABLE bank_transaction")
            old.version=4
            db.close()
            db=RecordDatabase(context,name)
            val sql=db.writableDatabase
            assertEquals(11,sql.version)
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

    @Test fun v8UpgradeToV10RetainsConfirmedReceiptAndImmutableDocument() {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val name="migration-v8-"+UUID.randomUUID()+".db"
        var db=RecordDatabase(ctx,name)
        var room=hr.brendigo.fiskai.room.FiscalRoomDatabase.open(ctx,name)
        try {
            val business=BusinessProfile(name="DEMO",oib="12345678903",address="Test",city="Test",vatRegistered=true)
            val register=Register("PP1","1","DEMO blagajna")
            val operator=Operator("OP1","Test","12345678903")
            val setup=FiscalSetup().withPremise(Premise("PP1","Test","Testna adresa")).withRegister(register).withOperator(operator)
            val draft=Draft(kind="Račun",customer="DEMO",oib="",lines=listOf(Line("Test",BigDecimal.ONE,BigDecimal("10.00"),BigDecimal("25"))))
            val ledger=RoomDemoLedger(ctx,db,room,null)
            assertEquals(1L,ledger.freeze(draft,business,setup,register,operator))
            val doc=ledger.history().single()
            val receiptStore=ReceiptStore(ctx,db,room)
            assertTrue(receiptStore.cash(business,"TEST-8","2026-11-27","12.50","Sintetički dokaz"))
            val receipt=receiptStore.read().receipts.single()
            val cipher=room.receipts().receiptById(receipt.id).payload
            room.close()
            val sql=db.writableDatabase
            sql.execSQL("DROP TABLE legal_decision_ledger")
            sql.execSQL("DROP TABLE demo_payment_preparation")
            sql.version=8
            db.close()
            db=RecordDatabase(ctx,name)
            assertEquals(11,db.writableDatabase.version)
            room=hr.brendigo.fiskai.room.FiscalRoomDatabase.open(ctx,name)
            assertEquals(doc.snapshot,room.queue().document(doc.id).payload)
            assertEquals(cipher,room.receipts().receiptById(receipt.id).payload)
            assertEquals(receipt,ReceiptStore(ctx,db,room).read().receipts.single())
            assertEquals("QUEUED",room.queue().entry(doc.id).state)
            assertTrue(db.writableDatabase.rawQuery("SELECT 1 FROM demo_payment_preparation",null).use{!it.moveToFirst()})
        } finally {room.close();db.close();ctx.deleteDatabase(name)}
    }

    @Test fun v10ToV11ThroughRoomRetainsEncryptedRecords() {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val name="migration-v10-"+UUID.randomUUID()+".db"
        val ns="migration_"+UUID.randomUUID()
        var db=RecordDatabase(ctx,name)
        try {
            assertTrue(SecureRecords(ctx,ns,db).write("drafts","[1]"))
            val cipher=db.read(ns,"drafts")
            val sql=db.writableDatabase
            sql.execSQL("DROP TABLE legal_decision_ledger")
            sql.version=10
            db.close()
            val room=hr.brendigo.fiskai.room.FiscalRoomDatabase.open(ctx,name)
            try {
                assertEquals(11,room.openHelper.writableDatabase.version)
                db=RecordDatabase(ctx,name)
                assertEquals(cipher,db.read(ns,"drafts"))
                assertEquals("[1]",SecureRecords(ctx,ns,db).getString("drafts","[]"))
                assertTrue(db.writableDatabase.rawQuery("SELECT 1 FROM legal_decision_ledger",null).use{!it.moveToFirst()})
            }finally{room.close()}
        }finally{db.close();ctx.deleteDatabase(name);ctx.getSharedPreferences(ns,0).edit().clear().commit()}
    }
}
