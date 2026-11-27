package hr.brendigo.fiskai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hr.brendigo.fiskai.room.FiscalRoomDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FiscalRoomInstrumentedTest {
    @Test fun durableQueueAndImmutableDocument() {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val name="room-test-"+UUID.randomUUID()+".db"
        val legacyName="room-legacy-"+UUID.randomUUID()+".db"
        val legacy=RecordDatabase(ctx,legacyName)
        var room=FiscalRoomDatabase.open(ctx,name)
        try {
            val register=Register("PP1","1","DEMO blagajna")
            val operator=Operator("OP1","Test","12345678903")
            val setup=FiscalSetup().withPremise(Premise("PP1","Test","Testna adresa")).withRegister(register).withOperator(operator)
            val business=BusinessProfile(name="DEMO",oib="12345678903",address="Test",city="Test",vatRegistered=true)
            val draft=Draft(kind="Račun",customer="DEMO",oib="",lines=listOf(Line("Test",BigDecimal.ONE,BigDecimal("10.00"),BigDecimal("25"))))
            val ledger=DemoLedger(ctx,legacy)
            assertEquals(1L,ledger.freeze(draft,business,setup,register,operator))
            val id=ledger.history().single().id
            val outbox=FiscalOutbox(ctx,legacy,room)
            assertEquals("QUEUED",outbox.prepare(id))
            assertEquals("QUEUED",outbox.prepare(id))
            val dao=room.queue()
            assertTrue(dao.document(id).payload.startsWith("gcm1:"))
            assertFalse(dao.document(id).payload.contains("DEMO"))
            val token=UUID.randomUUID().toString()
            assertTrue(dao.claim(id,token,1L))
            assertFalse(dao.claim(id,UUID.randomUUID().toString(),2L))
            assertFalse(dao.markUncertain(id,UUID.randomUUID().toString(),"TRANSPORT_ERROR",3L))
            assertTrue(runCatching{room.openHelper.writableDatabase.execSQL("UPDATE fiscal_outbox SET state='UNCERTAIN', reason=NULL")}.isFailure)
            room.close()
            room=FiscalRoomDatabase.open(ctx,name)
            assertEquals(1,room.queue().recoverInterrupted(4L))
            assertEquals("UNCERTAIN",FiscalOutbox(ctx,legacy,room).prepare(id))
            assertFalse(room.queue().claim(id,token,5L))
            val sql=room.openHelper.writableDatabase
            assertTrue(runCatching{sql.execSQL("UPDATE fiscal_document SET number=9")}.isFailure)
            assertTrue(runCatching{sql.execSQL("INSERT OR REPLACE INTO fiscal_document SELECT * FROM fiscal_document")}.isFailure)
            assertTrue(runCatching{sql.execSQL("UPDATE fiscal_outbox SET state='QUEUED'")}.isFailure)
            assertTrue(runCatching{sql.execSQL("DELETE FROM fiscal_outbox")}.isFailure)
        }finally{room.close();legacy.close();ctx.deleteDatabase(name);ctx.deleteDatabase(legacyName)}
    }
}
