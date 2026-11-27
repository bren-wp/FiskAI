package hr.brendigo.fiskai

/** A validated timeline for one business subject. Gaps are deliberately unknown. */
internal class TaxStatusHistory(periods: List<Period>) {
    data class Period(val from: String, val until: String?, val subject: LegalCompliance.Subject)
    data class Assessment(val eventDate: String, val subject: LegalCompliance.Subject,
        val scope: LegalCompliance.Scope, val effectivePeriod: Period?, val decision: LegalCompliance.Decision)

    private val timeline: List<Period> = periods.toList().also { rows ->
        require(rows.size <= 1000)
        rows.forEach { row ->
            LegalCompliance.validateDate(row.from)
            row.until?.let { LegalCompliance.validateDate(it); require(it > row.from) }
        }
    }.sortedBy { it.from }.also { rows ->
        rows.zipWithNext().forEach { (first, next) ->
            require(first.until != null && first.until <= next.from) { "Overlapping tax status periods" }
        }
    }

    /** from inclusive, until exclusive; never use the device date as a fallback. */
    fun assess(date: String, scope: LegalCompliance.Scope): Assessment {
        LegalCompliance.validateDate(date)
        val period = timeline.firstOrNull { it.from <= date && (it.until == null || date < it.until) }
        val subject = period?.subject ?: LegalCompliance.Subject.UNKNOWN
        return Assessment(date, subject, scope, period, LegalCompliance.evaluate(date, subject, scope))
    }
}
