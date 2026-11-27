package hr.brendigo.fiskai

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class ProductCatalogTest {
    @Test fun categoriesFiltersSortAndArchive() {
        val products=listOf(
            Product(name="Laptop",code="P01",price=BigDecimal("799"),vat=BigDecimal("25"),service=false,category="Računala",unitCode="C62"),
            Product(name="Servis",code="U01",price=BigDecimal("45"),vat=BigDecimal("25"),service=true,category="Servis",unitCode="HUR"),
            Product(name="Papir",code="P02",price=BigDecimal("7.50"),vat=BigDecimal("13"),service=false),
            Product(name="Stari",code="P03",price=BigDecimal.ONE,vat=BigDecimal.ZERO,service=false,archived=true,category="Računala")
        )
        assertEquals(3,ProductCatalog.categories(products).sumOf{it.second})
        assertEquals(listOf("Papir","Servis","Laptop"),ProductCatalog.select(products,CatalogKind.ALL,"",null,null,CatalogSort.PRICE_ASC).map{it.name})
        assertEquals(listOf("Servis"),ProductCatalog.select(products,CatalogKind.SERVICES,"",null,"25",CatalogSort.NAME_ASC).map{it.name})
        assertEquals(listOf("Laptop"),ProductCatalog.select(products,CatalogKind.ALL,"P01","Računala","25",CatalogSort.NAME_ASC).map{it.name})
        assertTrue(ProductCatalog.select(products,CatalogKind.ALL,"Stari",null,null,CatalogSort.NAME_ASC).isEmpty())
    }
}
