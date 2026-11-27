package hr.brendigo.fiskai

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.zxing.common.BitMatrix
import java.math.BigDecimal

@Composable
internal fun PaymentCodesScreen(business:BusinessProfile){
    var hub by remember{mutableStateOf(false)}
    var payer by remember{mutableStateOf("")}
    var payerAddress by remember{mutableStateOf("")}
    var payerCity by remember{mutableStateOf("")}
    var payee by remember{mutableStateOf(business.name)}
    var address by remember{mutableStateOf(business.address)}
    var city by remember{mutableStateOf(business.city)}
    var iban by remember{mutableStateOf(business.iban)}
    var amount by remember{mutableStateOf("")}
    var description by remember{mutableStateOf("")}
    var result by remember{mutableStateOf<Pair<PaymentData,Bitmap>?>(null)}
    var error by remember{mutableStateOf("")}
    fun invalidate(){result=null;error=""}
    Text("Platni kodovi",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
    Panel {
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            FilterChip(selected=!hub,onClick={hub=false;invalidate()},label={Text("EPC QR")})
            FilterChip(selected=hub,onClick={hub=true;invalidate()},label={Text("HUB-3")})
        }
        Text("Samostalna priprema podataka za plaćanje. Ne predstavlja izdani račun i ne pokreće uplatu.")
        if(hub){
            OutlinedTextField(payer,{payer=it;invalidate()},label={Text("Platitelj")},modifier=Modifier.fillMaxWidth(),singleLine=true)
            OutlinedTextField(payerAddress,{payerAddress=it;invalidate()},label={Text("Adresa platitelja")},modifier=Modifier.fillMaxWidth(),singleLine=true)
            OutlinedTextField(payerCity,{payerCity=it;invalidate()},label={Text("Poštanski broj i mjesto platitelja")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        }
        OutlinedTextField(payee,{payee=it;invalidate()},label={Text("Primatelj")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        if(hub){
            OutlinedTextField(address,{address=it;invalidate()},label={Text("Adresa primatelja")},modifier=Modifier.fillMaxWidth(),singleLine=true)
            OutlinedTextField(city,{city=it;invalidate()},label={Text("Poštanski broj i mjesto primatelja")},modifier=Modifier.fillMaxWidth(),singleLine=true)
            Text("Model HR99 — bez poziva na broj.")
        }
        OutlinedTextField(iban,{iban=it;invalidate()},label={Text("Hrvatski IBAN primatelja")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        OutlinedTextField(amount,{amount=it;invalidate()},label={Text("Iznos (€)")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        OutlinedTextField(description,{description=it;invalidate()},label={Text("Opis plaćanja")},modifier=Modifier.fillMaxWidth())
        Button(onClick={
            invalidate()
            val attempt=runCatching {
                val data=PaymentData(payer,payerAddress,payerCity,payee,address,city,iban,requireNotNull(parseDecimal(amount)),description)
                val code=if(hub)PaymentCodes.pdf417(PaymentCodes.hub(data)) else PaymentCodes.qr(PaymentCodes.epc(data))
                data to bitmap(code)
            }
            result=attempt.getOrNull()
            if(attempt.isFailure)error="Provjerite IBAN, iznos, obavezne podatke, duljinu i znakove u poljima."
        },modifier=Modifier.fillMaxWidth()){Text("Generiraj kod")}
        if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error)
    }
    result?.let{(data,bmp)->Panel{
        Text(if(hub)"HUB-3 PDF417" else "EPC QR",fontWeight=FontWeight.Bold)
        Text("Primatelj: "+data.payee)
        Text("IBAN: "+data.iban.filterNot(Char::isWhitespace))
        Text("Iznos: "+euro(data.amount))
        Text("Opis: "+data.description)
        if(hub){
            Text("Platitelj: "+data.payer)
            Text(data.payerAddress+" · "+data.payerCity)
            Text(data.payeeAddress+" · "+data.payeeCity)
            Text("Model HR99, bez poziva na broj")
        }
        Image(bitmap=bmp.asImageBitmap(),contentDescription="Platni barkod",modifier=Modifier.fillMaxWidth().height(if(hub)150.dp else 260.dp),filterQuality=FilterQuality.None)
        Text("Prije uplate usporedite podatke s prikazom bankovne aplikacije. Podrška ovisi o banci.")
    }}
}
private fun bitmap(matrix:BitMatrix):Bitmap {
    val scale=4
    val width=matrix.width*scale
    val height=matrix.height*scale
    val pixels=IntArray(width*height){index->if(matrix.get((index%width)/scale,(index/width)/scale))android.graphics.Color.BLACK else android.graphics.Color.WHITE}
    return Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888)
}
