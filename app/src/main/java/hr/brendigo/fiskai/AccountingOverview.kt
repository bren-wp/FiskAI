package hr.brendigo.fiskai

import java.math.BigDecimal

/** Read-only cash and document projection. Incoming invoice value is not a paid expense,
 * and unconfirmed bank inflows are not receipts. No tax liability is inferred. */
internal object AccountingOverview {
    data class Period(val from:String,val until:String) {
        init { ReceiptRules.date(from);ReceiptRules.date(until);require(from<=until) }
        fun includes(date:String)=date>=from&&date<=until
    }
    data class Report(val period:Period,val cash:Long,val confirmedBank:Long,val bankIn:Long,
        val bankOut:Long,val unconfirmedBank:Long,val receipts:Int,val pending:Int,
        val incoming:Long,val incomingCount:Int,val rejectedIncoming:Int,val reviewIncoming:Int) {
        val confirmed:Long get()=Math.addExact(cash,confirmedBank)
        val bankNet:Long get()=Math.subtractExact(bankIn,bankOut)
        /** Machine-readable aggregate only: no user text, OIB, account or invoice identifiers. */
        fun csv():String {
            val rows=listOf("gotovinski_primici" to cash,"potvrdeni_bankovni_primici" to confirmedBank,
                "ukupno_potvrdeni_primici" to confirmed,"bankovni_priljevi" to bankIn,
                "bankovni_odljevi" to bankOut,"neto_bankovni_tok" to bankNet,
                "nepotvrdeni_bankovni_priljevi" to unconfirmedBank,"ulazni_racuni_iznos" to incoming)
            return "razdoblje_od;razdoblje_do;pokazatelj;iznos_eur\r\n"+rows.joinToString("\r\n",postfix="\r\n"){
                period.from+";"+period.until+";"+it.first+";"+BigDecimal.valueOf(it.second,2).toPlainString()
            }
        }
    }
    fun summarize(owner:String,period:Period,workspace:ReceiptWorkspace,invoices:List<IncomingInvoice>):Report {
        require(validOib(owner))
        require(workspace.bank.size<=ReceiptRules.MAX_ROWS&&workspace.receipts.size<=ReceiptRules.MAX_ROWS&&invoices.size<=1000)
        val banks=workspace.bank.filter{it.owner==owner}
        val receipts=workspace.receipts.filter{it.owner==owner}
        val bankById=banks.associateBy{it.id}
        require(bankById.size==banks.size&&receipts.map{it.id}.distinct().size==receipts.size)
        val linked=mutableSetOf<String>()
        for(row in receipts) {
            require(row.cents>0)
            when(row.method) {
                "CASH"->require(row.bankId==null)
                "BANK"->{
                    val id=requireNotNull(row.bankId)
                    val b=requireNotNull(bankById[id])
                    require(b.cents==row.cents&&b.date==row.date&&linked.add(id))
                }
                else->error("Unsupported receipt method")
            }
        }
        val inPeriod=banks.filter{period.includes(it.date)}
        val confirmed=receipts.filter{period.includes(it.date)}
        val pending=inPeriod.filter{it.cents>0&&it.id !in linked}
        fun sum(values:Iterable<Long>)=values.fold(0L){total,next->Math.addExact(total,next)}
        val incoming=invoices.filter{it.buyerOib==owner&&period.includes(it.date)}
        require(incoming.map{it.key}.distinct().size==incoming.size)
        require(incoming.all{it.status in setOf("RECEIVED","REVIEW","REJECTED")&&it.amount.signum()>=0})
        val accepted=incoming.filter{it.status!="REJECTED"}
        val result=Report(period,sum(confirmed.filter{it.method=="CASH"}.map{it.cents}),
            sum(confirmed.filter{it.method=="BANK"}.map{it.cents}),sum(inPeriod.filter{it.cents>0}.map{it.cents}),
            sum(inPeriod.filter{it.cents<0}.map{Math.negateExact(it.cents)}),sum(pending.map{it.cents}),
            confirmed.size,pending.size,sum(accepted.map{it.amount.movePointRight(2).longValueExact()}),
            accepted.size,incoming.count{it.status=="REJECTED"},incoming.count{it.status=="REVIEW"})
        check(Math.addExact(result.confirmedBank,result.unconfirmedBank)==result.bankIn)
        return result
    }
}
