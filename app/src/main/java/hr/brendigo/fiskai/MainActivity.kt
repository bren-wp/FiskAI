package hr.brendigo.fiskai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
private fun euro(value: BigDecimal) = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("hr-HR")).format(value)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = DraftStore(this)
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Blue, background = Surface, surface = Color.White, onSurface = Navy)) { FiskApp(store) } }
    }
}

@Composable
private fun FiskApp(store: DraftStore) {
    var items by remember { mutableStateOf(store.read()) }
    var page by remember { mutableStateOf("Početna") }
    var kind by remember { mutableStateOf("Račun") }
    var selected by remember { mutableStateOf<Draft?>(null) }
    var error by remember { mutableStateOf("") }
    fun create(type: String) { kind = type; page = "Novi"; selected = null; error = "" }
    Scaffold(
        topBar = { Row(Modifier.fillMaxWidth().background(Navy).statusBarsPadding().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Fisk", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 26.sp)
            Text("AI", color = Color(0xFF48A3FF), fontWeight = FontWeight.Bold, fontSize = 26.sp)
            Spacer(Modifier.weight(1f)); Text(if(page == "Novi") "Novi dokument" else page, color = Color.White)
        } },
        bottomBar = { NavigationBar(containerColor = Color.White) {
            listOf("Početna" to "⌂", "Računi" to "≡", "Novi" to "+", "Ponude" to "▤", "Više" to "···").forEach { (label, glyph) ->
                NavigationBarItem(selected = page == label, onClick = { selected = null; error = ""; if(label == "Novi") create("Račun") else page = label }, icon = { Text(glyph, fontSize = 23.sp) }, label = { Text(label, fontSize = 11.sp) })
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
                }
                page == "Novi" -> key(kind) { DraftForm(kind, error) { draft ->
                    val updated = items + draft
                    if(store.save(updated)) { items = updated; page = if(kind == "Račun") "Računi" else "Ponude"; error = "" } else error = "Spremanje nije uspjelo."
                } }
                page == "Početna" -> {
                    Text("Pregled poslovanja", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
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
                else -> {
                    Text("Postavke", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Panel { Text("Lokalna pohrana"); Text("Nacrti se čuvaju na ovom uređaju. Fiskalizacija i eRačuni još nisu dostupni.") }
                    Panel { Text("FiskAI"); Text("Računi i ponude") }
                }
            }
        }
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
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
private fun DraftForm(kind: String, saveError: String, onSave: (Draft) -> Unit) {
    var customer by remember { mutableStateOf("") }
    var oib by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("1") }
    var price by remember { mutableStateOf("") }
    var vat by remember { mutableStateOf("25") }
    var lines by remember { mutableStateOf(listOf<Line>()) }
    var message by remember { mutableStateOf("") }
    Text(if(kind == "Račun") "Novi račun" else "Nova ponuda", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Panel {
        Text("Kupac", fontWeight = FontWeight.Bold)
        OutlinedTextField(customer, { customer = it }, label = { Text("Naziv kupca") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(oib, { oib = it }, label = { Text("OIB (ako je primjenjivo)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    }
    Panel {
        Text("Stavke", fontWeight = FontWeight.Bold)
        lines.forEachIndexed { index, line -> Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(line.description); Text(euro(line.total)) }
            TextButton(onClick = { lines = lines.filterIndexed { i, _ -> i != index } }) { Text("Ukloni") }
        } }
        OutlinedTextField(description, { description = it }, label = { Text("Naziv proizvoda ili usluge") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(quantity, { quantity = it }, label = { Text("Količina") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(price, { price = it }, label = { Text("Jedinična cijena bez PDV-a") }, modifier = Modifier.fillMaxWidth())
        Text("Stopa PDV-a (radni izračun)")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("0", "5", "13", "25").forEach { rate -> FilterChip(selected = vat == rate, onClick = { vat = rate }, label = { Text(rate + "%") }) }
        }
        OutlinedButton(onClick = {
            val q = parseDecimal(quantity); val p = parseDecimal(price)
            if(description.isBlank() || q == null || p == null || q.signum() <= 0 || p.signum() < 0 || q.precision() > 12 || p.precision() > 12 || q.scale() !in 0..4 || p.scale() !in 0..4) message = "Provjerite naziv, količinu i cijenu."
            else { lines = lines + Line(description.trim(), q, p, BigDecimal(vat)); description = ""; price = ""; quantity = "1"; message = "" }
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
