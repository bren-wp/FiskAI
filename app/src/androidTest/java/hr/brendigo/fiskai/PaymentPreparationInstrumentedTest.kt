package hr.brendigo.fiskai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hr.brendigo.fiskai.room.FiscalDocument
import hr.brendigo.fiskai.room.FiscalRoomDatabase
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PaymentPreparationInstrumentedTest {
    @Test fun explicitDemoPaymentPreparationAndBackup() {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val sourceName="payment-source-"+UUID.randomUUID()+".db"
        val targetName="payment-target-"+UUID.randomUUID()+".db"
        val legacyName="payment-v4-"+UUID.randomUUID()+".db"
        val legacy=RecordDatabase(ctx,legacyName)
        val legacyRoom=FiscalRoomDatabase.open(ctx,legacyName)
        val source=RecordDatabase(ctx,sourceName)
        val target=RecordDatabase(ctx,targetName)
        val room=FiscalRoomDatabase.open(ctx,sourceName)
        val targetRoom=FiscalRoomDatabase.open(ctx,targetName)
        try {
            if(!BuildConfig.DEMO_MODE) {
                assertTrue(runCatching{PaymentPreparationStore.prepare(ctx,source,"invalid","invalid")}.isFailure)
                return
            }
            FiscalCutover.ensure(ctx,source,null)
            val business=BusinessProfile(name="DEMO",oib="12345678903",address="Test",city="Test",vatRegistered=true)
            val register=Register("PP1","1","DEMO blagajna")
            val operator=Operator("OP1","Test","12345678903")
            val secure=SecureRecords(ctx,"fiskai_demo_receipts",source)
            val date="2026-11-27"
            val now=java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.ROOT).apply{
                timeZone=java.util.TimeZone.getTimeZone("Europe/Zagreb")
            }.parse(date)!!.time
            fun issue():String {
                val draft=Draft(kind="Račun",customer="DEMO",oib="",lines=listOf(Line("Test",BigDecimal.ONE,BigDecimal("10.00"),BigDecimal("25"))))
                return room.queue().issueLocal(draft.id,2026,"PP1","1",0,now){number->
                    val id=UUID.randomUUID().toString()
                    val plain=demoSnapshot(draft,business,register,operator,now).put("number",number).put("year",2026).toString()
                    FiscalDocument().apply {
                        this.id=id;draftId=draft.id;year=2026;premise="PP1";registerNo="1"
                        this.number=number;created=now;payload=secure.sealSnapshot(id,plain);digest=FiscalCutover.digest(plain)
                    }
                }.id
            }
            val doc=issue()
            val second=issue()
            val receipts=ReceiptStore(ctx,source,room)
            assertTrue(receipts.cash(business,"TEST-1",date,"12.50","Sintetički dokaz"))
            assertTrue(receipts.cash(business,"TEST-2",date,"1.00","Djelomična naplata"))
            val full=receipts.read().receipts.single{it.reference=="TEST-1"}
            val mismatch=receipts.read().receipts.single{it.reference=="TEST-2"}
            assertEquals(setOf(doc,second),receipts.demoPaymentSelection(full.id).choices.map{it.id}.toSet())
            assertEquals(setOf(doc,second),receipts.demoPaymentSelection(mismatch.id).choices.map{it.id}.toSet())
            val event=receipts.prepareDemoPayment(full.id,doc)
            val linked=receipts.demoPaymentSelection(full.id)
            assertTrue(linked.linked)
            assertEquals(doc,linked.choices.single().id)
            assertEquals(event,receipts.prepareDemoPayment(full.id,doc))
            assertTrue(runCatching{receipts.prepareDemoPayment(full.id,second)}.isFailure)
            assertTrue(runCatching{source.writableDatabase.execSQL("UPDATE demo_payment_preparation SET payload='tampered'")}.isFailure)
            assertTrue(runCatching{source.writableDatabase.execSQL("INSERT OR REPLACE INTO demo_payment_preparation SELECT * FROM demo_payment_preparation")}.isFailure)
            val legacyBackup=JSONObject(SecureRecords.exportWorkspace(ctx,source)).put("version",4)
            SecureRecords.restoreWorkspace(ctx,legacyBackup.toString(),legacy)
            assertEquals(event,ReceiptStore(ctx,legacy,legacyRoom).prepareDemoPayment(full.id,doc))
            assertEquals(0L,ReceiptStore(ctx,legacy,legacyRoom).demoPaymentSelection(full.id).choices.single().remainingCents)
            assertTrue(runCatching{receipts.prepareDemoPayment(mismatch.id,doc)}.isFailure)
            assertEquals(second,receipts.demoPaymentSelection(mismatch.id).choices.single().id)
            val partialEvent=receipts.prepareDemoPayment(mismatch.id,second)
            assertEquals(1150L,receipts.demoPaymentSelection(mismatch.id).choices.single().remainingCents)
            assertEquals(partialEvent,receipts.prepareDemoPayment(mismatch.id,second))
            assertTrue(receipts.cash(business,"TEST-3",date,"11.51","Preplata"))
            val over=receipts.read().receipts.single{it.reference=="TEST-3"}
            assertTrue(runCatching{receipts.prepareDemoPayment(over.id,second)}.isFailure)
            assertTrue(receipts.demoPaymentSelection(over.id).choices.isEmpty())
            assertTrue(receipts.cash(business,"TEST-4",date,"11.50","Završna naplata"))
            val finalPayment=receipts.read().receipts.single{it.reference=="TEST-4"}
            assertEquals(1150L,receipts.demoPaymentSelection(finalPayment.id).choices.single().remainingCents)
            val finalEvent=receipts.prepareDemoPayment(finalPayment.id,second)
            assertEquals(0L,receipts.demoPaymentSelection(finalPayment.id).choices.single().remainingCents)
            val syncEvents=receipts.demoSyncEvents(business)
            assertEquals(3,syncEvents.size)
            assertTrue(receipts.demoSyncEvents(business.copy(oib="00000000001")).isEmpty())
            val subject="71000000-0000-4000-8000-000000000001"
            val request=DemoSyncClient.request(subject,syncEvents)
            assertEquals(subject,request.getString("subjectId"))
            assertFalse(request.toString().contains(business.oib))
            assertFalse(request.toString().contains("Sintetički dokaz"))
            val ack=JSONObject().put("protocol",1).put("subjectId",subject).put("status","DEMO_METADATA_STORED")
                .put("accepted",org.json.JSONArray(syncEvents.map{it.getString("eventId")}))
            DemoSyncClient.verifyAck(subject,syncEvents,ack)
            val badAck=JSONObject(ack.toString()).put("subjectId","71000000-0000-4000-8000-000000000002")
            assertTrue(runCatching{DemoSyncClient.verifyAck(subject,syncEvents,badAck)}.isFailure)
            val exported=SecureRecords.exportWorkspace(ctx,source)
            val root=JSONObject(exported)
            assertEquals(5,root.getInt("version"))
            val array=root.getJSONArray("paymentPreparations")
            assertEquals(3,array.length())
            val prepared=(0 until array.length()).map{array.getJSONObject(it)}.single{it.getString("eventId")==event}
            assertEquals("2026-12-20",prepared.getString("deadlineDate"))
            assertEquals(event,prepared.getString("eventId"))
            val bad=JSONObject(exported)
            bad.getJSONArray("paymentPreparations").getJSONObject(0).put("amountCents",999)
            assertTrue(runCatching{SecureRecords.restoreWorkspace(ctx,bad.toString(),target)}.isFailure)
            assertTrue(target.readableDatabase.rawQuery("SELECT 1 FROM fiscal_document",null).use{!it.moveToFirst()})
            val badVersion=JSONObject(exported).put("version",4)
            assertTrue(runCatching{SecureRecords.restoreWorkspace(ctx,badVersion.toString(),target)}.isFailure)
            SecureRecords.restoreWorkspace(ctx,exported,target)
            assertEquals(event,ReceiptStore(ctx,target,targetRoom).prepareDemoPayment(full.id,doc))
            assertEquals(finalEvent,ReceiptStore(ctx,target,targetRoom).prepareDemoPayment(finalPayment.id,second))
            assertEquals(0L,ReceiptStore(ctx,target,targetRoom).demoPaymentSelection(finalPayment.id).choices.single().remainingCents)
            assertEquals(3,JSONObject(SecureRecords.exportWorkspace(ctx,target)).getJSONArray("paymentPreparations").length())
            assertTrue(runCatching{SecureRecords.restoreWorkspace(ctx,exported,target)}.isFailure)
        }finally{
            room.close();targetRoom.close();legacyRoom.close();source.close();target.close();legacy.close()
            ctx.deleteDatabase(sourceName);ctx.deleteDatabase(targetName);ctx.deleteDatabase(legacyName)
        }
    }
    @Test fun demoSyncEndpointRestrictions() {
        assertEquals("https://example.test/fiskai/api.php?action=demo-sync",DemoSyncClient.endpoint("https://example.test/fiskai/api.php").toString())
        for(url in listOf("http://example.test/api.php","https://user@example.test/api.php",
            "https://example.test/api.php?extra=1","https://example.test/api.php#fragment",
            "https://example.test:8080/api.php","https://example.test/other.php",
            "https://example.test/../api.php","https://example.test/%61pi.php")) {
            assertTrue(url,runCatching{DemoSyncClient.endpoint(url)}.isFailure)
        }
    }
    @Test fun durableSyncCheckpointRejectsRollbackAndConflict() {
        if(!BuildConfig.DEMO_MODE)return
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val subject=UUID.randomUUID().toString()
        val owner="12345678903"
        val url="https://example.test/api.php"
        val id=FiscalCutover.digest("DEMO_SYNC_V1\u0000"+DemoSyncClient.endpoint(url).toASCIIString()+"\u0000"+subject+"\u0000"+owner)
        val file=java.io.File(ctx.noBackupFilesDir,"demo-sync/"+id+".checkpoint")
        fun row()=JSONObject().put("eventId",UUID.randomUUID().toString())
            .put("receiptId",FiscalCutover.digest(UUID.randomUUID().toString()))
            .put("documentId",UUID.randomUUID().toString()).put("evidenceSha256",FiscalCutover.digest("test"))
            .put("eventDate","2026-12-31").put("amountCents",1250)
            .put("ruleVersion","HR-F2-2026-v1").put("deadlineDate","2027-01-20")
        val first=row()
        val remoteOnly=row()
        try {
            val remote=listOf(first,remoteOnly)
            val snapshot=JSONObject().put("protocol",1).put("subjectId",subject)
                .put("status","DEMO_METADATA_SNAPSHOT").put("events",org.json.JSONArray(remote))
            val validated=DemoSyncClient.snapshot(subject,snapshot)
            val local=JSONObject(first.toString()).put("environment","DEMO").put("kind","DEMO_PAYMENT_PREPARATION")
            assertEquals(DemoSyncJournal.Result(1,1,2),DemoSyncJournal(ctx).reconcileAndSave(url,subject,owner,listOf(local),validated))
            assertTrue(file.exists())
            assertFalse(file.readText().contains(first.getString("eventId")))
            assertEquals(DemoSyncJournal.Result(1,1,2),DemoSyncJournal(ctx).reconcileAndSave(url,subject,owner,listOf(local),validated))
            assertTrue(runCatching{DemoSyncJournal(ctx).reconcileAndSave(url,subject,owner,listOf(local),listOf(first))}.isFailure)
            val conflict=JSONObject(first.toString()).put("amountCents",1251)
            assertTrue(runCatching{DemoSyncJournal(ctx).reconcileAndSave(url,subject,owner,listOf(local),listOf(conflict,remoteOnly))}.isFailure)
            assertTrue(runCatching{DemoSyncClient.snapshot(UUID.randomUUID().toString(),snapshot)}.isFailure)
            val duplicate=JSONObject(snapshot.toString()).put("events",org.json.JSONArray(listOf(first,first)))
            assertTrue(runCatching{DemoSyncClient.snapshot(subject,duplicate)}.isFailure)
            assertEquals(DemoSyncJournal.Result(1,1,2),DemoSyncJournal(ctx).reconcileAndSave(url,subject,owner,listOf(local),validated))
            file.writeText("corrupted")
            assertTrue(runCatching{DemoSyncJournal(ctx).reconcileAndSave(url,subject,owner,listOf(local),validated)}.isFailure)
        }finally{android.util.AtomicFile(file).delete()}
    }
}
