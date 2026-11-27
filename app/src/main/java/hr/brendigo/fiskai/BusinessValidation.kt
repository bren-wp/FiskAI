package hr.brendigo.fiskai

internal fun validCroatianIban(input: String): Boolean {
    val iban = input.filterNot(Char::isWhitespace).uppercase(java.util.Locale.ROOT)
    if(!iban.matches(Regex("HR[0-9]{19}"))) return false
    val shifted = iban.substring(4) + iban.substring(0,4)
    var remainder = 0
    for(c in shifted) {
        remainder = if(c.isDigit()) (remainder * 10 + c.digitToInt()) % 97
        else (remainder * 100 + (c - 'A' + 10)) % 97
    }
    return remainder == 1
}
