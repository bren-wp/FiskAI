package hr.brendigo.fiskai

internal enum class CatalogKind { ALL, PRODUCTS, SERVICES, CATEGORIES }
internal enum class CatalogSort { NAME_ASC, NAME_DESC, PRICE_ASC, PRICE_DESC }

internal object ProductCatalog {
    fun categories(products:List<Product>):List<Pair<String,Int>> =
        products.filterNot{it.archived}.groupingBy{it.category.trim().ifEmpty{"Bez kategorije"}}.eachCount()
            .entries.sortedBy{it.key.lowercase(java.util.Locale.forLanguageTag("hr-HR"))}.map{it.key to it.value}
    fun select(products:List<Product>,kind:CatalogKind,query:String,category:String?,vat:String?,sort:CatalogSort):List<Product> {
        val filtered=products.filter{
            !it.archived && (kind !in setOf(CatalogKind.PRODUCTS,CatalogKind.SERVICES)||it.service==(kind==CatalogKind.SERVICES)) &&
                (category==null||it.category.trim().ifEmpty{"Bez kategorije"}==category) &&
                (vat==null||it.vat.stripTrailingZeros().toPlainString()==vat) &&
                listOf(it.name,it.code,it.kpd,it.category).any{field->field.contains(query.trim(),true)}
        }
        val collator=java.text.Collator.getInstance(java.util.Locale.forLanguageTag("hr-HR"))
        val byName=Comparator<Product>{a,b->collator.compare(a.name,b.name).let{if(it==0)a.id.compareTo(b.id)else it}}
        return when(sort){
            CatalogSort.NAME_ASC->filtered.sortedWith(byName)
            CatalogSort.NAME_DESC->filtered.sortedWith(byName.reversed())
            CatalogSort.PRICE_ASC->filtered.sortedWith(compareBy<Product>{it.price}.then(byName))
            CatalogSort.PRICE_DESC->filtered.sortedWith(compareByDescending<Product>{it.price}.then(byName))
        }
    }
}
