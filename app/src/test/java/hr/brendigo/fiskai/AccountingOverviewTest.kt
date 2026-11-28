package hr.brendigo.fiskai

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class AccountingOverviewTest {
    private val owner="12345678903"
    private val period=AccountingOverview.Period("2027-01-01","2027-01-31")
    private fun bank(id:String,cents:Long,date:String="2027-01-10",oib:String=owner)=BankEvidence(id,oib,"",id,date,cents,"")
    private fun receipt(id:String,cents:Long,bankId:String?=null,date:String="2027-01-10")=
        ReceiptEvidence(id,owner,id,date,cents,if(bankId==null)"CASH" else "BANK",bankId,"")
    private fun invoice(id:String,amount:String,status:String="RECEIVED",buyer:String=owner)=
        IncomingInvoice(id,"Supplier","",buyer,id,"2027-01-10",BigDecimal(amount),"",status,"")
    @Test fun noDoubleCountAndPeriodScope(){
        val data=ReceiptWorkspace(listOf(bank("a",1200),bank("b",400),bank("c",-700),
            bank("old",100,"2026-12-31"),bank("other",999,oib="00000000001")),
            listOf(receipt("r1",1200,"a"),receipt("r2",300)))
        val r=AccountingOverview.summarize(owner,period,data,listOf(invoice("i","12.34"),invoice("j","99","REJECTED"),
            invoice("k","1","REVIEW"),invoice("l","200",buyer="00000000001")))
        assertEquals(1500L,r.confirmed);assertEquals(1600L,r.bankIn)
        assertEquals(400L,r.unconfirmedBank);assertEquals(900L,r.bankNet)
        assertEquals(1334L,r.incoming);assertEquals(2,r.incomingCount);assertEquals(1,r.rejectedIncoming)
        assertTrue(r.csv().contains("ukupno_potvrdeni_primici;15.00"))
        assertFalse(r.csv().contains(owner))
    }
    @Test fun rejectsInconsistentReceipt(){
        val data=ReceiptWorkspace(listOf(bank("a",100)),listOf(receipt("r1",100,"a"),receipt("r2",100,"a")))
        assertTrue(runCatching{AccountingOverview.summarize(owner,period,data,emptyList())}.isFailure)
        assertTrue(runCatching{AccountingOverview.Period("2027-02-29","2027-03-01")}.isFailure)
    }
}
