package hr.brendigo.fiskai

import java.math.BigDecimal
import java.math.RoundingMode

data class Line(val description: String, val quantity: BigDecimal, val unitPrice: BigDecimal, val vatRate: BigDecimal) {
    init { require(description.isNotBlank()); require(quantity.signum() > 0); require(unitPrice.signum() >= 0); require(vatRate in BigDecimal.ZERO..BigDecimal("100")) }
    val net: BigDecimal get() = quantity.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP)
    val vat: BigDecimal get() = net.multiply(vatRate).movePointLeft(2).setScale(2, RoundingMode.HALF_UP)
    val total: BigDecimal get() = net.add(vat)
}
fun validOib(value: String): Boolean {
    if (!value.matches(Regex("[0-9]{11}"))) return false
    var a = 10
    for (c in value.take(10)) {
        a = (a + c.digitToInt()) % 10
        if (a == 0) a = 10
        a = a * 2 % 11
    }
    return (11 - a) % 10 == value.last().digitToInt()
}
fun parseDecimal(value: String): BigDecimal? = value.trim().replace(',', '.').toBigDecimalOrNull()
