package hr.brendigo.fiskai

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.pdf417.encoder.Compaction
import com.google.zxing.pdf417.encoder.PDF417
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

internal data class PaymentData(val payer:String,val payerAddress:String,val payerCity:String,val payee:String,val payeeAddress:String,val payeeCity:String,val iban:String,val amount:BigDecimal,val description:String)
internal object PaymentCodes {
    private val hubChars=Regex("[0-9A-Za-zČĆĐŠŽčćđšž ,.:+?'/()\\-]*")
    private fun hubField(s:String,max:Int,required:Boolean=true):String {
        val v=s.trim()
        require((!required||v.isNotEmpty())&&v.length<=max&&hubChars.matches(v))
        return v
    }
    private fun iban(p:PaymentData):String {
        val normalized=p.iban.filterNot(Char::isWhitespace).uppercase(Locale.ROOT)
        require(validCroatianIban(normalized))
        return normalized
    }
    private fun amount(p:PaymentData):BigDecimal {
        val v=p.amount.setScale(2,RoundingMode.UNNECESSARY)
        require(v>=BigDecimal("0.01")&&v<=BigDecimal("999999999.99"))
        return v
    }
    // HR99: without reference. No arbitrary model is guessed.
    fun hub(p:PaymentData):String = listOf(
        "HRVHUB30","EUR",amount(p).movePointRight(2).toBigIntegerExact().toString().padStart(15,'0'),
        hubField(p.payer,30),hubField(p.payerAddress,27),hubField(p.payerCity,27),
        hubField(p.payee,25),hubField(p.payeeAddress,25),hubField(p.payeeCity,27),
        iban(p),"HR99","","",hubField(p.description,35)
    ).joinToString("\n",postfix="\n")
    fun epc(p:PaymentData):String {
        val name=p.payee.trim();val text=p.description.trim()
        require(name.isNotEmpty()&&name.length<=70&&text.length<=140&&name.none{it.isISOControl()}&&text.none{it.isISOControl()})
        val fields=listOf("BCD","002","1","SCT","",name,iban(p),"EUR"+amount(p).toPlainString(),"","",text)
        val payload=fields.dropLastWhile{it.isEmpty()}.joinToString("\n")
        require(payload.toByteArray(Charsets.UTF_8).size<=331)
        return payload
    }
    fun qr(payload:String):BitMatrix {
        val matrix=QRCodeWriter().encode(payload,BarcodeFormat.QR_CODE,0,0,mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,EncodeHintType.MARGIN to 4
        ))
        require(matrix.width<=77)
        return matrix
    }
    fun pdf417(payload:String):BitMatrix {
        val encoder=PDF417(false)
        encoder.setDimensions(9,9,32,3)
        encoder.setCompaction(Compaction.BYTE)
        encoder.setEncoding(Charsets.UTF_8)
        encoder.generateBarcodeLogic(payload,4)
        val raw=encoder.barcodeMatrix.getScaledMatrix(1,3)
        require(raw.isNotEmpty()&&raw.size<=96)
        val result=BitMatrix(raw[0].size+4,raw.size+4)
        for(y in raw.indices)for(x in raw[y].indices)if(raw[y][x].toInt()==1)result.set(x+2,raw.size-1-y+2)
        return result
    }
}
