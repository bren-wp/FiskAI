package hr.brendigo.fiskai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CisJournalInstrumentedTest {
    @Test fun durableClaimAndRecovery() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="cis-test-"+UUID.randomUUID()+".db"
        val db=RecordDatabase(context,name)
        try {
            val journal=CisJournal(context,db)
            val id=journal.enqueue()
            assertTrue(journal.claim(id))
            assertFalse(journal.claim(id))
            journal.recoverInterrupted()
            assertEquals("UNCERTAIN",journal.history().single().state)
            assertFalse(journal.finish(id,"MATCH"))
            val second=journal.enqueue()
            assertTrue(journal.claim(second))
            assertTrue(journal.finish(second,"MATCH"))
            assertEquals("VERIFIED",journal.history().first{it.id==second}.state)
        } finally {db.close();context.deleteDatabase(name)}
    }
}
