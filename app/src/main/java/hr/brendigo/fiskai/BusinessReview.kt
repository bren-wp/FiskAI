package hr.brendigo.fiskai

/** Deterministic local checks; no LLM, external transfer or automatic legal assessment. */
internal object BusinessReview {
    data class Finding(val title:String,val detail:String,val destination:String)
    fun findings(business:BusinessProfile,drafts:List<Draft>,products:List<Product>,
        setup:FiscalSetup,receipts:ReceiptWorkspace?,invoices:List<IncomingInvoice>?,stock:Map<String,Long>?):List<Finding> = buildList {
        val configured=validOib(business.oib)
        if(!configured||business.name.isBlank()||business.address.isBlank()||business.city.isBlank())
            add(Finding("Dopunite poslovni subjekt","Provjerite naziv, OIB, adresu i grad.","Poslovni subjekt"))
        if(!validCroatianIban(business.iban))
            add(Finding("Provjerite IBAN","Za bankovnu evidenciju potreban je valjani hrvatski IBAN.","Poslovni subjekt"))
        if(setup.premises.isEmpty())add(Finding("Poslovni prostori","Nije unesen nijedan prostor.","Poslovni prostori"))
        if(setup.registers.isEmpty())add(Finding("Naplatni uređaji","Nije unesen nijedan uređaj.","Naplatni uređaji"))
        if(setup.operators.isEmpty())add(Finding("Operateri","Nije unesen nijedan operater.","Operateri"))
        val active=products.filterNot{it.archived}
        val incomplete=active.count{normalizeKpd(it.kpd)==null||it.unitCode !in UblDraft.units}
        if(incomplete>0)add(Finding("Katalog za eRačune","Artikli bez potpune oznake KPD ili jedinice mjere: $incomplete.","Proizvodi i usluge"))
        val retail=active.count{it.retail?.base==null}
        if(retail>0)add(Finding("Dokazi o cijenama","Aktivni artikli bez evidentirane bazne cijene: $retail. Provjerite primjenjivost prije objave.","Proizvodi i usluge"))
        val invoiceDrafts=drafts.filter{it.kind=="Račun"}
        val pendingDrafts=invoiceDrafts.count{!UblDraft.canExport(it,business)}
        if(pendingDrafts>0)add(Finding("Nacrti računa za pregled","Nacrti koji ne zadovoljavaju trenutačne uvjete testnog UBL izvoza: $pendingDrafts.","Računi"))
        if(receipts==null)add(Finding("Bankovna evidencija nije dostupna","Podaci nisu procijenjeni. Otvorite evidenciju za provjeru.","Bankovni izvodi"))
        else if(configured){
            val linked=receipts.receipts.filter{it.owner==business.oib}.mapNotNull{it.bankId}.toSet()
            val pending=receipts.bank.count{it.owner==business.oib&&it.cents>0&&it.id !in linked}
            if(pending>0)add(Finding("Nepotvrđeni bankovni priljevi","Za pregled: $pending. Ne knjiže se automatski.","Bankovni izvodi"))
        }
        if(invoices==null)add(Finding("Ulazni računi nisu dostupni","Podaci nisu procijenjeni.","eRačuni"))
        else if(configured){
            val pending=invoices.count{it.buyerOib==business.oib&&it.status!="REJECTED"}
            if(pending>0)add(Finding("Ulazni računi","Neodbijeni računi u evidenciji: $pending. Provjerite sadržaj i status.","eRačuni"))
        }
        if(stock==null)add(Finding("Stanje zaliha nije potvrđeno","Ne oslanjajte se na neprovjereno stanje.","Skladište"))
        else {
            val empty=active.count{!it.service&&(stock[it.id]?:0L)==0L}
            if(empty>0)add(Finding("Artikli bez zalihe","Artikli s nultim evidentiranim stanjem: $empty.","Skladište"))
        }
    }
}
