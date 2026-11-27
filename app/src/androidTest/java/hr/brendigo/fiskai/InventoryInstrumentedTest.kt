package hr.brendigo.fiskai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class InventoryInstrumentedTest {
    @Test fun atomicStockAndIdempotency(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="stock-test-"+UUID.randomUUID()+".db"
        val db=RecordDatabase(context,name)
        try{
            val store=Inventory(context,db)
            val p=Product(name="Test",code="T1",price=BigDecimal.ONE,vat=BigDecimal("25"),service=false)
            val id=UUID.randomUUID().toString()
            assertEquals(25000L,store.post(id,p,BigDecimal("2.5"),true,"Ulaz"))
            assertEquals(25000L,store.post(id,p,BigDecimal("2.5"),true,"Ulaz"))
            assertEquals(15000L,store.post(UUID.randomUUID().toString(),p,BigDecimal.ONE,false,"Izlaz"))
            assertTrue(runCatching{store.post(UUID.randomUUID().toString(),p,BigDecimal("2"),false,"Previše")}.isFailure)
            assertEquals(15000L,store.balance()[p.id]!!)
            assertEquals(2,store.history().size)
            assertTrue(runCatching{db.writableDatabase.execSQL("DELETE FROM stock_event")}.isFailure)
        }finally{db.close();context.deleteDatabase(name)}
    }
}
