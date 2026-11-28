package hr.brendigo.fiskai

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RegulatoryVectorInstrumentedTest {
    @Test fun crossRuntimePublicFixture() {
        val v=RegulatoryVector.data
        val resolver=RegulatoryVector.resolver(v)
        val digest=v.getString("catalogSha256")
        val result=resolver.resolveByDigest(digest,"2027-01-01",LegalCompliance.Subject.NON_VAT_COVERED,
            LegalCompliance.Scope.STANDARD_DOMESTIC_B2B)
        assertEquals(2,result.sequence)
        assertEquals(LegalCompliance.Obligation.REQUIRED,result.decision.issue)
        fun verify(nonce:String) = HistoricalLegalDecisionResolver.verifyFresh(v.getInt("sequence"),v.getString("head"),
            digest,nonce,RegulatoryVector.bytes(v,"statement"),RegulatoryVector.bytes(v,"signature"),
            RegulatoryVector.bytes(v,"publicKey"),v.getString("publicKeySha256"))
        verify(v.getString("nonce"))
        assertTrue(runCatching{verify("b".repeat(64))}.isFailure)
    }
}
