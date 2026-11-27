package hr.brendigo.fiskai

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight

@Composable
internal fun BusinessScreen(profile: BusinessProfile, onSave: (BusinessProfile) -> Boolean) {
    var name by remember(profile) { mutableStateOf(profile.name) }
    var oib by remember(profile) { mutableStateOf(profile.oib) }
    var address by remember(profile) { mutableStateOf(profile.address) }
    var city by remember(profile) { mutableStateOf(profile.city) }
    var iban by remember(profile) { mutableStateOf(profile.iban) }
    var vat by remember(profile) { mutableStateOf(profile.vatRegistered) }
    var message by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    Text("Poslovni subjekt", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Panel {
        Text("Osnovni podaci", fontWeight = FontWeight.Bold)
        OutlinedTextField(name,{name=it},label={Text("Naziv poslovnog subjekta")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        OutlinedTextField(oib,{oib=it},label={Text("OIB")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        OutlinedTextField(address,{address=it},label={Text("Adresa")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        OutlinedTextField(city,{city=it},label={Text("Poštanski broj i mjesto")},modifier=Modifier.fillMaxWidth(),singleLine=true)
    }
    Panel {
        Text("Porezni i platni podaci",fontWeight=FontWeight.Bold)
        Row(verticalAlignment=Alignment.CenterVertically) {
            Checkbox(checked=vat,onCheckedChange={vat=it})
            Text("U sustavu PDV-a")
        }
        OutlinedTextField(iban,{iban=it},label={Text("Hrvatski IBAN (neobavezno)")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        Text("Postavke su lokalne. Nisu prijavljene Poreznoj upravi niti određuju automatski porezni tretman nacrta.")
    }
    if(message.isNotEmpty()) Text(message,color=if(failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    Button(modifier=Modifier.fillMaxWidth(),onClick={
        val normalized = iban.filterNot(Char::isWhitespace).uppercase(java.util.Locale.ROOT)
        message = when {
            name.isBlank() || name.length > 200 -> "Unesite valjan naziv."
            !validOib(oib.trim()) -> "OIB nije ispravan."
            address.isBlank() || city.isBlank() || address.length > 200 || city.length > 100 -> "Unesite valjanu adresu i mjesto."
            normalized.isNotEmpty() && !validCroatianIban(normalized) -> "IBAN nije ispravan."
            else -> ""
        }
        failed = message.isNotEmpty()
        if(!failed) {
            failed = !onSave(BusinessProfile(name.trim(),oib.trim(),address.trim(),city.trim(),vat,normalized))
            message = if(failed) "Spremanje nije uspjelo." else "Poslovni podaci su spremljeni."
        }
    }) { Text("Spremi podatke") }
}
