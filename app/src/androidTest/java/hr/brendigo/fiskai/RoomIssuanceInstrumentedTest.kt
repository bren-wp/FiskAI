package hr.brendigo.fiskai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hr.brendigo.fiskai.room.FiscalDocument
import hr.brendigo.fiskai.room.FiscalRoomDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RoomIssuanceInstrumentedTest {
    @Test fun atomicAllocationRollbackAndConcurrentIssuance() {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val name="room-issue-"+UUID.randomUUID()+".db"
        val legacyName="room-key-"+UUID.randomUUID()+".db"
        val legacy=RecordDatabase(ctx,legacyName)
        val room=FiscalRoomDatabase.open(ctx,name)
        val secure=SecureRecords(ctx,"fiskai_demo_receipts",legacy)
        val dao=room.queue()
        val executor=Executors.newFixedThreadPool(4)
        val now=System.currentTimeMillis()
        fun issue(draft:String, floor:Long=10L, fail:Boolean=false):FiscalDocument =
            SecureRecords.withRecordLock {
                dao.issueLocal(draft,2026,"PP1","1",floor,now) { number ->
                    if(fail)error("Simulated interruption")
                    val id=UUID.randomUUID().toString()
                    val json=JSONObject().put("environment","DEMO").put("draft",draft)
                        .put("number",number).put("year",2026).put("premise","PP1")
                        .put("register","1").put("created",now).toString()
                    FiscalDocument().apply {
                        this.id=id;draftId=draft;year=2026;premise="PP1";registerNo="1"
                        this.number=number;created=now
                        payload=secure.sealSnapshot(id,json)
                        digest=MessageDigest.getInstance("SHA-256").digest(json.toByteArray())
                            .joinToString(""){"%02x".format(it.toInt() and 255)}
                    }
                }
            }
        try {
            val first=UUID.randomUUID().toString()
            val document=issue(first)
            assertEquals(11L,document.number)
            assertEquals(document.id,issue(first).id)
            assertEquals("QUEUED",dao.entry(document.id).state)
            val failed=UUID.randomUUID().toString()
            assertTrue(runCatching{issue(failed,fail=true)}.isFailure)
            assertNull(dao.byDraft(failed))
            assertEquals(12L,issue(failed).number)
            val numbers=(1..8).map { executor.submit<Long>{issue(UUID.randomUUID().toString()).number} }
                .map { it.get(30,TimeUnit.SECONDS) }.sorted()
            assertEquals((13L..20L).toList(),numbers)
            assertEquals(21L,issue(UUID.randomUUID().toString()).number)
        } finally {
            executor.shutdownNow();room.close();legacy.close()
            ctx.deleteDatabase(name);ctx.deleteDatabase(legacyName)
        }
    }
}
