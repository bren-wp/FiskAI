package hr.brendigo.fiskai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RegulatoryRecoveryEvidenceInstrumentedTest {
    @Test fun archivedProofRequiresIndependentPinsAndRejectsTamper() {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val dir=File(ctx.noBackupFilesDir,"regulatory-test-"+UUID.randomUUID())
        val name="regulatory-test-"+UUID.randomUUID()+".db"
        val db=RecordDatabase(ctx,name)
        val path=File(dir,"proof.sealed")
        try {
            val v=RegulatoryVector.data
            v.remove("fixture")
            val trust=RegulatoryRecoveryEvidence.Trust(v.getInt("sequence"),v.getString("head"),
                v.getString("catalogSha256"),v.getString("publicKeySha256"))
            val store=RegulatoryRecoveryEvidence(ctx,db,path)
            assertTrue(runCatching{store.install(v.toString(),trust,"b".repeat(64))}.isFailure)
            assertFalse(path.exists())
            store.install(v.toString(),trust,v.getString("nonce"))
            val reopened=RegulatoryRecoveryEvidence(ctx,db,path).open(trust)
            assertEquals(2,reopened.resolveByDigest(trust.catalogSha256,"2027-01-01",
                LegalCompliance.Subject.VAT,LegalCompliance.Scope.STANDARD_DOMESTIC_B2B).sequence)
            assertTrue(runCatching{store.open(trust.copy(head="0".repeat(64)))}.isFailure)
            assertTrue(runCatching{store.open(trust.copy(sequence=1))}.isFailure)
            val original=path.readBytes()
            assertFalse(original.toString(Charsets.UTF_8).contains(v.getString("history")))
            path.writeText("corrupted")
            assertTrue(runCatching{store.open(trust)}.isFailure)
            assertTrue(runCatching{store.install(v.toString(),trust,v.getString("nonce"))}.isFailure)
            path.writeBytes(original)
            assertEquals(2,store.open(trust).resolveByDigest(trust.catalogSha256,"2027-01-01",
                LegalCompliance.Subject.VAT,LegalCompliance.Scope.STANDARD_DOMESTIC_B2B).sequence)
        }finally{db.close();ctx.deleteDatabase(name);dir.deleteRecursively()}
    }
}
