package hr.brendigo.fiskai

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SecureRecordsInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var namespace: String
    private lateinit var dbName: String
    private lateinit var database: RecordDatabase
    @Before fun setup() {
        namespace = "fiskai_test_" + UUID.randomUUID()
        dbName = namespace + ".db"
        database = RecordDatabase(context,dbName)
    }
    @After fun cleanup() {
        database.close()
        context.deleteDatabase(dbName)
        context.getSharedPreferences(namespace,Context.MODE_PRIVATE).edit().clear().commit()
    }
    @Test fun plaintextMigrationRetainsDataAndRemovesLegacy() {
        val prefs=context.getSharedPreferences(namespace,Context.MODE_PRIVATE)
        val json="[{\"name\":\"Kupac\"}]"
        assertTrue(prefs.edit().putString("drafts",json).commit())
        val records=SecureRecords(context,namespace,database)
        assertEquals(json,records.getString("drafts","[]"))
        assertFalse(prefs.contains("drafts"))
        val payload=database.read(namespace,"drafts")!!
        assertTrue(payload.startsWith("gcm1:"))
        assertFalse(payload.contains("Kupac"))
        assertTrue(records.write("drafts","[]"))
        assertEquals("[]",SecureRecords(context,namespace,database).getString("drafts","[1]"))
    }
    @Test fun staleLegacyDoesNotReplaceNewData() {
        val prefs=context.getSharedPreferences(namespace,Context.MODE_PRIVATE)
        val records=SecureRecords(context,namespace,database)
        assertTrue(records.write("drafts","[2]"))
        assertTrue(prefs.edit().putString("drafts","[1]").commit())
        assertEquals("[2]",records.getString("drafts","[]"))
        assertFalse(prefs.contains("drafts"))
    }
    @Test fun invalidLegacyIsNotDeletedOrOverwritten() {
        val prefs=context.getSharedPreferences(namespace,Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString("drafts","invalid").commit())
        val records=SecureRecords(context,namespace,database)
        assertTrue(runCatching { records.getString("drafts","[]") }.isFailure)
        assertFalse(records.write("drafts","[]"))
        assertEquals("invalid",prefs.getString("drafts",null))
        assertNull(database.read(namespace,"drafts"))
    }
    @Test fun tamperedCiphertextFailsClosed() {
        val records=SecureRecords(context,namespace,database)
        assertTrue(records.write("drafts","[1]"))
        val damaged="gcm1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        database.write(namespace,"drafts",damaged)
        assertTrue(runCatching { records.getString("drafts","[]") }.isFailure)
        assertFalse(records.write("drafts","[]"))
        assertEquals(damaged,database.read(namespace,"drafts"))
    }
    @Test fun failedMigrationCanBeRetried() {
        assertTrue(runCatching { database.migrate(namespace,"drafts"){error("interrupted")} }.isFailure)
        val prefs=context.getSharedPreferences(namespace,Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString("drafts","[42]").commit())
        assertEquals("[42]",SecureRecords(context,namespace,database).getString("drafts","[]"))
    }
}
