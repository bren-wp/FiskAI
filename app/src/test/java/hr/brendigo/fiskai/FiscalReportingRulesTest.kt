package hr.brendigo.fiskai

import org.junit.Assert.*
import org.junit.Test
import hr.brendigo.fiskai.FiscalReportingRules.Event
import hr.brendigo.fiskai.FiscalReportingRules.DeadlineKind

class FiscalReportingRulesTest {
    @Test fun monthlyEvents() {
        for (event in listOf(Event.PAYMENT, Event.REJECTION)) {
            assertEquals("2027-01-20", FiscalReportingRules.deadline(event,"2026-12-31").date)
            assertEquals("2027-03-20", FiscalReportingRules.deadline(event,"2027-02-01").date)
            assertEquals("2028-01-20", FiscalReportingRules.deadline(event,"2027-12-31").date)
        }
    }
    @Test fun distinctFiscalDeadlines() {
        assertEquals(DeadlineKind.AT_ISSUE, FiscalReportingRules.deadline(Event.ISSUED,"2027-01-01").kind)
        assertEquals("2027-01-01", FiscalReportingRules.deadline(Event.ARTICLE_51_SUPPLY,"2027-01-01").date)
        for (event in listOf(Event.SELF_BILLED,Event.RECEIVED,Event.TECHNICAL_INTERRUPTION)) {
            val deadline=FiscalReportingRules.deadline(event,"2027-01-01")
            assertEquals(DeadlineKind.WORKING_DAYS_REVIEW,deadline.kind)
            assertEquals(5,deadline.workingDays)
            assertNull(deadline.date)
        }
        assertEquals(5,FiscalReportingRules.deadline(Event.ARTICLE_51_SUPPLY,"2027-01-01",true).workingDays)
        assertEquals(DeadlineKind.REVIEW_REQUIRED,FiscalReportingRules.deadline(Event.PAYMENT,"2028-01-01").kind)
    }
    @Test(expected=IllegalArgumentException::class) fun invalidDate() {
        FiscalReportingRules.deadline(Event.PAYMENT,"2027-02-29")
    }
    @Test(expected=IllegalArgumentException::class) fun invalidFlag() {
        FiscalReportingRules.deadline(Event.REJECTION,"2027-01-01",true)
    }
}
