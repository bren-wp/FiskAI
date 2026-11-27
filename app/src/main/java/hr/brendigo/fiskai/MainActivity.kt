package hr.brendigo.fiskai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

private val Navy = Color(0xFF0B1F3B)
private val Blue = Color(0xFF005BEA)
private val Surface = Color(0xFFF4F7FB)
internal fun euro(value: BigDecimal) = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("hr-HR")).format(value)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = DraftStore(this)
        val directory = DirectoryStore(this)
        val businessStore = BusinessStore(this)
        val fiscalStore = FiscalSetupStore(this)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Blue, background = Surface, surface = Color.White, onSurface = Navy)) {
                val loaded by produceState<Result<InitialData>?>(initialValue = null) {
                    value = withContext(Dispatchers.IO) {
                        runCatching { InitialData(store.read(), directory.contacts(), directory.products(), businessStore.read()) }
                    }
                }
                val result = loaded
                when {
                    result == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    result.isSuccess -> FiskApp(store, directory, businessStore, fiscalStore, result.getOrThrow()) { recreate() }
                    else -> Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
                        Text("Pohrana nije dostupna", style = MaterialTheme.typography.headlineSmall)
                        Text("Podaci nisu obrisani. Ponovno pokrenite aplikaciju. Ako se problem nastavi, obratite se podršci. Nemojte brisati podatke aplikacije.")
                    }
                }
            }
        }
    }
}

