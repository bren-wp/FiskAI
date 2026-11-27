package hr.brendigo.fiskai

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import java.math.BigDecimal

@Composable
internal fun PosDraftScreen(products:List<Product>,contacts:List<Contact>,save:(Draft)->Boolean){
    var cart by remember{mutableStateOf(emptyMap<String,BigDecimal>())}
    var query by remember{mutableStateOf("")}
    var customer by remember{mutableStateOf<Contact?>(null)}
    var message by remember{mutableStateOf("")}
    val lines=products.mapNotNull{p->cart[p.id]?.let{q->Line(p.name,q,p.price,p.vat,p.kpd,"")}}
    Text("POS blagajna",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
    Panel{
        Text("Košarica za nacrt računa",fontWeight=FontWeight.Bold)
        DirectoryPicker(customer?.name ?: "Odaberi kupca",contacts.map{it.name}){customer=contacts[it]}
        OutlinedTextField(query,{query=it},label={Text("Naziv, šifra ili KPD")},singleLine=true,modifier=Modifier.fillMaxWidth())
        products.filter{it.name.contains(query,true)||it.code.contains(query,true)||it.kpd.contains(query)}.take(50).forEach{p->
            OutlinedButton(onClick={
                val current=cart[p.id]?:BigDecimal.ZERO
                if(current<BigDecimal("9999"))cart=cart+(p.id to current.add(BigDecimal.ONE))
            },modifier=Modifier.fillMaxWidth()){Text(p.name+" · "+euro(Line(p.name,BigDecimal.ONE,p.price,p.vat).total))}
        }
    }
    Panel{
        Text("Stavke",fontWeight=FontWeight.Bold)
        if(lines.isEmpty())Text("Košarica je prazna.")
        products.filter{cart.containsKey(it.id)}.forEach{p->
            Row{
                Column(Modifier.weight(1f)){Text(p.name);Text(cart[p.id]!!.toPlainString()+" × "+euro(p.price)+" bez PDV-a")}
                TextButton(onClick={
                    val q=cart[p.id]!!.subtract(BigDecimal.ONE)
                    cart=if(q.signum()<=0)cart-p.id else cart+(p.id to q)
                }){Text("−")}
            }
        }
        Text("Ukupno: "+euro(lines.fold(BigDecimal.ZERO){a,l->a+l.total}),fontWeight=FontWeight.Bold)
        Button(enabled=lines.isNotEmpty()&&customer!=null,onClick={
            val c=customer
            if(c!=null&&save(Draft(kind="Račun",customer=c.name,oib=c.oib,lines=lines)))cart=emptyMap()
            else message="Spremanje nije uspjelo."
        },modifier=Modifier.fillMaxWidth()){Text("Spremi nacrt računa")}
        if(message.isNotEmpty())Text(message)
        Text("Nacrt ne naplaćuje kupca, ne fiskalizira račun i ne razdužuje skladište.")
    }
}
