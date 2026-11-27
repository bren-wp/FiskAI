package hr.brendigo.fiskai

import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.text.ParsePosition
import java.util.Locale

data class RetailPrice(val gross:BigDecimal,val base:BigDecimal?,val referenceDate:String,val evidence:String,val special:Boolean,val recorded:Long)

internal fun normalizeKpd(value:String):String? {
    val s=value.trim()
    if(!s.matches(Regex("[0-9]{6}|[0-9]{2}\\.[0-9]{2}\\.[0-9]{2}"))) return null
    return s.replace(".","").chunked(2).joinToString(".")
}
private fun money(s:String):BigDecimal {
    val v=requireNotNull(parseDecimal(s))
    require(v.signum()>=0 && v.precision()<=12 && v.scale() in 0..2)
    return v.setScale(2)
}
internal fun parseRetail(gross:String,base:String,date:String,evidence:String,special:Boolean):RetailPrice? {
    if(listOf(gross,base,date,evidence).all{it.isBlank()}&&!special)return null
    val current=money(gross)
    val anchor=if(base.isBlank())null else money(base)
    val day=date.trim()
    val note=evidence.trim()
    require(note.length<=1000)
    if(anchor!=null){
        require(day.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))&&note.isNotBlank())
        val fmt=SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).apply{isLenient=false}
        val pos=ParsePosition(0)
        require(fmt.parse(day,pos)!=null&&pos.index==day.length)
    }else require(day.isEmpty()&&note.isEmpty())
    return RetailPrice(current,anchor,day,note,special,System.currentTimeMillis())
}
internal fun Product.withRetail(next:RetailPrice?):Product {
    val old=retail
    if(old!=null&&next!=null&&old.copy(recorded=next.recorded)==next)return this
    return copy(retail=next,priceHistory=priceHistory+listOfNotNull(old))
}
