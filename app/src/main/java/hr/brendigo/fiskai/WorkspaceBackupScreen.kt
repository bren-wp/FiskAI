package hr.brendigo.fiskai

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun WorkspaceBackupScreen(onBusy:(Boolean)->Unit,onRestored:()->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    fun working(value:Boolean) { busy=value;onBusy(value) }
    val exportPicker=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if(uri==null) { working(false);return@rememberLauncherForActivityResult }
        val secret=password.toCharArray()
        password="";confirmation=""
        scope.launch {
            try {
                val result=withContext(Dispatchers.IO) {
                    runCatching {
                        FiscalCutover.ensure(context,RecordDatabase.shared(context))
                        val plain=SecureRecords.exportWorkspace(context).toByteArray(Charsets.UTF_8)
                        try {
                            val encrypted=PortableBackup.encrypt(plain,secret)
                            context.contentResolver.openOutputStream(uri,"wt")?.use { it.write(encrypted) } ?: error("Datoteka nije dostupna")
                        } finally { plain.fill(0) }
                    }
                }
                status=if(result.isSuccess) "Šifrirana kopija je spremljena." else "Izvoz nije uspio. Nepotpunu datoteku nemojte koristiti."
            } finally { secret.fill('\u0000');working(false) }
        }
    }
    val importPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri==null) { working(false);return@rememberLauncherForActivityResult }
        val secret=password.toCharArray()
        password="";confirmation=""
        scope.launch {
            var restored=false
            try {
                val result=withContext(Dispatchers.IO) {
                    runCatching {
                        val file=context.contentResolver.openInputStream(uri)?.use(PortableBackup::readBounded) ?: error("Datoteka nije dostupna")
                        val plain=PortableBackup.decrypt(file,secret)
                        try {
                            FiscalCutover.ensure(context,RecordDatabase.shared(context))
                            SecureRecords.restoreWorkspace(context,plain.toString(Charsets.UTF_8))
                        } finally { plain.fill(0) }
                    }
                }
                restored=result.isSuccess
                status=if(restored) "Kopija je vraćena." else "Vraćanje nije uspjelo. Provjerite lozinku, datoteku i je li radni prostor prazan."
            } finally { secret.fill('\u0000');working(false) }
            if(restored)onRestored()
        }
    }
    Text("Sigurnosna kopija radnog prostora",style=MaterialTheme.typography.headlineSmall)
    Panel {
        Text("Kopija obuhvaća nacrte, kupce, dobavljače, artikle, poslovni subjekt i fiskalne postavke.")
        Text("Uključuje DEMO račune i brojače, stanje i povijest zaliha te CIS testni dnevnik. Uključuje i lokalne Room statuse zahtjeva. Certifikati i privatni ključevi nisu uključeni. Ne pokrećite istu obnovljenu kopiju na više uređaja.")
        Text("Vraćanje je moguće samo u prazan radni prostor iste vrste aplikacije (DEMO ili redovna). Postojeći podaci neće biti prepisani.")
        if(Build.VERSION.SDK_INT<26) {
            Text("Prijenosna kopija zahtijeva Android 8 ili noviji.")
        } else {
            OutlinedTextField(password,{if(it.length<=256)password=it},label={Text("Lozinka kopije (najmanje 12 znakova)")},visualTransformation=PasswordVisualTransformation(),singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(confirmation,{if(it.length<=256)confirmation=it},label={Text("Potvrdite lozinku za izvoz")},visualTransformation=PasswordVisualTransformation(),singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
            Button(enabled=!busy&&password.length>=12&&password==confirmation,onClick={working(true);exportPicker.launch("FiskAI-radni-prostor.fiskbak")},modifier=Modifier.fillMaxWidth()){Text("Izvezi šifriranu kopiju")}
            OutlinedButton(enabled=!busy&&password.length>=12,onClick={working(true);importPicker.launch(arrayOf("application/octet-stream","application/*"))},modifier=Modifier.fillMaxWidth()){Text("Vrati u prazan radni prostor")}
            if(busy) CircularProgressIndicator()
            if(status.isNotEmpty()) Text(status)
        }
    }
}
