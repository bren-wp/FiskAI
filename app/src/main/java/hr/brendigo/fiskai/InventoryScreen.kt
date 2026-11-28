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
import java.util.UUID
import java.text.DateFormat
import java.util.Date

@Composable
internal fun InventoryScreen(products:List<Product>){
    val context=LocalContext.current
    val inventory=remember(context){Inventory(context.applicationContext)}
    val scope=rememberCoroutineScope()
    var balances by remember{mutableStateOf<Map<String,Long>>(emptyMap())}
    var history by remember{mutableStateOf<List<StockEvent>>(emptyList())}
    var ready by remember{mutableStateOf(false)}
    var busy by remember{mutableStateOf(true)}
    var selected by remember{mutableStateOf<Product?>(null)}
    var quantity by remember{mutableStateOf("")}
    var reason by remember{mutableStateOf("")}
    var incoming by remember{mutableStateOf(true)}
    var message by remember{mutableStateOf("")}
    var confirm by remember{mutableStateOf(false)}
    LaunchedEffect(inventory){
        val r=withContext(Dispatchers.IO){runCatching{inventory.verifiedSnapshot()}}
        r.onSuccess{balances=it.balances;history=it.recent;ready=true}.onFailure{message="Skladišna evidencija nije dostupna."}
        busy=false
    }
    Text("Skladište",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
    Panel {
        Text("Stanje zaliha",fontWeight=FontWeight.Bold)
        if(ready&&products.isEmpty())Text("Najprije dodajte proizvode u katalog.")
        if(ready)products.forEach{p->Text(p.name+" · "+StockQuantity.decode(balances[p.id]?:0).toPlainString())}
    }
    Panel{
        Text("Novo kretanje",fontWeight=FontWeight.Bold)
        DirectoryPicker(selected?.name ?: "Odaberi proizvod",products.map{it.name}){selected=products[it]}
        Row {
            FilterChip(selected=incoming,onClick={incoming=true},label={Text("Ulaz")})
            Spacer(Modifier.weight(1f))
            FilterChip(selected=!incoming,onClick={incoming=false},label={Text("Izlaz")})
        }
        OutlinedTextField(quantity,{quantity=it},label={Text("Količina")},enabled=!busy,singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(reason,{reason=it},label={Text("Dokument ili razlog kretanja")},enabled=!busy,modifier=Modifier.fillMaxWidth())
        Button(enabled=ready&&!busy&&selected!=null&&quantity.isNotBlank()&&reason.isNotBlank(),onClick={confirm=true},modifier=Modifier.fillMaxWidth()){Text("Knjiži kretanje")}
        if(busy)LinearProgressIndicator(modifier=Modifier.fillMaxWidth())
        if(message.isNotEmpty())Text(message)
        Text("Lokalno skladište. Nacrti računa ne mijenjaju stanje zaliha automatski.")
    }
    if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("Potvrditi kretanje?")},text={Text("Kretanje se ne može brisati. Ispravak se knjiži kao novo kretanje.")},confirmButton={TextButton(onClick={
        confirm=false
        val p=selected
        val q=parseDecimal(quantity)
        if(p==null||q==null){message="Provjerite količinu."}
        else {
            val id=UUID.randomUUID().toString();val note=reason;val direction=incoming
            busy=true
            scope.launch{
                try{
                    val r=withContext(Dispatchers.IO){runCatching{inventory.post(id,p,q,direction,note);inventory.verifiedSnapshot()}}
                    r.onSuccess{balances=it.balances;history=it.recent;quantity="";reason="";message="Kretanje je spremljeno."}.onFailure{ready=false;message="Knjiženje nije potvrđeno. Provjerite stanje i ponovno otvorite zaslon prije novog unosa."}
                }finally{busy=false}
            }
        }
    }){Text("Potvrdi")}},dismissButton={TextButton(onClick={confirm=false}){Text("Odustani")}})
    Text("Povijest kretanja",fontWeight=FontWeight.Bold)
    history.forEach{event->Panel{
        Text(event.label,fontWeight=FontWeight.Bold)
        Text((if(event.delta>0)"+" else "")+StockQuantity.decode(event.delta).toPlainString()+" · stanje "+StockQuantity.decode(event.after).toPlainString())
        Text(event.reason)
        Text(DateFormat.getDateTimeInstance().format(Date(event.created)))
    }}
}
