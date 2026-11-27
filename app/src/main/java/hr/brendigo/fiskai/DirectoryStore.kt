package hr.brendigo.fiskai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.util.UUID

data class Contact(val id: String = UUID.randomUUID().toString(), val name: String, val oib: String, val email: String, val supplier: Boolean)
data class Product(val id: String = UUID.randomUUID().toString(), val name: String, val code: String, val price: BigDecimal, val vat: BigDecimal, val service: Boolean, val kpd: String = "", val retail: RetailPrice? = null, val priceHistory: List<RetailPrice> = emptyList(), val archived: Boolean = false, val category: String = "", val unitCode: String = "")

class DirectoryStore(context: Context) {
    private val prefs = SecureRecords(context, "fiskai_directory")
    private fun read(key: String) = JSONArray(prefs.getString(key, "[]") ?: "[]")
    fun contacts(): List<Contact> = decodeContacts(read("contacts"))
    fun products(): List<Product> = decodeProducts(read("products"))
    companion object {
    internal fun decodeContacts(arr: JSONArray): List<Contact> {
        return List(arr.length()) { i -> arr.getJSONObject(i).let { Contact(it.getString("id"), it.getString("name"), it.getString("oib"), it.getString("email"), it.getBoolean("supplier")) } }
    }
    internal fun decodeProducts(arr: JSONArray): List<Product> {
        return List(arr.length()) { i -> arr.getJSONObject(i).let { Product(it.getString("id"), it.getString("name"), it.getString("code"), BigDecimal(it.getString("price")), BigDecimal(it.getString("vat")), it.getBoolean("service"), it.optString("kpd"), it.optJSONObject("retail")?.let(::decodeRetail), it.optJSONArray("priceHistory")?.let { h -> List(h.length()) { index -> decodeRetail(h.getJSONObject(index)) } } ?: emptyList(), it.optBoolean("archived"), it.optString("category"), it.optString("unitCode")) } }
    }
    private fun decodeRetail(j:JSONObject)=RetailPrice(BigDecimal(j.getString("gross")),if(j.isNull("base"))null else BigDecimal(j.getString("base")),j.optString("referenceDate"),j.optString("evidence"),j.optBoolean("special"),j.getLong("recorded"))
    }
    fun saveContacts(items: List<Contact>): Boolean {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("oib", it.oib).put("email", it.email).put("supplier", it.supplier)) }
        return prefs.write("contacts", arr.toString())
    }
    private fun encodeRetail(p:RetailPrice)=JSONObject().put("gross",p.gross.toPlainString()).put("base",p.base?.toPlainString() ?: JSONObject.NULL).put("referenceDate",p.referenceDate).put("evidence",p.evidence).put("special",p.special).put("recorded",p.recorded)
    fun saveProducts(items: List<Product>): Boolean {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("code", it.code).put("price", it.price.toPlainString()).put("vat", it.vat.toPlainString()).put("service", it.service).put("kpd",it.kpd).put("retail",it.retail?.let(::encodeRetail) ?: JSONObject.NULL).put("priceHistory",JSONArray().apply { it.priceHistory.forEach { p -> put(encodeRetail(p)) } }).put("archived",it.archived).put("category",it.category).put("unitCode",it.unitCode)) }
        return prefs.write("products", arr.toString())
    }
}
