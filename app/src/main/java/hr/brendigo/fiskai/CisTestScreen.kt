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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.DateFormat
import java.util.Date

// Serialize all diagnostics within the process, including interrupted screen visits.
private val echoLock=Mutex()

@Composable
internal fun CisTestScreen() {
    val context=LocalContext.current
    val journal=remember(context){CisJournal(context.applicationContext)}
    val scope=rememberCoroutineScope()
    var history by remember { mutableStateOf(emptyList<CisAttempt>()) }
    var ready by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("") }
    LaunchedEffect(journal) {
        val result=withContext(Dispatchers.IO) { echoLock.withLock { runCatching { journal.recoverInterrupted();journal.history() } } }
        history=result.getOrDefault(emptyList())
        ready=result.isSuccess
        busy=false
        if(!ready) message="Dnevnik provjera nije dostupan."
    }
    Text("Test fiskalizacije",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
    Panel {
        Text("Testno okruženje CIS",fontWeight=FontWeight.Bold)
        Text("Provjera dostupnosti servisa. Ne šalje račun, OIB, certifikat ni druge poslovne podatke.")
        Text("cistest.apis-it.hr")
        Button(enabled=ready&&!busy,onClick={
            busy=true;message=""
            scope.launch {
                try {
                    val result=withContext(Dispatchers.IO) {
                        echoLock.withLock {
                            runCatching {
                                val id=journal.enqueue()
                                check(journal.claim(id))
                                val outcome=try { CisEcho.send(id) } catch(_:Exception) { "NETWORK_OR_PROTOCOL_ERROR" }
                                check(journal.finish(id,outcome))
                                journal.history()
                            }
                        }
                    }
                    if(result.isSuccess) history=result.getOrThrow()
                    else {ready=false;message="Provjera nije dovršena. Ponovno otvorite zaslon za oporavak dnevnika."}
                } finally {busy=false}
            }
        },modifier=Modifier.fillMaxWidth()) {Text(if(busy) "Provjera…" else "Provjeri vezu sa CIS-om")}
        if(busy) LinearProgressIndicator(modifier=Modifier.fillMaxWidth())
        if(message.isNotEmpty()) Text(message,color=MaterialTheme.colorScheme.error)
    }
    Text("Povijest provjera",fontWeight=FontWeight.Bold)
    if(ready&&history.isEmpty()) Text("Nema provjera.")
    history.forEach { attempt -> Panel {
        Text(when(attempt.state) {
            "VERIFIED" -> "Odgovor potvrđen"
            "QUEUED" -> "Čeka"
            "SENDING" -> "U tijeku"
            else -> "Nepotvrđen rezultat"
        },fontWeight=FontWeight.Bold)
        Text(DateFormat.getDateTimeInstance().format(Date(attempt.created)))
        Text("ID: "+attempt.id)
        attempt.outcome?.let { outcome ->
            Text(when(outcome) {
                "MATCH" -> "Poslužitelj je vratio očekivani sadržaj."
                "INTERRUPTED" -> "Prethodna provjera prekinuta."
                "INVALID_RESPONSE" -> "Odgovor nije odgovarao zahtjevu."
                "NETWORK_OR_PROTOCOL_ERROR" -> "Mrežna ili protokolarna pogreška."
                else -> outcome
            })
        }
    } }
    Panel { Text("Uspješna Echo provjera ne potvrđuje valjanost fiskalnog certifikata, potpis računa ni izdavanje JIR-a.") }
}
