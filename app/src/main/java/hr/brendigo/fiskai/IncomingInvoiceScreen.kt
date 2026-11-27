package hr.brendigo.fiskai

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal

@Composable
internal fun IncomingInvoiceScreen(business:BusinessProfile,drafts:List<Draft>,openDraft:(Draft)->Unit,onBusy:(Boolean)->Unit) {
    val context=LocalContext.current
    val store=remember(context){IncomingInvoiceStore(context.applicationContext)}
    val scope=rememberCoroutineScope()
    var rows by remember{mutableStateOf(emptyList<IncomingInvoice>())}
    var outgoing by remember{mutableStateOf(false)}
    var query by remember{mutableStateOf("")}
    var filter by remember{mutableStateOf<String?>(null)}
    var filterOpen by remember{mutableStateOf(false)}
    var selected by remember{mutableStateOf<IncomingInvoice?>(null)}
    var busy by remember{mutableStateOf(true)}
    var message by remember{mutableStateOf("")}
    fun working(value:Boolean){busy=value;onBusy(value)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri==null){working(false);return@rememberLauncherForActivityResult}
        scope.launch{
            try{
                val result=withContext(Dispatchers.IO){runCatching{
                    val bytes=context.contentResolver.openInputStream(uri)?.use(IncomingUbl::read)?:error("Missing file")
                    val added=store.import(bytes,business.oib)
                    added to store.read()
                }}
                result.onSuccess{rows=it.second;message=if(it.first)"XML je lokalno uvezen." else "Račun je već uvezen."}
                    .onFailure{message="Uvoz nije uspio. Provjerite XML, OIB primatelja, valutu i mogući duplikat."}
            }finally{working(false)}
        }
    }
    LaunchedEffect(store){working(true);try{
        withContext(Dispatchers.IO){runCatching{store.read()}}.onSuccess{rows=it}.onFailure{message="Pohrana ulaznih računa nije dostupna."}
    }finally{working(false)}}
    val labels=mapOf("RECEIVED" to "Zaprimljeno","REVIEW" to "U obradi","REJECTED" to "Odbijeno")
    val colors=mapOf("RECEIVED" to Color(0xFFDDF5E7),"REVIEW" to Color(0xFFFFF3D0),"REJECTED" to Color(0xFFFCE4E6))
    TabRow(selectedTabIndex=if(outgoing)1 else 0){
        Tab(selected=!outgoing,onClick={outgoing=false},text={Text("Ulazni eRačuni",fontSize=12.sp)})
        Tab(selected=outgoing,onClick={outgoing=true},text={Text("Izlazni eRačuni",fontSize=12.sp)})
    }
    if(outgoing){
        Text("Izlazni nacrti",fontWeight=FontWeight.Bold)
        Text("Nisu poslani niti izdani eRačuni.",fontSize=12.sp)
        val list=drafts.filter{it.kind=="Račun"}
        if(list.isEmpty())Text("Nema nacrta.")
        list.reversed().forEach{d->OutlinedCard(onClick={openDraft(d)},modifier=Modifier.fillMaxWidth()){
            Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){Text(d.customer,Modifier.weight(1f));Text(euro(d.total))}
        }}
    }else{
        val own=rows.filter{it.buyerOib==business.oib}
        Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){
            (listOf(null)+labels.keys).forEach{status->
                Column(Modifier.weight(1f).background(colors[status]?:Color(0xFFE9EFFB),RoundedCornerShape(8.dp)).padding(vertical=10.dp),horizontalAlignment=Alignment.CenterHorizontally){
                    Text((if(status==null)own.size else own.count{it.status==status}).toString(),fontSize=20.sp,fontWeight=FontWeight.Bold)
                    Text(labels[status]?:"Ukupno",fontSize=10.sp,maxLines=1)
                }
            }
        }
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
            OutlinedTextField(query,{query=it},placeholder={Text("Dobavljač, broj računa…",fontSize=12.sp)},singleLine=true,modifier=Modifier.weight(1f))
            Box{
                OutlinedButton(onClick={filterOpen=true}){Text("Filtriraj",fontSize=12.sp)}
                DropdownMenu(expanded=filterOpen,onDismissRequest={filterOpen=false}){
                    DropdownMenuItem(text={Text("Svi statusi")},onClick={filter=null;filterOpen=false})
                    labels.forEach{(key,value)->DropdownMenuItem(text={Text(value)},onClick={filter=key;filterOpen=false})}
                }
            }
        }
        filter?.let{Text("Filtar: "+labels[it],fontSize=12.sp)}
        val shown=own.filter{(filter==null||it.status==filter)&&listOf(it.supplier,it.number,it.supplierOib).any{v->v.contains(query.trim(),true)}}.sortedByDescending{it.date}
        if(shown.isEmpty())Text("Nema ulaznih računa za prikaz.")
        shown.forEach{r->
            OutlinedCard(onClick={selected=r},modifier=Modifier.fillMaxWidth()){
                Row(Modifier.padding(10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    Box(Modifier.size(38.dp).background(Color(0xFFE5F0FF),RoundedCornerShape(8.dp)),contentAlignment=Alignment.Center){Text("▤",color=Color(0xFF005BEA),fontSize=22.sp)}
                    Column(Modifier.weight(1f)){
                        Text(r.supplier,fontWeight=FontWeight.Bold)
                        Text("Račun: "+r.number,fontSize=11.sp)
                        Text("Datum: "+r.date.split("-").reversed().joinToString(".")+".",fontSize=11.sp)
                    }
                    Column(horizontalAlignment=Alignment.End){
                        Text(euro(r.amount),fontWeight=FontWeight.Bold)
                        Text(labels.getValue(r.status),fontSize=10.sp,modifier=Modifier.background(colors.getValue(r.status),RoundedCornerShape(5.dp)).padding(4.dp))
                    }
                    Text("›",fontSize=22.sp)
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(Color(0xFFE9EFFB),RoundedCornerShape(8.dp)).padding(12.dp),verticalAlignment=Alignment.CenterVertically){
            Text("Ukupan iznos ("+shown.size+")",Modifier.weight(1f))
            Text(euro(shown.fold(BigDecimal.ZERO){a,r->a+r.amount}),fontWeight=FontWeight.Bold)
        }
        Button(enabled=!busy&&validOib(business.oib),onClick={working(true);picker.launch(arrayOf("application/xml","text/xml","application/octet-stream"))},modifier=Modifier.fillMaxWidth()){Text("Uvezi XML račun")}
        if(!validOib(business.oib))Text("Postavite OIB poslovnog subjekta prije uvoza.")
        Text("Lokalni statusi obrade. Nisu potvrde informacijskog posrednika niti eIzvještavanje. Iznos je preuzet iz XML-a, bez potvrde porezne ispravnosti.",fontSize=11.sp)
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(message.isNotEmpty())Text(message)
    }
    selected?.let{r->
        AlertDialog(onDismissRequest={selected=null},title={Text(r.supplier)},text={
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text("OIB: "+r.supplierOib);Text("Račun: "+r.number);Text("Datum: "+r.date);Text(euro(r.amount))
                Text("Lokalna obrada",fontWeight=FontWeight.Bold)
                labels.forEach{(state,label)->OutlinedButton(enabled=!busy,onClick={
                    working(true)
                    scope.launch{try{
                        val result=withContext(Dispatchers.IO){runCatching{store.review(r.key,state);store.read()}}
                        result.onSuccess{rows=it;selected=null}.onFailure{message="Promjena statusa nije spremljena.";selected=null}
                    }finally{working(false)}}
                },modifier=Modifier.fillMaxWidth()){Text(label)}}
            }
        },confirmButton={TextButton(onClick={selected=null}){Text("Zatvori")}})
    }
}
