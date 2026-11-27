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
    var uri by remember { mutableStateOf<Uri?>(null) }
    var password by remember { mutableStateOf("") }
    var info by remember { mutableStateOf<CertificateInfo?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selected ->
        if(selected != null) {
            uri = selected
            password = ""
            info = null
            message = ""
        }
    }
    val formatter = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT,Locale.forLanguageTag("hr-HR")) }
    Text("Certifikati",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
    Panel {
        Text("Provjera aplikacijskog certifikata",fontWeight=FontWeight.Bold)
        Text("Odaberite .p12 ili .pfx datoteku do 2 MB. Provjera se obavlja na uređaju, bez slanja podataka.")
        if(businessOib.isNotBlank()) Text("OIB poslovnog subjekta: " + businessOib)
        OutlinedButton(enabled=!busy,onClick={picker.launch(arrayOf("*/*"))},modifier=Modifier.fillMaxWidth()) {
            Text(if(uri == null) "Odaberi certifikat" else "Odaberi drugu datoteku")
        }
        if(uri != null) {
            OutlinedTextField(password,{password=it},label={Text("Lozinka certifikata")},visualTransformation=PasswordVisualTransformation(),singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
            Button(enabled=!busy,onClick={
                val source = uri ?: return@Button
                val secret = password.toCharArray()
                password = ""
                busy = true
                message = ""
                info = null
                scope.launch {
                    try {
                        val outcome = withContext(Dispatchers.IO) {
                            runCatching {
                                val bytes = context.contentResolver.openInputStream(source)?.use { readBounded(it) } ?: error("File unavailable")
                                try { CertificateInspection.inspect(bytes,secret) } finally { bytes.fill(0) }
                            }
                        }
                        info = outcome.getOrNull()
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
            OutlinedButton(onClick={ info=null;uri=null;password="";message="" },modifier=Modifier.fillMaxWidth()) {Text("Zatvori pregled")}
        }
    }
    Panel {
        Text("Status fiskalizacije",fontWeight=FontWeight.Bold)
        Text("Provjera lanca povjerenja, opoziva, OIB-a i ovlaštenja Porezne uprave još nije provedena. Certifikat nije aktiviran za fiskalizaciju. Lozinka i privatni ključ ne spremaju se u aplikaciji.")
    }
}
