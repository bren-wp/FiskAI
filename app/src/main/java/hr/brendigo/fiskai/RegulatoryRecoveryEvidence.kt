package hr.brendigo.fiskai

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream

/** Archived public proof, not a fresh authorization. Independent trust pins must be
 * supplied again on each open, never read from the workspace or this archive. */
internal class RegulatoryRecoveryEvidence(context:Context,db:RecordDatabase=RecordDatabase.shared(context),
    path:File=File(context.noBackupFilesDir,"regulatory-recovery/history.sealed")) {
    data class Trust(val sequence:Int,val head:String,val catalogSha256:String,val publicKeySha256:String)
    private val secure=SecureRecords(context,"fiskai_regulatory_recovery",db)
    private val file=AtomicFile(path)
    companion object {
        private const val ID="regulatory-history-v1"
        private const val MAX=8*1024*1024
        private const val SEALED_MAX=12*1024*1024
        private val fields=setOf("sequence","head","catalogSha256","publicKeySha256","publicKey",
            "history","historySignature","catalogs","nonce","statement","signature")
    }
    private fun bytes(v:JSONObject,key:String):ByteArray {
        val value=v.get(key) as String
        val decoded=Base64.decode(value,Base64.NO_WRAP)
        require(Base64.encodeToString(decoded,Base64.NO_WRAP)==value)
        return decoded
    }
    private fun trust(v:JSONObject)=Trust(v.getInt("sequence"),v.getString("head"),
        v.getString("catalogSha256"),v.getString("publicKeySha256"))
    private fun validate(v:JSONObject,expected:Trust):HistoricalLegalDecisionResolver {
        require(v.keys().asSequence().toSet()==fields && trust(v)==expected)
        val array=v.getJSONArray("catalogs")
        require(array.length()==expected.sequence && expected.sequence in 1..1000)
        val catalogs=List(array.length()) {
            val value=array.get(it) as String
            val decoded=Base64.decode(value,Base64.NO_WRAP)
            require(Base64.encodeToString(decoded,Base64.NO_WRAP)==value)
            decoded
        }
        val pem=bytes(v,"publicKey")
        val resolver=HistoricalLegalDecisionResolver.verified(bytes(v,"history"),bytes(v,"historySignature"),pem,
            expected.publicKeySha256,expected.sequence,expected.head,expected.catalogSha256,catalogs)
        HistoricalLegalDecisionResolver.verifyFresh(expected.sequence,expected.head,expected.catalogSha256,
            v.getString("nonce"),bytes(v,"statement"),bytes(v,"signature"),pem,expected.publicKeySha256)
        return resolver
    }
    private fun read():JSONObject {
        val encrypted=file.openRead().use { input ->
            val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true) {
                val n=input.read(buffer)
                if(n==-1)break
                require(n>0&&n<=SEALED_MAX-out.size())
                out.write(buffer,0,n)
            }
            out.toByteArray().toString(Charsets.UTF_8)
        }
        val plain=secure.openSnapshot(ID,encrypted)
        require(plain.toByteArray(Charsets.UTF_8).size<=MAX)
        return JSONObject(plain)
    }
    /** Caller must supply the newly generated challenge used for the independent request.
     * Installing this archive is never proof of current freshness on subsequent reads. */
    fun install(raw:String,expected:Trust,requestedNonce:String):HistoricalLegalDecisionResolver=SecureRecords.withRecordLock {
        require(raw.toByteArray(Charsets.UTF_8).size in 1..MAX)
        val v=JSONObject(raw)
        require(v.getString("nonce")==requestedNonce)
        val resolver=validate(v,expected)
        val parent=file.baseFile.parentFile
        check(parent!=null&&(parent.isDirectory||parent.mkdirs()))
        if(file.baseFile.exists()||File(file.baseFile.path+".bak").exists()) {
            val prior=read()
            val old=trust(prior)
            validate(prior,old) // authenticated prior, not external current trust
            require(expected.publicKeySha256==old.publicKeySha256 && expected.sequence>=old.sequence)
            val lines=bytes(v,"history").toString(Charsets.US_ASCII).lines()
            require(HistoricalLegalDecisionResolver.digest((lines[old.sequence]+"\n").toByteArray(Charsets.US_ASCII))==old.head) {
                "Regulatory archive rollback or fork"
            }
        }
        val sealed=secure.sealSnapshot(ID,raw).toByteArray(Charsets.UTF_8)
        require(sealed.size<=SEALED_MAX)
        val out=file.startWrite()
        try {out.write(sealed);file.finishWrite(out)} catch(e:Throwable){file.failWrite(out);throw e}
        validate(read(),expected)
        resolver
    }
    fun open(expected:Trust):HistoricalLegalDecisionResolver=SecureRecords.withRecordLock {
        validate(read(),expected)
    }
}
