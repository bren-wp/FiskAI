package hr.brendigo.fiskai

/** A scoped statutory baseline only. Not permission to issue or fiscalize a document. */
internal object LegalCompliance {
    enum class Subject { VAT, NON_VAT_COVERED, UNKNOWN }
    enum class Scope { STANDARD_DOMESTIC_B2B, UNRESOLVED }
    enum class Obligation { REQUIRED, NOT_REQUIRED_BY_THIS_RULE, REVIEW_REQUIRED }
    data class Decision(val version: String?, val issue: Obligation, val receive: Obligation) {
        val fiscalizeIssue: Obligation get() = issue
        val fiscalizeReceive: Obligation get() = receive
    }

    // ISO date of the legally relevant event, not the current device clock.
    // Avoid java.time dependency on API 24/25.
    internal fun validateDate(date: String): Int {
        require(date.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")))
        val year = date.substring(0, 4).toInt()
        val month = date.substring(5, 7).toInt()
        val day = date.substring(8, 10).toInt()
        require(year in 1..9999 && month in 1..12)
        val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
        val days = when (month) { 2 -> if (leap) 29 else 28; 4, 6, 9, 11 -> 30; else -> 31 }
        require(day in 1..days)
        return year
    }

    fun evaluate(date: String, subject: Subject, scope: Scope): Decision {
        val year = validateDate(date)
        val rule = LegalRuleCatalog.rules[year]
        val version = rule?.version
        if (rule == null || scope != Scope.STANDARD_DOMESTIC_B2B || subject == Subject.UNKNOWN) {
            return Decision(version, Obligation.REVIEW_REQUIRED, Obligation.REVIEW_REQUIRED)
        }
        val issue = if (if (subject == Subject.VAT) rule.vatIssue else rule.nonVatIssue) Obligation.REQUIRED else Obligation.NOT_REQUIRED_BY_THIS_RULE
        return Decision(version, issue, Obligation.REQUIRED)
    }
}
