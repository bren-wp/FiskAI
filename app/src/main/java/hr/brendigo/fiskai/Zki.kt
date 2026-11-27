package hr.brendigo.fiskai

import java.math.BigDecimal
import java.math.RoundingMode
import java.security.PrivateKey
import java.security.Signature
import java.security.MessageDigest
import java.util.Locale

/** Spec v2.7 chapter 12. Not a complete fiscal signature or authorization check. */
internal object Zki {
    fun input(oib:String,dateTime:String,number:String,premise:String,register:String,total:BigDecimal):ByteArray {
        require(validOib(oib) && dateTime.matches(Regex("[0-9]{2}\\.[0-9]{2}\\.[0-9]{4} [0-9]{2}:[0-9]{2}:[0-9]{2}")))
        require(validRegisterNumber(number)&&validLocalCode(premise)&&validRegisterNumber(register))
        return (oib+dateTime+number+premise+register+total.setScale(2,RoundingMode.UNNECESSARY).toPlainString()).toByteArray(Charsets.UTF_8)
    }
    fun compute(key:PrivateKey,input:ByteArray):String {
        require(key.algorithm.equals("RSA",true))
        val signature=Signature.getInstance("SHA256withRSA").apply{initSign(key);update(input)}.sign()
        // MD5 is mandated here only for the ZKI representation, never for security or XMLDSig.
        return try{MessageDigest.getInstance("MD5").digest(signature).joinToString(""){"%02x".format(Locale.ROOT,it.toInt() and 255)}}finally{signature.fill(0)}
    }
}
