package hr.brendigo.fiskai

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WorkspaceBackupInstrumentedTest {
    private val base get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val databases=mutableListOf<Pair<String,RecordDatabase>>()
    private val preferences=mutableSetOf<String>()
    private lateinit var scope:String

    @Before fun init() { scope="backup_test_"+UUID.randomUUID() }
    private fun workspace(label:String):Pair<Context,RecordDatabase> {
        val ctx=object:ContextWrapper(base) {
            override fun getSharedPreferences(name:String,mode:Int):SharedPreferences {
                val isolated="$scope/$label/$name".replace('/','_')
                preferences.add(isolated)
                return baseContext.getSharedPreferences(isolated,mode)
            }
        }
        val name="$scope-$label.db"
        val db=RecordDatabase(base,name)
        databases.add(name to db)
        return ctx to db
    }
    @After fun cleanup() {
        for((name,db) in databases) { db.close();base.deleteDatabase(name) }
        preferences.forEach { base.getSharedPreferences(it,Context.MODE_PRIVATE).edit().clear().commit() }
    }
    @Test fun exportRestoreRoundTripAndNoOverwrite() {
        val (source,sourceDb)=workspace("source")
        val (target,targetDb)=workspace("target")
        val original="{\"name\":\"Testni subjekt\",\"oib\":\"\",\"address\":\"\",\"city\":\"Rijeka\",\"iban\":\"\",\"vatRegistered\":false}"
        assertTrue(SecureRecords(source,"fiskai_business",sourceDb).write("profile",original))
        assertTrue(SecureRecords(source,"fiskai_test_certificate",sourceDb).write("credential","{\"pkcs12\":\"test-only\"}"))
        val exported=SecureRecords.exportWorkspace(source,sourceDb)
        assertFalse(exported.contains("test-only"))
        val password="Sigurna testna lozinka".toCharArray()
        val sealed=PortableBackup.encrypt(exported.toByteArray(),password)
        val restored=PortableBackup.decrypt(sealed,password).toString(Charsets.UTF_8)
        SecureRecords.restoreWorkspace(target,restored,targetDb)
        val records=SecureRecords(target,"fiskai_business",targetDb)
        assertEquals("Testni subjekt",JSONObject(records.getString("profile","{}")).getString("name"))
        assertTrue(targetDb.read("fiskai_business","profile")!!.startsWith("gcm1:"))
        assertNull(targetDb.read("fiskai_test_certificate","credential"))
        assertTrue(runCatching { SecureRecords.restoreWorkspace(target,restored,targetDb) }.isFailure)
        assertEquals("Testni subjekt",JSONObject(records.getString("profile","{}")).getString("name"))
    }
    @Test fun rejectsWrongModeAndInvalidDomainBeforeWrite() {
        val (source,sourceDb)=workspace("source")
        val (target,targetDb)=workspace("target")
        val exported=JSONObject(SecureRecords.exportWorkspace(source,sourceDb))
        exported.put("demo",!BuildConfig.DEMO_MODE)
        assertTrue(runCatching { SecureRecords.restoreWorkspace(target,exported.toString(),targetDb) }.isFailure)
        assertNull(targetDb.read("fiskai_business","profile"))
        exported.put("demo",BuildConfig.DEMO_MODE)
        exported.getJSONObject("records").put("fiskai_drafts/drafts",org.json.JSONArray().put(JSONObject().put("id","invalid")))
        assertTrue(runCatching { SecureRecords.restoreWorkspace(target,exported.toString(),targetDb) }.isFailure)
        assertNull(targetDb.read("fiskai_business","profile"))
    }
    @Test fun journalRoundTripPreservesCountersStockAndInterruptedCis() {
        val (source,sourceDb)=workspace("source")
        val (target,targetDb)=workspace("target")
        val register=Register("PP1","1","DEMO blagajna")
        val operator=Operator("OP1","Test","12345678903")
        val setup=FiscalSetup().withPremise(Premise("PP1","Test","Testna adresa")).withRegister(register).withOperator(operator)
        val business=BusinessProfile(name="DEMO",oib="12345678903",address="Test",city="Test",vatRegistered=true)
        val line=Line("Test",java.math.BigDecimal.ONE,java.math.BigDecimal("10.00"),java.math.BigDecimal("25"))
        val draft=Draft(kind="Račun",customer="DEMO",oib="",lines=listOf(line))
        assertEquals(1L,DemoLedger(source,sourceDb).freeze(draft,business,setup,register,operator))
        val product=Product(name="Test",code="T1",price=java.math.BigDecimal.ONE,vat=java.math.BigDecimal("25"),service=false)
        val stock=Inventory(source,sourceDb)
        stock.post(UUID.randomUUID().toString(),product,java.math.BigDecimal("2.5"),true,"Ulaz")
        stock.post(UUID.randomUUID().toString(),product,java.math.BigDecimal.ONE,false,"Izlaz")
        val cis=CisJournal(source,sourceDb)
        val attempt=cis.enqueue()
        assertTrue(cis.claim(attempt))
        val exported=SecureRecords.exportWorkspace(source,sourceDb)
        val bad=JSONObject(exported)
        bad.getJSONObject("journals").getJSONArray("demo_sequence").getJSONObject(0).put("last_no",2)
        assertTrue(runCatching { SecureRecords.restoreWorkspace(target,bad.toString(),targetDb) }.isFailure)
        assertTrue(DemoLedger(target,targetDb).history().isEmpty())
        val badStock=JSONObject(exported)
        badStock.getJSONObject("journals").getJSONArray("stock_balance").getJSONObject(0).put("quantity",999)
        assertTrue(runCatching { SecureRecords.restoreWorkspace(target,badStock.toString(),targetDb) }.isFailure)
        assertTrue(Inventory(target,targetDb).balance().isEmpty())
        SecureRecords.restoreWorkspace(target,exported,targetDb)
        val ledger=DemoLedger(target,targetDb)
        assertEquals("12.50",ledger.total(ledger.history().single()))
        assertEquals(1L,ledger.freeze(draft,business,setup,register,operator))
        assertEquals(2L,ledger.freeze(draft.copy(id=UUID.randomUUID().toString()),business,setup,register,operator))
        assertEquals(15000L,Inventory(target,targetDb).balance()[product.id]!!)
        assertEquals(2,Inventory(target,targetDb).history().size)
        assertEquals("UNCERTAIN",CisJournal(target,targetDb).history().single().state)
        assertFalse(CisJournal(target,targetDb).claim(attempt))
        assertTrue(runCatching { targetDb.writableDatabase.execSQL("DELETE FROM demo_receipt") }.isFailure)
        assertTrue(runCatching { targetDb.writableDatabase.execSQL("DELETE FROM stock_event") }.isFailure)
    }

    @Test fun legacyV1StillRestoresEmptyWorkspace() {
        val (source,sourceDb)=workspace("source")
        val (target,targetDb)=workspace("target")
        val exported=JSONObject(SecureRecords.exportWorkspace(source,sourceDb))
        exported.put("version",1)
        exported.remove("journals")
        SecureRecords.restoreWorkspace(target,exported.toString(),targetDb)
        assertTrue(DemoLedger(target,targetDb).history().isEmpty())
    }
    @Test fun unifiedRoomCutoverAndV3Backup() {
        val (source,sourceDb)=workspace("unified_source")
        val (target,targetDb)=workspace("unified_target")
        val sourceRoom=hr.brendigo.fiskai.room.FiscalRoomDatabase.open(source,sourceDb.databaseName)
        val targetRoom=hr.brendigo.fiskai.room.FiscalRoomDatabase.open(target,targetDb.databaseName)
        val register=Register("PP1","1","DEMO blagajna")
        val operator=Operator("OP1","Test","12345678903")
        val setup=FiscalSetup().withPremise(Premise("PP1","Test","Testna adresa")).withRegister(register).withOperator(operator)
        val business=BusinessProfile(name="DEMO",oib="12345678903",address="Test",city="Test",vatRegistered=true)
        fun draft()=Draft(kind="Račun",customer="DEMO",oib="",lines=listOf(Line("Test",java.math.BigDecimal.ONE,java.math.BigDecimal("10.00"),java.math.BigDecimal("25"))))
        val old=draft()
        val second=draft()
        try {
            assertEquals(1L,DemoLedger(source,sourceDb).freeze(old,business,setup,register,operator))
            val ledger=RoomDemoLedger(source,sourceDb,sourceRoom,null)
            assertEquals(1,ledger.history().size)
            assertEquals("ARCHIVED",ledger.status(ledger.history().single().id))
            assertTrue(runCatching{DemoLedger(source,sourceDb).freeze(draft(),business,setup,register,operator)}.isFailure)
            assertEquals(2L,ledger.freeze(second,business,setup,register,operator))
            assertEquals(2L,ledger.freeze(second,business,setup,register,operator))
            val row=ledger.history().first{it.draftId==second.id}
            val token=UUID.randomUUID().toString()
            assertTrue(sourceRoom.queue().claim(row.id,token,System.currentTimeMillis()))
            val exported=SecureRecords.exportWorkspace(source,sourceDb)
            assertEquals(3,JSONObject(exported).getInt("version"))
            SecureRecords.restoreWorkspace(target,exported,targetDb)
            val restored=RoomDemoLedger(target,targetDb,targetRoom,null)
            assertEquals(2,restored.history().size)
            assertEquals("UNCERTAIN",restored.status(row.id))
            assertEquals("12.50",restored.total(restored.history().first{it.id==row.id}))
            assertEquals(3L,restored.freeze(draft(),business,setup,register,operator))
            assertTrue(runCatching{SecureRecords.restoreWorkspace(target,exported,targetDb)}.isFailure)
        } finally {sourceRoom.close();targetRoom.close()}
    }

}
