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
}
