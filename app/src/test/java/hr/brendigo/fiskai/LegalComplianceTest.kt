package hr.brendigo.fiskai

import org.junit.Assert.assertEquals
import org.junit.Test
import hr.brendigo.fiskai.LegalCompliance.Subject
import hr.brendigo.fiskai.LegalCompliance.Scope
import hr.brendigo.fiskai.LegalCompliance.Obligation

class LegalComplianceTest {
    @Test fun statutoryBoundaries() {
        val required = Obligation.REQUIRED
        val notRequired = Obligation.NOT_REQUIRED_BY_THIS_RULE
        val review = Obligation.REVIEW_REQUIRED
        fun verify(date: String, subject: Subject, scope: Scope, version: String?, issue: Obligation, receive: Obligation) {
            val result = LegalCompliance.evaluate(date, subject, scope)
            assertEquals(LegalCompliance.Decision(version, issue, receive), result)
            assertEquals(issue, result.fiscalizeIssue)
            assertEquals(receive, result.fiscalizeReceive)
        }
        val b2b = Scope.STANDARD_DOMESTIC_B2B
        verify("2026-01-01", Subject.VAT, b2b, "HR-F2-2026-v1", required, required)
        verify("2026-12-31", Subject.NON_VAT_COVERED, b2b, "HR-F2-2026-v1", notRequired, required)
        verify("2027-01-01", Subject.NON_VAT_COVERED, b2b, "HR-F2-2027-v1", required, required)
        verify("2027-12-31", Subject.VAT, b2b, "HR-F2-2027-v1", required, required)
        verify("2025-12-31", Subject.VAT, b2b, null, review, review)
        verify("2028-01-01", Subject.VAT, b2b, null, review, review)
        verify("2027-01-01", Subject.UNKNOWN, b2b, "HR-F2-2027-v1", review, review)
        verify("2027-01-01", Subject.VAT, Scope.UNRESOLVED, "HR-F2-2027-v1", review, review)
        verify("2026-06-01", Subject.NON_VAT_COVERED, b2b, "HR-F2-2026-v1", notRequired, required)
        verify("2026-06-01", Subject.VAT, b2b, "HR-F2-2026-v1", required, required)
    }
    @Test(expected = IllegalArgumentException::class) fun invalidCalendarDate() {
        LegalCompliance.evaluate("2027-02-29", Subject.VAT, Scope.STANDARD_DOMESTIC_B2B)
    }
    @Test(expected = IllegalArgumentException::class) fun invalidDateFormat() {
        LegalCompliance.evaluate("2027-1-01", Subject.VAT, Scope.STANDARD_DOMESTIC_B2B)
    }
}
