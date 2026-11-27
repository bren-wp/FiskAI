package hr.brendigo.fiskai

internal data class Premise(val code: String, val name: String, val address: String)
internal data class Register(val premise: String, val number: String, val name: String)
internal data class Operator(val code: String, val name: String, val oib: String)
internal data class FiscalSetup(
    val premises: List<Premise> = emptyList(),
    val registers: List<Register> = emptyList(),
    val operators: List<Operator> = emptyList()
) {
    fun withPremise(p: Premise): FiscalSetup {
        require(validLocalCode(p.code) && p.name.isNotBlank() && p.address.isNotBlank())
        require(premises.none { it.code == p.code })
        return copy(premises = premises + p)
    }
    fun withRegister(r: Register): FiscalSetup {
        require(premises.any { it.code == r.premise })
        require(validRegisterNumber(r.number) && r.name.isNotBlank())
        require(registers.none { it.premise == r.premise && it.number == r.number })
        return copy(registers = registers + r)
    }
    fun withOperator(o: Operator): FiscalSetup {
        require(validLocalCode(o.code) && o.name.isNotBlank() && validOib(o.oib))
        require(operators.none { it.code == o.code })
        return copy(operators = operators + o)
    }
    fun withoutPremise(code: String): FiscalSetup {
        require(registers.none { it.premise == code })
        return copy(premises = premises.filterNot { it.code == code })
    }
}
internal fun validLocalCode(value: String) = value.matches(Regex("[A-Za-z0-9]{1,20}"))
internal fun validRegisterNumber(value: String) = value.matches(Regex("[1-9][0-9]{0,8}"))