@Composable
private fun FiskApp(store: DraftStore, directory: DirectoryStore, businessStore: BusinessStore, fiscalStore: FiscalSetupStore, initial: InitialData, onRestored: () -> Unit) {
    var items by remember { mutableStateOf(initial.drafts) }
    var contacts by remember { mutableStateOf(initial.contacts) }
    var products by remember { mutableStateOf(initial.products) }
    var business by remember { mutableStateOf(initial.business) }
    var page by remember { mutableStateOf("Početna") }
    var backupBusy by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf("Račun") }
    var selected by remember { mutableStateOf<Draft?>(null) }
    var error by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var exportTarget by remember { mutableStateOf<Draft?>(null) }
    var exportStatus by remember { mutableStateOf("") }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val target = exportTarget
        exportTarget = null
        if(uri != null && target != null) {
            exporting = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.openOutputStream(uri, "wt")?.use { DraftPdf.write(target, it) } ?: error("Datoteka nije dostupna.") }
                }
                exporting = false
                exportStatus = if(result.isSuccess) "PDF nacrt je spremljen." else "Spremanje PDF-a nije uspjelo."
            }
        }
    }
    var ublTarget by remember { mutableStateOf<Pair<Draft,BusinessProfile>?>(null) }
    val ublPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/xml")) { uri ->
        val target=ublTarget
        ublTarget=null
        if(uri!=null && target!=null){
            exporting=true
            scope.launch {
                try {
                    val result=withContext(Dispatchers.IO){
                        runCatching {
                            val bytes=UblDraft.build(target.first,target.second)
                            context.contentResolver.openOutputStream(uri,"wt")?.use { it.write(bytes) } ?: error("Datoteka nije dostupna")
                        }
                    }
                    exportStatus=if(result.isSuccess)"Testni UBL nacrt spremljen." else "Izvoz nije uspio. Provjerite podatke."
                }finally{exporting=false}
            }
        }
    }
    fun create(type: String) { kind = type; page = "Novi"; selected = null; error = "" }
    Scaffold(
        topBar = { Row(Modifier.fillMaxWidth().background(Navy).statusBarsPadding().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Fisk", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 26.sp)
            Text("AI", color = Color(0xFF48A3FF), fontWeight = FontWeight.Bold, fontSize = 26.sp)
            if(BuildConfig.DEMO_MODE) Text(" DEMO",color=Color.White,fontWeight=FontWeight.Bold,fontSize=12.sp)
            Spacer(Modifier.weight(1f)); Text(if(page == "Novi") "Novi dokument" else if(page == "eRačuni") "Ulazni eRačuni" else page, color = Color.White)
        } },
        bottomBar = { NavigationBar(containerColor = Color.White) {
            listOf("Početna" to "⌂", "Računi" to "≡", "Novi" to "+", "eRačuni" to "✉", "Više" to "···").forEach { (label, glyph) ->
                NavigationBarItem(selected = page == label, enabled = !backupBusy, onClick = { selected = null; error = ""; if(label == "Novi") create("Račun") else page = label }, icon = { Text(glyph, fontSize = 23.sp) }, label = { Text(label, fontSize = 11.sp) })
            }
        } }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                selected != null -> {
                    val draft = selected!!
                    TextButton(onClick = { selected = null }) { Text("‹ Natrag") }
                    Text(draft.kind + " – nacrt", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Panel { Text(draft.customer); if(draft.oib.isNotBlank()) Text("OIB: " + draft.oib) }
                    draft.lines.forEach { line -> Panel { Text(line.description, fontWeight = FontWeight.Bold); Text(line.quantity.toPlainString() + " × " + euro(line.unitPrice)); Text(euro(line.total)) } }
                    Panel { Text("Ukupno", fontWeight = FontWeight.Bold); Text(euro(draft.total), fontSize = 26.sp) }
                    Text("Dokument nije izdan niti fiskaliziran.")
                    OutlinedButton(enabled = !exporting && exportTarget == null && ublTarget == null, onClick = {
                        exportStatus = ""
                        exportTarget = draft
                        pdfPicker.launch("FiskAI-nacrt-" + draft.id.take(8) + ".pdf")
                    }, modifier = Modifier.fillMaxWidth()) { Text(if(exporting) "Spremanje…" else "Izvezi PDF nacrt") }
                    if(draft.kind=="Račun") {
                        OutlinedButton(enabled=!exporting && exportTarget==null && ublTarget==null && UblDraft.canExport(draft,business),onClick={
                            exportStatus=""
                            ublTarget=draft to business
                            ublPicker.launch("FiskAI-TEST-UBL-"+draft.id.take(8)+".xml")
                        },modifier=Modifier.fillMaxWidth()) { Text("Izvezi testni UBL nacrt") }
                        Text("Za UBL nacrt potrebni su OIB kupca i prodavatelja, naziv i adresa prodavatelja, KPD i jedinica mjere svake stavke te podržane stope PDV-a. XML nije izdan eRačun.")
                    }
                    if(exportStatus.isNotEmpty()) Text(exportStatus)
                }
                page == "Novi" -> key(kind) { DraftForm(kind, error, contacts.filter { !it.supplier }, products.filterNot { it.archived }) { draft ->
                    val updated = items + draft
                    if(store.save(updated)) { items = updated; page = if(kind == "Račun") "Računi" else "Ponude"; error = "" } else error = "Spremanje nije uspjelo."
                } }
                page == "Početna" -> {
                    Text("Pregled poslovanja", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    if(business.name.isNotBlank()) Text(business.name)
                    Panel { Text("Vrijednost nacrta"); Text(euro(items.fold(BigDecimal.ZERO) { a, d -> a + d.total }), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Blue); Text("Nije ostvareni promet", color = Color.DarkGray) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { create("Račun") }, modifier = Modifier.weight(1f)) { Text("Novi račun") }
                        OutlinedButton(onClick = { create("Ponuda") }, modifier = Modifier.weight(1f)) { Text("Nova ponuda") }
                    }
                    Text("Posljednji nacrti", fontWeight = FontWeight.Bold)
                    if(items.isEmpty()) Text("Još nema dokumenata.")
                    items.takeLast(5).reversed().forEach { DraftRow(it) { selected = it } }
                }
                page == "Računi" || page == "Ponude" -> {
                    var search by remember(page) { mutableStateOf("") }
                    val type = if(page == "Računi") "Račun" else "Ponuda"
                    Text(page, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    OutlinedTextField(search, { search = it }, label = { Text("Pretraži nacrte") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Button(onClick = { create(type) }, modifier = Modifier.fillMaxWidth()) { Text(if(type == "Račun") "+ Novi račun" else "+ Nova ponuda") }
                    val filtered = items.filter { it.kind == type && (it.customer.contains(search, true) || it.oib.contains(search)) }
                    if(filtered.isEmpty()) Text("Nema odgovarajućih nacrta.")
                    filtered.reversed().forEach { DraftRow(it) { selected = it } }
                }
                page == "eRačuni" -> IncomingInvoiceScreen(business,items,{selected=it},{backupBusy=it})
                page == "Kupci" || page == "Dobavljači" -> ContactScreen(
                    supplier = page == "Dobavljači", contacts = contacts,
                    onSave = { next -> directory.saveContacts(next).also { if(it) contacts = next } }
                )
                page == "Proizvodi i usluge" -> ProductScreen(products) { next ->
                    directory.saveProducts(next).also { if(it) products = next }
                }
                page == "Poslovni prostori" || page == "Naplatni uređaji" || page == "Operateri" -> FiscalSetupScreen(page, fiscalStore)
                page == "Platni kodovi" -> PaymentCodesScreen(business)
                page == "Sigurnosna kopija" -> WorkspaceBackupScreen({ backupBusy=it },onRestored)
                page == "Skladište" -> InventoryScreen(products.filter { !it.service && !it.archived })
                page == "POS blagajna" -> PosDraftScreen(products.filterNot { it.archived }, contacts.filterNot { it.supplier }) { draft ->
                    val updated=items+draft
                    store.save(updated).also { if(it) { items=updated;page="Računi" } }
                }
                page == "DEMO računi" -> DemoLedgerScreen(items.filter { it.kind == "Račun" }, business, fiscalStore)
                page == "Test fiskalizacije" -> CisTestScreen()
                page == "Certifikati" -> CertificateScreen(business.oib)
                page == "Poslovni subjekt" -> BusinessScreen(business) { next ->
                    businessStore.save(next).also { if(it) business = next }
                }
                else -> {
                    Text("Više", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    listOf("Ponude", "Kupci", "Dobavljači", "Proizvodi i usluge", "Poslovni subjekt", "Poslovni prostori", "Naplatni uređaji", "Operateri", "Certifikati", "Test fiskalizacije", "DEMO računi", "POS blagajna", "Skladište", "Platni kodovi", "Sigurnosna kopija").forEach { destination ->
                        OutlinedButton(onClick = { page = destination }, modifier = Modifier.fillMaxWidth()) { Text(destination) }
                    }
                    Panel { Text("Lokalna pohrana"); Text("Lokalni zapisi zaštićeni su ključem ovog uređaja. Fiskalno slanje i povezivanje s informacijskim posrednikom još nisu dostupni.") }
                    Panel { Text("FiskAI"); Text("Računi i ponude") }
                }
            }
        }
    }
}

@Composable
internal fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}
@Composable
private fun DraftRow(draft: Draft, open: () -> Unit) {
    Card(onClick = open, colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(draft.customer, fontWeight = FontWeight.Bold); Text(draft.kind + " · Nacrt", color = Color.DarkGray) }
            Text(euro(draft.total), fontWeight = FontWeight.Bold)
        }
    }
}
@Composable
private fun DraftForm(kind: String, saveError: String, contacts: List<Contact>, products: List<Product>, onSave: (Draft) -> Unit) {
    var customer by remember { mutableStateOf("") }
    var oib by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("1") }
    var price by remember { mutableStateOf("") }
    var vat by remember { mutableStateOf("25") }
    var kpd by remember { mutableStateOf("") }
    var unitCode by remember { mutableStateOf("") }
    var lines by remember { mutableStateOf(listOf<Line>()) }
    var message by remember { mutableStateOf("") }
    Text(if(kind == "Račun") "Novi račun" else "Nova ponuda", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Panel {
        Text("Kupac", fontWeight = FontWeight.Bold)
        if(contacts.isNotEmpty()) DirectoryPicker("Odaberi kupca", contacts.map { it.name }) { index -> customer = contacts[index].name; oib = contacts[index].oib }
        OutlinedTextField(customer, { customer = it }, label = { Text("Naziv kupca") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(oib, { oib = it }, label = { Text("OIB (ako je primjenjivo)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    }
    Panel {
        Text("Stavke", fontWeight = FontWeight.Bold)
        lines.forEachIndexed { index, line -> Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(line.description); Text(euro(line.total)) }
            TextButton(onClick = { lines = lines.filterIndexed { i, _ -> i != index } }) { Text("Ukloni") }
        } }
        if(products.isNotEmpty()) DirectoryPicker("Odaberi artikl", products.map { it.name }) { index -> val product = products[index]; description = product.name; price = product.price.toPlainString(); vat = product.vat.toPlainString(); kpd=product.kpd;unitCode=product.unitCode }
        OutlinedTextField(description, { description = it }, label = { Text("Naziv proizvoda ili usluge") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(kpd,{kpd=it},label={Text("KPD 2025 (neobavezno za nacrt)")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        DirectoryPicker("Jedinica mjere: "+(UblDraft.units[unitCode] ?: "Odaberi"),UblDraft.units.values.toList()){unitCode=UblDraft.units.keys.elementAt(it)}
        OutlinedTextField(quantity, { quantity = it }, label = { Text("Količina") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(price, { price = it }, label = { Text("Jedinična cijena bez PDV-a") }, modifier = Modifier.fillMaxWidth())
        Text("Stopa PDV-a (radni izračun)")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("0", "5", "13", "25").forEach { rate -> FilterChip(selected = vat == rate, onClick = { vat = rate }, label = { Text(rate + "%") }) }
        }
        OutlinedButton(onClick = {
            val q = parseDecimal(quantity); val p = parseDecimal(price)
            if(description.isBlank() || q == null || p == null || q.signum() <= 0 || p.signum() < 0 || q.precision() > 12 || p.precision() > 12 || q.scale() !in 0..4 || p.scale() !in 0..4) message = "Provjerite naziv, količinu i cijenu."
            else if(kpd.isNotBlank() && normalizeKpd(kpd)==null) message="KPD mora imati šest znamenki."
            else { lines = lines + Line(description.trim(), q, p, BigDecimal(vat),normalizeKpd(kpd).orEmpty(),unitCode); description = ""; price = ""; quantity = "1"; kpd=""; unitCode=""; message = "" }
        }, modifier = Modifier.fillMaxWidth()) { Text("+ Dodaj stavku") }
    }
    Panel {
        Text("Osnovica: " + euro(lines.fold(BigDecimal.ZERO) { a, l -> a + l.net }))
        Text("PDV: " + euro(lines.fold(BigDecimal.ZERO) { a, l -> a + l.vat }))
        Text("Ukupno: " + euro(lines.fold(BigDecimal.ZERO) { a, l -> a + l.total }), fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Blue)
    }
    if(message.isNotEmpty() || saveError.isNotEmpty()) Text(message.ifEmpty { saveError }, color = MaterialTheme.colorScheme.error)
    Button(onClick = {
        if(customer.isBlank() || lines.isEmpty() || (oib.isNotBlank() && !validOib(oib))) message = "Unesite kupca, ispravan OIB (ako je unesen) i barem jednu stavku."
        else if(description.isNotBlank() || price.isNotBlank()) message = "Dodajte ili očistite nedovršenu stavku."
        else onSave(Draft(kind = kind, customer = customer.trim(), oib = oib, lines = lines))
    }, modifier = Modifier.fillMaxWidth()) { Text("Spremi nacrt") }
}
