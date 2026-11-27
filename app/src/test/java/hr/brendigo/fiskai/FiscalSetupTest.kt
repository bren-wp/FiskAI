package hr.brendigo.fiskai

import org.junit.Assert.*
import org.junit.Test

class FiscalSetupTest {
    private val premise = Premise("PP1","Ured","Zagreb")
    @Test fun registerRequiresPremise() {
        val state = FiscalSetup().withPremise(premise).withRegister(Register("PP1","1","Blagajna"))
        assertEquals(1,state.registers.size)
    }
    @Test(expected=IllegalArgumentException::class) fun unknownPremise() {
        FiscalSetup().withRegister(Register("PP1","1","Blagajna"))
    }
    @Test(expected=IllegalArgumentException::class) fun duplicateRegister() {
        val r=Register("PP1","1","Blagajna")
        FiscalSetup().withPremise(premise).withRegister(r).withRegister(r)
    }
    @Test(expected=IllegalArgumentException::class) fun linkedPremiseCannotBeDeleted() {
        FiscalSetup().withPremise(premise).withRegister(Register("PP1","1","Blagajna")).withoutPremise("PP1")
    }
    @Test(expected=IllegalArgumentException::class) fun invalidOperatorOib() {
        FiscalSetup().withOperator(Operator("OP1","Operater","12345678901"))
    }
    @Test fun registerNumberFormat() {
        assertTrue(validRegisterNumber("123"))
        assertFalse(validRegisterNumber("01"))
        assertFalse(validRegisterNumber("0"))
    }
}
