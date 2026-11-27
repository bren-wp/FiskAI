package hr.brendigo.fiskai

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun KpdLookup(onPick:(String)->Unit){
    var open by remember{mutableStateOf(false)}
    var endpoint by remember{mutableStateOf("")}
    var query by remember{mutableStateOf("")}
    var result by remember{mutableStateOf<KpdResult?>(null)}
    var busy by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    val scope=rememberCoroutineScope()
    TextButton(onClick={open=!open}){Text(if(open)"Zatvori KPD pretragu" else "Pretraži povezani KPD katalog")}
    if(open){
        Text("Unesite HTTPS adresu vlastitog FiskAI API-ja, s putanjom /api.php. Šalje se samo upit za klasifikaciju, bez podataka računa.")
        OutlinedTextField(endpoint,{endpoint=it;result=null},label={Text("Adresa API-ja")},enabled=!busy,singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(query,{query=it;result=null},label={Text("Naziv ili KPD šifra")},enabled=!busy,singleLine=true,modifier=Modifier.fillMaxWidth())
        Button(enabled=!busy&&endpoint.isNotBlank()&&query.isNotBlank(),onClick={
            val url=endpoint;val q=query
            busy=true;error="";result=null
            scope.launch {
                try{
                    val response=withContext(Dispatchers.IO){runCatching{KpdClient.search(url,q)}}
                    result=response.getOrNull()
                    if(response.isFailure)error="Katalog nije dostupan. Provjerite HTTPS adresu i uvoz KPD podataka."
                }finally{busy=false}
            }
        }){Text("Pretraži")}
        if(busy)LinearProgressIndicator(modifier=Modifier.fillMaxWidth())
        if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error)
        result?.let{data->
            Text("Podaci povezanog kataloga · uvoz "+data.imported)
            if(data.entries.isEmpty())Text("Nema rezultata.")
            data.entries.forEach{item->
                TextButton(onClick={onPick(item.code);open=false;result=null},modifier=Modifier.fillMaxWidth()){Text(item.code+" — "+item.name)}
            }
        }
    }
}
