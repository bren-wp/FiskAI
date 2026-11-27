package hr.brendigo.fiskai

/** Statutory baseline for confirmed events; not a transport or proof of acceptance. */
internal object FiscalReportingRules {
    enum class Event { PAYMENT, REJECTION, ARTICLE_51_SUPPLY, ISSUED, SELF_BILLED, RECEIVED, TECHNICAL_INTERRUPTION }
    enum class DeadlineKind { AT_ISSUE, MONTHLY_STATUTORY_DATE, WORKING_DAYS_REVIEW, REVIEW_REQUIRED }
    data class Deadline(val kind: DeadlineKind, val date: String? = null, val workingDays: Int? = null)

    fun deadline(event: Event, date: String, selfBilled: Boolean = false): Deadline {
        val year = LegalCompliance.validateDate(date)
        require(!selfBilled || event == Event.ARTICLE_51_SUPPLY)
        if (LegalRuleCatalog.rules[year] == null) return Deadline(DeadlineKind.REVIEW_REQUIRED)
        return when(event) {
            Event.PAYMENT, Event.REJECTION -> {
                val month = date.substring(5, 7).toInt()
                val nextYear = if (month == 12) year + 1 else year
                val nextMonth = if (month == 12) 1 else month + 1
                Deadline(DeadlineKind.MONTHLY_STATUTORY_DATE,
                    nextYear.toString().padStart(4, '0') + "-" + nextMonth.toString().padStart(2, '0') + "-20")
            }
            Event.ISSUED -> Deadline(DeadlineKind.AT_ISSUE, date)
            Event.ARTICLE_51_SUPPLY -> if (selfBilled) Deadline(DeadlineKind.WORKING_DAYS_REVIEW, workingDays = 5)
                else Deadline(DeadlineKind.AT_ISSUE, date)
            Event.SELF_BILLED, Event.RECEIVED, Event.TECHNICAL_INTERRUPTION ->
                Deadline(DeadlineKind.WORKING_DAYS_REVIEW, workingDays = 5)
        }
    }
}
