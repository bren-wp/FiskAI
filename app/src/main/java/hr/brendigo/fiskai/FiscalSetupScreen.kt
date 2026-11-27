package hr.brendigo.fiskai

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun FiscalSetupScreen(page: String, store: FiscalSetupStore) {
    var data by remember { mutableStateOf<FiscalSetup?>(null) }
    var error by remember(page) { mutableStateOf("") }
    var busy by remember { mutableStateOf(true) }
    var add by remember(page) { mutableStateOf(false) }
    var delete by remember(page) { mutableStateOf<Pair<String, () -> FiscalSetup>?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(store) {
        val loaded = withContext(Dispatchers.IO) { runCatching { store.read() } }
        data = loaded.getOrNull()
        if(loaded.isFailure) error = "Pohrana nije dostupna. Zapisi nisu obrisani."
        busy = false
    }
    fun save(next: FiscalSetup) {
        if(busy) return
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { store.save(next) }
            if(ok) { data = next; add = false; delete = null; error = "" }
            else error = "Spremanje nije uspjelo."
            busy = false
        }
    }
    Text(page,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
    Text("Lokalne postavke. Nisu prijavljene Poreznoj upravi.")
    val current = data
    if(current == null) {
        if(busy) CircularProgressIndicator()
        if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
        return
    }
    val premisePage = page == "Poslovni prostori"
    val registerPage = page == "Naplatni uređaji"
    val canAdd = !busy && (!registerPage || current.premises.isNotEmpty())
    Button(enabled=canAdd,onClick={ add=true; error="" },modifier=Modifier.fillMaxWidth()) {
        Text(if(premisePage) "+ Dodaj poslovni prostor" else if(registerPage) "+ Dodaj naplatni uređaj" else "+ Dodaj operatera")
    }
    if(registerPage && current.premises.isEmpty()) Text("Najprije dodajte poslovni prostor.")
    if(premisePage) {
        if(current.premises.isEmpty()) Text("Nema poslovnih prostora.")
        current.premises.forEach { item -> Panel {
            Text(item.name,fontWeight=FontWeight.Bold)
            Text("Oznaka: " + item.code)
            Text(item.address)
            Text("Naplatni uređaji: " + current.registers.count { it.premise == item.code })
            TextButton(enabled=!busy && current.registers.none { it.premise == item.code },onClick={
                delete = item.name to { current.withoutPremise(item.code) }
            }) { Text("Obriši") }
        } }
    } else if(registerPage) {
        if(current.registers.isEmpty()) Text("Nema naplatnih uređaja.")
        current.registers.forEach { item -> Panel {
            Text(item.name,fontWeight=FontWeight.Bold)
            Text("Poslovni prostor: " + item.premise)
            Text("Broj uređaja: " + item.number)
            TextButton(enabled=!busy,onClick={
                delete = item.name to { current.copy(registers=current.registers.filterNot { it == item }) }
            }) { Text("Obriši") }
        } }
    } else {
        if(current.operators.isEmpty()) Text("Nema operatera.")
        current.operators.forEach { item -> Panel {
            Text(item.name,fontWeight=FontWeight.Bold)
            Text("Oznaka: " + item.code)
            Text("OIB: " + item.oib)
            TextButton(enabled=!busy,onClick={
                delete = item.name to { current.copy(operators=current.operators.filterNot { it == item }) }
            }) { Text("Obriši") }
        } }
    }
    if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
    if(add) {
        var name by remember { mutableStateOf("") }
        var code by remember { mutableStateOf("") }
        var detail by remember { mutableStateOf("") }
        var premise by remember { mutableStateOf(current.premises.firstOrNull()?.code.orEmpty()) }
        AlertDialog(onDismissRequest={ if(!busy) add=false },title={Text("Novi zapis")},text={
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                if(registerPage) DirectoryPicker("Poslovni prostor: " + premise,current.premises.map { it.code }) { premise=current.premises[it].code }
                OutlinedTextField(code,{code=it},label={Text(if(registerPage) "Broj uređaja" else "Oznaka")},singleLine=true)
                OutlinedTextField(name,{name=it},label={Text("Naziv")},singleLine=true)
                if(!registerPage) OutlinedTextField(detail,{detail=it},label={Text(if(premisePage) "Adresa" else "OIB operatera")},singleLine=true)
                if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
            }
        },confirmButton={TextButton(enabled=!busy,onClick={
            val n=name.trim(); val c=code.trim(); val d=detail.trim()
            if(n.isBlank() || n.length>200 || d.length>250) error="Provjerite naziv i podatke."
            else {
                val next=runCatching {
                    when {
                        premisePage -> current.withPremise(Premise(c,n,d))
                        registerPage -> current.withRegister(Register(premise,c,n))
                        else -> current.withOperator(Operator(c,n,d))
                    }
                }
                next.onSuccess { save(it) }.onFailure { error="Provjerite oznaku, duplikate i obavezne podatke." }
            }
        }) {Text("Spremi")}},dismissButton={TextButton(enabled=!busy,onClick={add=false;error=""}) {Text("Odustani")}})
    }
    delete?.let { (name, action) ->
        AlertDialog(onDismissRequest={if(!busy) delete=null},title={Text("Obriši zapis?")},text={Text("Obrisati „" + name + "”?")},
            confirmButton={TextButton(enabled=!busy,onClick={runCatching(action).onSuccess{save(it)}.onFailure{error="Zapis je povezan s drugim podacima.";delete=null}}){Text("Obriši")}},
            dismissButton={TextButton(enabled=!busy,onClick={delete=null}){Text("Odustani")}})
    }
}
