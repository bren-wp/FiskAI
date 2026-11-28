package hr.brendigo.fiskai

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun BusinessReviewScreen(business:BusinessProfile,drafts:List<Draft>,products:List<Product>,
    fiscalStore:FiscalSetupStore,onNavigate:(String)->Unit) {
    val context=LocalContext.current
    var reload by remember{mutableStateOf(0)}
    val result by produceState<Result<List<BusinessReview.Finding>>?>(null,business,drafts,products,reload) {
        value=null
        value=withContext(Dispatchers.IO){
            runCatching{
                SecureRecords.withRecordLock {
                    BusinessReview.findings(business,drafts,products,fiscalStore.read(),
                        runCatching{ReceiptStore(context).read()}.getOrNull(),
                        runCatching{IncomingInvoiceStore(context).read()}.getOrNull(),
                        runCatching{Inventory(context).balance()}.getOrNull())
                }
            }
        }
    }
    Text("Poslovni pomoćnik",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
    Text("Lokalne provjere poslovnih podataka. Nema slanja podataka izvan uređaja. Rezultati nisu pravna ili porezna potvrda.")
    when {
        result==null -> LinearProgressIndicator(Modifier.fillMaxWidth())
        result?.isFailure==true -> Text("Provjera nije dostupna. Podaci nisu izmijenjeni.")
        else -> {
            val findings=result?.getOrThrow().orEmpty()
            if(findings.isEmpty())Text("Nema otvorenih stavki u obuhvatu ove provjere.")
            findings.forEach{item->Panel{
                Text(item.title,fontWeight=FontWeight.Bold);Text(item.detail)
                OutlinedButton(onClick={onNavigate(item.destination)},modifier=Modifier.fillMaxWidth()){Text("Otvori evidenciju")}
            }}
        }
    }
    OutlinedButton(enabled=result!=null,onClick={reload++},modifier=Modifier.fillMaxWidth()){Text("Ponovi provjeru")}
}
