package hr.brendigo.fiskai

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory

internal data class IncomingInvoice(val key:String,val supplier:String,val supplierOib:String,
    val buyerOib:String,val number:String,val date:String,val amount:BigDecimal,val digest:String,val status:String,val xml:String)

internal object IncomingUbl {
    const val MAX=512*1024
    private const val CBC="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2"
    private const val CAC="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2"
    private const val INV="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"
    fun read(input:InputStream):ByteArray {
        val out=ByteArrayOutputStream()
        val buffer=ByteArray(8192)
        while(true){
            val n=input.read(buffer);if(n<0)break
            require(n>0&&n<=MAX-out.size());out.write(buffer,0,n)
        }
        return out.toByteArray()
    }
    private fun children(e:Element,ns:String,name:String):List<Element> =
        (0 until e.childNodes.length).mapNotNull{e.childNodes.item(it) as? Element}.filter{it.namespaceURI==ns&&it.localName==name}
    private fun one(e:Element,ns:String,name:String)=children(e,ns,name).single()
    private fun text(e:Element,ns:String,name:String,max:Int):String {
        val n=one(e,ns,name)
        require((0 until n.childNodes.length).none{n.childNodes.item(it) is Element})
        return n.textContent.trim().also{require(it.isNotBlank()&&it.length<=max&&it.none{c->c.isISOControl()})}
    }
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun oib(p:Element):String {
        val schemes=children(p,CAC,"PartyTaxScheme")
        val ids=schemes.filter{scheme->
            children(scheme,CAC,"TaxScheme").singleOrNull()?.let{ text(it,CBC,"ID",20)=="VAT" }==true
        }.map{text(it,CBC,"CompanyID",32).removePrefix("HR")}
        val legal=children(p,CAC,"PartyLegalEntity").flatMap{children(it,CBC,"CompanyID")}.map{it.textContent.trim().removePrefix("HR")}.filter(::validOib)
        val candidates=(ids+legal).distinct()
        require(candidates.size==1&&validOib(candidates.single()))
        return candidates.single()
    }
    fun parse(bytes:ByteArray,buyer:String):IncomingInvoice {
        require(bytes.size in 1..MAX&&validOib(buyer))
        val xml=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        require(!xml.contains('\u0000')&&!xml.contains("<!"))
        val builder=DocumentBuilderFactory.newInstance().apply{
            isNamespaceAware=true
            isExpandEntityReferences=false
            setFeature("http://xml.org/sax/features/external-general-entities",false)
            setFeature("http://xml.org/sax/features/external-parameter-entities",false)
        }.newDocumentBuilder()
        builder.setEntityResolver{_,_->throw org.xml.sax.SAXException("External entity forbidden")}
        val document=builder.parse(ByteArrayInputStream(bytes))
        val root=document.documentElement
        require(document.doctype==null&&root.namespaceURI==INV&&root.localName=="Invoice")
        val number=text(root,CBC,"ID",128)
        val date=text(root,CBC,"IssueDate",10)
        require(date.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")))
        val format=SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).apply{isLenient=false;timeZone=TimeZone.getTimeZone("UTC")}
        require(format.format(requireNotNull(format.parse(date)))==date)
        require(text(root,CBC,"DocumentCurrencyCode",3)=="EUR")
        val supplier=one(one(root,CAC,"AccountingSupplierParty"),CAC,"Party")
        val customer=one(one(root,CAC,"AccountingCustomerParty"),CAC,"Party")
        val supplierOib=oib(supplier);val customerOib=oib(customer)
        require(customerOib==buyer&&supplierOib!=buyer)
        val supplierName=children(supplier,CAC,"PartyLegalEntity").singleOrNull()?.let{
            if(children(it,CBC,"RegistrationName").size==1)text(it,CBC,"RegistrationName",200) else null
        }?:text(one(supplier,CAC,"PartyName"),CBC,"Name",200)
        val payable=one(one(root,CAC,"LegalMonetaryTotal"),CBC,"PayableAmount")
        require(payable.getAttribute("currencyID")=="EUR"&&(0 until payable.childNodes.length).none{payable.childNodes.item(it) is Element})
        val decimal=payable.textContent.trim()
        require(decimal.matches(Regex("[0-9]{1,12}(\\.[0-9]{1,2})?")))
        val amount=BigDecimal(decimal).setScale(2)
        require(children(root,CAC,"InvoiceLine").isNotEmpty())
        val key=sha((supplierOib+"\u0000"+customerOib+"\u0000"+number).toByteArray(Charsets.UTF_8))
        return IncomingInvoice(key,supplierName,supplierOib,customerOib,number,date,amount,sha(bytes),"RECEIVED",Base64.encodeToString(bytes,Base64.NO_WRAP))
    }
}
internal class IncomingInvoiceStore(context:Context,db:RecordDatabase=RecordDatabase.shared(context)) {
    private val secure=SecureRecords(context,"fiskai_incoming",db)
    companion object {
        const val MAX_STORE=4*1024*1024
        fun decode(array:JSONArray):List<IncomingInvoice>{
            require(array.length()<=1000)
            val rows=List(array.length()){i->
                val j=array.getJSONObject(i)
                val parsed=IncomingUbl.parse(Base64.decode(j.getString("xml"),Base64.NO_WRAP),j.getString("buyerOib"))
                require(parsed.key==j.getString("key")&&parsed.digest==j.getString("digest"))
                val status=j.getString("status")
                require(status in setOf("RECEIVED","REVIEW","REJECTED"))
                parsed.copy(status=status)
            }
            require(rows.map{it.key}.distinct().size==rows.size)
            return rows
        }
        private fun encode(rows:List<IncomingInvoice>)=JSONArray().apply{rows.forEach{
            put(JSONObject().put("key",it.key).put("buyerOib",it.buyerOib).put("digest",it.digest).put("status",it.status).put("xml",it.xml))
        }}
    }
    fun read():List<IncomingInvoice> = SecureRecords.withRecordLock {
        val data=secure.getString("invoices","[]")
        require(data.toByteArray(Charsets.UTF_8).size<=MAX_STORE)
        decode(JSONArray(data))
    }
    private fun save(rows:List<IncomingInvoice>) {
        val json=encode(rows).toString()
        require(rows.size<=1000&&json.toByteArray(Charsets.UTF_8).size<=MAX_STORE)
        check(secure.write("invoices",json))
    }
    fun import(bytes:ByteArray,buyer:String):Boolean=SecureRecords.withRecordLock{
        val candidate=IncomingUbl.parse(bytes,buyer)
        val rows=read()
        val existing=rows.firstOrNull{it.key==candidate.key}
        if(existing!=null){
            require(existing.digest==candidate.digest){"Conflicting invoice identity"}
            return@withRecordLock false
        }
        save(rows+candidate);true
    }
    fun review(key:String,state:String)=SecureRecords.withRecordLock{
        require(state in setOf("RECEIVED","REVIEW","REJECTED"))
        val rows=read()
        require(rows.any{it.key==key})
        save(rows.map{if(it.key==key)it.copy(status=state)else it})
    }
}
