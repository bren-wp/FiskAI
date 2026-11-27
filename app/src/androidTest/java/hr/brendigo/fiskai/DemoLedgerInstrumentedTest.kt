package hr.brendigo.fiskai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DemoLedgerInstrumentedTest {
    @Test fun atomicNumberingAndImmutableSnapshot() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="demo-ledger-"+UUID.randomUUID()+".db"
        val db=RecordDatabase(context,name)
        try {
            val ledger=DemoLedger(context,db)
            val register=Register("PP1","1","DEMO blagajna")
            val operator=Operator("OP1","Test","12345678903")
            val setup=FiscalSetup().withPremise(Premise("PP1","Test","Testna adresa")).withRegister(register).withOperator(operator)
            val business=BusinessProfile(name="DEMO",oib="12345678903",address="Test",city="Test",vatRegistered=true)
            val line=Line("Test",BigDecimal.ONE,BigDecimal("10.00"),BigDecimal("25"))
            val draft=Draft(kind="Račun",customer="DEMO",oib="",lines=listOf(line))
            assertEquals(1L,ledger.freeze(draft,business,setup,register,operator))
            assertEquals(1L,ledger.freeze(draft,business,setup,register,operator))
            val second=draft.copy(id=UUID.randomUUID().toString())
            assertEquals(2L,ledger.freeze(second,business,setup,register,operator))
            val rows=ledger.history()
            assertEquals(2,rows.size)
            assertEquals("12.50",ledger.total(rows.first{it.draftId==draft.id}))
            assertFalse(rows.any{it.snapshot.contains("DEMO")})
            val sql=db.writableDatabase
            assertTrue(runCatching{sql.execSQL("UPDATE demo_receipt SET number=9 WHERE draft_id=?",arrayOf(draft.id))}.isFailure)
            assertTrue(runCatching{sql.execSQL("DELETE FROM demo_receipt WHERE draft_id=?",arrayOf(draft.id))}.isFailure)
            assertEquals(2,ledger.history().size)
        }finally{db.close();context.deleteDatabase(name)}
    }
}
