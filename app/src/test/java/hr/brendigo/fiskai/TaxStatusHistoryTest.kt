package hr.brendigo.fiskai

import org.junit.Assert.*
import org.junit.Test
import hr.brendigo.fiskai.LegalCompliance.Subject
import hr.brendigo.fiskai.LegalCompliance.Scope
import hr.brendigo.fiskai.LegalCompliance.Obligation
import hr.brendigo.fiskai.TaxStatusHistory.Period

class TaxStatusHistoryTest {
    private val b2b = Scope.STANDARD_DOMESTIC_B2B
    @Test fun historicalStatusAndGaps() {
        val history = TaxStatusHistory(listOf(
            Period("2027-01-01", null, Subject.NON_VAT_COVERED),
            Period("2026-07-01", "2026-12-01", Subject.VAT),
            Period("2026-01-01", "2026-07-01", Subject.NON_VAT_COVERED)
        ))
        val cases = listOf(
            Triple("2025-12-31", Subject.UNKNOWN, Obligation.REVIEW_REQUIRED),
            Triple("2026-06-30", Subject.NON_VAT_COVERED, Obligation.NOT_REQUIRED_BY_THIS_RULE),
            Triple("2026-07-01", Subject.VAT, Obligation.REQUIRED),
            Triple("2026-12-01", Subject.UNKNOWN, Obligation.REVIEW_REQUIRED),
            Triple("2027-01-01", Subject.NON_VAT_COVERED, Obligation.REQUIRED)
        )
        for ((date, subject, issue) in cases) {
            val result = history.assess(date, b2b)
            assertEquals(date, result.eventDate)
            assertEquals(subject, result.subject)
            assertEquals(issue, result.decision.issue)
        }
        assertNull(history.assess("2026-12-31", b2b).effectivePeriod)
        assertEquals(Obligation.REVIEW_REQUIRED, history.assess("2027-01-01", Scope.UNRESOLVED).decision.issue)
        assertEquals("HR-F2-2027-v1", history.assess("2027-01-01", b2b).decision.version)
    }
    @Test fun invalidTimelinesRejected() {
        val invalid = listOf(
            listOf(Period("2026-02-29", null, Subject.VAT)),
            listOf(Period("2026-01-01", "2026-01-01", Subject.VAT)),
            listOf(Period("2026-01-01", null, Subject.VAT), Period("2027-01-01", null, Subject.NON_VAT_COVERED)),
            listOf(Period("2026-01-01", "2026-08-01", Subject.VAT), Period("2026-07-01", null, Subject.VAT))
        )
        for (rows in invalid) {
            try { TaxStatusHistory(rows); fail("Invalid timeline accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun snapshotIsIndependentOfLaterInputChanges() {
        val rows = mutableListOf(Period("2026-01-01", null, Subject.NON_VAT_COVERED))
        val history = TaxStatusHistory(rows)
        val previous = history.assess("2026-01-01", b2b)
        rows.clear()
        rows.add(Period("2026-01-01", null, Subject.VAT))
        assertEquals(previous, history.assess("2026-01-01", b2b))
        assertEquals(Obligation.REQUIRED, TaxStatusHistory(rows).assess("2026-01-01", b2b).decision.issue)
    }
}
