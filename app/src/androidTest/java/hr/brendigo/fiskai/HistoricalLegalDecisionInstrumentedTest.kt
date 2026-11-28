package hr.brendigo.fiskai

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

@RunWith(AndroidJUnit4::class)
class HistoricalLegalDecisionInstrumentedTest {
    @Test fun signedRevisionAndNonceBoundFreshness() {
        val key=KeyPairGenerator.getInstance("EC").apply{initialize(ECGenParameterSpec("secp256r1"))}.generateKeyPair()
        val pem=("-----BEGIN PUBLIC KEY-----\n"+Base64.encodeToString(key.public.encoded,Base64.NO_WRAP)+
            "\n-----END PUBLIC KEY-----\n").toByteArray()
        fun digest(bytes:ByteArray)=HistoricalLegalDecisionResolver.digest(bytes)
        fun sign(bytes:ByteArray)=Signature.getInstance("SHA256withECDSA").run{initSign(key.private);update(bytes);sign()}
        fun catalog(nonVat:Boolean,version:String)=("""{"source":"https://narodne-novine.nn.hr/clanci/sluzbeni/2025_06_89_1233.html","scope":"STANDARD_DOMESTIC_B2B","rules":[{"from":"2027-01-01","until":"2028-01-01","version":"$version","vatIssue":true,"nonVatIssue":$nonVat}]}""").toByteArray()
        // Synthetic only: not a legal assertion.
        val old=catalog(false,"HR-F2-2027-v99");val active=catalog(true,"HR-F2-2027-v1")
        val l1="1|"+"0".repeat(64)+"|"+digest(old)+"\n"
        val l2="2|"+digest(l1.toByteArray())+"|"+digest(active)+"\n"
        val history=("FISKAI-REGULATORY-HISTORY-V1\n"+l1+l2).toByteArray()
        val head=digest(l2.toByteArray());val pin=digest(pem)
        val resolver=HistoricalLegalDecisionResolver.verified(history,sign(history),pem,pin,2,head,digest(active),listOf(old,active))
        val subject=LegalCompliance.Subject.NON_VAT_COVERED;val scope=LegalCompliance.Scope.STANDARD_DOMESTIC_B2B
        assertEquals(LegalCompliance.Obligation.NOT_REQUIRED_BY_THIS_RULE,resolver.resolve(1,digest(old),"2027-01-01",subject,scope).decision.issue)
        assertEquals(LegalCompliance.Obligation.REQUIRED,resolver.resolve(2,digest(active),"2027-01-01",subject,scope).decision.issue)
        assertEquals(LegalCompliance.Obligation.REVIEW_REQUIRED,resolver.resolve(2,digest(active),"2028-01-01",subject,scope).decision.issue)
        assertTrue(runCatching{resolver.resolve(1,digest(active),"2027-01-01",subject,scope)}.isFailure)
        val nonce="a".repeat(64)
        val statement=("2|$head|"+digest(active)+"\n").toByteArray()
        val proof=sign("FISKAI-REGULATORY-FRESH-V1\n$nonce\n".toByteArray()+statement)
        HistoricalLegalDecisionResolver.verifyFresh(2,head,digest(active),nonce,statement,proof,pem,pin)
        assertTrue(runCatching{HistoricalLegalDecisionResolver.verifyFresh(2,head,digest(active),"b".repeat(64),statement,proof,pem,pin)}.isFailure)
        assertTrue(runCatching{HistoricalLegalDecisionResolver.verified(history,sign(history),pem,pin,2,"0".repeat(64),digest(active),listOf(old,active))}.isFailure)
    }
}
