package hr.brendigo.fiskai

import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.io.InputStream
import java.io.ByteArrayOutputStream
import org.json.JSONObject

internal data class BankEvidence(val id:String,val owner:String,val iban:String,val reference:String,
    val date:String,val cents:Long,val description:String)
internal data class ReceiptEvidence(val id:String,val owner:String,val reference:String,val date:String,
    val cents:Long,val method:String,val bankId:String?,val note:String)

internal object ReceiptRules {
    const val MAX_BYTES=512*1024
    const val MAX_ROWS=2000
    fun date(s:String):String {
        require(s.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))&&s.take(4).toInt() in 2000..9999)
        val f=SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).apply{isLenient=false;timeZone=TimeZone.getTimeZone("UTC")}
        require(f.format(requireNotNull(f.parse(s)))==s)
        return s
    }
    fun text(s:String,max:Int):String=s.trim().also{require(it.isNotBlank()&&it.length<=max&&it.none{c->c.isISOControl()})}
    fun cents(s:String):Long {
        require(s.matches(Regex("-?[0-9]{1,10}([.,][0-9]{1,2})?")))
        return BigDecimal(s.replace(',','.')).movePointRight(2).longValueExact().also{require(it!=0L)}
    }
    fun amount(cents:Long):BigDecimal=BigDecimal.valueOf(cents,2)
    fun account(s:String)=s.filterNot(Char::isWhitespace).uppercase(Locale.ROOT).also{require(validCroatianIban(it))}
    fun bank(owner:String,iban:String,ref:String,date:String,cents:Long,description:String):BankEvidence {
        require(validOib(owner)&&cents in -999999999999L..999999999999L&&cents!=0L)
        val a=account(iban);val n=text(ref,128)
        return BankEvidence(FiscalCutover.digest("bank\u0000"+owner+"\u0000"+a+"\u0000"+n),owner,a,n,date(date),cents,text(description,512))
    }
    fun cash(owner:String,ref:String,date:String,cents:Long,note:String):ReceiptEvidence {
        require(validOib(owner)&&cents in 1L..999999999999L)
        val n=text(ref,128)
        return ReceiptEvidence(FiscalCutover.digest("cash\u0000"+owner+"\u0000"+n),owner,n,date(date),cents,"CASH",null,text(note,512))
    }
    fun fromBank(row:BankEvidence,note:String):ReceiptEvidence {
        require(row.cents>0)
        return ReceiptEvidence(FiscalCutover.digest("receipt\u0000"+row.id),row.owner,row.reference,row.date,row.cents,"BANK",row.id,text(note,512))
    }
    fun bankJson(row:BankEvidence)=JSONObject().put("id",row.id).put("owner",row.owner).put("iban",row.iban).put("reference",row.reference)
        .put("date",row.date).put("cents",row.cents).put("description",row.description)
    fun receiptJson(row:ReceiptEvidence)=JSONObject().put("id",row.id).put("owner",row.owner).put("reference",row.reference)
        .put("date",row.date).put("cents",row.cents).put("method",row.method).put("bankId",row.bankId?:JSONObject.NULL).put("note",row.note)
    fun decodeBank(j:JSONObject):BankEvidence {
        val row=bank(j.getString("owner"),j.getString("iban"),j.getString("reference"),j.getString("date"),j.getLong("cents"),j.getString("description"))
        val expected=bankJson(row)
        require(j.keys().asSequence().toSet()==expected.keys().asSequence().toSet()&&expected.keys().asSequence().all{expected.get(it).toString()==j.get(it).toString()})
        return row
    }
    fun decodeReceipt(j:JSONObject,banks:Map<String,BankEvidence>):ReceiptEvidence {
        val note=j.getString("note")
        val row=when(j.getString("method")){
            "CASH"->cash(j.getString("owner"),j.getString("reference"),j.getString("date"),j.getLong("cents"),note)
            "BANK"->fromBank(requireNotNull(banks[j.getString("bankId")]),note)
            else->error("Unsupported receipt")
        }
        // Compare fields by value, independent of JSON key order.
        val expected=receiptJson(row)
        require(j.keys().asSequence().toSet()==expected.keys().asSequence().toSet())
        require(expected.keys().asSequence().all{expected.get(it).toString()==j.get(it).toString()})
        return row
    }
    fun read(input:InputStream):ByteArray {
        val out=ByteArrayOutputStream();val buf=ByteArray(8192)
        while(true){val n=input.read(buf);if(n<0)break;require(n>0&&n<=MAX_BYTES-out.size());out.write(buf,0,n)}
        return out.toByteArray()
    }
    // Explicit, narrow interchange format; never guesses a bank's column layout.
    fun csv(bytes:ByteArray,owner:String,iban:String):List<BankEvidence> {
        require(bytes.size in 1..MAX_BYTES)
        val content=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        val lines=content.replace("\r\n","\n").trimEnd('\n').split('\n')
        require(lines.first()=="id;datum;iban;iznos;valuta;opis"&&lines.size in 2..(MAX_ROWS+1))
        val expected=account(iban)
        return lines.drop(1).map{line->
            require(!line.contains('"'))
            val fields=line.split(';');require(fields.size==6&&fields[4]=="EUR"&&account(fields[2])==expected)
            bank(owner,expected,fields[0],fields[1],cents(fields[3]),fields[5])
        }.also{require(it.map{r->r.id}.distinct().size==it.size)}
    }
}
