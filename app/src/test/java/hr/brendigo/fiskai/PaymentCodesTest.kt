package hr.brendigo.fiskai

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.pdf417.PDF417Reader

class PaymentCodesTest {
    private val data=PaymentData("Test kupac","Ulica 1","10000 Zagreb","Test primatelj","Ulica 2","10000 Zagreb","HR1210010051863000160",BigDecimal("12.35"),"Testna uplata")
    private fun pixels(matrix:BitMatrix):BinaryBitmap{
        val scale=4;val w=matrix.width*scale;val h=matrix.height*scale
        return BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w,h,IntArray(w*h){i->if(matrix.get((i%w)/scale,(i/w)/scale))0xff000000.toInt() else 0xffffffff.toInt()})))
    }
    @Test fun epcRoundTrip(){
        val payload=PaymentCodes.epc(data)
        assertTrue(payload.startsWith("BCD\n002\n1\nSCT\n"))
        assertTrue(payload.contains("EUR12.35"))
        assertEquals(payload,QRCodeReader().decode(pixels(PaymentCodes.qr(payload))).text)
    }
    @Test fun hubRoundTrip(){
        val payload=PaymentCodes.hub(data)
        assertTrue(payload.startsWith("HRVHUB30\nEUR\n000000000001235\n"))
        assertEquals(14,payload.trimEnd('\n').split('\n').size)
        assertEquals(payload,PDF417Reader().decode(pixels(PaymentCodes.pdf417(payload))).text)
    }
    @Test fun rejectsNewlineAndInvalidIban(){
        assertTrue(runCatching{PaymentCodes.hub(data.copy(payee="A\nB"))}.isFailure)
        assertTrue(runCatching{PaymentCodes.epc(data.copy(iban="HR0000000000000000000"))}.isFailure)
    }
}
