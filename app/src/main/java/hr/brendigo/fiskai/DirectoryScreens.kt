package hr.brendigo.fiskai

import androidx.compose.foundation.layout.*
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
    var filter by remember { mutableStateOf("Svi") }
    var editing by remember { mutableStateOf<Product?>(null) }
    var deleting by remember { mutableStateOf<Product?>(null) }
    var error by remember { mutableStateOf("") }
    Text("Proizvodi i usluge", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Svi", "Proizvodi", "Usluge").forEach { item -> FilterChip(selected = filter == item, onClick = { filter = item }, label = { Text(item) }) }
    }
    OutlinedTextField(query, { query = it }, label = { Text("Pretraži proizvode i usluge") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    Button(onClick = { editing = Product(name = "", code = "", price = BigDecimal.ZERO, vat = BigDecimal("25"), service = false); error = "" }, modifier = Modifier.fillMaxWidth()) { Text("+ Dodaj proizvod ili uslugu") }
    val shown = products.filter { (filter == "Svi" || (filter == "Usluge") == it.service) && (it.name.contains(query, true) || it.code.contains(query, true)) }.sortedBy { it.name.lowercase() }
    if(shown.isEmpty()) Text("Nema artikala.")
    shown.forEach { product -> Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(product.name, fontWeight = FontWeight.Bold); Text(product.code + " · " + if(product.service) "Usluga" else "Proizvod") }
            Text(euro(product.price), fontWeight = FontWeight.Bold)
        }
        Text("PDV: " + product.vat.toPlainString() + "% · Cijena bez PDV-a")
        Row { TextButton(onClick = { editing = product; error = "" }) { Text("Uredi") }; TextButton(onClick = { deleting = product; error = "" }) { Text("Obriši") } }
    } }
    if(error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
    editing?.let { product ->
        key(product.id) {
            var name by remember { mutableStateOf(product.name) }
            var code by remember { mutableStateOf(product.code) }
            var price by remember { mutableStateOf(product.price.toPlainString()) }
            var vat by remember { mutableStateOf(product.vat.toPlainString()) }
            var service by remember { mutableStateOf(product.service) }
            AlertDialog(onDismissRequest = { editing = null }, title = { Text("Artikl") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Naziv") }, singleLine = true)
                    OutlinedTextField(code, { code = it }, label = { Text("Šifra") }, singleLine = true)
                    OutlinedTextField(price, { price = it }, label = { Text("Cijena bez PDV-a (€)") }, singleLine = true)
                    DirectoryPicker("PDV: $vat%", listOf("0%", "5%", "13%", "25%")) { vat = listOf("0","5","13","25")[it] }
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = service, onCheckedChange = { service = it }); Text("Usluga") }
                    if(error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                }
            }, confirmButton = { TextButton(onClick = {
                val p = parseDecimal(price)
                val n = name.trim(); val c = code.trim()
                error = when {
                    n.isBlank() || n.length > 200 || c.isBlank() || c.length > 64 -> "Unesite naziv i šifru."
                    products.any { it.id != product.id && it.code.equals(c, true) } -> "Šifra artikla već postoji."
                    p == null || p.signum() < 0 || p.precision() > 12 || p.scale() !in 0..4 -> "Cijena nije ispravna."
                    else -> ""
                }
                if(error.isEmpty() && p != null) {
                    if(onSave(products.filterNot { it.id == product.id } + product.copy(name = n, code = c, price = p, vat = BigDecimal(vat), service = service))) editing = null else error = "Spremanje nije uspjelo."
                }
            }) { Text("Spremi") } }, dismissButton = { TextButton(onClick = { editing = null; error = "" }) { Text("Odustani") } })
        }
    }
    deleting?.let { product -> DeleteDialog(product.name, { deleting = null }) {
        if(!onSave(products.filterNot { it.id == product.id })) error = "Brisanje nije uspjelo."
        deleting = null
    } }
}

@Composable
private fun DeleteDialog(name: String, close: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text("Obriši zapis?") }, text = { Text("Obrisati „$name”? Postojeći nacrti ostaju nepromijenjeni.") }, confirmButton = { TextButton(onClick = confirm) { Text("Obriši") } }, dismissButton = { TextButton(onClick = close) { Text("Odustani") } })
}
