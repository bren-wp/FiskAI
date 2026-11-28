package hr.brendigo.fiskai

import android.util.Base64
import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec

/** Offline reconstruction from signed exact-byte catalog history. Trust inputs
 * must come from an independently approved configuration, not the workspace backup.
 * Does not authorize dispatch or install a catalog. */
internal class HistoricalLegalDecisionResolver private constructor(private val catalogs:List<ByteArray>) {
    data class Result(val sequence:Int,val catalogSha256:String,val eventDate:String,
        val rule:LegalRuleCatalog.Rule?,val decision:LegalCompliance.Decision)
    companion object {
        private const val PREFIX="FISKAI-REGULATORY-HISTORY-V1\n"
        private const val MAX=1048576
        private val hex=Regex("[0-9a-f]{64}")
        internal fun digest(bytes:ByteArray):String=MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(""){"%02x".format(java.util.Locale.ROOT,it.toInt() and 255)}
        private fun publicKey(pem:ByteArray,pin:String):ECPublicKey {
            require(pem.size in 1..8192 && pin.matches(hex) && digest(pem)==pin)
            val text=pem.toString(Charsets.US_ASCII).trim()
            require(text.startsWith("-----BEGIN PUBLIC KEY-----\n") && text.endsWith("\n-----END PUBLIC KEY-----"))
            val body=text.removePrefix("-----BEGIN PUBLIC KEY-----\n").removeSuffix("\n-----END PUBLIC KEY-----")
            val key=KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(body,Base64.DEFAULT))) as ECPublicKey
            val params=java.security.AlgorithmParameters.getInstance("EC").apply{init(ECGenParameterSpec("secp256r1"))}
                .getParameterSpec(ECParameterSpec::class.java)
            require(key.params.curve==params.curve && key.params.generator==params.generator &&
                key.params.order==params.order && key.params.cofactor==params.cofactor)
            return key
        }
        fun verified(history:ByteArray,signature:ByteArray,pem:ByteArray,pin:String,latestSequence:Int,
            latestHead:String,activeCatalogSha256:String,catalogs:List<ByteArray>):HistoricalLegalDecisionResolver {
            require(history.size in 1..MAX && signature.size in 8..128 && latestSequence in 1..1000 &&
                latestHead.matches(hex) && activeCatalogSha256.matches(hex) && catalogs.size==latestSequence)
            require(catalogs.all{it.size in 1..MAX} && catalogs.sumOf{it.size.toLong()}<=16L*MAX)
            val copies=catalogs.map{it.copyOf()}
            val text=history.toString(Charsets.US_ASCII)
            require(text.toByteArray(Charsets.US_ASCII).contentEquals(history) && text.startsWith(PREFIX) && text.endsWith("\n"))
            val verifier=Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey(pem,pin));verifier.update(history)
            require(verifier.verify(signature)){"Invalid regulatory history signature"}
            val lines=text.removePrefix(PREFIX).dropLast(1).split("\n")
            require(lines.size==latestSequence)
            var head="0".repeat(64)
            val seen=mutableSetOf<String>()
            for((i,line) in lines.withIndex()) {
                val hash=digest(copies[i])
                require(seen.add(hash) && line==(i+1).toString()+"|$head|$hash"){"Invalid regulatory chain"}
                head=digest((line+"\n").toByteArray(Charsets.US_ASCII))
            }
            require(head==latestHead && digest(copies.last())==activeCatalogSha256){"Regulatory checkpoint mismatch"}
            return HistoricalLegalDecisionResolver(copies)
        }
        /** Same nonce-bound bytes as the independent Python witness / PHP verifier. */
        fun verifyFresh(sequence:Int,head:String,catalogSha256:String,nonce:String,statement:ByteArray,
            signature:ByteArray,pem:ByteArray,pin:String) {
            require(sequence in 1..1000 && listOf(head,catalogSha256,nonce).all{it.matches(hex)} && signature.size in 8..128)
            require(statement.contentEquals("$sequence|$head|$catalogSha256\n".toByteArray(Charsets.US_ASCII)))
            val verifier=Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey(pem,pin))
            verifier.update("FISKAI-REGULATORY-FRESH-V1\n$nonce\n".toByteArray(Charsets.US_ASCII))
            verifier.update(statement)
            require(verifier.verify(signature)){"Invalid regulatory freshness proof"}
        }
    }
    fun resolveByDigest(catalogSha256:String,date:String,subject:LegalCompliance.Subject,scope:LegalCompliance.Scope):Result {
        require(catalogSha256.matches(hex))
        val index=catalogs.indexOfFirst{digest(it)==catalogSha256}
        require(index>=0){"Unknown historical catalog"}
        return resolve(index+1,catalogSha256,date,subject,scope)
    }
    fun resolve(sequence:Int,catalogSha256:String,date:String,subject:LegalCompliance.Subject,scope:LegalCompliance.Scope):Result {
        LegalCompliance.validateDate(date)
        require(sequence in 1..catalogs.size && catalogSha256.matches(hex))
        val bytes=catalogs[sequence-1]
        require(digest(bytes)==catalogSha256)
        val catalog=JSONObject(bytes.toString(Charsets.UTF_8))
        require(catalog.keys().asSequence().toSet()==setOf("source","scope","rules") &&
            catalog.get("scope")=="STANDARD_DOMESTIC_B2B" &&
            catalog.get("source")=="https://narodne-novine.nn.hr/clanci/sluzbeni/2025_06_89_1233.html")
        val rules=catalog.getJSONArray("rules")
        require(rules.length() in 1..1000)
        var previous:String?=null
        val versions=mutableSetOf<String>()
        var match:LegalRuleCatalog.Rule?=null
        for(i in 0 until rules.length()) {
            val row=rules.getJSONObject(i)
            require(row.keys().asSequence().toSet()==setOf("from","until","version","vatIssue","nonVatIssue"))
            val from=row.get("from") as String;val until=row.get("until") as String
            val version=row.get("version") as String
            val vat=row.get("vatIssue") as Boolean;val nonVat=row.get("nonVatIssue") as Boolean
            val year=LegalCompliance.validateDate(from);LegalCompliance.validateDate(until)
            require(year>=2026 && from<until && (previous==null||previous<=from) &&
                version.matches(Regex("HR-F2-$year-v[1-9][0-9]*")) && versions.add(version))
            previous=until
            if(date>=from && date<until)match=LegalRuleCatalog.Rule(from,until,version,vat,nonVat)
        }
        return Result(sequence,catalogSha256,date,match,LegalCompliance.evaluateResolved(match,subject,scope))
    }
}
