package hr.brendigo.fiskai

import java.time.LocalDate
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

class LegalParityTest {
    @Test fun allDailyLegalAndReportingDecisions() {
        val hash = MessageDigest.getInstance("SHA-256")
        var count = 0
        fun emit(vararg fields: Any?) {
            hash.update((fields.joinToString("|") { it?.toString() ?: "-" } + "\n").toByteArray(Charsets.UTF_8))
            count++
        }
        var day = LocalDate.parse("2025-12-30")
        val end = LocalDate.parse("2028-01-03")
        while (day < end) {
            val date = day.toString()
            for (subject in LegalCompliance.Subject.values()) for (scope in LegalCompliance.Scope.values()) {
                val x = LegalCompliance.evaluate(date, subject, scope)
                emit("L", date, subject, scope, x.version, x.issue, x.receive, x.fiscalizeIssue, x.fiscalizeReceive)
            }
            for (event in FiscalReportingRules.Event.values()) {
                for (self in if (event == FiscalReportingRules.Event.ARTICLE_51_SUPPLY) listOf(false, true) else listOf(false)) {
                    val x = FiscalReportingRules.deadline(event, date, self)
                    emit("R", date, event, if (self) 1 else 0, x.kind, x.date, x.workingDays)
                }
            }
            day = day.plusDays(1)
        }
        assertEquals(10276, count)
        assertEquals("4c42d68d72352bda13ca28503f33489a6d3b85e0c508bdb47a4c06f2b31fbb6a", hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
    }
}
