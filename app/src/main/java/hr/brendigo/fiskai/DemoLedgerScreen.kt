package hr.brendigo.fiskai

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DemoLedgerScreen(drafts:List<Draft>,business:BusinessProfile,fiscalStore:FiscalSetupStore) {
    val context=LocalContext.current
    val ledger=remember(context){RoomDemoLedger(context.applicationContext)}
    var queueStates by remember{mutableStateOf<Map<String,String>>(emptyMap())}
    val scope=rememberCoroutineScope()
    var setup by remember{mutableStateOf<FiscalSetup?>(null)}
    var rows by remember{mutableStateOf<List<Pair<DemoReceipt,String>>>(emptyList())}
    var draft by remember{mutableStateOf<Draft?>(null)}
    var register by remember{mutableStateOf<Register?>(null)}
    var operator by remember{mutableStateOf<Operator?>(null)}
    var busy by remember{mutableStateOf(true)}
    var message by remember{mutableStateOf("")}
    var confirm by remember{mutableStateOf(false)}
    LaunchedEffect(ledger){
        val result=withContext(Dispatchers.IO){runCatching{fiscalStore.read() to ledger.history().map{it to ledger.total(it)}}}
        result.onSuccess{setup=it.first;rows=it.second}.onFailure{message="Pohrana nije dostupna."}
        queueStates=withContext(Dispatchers.IO){runCatching{rows.mapNotNull{(row,_)->ledger.status(row.id)?.let{row.id to it}}.toMap()}.getOrDefault(emptyMap())}
        busy=false
    }
    Text("DEMO računi",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
    Panel {
        Text("Testno numeriranje",fontWeight=FontWeight.Bold)
        Text("Lokalna snimka nacrta. Nije izdani račun, ne šalje se Poreznoj upravi. Koristite isključivo testne podatke.")
        val config=setup
        if(config!=null){
            val available=drafts.filter{d->rows.none{it.first.draftId==d.id}}
            DirectoryPicker(draft?.customer ?: "Odaberi nacrt",available.map{it.customer+" · "+euro(it.total)}){draft=available[it]}
            DirectoryPicker(register?.let{it.premise+"/"+it.number} ?: "Odaberi uređaj",config.registers.map{it.premise+"/"+it.number}){register=config.registers[it]}
            DirectoryPicker(operator?.name ?: "Odaberi operatera",config.operators.map{it.name}){operator=config.operators[it]}
            Button(enabled=!busy&&draft!=null&&register!=null&&operator!=null&&validOib(business.oib),onClick={confirm=true},modifier=Modifier.fillMaxWidth()){Text("Zaključaj DEMO snimku")}
        }
        if(!validOib(business.oib)) Text("Najprije postavite testni poslovni subjekt.")
        if(busy) LinearProgressIndicator(modifier=Modifier.fillMaxWidth())
        if(message.isNotEmpty()) Text(message)
    }
    if(confirm) AlertDialog(onDismissRequest={confirm=false},title={Text("Zaključati testnu snimku?")},text={Text("Broj i sadržaj snimke više se ne mogu mijenjati kroz aplikaciju.")},confirmButton={TextButton(onClick={
        confirm=false
        val d=draft;val r=register;val o=operator;val s=setup
        if(d!=null&&r!=null&&o!=null&&s!=null){
            busy=true
            scope.launch {
                try{
                    val result=withContext(Dispatchers.IO){runCatching{ledger.freeze(d,business,s,r,o);val updated=ledger.history().map{it to ledger.total(it)};updated to updated.mapNotNull{(row,_)->ledger.status(row.id)?.let{row.id to it}}.toMap()}}
                    result.onSuccess{rows=it.first;queueStates=it.second;draft=null;message="DEMO snimka spremljena."}.onFailure{message="Spremanje nije potvrđeno. Ponovno otvorite zaslon."}
                }finally{busy=false}
            }
        }
    }){Text("Potvrdi")}},dismissButton={TextButton(onClick={confirm=false}){Text("Odustani")}})
    Text("Zaključane snimke",fontWeight=FontWeight.Bold)
    rows.forEach{(row,total)->Panel{
        Text("DEMO "+row.number+"/"+row.premise+"/"+row.register,fontWeight=FontWeight.Bold)
        Text(row.year.toString()+" · "+total+" EUR")
        Text("Nije fiskalizirano")
        val state=queueStates[row.id]
        Text(when(state){"QUEUED"->"Lokalno pripremljeno – nije poslano";"ARCHIVED"->"Arhivirana testna snimka";"SENDING"->"Slanje nije potvrđeno";"UNCERTAIN"->"Ishod nije potvrđen";else->"Status nije dostupan"})
    }}
}
