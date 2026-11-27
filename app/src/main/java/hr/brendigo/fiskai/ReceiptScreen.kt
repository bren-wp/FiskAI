package hr.brendigo.fiskai

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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
internal fun ReceiptScreen(page:String,business:BusinessProfile,onBusy:(Boolean)->Unit) {
    val context=LocalContext.current
    val store=remember(context){ReceiptStore(context.applicationContext)}
    val scope=rememberCoroutineScope()
    var data by remember{mutableStateOf(ReceiptWorkspace(emptyList(),emptyList()))}
    var busy by remember{mutableStateOf(true)}
    var message by remember{mutableStateOf("")}
    var paymentReceipt by remember{mutableStateOf<ReceiptEvidence?>(null)}
    var paymentSelection by remember{mutableStateOf(PaymentPreparationStore.Selection(emptyList(),false))}
    var choiceIndex by remember{mutableStateOf(0)}
    var selected by remember{mutableStateOf<BankEvidence?>(null)}
    var note by remember{mutableStateOf("")}
    var syncDialog by remember{mutableStateOf(false)}
    var remoteDocuments by remember(business.oib){mutableStateOf<List<DemoDocumentJournal.Document>?>(null)}
    var documentIndex by remember(business.oib){mutableStateOf(0)}
    var syncUrl by remember{mutableStateOf("")}
    var syncSubject by remember{mutableStateOf("")}
    var syncToken by remember{mutableStateOf("")}
    var enrollment by remember{mutableStateOf<DemoDeviceIdentity.Enrollment?>(null)}
    var manual by remember{mutableStateOf(false)}
    var reference by remember{mutableStateOf("")}
    var amount by remember{mutableStateOf("")}
    val today=remember{SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).apply{timeZone=TimeZone.getTimeZone("Europe/Zagreb")}.format(Date())}
    var date by remember{mutableStateOf(today)}
    var year by remember{mutableStateOf(today.take(4))}
    fun working(value:Boolean){busy=value;onBusy(value)}
    fun perform(action:()->String){
        working(true)
        scope.launch{try{
            withContext(Dispatchers.IO){runCatching{action() to store.read()}}
                .onSuccess{message=it.first;data=it.second;selected=null;manual=false;note=""}
                .onFailure{message="Spremanje nije potvrđeno. Provjerite podatke, format i postojeće zapise."}
        }finally{working(false)}}
    }
    fun choosePayment(row:ReceiptEvidence){
        working(true)
        scope.launch{try{
            withContext(Dispatchers.IO){runCatching{store.demoPaymentSelection(row.id)}}
                .onSuccess{paymentSelection=it;choiceIndex=0;paymentReceipt=row}
                .onFailure{message="Odgovarajući DEMO računi nisu dostupni."}
        }finally{working(false)}}
    }
    var documentExportSubject by remember{mutableStateOf("")}
    val documentExport=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->
        if(uri==null)working(false) else {
            val subject=documentExportSubject;documentExportSubject=""
            scope.launch{try{
                withContext(Dispatchers.IO){runCatching{
                    val envelope=DemoDocumentExchange.export(context,RecordDatabase.shared(context),business.oib,subject)
                    val output=context.contentResolver.openOutputStream(uri,"wt")?:error("Missing destination")
                    output.use{it.write(envelope.toByteArray(Charsets.UTF_8));it.flush()}
                }}.onSuccess{message="Potpisani DEMO dokumenti izvezeni su za pregled."}
                    .onFailure{message="Izvoz nije potvrđen. Provjerite dokumente i odredište."}
            }finally{working(false)}}
        }
    }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri==null)working(false) else perform{
            val bytes=context.contentResolver.openInputStream(uri)?.use(ReceiptRules::read)?:error("Missing file")
            "Uvezeno novih stavaka: "+store.importCsv(bytes,business)
        }
    }
    LaunchedEffect(store){working(true);try{
        withContext(Dispatchers.IO){runCatching{store.read()}}.onSuccess{data=it}.onFailure{message="Evidencija nije dostupna."}
    }finally{working(false)}}
    val receipts=data.receipts.filter{it.owner==business.oib}
    val banks=data.bank.filter{it.owner==business.oib}
    val ready=validOib(business.oib)
    Text(page,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
    if(!ready)Text("Najprije postavite OIB poslovnog subjekta.")
    if(page=="Bankovni izvodi"){
        Panel{
            Text("Uvoz CSV izvoda",fontWeight=FontWeight.Bold)
            Text("UTF-8; zaglavlje: id;datum;iban;iznos;valuta;opis. Datum GGGG-MM-DD, valuta EUR, stabilni ID bankovne transakcije. Izvoz drugih formata nije automatski prepoznat.")
            Button(enabled=!busy&&ready&&runCatching{ReceiptRules.account(business.iban)}.isSuccess,onClick={working(true);picker.launch(arrayOf("text/csv","text/plain","application/octet-stream"))},modifier=Modifier.fillMaxWidth()){Text("Uvezi CSV")}
            Text("Uvoz ne potvrđuje poslovni primitak. Samo pozitivne stavke možete ručno potvrditi.")
        }
        banks.sortedByDescending{it.date}.forEach{b->Panel{
            Text(b.description,fontWeight=FontWeight.Bold)
            Text(b.date+" · "+b.reference)
            Text(euro(ReceiptRules.amount(b.cents)))
            if(receipts.any{it.bankId==b.id})Text("Potvrđen primitak")
            else if(b.cents>0)OutlinedButton(enabled=!busy,onClick={selected=b;note=""}){Text("Potvrdi poslovni primitak")}
            else Text("Odljev – nije primitak")
        }}
        if(banks.isEmpty())Text("Nema uvezenih bankovnih stavaka.")
    } else {
        OutlinedTextField(year,{year=it.filter(Char::isDigit).take(4)},label={Text("Godina")},singleLine=true,modifier=Modifier.fillMaxWidth())
        val shown=receipts.filter{it.date.startsWith(year+"-")}.sortedWith(compareBy<ReceiptEvidence>{it.date}.thenBy{it.id})
        Panel {
            Text("Potvrđeni primici",fontWeight=FontWeight.Bold)
            Text(euro(ReceiptRules.amount(shown.sumOf{it.cents})),style=MaterialTheme.typography.headlineMedium)
            Text("Gotovina: "+euro(ReceiptRules.amount(shown.filter{it.method=="CASH"}.sumOf{it.cents})))
            Text("Transakcijski račun: "+euro(ReceiptRules.amount(shown.filter{it.method=="BANK"}.sumOf{it.cents})))
        }
        if(page=="Evidencija naplate"&&BuildConfig.DEMO_MODE)OutlinedButton(enabled=!busy&&ready,onClick={syncDialog=true;syncToken=""},modifier=Modifier.fillMaxWidth()){Text("Sinkroniziraj DEMO naplate")}
        if(page=="Evidencija naplate")Button(enabled=!busy&&ready,onClick={manual=true;note="";reference="";date=today;amount=""},modifier=Modifier.fillMaxWidth()){Text("Evidentiraj gotovinski primitak")}
        else Text("Radni pregled za pripremu KPR-a, nije službeni obrazac. Porezni tretman i korekcije potrebno je provjeriti.")
        shown.forEachIndexed{i,row->Panel{
            Text((i+1).toString()+". "+row.date+" · "+row.reference,fontWeight=FontWeight.Bold)
            Text(row.note);Text(if(row.method=="CASH")"Gotovina" else "Transakcijski račun")
            Text(euro(ReceiptRules.amount(row.cents)))
            if(BuildConfig.DEMO_MODE&&page=="Evidencija naplate")OutlinedButton(enabled=!busy&&ready,onClick={choosePayment(row)}){Text("Poveži s DEMO računom")}
        }}
        if(shown.isEmpty())Text("Nema potvrđenih primitaka za odabranu godinu.")
    }
    if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
    if(message.isNotEmpty())Text(message)
    selected?.let{row->AlertDialog(onDismissRequest={if(!busy)selected=null},title={Text("Potvrda poslovnog primitka")},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(euro(ReceiptRules.amount(row.cents))+" · "+row.date)
            Text("Potvrdite samo stvarni poslovni primitak. Vlastiti prijenosi i zajmovi ne knjiže se automatski. Zapis je nepromjenjiv.")
            OutlinedTextField(note,{note=it.take(512)},label={Text("Dokaz / namjena")})
        }
    },confirmButton={TextButton(enabled=!busy&&note.isNotBlank(),onClick={val evidence=note;perform{if(store.confirmBank(business,row.id,evidence))"Primitak je evidentiran." else "Primitak je već evidentiran."}}){Text("Potvrdi")}},dismissButton={TextButton(enabled=!busy,onClick={selected=null}){Text("Odustani")}})}
    paymentReceipt?.let{row->
        val choices=paymentSelection.choices
        val choice=choices.getOrNull(choiceIndex)
        AlertDialog(onDismissRequest={if(!busy)paymentReceipt=null},title={Text("Povezivanje naplate")},text={
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text(row.reference+" · "+euro(ReceiptRules.amount(row.cents)))
                if(paymentSelection.linked)Text("Naplata je već povezana s DEMO računom.")
                else Text("Odaberite odgovarajući DEMO račun. Veza je nepromjenjiva i ne šalje podatke Poreznoj upravi.")
                if(choice!=null){
                    Text("Račun "+choice.label,fontWeight=FontWeight.Bold)
                    Text(choice.customer);Text(choice.date)
                    Text("Preostalo za naplatu: "+euro(ReceiptRules.amount(choice.remainingCents)))
                    if(choices.size>1)Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        TextButton(enabled=!busy&&choiceIndex>0,onClick={choiceIndex--}){Text("Prethodni")}
                        TextButton(enabled=!busy&&choiceIndex<choices.lastIndex,onClick={choiceIndex++}){Text("Sljedeći")}
                    }
                    if(choices.size>1)Text((choiceIndex+1).toString()+" / "+choices.size)
                }else Text("Nema odgovarajućeg nepovezanog računa među posljednjih 100 DEMO računa.")
            }
        },confirmButton={
            if(choice!=null&&!paymentSelection.linked)TextButton(enabled=!busy,onClick={
                paymentReceipt=null
                perform{store.prepareDemoPayment(row.id,choice.id);"Naplata je povezana s DEMO računom."}
            }){Text("Poveži")}
        },dismissButton={TextButton(enabled=!busy,onClick={paymentReceipt=null}){Text("Zatvori")}})
    }
    if(syncDialog)AlertDialog(onDismissRequest={if(!busy){syncDialog=false;syncToken=""}},title={Text("DEMO sinkronizacija")},text={
        Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
            Text("Samo testni zapisi. Podatke šaljite isključivo na vlastiti pouzdani HTTPS server. Ovo nije fiskalizacija.")
            OutlinedTextField(syncUrl,{syncUrl=it.take(512)},label={Text("HTTPS adresa API-ja")},singleLine=true)
            OutlinedTextField(syncSubject,{syncSubject=it.take(36)},label={Text("ID testnog subjekta")},singleLine=true)
            OutlinedTextField(syncToken,{syncToken=it.take(64)},label={Text("Token uređaja")},singleLine=true,visualTransformation=PasswordVisualTransformation())
            OutlinedButton(enabled=!busy,onClick={
                working(true)
                scope.launch{try{
                    withContext(Dispatchers.IO){runCatching{DemoDeviceIdentity.enrollment()}}
                        .onSuccess{enrollment=it}
                        .onFailure{message="Ključ uređaja nije dostupan."}
                }finally{working(false)}}
            }){Text("Pripremi javni ključ uređaja")}
            OutlinedButton(enabled=!busy&&syncToken.matches(Regex("[0-9a-f]{64}"))&&syncUrl.isNotBlank()&&syncSubject.isNotBlank(),onClick={
                val url=syncUrl;val subject=syncSubject;val token=syncToken
                syncToken="";syncDialog=false;working(true)
                scope.launch{try{
                    withContext(Dispatchers.IO){runCatching{DemoDocumentJournal(context).synchronize(url,subject,business.oib,token)}}
                        .onSuccess{result->
                            message="Potvrđeni dokumenti: "+result.matched+" · Samo na serveru: "+result.remoteOnly.size
                            remoteDocuments=result.remoteOnly;documentIndex=0
                        }
                        .onFailure{message="Sinkronizacija dokumenata nije potvrđena. Lokalni računi ostaju nepromijenjeni."}
                }finally{working(false)}}
            }){Text("Sinkroniziraj DEMO dokumente")}
            OutlinedButton(enabled=!busy&&syncSubject.isNotBlank(),onClick={
                documentExportSubject=syncSubject;syncToken="";syncDialog=false
                working(true);documentExport.launch("FiskAI-DEMO-dokumenti.json")
            }){Text("Izvezi potpisane DEMO dokumente")}
            Text("Izvoz sadrži sadržaj testnih računa. Datoteka je potpisana, nije šifrirana. Koristite samo testne podatke.")
            enrollment?.let{key->
                Text(if(key.hardwareBacked)"Ključ zaštićen hardverom" else "Ključ zaštićen sustavom Android Keystore")
                TextButton(enabled=!busy,onClick={
                    val clipboard=context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("FiskAI javni ključ",key.publicKey))
                    message="Javni ključ kopiran. Predajte ga administratoru vlastitog servera."
                }){Text("Kopiraj javni ključ")}
            }
        }
    },confirmButton={TextButton(enabled=!busy&&syncToken.matches(Regex("[0-9a-f]{64}"))&&syncUrl.isNotBlank()&&syncSubject.isNotBlank(),onClick={
        val url=syncUrl;val subject=syncSubject;val token=syncToken
        syncToken="";syncDialog=false
        working(true)
        scope.launch{try{
            withContext(Dispatchers.IO){runCatching{DemoSyncJournal(context).synchronize(url,subject,business.oib,token,store.demoSyncEvents(business))}}
                .onSuccess{message="Potvrđeni lokalni zapisi: "+it.matched+" · Samo na serveru: "+it.remoteOnly}
                .onFailure{message="Prijenos nije u cijelosti potvrđen. Lokalni zapisi ostaju sačuvani. Provjerite adresu, pristup ili sukob zapisa pa pokušajte ponovno."}
        }finally{working(false)}}
    }){Text("Usporedi i sinkroniziraj")}},dismissButton={TextButton(enabled=!busy,onClick={syncDialog=false;syncToken=""}){Text("Odustani")}})
    remoteDocuments?.let{documents->
        val document=documents.getOrNull(documentIndex)
        AlertDialog(onDismissRequest={remoteDocuments=null},title={Text("Serverski DEMO dokumenti")},text={
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text("Pregled dokumenata koji nisu u lokalnoj evidenciji. Ne dodjeljuje im nove brojeve niti ih knjiži.")
                if(document==null)Text("Nema dodatnih dokumenata.")
                else{
                    val snapshot=org.json.JSONObject(document.snapshot)
                    Text(document.number,fontWeight=FontWeight.Bold)
                    Text(snapshot.optString("customer").take(120))
                    Text(euro(snapshot.optString("total")))
                    Text((documentIndex+1).toString()+" / "+documents.size)
                    Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){
                        TextButton(enabled=documentIndex>0,onClick={documentIndex--}){Text("Prethodni")}
                        TextButton(enabled=documentIndex<documents.lastIndex,onClick={documentIndex++}){Text("Sljedeći")}
                    }
                }
            }
        },confirmButton={TextButton(onClick={remoteDocuments=null}){Text("Zatvori")}})
    }
    if(manual)AlertDialog(onDismissRequest={if(!busy)manual=false},title={Text("Gotovinski primitak")},text={
        Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
            Text("Unesite stvarno primljeni iznos i jedinstvenu oznaku dokaza. Ovaj zapis nije fiskalni račun i ne može se mijenjati.")
            OutlinedTextField(reference,{reference=it.take(128)},label={Text("Oznaka dokaza")},singleLine=true)
            OutlinedTextField(date,{date=it.take(10)},label={Text("Datum GGGG-MM-DD")},singleLine=true)
            OutlinedTextField(amount,{amount=it.take(16)},label={Text("Iznos EUR")},singleLine=true)
            OutlinedTextField(note,{note=it.take(512)},label={Text("Opis / dokaz")})
        }
    },confirmButton={TextButton(enabled=!busy&&reference.isNotBlank()&&amount.isNotBlank()&&note.isNotBlank(),onClick={
        val ref=reference;val day=date;val value=amount;val proof=note
        perform{if(store.cash(business,ref,day,value,proof))"Primitak je evidentiran." else "Primitak je već evidentiran."}
    }){Text("Potvrdi")}},dismissButton={TextButton(enabled=!busy,onClick={manual=false}){Text("Odustani")}})
}
