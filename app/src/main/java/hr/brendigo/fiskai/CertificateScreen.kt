package hr.brendigo.fiskai

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun CertificateScreen(businessOib: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vault = remember(context) { TestCertificateVault(context.applicationContext) }
    var saved by remember { mutableStateOf<StoredCertificate?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var vaultError by remember { mutableStateOf(false) }
    var fromVault by remember { mutableStateOf(false) }
    var saveCopy by remember { mutableStateOf(false) }
    var removeConfirm by remember { mutableStateOf(false) }
    var uri by remember { mutableStateOf<Uri?>(null) }
    var password by remember { mutableStateOf("") }
    var info by remember { mutableStateOf<CertificateInfo?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    LaunchedEffect(vault) {
        val result = withContext(Dispatchers.IO) { runCatching { vault.summary() } }
        saved = result.getOrNull()
        vaultError = result.isFailure
        loaded = true
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selected ->
        if(selected != null) {
            uri = selected
            fromVault = false
            saveCopy = false
            password = ""
            info = null
            message = ""
        }
    }
    val formatter = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT,Locale.forLanguageTag("hr-HR")) }
    Text("Certifikati",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
    Panel {
        Text("Sef testnih certifikata",fontWeight=FontWeight.Bold)
        Text("Samo testni certifikat. Kopija je šifrirana ključem uređaja; lozinka se ne sprema. Izvorni certifikat čuvajte izvan aplikacije.")
        if(!loaded) LinearProgressIndicator(modifier=Modifier.fillMaxWidth())
        if(vaultError) Text("Sef nije čitljiv. Nije prepisan.",color=MaterialTheme.colorScheme.error)
        saved?.let { record ->
            Text("SHA-256: " + record.fingerprint)
            Text("Vrijedi do: " + formatter.format(Date(record.validTo)))
            OutlinedButton(enabled=!busy,onClick={
                uri=null; fromVault=true; saveCopy=false; password=""; info=null; message=""
            },modifier=Modifier.fillMaxWidth()) { Text("Provjeri spremljeni certifikat") }
            TextButton(enabled=!busy,onClick={removeConfirm=true}) { Text("Ukloni kopiju iz sefa") }
        }
        if(loaded && !vaultError && saved == null) Text("Nema spremljenog testnog certifikata.")
    }
    if(removeConfirm) AlertDialog(
        onDismissRequest={removeConfirm=false},title={Text("Ukloniti testni certifikat?")},
        text={Text("Uklanja se samo lokalna kopija. Izvorna datoteka ostaje nepromijenjena.")},
        confirmButton={TextButton(onClick={
            removeConfirm=false;busy=true
            scope.launch {
                try {
                    val ok=withContext(Dispatchers.IO){vault.clear()}
                    if(ok){saved=null;fromVault=false;password="";info=null;message=""}
                    else message="Uklanjanje nije uspjelo."
                } finally {busy=false}
            }
        }){Text("Ukloni")}},
        dismissButton={TextButton(onClick={removeConfirm=false}){Text("Odustani")}}
    )
    Panel {
        Text("Provjera aplikacijskog certifikata",fontWeight=FontWeight.Bold)
        Text("Odaberite .p12 ili .pfx datoteku do 2 MB. Provjera se obavlja na uređaju, bez slanja podataka.")
        if(businessOib.isNotBlank()) Text("OIB poslovnog subjekta: " + businessOib)
        OutlinedButton(enabled=!busy,onClick={picker.launch(arrayOf("*/*"))},modifier=Modifier.fillMaxWidth()) {
            Text(if(uri == null) "Odaberi certifikat" else "Odaberi drugu datoteku")
        }
        if(uri != null || fromVault) {
            OutlinedTextField(password,{password=it},label={Text("Lozinka certifikata")},visualTransformation=PasswordVisualTransformation(),singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
            if(!fromVault && loaded && !vaultError) Row {
                Checkbox(checked=saveCopy,onCheckedChange={saveCopy=it},enabled=!busy)
                Text(if(saved==null) "Spremi kopiju testnog certifikata u sef" else "Zamijeni spremljenu testnu kopiju")
            }
            Button(enabled=!busy,onClick={
                val source = uri
                val useSaved = fromVault
                val keep = saveCopy && !useSaved
                if(source == null && !useSaved) return@Button
                val secret = password.toCharArray()
                password = ""
                busy = true
                message = ""
                info = null
                scope.launch {
                    try {
                        val outcome = withContext(Dispatchers.IO) {
                            runCatching {
                                val bytes = if(useSaved) vault.readBytes() else context.contentResolver.openInputStream(requireNotNull(source))?.use { readBounded(it) } ?: error("File unavailable")
                                try {
                                    val checked = CertificateInspection.inspect(bytes,secret)
                                    if(keep) {
                                        require(checked.dateValid) { "Expired credential" }
                                        check(vault.save(bytes,checked)) { "Vault write failed" }
                                    }
                                    checked
                                } finally { bytes.fill(0) }
                            }
                        }
                        info = outcome.getOrNull()
                        if(outcome.isSuccess && keep) {
                            val cert = outcome.getOrThrow()
                            saved = StoredCertificate(cert.fingerprint,cert.validTo)
                            saveCopy=false
                        }
                        if(outcome.isFailure) message = "Provjera nije uspjela. Provjerite datoteku, lozinku i vrstu certifikata."
                    } finally { secret.fill('\u0000'); busy = false }
                }
            },modifier=Modifier.fillMaxWidth()) { Text(if(busy) "Provjera…" else "Provjeri certifikat") }
        }
        if(busy) LinearProgressIndicator(modifier=Modifier.fillMaxWidth())
        if(message.isNotEmpty()) Text(message,color=MaterialTheme.colorScheme.error)
    }
    info?.let { cert ->
        Panel {
            Text(if(cert.dateValid) "Vremenski valjan" else "Izvan razdoblja valjanosti",fontWeight=FontWeight.Bold,color=if(cert.dateValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            Text("Vlasnik: " + cert.subject)
            Text("Izdavatelj: " + cert.issuer)
            Text("Serijski broj: " + cert.serial)
            Text("Vrijedi od: " + formatter.format(Date(cert.validFrom)))
            Text("Vrijedi do: " + formatter.format(Date(cert.validTo)))
            Text("RSA: " + cert.keyBits + " bita")
            Text("SHA-256: " + cert.fingerprint)
            Text("Podudarnost ključa i certifikata provjerena je lokalnim potpisom.")
            OutlinedButton(onClick={ info=null;uri=null;fromVault=false;saveCopy=false;password="";message="" },modifier=Modifier.fillMaxWidth()) {Text("Zatvori pregled")}
        }
    }
    Panel {
        Text("Status fiskalizacije",fontWeight=FontWeight.Bold)
        Text("Provjera lanca povjerenja, opoziva, OIB-a i ovlaštenja Porezne uprave još nije provedena. Certifikat nije aktiviran za fiskalizaciju. Lozinka se ne sprema. Ako izričito odaberete sef, sprema se šifrirana PKCS#12 kopija koja sadrži privatni ključ. To nije aktivacija fiskalizacije.")
    }
}
