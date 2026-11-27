package hr.brendigo.fiskai

import android.util.Xml
import org.xmlpull.v1.XmlSerializer
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Structural UBL 2.1 draft only; not a Croatian CIUS invoice or fiscal request. */
internal object UblDraft {
    const val INV="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"
    const val CBC="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2"
    const val CAC="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2"
    val units=linkedMapOf("C62" to "Komad","HUR" to "Sat","KGM" to "Kilogram","LTR" to "Litra","MTR" to "Metar")
    fun canExport(d:Draft,b:BusinessProfile):Boolean {
        if(d.kind!="Račun"||!b.vatRegistered||!validOib(b.oib)||!validOib(d.oib)||b.name.isBlank()||b.address.isBlank()||b.city.isBlank()||d.customer.isBlank()||d.lines.isEmpty())return false
        if(d.lines.any{normalizeKpd(it.kpd)==null||it.unitCode !in units||it.vatRate.stripTrailingZeros().toPlainString() !in listOf("5","13","25")})return false
        return d.lines.groupBy{it.vatRate.stripTrailingZeros()}.all{(rate,lines)->
            val net=lines.fold(BigDecimal.ZERO){a,l->a+l.net}
            val vat=lines.fold(BigDecimal.ZERO){a,l->a+l.vat}
            net.multiply(rate).movePointLeft(2).setScale(2,RoundingMode.HALF_UP).compareTo(vat)==0
        }
    }
    fun build(d:Draft,b:BusinessProfile):ByteArray {
        require(canExport(d,b))
        val out=ByteArrayOutputStream()
        val x=Xml.newSerializer()
        x.setOutput(out,"UTF-8")
        x.startDocument("UTF-8",true)
        x.setPrefix("",INV);x.setPrefix("cbc",CBC);x.setPrefix("cac",CAC)
        x.startTag(INV,"Invoice")
        fun c(name:String,value:String,attrs:Map<String,String> = emptyMap()) {
            x.startTag(CBC,name);attrs.forEach{(k,v)->x.attribute(null,k,v)};x.text(value);x.endTag(CBC,name)
        }
        fun a(name:String,block:()->Unit){x.startTag(CAC,name);block();x.endTag(CAC,name)}
        fun amount(name:String,value:BigDecimal)=c(name,value.setScale(2,RoundingMode.UNNECESSARY).toPlainString(),mapOf("currencyID" to "EUR"))
        fun sum(lines:List<Line>,value:(Line)->BigDecimal)=lines.fold(BigDecimal.ZERO){acc,l->acc+value(l)}
        fun taxScheme(){a("TaxScheme"){c("ID","VAT")}}
        fun category(name:String,rate:BigDecimal){a(name){c("ID","S");c("Percent",rate.stripTrailingZeros().toPlainString());taxScheme()}}
        fun party(wrapper:String,name:String,oib:String,address:String?,city:String?){
            a(wrapper){a("Party"){
                a("PartyName"){c("Name",name)}
                if(address!=null&&city!=null)a("PostalAddress"){c("StreetName",address);c("CityName",city);a("Country"){c("IdentificationCode","HR")}}
                a("PartyTaxScheme"){c("CompanyID","HR"+oib);taxScheme()}
                a("PartyLegalEntity"){c("RegistrationName",name);c("CompanyID",oib)}
            }}
        }
        c("UBLVersionID","2.1")
        c("ID","TEST-NACRT-"+d.id)
        c("IssueDate",SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).apply{timeZone=TimeZone.getTimeZone("Europe/Zagreb")}.format(Date()))
        c("InvoiceTypeCode","380")
        c("Note","TESTNI NACRT - nije izdani eRačun, nije zahtjev za plaćanje.")
        c("DocumentCurrencyCode","EUR")
        party("AccountingSupplierParty",b.name,b.oib,b.address,b.city)
        // Customer postal address and VAT registration are not known in alpha; avoid asserting them.
        a("AccountingCustomerParty"){a("Party"){
            a("PartyName"){c("Name",d.customer)}
            a("PartyLegalEntity"){c("RegistrationName",d.customer);c("CompanyID",d.oib)}
        }}
        val net=sum(d.lines){it.net};val vat=sum(d.lines){it.vat}
        a("TaxTotal"){
            amount("TaxAmount",vat)
            d.lines.groupBy{it.vatRate.stripTrailingZeros()}.forEach{(rate,lines)->
                a("TaxSubtotal"){amount("TaxableAmount",sum(lines){it.net});amount("TaxAmount",sum(lines){it.vat});category("TaxCategory",rate)}
            }
        }
        a("LegalMonetaryTotal"){amount("LineExtensionAmount",net);amount("TaxExclusiveAmount",net);amount("TaxInclusiveAmount",net+vat);amount("PayableAmount",net+vat)}
        d.lines.forEachIndexed{i,line->a("InvoiceLine"){
            c("ID",(i+1).toString());c("InvoicedQuantity",line.quantity.toPlainString(),mapOf("unitCode" to line.unitCode));amount("LineExtensionAmount",line.net)
            a("Item"){c("Name",line.description);a("CommodityClassification"){c("ItemClassificationCode",requireNotNull(normalizeKpd(line.kpd)),mapOf("listID" to "CG"))};category("ClassifiedTaxCategory",line.vatRate)}
            a("Price"){c("PriceAmount",line.unitPrice.toPlainString(),mapOf("currencyID" to "EUR"))}
        }}
        x.endTag(INV,"Invoice");x.endDocument();x.flush()
        return out.toByteArray()
    }
}
