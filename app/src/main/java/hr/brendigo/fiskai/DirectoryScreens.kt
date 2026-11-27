package hr.brendigo.fiskai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.math.BigDecimal

@Composable
internal fun DirectoryPicker(label: String, options: List<String>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text(label) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { index, name ->
                DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onPick(index) })
            }
        }
    }
}

@Composable
internal fun ContactScreen(supplier: Boolean, contacts: List<Contact>, onSave: (List<Contact>) -> Boolean) {
    var query by remember(supplier) { mutableStateOf("") }
    var editing by remember(supplier) { mutableStateOf<Contact?>(null) }
    var deleting by remember(supplier) { mutableStateOf<Contact?>(null) }
    var error by remember(supplier) { mutableStateOf("") }
    val title = if(supplier) "Dobavljači" else "Kupci"
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    OutlinedTextField(query, { query = it }, label = { Text("Pretraži po nazivu ili OIB-u") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Button(onClick = { editing = Contact(name = "", oib = "", email = "", supplier = supplier); error = "" }, modifier = Modifier.fillMaxWidth()) { Text(if(supplier) "+ Novi dobavljač" else "+ Novi kupac") }
    val shown = contacts.filter { it.supplier == supplier && (it.name.contains(query, true) || it.oib.contains(query)) }.sortedBy { it.name.lowercase() }
    if(shown.isEmpty()) Text("Nema zapisa.")
    shown.forEach { contact ->
        Panel {
            Text(contact.name, fontWeight = FontWeight.Bold)
            if(contact.oib.isNotEmpty()) Text("OIB: " + contact.oib)
            if(contact.email.isNotEmpty()) Text(contact.email)
            Row { TextButton(onClick = { editing = contact; error = "" }) { Text("Uredi") }; TextButton(onClick = { deleting = contact; error = "" }) { Text("Obriši") } }
        }
    }
    if(error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
    editing?.let { contact ->
        key(contact.id) {
            var name by remember { mutableStateOf(contact.name) }
            var oib by remember { mutableStateOf(contact.oib) }
            var email by remember { mutableStateOf(contact.email) }
            AlertDialog(onDismissRequest = { editing = null }, title = { Text(if(supplier) "Dobavljač" else "Kupac") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Naziv") }, singleLine = true)
                    OutlinedTextField(oib, { oib = it }, label = { Text("OIB") }, singleLine = true)
                    OutlinedTextField(email, { email = it }, label = { Text("E-pošta") }, singleLine = true)
                    if(error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                }
            }, confirmButton = { TextButton(onClick = {
                val n = name.trim(); val o = oib.trim(); val e = email.trim()
                error = when {
                    n.isBlank() || n.length > 200 -> "Unesite naziv do 200 znakova."
                    o.isNotEmpty() && !validOib(o) -> "OIB nije ispravan."
                    o.isNotEmpty() && contacts.any { it.id != contact.id && it.supplier == supplier && it.oib == o } -> "OIB već postoji u imeniku."
                    e.isNotEmpty() && (e.length > 254 || !android.util.Patterns.EMAIL_ADDRESS.matcher(e).matches()) -> "E-pošta nije ispravna."
                    else -> ""
                }
                if(error.isEmpty()) {
                    val updated = contacts.filterNot { it.id == contact.id } + contact.copy(name = n, oib = o, email = e)
                    if(onSave(updated)) editing = null else error = "Spremanje nije uspjelo."
                }
            }) { Text("Spremi") } }, dismissButton = { TextButton(onClick = { editing = null; error = "" }) { Text("Odustani") } })
        }
    }
    deleting?.let { contact -> DeleteDialog(contact.name, { deleting = null }) {
        if(!onSave(contacts.filterNot { it.id == contact.id })) error = "Brisanje nije uspjelo."
        deleting = null
    } }
}

@Composable
internal fun ProductScreen(products: List<Product>, onSave: (List<Product>) -> Boolean) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(CatalogKind.ALL) }
    var category by remember { mutableStateOf<String?>(null) }
    var vatFilter by remember { mutableStateOf<String?>(null) }
    var sort by remember { mutableStateOf(CatalogSort.NAME_ASC) }
    var filtersOpen by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Product?>(null) }
    var editing by remember { mutableStateOf<Product?>(null) }
    var deleting by remember { mutableStateOf<Product?>(null) }
    var error by remember { mutableStateOf("") }
    val blue=Color(0xFF005BEA)
    val categories=ProductCatalog.categories(products)
    val tabs=listOf("Svi artikli","Proizvodi","Usluge","Kategorije")
    TabRow(selectedTabIndex=filter.ordinal,containerColor=Color.White,contentColor=blue) {
        tabs.forEachIndexed { index,label ->
            Tab(selected=filter.ordinal==index,onClick={filter=CatalogKind.entries[index];category=null},text={Text(label,fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis)})
        }
    }
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(query,{query=it},placeholder={Text("Pretraži proizvode ili usluge…")},singleLine=true,modifier=Modifier.weight(1f))
        Box {
            OutlinedButton(onClick={filtersOpen=true}){Text("Filtri")}
            DropdownMenu(expanded=filtersOpen,onDismissRequest={filtersOpen=false}) {
                DropdownMenuItem(text={Text("Sve stope PDV-a")},onClick={vatFilter=null;filtersOpen=false})
                listOf("0","5","13","25").forEach{rate->DropdownMenuItem(text={Text("PDV $rate%")},onClick={vatFilter=rate;filtersOpen=false})}
                DropdownMenuItem(text={Text("Ukloni filtre")},onClick={vatFilter=null;category=null;query="";filtersOpen=false})
            }
        }
    }
    if(vatFilter!=null||category!=null)Text("Filtri: "+listOfNotNull(category,vatFilter?.let{"PDV $it%"}).joinToString(" · "),color=blue)
    val shown=ProductCatalog.select(products,filter,query,category,vatFilter,sort)
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Ukupno: "+shown.size+" artikala",modifier=Modifier.weight(1f),fontSize=12.sp)
        Box(Modifier.weight(1f)) {
            val labels=listOf("Naziv (A – Ž)","Naziv (Ž – A)","Cijena ↑","Cijena ↓")
            DirectoryPicker(labels[sort.ordinal],labels){sort=CatalogSort.entries[it]}
        }
    }
    if(filter==CatalogKind.CATEGORIES&&category==null) {
        if(categories.isEmpty())Text("Još nema kategorija. Dodijelite kategoriju artiklu pri uređivanju.")
        categories.filter{it.first.contains(query,true)}.forEach{(name,count)->
            OutlinedButton(onClick={category=name;query=""},modifier=Modifier.fillMaxWidth()){Text("$name · $count")}
        }
    } else {
        if(shown.isEmpty())Text("Nema odgovarajućih artikala.")
        shown.forEach { product ->
            var actions by remember(product.id){mutableStateOf(false)}
            Row(Modifier.fillMaxWidth().background(Color.White).padding(vertical=10.dp,horizontal=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(44.dp).background(Color(0xFFE0EEFF),RoundedCornerShape(9.dp)),contentAlignment=Alignment.Center) {
                    Text(if(product.service)"U" else "P",color=blue,fontWeight=FontWeight.Bold)
                }
                Column(Modifier.weight(1f)) {
                    Text(product.name,fontWeight=FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text("Šifra: "+product.code,fontSize=12.sp,color=Color.DarkGray)
                }
                Column(horizontalAlignment=Alignment.End) {
                    Text("PDV "+product.vat.stripTrailingZeros().toPlainString()+"%",color=blue,fontSize=11.sp,modifier=Modifier.background(Color(0xFFE0EEFF),RoundedCornerShape(5.dp)).padding(horizontal=5.dp,vertical=2.dp))
                    Text(euro(product.price),fontWeight=FontWeight.Bold)
                    Text(listOfNotNull("bez PDV-a",UblDraft.units[product.unitCode]).joinToString(" / "),fontSize=10.sp)
                }
                Box {
                    IconButton(onClick={actions=true},modifier=Modifier.size(32.dp)){Text("⋮",fontSize=22.sp)}
                    DropdownMenu(expanded=actions,onDismissRequest={actions=false}) {
                        DropdownMenuItem(text={Text("Detalji")},onClick={actions=false;detail=product})
                        DropdownMenuItem(text={Text("Uredi")},onClick={actions=false;editing=product;error=""})
                        DropdownMenuItem(text={Text("Arhiviraj")},onClick={actions=false;deleting=product;error=""})
                    }
                }
            }
            HorizontalDivider(color=Color(0xFFE2E8F0))
        }
    }
    Button(onClick={editing=Product(name="",code="",price=BigDecimal.ZERO,vat=BigDecimal("25"),service=filter==CatalogKind.SERVICES,category=category?.takeUnless{it=="Bez kategorije"}.orEmpty());error=""},modifier=Modifier.fillMaxWidth()){Text("+ Dodaj proizvod ili uslugu")}
    detail?.let{product->
        AlertDialog(onDismissRequest={detail=null},title={Text(product.name)},text={
            Column {
                Text("Šifra: "+product.code)
                Text("Kategorija: "+product.category.ifEmpty{"Bez kategorije"})
                Text("Jedinica mjere: "+(UblDraft.units[product.unitCode]?:"Nije dodijeljena"))
                Text("KPD 2025: "+product.kpd.ifEmpty{"Nije dodijeljena"})
                Text("Cijena bez PDV-a: "+euro(product.price))
                product.retail?.let{r->
                    Text("Maloprodajna cijena: "+euro(r.gross))
                    Text("Bazna cijena: "+(r.base?.let(::euro)?:"Nije utvrđena"))
                    if(r.referenceDate.isNotEmpty())Text("Referentni datum: "+r.referenceDate)
                    if(r.special)Text("Poseban oblik prodaje")
                }
                Text("Prethodni zapisi cijena: "+product.priceHistory.size)
            }
        },confirmButton={TextButton(onClick={detail=null;editing=product;error=""}){Text("Uredi")}},dismissButton={TextButton(onClick={detail=null}){Text("Zatvori")}})
    }
    if(error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
    editing?.let { product ->
        key(product.id) {
            var name by remember { mutableStateOf(product.name) }
            var code by remember { mutableStateOf(product.code) }
            var price by remember { mutableStateOf(product.price.toPlainString()) }
            var vat by remember { mutableStateOf(product.vat.toPlainString()) }
            var service by remember { mutableStateOf(product.service) }
            var kpd by remember { mutableStateOf(product.kpd) }
            var categoryName by remember { mutableStateOf(product.category) }
            var unit by remember { mutableStateOf(product.unitCode) }
            var gross by remember { mutableStateOf(product.retail?.gross?.toPlainString().orEmpty()) }
            var basePrice by remember { mutableStateOf(product.retail?.base?.toPlainString().orEmpty()) }
            var reference by remember { mutableStateOf(product.retail?.referenceDate.orEmpty()) }
            var evidence by remember { mutableStateOf(product.retail?.evidence.orEmpty()) }
            var special by remember { mutableStateOf(product.retail?.special ?: false) }
            AlertDialog(onDismissRequest = { editing = null }, title = { Text("Artikl") }, text = {
                Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Naziv") }, singleLine = true)
                    OutlinedTextField(code, { code = it }, label = { Text("Šifra") }, singleLine = true)
                    OutlinedTextField(categoryName,{categoryName=it},label={Text("Kategorija")},singleLine=true)
                    if(categories.isNotEmpty())DirectoryPicker("Postojeće kategorije",categories.map{it.first}){categoryName=categories[it].first.takeUnless{it=="Bez kategorije"}.orEmpty()}
                    DirectoryPicker("Jedinica mjere: "+(UblDraft.units[unit]?:"Odaberi"),listOf("Nije dodijeljena")+UblDraft.units.values.toList()){unit=if(it==0)"" else UblDraft.units.keys.elementAt(it-1)}
                    OutlinedTextField(price, { price = it }, label = { Text("Cijena bez PDV-a (€)") }, singleLine = true)
                    DirectoryPicker("PDV: $vat%", listOf("0%", "5%", "13%", "25%")) { vat = listOf("0","5","13","25")[it] }
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = service, onCheckedChange = { service = it }); Text("Usluga") }
                    OutlinedTextField(kpd,{kpd=it},label={Text("KPD 2025 (XX.XX.XX)")},singleLine=true)
                    Text("Šifru provjerite u službenom KLASUS katalogu; format ne potvrđuje klasifikaciju.")
                    KpdLookup { kpd=it }
                    OutlinedTextField(gross,{gross=it},label={Text("Maloprodajna cijena (€)")},singleLine=true)
                    OutlinedTextField(basePrice,{basePrice=it},label={Text("Bazna cijena (€)")},singleLine=true)
                    OutlinedTextField(reference,{reference=it},label={Text("Referentni datum (GGGG-MM-DD)")},singleLine=true)
                    OutlinedTextField(evidence,{evidence=it},label={Text("Izvor / dokaz bazne cijene")})
                    Row(verticalAlignment=Alignment.CenterVertically){Checkbox(checked=special,onCheckedChange={special=it});Text("Poseban oblik prodaje")}
                    if(error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                }
            }, confirmButton = { TextButton(onClick = {
                val p = parseDecimal(price)
                val n = name.trim(); val c = code.trim()
                val retailResult=runCatching { parseRetail(gross,basePrice,reference,evidence,special) }
                error = when {
                    n.isBlank() || n.length > 200 || c.isBlank() || c.length > 64 -> "Unesite naziv i šifru."
                    categoryName.trim().length>80 || categoryName.any{it.isISOControl()} -> "Kategorija može sadržavati najviše 80 znakova."
                    kpd.isNotBlank() && normalizeKpd(kpd)==null -> "KPD mora imati šest znamenki."
                    retailResult.isFailure -> "Provjerite maloprodajnu i baznu cijenu, datum i dokaz."
                    products.any { it.id != product.id && it.code.equals(c, true) } -> "Šifra artikla već postoji."
                    p == null || p.signum() < 0 || p.precision() > 12 || p.scale() !in 0..4 -> "Cijena nije ispravna."
                    else -> ""
                }
                if(error.isEmpty() && p != null) {
                    if(onSave(products.filterNot { it.id == product.id } + product.withRetail(retailResult.getOrThrow()).copy(name = n, code = c, price = p, vat = BigDecimal(vat), service = service,kpd=normalizeKpd(kpd).orEmpty(),category=categoryName.trim(),unitCode=unit))) editing = null else error = "Spremanje nije uspjelo."
                }
            }) { Text("Spremi") } }, dismissButton = { TextButton(onClick = { editing = null; error = "" }) { Text("Odustani") } })
        }
    }
    deleting?.let { product -> AlertDialog(onDismissRequest={deleting=null},title={Text("Arhivirati artikl?")},text={Text("Artikl „"+product.name+"” više neće biti ponuđen u novim dokumentima. Povijesni zapisi ostaju nepromijenjeni.")},dismissButton={TextButton(onClick={deleting=null}){Text("Odustani")}},confirmButton={TextButton(onClick={
        if(!onSave(products.map { if(it.id==product.id) it.copy(archived=true) else it })) error = "Arhiviranje nije uspjelo."
        deleting = null
    }){Text("Arhiviraj")}}) }
}

@Composable
private fun DeleteDialog(name: String, close: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text("Obriši zapis?") }, text = { Text("Obrisati „$name”? Postojeći nacrti ostaju nepromijenjeni.") }, confirmButton = { TextButton(onClick = confirm) { Text("Obriši") } }, dismissButton = { TextButton(onClick = close) { Text("Odustani") } })
}
