package hr.brendigo.fiskai

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
internal fun AccountingOverviewScreen(business:BusinessProfile,onBusy:(Boolean)->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val today=remember{SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).apply{timeZone=TimeZone.getTimeZone("Europe/Zagreb")}.format(Date())}
    var from by remember{mutableStateOf(today.take(4)+"-01-01")}
    var until by remember{mutableStateOf(today)}
    var report by remember(business.oib){mutableStateOf<AccountingOverview.Report?>(null)}
    var pendingExport by remember{mutableStateOf<AccountingOverview.Report?>(null)}
    var busy by remember{mutableStateOf(false)}
    var message by remember{mutableStateOf("")}
    fun working(value:Boolean){busy=value;onBusy(value)}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")){uri->
        val current=pendingExport;pendingExport=null
        if(uri==null||current==null)working(false) else scope.launch {
            try {
                val result=withContext(Dispatchers.IO){runCatching{
                    val output=context.contentResolver.openOutputStream(uri,"wt")?:error("Missing destination")
                    output.use{it.write(current.csv().toByteArray(Charsets.UTF_8));it.flush()}
                }}
                message=if(result.isSuccess)"Pregled je spremljen." else "Izvoz nije uspio. Nepotpunu datoteku nemojte koristiti."
            }finally{working(false)}
        }
    }
    Text("Računovodstveni pregled",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
    if(!validOib(business.oib))Text("Najprije postavite OIB poslovnog subjekta.")
    Panel {
        OutlinedTextField(from,{from=it.take(10);report=null},label={Text("Od (GGGG-MM-DD)")},enabled=!busy,singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(until,{until=it.take(10);report=null},label={Text("Do (GGGG-MM-DD)")},enabled=!busy,singleLine=true,modifier=Modifier.fillMaxWidth())
        Button(enabled=!busy&&validOib(business.oib),onClick={
            val start=from;val end=until;val owner=business.oib
            report=null;message="";working(true)
            scope.launch{try{
                val result=withContext(Dispatchers.IO){runCatching{
                    val period=AccountingOverview.Period(start,end)
                    SecureRecords.withRecordLock {
                        AccountingOverview.summarize(owner,period,ReceiptStore(context).read(),IncomingInvoiceStore(context).read())
                    }
                }}
                result.onSuccess{report=it}.onFailure{message="Pregled nije dostupan. Provjerite razdoblje i pohranjene evidencije."}
            }finally{working(false)}}
        },modifier=Modifier.fillMaxWidth()){Text("Izračunaj pregled")}
    }
    report?.let{r->
        fun money(cents:Long)=euro(ReceiptRules.amount(cents))
        Panel{
            Text("Potvrđeni primici",fontWeight=FontWeight.Bold)
            Text(money(r.confirmed),style=MaterialTheme.typography.headlineMedium)
            Text("Gotovina: "+money(r.cash))
            Text("Bankovni primici: "+money(r.confirmedBank))
            Text("Broj primitaka: "+r.receipts)
        }
        Panel{
            Text("Usklađivanje banke",fontWeight=FontWeight.Bold)
            Text("Priljevi: "+money(r.bankIn))
            Text("Odljevi: "+money(r.bankOut))
            Text("Neto tok: "+money(r.bankNet))
            Text("Nepotvrđeni priljevi: "+money(r.unconfirmedBank)+" ("+r.pending+")")
        }
        Panel{
            Text("Ulazni računi",fontWeight=FontWeight.Bold)
            Text(money(r.incoming),style=MaterialTheme.typography.headlineSmall)
            Text("Neodbijeni računi: "+r.incomingCount+" · Na pregledu: "+r.reviewIncoming+" · Odbijeni: "+r.rejectedIncoming)
            Text("Iznos ulaznih računa nije dokaz plaćanja.")
        }
        Text("Informativni pregled iz lokalnih evidencija. Nije porezna prijava, knjigovodstveni obračun ni potvrda porezne obveze.")
        OutlinedButton(enabled=!busy,onClick={
            pendingExport=r;working(true);export.launch("FiskAI-pregled-"+r.period.from+"-"+r.period.until+".csv")
        },modifier=Modifier.fillMaxWidth()){Text("Izvezi sažetak CSV")}
    }
    if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
    if(message.isNotEmpty())Text(message)
}
